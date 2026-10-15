package io.github.jackfeicoder.codey.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 偏好文件使用临时目录，验证跨实例恢复、最小内容、损坏隔离与失败提示。 */
class ModelPreferenceStoreTest {
    /** 完整选择跨进程恢复，模型大小写保持；旧单别名文件仍按原格式读取。 */
    @Test
    void savesProviderAndModelIdTogether() {
        var store = new ModelPreferenceStore(directory);
        store.save(" QWEN  Vendor/Model:v2 ");
        assertEquals("qwen Vendor/Model:v2", new ModelPreferenceStore(directory).load().orElseThrow());
        assertThrows(ConfigException.class, () -> store.save("qwen first second"));
        assertEquals("qwen Vendor/Model:v2", store.load().orElseThrow());
    }

    /** 独立的用户数据目录。 */
    @TempDir Path directory;

    /** 缺失返回 empty，查看默认不创建目录；每次成功选择只替换一个别名文件。 */
    @Test
    void lastChoiceSurvivesRestartWithoutCredentialsOrTemporaryFiles() throws Exception {
        Path data = directory.resolve("data");
        ModelPreferenceStore store = new ModelPreferenceStore(data);
        assertTrue(store.load().isEmpty());
        assertFalse(Files.exists(data));
        store.save(" Cloud ");
        assertEquals("cloud", new ModelPreferenceStore(data).load().orElseThrow());
        store.save("local");
        assertEquals("local", new ModelPreferenceStore(data).load().orElseThrow());
        String json = Files.readString(data.resolve("model-preference.json"));
        assertEquals("{\"schemaVersion\":1,\"lastUsedProfile\":\"local\"}", json);
        try (var files = Files.list(data)) { assertEquals(1, files.count()); }
    }

    /** 残缺/重复/未知版本/溢出/额外字段/非法别名都不能作为默认配置。 */
    @ParameterizedTest
    @ValueSource(strings = {"{fixture-secret}", "[]", "{\"schemaVersion\":2,\"lastUsedProfile\":\"local\"}",
            "{\"schemaVersion\":4294967297,\"lastUsedProfile\":\"local\"}",
            "{\"schemaVersion\":1,\"lastUsedProfile\":\"local\",\"lastUsedProfile\":\"other\"}",
            "{\"schemaVersion\":1,\"lastUsedProfile\":\"local\"} {}",
            "{\"schemaVersion\":1,\"lastUsedProfile\":\"../fixture-secret\"}",
            "{\"schemaVersion\":1,\"lastUsedProfile\":null}",
            "{\"schemaVersion\":1,\"lastUsedProfile\":\"local\",\"apiKey\":\"fixture-secret\"}"})
    void corruptedPreferenceIsRejectedWithoutEchoingContents(String content) throws Exception {
        Files.writeString(directory.resolve("model-preference.json"), content);
        ConfigException error = assertThrows(ConfigException.class, () -> new ModelPreferenceStore(directory).load());
        assertFalse(error.getMessage().contains("fixture-secret"));
        assertEquals(content, Files.readString(directory.resolve("model-preference.json")));
    }

    /** 保存位置冲突明确报错，不把一次失败误报为已持久化。 */
    @Test
    void conflictingFileLocationReportsWriteFailure() throws Exception {
        Files.createDirectory(directory.resolve("model-preference.json"));
        assertThrows(ConfigException.class, () -> new ModelPreferenceStore(directory).save("local"));
        assertThrows(ConfigException.class, () -> new ModelPreferenceStore(directory).load());
    }
}
