// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.platform;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.platform.ConstraintSettingInfo;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.util.CPU;
import com.google.devtools.build.lib.util.OS;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests of {@link Platform}. */
@RunWith(JUnit4.class)
public class PlatformTest extends BuildViewTestCase {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Before
  public void createPlatform() throws Exception {
    scratch.file(
        "constraint/BUILD",
        "constraint_setting(name = 'basic')",
        "constraint_value(name = 'foo',",
        "    constraint_setting = ':basic',",
        "    )",
        "platform(name = 'plat1',",
        "    constraint_values = [",
        "       ':foo',",
        "    ])");
  }

  @Test
  public void testPlatform() throws Exception {
    ConfiguredTarget platform = getConfiguredTarget("//constraint:plat1");
    assertThat(platform).isNotNull();

    PlatformInfo provider = Platform.platform(platform);
    assertThat(provider).isNotNull();
    assertThat(provider.constraints()).hasSize(1);
    ConstraintSettingInfo constraintSetting =
        ConstraintSettingInfo.create(makeLabel("//constraint:basic"));
    ConstraintValueInfo constraintValue =
        ConstraintValueInfo.create(constraintSetting, makeLabel("//constraint:foo"));
    assertThat(provider.constraints()).containsExactly(constraintValue);
    assertThat(provider.remoteExecutionProperties()).isEmpty();
  }

  @Test
  public void testPlatform_host() throws Exception {
    String currentCpu = CPU.getCurrent().getCanonicalName();
    String currentOs = OS.getCurrent().getCanonicalName();
    scratch.file(
        "host/BUILD",
        "constraint_setting(name = 'cpu')",
        "constraint_value(name = '" + currentCpu + "', constraint_setting = ':cpu')",
        "constraint_value(name = 'another_cpu', constraint_setting = ':cpu')",
        "constraint_setting(name = 'os')",
        "constraint_value(name = '" + currentOs + "', constraint_setting = ':os')",
        "constraint_value(name = 'another_os', constraint_setting = ':os')",
        "platform(name = 'host_platform',",
        "    host_platform = True,",
        "    host_cpu_constraints = [':" + currentCpu + "', ':another_cpu'],",
        "    host_os_constraints = [':" + currentOs + "', ':another_os'],",
        ")");

    ConfiguredTarget platform = getConfiguredTarget("//host:host_platform");
    assertThat(platform).isNotNull();

    PlatformInfo provider = Platform.platform(platform);
    assertThat(provider).isNotNull();

    // Check the CPU and OS.
    ConstraintSettingInfo cpuConstraint = ConstraintSettingInfo.create(makeLabel("//host:cpu"));
    ConstraintSettingInfo osConstraint = ConstraintSettingInfo.create(makeLabel("//host:os"));
    assertThat(provider.constraints())
        .containsExactly(
            ConstraintValueInfo.create(cpuConstraint, makeLabel("//host:" + currentCpu)),
            ConstraintValueInfo.create(osConstraint, makeLabel("//host:" + currentOs)));
  }

  @Test
  public void testPlatform_overlappingConstraintValueError() throws Exception {
    checkError(
        "constraint/overlap",
        "plat_overlap",
        "Duplicate constraint_values for constraint_setting //constraint:basic: "
            + "//constraint:foo, //constraint/overlap:bar",
        "constraint_value(name = 'bar',",
        "    constraint_setting = '//constraint:basic',",
        "    )",
        "platform(name = 'plat_overlap',",
        "    constraint_values = [",
        "       '//constraint:foo',",
        "       ':bar',",
        "    ])");
  }

  @Test
  public void testPlatform_remoteExecution() throws Exception {
    scratch.file(
        "constraint/remote/BUILD",
        "platform(name = 'plat_remote',",
        "    constraint_values = [",
        "       '//constraint:foo',",
        "    ],",
        "    remote_execution_properties = {",
        "        'foo': 'val1',",
        "        'bar': 'val2',",
        "    },",
        ")");

    ConfiguredTarget platform = getConfiguredTarget("//constraint/remote:plat_remote");
    assertThat(platform).isNotNull();

    PlatformInfo provider = Platform.platform(platform);
    assertThat(provider).isNotNull();
    assertThat(provider.remoteExecutionProperties())
        .containsExactlyEntriesIn(ImmutableMap.of("foo", "val1", "bar", "val2"));
  }

  @Test
  public void testPlatform_skylark() throws Exception {

    scratch.file(
        "test/platform/platform.bzl",
        "def _impl(ctx):",
        "  platform = ctx.attr.platform[platform_common.PlatformInfo]",
        "  return struct(",
        "    count = len(platform.constraints),",
        "    first_setting = platform.constraints[0].constraint.label,",
        "    first_value = platform.constraints[0].label)",
        "my_rule = rule(",
        "  implementation = _impl,",
        "  attrs = { 'platform': attr.label(providers = [platform_common.PlatformInfo])},",
        ")");

    scratch.file(
        "test/platform/BUILD",
        "load('//test/platform:platform.bzl', 'my_rule')",
        "my_rule(name = 'r',",
        "  platform = '//constraint:plat1')");

    ConfiguredTarget configuredTarget = getConfiguredTarget("//test/platform:r");
    assertThat(configuredTarget).isNotNull();

    int count = (int) configuredTarget.get("count");
    assertThat(count).isEqualTo(1);

    Label settingLabel = (Label) configuredTarget.get("first_setting");
    assertThat(settingLabel).isNotNull();
    assertThat(settingLabel).isEqualTo(makeLabel("//constraint:basic"));
    Label valueLabel = (Label) configuredTarget.get("first_value");
    assertThat(valueLabel).isNotNull();
    assertThat(valueLabel).isEqualTo(makeLabel("//constraint:foo"));
  }
}
