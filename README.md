# CodeY

使用 Java 从零构建 CLI 编程 Agent，通过实际实现理解 Claude Code、Codex 等编程 Agent 的工作原理。

目标闭环：**接收任务 → 探索代码 → 修改文件 → 执行测试 → 根据结果继续修复 → 输出结果与验证证据**。

## 当前进度

当前是项目初始化骨架：已建立 Maven 工程、CLI 入口、模块目录和设计文档。LLM、Agent 循环、工具、记忆和 MCP 尚待实现；当前入口只显示项目说明。

## 技术基线

- Java 17、Maven 单模块。
- 基础包：`io.github.jackfeicoder.codey`。
- 首版计划采用 JDK HttpClient 接入兼容 Chat Completions 的接口，使用 Jackson 处理 JSON。
- 第一版聚焦单 Agent；上下文、持久化、MCP 按阶段加入。

## 构建与启动

需要 JDK 17+ 和 Maven 3.9+。首次构建需要下载 Maven 插件。

```shell
mvn clean verify
java -jar target/codey-0.1.0-SNAPSHOT.jar
java -jar target/codey-0.1.0-SNAPSHOT.jar --help
java -jar target/codey-0.1.0-SNAPSHOT.jar --version
```

当前版本没有运行时第三方依赖，因此普通 JAR 即可运行。加入依赖后，在 LLM 接入阶段同步配置可执行 JAR 的依赖打包。

## 文档入口

| 文档 | 用途 |
|---|---|
| [文档索引](docs/README.md) | 阅读顺序与文档维护规则 |
| [架构设计](docs/architecture.md) | 范围、技术栈、目录、分层与完整执行流程 |
| [模块与接口](docs/modules.md) | 模块职责、数据契约、错误与扩展边界 |
| [开发路线](docs/roadmap.md) | 每阶段的实现任务和验收标准 |
| [面试学习笔记](docs/interview.md) | 与开发阶段对应的问题、回答要点和项目证据 |
| [设计决策](docs/decisions.md) | 设计取舍及重新评估条件 |
| [PaiCLI 源码分析参考](docs/references/paicli-source-architecture-guide.md) | 原项目分析快照，帮助定位参考实现 |

## 学习方式

每完成一个阶段，都补充运行方法、执行证据和设计取舍。参考 PaiCLI 的工程场景与 Hello-Agents 的教学思路，用自己的实现验证理解。

第三方项目是学习参考。本仓库不包含 PaiCLI 的业务源码；参考文档中的源码链接指向分析时的本机目录。
