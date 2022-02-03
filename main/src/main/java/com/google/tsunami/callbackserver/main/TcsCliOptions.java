/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.tsunami.callbackserver.main;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;

/** Command line arguments for TCS. */
@Parameters(separators = "=")
final class TcsCliOptions {
  @Parameter(
      names = "--custom-config",
      description =
          "The file path of custom configuration file (Default is tcs_config.yaml at the same"
              + " directory of the server jar file.).")
  public String customConfig = "tcs_config.yaml";

  /**
   * Creates {@code MainCliOptions} from command line arguments.
   *
   * @param args command line arguments
   * @return an instance of {@code MainCliOptions} with parsed values from command line
   */
  public static TcsCliOptions parseArgs(String... args) {
    TcsCliOptions options = new TcsCliOptions();
    JCommander jCommander = new JCommander(options);
    jCommander.setProgramName("TcsMain");
    try {
      jCommander.parse(args);
    } catch (ParameterException exception) {
      jCommander.usage();
      throw exception;
    }
    return options;
  }
}
