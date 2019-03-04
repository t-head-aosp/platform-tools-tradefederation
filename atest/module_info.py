# Copyright 2018, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Module Info class used to hold cached module-info.json.
"""

import json
import logging
import os

import atest_utils
import constants
from test_finders import test_finder_utils

# JSON file generated by build system that lists all buildable targets.
_MODULE_INFO = 'module-info.json'


class ModuleInfo(object):
    """Class that offers fast/easy lookup for Module related details."""

    def __init__(self, force_build=False, module_file=None):
        """Initialize the ModuleInfo object.

        Load up the module-info.json file and initialize the helper vars.

        Args:
            force_build: Boolean to indicate if we should rebuild the
                         module_info file regardless if it's created or not.
            module_file: String of path to file to load up. Used for testing.
        """
        module_info_target, name_to_module_info = self._load_module_info_file(
            force_build, module_file)
        self.name_to_module_info = name_to_module_info
        self.module_info_target = module_info_target
        self.path_to_module_info = self._get_path_to_module_info(
            self.name_to_module_info)
        self.root_dir = os.environ.get(constants.ANDROID_BUILD_TOP)

    @staticmethod
    def _discover_mod_file_and_target(force_build):
        """Find the module file.

        Args:
            force_build: Boolean to indicate if we should rebuild the
                         module_info file regardless if it's created or not.

        Returns:
            Tuple of module_info_target and path to module file.
        """
        module_info_target = None
        root_dir = os.environ.get(constants.ANDROID_BUILD_TOP, '/')
        out_dir = os.environ.get(constants.ANDROID_PRODUCT_OUT, root_dir)
        module_file_path = os.path.join(out_dir, _MODULE_INFO)

        # Check if the user set a custom out directory by comparing the out_dir
        # to the root_dir.
        if out_dir.find(root_dir) == 0:
            # Make target is simply file path relative to root
            module_info_target = os.path.relpath(module_file_path, root_dir)
        else:
            # If the user has set a custom out directory, generate an absolute
            # path for module info targets.
            logging.debug('User customized out dir!')
            module_file_path = os.path.join(
                os.environ.get(constants.ANDROID_PRODUCT_OUT), _MODULE_INFO)
            module_info_target = module_file_path
        if not os.path.isfile(module_file_path) or force_build:
            logging.debug('Generating %s - this is required for '
                          'initial runs.', _MODULE_INFO)
            atest_utils.build([module_info_target],
                              logging.getLogger().isEnabledFor(logging.DEBUG))
        return module_info_target, module_file_path

    def _load_module_info_file(self, force_build, module_file):
        """Load the module file.

        Args:
            force_build: Boolean to indicate if we should rebuild the
                         module_info file regardless if it's created or not.
            module_file: String of path to file to load up. Used for testing.

        Returns:
            Tuple of module_info_target and dict of json.
        """
        # If module_file is specified, we're testing so we don't care if
        # module_info_target stays None.
        module_info_target = None
        file_path = module_file
        if not file_path:
            module_info_target, file_path = self._discover_mod_file_and_target(
                force_build)
        with open(file_path) as json_file:
            mod_info = json.load(json_file)
        return module_info_target, mod_info

    @staticmethod
    def _get_path_to_module_info(name_to_module_info):
        """Return the path_to_module_info dict.

        Args:
            name_to_module_info: Dict of module name to module info dict.

        Returns:
            Dict of module path to module info dict.
        """
        path_to_module_info = {}
        for mod_name, mod_info in name_to_module_info.items():
            # Cross-compiled and multi-arch modules actually all belong to
            # a single target so filter out these extra modules.
            if mod_name != mod_info.get(constants.MODULE_NAME, ''):
                continue
            for path in mod_info.get(constants.MODULE_PATH, []):
                mod_info[constants.MODULE_NAME] = mod_name
                # There could be multiple modules in a path.
                if path in path_to_module_info:
                    path_to_module_info[path].append(mod_info)
                else:
                    path_to_module_info[path] = [mod_info]
        return path_to_module_info

    def is_module(self, name):
        """Return True if name is a module, False otherwise."""
        return name in self.name_to_module_info

    def get_paths(self, name):
        """Return paths of supplied module name, Empty list if non-existent."""
        info = self.name_to_module_info.get(name)
        if info:
            return info.get(constants.MODULE_PATH, [])
        return []

    def get_module_names(self, rel_module_path):
        """Get the modules that all have module_path.

        Args:
            rel_module_path: path of module in module-info.json

        Returns:
            List of module names.
        """
        return [m.get(constants.MODULE_NAME)
                for m in self.path_to_module_info.get(rel_module_path, [])]

    def get_module_info(self, mod_name):
        """Return dict of info for given module name, None if non-existent."""
        return self.name_to_module_info.get(mod_name)

    def is_suite_in_compatibility_suites(self, suite, mod_info):
        """Check if suite exists in the compatibility_suites of module-info.

        Args:
            suite: A string of suite name.
            mod_info: Dict of module info to check.

        Returns:
            True if it exists in mod_info, False otherwise.
        """
        return suite in mod_info.get(constants.MODULE_COMPATIBILITY_SUITES, [])

    def get_testable_modules(self, suite=None):
        """Return the testable modules of the given suite name.

        Args:
            suite: A string of suite name. Set to None to return all testable
            modules.

        Returns:
            List of testable modules. Empty list if non-existent.
            If suite is None, return all the testable modules in module-info.
        """
        modules = set()
        for _, info in self.name_to_module_info.items():
            if self.is_testable_module(info):
                if suite:
                    if self.is_suite_in_compatibility_suites(suite, info):
                        modules.add(info.get(constants.MODULE_NAME))
                else:
                    modules.add(info.get(constants.MODULE_NAME))
        return modules

    def is_testable_module(self, mod_info):
        """Check if module is something we can test.

        A module is testable if:
          - it's installed, or
          - it's a robolectric module (or shares path with one).

        Args:
            mod_info: Dict of module info to check.

        Returns:
            True if we can test this module, False otherwise.
        """
        if not mod_info:
            return False
        if mod_info.get(constants.MODULE_INSTALLED) and self.has_test_config(mod_info):
            return True
        if self.is_robolectric_test(mod_info.get(constants.MODULE_NAME)):
            return True
        return False

    def has_test_config(self, mod_info):
        """Validate if this module has a test config.

        A module can have a test config in the following manner:
          - AndroidTest.xml at the module path.
          - test_config be set in module-info.json.
          - Auto-generated config via the auto_test_config key in module-info.json.

        Args:
            mod_info: Dict of module info to check.

        Returns:
            True if this module has a test config, False otherwise.
        """
        # Check if test_config in module-info is set.
        for test_config in mod_info.get(constants.MODULE_TEST_CONFIG, []):
            if os.path.isfile(os.path.join(self.root_dir, test_config)):
                return True
        # Check for AndroidTest.xml at the module path.
        for path in mod_info.get(constants.MODULE_PATH, []):
            if os.path.isfile(os.path.join(self.root_dir, path,
                                           constants.MODULE_CONFIG)):
                return True
        # Check if the module has an auto-generated config.
        return self.is_auto_gen_test_config(mod_info.get(constants.MODULE_NAME))

    def get_robolectric_test_name(self, module_name):
        """Returns runnable robolectric module name.

        There are at least 2 modules in every robolectric module path, return
        the module that we can run as a build target.

        Arg:
            module_name: String of module.

        Returns:
            String of module that is the runnable robolectric module, None if
            none could be found.
        """
        module_name_info = self.name_to_module_info.get(module_name)
        if not module_name_info:
            return None
        module_paths = module_name_info.get(constants.MODULE_PATH, [])
        if module_paths:
            for mod in self.get_module_names(module_paths[0]):
                mod_info = self.get_module_info(mod)
                if test_finder_utils.is_robolectric_module(mod_info):
                    return mod
        return None

    def is_robolectric_test(self, module_name):
        """Check if module is a robolectric test.

        A module can be a robolectric test if the specified module has their
        class set as ROBOLECTRIC (or shares their path with a module that does).

        Args:
            module_name: String of module to check.

        Returns:
            True if the module is a robolectric module, else False.
        """
        # Check 1, module class is ROBOLECTRIC
        mod_info = self.get_module_info(module_name)
        if mod_info and test_finder_utils.is_robolectric_module(mod_info):
            return True
        # Check 2, shared modules in the path have class ROBOLECTRIC_CLASS.
        if self.get_robolectric_test_name(module_name):
            return True
        return False

    def is_auto_gen_test_config(self, module_name):
        """Check if the test config file will be generated automatically.

        Args:
            module_name: A string of the module name.

        Returns:
            True if the test config file will be generated automatically.
        """
        if self.is_module(module_name):
            mod_info = self.name_to_module_info.get(module_name)
            auto_test_config = mod_info.get('auto_test_config', [])
            return auto_test_config and auto_test_config[0]
        return False
