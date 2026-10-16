/**
 * ListFilesTool 浏览目录，SearchFileTool 定位路径，GrepCodeTool 搜索正文，ReadFileTool 阅读，WriteFileTool 完整写入，EditFileTool 局部编辑。
 * 工具互不调用；目录工具共用 filesystem 包的路径校验和有界遍历，均检查工作区范围。
 * 读取与搜索共享有界 UTF-8 读取器，两个写工具共享快照与提交逻辑；已连接生产 CLI，命令工具后续实现。
 * <p>实现状态与开发顺序见 docs/architecture.md。</p>
 */
package io.github.jackfeicoder.codey.tool.builtin;
