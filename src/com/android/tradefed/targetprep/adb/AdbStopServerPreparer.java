/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.targetprep.adb;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.GlobalConfiguration;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.IDeviceManager;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.BaseTargetPreparer;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.ITargetCleaner;
import com.android.tradefed.targetprep.SemaphoreTokenTargetPreparer;
import com.android.tradefed.targetprep.TargetSetupError;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import java.io.File;
import java.io.IOException;

/**
 * Target preparer to stop adb server on the host before and after running adb tests.
 *
 * <p>This preparer should be used with care as it stops and restart adb on the hosts. It should
 * usually be tight with {@link SemaphoreTokenTargetPreparer} to avoid other tests from running at
 * the same time.
 */
public class AdbStopServerPreparer extends BaseTargetPreparer implements ITargetCleaner {

    private static final String PATH = "PATH";
    private static final long CMD_TIMEOUT = 60000L;
    private static final String ANDROID_HOST_OUT = "ANDROID_HOST_OUT";

    private IRunUtil mRunUtil;
    private File mTmpDir;

    /** {@inheritDoc} */
    @Override
    public void setUp(ITestDevice device, IBuildInfo buildInfo)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {

        getDeviceManager().stopAdbBridge();

        // Kill the default adb server
        getRunUtil().runTimedCmd(CMD_TIMEOUT, "adb", "kill-server");

        File adb = null;
        if (getEnvironment(ANDROID_HOST_OUT) != null) {
            String hostOut = getEnvironment(ANDROID_HOST_OUT);
            adb = new File(hostOut, "bin/adb");
            if (adb.exists()) {
                adb.setExecutable(true);
            } else {
                adb = null;
            }
        }

        if (adb == null && buildInfo.getFile("adb") != null) {
            adb = buildInfo.getFile("adb");
            adb = renameAdbBinary(adb);
        }

        if (adb != null) {
            CLog.d("Restarting adb from %s", adb.getAbsolutePath());
            IRunUtil restartAdb = createRunUtil();
            restartAdb.setEnvVariable(PATH, adb.getAbsolutePath());
            CommandResult result =
                    restartAdb.runTimedCmd(CMD_TIMEOUT, adb.getAbsolutePath(), "start-server");
            if (!CommandStatus.SUCCESS.equals(result.getStatus())) {
                throw new TargetSetupError(
                        "Failed to restart adb with the build info one.",
                        device.getDeviceDescriptor());
            }
        } else {
            getRunUtil().runTimedCmd(CMD_TIMEOUT, "adb", "start-server");
            throw new TargetSetupError(
                    "Could not find a new version of adb to tests.", device.getDeviceDescriptor());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void tearDown(ITestDevice device, IBuildInfo buildInfo, Throwable e)
            throws DeviceNotAvailableException {
        FileUtil.recursiveDelete(mTmpDir);
        // Kill the test adb server
        getRunUtil().runTimedCmd(CMD_TIMEOUT, "adb", "kill-server");
        // Restart the one from the parent PATH (original one)
        getRunUtil().runTimedCmd(CMD_TIMEOUT, "adb", "start-server");
        // Restart device manager monitor
        getDeviceManager().restartAdbBridge();
    }

    @VisibleForTesting
    IDeviceManager getDeviceManager() {
        return GlobalConfiguration.getDeviceManagerInstance();
    }

    @VisibleForTesting
    IRunUtil createRunUtil() {
        return new RunUtil();
    }

    @VisibleForTesting
    String getEnvironment(String key) {
        return System.getenv(key);
    }

    private IRunUtil getRunUtil() {
        if (mRunUtil == null) {
            mRunUtil = createRunUtil();
        }
        return mRunUtil;
    }

    private File renameAdbBinary(File originalAdb) {
        try {
            mTmpDir = FileUtil.createTempDir("adb");
        } catch (IOException e) {
            CLog.e("Cannot create temp directory");
            FileUtil.recursiveDelete(mTmpDir);
            return null;
        }
        File renamedAdbBinary = new File(mTmpDir, "adb");
        if (!originalAdb.renameTo(renamedAdbBinary)) {
            CLog.e("Cannot rename adb binary");
            return null;
        }
        if (!renamedAdbBinary.setExecutable(true)) {
            CLog.e("Cannot set adb binary executable");
            return null;
        }
        return renamedAdbBinary;
    }
}
