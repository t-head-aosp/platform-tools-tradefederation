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
package com.android.tradefed.cluster;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.build.BuildRetrievalError;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IBuildProvider;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.invoker.logger.InvocationLocal;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.TarUtil;
import com.android.tradefed.util.ZipUtil2;
import org.apache.commons.compress.archivers.zip.ZipFile;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** A {@link IBuildProvider} to download TFC test resources. */
@OptionClass(alias = "cluster", global_namespace = false)
public class ClusterBuildProvider implements IBuildProvider {

    private static final String DEFAULT_FILE_VERSION = "0";

    @Option(name = "root-dir", description = "A root directory", mandatory = true)
    private File mRootDir;

    @Option(
            name = "test-resource",
            description = "Mapping from test resource name to URL",
            mandatory = true)
    private Map<String, String> mTestResources = new TreeMap<>();

    @Option(
            name = "decompress-test-resource",
            description =
                    "Mapping from test resource name to the subdirectory where the "
                            + "resource is decompressed")
    private Map<String, String> mDecompressTestResources = new TreeMap<>();

    @Option(name = "build-id", description = "Build ID")
    private String mBuildId = IBuildInfo.UNKNOWN_BUILD_ID;

    @Option(name = "build-target", description = "Build target name")
    private String mBuildTarget = "stub";

    // The keys are the URLs; the values are the downloaded files shared among all build providers
    // in the invocation.
    // TODO(b/139876060): Use dynamic download when it supports caching HTTPS and GCS files.
    @VisibleForTesting
    static final InvocationLocal<ConcurrentHashMap<String, File>> sDownloadCache =
            new InvocationLocal<ConcurrentHashMap<String, File>>() {
                @Override
                protected ConcurrentHashMap<String, File> initialValue() {
                    return new ConcurrentHashMap<String, File>();
                }
            };

    // The keys are the resource names; the values are the files and directories.
    @VisibleForTesting
    static final InvocationLocal<ConcurrentHashMap<String, File>> sCreatedResources =
            new InvocationLocal<ConcurrentHashMap<String, File>>() {
                @Override
                protected ConcurrentHashMap<String, File> initialValue() {
                    return new ConcurrentHashMap<String, File>();
                }
            };

    @Override
    public IBuildInfo getBuild() throws BuildRetrievalError {
        mRootDir.mkdirs();
        final IBuildInfo buildInfo = new ClusterBuildInfo(mRootDir, mBuildId, mBuildTarget);
        final TestResourceDownloader downloader = createTestResourceDownloader();
        final ConcurrentHashMap<String, File> cache = sDownloadCache.get();
        final ConcurrentHashMap<String, File> createdResources = sCreatedResources.get();

        for (final Entry<String, String> entry : mTestResources.entrySet()) {
            final String resourceName = entry.getKey();
            String decompressDir = mDecompressTestResources.get(resourceName);
            // For backward compatibility.
            if (decompressDir == null && resourceName.endsWith(".zip")) {
                decompressDir = new File(resourceName).getParent();
                if (decompressDir == null) {
                    decompressDir = "";
                }
            }
            final TestResource resource =
                    new TestResource(
                            resourceName, entry.getValue(), decompressDir != null, decompressDir);
            // Validate the paths before the file operations.
            final File resourceFile = resource.getFile(mRootDir);
            validateTestResourceFile(resourceFile);
            if (resource.getDecompress()) {
                validateTestResourceFile(resource.getDecompressDir(mRootDir));
            }
            // Download and decompress.
            File file;
            try {
                File cachedFile = retrieveFile(resource.getUrl(), cache, downloader, resourceFile);
                file = prepareTestResource(resource, createdResources, cachedFile);
            } catch (UncheckedIOException e) {
                throw new BuildRetrievalError("failed to get test resources", e.getCause());
            }
            buildInfo.setFile(resource.getName(), file, DEFAULT_FILE_VERSION);
        }
        return buildInfo;
    }

    /** Check if a resource file is under the working directory. */
    private void validateTestResourceFile(File file) throws BuildRetrievalError {
        if (!file.toPath().normalize().startsWith(mRootDir.toPath().normalize())) {
            throw new BuildRetrievalError(file + " is outside of working directory.");
        }
    }

    /**
     * Retrieve a file from cache or URL.
     *
     * <p>If the URL is in the cache, this method returns the cached file. Otherwise, it downloads
     * and adds the file to the cache. If any file operation fails, this method throws {@link
     * UncheckedIOException}.
     *
     * @param downloadUrl the file to be retrieved.
     * @param cache the cache that maps URLs to files.
     * @param downloader the downloader that gets the file.
     * @param downloadDest the file to be created if the URL isn't in the cache.
     * @return the cached or downloaded file.
     */
    private File retrieveFile(
            String downloadUrl,
            ConcurrentHashMap<String, File> cache,
            TestResourceDownloader downloader,
            File downloadDest) {
        return cache.computeIfAbsent(
                downloadUrl,
                url -> {
                    CLog.i("Download from %s.", url);
                    try {
                        downloader.download(url, downloadDest);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return downloadDest;
                });
    }

    /**
     * Create a resource file from cache and decompress it if needed.
     *
     * <p>If any file operation fails, this method throws {@link UncheckedIOException}.
     *
     * @param resource the resource to be created.
     * @param createdResources the map from created resource names to paths.
     * @param source the local cache of the file.
     * @return the file or directory to be added to build info.
     */
    private File prepareTestResource(
            TestResource resource, ConcurrentHashMap<String, File> createdResources, File source) {
        return createdResources.computeIfAbsent(
                resource.getName(),
                name -> {
                    // Create the file regardless of the decompress flag.
                    final File file = resource.getFile(mRootDir);
                    if (!source.equals(file)) {
                        if (file.exists()) {
                            CLog.w("Overwrite %s.", name);
                            file.delete();
                        } else {
                            CLog.i("Create %s.", name);
                            file.getParentFile().mkdirs();
                        }
                        try {
                            FileUtil.hardlinkFile(source, file);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                    // Decompress if needed.
                    if (resource.getDecompress()) {
                        final File dir = resource.getDecompressDir(mRootDir);
                        try {
                            decompressArchive(source, dir);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        return dir;
                    }
                    return file;
                });
    }

    /** Extracts a zip or a gzip to a directory. */
    private void decompressArchive(File archive, File destDir) throws IOException {
        if (!destDir.exists()) {
            if (!destDir.mkdirs()) {
                CLog.e("Cannot create %s.", destDir);
            }
        }
        if (TarUtil.isGzip(archive)) {
            File unGzipDir = FileUtil.createTempDir("ClusterBuildProviderUnGzip");
            try {
                File tar = TarUtil.unGzip(archive, unGzipDir);
                TarUtil.unTar(tar, destDir);
            } finally {
                FileUtil.recursiveDelete(unGzipDir);
            }
        } else {
            try (ZipFile zip = new ZipFile(archive)) {
                ZipUtil2.extractZip(zip, destDir);
            }
        }
    }

    @Override
    public void buildNotTested(IBuildInfo info) {}

    @Override
    public void cleanUp(IBuildInfo info) {
        if (!(info instanceof ClusterBuildInfo)) {
            throw new IllegalArgumentException("info is not an instance of ClusterBuildInfo");
        }
    }

    @VisibleForTesting
    TestResourceDownloader createTestResourceDownloader() {
        return new TestResourceDownloader();
    }

    @VisibleForTesting
    void setRootDir(File rootDir) {
        mRootDir = rootDir;
    }

    @VisibleForTesting
    Map<String, String> getTestResources() {
        return mTestResources;
    }

    @VisibleForTesting
    Map<String, String> getDecompressTestResources() {
        return mDecompressTestResources;
    }
}
