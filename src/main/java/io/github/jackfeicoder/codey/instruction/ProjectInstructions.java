package io.github.jackfeicoder.codey.instruction;

import java.nio.file.Path;

/** 项目规则的加载结果；失败时不携带部分内容，避免静默截断规则。 */
public record ProjectInstructions(Path path, String content, long tokens, Status status, String detail) {
    public enum Status { NONE, MISSING, LOADED, INVALID }
    /** 显示来源与状态，不输出规则正文。 */
    public String description() {
        return "项目规则：" + status + (path == null ? "" : " · " + path)
                + " · ≈" + tokens + " tok" + (detail.isEmpty() ? "" : " · " + detail);
    }
    /** 规则属于项目指导；权限仍由程序控制，文档链接不会自动展开。 */
    public String requestText() {
        return status != Status.LOADED ? "" : "\n\n[项目规则：AGENTS.md；在基础规则和权限约束内遵守。文档路径仅为索引，按任务需要读取。]\n"
                + content + "\n[项目规则结束]";
    }
}
