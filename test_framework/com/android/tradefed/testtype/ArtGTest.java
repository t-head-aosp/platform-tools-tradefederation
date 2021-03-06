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

package com.android.tradefed.testtype;

import com.android.tradefed.targetprep.ArtChrootPreparer;

public class ArtGTest extends GTest {
    @Override
    protected String getGTestCmdLineWrapper(String fullPath, String flags) {
        String chroot = ArtChrootPreparer.CHROOT_PATH;
        if (fullPath.startsWith(chroot)) {
            fullPath = fullPath.substring(chroot.length());
        }
        return String.format("chroot %s %s %s", chroot, fullPath, flags);
    }
}
