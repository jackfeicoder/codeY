package io.github.jackfeicoder.codey.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证内置提示词的 UTF-8 加载，以及缺失、空白资源的明确失败。 */
class PromptLoaderTest {
    @org.junit.jupiter.api.Test void compactionPromptIsSeparateAndSpecifiesFactsConstraintsAndJson() {
        String text = PromptLoader.loadCompact();
        org.junit.jupiter.api.Assertions.assertNotEquals(PromptLoader.loadBase(), text);
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("constraints"));
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("verification"));
        org.junit.jupiter.api.Assertions.assertTrue(text.contains("只生成摘要"));
    }
    /** 基础提示词保留 Markdown 标题和中文文本，不依赖工作目录。 */
    @Test
    void loadsUtf8BasePrompt() {
        // 加载真实的内置资源，验证资源可达性与编码。
        String prompt = PromptLoader.loadBase();
        assertTrue(prompt.startsWith("# CodeY 基础提示词"));
        assertTrue(prompt.contains("默认使用中文"));
        assertFalse(prompt.contains("\uFFFD"));
    }

    /** 缺失内置资源时明确报错，避免静默发送没有指令的请求。 */
    @Test
    void missingResourceReportsPath() {
        // 错误信息仅包含资源路径，便于定位打包遗漏。
        PromptException error = assertThrows(PromptException.class,
                () -> PromptLoader.loadResource("/prompts/missing-fixture.txt"));
        assertTrue(error.getMessage().contains("/prompts/missing-fixture.txt"));
    }

    /** 仅含空白的资源视为错误，避免误把占位文件当作提示词。 */
    @Test
    void blankResourceFails() {
        assertThrows(PromptException.class,
                () -> PromptLoader.loadResource("/prompts/blank-fixture.txt"));
    }
}
