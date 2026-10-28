package io.github.jackfeicoder.codey.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 提示词资源加载器：从 classpath 读取 UTF-8 文本，开发运行和打包 JAR 使用同一路径。 */
public final class PromptLoader {
    /** 内置基础提示词在 classpath 中的绝对资源路径，与启动目录无关。 */
    private static final String BASE_RESOURCE = "/prompts/base.md";

    /** 加载器仅提供静态方法，无需创建实例。 */
    private PromptLoader() {
    }

    /** 返回基础提示词全文，供调用方通过 Message.system 注入模型请求。 */
    public static String loadBase() {
        return loadResource(BASE_RESOURCE);
    }

    /** 压缩专用指令，独立于业务 base.md，不把旧对话当作当前执行任务。 */
    public static String loadCompact() { return loadResource("/prompts/compact.md"); }
    /** Reduce 负责合并按时间排列的子摘要，旧摘要只参与一次。 */
    public static String loadCompactReduce() { return loadResource("/prompts/compact-reduce.md"); }

    /** 项目初始化专用指令。 */
    public static String loadInit() { return loadResource("/prompts/init.md"); }

    /** 成功任务后的长期记忆提取，不复用业务系统提示词。 */
    public static String loadMemoryExtract() { return loadResource("/prompts/memory-extract.md"); }

    /** 按资源路径读取文本；缺失、空白或读取失败时抛出可由 CLI 展示的异常。 */
    static String loadResource(String resourcePath) {
        // 通过类定位资源，支持从构建目录或 JAR 读取，try 自动关闭流。
        try (InputStream stream = PromptLoader.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new PromptException("Prompt resource is missing: " + resourcePath);
            }
            // 保留 Markdown 原文与换行；不需要解析标题或转换为 HTML。
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (content.isBlank()) {
                throw new PromptException("Prompt resource is blank: " + resourcePath);
            }
            return content;
        } catch (IOException failed) {
            throw new PromptException("Failed to read prompt resource: " + resourcePath, failed);
        }
    }
}
