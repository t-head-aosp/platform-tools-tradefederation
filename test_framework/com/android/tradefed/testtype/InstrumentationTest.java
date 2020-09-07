/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.tradefed.testtype;

import static com.android.tradefed.testtype.coverage.CoverageOptions.Toolchain.CLANG;
import static com.android.tradefed.testtype.coverage.CoverageOptions.Toolchain.GCOV;
import static com.android.tradefed.testtype.coverage.CoverageOptions.Toolchain.JACOCO;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.Log;
import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner.TestSize;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;
import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.config.IConfigurationReceiver;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.Option.Importance;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.metric.ClangCodeCoverageCollector;
import com.android.tradefed.device.metric.GcovCodeCoverageCollector;
import com.android.tradefed.device.metric.IMetricCollector;
import com.android.tradefed.device.metric.IMetricCollectorReceiver;
import com.android.tradefed.device.metric.JavaCodeCoverageCollector;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.BugreportCollector;
import com.android.tradefed.result.CollectingTestListener;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.ITestLifeCycleReceiver;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.result.TestResult;
import com.android.tradefed.result.TestRunResult;
import com.android.tradefed.result.ddmlib.DefaultRemoteAndroidTestRunner;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.retry.IRetryDecision;
import com.android.tradefed.retry.RetryStrategy;
import com.android.tradefed.testtype.coverage.CoverageOptions;
import com.android.tradefed.util.AbiFormatter;
import com.android.tradefed.util.ArrayUtil;
import com.android.tradefed.util.JavaCodeCoverageFlusher;
import com.android.tradefed.util.ListInstrumentationParser;
import com.android.tradefed.util.ListInstrumentationParser.InstrumentationTarget;
import com.android.tradefed.util.NativeCodeCoverageFlusher;
import com.android.tradefed.util.StringEscapeUtils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** A Test that runs an instrumentation test package on given device. */
@OptionClass(alias = "instrumentation")
public class InstrumentationTest
        implements IDeviceTest,
                IRemoteTest,
                ITestCollector,
                IAbiReceiver,
                IConfigurationReceiver,
                IMetricCollectorReceiver {

    private static final String LOG_TAG = "InstrumentationTest";

    /** max number of attempts to collect list of tests in package */
    private static final int COLLECT_TESTS_ATTEMPTS = 3;
    /** instrumentation test runner argument key used for test execution using a file */
    private static final String TEST_FILE_INST_ARGS_KEY = "testFile";

    /** instrumentation test runner argument key used for individual test timeout */
    static final String TEST_TIMEOUT_INST_ARGS_KEY = "timeout_msec";

    /** default timeout for tests collection */
    static final long TEST_COLLECTION_TIMEOUT_MS = 2 * 60 * 1000;

    /** test run name for merging coverage measurements */
    static final String MERGE_COVERAGE_MEASUREMENTS_TEST_NAME = "mergeCoverageMeasurements";

    static final String RUN_TESTS_AS_USER_KEY = "RUN_TESTS_AS_USER";

    @Option(
        name = "package",
        shortName = 'p',
        description = "The manifest package name of the Android test application to run.",
        importance = Importance.IF_UNSET
    )
    private String mPackageName = null;

    @Option(name = "runner",
            description="The instrumentation test runner class name to use. Will try to determine "
                    + "automatically if it is not specified.")
    private String mRunnerName = null;

    @Option(name = "class", shortName = 'c',
            description="The test class name to run.")
    private String mTestClassName = null;

    @Option(name = "method", shortName = 'm',
            description="The test method name to run.")
    private String mTestMethodName = null;

    @Option(name = "test-package",
            description="Only run tests within this specific java package. " +
            "Will be ignored if --class is set.")
    private String mTestPackageName = null;

    /**
     * @deprecated use shell-timeout or test-timeout option instead.
     */
    @Deprecated
    @Option(name = "timeout",
            description="Deprecated - Use \"shell-timeout\" or \"test-timeout\" instead.")
    private Integer mTimeout = null;

    @Option(
        name = "shell-timeout",
        description =
                "The defined timeout (in milliseconds) is used as a maximum waiting time when "
                        + "expecting the command output from the device. At any time, if the shell "
                        + "command does not output anything for a period longer than defined "
                        + "timeout the TF run terminates. For no timeout, set to 0.",
        isTimeVal = true
    )
    private long mShellTimeout = 10 * 60 * 1000L; // default to 10 minutes

    @Option(
        name = "test-timeout",
        description =
                "Sets timeout (in milliseconds) that will be applied to each test. In the event of "
                        + "a test timeout, it will log the results and proceed with executing the "
                        + "next test. For no timeout, set to 0.",
        isTimeVal = true
    )
    private long mTestTimeout = 5 * 60 * 1000L; // default to 5 minutes

    @Option(
        name = "max-timeout",
        description =
                "Sets the max timeout for the instrumentation to terminate. "
                        + "For no timeout, set to 0.",
        isTimeVal = true
    )
    private long mMaxTimeout = 0l;

    @Option(name = "size",
            description="Restrict test to a specific test size.")
    private String mTestSize = null;

    @Option(name = "rerun",
            description = "Rerun unexecuted tests individually on same device if test run " +
            "fails to complete.")
    private boolean mIsRerunMode = true;

    @Option(name = "resume",
            description = "Schedule unexecuted tests for resumption on another device " +
            "if first device becomes unavailable.")
    private boolean mIsResumeMode = false;

    @Option(name = "install-file",
            description="Optional file path to apk file that contains the tests.")
    private File mInstallFile = null;

    @Option(name = "run-name",
            description="Optional custom test run name to pass to listener. " +
            "If unspecified, will use package name.")
    private String mRunName = null;

    @Option(
            name = "instrumentation-arg",
            description = "Additional instrumentation arguments to provide.",
            requiredForRerun = true)
    private final Map<String, String> mInstrArgMap = new HashMap<String, String>();

    @Option(name = "bugreport-on-failure", description = "Sets which failed testcase events " +
            "cause a bugreport to be collected. a bugreport after failed testcases.  Note that " +
            "there is _no feedback mechanism_ between the test runner and the bugreport " +
            "collector, so use the EACH setting with due caution.")
    private BugreportCollector.Freq mBugreportFrequency = null;

    @Option(
        name = "rerun-from-file",
        description =
                "Use test file instead of separate adb commands for each test "
                        + "when re-running instrumentations for tests that failed to run in previous attempts. "
    )
    private boolean mReRunUsingTestFile = true;

    @Option(
        name = "rerun-from-file-attempts",
        description = "Max attempts to rerun tests from file. -1 means rerun from file infinitely."
    )
    private int mReRunUsingTestFileAttempts = 3;

    @Option(
        name = "fallback-to-serial-rerun",
        description = "Rerun tests serially after rerun from file failed."
    )
    private boolean mFallbackToSerialRerun = false;

    @Option(name = "reboot-before-rerun", description =
            "Reboot a device before re-running instrumentations.")
    private boolean mRebootBeforeReRun = false;

    @Option(name = AbiFormatter.FORCE_ABI_STRING,
            description = AbiFormatter.FORCE_ABI_DESCRIPTION,
            importance = Importance.IF_UNSET)
    private String mForceAbi = null;

    @Option(name = "collect-tests-only",
            description = "Only invoke the instrumentation to collect list of applicable test "
                    + "cases. All test run callbacks will be triggered, but test execution will "
                    + "not be actually carried out.")
    private boolean mCollectTestsOnly = false;

    @Option(
        name = "collect-tests-timeout",
        description = "Timeout for the tests collection operation.",
        isTimeVal = true
    )
    private long mCollectTestTimeout = TEST_COLLECTION_TIMEOUT_MS;

    @Option(
        name = "debug",
        description =
                "Wait for debugger before instrumentation starts. Note "
                        + "that this should only be used for local debugging, not suitable for automated runs."
    )
    protected boolean mDebug = false;

    /** @deprecated Use the --coverage option in CoverageOptions instead. */
    @Deprecated
    @Option(
        name = "coverage",
        description =
                "Collect code coverage for this test run. Note that the build under test must be a "
                        + "coverage build or else this will fail."
    )
    private boolean mCoverage = false;

    @Option(
        name = "merge-coverage-measurements",
        description =
                "Merge coverage measurements from all test runs into a single measurement before "
                        + "logging."
    )
    private boolean mMergeCoverageMeasurements = false;

    @Deprecated
    @Option(
            name = "enforce-ajur-format",
            description = "Whether or not enforcing the AJUR instrumentation output format")
    private boolean mShouldEnforceFormat = false;

    @Option(
        name = "hidden-api-checks",
        description =
                "If set to false, the '--no-hidden-api-checks' flag will be passed to the am "
                        + "instrument command. Only works for P or later."
    )
    private boolean mHiddenApiChecks = true;

    @Option(
            name = "test-api-access",
            description =
                    "If set to false and hidden API checks are enabled, the '--no-test-api-access'"
                            + " flag will be passed to the am instrument command."
                            + " Only works for R or later.")
    private boolean mTestApiAccess = true;

    @Option(
            name = "isolated-storage",
            description =
                    "If set to false, the '--no-isolated-storage' flag will be passed to the am "
                            + "instrument command. Only works for Q or later.")
    private boolean mIsolatedStorage = true;

    @Option(
        name = "window-animation",
        description =
                "If set to false, the '--no-window-animation' flag will be passed to the am "
                        + "instrument command. Only works for ICS or later."
    )
    private boolean mWindowAnimation = true;

    @Option(
            name = "disable-duplicate-test-check",
            description =
                    "If set to true, it will not check that a method is only run once by a "
                            + "given instrumentation.")
    private boolean mDisableDuplicateCheck = false;

    @Option(
            name = "enable-soft-restart-check",
            description =
                    "Whether or not to enable checking whether instrumentation crash is due to "
                            + "a system_server restart.")
    private boolean mEnableSoftRestartCheck = false;

    @Option(
            name = "report-unexecuted-tests",
            description =
                    "Whether or not to enable reporting all unexecuted tests from instrumentation.")
    private boolean mReportUnexecuted = true;

    private IAbi mAbi = null;

    private Collection<String> mInstallArgs = new ArrayList<>();

    private ITestDevice mDevice = null;

    private IRemoteAndroidTestRunner mRunner;

    private Collection<TestDescription> mTestsToRun = null;

    private String mCoverageTarget = null;

    private String mTestFilePathOnDevice = null;

    private ListInstrumentationParser mListInstrumentationParser = null;
    private GcovCodeCoverageCollector mNativeCoverageListener = null;

    private List<String> mExtraDeviceListener = new ArrayList<>();

    private boolean mIsRerun = false;

    private IConfiguration mConfiguration = null;
    private List<IMetricCollector> mCollectors = new ArrayList<>();

    /** {@inheritDoc} */
    @Override
    public void setConfiguration(IConfiguration config) {
        mConfiguration = config;
    }

    /** Gets the {@link IConfiguration} for this test. */
    public IConfiguration getConfiguration() {
        return mConfiguration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDevice(ITestDevice device) {
        mDevice = device;
    }

    /**
     * Set the Android manifest package to run.
     */
    public void setPackageName(String packageName) {
        mPackageName = packageName;
    }

    /**
     * Optionally, set the Android instrumentation runner to use.
     */
    public void setRunnerName(String runnerName) {
        mRunnerName = runnerName;
    }

    /**
     * Gets the Android instrumentation runner to be used.
     */
    public String getRunnerName() {
        return mRunnerName;
    }

    /**
     * Optionally, set the test class name to run.
     */
    public void setClassName(String testClassName) {
        mTestClassName = testClassName;
    }

    /**
     * Optionally, set the test method to run.
     */
    public void setMethodName(String testMethodName) {
        mTestMethodName = StringEscapeUtils.escapeShell(testMethodName);
    }

    /**
     * Optionally, set the path to a file located on the device that should contain a list of line
     * separated test classes and methods (format: com.foo.Class#method) to be run.
     * If set, will automatically attempt to re-run tests using this test file
     * via {@link InstrumentationFileTest} instead of executing separate adb commands for each
     * remaining test via {@link InstrumentationSerialTest}"
     */
    public void setTestFilePathOnDevice(String testFilePathOnDevice) {
        mTestFilePathOnDevice = testFilePathOnDevice;
    }

    /**
     * Optionally, set the test size to run.
     */
    public void setTestSize(String size) {
        mTestSize = size;
    }

    /**
     * Get the Android manifest package to run.
     */
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Get the custom test run name that will be provided to listener
     */
    public String getRunName() {
        return mRunName;
    }

    /**
     * Set the custom test run name that will be provided to listener
     */
    public void setRunName(String runName) {
        mRunName = runName;
    }

    /**
     * Set the collection of tests that should be executed by this InstrumentationTest.
     *
     * @param tests the tests to run
     */
    public void setTestsToRun(Collection<TestDescription> tests) {
        mTestsToRun = tests;
    }

    /**
     * Get the class name to run.
     */
    protected String getClassName() {
        return mTestClassName;
    }

    /**
     * Get the test method to run.
     */
    protected String getMethodName() {
        return mTestMethodName;
    }

    /**
     * Get the path to a file that contains class#method combinations to be run
     */
    String getTestFilePathOnDevice() {
        return mTestFilePathOnDevice;
    }

    /** Get the test java package to run. */
    protected String getTestPackageName() {
        return mTestPackageName;
    }

    /**
     * Sets the test package filter.
     * <p/>
     * If non-null, only tests within the given java package will be executed.
     * <p/>
     * Will be ignored if a non-null value has been provided to {@link #setClassName(String)}
     */
    public void setTestPackageName(String testPackageName) {
        mTestPackageName = testPackageName;
    }

    /**
     * Get the test size to run. Returns <code>null</code> if no size has been set.
     */
    String getTestSize() {
        return mTestSize;
    }

    /**
     * Optionally, set the maximum time (in milliseconds) expecting shell output from the device.
     */
    public void setShellTimeout(long timeout) {
        mShellTimeout = timeout;
    }

    /** Optionally, set the maximum time (in milliseconds) for each individual test run. */
    public void setTestTimeout(long timeout) {
        mTestTimeout = timeout;
    }

    /**
     * Set the coverage target of this test.
     * <p/>
     * Currently unused. This method is just present so coverageTarget can be later retrieved via
     * {@link #getCoverageTarget()}
     */
    public void setCoverageTarget(String coverageTarget) {
        mCoverageTarget = coverageTarget;
    }

    /**
     * Get the coverageTarget previously set via {@link #setCoverageTarget(String)}.
     */
    public String getCoverageTarget() {
        return mCoverageTarget;
    }

    /**
     * Return <code>true</code> if rerun mode is on.
     */
    boolean isRerunMode() {
        return mIsRerunMode;
    }

    /** Sets whether this is a test rerun. Reruns do not create new listeners or merge coverage. */
    void setIsRerun(boolean isRerun) {
        mIsRerun = isRerun;
    }

    /**
     * Optionally, set the rerun mode.
     */
    public void setRerunMode(boolean rerun) {
        mIsRerunMode = rerun;
    }

    /**
     * Optionally, set the resume mode.
     */
    public void setResumeMode(boolean resume) {
        mIsResumeMode = resume;
    }

    /**
     * Get the shell timeout in ms.
     */
    long getShellTimeout() {
        return mShellTimeout;
    }

    /** Get the test timeout in ms. */
    long getTestTimeout() {
        return mTestTimeout;
    }

    /** Returns the max timeout set for the instrumentation. */
    public long getMaxTimeout() {
        return mMaxTimeout;
    }

    /**
     * Set the optional file to install that contains the tests.
     *
     * @param installFile the installable {@link File}
     */
    public void setInstallFile(File installFile) {
        mInstallFile = installFile;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ITestDevice getDevice() {
        return mDevice;
    }

    /**
     * Set the max time in ms to allow for the 'max time to shell output response' when collecting
     * tests.
     * <p/>
     * @deprecated This method is a no-op
     */
    @Deprecated
    @SuppressWarnings("unused")
    public void setCollectsTestsShellTimeout(int timeout) {
        // no-op
    }

    /**
     * Set the frequency with which to automatically collect bugreports after test failures.
     * <p />
     * Note that there is _no feedback mechanism_ between the test runner and the bugreport
     * collector, so use the EACH setting with due caution: if a large quantity of failures happen
     * in rapid succession, the bugreport for a given one of the failures could end up being
     * collected tens of minutes or hours after the respective failure occurred.
     */
    public void setBugreportFrequency(BugreportCollector.Freq freq) {
        mBugreportFrequency = freq;
    }

    /**
     * Add an argument to provide when running the instrumentation tests.
     *
     * @param key the argument name
     * @param value the argument value
     */
    public void addInstrumentationArg(String key, String value) {
        mInstrArgMap.put(key, value);
    }

    /** Allows to remove an entry from the instrumentation-arg. */
    void removeFromInstrumentationArg(String key) {
        mInstrArgMap.remove(key);
    }

    /**
     * Retrieve the value of an argument to provide when running the instrumentation tests.
     *
     * @param key the argument name
     * <p/>
     * Exposed for testing
     */
    String getInstrumentationArg(String key) {
        if (mInstrArgMap.containsKey(key)) {
            return mInstrArgMap.get(key);
        }
        return null;
    }

    /**
     * Sets force-abi option.
     * @param abi
     */
    public void setForceAbi(String abi) {
        mForceAbi = abi;
    }

    public String getForceAbi() {
        return mForceAbi;
    }

    /** Sets the --merge-coverage-measurements option for testing. */
    @VisibleForTesting
    void setMergeCoverageMeasurements(boolean merge) {
        mMergeCoverageMeasurements = merge;
    }

    /** Sets the --rerun-from-file option. */
    public void setReRunUsingTestFile(boolean reRunUsingTestFile) {
        mReRunUsingTestFile = reRunUsingTestFile;
    }

    /** Sets the --fallback-to-serial-rerun option. */
    public void setFallbackToSerialRerun(boolean reRunSerially) {
        mFallbackToSerialRerun = reRunSerially;
    }

    /** Sets the --reboot-before-rerun option. */
    public void setRebootBeforeReRun(boolean rebootBeforeReRun) {
        mRebootBeforeReRun = rebootBeforeReRun;
    }

    /** Allows to add more custom listeners to the runner */
    public void addDeviceListeners(List<String> extraListeners) {
        mExtraDeviceListener.addAll(extraListeners);
    }

    /**
     * @return the {@link IRemoteAndroidTestRunner} to use.
     * @throws DeviceNotAvailableException
     */
    IRemoteAndroidTestRunner createRemoteAndroidTestRunner(String packageName, String runnerName,
            IDevice device) throws DeviceNotAvailableException {
        RemoteAndroidTestRunner runner =
                new DefaultRemoteAndroidTestRunner(packageName, runnerName, device);
        String abiName = resolveAbiName();
        String runOptions = "";
        // hidden-api-checks flag only exists in P and after.
        // Using a temp variable to consolidate the dynamic checks
        int apiLevel = !mHiddenApiChecks || !mWindowAnimation ? getDevice().getApiLevel() : 0;
        if (!mHiddenApiChecks && apiLevel >= 28) {
            runOptions += "--no-hidden-api-checks ";
        }
        // test-api-access flag only exists in R and after.
        // Test API checks are subset of hidden API checks, so only make sense if hidden API
        // checks are enabled.
        if (mHiddenApiChecks
                && !mTestApiAccess
                && getDevice().checkApiLevelAgainstNextRelease(30)) {
            runOptions += "--no-test-api-access ";
        }
        // isolated-storage flag only exists in Q and after.
        if (!mIsolatedStorage && getDevice().checkApiLevelAgainstNextRelease(29)) {
            runOptions += "--no-isolated-storage ";
        }
        // window-animation flag only exists in ICS and after
        if (!mWindowAnimation && apiLevel >= 14) {
            runOptions += "--no-window-animation ";
        }

        if (abiName != null && getDevice().getApiLevel() > 20) {
            mInstallArgs.add(String.format("--abi %s", abiName));
            runOptions += String.format("--abi %s", abiName);
        }
        // Set the run options if any.
        if (!runOptions.isEmpty()) {
            runner.setRunOptions(runOptions);
        }

        return runner;
    }

    private String resolveAbiName() throws DeviceNotAvailableException {
        if (mAbi != null && mForceAbi != null) {
            throw new IllegalArgumentException("cannot specify both abi flags");
        }
        String abiName = null;
        if (mAbi != null) {
            abiName = mAbi.getName();
        } else if (mForceAbi != null && !mForceAbi.isEmpty()) {
            abiName = AbiFormatter.getDefaultAbi(mDevice, mForceAbi);
            if (abiName == null) {
                throw new RuntimeException(
                        String.format("Cannot find abi for force-abi %s", mForceAbi));
            }
        }
        return abiName;
    }

    /**
     * Set the {@link ListInstrumentationParser}.
     */
    @VisibleForTesting
    void setListInstrumentationParser(ListInstrumentationParser listInstrumentationParser) {
        mListInstrumentationParser = listInstrumentationParser;
    }

    /**
     * Get the {@link ListInstrumentationParser} used to parse 'pm list instrumentation' queries.
     */
    protected ListInstrumentationParser getListInstrumentationParser() {
        if (mListInstrumentationParser == null) {
            mListInstrumentationParser = new ListInstrumentationParser();
        }
        return mListInstrumentationParser;
    }

    /**
     * Query the device for a test runner to use.
     *
     * @return the first test runner name that matches the package or null if we don't find any.
     * @throws DeviceNotAvailableException
     */
    protected String queryRunnerName() throws DeviceNotAvailableException {
        ListInstrumentationParser parser = getListInstrumentationParser();
        getDevice().executeShellCommand("pm list instrumentation", parser);

        Set<String> candidates = new LinkedHashSet<>();
        for (InstrumentationTarget target : parser.getInstrumentationTargets()) {
            if (mPackageName.equals(target.packageName)) {
                candidates.add(target.runnerName);
            }
        }
        if (candidates.isEmpty()) {
            CLog.w("Unable to determine runner name for package: %s", mPackageName);
            return null;
        }
        // Bias toward using one of the AJUR runner when available, otherwise use the first runner
        // available.
        Set<String> intersection =
                Sets.intersection(candidates, ListInstrumentationParser.SHARDABLE_RUNNERS);
        if (intersection.isEmpty()) {
            return candidates.iterator().next();
        }
        return intersection.iterator().next();
    }

    /** {@inheritDoc} */
    @Override
    public void run(TestInformation testInfo, final ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        checkArgument(mDevice != null, "Device has not been set.");
        checkArgument(mPackageName != null, "Package name has not been set.");
        // Install the apk before checking the runner
        if (mInstallFile != null) {
            String installOutput =
                    mDevice.installPackage(
                            mInstallFile, true, mInstallArgs.toArray(new String[] {}));
            if (installOutput != null) {
                throw new RuntimeException(
                        String.format(
                                "Error while installing '%s': %s",
                                mInstallFile.getName(), installOutput));
            }
        }
        if (mRunnerName == null) {
            setRunnerName(queryRunnerName());
            checkArgument(
                    mRunnerName != null,
                    "Runner name has not been set and no matching instrumentations were found.");
            CLog.i("No runner name specified. Using: %s.", mRunnerName);
        }
        mRunner = createRemoteAndroidTestRunner(mPackageName, mRunnerName, mDevice.getIDevice());
        setRunnerArgs(mRunner);

        doTestRun(testInfo, listener);
        if (mInstallFile != null) {
            mDevice.uninstallPackage(mPackageName);
        }
    }

    protected void setRunnerArgs(IRemoteAndroidTestRunner runner) {
        if (mTestClassName != null) {
            if (mTestMethodName != null) {
                runner.setMethodName(mTestClassName, mTestMethodName);
            } else {
                runner.setClassName(mTestClassName);
            }
        } else if (mTestPackageName != null) {
            runner.setTestPackageName(mTestPackageName);
        }
        if (mTestFilePathOnDevice != null) {
            addInstrumentationArg(TEST_FILE_INST_ARGS_KEY, mTestFilePathOnDevice);
        }
        if (mTestSize != null) {
            runner.setTestSize(TestSize.getTestSize(mTestSize));
        }
        addTimeoutsToRunner(runner);
        if (mRunName != null) {
            runner.setRunName(mRunName);
        }
        for (Map.Entry<String, String> argEntry : mInstrArgMap.entrySet()) {
            runner.addInstrumentationArg(argEntry.getKey(), argEntry.getValue());
        }
    }

    /**
     * Helper method to add test-timeout & shell-timeout timeouts to  given runner
     */
    private void addTimeoutsToRunner(IRemoteAndroidTestRunner runner) {
        if (mTimeout != null) {
            CLog.w("\"timeout\" argument is deprecated and should not be used! \"shell-timeout\""
                    + " argument value is overwritten with %d ms", mTimeout);
            setShellTimeout(mTimeout);
        }
        if (mTestTimeout < 0) {
            throw new IllegalArgumentException(
                    String.format("test-timeout %d cannot be negative", mTestTimeout));
        }
        if (mShellTimeout <= mTestTimeout) {
            // set shell timeout to 110% of test timeout
            mShellTimeout = mTestTimeout + mTestTimeout / 10;
            CLog.w(String.format("shell-timeout should be larger than test-timeout %d; "
                    + "NOTE: extending shell-timeout to %d, please consider fixing this!",
                    mTestTimeout, mShellTimeout));
        }
        runner.setMaxTimeToOutputResponse(mShellTimeout, TimeUnit.MILLISECONDS);
        runner.setMaxTimeout(mMaxTimeout, TimeUnit.MILLISECONDS);
        addInstrumentationArg(TEST_TIMEOUT_INST_ARGS_KEY, Long.toString(mTestTimeout));
    }

    /**
     * Execute test run.
     *
     * @param listener the test result listener
     * @throws DeviceNotAvailableException if device stops communicating
     */
    private void doTestRun(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        // If this is a dry-run, just collect the tests and return
        if (mCollectTestsOnly) {
            checkState(
                    mTestsToRun == null,
                    "Tests to run should not be set explicitly when --collect-tests-only is set.");

            // Use the actual listener to collect the tests, and print a error if this fails
            Collection<TestDescription> collectedTests =
                    collectTestsToRun(testInfo, mRunner, listener);
            if (collectedTests == null) {
                CLog.e("Failed to collect tests for %s", mPackageName);
            } else {
                CLog.i("Collected %d tests for %s", collectedTests.size(), mPackageName);
            }
            return;
        }

        // If the tests to run weren't provided explicitly, collect them.
        Collection<TestDescription> testsToRun = mTestsToRun;
        if (testsToRun == null) {
            // Don't notify the listener since it's not a real run.
            testsToRun = collectTestsToRun(testInfo, mRunner, null);
        }

        // Only set the debug flag after collecting tests.
        if (mDebug) {
            mRunner.setDebug(true);
        }
        if (mConfiguration != null && mConfiguration.getCoverageOptions().isCoverageEnabled()) {
            mRunner.addInstrumentationArg("coverage", "true");
        }

        // Reruns do not create new listeners or clear coverage measurements.
        if (!mIsRerun) {
            listener = addBugreportListenerIfEnabled(listener);
            listener = addJavaCoverageListenerIfEnabled(testInfo, listener);
            listener = addGcovCoverageListenerIfEnabled(testInfo, listener);
            listener = addClangCoverageListenerIfEnabled(testInfo, listener);

            // Clear coverage measurements on the device before running.
            if (mConfiguration != null
                    && mConfiguration.getCoverageOptions().isCoverageFlushEnabled()) {
                CoverageOptions options = mConfiguration.getCoverageOptions();

                if (options.getCoverageToolchains().contains(GCOV)
                        || options.getCoverageToolchains().contains(CLANG)) {
                    // Enable abd root on the device, otherwise the following commands will fail.
                    verify(mDevice.enableAdbRoot(), "Failed to enable adb root.");

                    NativeCodeCoverageFlusher flusher =
                            new NativeCodeCoverageFlusher(mDevice, options.getCoverageProcesses());
                    flusher.resetCoverage();
                }

                if (options.getCoverageToolchains().contains(JACOCO)) {
                    JavaCodeCoverageFlusher flusher =
                            new JavaCodeCoverageFlusher(mDevice, options.getCoverageProcesses());
                    flusher.resetCoverage();
                }
            }

            // TODO: Convert to device-side collectors when possible.
            for (IMetricCollector collector : mCollectors) {
                if (collector.isDisabled()) {
                    CLog.d("%s has been disabled. Skipping.", collector);
                } else {
                    CLog.d(
                            "Initializing %s for instrumentation.",
                            collector.getClass().getCanonicalName());
                    if (collector instanceof IConfigurationReceiver) {
                        ((IConfigurationReceiver) collector).setConfiguration(mConfiguration);
                    }
                    listener = collector.init(testInfo.getContext(), listener);
                }
            }
        }

        // Add the extra listeners only to the actual run and not the --collect-test-only one
        if (!mExtraDeviceListener.isEmpty()) {
            mRunner.addInstrumentationArg("listener", ArrayUtil.join(",", mExtraDeviceListener));
        }

        if (testsToRun == null) {
            // Failed to collect the tests or collection is off. Just try to run them all.
            runInstrumentationTests(testInfo, mRunner, listener);
        } else if (!testsToRun.isEmpty()) {
            runWithRerun(testInfo, listener, testsToRun);
        } else {
            CLog.i("No tests expected for %s, skipping", mPackageName);
        }

        // Merge coverage measurements after all tests have been run, but not inside the rerun
        // itself since the merging will be handled by the caller.
        if (!mIsRerun && mMergeCoverageMeasurements) {
            listener.testRunStarted(MERGE_COVERAGE_MEASUREMENTS_TEST_NAME, 0);
            listener.testRunEnded(0, new HashMap<String, Metric>());
        }
    }

    private boolean runInstrumentationTests(
            TestInformation testInfo,
            IRemoteAndroidTestRunner runner,
            ITestLifeCycleReceiver... receivers)
            throws DeviceNotAvailableException {
        if (testInfo.properties().containsKey(RUN_TESTS_AS_USER_KEY)) {
            return mDevice.runInstrumentationTestsAsUser(
                    runner,
                    Integer.parseInt(testInfo.properties().get(RUN_TESTS_AS_USER_KEY)),
                    receivers);
        }
        return mDevice.runInstrumentationTests(runner, receivers);
    }

    /**
     * Returns a listener that will collect bugreports, or the original {@code listener} if this
     * feature is disabled.
     */
    ITestInvocationListener addBugreportListenerIfEnabled(ITestInvocationListener listener) {
        if (mBugreportFrequency != null) {
            // Collect a bugreport after EACH/FIRST failed testcase
            BugreportCollector.Predicate pred = new BugreportCollector.Predicate(
                    BugreportCollector.Relation.AFTER,
                    mBugreportFrequency,
                    BugreportCollector.Noun.FAILED_TESTCASE);
            BugreportCollector collector = new BugreportCollector(listener, getDevice());
            collector.addPredicate(pred);
            listener = collector;
        }
        return listener;
    }

    /**
     * Returns a listener that will collect coverage measurements, or the original {@code listener}
     * if this feature is disabled.
     */
    ITestInvocationListener addJavaCoverageListenerIfEnabled(
            final TestInformation testInfo, ITestInvocationListener listener) {
        if (mConfiguration == null) {
            return listener;
        }
        if (mConfiguration.getCoverageOptions().isCoverageEnabled()
                && mConfiguration.getCoverageOptions().getCoverageToolchains().contains(JACOCO)) {
            JavaCodeCoverageCollector javaListener = new JavaCodeCoverageCollector();
            javaListener.setConfiguration(mConfiguration);
            return javaListener.init(testInfo.getContext(), listener);
        }
        return listener;
    }

    /**
     * Returns a listener that will collect gcov coverage measurements, or the original {@code
     * listener} if this feature is disabled.
     */
    ITestInvocationListener addGcovCoverageListenerIfEnabled(
            final TestInformation testInfo, ITestInvocationListener listener) {
        if (mConfiguration == null) {
            return listener;
        }
        if (mConfiguration.getCoverageOptions().isCoverageEnabled()
                && mConfiguration.getCoverageOptions().getCoverageToolchains().contains(GCOV)) {
            mNativeCoverageListener = new GcovCodeCoverageCollector();
            mNativeCoverageListener.setConfiguration(mConfiguration);
            listener = mNativeCoverageListener.init(testInfo.getContext(), listener);
        }
        return listener;
    }

    /**
     * Returns a listener that will collect Clang coverage measurements, or the original {@code
     * listener} if this feature is disabled.
     */
    ITestInvocationListener addClangCoverageListenerIfEnabled(
            TestInformation testInfo, ITestInvocationListener listener) {
        if (mConfiguration == null) {
            return listener;
        }
        if (mConfiguration.getCoverageOptions().isCoverageEnabled()
                && mConfiguration.getCoverageOptions().getCoverageToolchains().contains(CLANG)) {
            ClangCodeCoverageCollector clangListener = new ClangCodeCoverageCollector();
            clangListener.setConfiguration(mConfiguration);
            listener = clangListener.init(testInfo.getContext(), listener);
        }
        return listener;
    }

    /**
     * Execute the test run, but re-run incomplete tests individually if run fails to complete.
     *
     * @param listener the {@link ITestInvocationListener}
     * @param expectedTests the full set of expected tests in this run.
     */
    private void runWithRerun(
            TestInformation testInfo,
            final ITestInvocationListener listener,
            Collection<TestDescription> expectedTests)
            throws DeviceNotAvailableException {
        CollectingTestListener testTracker = new CollectingTestListener();
        // Our dedicated listener that allows to perform checks for the harness and collect
        // the appropriate data.
        InstrumentationListener instrumentationListener =
                new InstrumentationListener(getDevice(), expectedTests, listener, testTracker);
        instrumentationListener.setDisableDuplicateCheck(mDisableDuplicateCheck);
        if (mEnableSoftRestartCheck) {
            instrumentationListener.setOriginalSystemServer(
                    getDevice().getProcessByName("system_server"));
        }
        instrumentationListener.setReportUnexecutedTests(mReportUnexecuted);
        runInstrumentationTests(testInfo, mRunner, instrumentationListener);
        TestRunResult testRun = testTracker.getCurrentRunResults();
        if (testRun.isRunFailure() || !testRun.getCompletedTests().containsAll(expectedTests)) {
            // Don't re-run any completed tests, unless this is a coverage run.
            if (mConfiguration != null
                    && !mConfiguration.getCoverageOptions().isCoverageEnabled()) {
                expectedTests.removeAll(excludeNonExecuted(testTracker.getCurrentRunResults()));
                IRetryDecision decision = mConfiguration.getRetryDecision();
                if (!RetryStrategy.NO_RETRY.equals(decision.getRetryStrategy())
                        && decision.getMaxRetryCount() > 1) {
                    // Delegate retry to the module/invocation level.
                    // This prevents the InstrumentationTest retry from re-running by itself and
                    // creating overhead.
                    return;
                }
            }
            rerunTests(expectedTests, testInfo, listener);
        }
    }

    /** Filter out "NOT_EXECUTED" for the purpose of tracking what needs to be rerun. */
    protected static Set<TestDescription> excludeNonExecuted(TestRunResult results) {
        Set<TestDescription> completedTest = results.getCompletedTests();
        for (Entry<TestDescription, TestResult> entry : results.getTestResults().entrySet()) {
            if (completedTest.contains(entry.getKey()) && entry.getValue().getFailure() != null) {
                if (FailureStatus.NOT_EXECUTED.equals(
                        entry.getValue().getFailure().getFailureStatus())) {
                    completedTest.remove(entry.getKey());
                }
            }
        }
        return completedTest;
    }

    /**
     * Rerun any <var>mRemainingTests</var>
     *
     * @param listener the {@link ITestInvocationListener}
     * @throws DeviceNotAvailableException
     */
    private void rerunTests(
            Collection<TestDescription> expectedTests,
            TestInformation testInfo,
            final ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        if (expectedTests.isEmpty()) {
            CLog.d("No tests to re-run, all tests executed at least once.");
            return;
        }
        if (mRebootBeforeReRun) {
            mDevice.reboot();
        } else {
            // Ensure device is online and responsive before retrying.
            mDevice.waitForDeviceAvailable();
        }

        IRemoteTest testReRunner = null;
        try {
            testReRunner = getTestReRunner(expectedTests);
        } catch (ConfigurationException e) {
            CLog.e("Failed to create test runner: %s", e.getMessage());
            return;
        }

        if (mNativeCoverageListener != null) {
            mNativeCoverageListener.setCollectOnTestEnd(false);
        }

        testReRunner.run(testInfo, listener);

        if (mNativeCoverageListener != null) {
            mNativeCoverageListener.setCollectOnTestEnd(true);
            mNativeCoverageListener.logCoverageMeasurements("rerun_merged");
        }
    }

    @VisibleForTesting
    IRemoteTest getTestReRunner(Collection<TestDescription> tests) throws ConfigurationException {
        if (mReRunUsingTestFile) {
            return new InstrumentationFileTest(
                    this, tests, mFallbackToSerialRerun, mReRunUsingTestFileAttempts);
        } else {
            // Since the same runner is reused we must ensure TEST_FILE_INST_ARGS_KEY is not set.
            // Otherwise, the runner will attempt to execute tests from file.
            mInstrArgMap.remove(TEST_FILE_INST_ARGS_KEY);
            return new InstrumentationSerialTest(this, tests);
        }
    }

    /**
     * Collect the list of tests that should be executed by this test run.
     *
     * <p>This will be done by executing the test run in 'logOnly' mode, and recording the list of
     * tests.
     *
     * @param runner the {@link IRemoteAndroidTestRunner} to use to run the tests.
     * @return a {@link Collection} of {@link TestDescription}s that represent all tests to be
     *     executed by this run
     * @throws DeviceNotAvailableException
     */
    private Collection<TestDescription> collectTestsToRun(
            final TestInformation testInfo,
            final IRemoteAndroidTestRunner runner,
            final ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        if (isRerunMode()) {
            Log.d(LOG_TAG, String.format("Collecting test info for %s on device %s",
                    mPackageName, mDevice.getSerialNumber()));
            runner.setTestCollection(true);
            // always explicitly set debug to false when collecting tests
            runner.setDebug(false);
            // try to collect tests multiple times, in case device is temporarily not available
            // on first attempt
            Collection<TestDescription> tests = collectTestsAndRetry(testInfo, runner, listener);
            // done with "logOnly" mode, restore proper test timeout before real test execution
            addTimeoutsToRunner(runner);
            runner.setTestCollection(false);
            return tests;
        }
        return null;
    }

    /**
     * Performs the actual work of collecting tests, making multiple attempts if necessary
     *
     * @param runner the {@link IRemoteAndroidTestRunner} that will be used for the instrumentation
     * @param listener the {ITestInvocationListener} where to report results, can be null if we are
     *     not reporting the results to the main invocation and simply collecting tests.
     * @return the collection of tests, or <code>null</code> if tests could not be collected
     * @throws DeviceNotAvailableException if communication with the device was lost
     */
    @VisibleForTesting
    Collection<TestDescription> collectTestsAndRetry(
            final TestInformation testInfo,
            final IRemoteAndroidTestRunner runner,
            final ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        boolean communicationFailure = false;
        for (int i=0; i < COLLECT_TESTS_ATTEMPTS; i++) {
            CollectingTestListener collector = new CollectingTestListener();
            boolean instrResult = false;
            // We allow to override the ddmlib default timeout for collection of tests.
            runner.setMaxTimeToOutputResponse(mCollectTestTimeout, TimeUnit.MILLISECONDS);
            if (listener == null) {
                instrResult = runInstrumentationTests(testInfo, runner, collector);
            } else {
                instrResult = runInstrumentationTests(testInfo, runner, collector, listener);
            }
            TestRunResult runResults = collector.getCurrentRunResults();
            if (!instrResult || !runResults.isRunComplete()) {
                // communication failure with device, retry
                Log.w(LOG_TAG, String.format(
                        "No results when collecting tests to run for %s on device %s. Retrying",
                        mPackageName, mDevice.getSerialNumber()));
                communicationFailure = true;
            } else if (runResults.isRunFailure()) {
                // not a communication failure, but run still failed.
                // TODO: should retry be attempted
                CLog.w("Run failure %s when collecting tests to run for %s on device %s.",
                        runResults.getRunFailureMessage(), mPackageName,
                        mDevice.getSerialNumber());
                return null;
            } else {
                // success!
                return runResults.getCompletedTests();
            }
        }
        if (communicationFailure) {
            // TODO: find a better way to handle this
            // throwing DeviceUnresponsiveException is not always ideal because a misbehaving
            // instrumentation can hang, even though device is responsive. Would be nice to have
            // a louder signal for this situation though than just logging an error
//            throw new DeviceUnresponsiveException(String.format(
//                    "Communication failure when attempting to collect tests %s on device %s",
//                    mPackageName, mDevice.getSerialNumber()));
            CLog.w("Ignoring repeated communication failure when collecting tests %s for device %s",
                    mPackageName, mDevice.getSerialNumber());
        }
        CLog.e("Failed to collect tests to run for %s on device %s.",
                mPackageName, mDevice.getSerialNumber());
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCollectTestsOnly(boolean shouldCollectTest) {
        mCollectTestsOnly = shouldCollectTest;
    }

    @Override
    public void setAbi(IAbi abi) {
        mAbi = abi;
    }

    @Override
    public IAbi getAbi() {
        return mAbi;
    }

    @Override
    public void setMetricCollectors(List<IMetricCollector> collectors) {
        mCollectors = collectors;
    }

    /** Set True if we enforce the AJUR output format of instrumentation. */
    public void setEnforceFormat(boolean enforce) {
        mShouldEnforceFormat = enforce;
    }

    /**
     * Set the instrumentation debug setting.
     *
     * @param debug boolean value to set the instrumentation debug setting to.
     */
    public void setDebug(boolean debug) {
        mDebug = debug;
    }

    /**
     * Get the instrumentation debug setting.
     *
     * @return The boolean debug setting.
     */
    public boolean getDebug() {
        return mDebug;
    }

    /** Set wether or not to use the isolated storage. */
    public void setIsolatedStorage(boolean isolatedStorage) {
        mIsolatedStorage = isolatedStorage;
    }
}
