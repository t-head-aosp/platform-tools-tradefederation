/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tradefed.lite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.Request;
import org.junit.runner.RunWith;
import org.junit.runner.notification.RunListener;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/** {@link com.android.tradefed.lite.HostUtils} */
@RunWith(JUnit4.class)
public class HostUtilsTest {
    /**
     * This test just quickly tries the fake execution method to ensure that it basically works.
     *
     * @throws Exception
     */
    @Test
    public void testFakeExecution() throws Exception {
        RunListener list = Mockito.mock(RunListener.class);
        Request req = Request.classes(SampleTests.class);
        Description desc = req.getRunner().getDescription();
        HostUtils.fakeExecution(desc, list);
        assertEquals("Test count should be 3", 3, desc.testCount());
        Mockito.verify(list, Mockito.times(desc.testCount()))
                .testStarted((Description) Mockito.anyObject());
        Mockito.verify(list, Mockito.times(desc.testCount()))
                .testFinished((Description) Mockito.anyObject());
    }

    /**
     * This test checks if our test class is correctly detected to bes annotated with the JUnit
     * annotation.
     */
    @Test
    public void testHasJUnit4Annotation() {
        assertTrue(
                "Has JUnit annotation on crafted test class",
                HostUtils.hasJUnit4Annotation(SampleTests.class));
    }
}
