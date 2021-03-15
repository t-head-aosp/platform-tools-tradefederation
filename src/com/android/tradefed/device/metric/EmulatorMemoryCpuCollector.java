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

package com.android.tradefed.device.metric;

import com.android.tradefed.device.IManagedTestDevice;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil;
import com.android.tradefed.metrics.proto.MetricMeasurement;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.RunUtil;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link IMetricCollector} that collects emulator cpu and mempry usage data on test run start and
 * end.
 */
public class EmulatorMemoryCpuCollector extends BaseDeviceMetricCollector {
    private static final Pattern PSS_PATTERN = Pattern.compile("Pss:\\s+(\\d+)");

    @Override
    public void onTestRunStart(DeviceMetricData runData) {
        runData.addMetric("initial_memory_pss", buildMemoryMetric(getPssMemory(getEmulatorPid())));
        runData.addMetric("initial_cpu_usage", buildCpuMetric(getCpuUsage(getEmulatorPid())));
    }

    @Override
    public void onTestRunEnd(
            DeviceMetricData runData,
            final Map<String, MetricMeasurement.Metric> currentRunMetrics) {
        runData.addMetric("end_memory_pss", buildMemoryMetric(getPssMemory(getEmulatorPid())));
        runData.addMetric("overall_cpu_usage", buildCpuMetric(getCpuUsage(getEmulatorPid())));
    }

    private static MetricMeasurement.Metric.Builder buildMemoryMetric(long memory) {
        MetricMeasurement.Metric.Builder metricBuilder = MetricMeasurement.Metric.newBuilder();
        metricBuilder.getMeasurementsBuilder().setSingleInt(memory);
        return metricBuilder.setType(MetricMeasurement.DataType.RAW);
    }

    private static MetricMeasurement.Metric.Builder buildCpuMetric(float cpu) {
        MetricMeasurement.Metric.Builder metricBuilder = MetricMeasurement.Metric.newBuilder();
        metricBuilder.getMeasurementsBuilder().setSingleDouble(cpu);
        return metricBuilder.setType(MetricMeasurement.DataType.RAW);
    }

    private long getEmulatorPid() {
        ITestDevice testDevice = Iterables.getOnlyElement(this.getDevices());
        IManagedTestDevice managedTestDevice = (IManagedTestDevice) testDevice;
        Preconditions.checkArgument(
                managedTestDevice.getIDevice().isEmulator(),
                String.format(
                        "device %s is not an emulator",
                        managedTestDevice.getIDevice().getSerialNumber()));
        return managedTestDevice.getEmulatorProcess().pid();
    }

    static long getPssMemory(long pid) {
        try {
            String s = FileUtil.readStringFromFile(new File(String.format("/proc/%d/smaps", pid)));
            return parsePssMemory(s);
        } catch (IOException e) {
            LogUtil.CLog.e("Failed to read /proc/x/smaps", e);
        }
        return 0;
    }

    static long parsePssMemory(String procSmapsContent) {
        // expect several entries of 'Pss:  XX KB'. Parse them out and add them up.
        Matcher m = PSS_PATTERN.matcher(procSmapsContent);
        List<Integer> allPss = new ArrayList<>();
        while (m.find()) {
            allPss.add(Integer.parseInt(m.group(1)));
        }

        return allPss.stream().mapToInt(Integer::intValue).sum();
    }

    static float getCpuUsage(long pid) {
        CommandResult result =
                RunUtil.getDefault()
                        .runTimedCmd(2000, "ps", "-o", "%cpu", "-p", Long.toString(pid));
        if (result.getStatus() == CommandStatus.SUCCESS) {
            return parseCpuUsage(result.getStdout());
        } else {
            LogUtil.CLog.e("Failed to run ps %s", result.toString());
            return 0;
        }
    }

    /**
     * Parse the cpu usage string.
     *
     * <p>Expected input in format: %CPU 1.4
     */
    static float parseCpuUsage(String psContents) {
        return Float.parseFloat(psContents.replace("%CPU\n", "").trim());
    }
}
