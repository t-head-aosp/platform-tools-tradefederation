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
package com.android.tradefed.invoker;

import com.google.common.collect.ImmutableMap;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Files generated during the execution of a test or invocation that need to be carried */
public class ExecutionFiles {

    /** Enumeration of known standard key for the map. */
    public static enum FilesKey {
        ADB_BINARY
    }

    private final ConcurrentMap<String, File> mProperties = new ConcurrentHashMap<>();

    // Package private to avoid new instantiation.
    ExecutionFiles() {}

    /**
     * Associates the specified value with the specified key in this map.
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with {@code key}, or {@code null} if there was no
     *     mapping for {@code key}.
     * @see ConcurrentMap
     */
    public File put(String key, File value) {
        return mProperties.put(key, value);
    }

    /**
     * Variation of {@link #put(FilesKey, File)} with a known key.
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with {@code key}, or {@code null} if there was no
     *     mapping for {@code key}.
     */
    public File put(FilesKey key, File value) {
        return mProperties.put(key.toString(), value);
    }

    /**
     * If the specified key is not already associated with a value, associates it with the given
     * value.
     *
     * @param key key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with the specified key, or {@code null} if there was no
     *     mapping for the key.
     */
    public File putIfAbsent(String key, File value) {
        return mProperties.putIfAbsent(key, value);
    }

    /**
     * Copies all of the mappings from the specified map to this map.
     *
     * @param properties mappings to be stored in this map
     * @return The final mapping
     */
    public ExecutionFiles putAll(Map<String, File> properties) {
        mProperties.putAll(properties);
        return this;
    }

    /** Returns whether or not the map of properties is empty. */
    public boolean isEmpty() {
        return mProperties.isEmpty();
    }

    /**
     * Returns the value to which the specified key is mapped, or {@code null} if this map contains
     * no mapping for the key.
     *
     * @param key the key whose associated value is to be returned
     * @return the value to which the specified key is mapped, or {@code null} if this map contains
     *     no mapping for the key
     */
    public File get(String key) {
        return mProperties.get(key);
    }

    /**
     * Variation of {@link #get(String)} with a known key.
     *
     * @param key the key whose associated value is to be returned
     * @return the value to which the specified key is mapped, or {@code null} if this map contains
     *     no mapping for the key
     */
    public File get(FilesKey key) {
        return mProperties.get(key.toString());
    }

    /**
     * Returns {@code true} if this map contains a mapping for the specified key.
     *
     * @param key key whose presence in this map is to be tested
     * @return {@code true} if this map contains a mapping for the specified key
     */
    public boolean containsKey(String key) {
        return mProperties.containsKey(key);
    }

    /** Returns all the properties in a copy of the map */
    public ImmutableMap<String, File> getAll() {
        return ImmutableMap.copyOf(mProperties);
    }

    /**
     * Removes the mapping for a key from this map if it is present (optional operation).
     *
     * @param key key whose mapping is to be removed from the map
     * @return the previous value associated with {@code key}, or {@code null} if there was no
     *     mapping for {@code key}.
     */
    public File remove(String key) {
        return mProperties.remove(key);
    }
}
