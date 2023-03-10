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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.inject.AbstractModule;
import com.google.tsunami.callbackserver.server.common.monitoring.NoOpTcsEventsObserver;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class TcsCliOptionsTest {
  @Test
  public void getTcsEventsObserverModule_whenNoFlagSpecified_returnsNoOpObserverModule() {
    var options = TcsCliOptions.parseArgs();
    assertThat(options.getTcsEventsObserverModule())
        .isInstanceOf(NoOpTcsEventsObserver.Module.class);
  }

  @Test
  public void getTcsEventsObserverModule_whenNonExistentClass_throwsIllegalArgument() {
    var options =
        TcsCliOptions.parseArgs("--tcs-events-observer-module-class=a.non.existent.ObserverClass");
    var ex = assertThrows(IllegalArgumentException.class, options::getTcsEventsObserverModule);

    assertThat(ex).hasMessageThat().contains("it could not be loaded");
  }

  @Test
  public void getTcsEventsObserverModule_whenNonGuiceModuleClass_throwsIllegalArgument() {
    var options =
        TcsCliOptions.parseArgs(
            "--tcs-events-observer-module-class=" + TcsCliOptionsTest.class.getName());
    var ex = assertThrows(IllegalArgumentException.class, options::getTcsEventsObserverModule);

    assertThat(ex).hasMessageThat().contains("it does not extend");
  }

  @Test
  public void getTcsEventsObserverModule_whenNonConstructableClass_throwsIllegalArgument() {
    var options =
        TcsCliOptions.parseArgs(
            "--tcs-events-observer-module-class=" + NonInstantiableModuleClass.class.getName());
    var ex = assertThrows(IllegalArgumentException.class, options::getTcsEventsObserverModule);

    assertThat(ex).hasMessageThat().contains("it could not be constructed");
  }

  public static final class NonInstantiableModuleClass extends AbstractModule {
    public NonInstantiableModuleClass(int unusedParam) {}
  }
}
