/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.tradefed.targetprep;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.invoker.TestInformation;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class RunOnSecondaryUserTargetPreparerTest {

    private static final String CREATED_USER_2_MESSAGE = "Created user id 2";

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private TestInformation mTestInfo;

    private RunOnSecondaryUserTargetPreparer mPreparer;
    private OptionSetter mOptionSetter;

    @Before
    public void setUp() throws Exception {
        mPreparer = new RunOnSecondaryUserTargetPreparer();
        mOptionSetter = new OptionSetter(mPreparer);
    }

    @Test
    public void setUp_createsAndStartsSecondaryUser() throws Exception {
        String expectedCreateUserCommand = "pm create-user secondary";
        String expectedStartUserCommand = "am start-user -w 2";
        when(mTestInfo.getDevice().executeShellCommand(expectedCreateUserCommand))
                .thenReturn(CREATED_USER_2_MESSAGE);

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.getDevice()).executeShellCommand(expectedCreateUserCommand);
        verify(mTestInfo.getDevice()).executeShellCommand(expectedStartUserCommand);
    }

    @Test
    public void setUp_setsRunTestsAsUser() throws Exception {
        String expectedCreateUserCommand = "pm create-user secondary";
        when(mTestInfo.getDevice().executeShellCommand(expectedCreateUserCommand))
                .thenReturn(CREATED_USER_2_MESSAGE);

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.properties())
                .put(RunOnSecondaryUserTargetPreparer.RUN_TESTS_AS_USER_KEY, "2");
    }

    @Test
    public void setUp_installsPackagesInSecondaryUser() throws Exception {
        String expectedCreateUserCommand = "pm create-user secondary";
        when(mTestInfo.getDevice().executeShellCommand(expectedCreateUserCommand))
                .thenReturn(CREATED_USER_2_MESSAGE);
        mOptionSetter.setOptionValue(
                RunOnSecondaryUserTargetPreparer.TEST_PACKAGE_NAME_OPTION,
                "com.android.testpackage");

        mPreparer.setUp(mTestInfo);

        verify(mTestInfo.getDevice())
                .executeShellCommand("pm install-existing --user 2 com.android.testpackage");
    }

    @Test
    public void tearDown_removesWorkUser() throws Exception {
        when(mTestInfo.properties().get(RunOnSecondaryUserTargetPreparer.RUN_TESTS_AS_USER_KEY))
                .thenReturn("2");

        mPreparer.tearDown(mTestInfo, /* throwable= */ null);

        verify(mTestInfo.getDevice()).removeUser(2);
    }
}
