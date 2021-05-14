/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tradefed.result;

import static org.junit.Assert.assertEquals;

import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.invoker.InvocationContext;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.testtype.suite.ModuleDefinition;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.util.Collections;

/** Unit tests for {@link ReportPassedTests}. */
@RunWith(JUnit4.class)
public class ReportPassedTestsTest {

    private String mExpectedString;
    private ITestLogger mLogger;

    private ReportPassedTests mReporter =
            new ReportPassedTests() {
                @Override
                void testLog(String toBeLogged) {
                    assertEquals(mExpectedString, toBeLogged);
                }
            };

    @Before
    public void setUp() {
        mLogger = Mockito.mock(ITestLogger.class);
        mReporter.setLogger(mLogger);
    }

    @Test
    public void testReport() {
        mExpectedString = "run-name\nrun-name2\n";
        mReporter.testRunStarted("run-name", 0);
        TestDescription tid = new TestDescription("class", "testName");
        mReporter.testStarted(tid);
        mReporter.testEnded(tid, Collections.emptyMap());
        mReporter.testRunEnded(0L, Collections.emptyMap());
        mReporter.testRunStarted("run-name2", 0);
        mReporter.testRunEnded(0L, Collections.emptyMap());

        mReporter.invocationEnded(0L);
    }

    @Test
    public void testReport_withRunFailure() {
        mExpectedString = "run-name2\n";
        mReporter.testRunStarted("run-name", 0);
        mReporter.testRunFailed("failed");
        mReporter.testRunEnded(0L, Collections.emptyMap());
        mReporter.testRunStarted("run-name2", 0);
        mReporter.testRunEnded(0L, Collections.emptyMap());

        mReporter.invocationEnded(0L);
    }

    @Test
    public void testReport_withTestFailure() {
        TestDescription testPass = new TestDescription("class", "testName1");
        mExpectedString = String.format("run-name %s\nrun-name2\n", testPass.toString());
        mReporter.testRunStarted("run-name", 2);
        TestDescription testFailed = new TestDescription("class", "testName0");
        mReporter.testStarted(testFailed);
        mReporter.testFailed(testFailed, "failed");
        mReporter.testEnded(testFailed, Collections.emptyMap());
        mReporter.testStarted(testPass);
        mReporter.testEnded(testPass, Collections.emptyMap());
        mReporter.testRunEnded(0L, Collections.emptyMap());
        mReporter.testRunStarted("run-name2", 0);
        mReporter.testRunEnded(0L, Collections.emptyMap());

        mReporter.invocationEnded(0L);
    }

    @Test
    public void testReport_module() {
        mExpectedString = "x86 module1\nrun-name2\n";
        mReporter.testModuleStarted(createModule("x86 module1"));
        mReporter.testRunStarted("run-name", 0);
        mReporter.testRunEnded(0L, Collections.emptyMap());
        mReporter.testModuleEnded();
        mReporter.testRunStarted("run-name2", 0);
        mReporter.testRunEnded(0L, Collections.emptyMap());

        mReporter.invocationEnded(0L);
    }

    @Test
    public void testReport_invocationFailed() {
        mExpectedString = "run-name\n";
        mReporter.testRunStarted("run-name", 0);
        TestDescription tid = new TestDescription("class", "testName");
        mReporter.testStarted(tid);
        mReporter.testEnded(tid, Collections.emptyMap());
        mReporter.testRunEnded(0L, Collections.emptyMap());
        mReporter.testRunStarted("run-name2", 0);
        mReporter.invocationFailed(FailureDescription.create("invoc failed"));
        mReporter.testRunEnded(0L, Collections.emptyMap());

        mReporter.invocationEnded(0L);
    }

    @Test
    public void testReport_module_invocationFailed() {
        mExpectedString = "x86 module1\n";
        mReporter.testModuleStarted(createModule("x86 module1"));
        mReporter.testRunStarted("run-name", 0);
        mReporter.testRunEnded(0L, Collections.emptyMap());
        mReporter.testModuleEnded();
        mReporter.testModuleStarted(createModule("x86 module2"));
        mReporter.testRunStarted("run-name2", 0);
        mReporter.testRunEnded(0L, Collections.emptyMap());
        mReporter.invocationFailed(FailureDescription.create("invoc failed"));
        mReporter.testModuleEnded();

        mReporter.invocationEnded(0L);
    }

    private IInvocationContext createModule(String id) {
        IInvocationContext context = new InvocationContext();
        context.addInvocationAttribute(ModuleDefinition.MODULE_ID, id);
        return context;
    }
}