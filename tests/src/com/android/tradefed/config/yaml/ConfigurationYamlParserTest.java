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
package com.android.tradefed.config.yaml;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.config.ConfigurationDef;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.targetprep.ITargetPreparer;
import com.android.tradefed.targetprep.PushFilePreparer;
import com.android.tradefed.targetprep.suite.SuiteApkInstaller;
import com.android.tradefed.testtype.AndroidJUnitTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.InputStream;

/** Unit tests for {@link ConfigurationYamlParser}. */
@RunWith(JUnit4.class)
public class ConfigurationYamlParserTest {

    private static final String YAML_TEST_CONFIG_1 = "/testconfigs/yaml/test-config.tf_yaml";

    private ConfigurationYamlParser mParser;
    private ConfigurationDef mConfigDef;

    @Before
    public void setUp() {
        mParser = new ConfigurationYamlParser();
        mConfigDef = new ConfigurationDef("test");
    }

    @Test
    public void testParseConfig() throws Exception {
        try (InputStream res = readFromRes(YAML_TEST_CONFIG_1)) {
            mParser.parse(mConfigDef, "source", res);
            // Create the configuration to test the flow
            IConfiguration config = mConfigDef.createConfiguration();
            config.validateOptions();
            // Test
            assertEquals(1, config.getTests().size());
            assertTrue(config.getTests().get(0) instanceof AndroidJUnitTest);
            assertEquals(
                    "android.package",
                    ((AndroidJUnitTest) config.getTests().get(0)).getPackageName());

            // Dependencies
            // apk dependencies
            assertEquals(2, config.getTargetPreparers().size());
            ITargetPreparer installApk = config.getTargetPreparers().get(0);
            assertTrue(installApk instanceof SuiteApkInstaller);
            assertThat(((SuiteApkInstaller) installApk).getTestsFileName())
                    .containsExactly(
                            new File("test.apk"), new File("test2.apk"), new File("test1.apk"));
            // device file dependencies
            ITargetPreparer pushFile = config.getTargetPreparers().get(1);
            assertTrue(pushFile instanceof PushFilePreparer);
            assertThat(((PushFilePreparer) pushFile).getPushSpecs(null))
                    .containsExactly(
                            "/sdcard/",
                            new File("tobepushed2.txt"),
                            "/sdcard",
                            new File("tobepushed.txt"));
        }
    }

    private InputStream readFromRes(String resourceFile) {
        return getClass().getResourceAsStream(resourceFile);
    }
}