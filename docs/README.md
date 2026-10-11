# CodeY 文档索引

设计基线：2026-10-11。当前实现状态：M0 项目骨架。

## 阅读顺序

1. [architecture.md](architecture.md)：了解项目目标、目录与执行流程。
2. [modules.md](modules.md)：按模块理解责任和数据契约。
3. [roadmap.md](roadmap.md)：选择当前开发阶段，按验收条件推进。
4. [interview.md](interview.md)：完成模块后整理对应问题与项目证据。
5. [decisions.md](decisions.md)：记录采用某种设计的原因与代价。
6. [PaiCLI 分析快照](references/paicli-source-architecture-guide.md)：需要参考源码时查阅。

## 文档使用约定

- 除明确标注“已实现”的条目外，架构与接口均为目标设计。
- 实现发生变化时，同步更新文档；不要让规划中的功能被误解为已有能力。
- 完成阶段后，在 roadmap 勾选验收项，在 interview 补上代码位置、问题记录与评测证据。
- diagrams 中保存可编辑的 Mermaid 源文件。架构文档内嵌同样的图，修改时保持一致。
- PaiCLI 文档是原项目分析快照，不代表 CodeY 的功能或代码。其原有源码导航使用本机绝对路径，在 GitHub 上不具备可移植性。

## 参考资料

- [PaiCLI](https://gitcode.com/javabetter/paicli)：本次以用户本机工作区为主要分析依据；远程仓库与本地工作区可能不同。
- [Hello-Agents](https://github.com/datawhalechina/hello-agents)：按需参考框架、记忆、上下文工程相关章节。
- [Agent Harness 的概念划分](https://www.langchain.com/blog/agent-frameworks-runtimes-and-harnesses-oh-my)：理解框架、运行时与 Harness 的职责边界。
- [长期任务 Harness 实践](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)：理解进度交接、验收与跨会话工作。
