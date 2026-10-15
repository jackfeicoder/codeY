package io.github.jackfeicoder.codey.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 多模型配置测试：验证别名选择、配置隔离、认证方式、超时继承和不可变切换。 */
class ModelProfilesTest {
    /** 同一供应商共用凭证与超时，实际配置只含明确选择的一个模型。 */
    @Test
    void commaSeparatedModelsShareProviderSettingsAndSelectExactId() {
        var values = settings();
        values.put("CODEY_PROFILE_CLOUD_MODEL", "first, second, Vendor/Model:v2");
        var profiles = load(values);
        assertEquals(List.of("first", "second", "Vendor/Model:v2"), profiles.modelIdsFor("CLOUD"));
        assertEquals("first", profiles.activeConfig().model());
        var selected = profiles.select(" CLOUD   Vendor/Model:v2 ");
        assertEquals("cloud", selected.activeProfile());
        assertEquals("cloud Vendor/Model:v2", selected.activeSelection());
        assertEquals("Vendor/Model:v2", selected.activeConfig().model());
        assertEquals(profiles.activeConfig().baseUrl(), selected.activeConfig().baseUrl());
        assertEquals(profiles.activeConfig().apiKey(), selected.activeConfig().apiKey());
        assertEquals(profiles.activeConfig().timeout(), selected.activeConfig().timeout());
        assertEquals("first", profiles.activeConfig().model());
        assertThrows(ConfigException.class, () -> profiles.select("cloud missing"));
        assertThrows(ConfigException.class, () -> profiles.select("cloud vendor/model:v2"));
        assertThrows(ConfigException.class, () -> profiles.select("cloud first second"));
    }

    /** 目录每个模型独立一行，选择第一个占位模型失败时仍可选择后面的已配置模型。 */
    @Test
    void catalogListsEachModelAndReorderingDoesNotChangeExplicitSelection() {
        var values = settings();
        values.put("CODEY_PROFILE_CLOUD_MODEL", "YOUR_MODEL,second");
        var catalog = ConfigLoader.loadModelCatalog(directory, values);
        assertEquals(new ModelProfiles.ProfileInfo("cloud", "YOUR_MODEL", false), catalog.get(0));
        assertEquals(new ModelProfiles.ProfileInfo("cloud", "second", true), catalog.get(1));
        assertEquals("second", load(values).configFor("cloud second").model());
        values.put("CODEY_PROFILE_CLOUD_MODEL", "second,first");
        assertEquals("first", ConfigLoader.loadProfiles(directory, values, "cloud first").activeConfig().model());
    }

    /** 空项、重复项、含空格 ID 都报告模型字段，不回显完整原始配置。 */
    @ParameterizedTest
    @ValueSource(strings = {"first,", ",first", "first,,second", "first,first", "first,two words"})
    void rejectsMalformedModelListsOnUse(String ids) {
        var values = settings();
        values.put("CODEY_PROFILE_CLOUD_MODEL", ids);
        var profiles = load(values);
        assertTrue(assertThrows(ConfigException.class, profiles::activeConfig).getMessage().contains("CODEY_PROFILE_CLOUD_MODEL"));
        assertEquals("local-model", profiles.configFor("local").model());
        assertFalse(profiles.listInfo().get(0).configured());
    }

    /** 目录独立于默认别名是否存在，配置不完整标记而非隐藏；目录 DTO 没有密钥。 */
    @Test
    void catalogListsProfilesWithoutValidDefaultOrExposingCredentials() {
        Map<String, String> values = new java.util.HashMap<>();
        values.put("CODEY_PROFILE_NAMES", "ready,unfinished");
        values.put("CODEY_ACTIVE_PROFILE", "removed");
        values.put("CODEY_PROFILE_READY_BASE_URL", "http://127.0.0.1:8000/v1");
        values.put("CODEY_PROFILE_READY_MODEL", "ready-model");
        values.put("CODEY_PROFILE_READY_API_KEY", "fixture-secret");
        values.put("CODEY_PROFILE_UNFINISHED_MODEL", "pending-model");
        var entries = ConfigLoader.loadModelCatalog(directory, values);
        assertEquals(List.of(new ModelProfiles.ProfileInfo("ready", "ready-model", true),
                new ModelProfiles.ProfileInfo("unfinished", "pending-model", false)), entries);
        assertFalse(entries.toString().contains("fixture-secret"));
    }
    /** JUnit 为本次测试创建的临时目录，与真实项目配置隔离。 */
    @TempDir
    Path directory;

    /** 验证多个别名分别加载自己的模型和密钥。 */
    @Test
    void keepsSelectedAndOtherProfilesSeparate() {
        // 加载得到的多配置快照，用于检查别名与目标字段。
        ModelProfiles profiles = load(settings());
        assertEquals(List.of("cloud", "local", "gateway"), profiles.profileNames());
        assertEquals("cloud", profiles.activeProfile());
        assertEquals("cloud-model", profiles.activeConfig().model());
        assertEquals("fixture-cloud-key", profiles.activeConfig().apiKey());
        assertEquals("gateway-model", profiles.configFor("gateway").model());
        assertEquals("fixture-gateway-key", profiles.configFor("gateway").apiKey());
    }

    /** 验证命令行指定别名优先于缺失或错误的默认选择。 */
    @Test
    void cliOverrideWinsWhenDefaultIsMissingOrInvalid() {
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = settings();
        values.put("CODEY_ACTIVE_PROFILE", "unknown");
        assertEquals("local", ConfigLoader.loadProfiles(directory, values, "local").activeProfile());
        values.remove("CODEY_ACTIVE_PROFILE");
        assertEquals(AuthMode.NONE, ConfigLoader.loadProfiles(directory, values, "local").activeConfig().authMode());
    }

    /** 验证环境变量可覆盖命名字段以及默认 Profile。 */
    @Test
    void environmentOverridesFileFieldsAndDefaultSelection() throws IOException {
        Files.writeString(directory.resolve(".env"), """
                CODEY_PROFILE_NAMES=cloud,local
                CODEY_ACTIVE_PROFILE=cloud
                CODEY_PROFILE_LOCAL_BASE_URL=http://localhost:8000/v1
                CODEY_PROFILE_LOCAL_AUTH=none
                CODEY_PROFILE_LOCAL_MODEL=file-model
                """);
        // 加载得到的多配置快照，用于检查别名与目标字段。
        ModelProfiles profiles = load(Map.of("CODEY_ACTIVE_PROFILE", "local",
                "CODEY_PROFILE_LOCAL_MODEL", "environment-model"));
        assertEquals("local", profiles.activeProfile());
        assertEquals("environment-model", profiles.activeConfig().model());
    }

    /** 验证别名选择忽略大小写，并去除首尾空白。 */
    @Test
    void aliasesAreCaseInsensitive() {
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = settings();
        values.put("CODEY_PROFILE_NAMES", "Cloud,LOCAL,Gateway");
        values.put("CODEY_ACTIVE_PROFILE", "CLOUD");
        assertEquals("cloud", load(values).activeProfile());
        assertEquals("local", load(values).select(" LOCAL ").activeProfile());
    }

    /** 验证空项、非法字符和大小写重复别名会报错。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "cloud,", "cloud,,local", "cloud,CLOUD", "cloud-name", "1cloud", "cloud.local"})
    void rejectsInvalidOrDuplicateAliasLists(String names) {
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = settings();
        values.put("CODEY_PROFILE_NAMES", names);
        assertThrows(ConfigException.class, () -> load(values));
    }

    /** 验证默认别名必须存在并属于声明列表。 */
    @Test
    void rejectsUnknownOrMissingActiveProfile() {
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = settings();
        values.put("CODEY_ACTIVE_PROFILE", "unknown");
        assertThrows(ConfigException.class, () -> load(values));
        values.remove("CODEY_ACTIVE_PROFILE");
        assertTrue(assertThrows(ConfigException.class, () -> load(values)).getMessage().contains("CODEY_ACTIVE_PROFILE"));
    }

    /** 验证无认证配置允许缺少密钥，并丢弃多余密钥。 */
    @Test
    void noneAuthAllowsMissingKeyAndDiscardsConfiguredKey() {
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = settings();
        assertEquals("", load(values).configFor("local").apiKey());
        values.put("CODEY_PROFILE_LOCAL_API_KEY", "unused-fixture-key");
        // 无认证本地配置，验证密钥省略与丢弃规则。
        LlmConfig local = load(values).configFor("local");
        assertEquals("", local.apiKey());
        assertEquals(AuthMode.NONE, local.authMode());
        assertFalse(local.toString().contains("unused-fixture-key"));
    }

    /** 验证命名配置缺密钥时定位对应字段，不借用旧版凭证。 */
    @Test
    void missingBearerKeyUsesProfileSpecificFieldAndNeverLegacyFallback() {
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = settings();
        values.remove("CODEY_PROFILE_CLOUD_API_KEY");
        values.put("CODEY_LLM_API_KEY", "legacy-fixture-key");
        // 预期抛出的异常，用于核对类别、字段定位或脱敏。
        ConfigException error = assertThrows(ConfigException.class, () -> load(values).activeConfig());
        assertTrue(error.getMessage().contains("CODEY_PROFILE_CLOUD_API_KEY"));
        assertFalse(error.getMessage().contains("legacy-fixture-key"));
    }

    /** 验证未选中的不完整配置不会阻塞当前可用配置。 */
    @Test
    void inactiveIncompleteProfileDoesNotBlockSelectedModel() {
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = settings();
        values.put("CODEY_PROFILE_NAMES", "cloud,local,gateway,incomplete");
        // 加载得到的多配置快照，用于检查别名与目标字段。
        ModelProfiles profiles = load(values);
        assertEquals("cloud-model", profiles.activeConfig().model());
        assertEquals(4, profiles.profileNames().size());
        assertThrows(ConfigException.class, () -> profiles.configFor("incomplete"));
    }

    /** 验证目标校验失败后原来的选择保持不变。 */
    @Test
    void failedSelectionLeavesOriginalActiveProfileUnchanged() {
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = settings();
        values.remove("CODEY_PROFILE_GATEWAY_API_KEY");
        // 切换前的配置快照，应保持原选择和内容不变。
        ModelProfiles original = load(values);
        assertThrows(ConfigException.class, () -> original.select("gateway"));
        assertEquals("cloud", original.activeProfile());
        assertEquals("fixture-cloud-key", original.activeConfig().apiKey());
        assertThrows(ConfigException.class, () -> original.select("unknown"));
    }

    /** 验证成功切换创建新快照，不受原输入 Map 后续修改影响。 */
    @Test
    void successfulSelectionReturnsIndependentImmutableSnapshot() {
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = settings();
        // 切换前的配置快照，应保持原选择和内容不变。
        ModelProfiles original = load(values);
        // 切换成功得到的新快照，与原快照相互独立。
        ModelProfiles selected = original.select("gateway");
        values.put("CODEY_PROFILE_GATEWAY_API_KEY", "changed-after-load");
        assertEquals("cloud", original.activeProfile());
        assertEquals("gateway", selected.activeProfile());
        assertEquals("fixture-gateway-key", selected.activeConfig().apiKey());
        assertThrows(UnsupportedOperationException.class, () -> selected.profileNames().add("other"));
        assertFalse(selected.toString().contains("fixture-gateway-key"));
    }

    /** 验证单配置超时优先于公共超时。 */
    @Test
    void profileTimeoutOverridesGlobalDefault() {
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = settings();
        values.put("CODEY_LLM_TIMEOUT_SECONDS", "45");
        values.put("CODEY_PROFILE_LOCAL_TIMEOUT_SECONDS", "30");
        // 加载得到的多配置快照，用于检查别名与目标字段。
        ModelProfiles profiles = load(values);
        assertEquals(Duration.ofSeconds(45), profiles.activeConfig().timeout());
        assertEquals(Duration.ofSeconds(30), profiles.configFor("local").timeout());
    }

    /** 验证显式空配置不会回退到公共超时。 */
    @Test
    void blankProfileTimeoutDoesNotFallBack() {
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = settings();
        values.put("CODEY_LLM_TIMEOUT_SECONDS", "45");
        values.put("CODEY_PROFILE_LOCAL_TIMEOUT_SECONDS", "");
        // 预期抛出的异常，用于核对类别、字段定位或脱敏。
        ConfigException error = assertThrows(ConfigException.class, () -> load(values).configFor("local"));
        assertTrue(error.getMessage().contains("CODEY_PROFILE_LOCAL_TIMEOUT_SECONDS"));
    }

    /** 验证只接受支持的认证方式，错误不回显输入值。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "basic", "wrong-fixture-secret"})
    void rejectsUnsupportedAuthWithoutEchoingValue(String auth) {
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = settings();
        values.put("CODEY_PROFILE_CLOUD_AUTH", auth);
        // 预期抛出的异常，用于核对类别、字段定位或脱敏。
        ConfigException error = assertThrows(ConfigException.class, () -> load(values).activeConfig());
        assertTrue(error.getMessage().contains("CODEY_PROFILE_CLOUD_AUTH"));
        assertFalse(error.getMessage().contains("wrong-fixture-secret"));
    }

    /** 验证地址错误使用命名 Profile 的字段名。 */
    @Test
    void validationErrorsUseNamedUrlField() {
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = settings();
        values.put("CODEY_PROFILE_CLOUD_BASE_URL", "ftp://example.com/v1");
        // 预期抛出的异常，用于核对类别、字段定位或脱敏。
        ConfigException error = assertThrows(ConfigException.class, () -> load(values).activeConfig());
        assertTrue(error.getMessage().contains("CODEY_PROFILE_CLOUD_BASE_URL"));
    }

    /** 验证旧格式仍映射为 default，并保持默认 Bearer 认证。 */
    @Test
    void legacySingleModelBecomesDefaultProfile() {
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = Map.of("CODEY_LLM_BASE_URL", "https://example.com/v1",
                "CODEY_LLM_API_KEY", "legacy-fixture-key", "CODEY_LLM_MODEL", "legacy-model");
        // 加载得到的多配置快照，用于检查别名与目标字段。
        ModelProfiles profiles = load(values);
        assertEquals(List.of("default"), profiles.profileNames());
        assertEquals("default", profiles.activeProfile());
        assertEquals("legacy-model", profiles.activeConfig().model());
        assertEquals(AuthMode.BEARER, profiles.activeConfig().authMode());
        assertEquals("default", ConfigLoader.loadProfiles(directory, values, "default").activeProfile());
    }

    /** 从当前临时目录与传入的假环境加载配置快照。 */
    private ModelProfiles load(Map<String, String> values) {
        return ConfigLoader.loadProfiles(directory, values);
    }

    /** 创建三套独立的云端、本地和第三方测试配置。 */
    private static Map<String, String> settings() {
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = new HashMap<>();
        values.put("CODEY_PROFILE_NAMES", "cloud,local,gateway");
        values.put("CODEY_ACTIVE_PROFILE", "cloud");
        values.put("CODEY_PROFILE_CLOUD_BASE_URL", "https://cloud.example.com/v1");
        values.put("CODEY_PROFILE_CLOUD_API_KEY", "fixture-cloud-key");
        values.put("CODEY_PROFILE_CLOUD_MODEL", "cloud-model");
        values.put("CODEY_PROFILE_LOCAL_BASE_URL", "http://localhost:8000/v1");
        values.put("CODEY_PROFILE_LOCAL_MODEL", "local-model");
        values.put("CODEY_PROFILE_LOCAL_AUTH", "none");
        values.put("CODEY_PROFILE_GATEWAY_BASE_URL", "https://gateway.example.com/v1");
        values.put("CODEY_PROFILE_GATEWAY_API_KEY", "fixture-gateway-key");
        values.put("CODEY_PROFILE_GATEWAY_MODEL", "gateway-model");
        return values;
    }
}
