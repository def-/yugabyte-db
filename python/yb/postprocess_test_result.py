#!/usr/bin/env python3

# Copyright (c) Yugabyte, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
# or implied.  See the License for the specific language governing permissions and limitations
# under the License.

"""
Post-processes results of running a single YugabyteDB unit test (e.g. a C++ or Java test) and
creates a structured output file with a summary of those results. This includes test running time
and possible causes of test failure.
"""
import subprocess
import sys
import os
import logging
import yugabyte_pycommon  # type: ignore
import argparse
import xml.etree.ElementTree as ET
import json
import signal
import glob

from typing import Any, Dict, AnyStr

from yb.common_util import init_logging


# Example test failure (from C++)
# <?xml version="1.0" ?><testsuites disabled="0" errors="0" failures="1" name="AllTests" tests="1
#                                   time="0.125" timestamp="2019-03-07T23:04:23">
#   <testsuite disabled="0" errors="0" failures="1" name="StringUtilTest" tests="1" time="0.125">
#     <testcase classname="StringUtilTest" name="TestCollectionToString" status="run" time="0.125">
#       <failure message="../../src/yb/gutil/strings/string_util-test.cc:110
# Failed" type="">
# <![CDATA[../../src/yb/gutil/strings/string_util-test.cc:110
# Failed]]></failure>
#     </testcase>
#   </testsuite>
# </testsuites>

# Example test success (Java):
# <?xml version="1.0" encoding="UTF-8"?>
# <testsuite name="org.yb.cql.SimpleQueryTest" time="13.539" tests="1" errors="0" skipped="0"
#            failures="0">
#   <properties>
#     <!-- ... -->
#   </properties>
#   <testcase name="restrictionOnRegularColumnWithStaticColumnPresentTest"
#             classname="org.yb.cql.SimpleQueryTest" time="12.347"/>
# </testsuite>

# Example test failure (Java):
# <?xml version="1.0" encoding="UTF-8"?>
# <testsuite name="com.yugabyte.jedis.TestReadFromFollowers"
#            time="11.383" tests="1" errors="1" skipped="0" failures="0">
#   <properties>
#     <!-- ... -->
#   </properties>
#   <testcase name="testSameZoneOps[1]" classname="com.yugabyte.jedis.TestReadFromFollowers"
#             time="11.209">
#     <error message="Could not get a resource from the pool"
#            type="redis.clients.jedis.exceptions.JedisConnectionException">
#       redis.clients.jedis.exceptions.JedisConnectionException: Could not get a resource from the
#       pool
#     </error>
#     <system-out>...</system-out>
#   </testcase>
# </testsuite>

# Other related resources:
# https://llg.cubic.org/docs/junit/
# https://stackoverflow.com/questions/442556/spec-for-junit-xml-output/4926073#4926073
# https://raw.githubusercontent.com/windyroad/JUnit-Schema/master/JUnit.xsd


# Create list of signals to search for from signal module.
SIGNALS = [name for name in dir(signal) if name.startswith('SIG') and 'SIG_' not in name]
SIGNAL_FORMAT_STRING = 'signal_{}'

# Derived from build-support/common-test-env.sh:did_test_succeed()
FAIL_TAG_AND_PATTERN: Dict[str, str] = {
    'timeout': 'Timeout reached',
    'memory_leak': 'LeakSanitizer: detected memory leaks',
    'asan_heap_use_after_free': 'AddressSanitizer: heap-use-after-free',
    'asan_undefined': 'AddressSanitizer: undefined-behavior',
    'undefined_behavior': 'UndefinedBehaviorSanitizer: undefined-behavior',
    'tsan': 'ThreadSanitizer',
    'leak_check_failure': 'Leak check.*detected leaks',
    'segmentation_fault': 'Segmentation fault: ',
    'gtest': r'^\[  FAILED  \]',
    SIGNAL_FORMAT_STRING: '|'.join(SIGNALS),
    'check_failed': 'Check failed: ',
    'java_build': r'^\[INFO\] BUILD FAILURE$',
}


def rename_key(d: Dict[str, Any], key: str, new_key: str) -> None:
    if key in d:
        d[new_key] = d[key]
        del d[key]


def del_default_value(d: Dict[str, Any], key: str, default_value: Any) -> None:
    if key in d and d[key] == default_value:
        del d[key]


def count_subelements(root: ET.Element, el_name: str) -> int:
    return len(list(root.iter(el_name)))


def set_element_count_property(test_kvs: Dict[str, Any], root: ET.Element, el_name: str,
                               field_name: str, increment_by: int = 0) -> None:
    count = count_subelements(root, el_name) + increment_by
    if count != 0:
        test_kvs[field_name] = count


class Postprocessor:

    #  Use custom_args for unit testing
    def __init__(self, custom_args=None):  # type: ignore
        parser = argparse.ArgumentParser(
            description=__doc__)
        parser.add_argument(
            '--yb-src-root',
            help='Root directory of YugaByte source code',
            required=True)
        parser.add_argument(
            '--build-root',
            help='Root directory of YugaByte build',
            required=True)
        parser.add_argument(
            '--test-log-path',
            help='Main log file of this test',
            required=True)
        parser.add_argument(
            '--junit-xml-path',
            help='JUnit-compatible XML result file of this test',
            required=True)
        parser.add_argument(
            '--language',
            help='The language this unit test is written in',
            choices=['cxx', 'java'],
            required=True)
        parser.add_argument(
            '--test-failed',
            help='Flag computed by the Bash-based test framework saying whether the test failed',
            choices=['true', 'false']
        )
        parser.add_argument(
            '--fatal-details-path-prefix',
            help='Prefix of fatal failure details files'
        )
        parser.add_argument(
            '--class-name',
            help='Class name (helpful when there is no test result XML file)'
        )
        parser.add_argument(
            '--test-name',
            help='Test name within the class (helpful when there is no test result XML file)'
        )
        parser.add_argument(
            '--java-module-dir',
            help='Java module directory containing this test'
        )
        parser.add_argument(
            '--cxx-rel-test-binary',
            help='C++ test binary path relative to the build directory')
        parser.add_argument(
            '--extra-error-log-path',
            help='Extra error log path (stdout/stderr of the outermost test invocation)')
        # By default, argparse picks sys.argv[1:]
        self.args = parser.parse_args(sys.argv[1:] if not custom_args else custom_args)
        self.test_log_path = self.args.test_log_path
        if not os.path.exists(self.test_log_path) and os.path.exists(self.test_log_path + '.gz'):
            self.test_log_path += '.gz'
        logging.info("Log path: %s", self.test_log_path)
        logging.info("JUnit XML path: %s", self.args.junit_xml_path)
        self.real_build_root = os.path.realpath(self.args.build_root)
        self.real_yb_src_root = os.path.realpath(self.args.yb_src_root)
        self.rel_test_log_path = self.path_rel_to_src_root(self.test_log_path)
        self.rel_junit_xml_path = self.path_rel_to_src_root(self.args.junit_xml_path)

        self.rel_extra_error_log_path = None
        if self.args.extra_error_log_path:
            self.rel_extra_error_log_path = self.path_rel_to_src_root(
                self.args.extra_error_log_path)

        self.fatal_details_paths = None
        if self.args.class_name:
            logging.info("Externally specified class name: %s", self.args.class_name)
        if self.args.test_name:
            logging.info("Externally specified test name: %s", self.args.test_name)

        if (not self.args.fatal_details_path_prefix and
                self.args.test_name and
                self.args.test_log_path.endswith('-output.txt')):
            # This mimics the logic in MiniYBCluster.configureAndStartProcess that comes up with a
            # prefix like "org.yb.client.TestYBClient.testKeySpace.fatal_failure_details.", while
            # the Java test log paths are of the form "org.yb.client.TestYBClient-output.txt".
            self.args.fatal_details_path_prefix = '.'.join([
                self.args.test_log_path[:-len('-output.txt')],
                self.args.test_name,
                'fatal_failure_details'
            ]) + '.'

        if self.args.fatal_details_path_prefix:
            self.fatal_details_paths = [
                self.path_rel_to_src_root(path)
                for path in glob.glob(self.args.fatal_details_path_prefix + '*')
            ]

        self.test_descriptor_str = os.environ.get('YB_TEST_DESCRIPTOR')

    def path_rel_to_build_root(self, path: AnyStr) -> AnyStr:
        return os.path.relpath(os.path.realpath(path), self.real_build_root)

    def path_rel_to_src_root(self, path: AnyStr) -> AnyStr:
        return os.path.relpath(os.path.realpath(path), self.real_yb_src_root)

    def set_common_test_kvs(self, test_kvs: Dict[str, Any]) -> None:
        """
        Set common data for all tests produced by this invocation of the script. In practice there
        won't be too much duplication as this script will be invoked for one test at a time.
        """
        test_kvs["language"] = self.args.language
        if self.args.cxx_rel_test_binary:
            test_kvs["cxx_rel_test_binary"] = self.args.cxx_rel_test_binary
        test_kvs["log_path"] = self.rel_test_log_path
        test_kvs["junit_xml_path"] = self.rel_junit_xml_path
        if self.fatal_details_paths:
            test_kvs["fatal_details_paths"] = self.fatal_details_paths
        if self.test_descriptor_str:
            test_kvs["test_descriptor"] = self.test_descriptor_str

        if self.rel_extra_error_log_path:
            test_kvs["extra_error_log_path"] = self.rel_extra_error_log_path

    def set_fail_tags(self, test_kvs: Dict[str, Any]) -> None:
        if test_kvs.get('num_errors', 0) > 0 or test_kvs.get('num_failures', 0) > 0:
            # Parse for fail tags
            for tag, pattern in FAIL_TAG_AND_PATTERN.items():
                # When using zgrep, given files are uncompressed if necessary and fed to grep
                grep_command = subprocess.run(['zgrep', '-Eoh', pattern, self.test_log_path],
                                              capture_output=True)
                if grep_command.returncode == 0:
                    # When grep-ing for a signal, there can be multiple matches.
                    # Filter stdout to get unique, non-empty matches and append a tag for each.
                    grep_stdout = sorted(set(grep_command.stdout.decode('utf-8').split('\n')))
                    grep_stdout = [match for match in grep_stdout if match != '']

                    if tag == SIGNAL_FORMAT_STRING:
                        # Get the matching signals from stdout
                        fail_tags = [tag.format(match) for match in grep_stdout]
                    else:
                        fail_tags = [tag]

                    test_kvs['fail_tags'] = test_kvs.get('fail_tags', []) + fail_tags

                elif grep_command.returncode > 1:
                    logging.warning("Error running '{}': \n{}".format(
                        ' '.join(grep_command.args), grep_command.stderr.decode('utf-8'))
                    )
                    test_kvs['processing_errors'] = grep_command.stderr.decode('utf-8').split()

    def run(self) -> None:
        junit_xml = ET.parse(self.args.junit_xml_path)
        tests = []
        for test_case_element in junit_xml.iter('testcase'):
            test_kvs = dict(test_case_element.attrib)  # type: Dict[str, Any]
            set_element_count_property(test_kvs, test_case_element, 'error', 'num_errors')
            set_element_count_property(test_kvs, test_case_element, 'failure', 'num_failures')

            # In C++ tests, we don't get the "skipped" attribute, but we get the status="notrun"
            # attribute.
            set_element_count_property(
                test_kvs, test_case_element, 'skipped', 'num_skipped',
                increment_by=1 if test_kvs.get('status') == 'notrun' else 0)

            parsing_errors = []
            if "time" in test_kvs and isinstance(test_kvs["time"], str):
                # Remove commas to parse numbers like "1,275.516".
                time_str = test_kvs["time"].replace(",", "")
                try:
                    test_kvs["time"] = float(time_str)
                except ValueError as ex:
                    test_kvs["time"] = None
                    parsing_errors.append(
                        "Could not parse time: %s. Error: %s" % (time_str, str(ex))
                    )
            if parsing_errors:
                test_kvs["parsing_errors"] = parsing_errors
            rename_key(test_kvs, 'name', 'test_name')
            rename_key(test_kvs, 'classname', 'class_name')
            self.set_common_test_kvs(test_kvs)
            self.set_fail_tags(test_kvs)
            tests.append(test_kvs)

        output_path = os.path.splitext(self.args.junit_xml_path)[0] + '_test_report.json'
        if len(tests) == 1:
            tests = tests[0]  # type: ignore
        with open(output_path, 'w') as output_file:
            output_file.write(
                json.dumps(tests, indent=2)
            )
        logging.info("Wrote JSON test report file: %s", output_path)


def main() -> None:
    postprocessor = Postprocessor()  # type: ignore
    postprocessor.run()


if __name__ == '__main__':
    init_logging(verbose=False)
    main()
