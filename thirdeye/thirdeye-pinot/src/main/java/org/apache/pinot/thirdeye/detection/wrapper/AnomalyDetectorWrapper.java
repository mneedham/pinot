/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pinot.thirdeye.detection.wrapper;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.commons.collections.MapUtils;
import org.apache.pinot.thirdeye.anomaly.detection.DetectionJobSchedulerUtils;
import org.apache.pinot.thirdeye.common.time.TimeGranularity;
import org.apache.pinot.thirdeye.common.time.TimeSpec;
import org.apache.pinot.thirdeye.dataframe.DoubleSeries;
import org.apache.pinot.thirdeye.dataframe.util.MetricSlice;
import org.apache.pinot.thirdeye.datalayer.dto.AnomalyFunctionDTO;
import org.apache.pinot.thirdeye.datalayer.dto.DatasetConfigDTO;
import org.apache.pinot.thirdeye.datalayer.dto.DetectionConfigDTO;
import org.apache.pinot.thirdeye.datalayer.dto.MergedAnomalyResultDTO;
import org.apache.pinot.thirdeye.datalayer.dto.MetricConfigDTO;
import org.apache.pinot.thirdeye.detection.DataProvider;
import org.apache.pinot.thirdeye.detection.DetectionPipelineException;
import org.apache.pinot.thirdeye.detection.DetectionPipeline;
import org.apache.pinot.thirdeye.detection.DetectionPipelineResult;
import org.apache.pinot.thirdeye.detection.DetectionUtils;
import org.apache.pinot.thirdeye.detection.spi.components.AnomalyDetector;
import org.apache.pinot.thirdeye.detection.spi.exception.DetectorDataInsufficientException;
import org.apache.pinot.thirdeye.rootcause.impl.MetricEntity;
import org.apache.pinot.thirdeye.util.ThirdEyeUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Interval;
import org.joda.time.Period;
import org.omg.SendingContext.RunTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.pinot.thirdeye.dataframe.util.DataFrameUtils.*;


/**
 * Anomaly Detector Wrapper. This wrapper runs a anomaly detector and return the anomalies.
 * Optionally set the detection window to be moving window fashion. This wrapper will call detection multiple times with
 * sliding window. Each sliding window start time and end time is aligned to the data granularity. Each window size is set by the spec.
 */
public class AnomalyDetectorWrapper extends DetectionPipeline {
  private static final String PROP_METRIC_URN = "metricUrn";

  // moving window detection properties
  private static final String PROP_MOVING_WINDOW_DETECTION = "isMovingWindowDetection";
  private static final String PROP_WINDOW_DELAY = "windowDelay";
  private static final String PROP_WINDOW_DELAY_UNIT = "windowDelayUnit";
  private static final String PROP_WINDOW_SIZE = "windowSize";
  private static final String PROP_WINDOW_UNIT = "windowUnit";
  private static final String PROP_FREQUENCY = "frequency";
  private static final String PROP_DETECTOR = "detector";
  private static final String PROP_DETECTOR_COMPONENT_NAME = "detectorComponentName";
  private static final String PROP_TIMEZONE = "timezone";
  private static final String PROP_BUCKET_PERIOD = "bucketPeriod";
  private static final String PROP_CACHE_PERIOD_LOOKBACK = "cachingPeriodLookback";
  private static final long DEFAULT_CACHING_PERIOD_LOOKBACK = -1;
  private static final long CACHING_PERIOD_LOOKBACK_DAILY = TimeUnit.DAYS.toMillis(90);
  private static final long CACHING_PERIOD_LOOKBACK_HOURLY = TimeUnit.DAYS.toMillis(60);
  // disable minute level cache warm up
  private static final long CACHING_PERIOD_LOOKBACK_MINUTELY = -1;
  // fail detection job if it failed successively for the first 5 windows
  private static final long EARLY_TERMINATE_WINDOW = 5;

  private static final Logger LOG = LoggerFactory.getLogger(AnomalyDetectorWrapper.class);

  private final String metricUrn;
  private final AnomalyDetector anomalyDetector;

  private final int windowDelay;
  private final TimeUnit windowDelayUnit;
  private int windowSize;
  private TimeUnit windowUnit;
  private final MetricConfigDTO metric;
  private final MetricEntity metricEntity;
  private final boolean isMovingWindowDetection;
  // need to specify run frequency for minute level detection. Used for moving monitoring window alignment, default to be 15 minutes.
  private final TimeGranularity functionFrequency;
  private final String detectorName;
  private final DatasetConfigDTO dataset;
  private final DateTimeZone dateTimeZone;
  private Period bucketPeriod;
  private final long cachingPeriodLookback;

  public AnomalyDetectorWrapper(DataProvider provider, DetectionConfigDTO config, long startTime, long endTime) {
    super(provider, config, startTime, endTime);

    this.metricUrn = MapUtils.getString(config.getProperties(), PROP_METRIC_URN);
    this.metricEntity = MetricEntity.fromURN(this.metricUrn);
    this.metric = provider.fetchMetrics(Collections.singleton(this.metricEntity.getId())).get(this.metricEntity.getId());

    Preconditions.checkArgument(this.config.getProperties().containsKey(PROP_DETECTOR));
    this.detectorName = DetectionUtils.getComponentName(MapUtils.getString(config.getProperties(), PROP_DETECTOR));
    Preconditions.checkArgument(this.config.getComponents().containsKey(this.detectorName));
    this.anomalyDetector = (AnomalyDetector) this.config.getComponents().get(this.detectorName);

    // emulate moving window or now
    this.isMovingWindowDetection = MapUtils.getBooleanValue(config.getProperties(), PROP_MOVING_WINDOW_DETECTION, false);
    // delays to wait for data becomes available
    this.windowDelay = MapUtils.getIntValue(config.getProperties(), PROP_WINDOW_DELAY, 0);
    // window delay unit
    this.windowDelayUnit = TimeUnit.valueOf(MapUtils.getString(config.getProperties(), PROP_WINDOW_DELAY_UNIT, "DAYS"));
    // detection window size
    this.windowSize = MapUtils.getIntValue(config.getProperties(), PROP_WINDOW_SIZE, 1);
    // detection window unit
    this.windowUnit = TimeUnit.valueOf(MapUtils.getString(config.getProperties(), PROP_WINDOW_UNIT, "DAYS"));
    // run frequency, used to determine moving windows for minute-level detection
    Map<String, Object> frequency = MapUtils.getMap(config.getProperties(), PROP_FREQUENCY, Collections.emptyMap());
    this.functionFrequency = new TimeGranularity(MapUtils.getIntValue(frequency, "size", 15), TimeUnit.valueOf(MapUtils.getString(frequency, "unit", "MINUTES")));

    MetricConfigDTO metricConfigDTO = this.provider.fetchMetrics(Collections.singletonList(this.metricEntity.getId())).get(this.metricEntity.getId());
    this.dataset = this.provider.fetchDatasets(Collections.singletonList(metricConfigDTO.getDataset()))
        .get(metricConfigDTO.getDataset());
    // date time zone for moving windows. use dataset time zone as default
    this.dateTimeZone = DateTimeZone.forID(MapUtils.getString(config.getProperties(), PROP_TIMEZONE, this.dataset.getTimezone()));

    String bucketStr = MapUtils.getString(config.getProperties(), PROP_BUCKET_PERIOD);
    this.bucketPeriod = bucketStr == null ? this.getBucketSizePeriodForDataset() : Period.parse(bucketStr);
    this.cachingPeriodLookback = config.getProperties().containsKey(PROP_CACHE_PERIOD_LOOKBACK) ?
        MapUtils.getLong(config.getProperties(), PROP_CACHE_PERIOD_LOOKBACK) : getCachingPeriodLookback(this.dataset.bucketTimeGranularity());

    speedUpMinuteLevelDetection();
  }

  @Override
  public DetectionPipelineResult run() throws Exception {
    // pre-cache time series with default granularity. this is used in multiple places:
    // 1. get the last time stamp for the time series.
    // 2. to calculate current values and  baseline values for the anomalies detected
    // 3. anomaly detection current and baseline time series value
    if (this.cachingPeriodLookback >= 0) {
      MetricSlice cacheSlice = MetricSlice.from(this.metricEntity.getId(), startTime - cachingPeriodLookback, endTime,
          this.metricEntity.getFilters());
      this.provider.fetchTimeseries(Collections.singleton(cacheSlice));
    }

    List<Interval> monitoringWindows = this.getMonitoringWindows();
    List<MergedAnomalyResultDTO> anomalies = new ArrayList<>();
    int totalWindows = monitoringWindows.size();
    int successWindows = 0;
    // The last exception of the detection windows. It will be thrown out to upper level.
    Exception lastException = null;
    for (int i = 0; i < totalWindows; i++) {
      checkEarlyStop(totalWindows, successWindows, i, lastException);

      // run detection
      Interval window = monitoringWindows.get(i);
      List<MergedAnomalyResultDTO> anomaliesForOneWindow = new ArrayList<>();
      try {
        LOG.info("[Pipeline] start detection for config {} metricUrn {} window ({}/{}) - start {} end {}",
            config.getId(), metricUrn, i + 1, monitoringWindows.size(), window.getStart(), window.getEnd());
        long ts = System.currentTimeMillis();
        anomaliesForOneWindow = anomalyDetector.runDetection(window, this.metricUrn);
        LOG.info("[Pipeline] end detection for config {} metricUrn {} window ({}/{}) - start {} end {} used {} milliseconds, detected {} anomalies",
            config.getId(), metricUrn, i + 1, monitoringWindows.size(), window.getStart(), window.getEnd(),
            System.currentTimeMillis() - ts, anomaliesForOneWindow.size());
        successWindows++;
      }
      catch (DetectorDataInsufficientException e) {
        LOG.warn("[DetectionConfigID{}] Insufficient data ro run detection for window {} to {}.", this.config.getId(), window.getStart(), window.getEnd());
        lastException = e;
      }
      catch (Exception e) {
        LOG.warn("[DetectionConfigID{}] detecting anomalies for window {} to {} failed.", this.config.getId(), window.getStart(), window.getEnd(), e);
        lastException = e;
      }
      anomalies.addAll(anomaliesForOneWindow);
    }

    checkMovingWindowDetectionStatus(totalWindows, successWindows, lastException);

    for (MergedAnomalyResultDTO anomaly : anomalies) {
      anomaly.setDetectionConfigId(this.config.getId());
      anomaly.setMetricUrn(this.metricUrn);
      anomaly.setMetric(this.metric.getName());
      anomaly.setCollection(this.metric.getDataset());
      anomaly.setDimensions(DetectionUtils.toFilterMap(this.metricEntity.getFilters()));
      anomaly.getProperties().put(PROP_DETECTOR_COMPONENT_NAME, this.detectorName);
    }
    long lastTimeStamp = this.getLastTimeStamp();
    return new DetectionPipelineResult(anomalies.stream().filter(anomaly -> anomaly.getEndTime() <= lastTimeStamp).collect(
        Collectors.toList()), lastTimeStamp);
  }

  private void checkEarlyStop(int totalWindows, int successWindows, int i, Exception lastException) throws DetectionPipelineException {
    // if the first certain number of windows all failed, throw an exception
    if (i == EARLY_TERMINATE_WINDOW && successWindows == 0) {
      throw new DetectionPipelineException(String.format(
          "Successive first %d/%d detection windows failed for config %d metricUrn %s for monitoring window %d to %d. Discard remaining windows",
          EARLY_TERMINATE_WINDOW, totalWindows, config.getId(), metricUrn, this.getStartTime(), this.getEndTime()), lastException);
    }
  }

  private void checkMovingWindowDetectionStatus(int totalWindows, int successWindows, Exception lastException) throws DetectionPipelineException {
    // if all moving window detection failed, throw an exception
    if (successWindows == 0 && totalWindows > 0) {
      throw new DetectionPipelineException(String.format(
          "Detection failed for all windows for detection config id %d detector %s for monitoring window %d to %d.",
          this.config.getId(), this.detectorName, this.getStartTime(), this.getEndTime()), lastException);
    }
  }

  // guess-timate next time stamp
  // there are two cases. If the data is complete, next detection starts from the end time of this detection
  // If data is incomplete, next detection starts from the latest available data's time stamp plus the one time granularity.
  long getLastTimeStamp() {
    long end = this.endTime;
    if (this.dataset != null) {
      MetricSlice metricSlice = MetricSlice.from(this.metricEntity.getId(),
          this.startTime,
          this.endTime,
          this.metricEntity.getFilters());
      DoubleSeries timestamps = this.provider.fetchTimeseries(Collections.singleton(metricSlice)).get(metricSlice).getDoubles(COL_TIME);
      if (timestamps.size() == 0) {
        // no data available, don't update time stamp
        return -1;
      }
      Period period = dataset.bucketTimeGranularity().toPeriod();
      DateTimeZone timezone = DateTimeZone.forID(dataset.getTimezone());
      long lastTimestamp = timestamps.getLong(timestamps.size() - 1);

      end = new DateTime(lastTimestamp, timezone).plus(period).getMillis();
    }

    // truncate at analysis end time
    return Math.min(end, this.endTime);
  }

  // get a list of the monitoring window, if no sliding window used, use start time and end time as window
  List<Interval> getMonitoringWindows() {
    if (this.isMovingWindowDetection) {
      try{
        Period windowDelayPeriod = DetectionUtils.periodFromTimeUnit(windowDelay, windowDelayUnit);
        Period windowSizePeriod = DetectionUtils.periodFromTimeUnit(windowSize, windowUnit);
        List<Interval> monitoringWindows = new ArrayList<>();
        List<DateTime> monitoringWindowEndTimes = getMonitoringWindowEndTimes();
        for (DateTime monitoringEndTime : monitoringWindowEndTimes) {
          DateTime endTime = monitoringEndTime.minus(windowDelayPeriod);
          DateTime startTime = endTime.minus(windowSizePeriod);
          monitoringWindows.add(new Interval(startTime, endTime));
        }
        for (Interval window : monitoringWindows){
          LOG.info("Will run detection in window {}", window);
        }
        return monitoringWindows;
      } catch (Exception e) {
        LOG.info("can't generate moving monitoring windows, calling with single detection window", e);
      }
    }
    return Collections.singletonList(new Interval(startTime, endTime));
  }

  private long getCachingPeriodLookback(TimeGranularity granularity) {
    long period;
    switch (granularity.getUnit()) {
      case DAYS:
        period = CACHING_PERIOD_LOOKBACK_DAILY;
        break;
      case HOURS:
        period = CACHING_PERIOD_LOOKBACK_HOURLY;
        break;
      case MINUTES:
        period = CACHING_PERIOD_LOOKBACK_MINUTELY;
        break;
      default:
        period = DEFAULT_CACHING_PERIOD_LOOKBACK;
    }
    return period;
  }

  // get the list of monitoring window end times
  private List<DateTime> getMonitoringWindowEndTimes() {
    List<DateTime> endTimes = new ArrayList<>();

    // get current hour/day, depending on granularity of dataset,
    DateTime currentEndTime = new DateTime(getBoundaryAlignedTimeForDataset(new DateTime(endTime, dateTimeZone)), dateTimeZone);

    DateTime lastDateTime = new DateTime(getBoundaryAlignedTimeForDataset(new DateTime(startTime, dateTimeZone)), dateTimeZone);
    while (lastDateTime.isBefore(currentEndTime)) {
      lastDateTime = lastDateTime.plus(this.bucketPeriod);
      endTimes.add(lastDateTime);
    }
    return endTimes;
  }

  /**
   * round this time to earlier boundary, depending on granularity of dataset
   * e.g. 12:15pm on HOURLY dataset should be treated as 12pm
   * any dataset with granularity finer than HOUR, will be rounded as per function frequency (assumption is that this is in MINUTES)
   * so 12.53 on 5 MINUTES dataset, with function frequency 15 MINUTES will be rounded to 12.45
   * See also {@link DetectionJobSchedulerUtils#getBoundaryAlignedTimeForDataset(DateTime, TimeUnit)}
   */
  private long getBoundaryAlignedTimeForDataset(DateTime currentTime) {
    TimeSpec timeSpec = ThirdEyeUtils.getTimeSpecFromDatasetConfig(dataset);
    TimeUnit dataUnit = timeSpec.getDataGranularity().getUnit();

    // For nMINUTE level datasets, with frequency defined in nMINUTES in the function, (make sure size doesnt exceed 30 minutes, just use 1 HOUR in that case)
    // Calculate time periods according to the function frequency
    if (dataUnit.equals(TimeUnit.MINUTES) || dataUnit.equals(TimeUnit.MILLISECONDS) || dataUnit.equals(
        TimeUnit.SECONDS)) {
      if (functionFrequency.getUnit().equals(TimeUnit.MINUTES) && (functionFrequency.getSize() <= 30)) {
        int minuteBucketSize = functionFrequency.getSize();
        int roundedMinutes = (currentTime.getMinuteOfHour() / minuteBucketSize) * minuteBucketSize;
        currentTime = currentTime.withTime(currentTime.getHourOfDay(), roundedMinutes, 0, 0);
      } else {
        currentTime = DetectionJobSchedulerUtils.getBoundaryAlignedTimeForDataset(currentTime,
            TimeUnit.HOURS); // default to HOURS
      }
    } else {
      currentTime = DetectionJobSchedulerUtils.getBoundaryAlignedTimeForDataset(currentTime, dataUnit);
    }

    return currentTime.getMillis();
  }

  /**
   * get bucket size in millis, according to data granularity of dataset
   * Bucket size are 1 HOUR for hourly, 1 DAY for daily
   * For MINUTE level data, bucket size is calculated based on anomaly function frequency
   * See also {@link DetectionJobSchedulerUtils#getBucketSizePeriodForDataset(DatasetConfigDTO, AnomalyFunctionDTO)} (DateTime, TimeUnit)}
   */
  public Period getBucketSizePeriodForDataset() {
    Period bucketSizePeriod = null;
    TimeSpec timeSpec = ThirdEyeUtils.getTimeSpecFromDatasetConfig(dataset);
    TimeUnit dataUnit = timeSpec.getDataGranularity().getUnit();

    // For nMINUTE level datasets, with frequency defined in nMINUTES in the function, (make sure size doesnt exceed 30 minutes, just use 1 HOUR in that case)
    // Calculate time periods according to the function frequency
    if (dataUnit.equals(TimeUnit.MINUTES) || dataUnit.equals(TimeUnit.MILLISECONDS) || dataUnit.equals(
        TimeUnit.SECONDS)) {
      if (functionFrequency.getUnit().equals(TimeUnit.MINUTES) && (functionFrequency.getSize() <= 30)) {
        bucketSizePeriod = new Period(0, 0, 0, 0, 0, functionFrequency.getSize(), 0, 0);
      } else {
        bucketSizePeriod = DetectionJobSchedulerUtils.getBucketSizePeriodForUnit(TimeUnit.HOURS); // default to 1 HOUR
      }
    } else {
      bucketSizePeriod = DetectionJobSchedulerUtils.getBucketSizePeriodForUnit(dataUnit);
    }
    return bucketSizePeriod;
  }

  /**
   * Speed up minute level detection.
   *
   * It will generate lots of small windows if the bucket size smaller than 15 minutes and detection window larger than 1 day.
   * This optimization is to change the bucket period, window size and window unit to 1 day.
   * Please note we need to change all the three parameters together since the detection window is:
   * [bucketPeriod_end - windowSize * windowUnit, bucketPeriod_end]
   *
   * It is possible to have bucketPeriod as 5 minutes but windowSize is 6 hours.
   */
  private void speedUpMinuteLevelDetection() {
    if (bucketPeriod.toStandardDuration().getMillis() <= Period.minutes(15).toStandardDuration().getMillis()
        && endTime - startTime >= Period.days(1).toStandardDuration().getMillis()) {
      bucketPeriod = Period.days(1);
      windowSize = 1;
      windowUnit = TimeUnit.DAYS;
    }
  }
}
