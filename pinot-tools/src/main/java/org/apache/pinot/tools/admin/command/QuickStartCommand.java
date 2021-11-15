/**
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
package org.apache.pinot.tools.admin.command;

import org.apache.pinot.spi.plugin.PluginManager;
import org.apache.pinot.tools.Command;
import org.apache.pinot.tools.QuickStartBase;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;


@CommandLine.Command(name = "QuickStart")
public class QuickStartCommand extends AbstractBaseAdminCommand implements Command {
  private static final Logger LOGGER = LoggerFactory.getLogger(QuickStartCommand.class.getName());

  @CommandLine.Option(names = "-type", required = false,
      description = "Type of quickstart, supported: STREAM/BATCH/HYBRID")
  private String _type;

  @CommandLine.Option(names = {"-tmpDir", "-quickstartDir", "-dataDir"}, required = false,
      description = "Temp Directory to host quickstart data")
  private String _tmpDir;

  @CommandLine.Option(names = {"-help", "-h", "--h", "--help"}, required = false,
      description = "Print this message.")
  private boolean _help = false;

  @Override
  public boolean getHelp() {
    return _help;
  }

  @Override
  public String getName() {
    return "QuickStart";
  }

  public QuickStartCommand setType(String type) {
    _type = type;
    return this;
  }

  public String getTmpDir() {
    return _tmpDir;
  }

  public void setTmpDir(String tmpDir) {
    _tmpDir = tmpDir;
  }

  @Override
  public String toString() {
    return ("QuickStart -type " + _type);
  }

  @Override
  public void cleanup() {
  }

  @Override
  public String description() {
    return "Run Pinot QuickStart.";
  }

  public static QuickStartBase selectQuickStart(String type)
          throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
    Set<Class<? extends QuickStartBase>> quickStarts = allQuickStarts();
    for (Class<? extends QuickStartBase> quickStart : quickStarts) {
      QuickStartBase quickStartBase = quickStart.getDeclaredConstructor().newInstance();
      if (quickStartBase.types().contains(type)) {
        return quickStartBase;
      }
    }
    throw new UnsupportedOperationException("Unsupported QuickStart type: " + type + ". " +
            "Valid types are: " + errroMessageFor(quickStarts));
  }

  @Override
  public boolean execute() throws Exception {
    PluginManager.get().init();

    if (_type == null) {
      Set<Class<? extends QuickStartBase>> quickStarts = allQuickStarts();

      throw new UnsupportedOperationException("No QuickStart type provided. " +
              "Valid types are: " + errroMessageFor(quickStarts));
    }

    QuickStartBase quickstart = selectQuickStart(_type);

    if (_tmpDir != null) {
      quickstart.setTmpDir(_tmpDir);
    }
    quickstart.execute();
    return true;
  }

  private static List<String> errroMessageFor(Set<Class<? extends QuickStartBase>> quickStarts)
          throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
    List<String> validTypes = new ArrayList<>();
    for (Class<? extends QuickStartBase> quickStart : quickStarts) {
      validTypes.addAll(quickStart.getDeclaredConstructor().newInstance().types());
    }
    return validTypes;
  }

  private static Set<Class<? extends QuickStartBase>> allQuickStarts() {
    Reflections reflections = new Reflections("org.apache.pinot.tools");
    return reflections.getSubTypesOf(QuickStartBase.class);
  }
}
