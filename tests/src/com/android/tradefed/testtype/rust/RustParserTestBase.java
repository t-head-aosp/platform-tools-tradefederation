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
package com.android.tradefed.testtype.rust;

import com.android.tradefed.log.LogUtil.CLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Vector;

/** Base test class for various Rust parsers */
public abstract class RustParserTestBase {
    protected static final String TEST_TYPE_DIR = "testtype";
    protected static final String TEST_MODULE_NAME = "module";
    protected static final String RUST_OUTPUT_FILE_1 = "rust_output1.txt";
    protected static final String RUST_OUTPUT_FILE_2 = "rust_output2.txt";
    protected static final String RUST_OUTPUT_FILE_3 = "rust_output3.txt";
    protected static final String RUST_OUTPUT_FILE_4 = "rust_output4.txt";
    protected static final String RUST_LIST_FILE_1 = "rust_list1.txt";

    /**
     * Helper to read a file from the res/testtype directory and return its contents as a String[]
     *
     * @param filename the name of the file (without the extension) in the res/testtype directory
     * @return a String[] of the
     */
    protected String[] readInFile(String filename) {
        Vector<String> fileContents = new Vector<String>();
        try {
            InputStream rustResultStream1 =
                    getClass()
                            .getResourceAsStream(
                                    File.separator + TEST_TYPE_DIR + File.separator + filename);
            BufferedReader reader = new BufferedReader(new InputStreamReader(rustResultStream1));
            String line = null;
            while ((line = reader.readLine()) != null) {
                fileContents.add(line);
            }
        } catch (NullPointerException e) {
            CLog.e("Gest output file does not exist: " + filename);
        } catch (IOException e) {
            CLog.e("Unable to read contents of rust output file: " + filename);
        }
        return fileContents.toArray(new String[fileContents.size()]);
    }
}
