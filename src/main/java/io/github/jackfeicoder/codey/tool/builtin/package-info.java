/**
 * ListFilesTool 浏览目录，SearchFileTool 按名称/路径模式定位文件，ReadFileTool 读取具体文件。
 * 工具互不调用；目录工具共用 filesystem 包的路径校验和有界遍历，均检查工作区范围。
 * 已连接生产 CLI 的工作区会话；内容搜索、修改和命令工具后续实现。
 * <p>实现状态与开发顺序见 docs/architecture.md。</p>
 */
package io.github.jackfeicoder.codey.tool.builtin;
