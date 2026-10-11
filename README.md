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

## 架构概览

以下为目标架构。当前仅有工程骨架和说明型 CLI，图中的 Agent、LLM、工具及存储能力按开发路线逐步实现。

```mermaid
flowchart LR
    user["用户"] --> cli["CLI 与组件装配"]
    cli --> agent["AgentLoop"]
    config["Config"] --> cli
    agent --> context["ContextBuilder"]
    context --> session["Session 与消息历史"]
    context --> memory["Memory 检索"]
    agent --> llm["LlmClient"]
    llm --> provider["外部模型 API"]
    agent --> executor["ToolExecutor"]
    executor --> policy["路径与审批策略"]
    policy --> builtin["文件与命令工具"]
    policy --> mcp["MCP 工具适配"]
    builtin --> workspace["工作区与子进程"]
    mcp --> server["外部 MCP 服务"]
    agent --> events["执行事件"]
    events --> cli
    events --> store["SessionStore 与轨迹"]
```

| 模块 | 职责 |
|---|---|
| CLI / Config | 接收输入、加载配置、装配组件与展示执行事件 |
| Agent | 维护执行循环、预算、取消与任务完成状态 |
| LLM | 编码请求、调用模型、解析 SSE 并返回完整响应 |
| Tool / Policy | 注册工具、校验参数与权限、执行文件和命令操作 |
| Context | 每轮选择、组织和压缩模型需要的信息 |
| Session | 保存消息历史、工具结果与执行状态 |
| Memory | 保存和召回带项目范围的长期事实与约定 |
| MCP | 接入外部工具服务，复用统一工具执行流程 |
| Observability | 记录执行轨迹、Token、耗时与验证证据 |

这些组件共同构成 CodeY 的 Agent Harness：模型决定下一步行动，Java 程序维护状态、执行工具并检查结果。

## 核心工作流程

1. 用户输入任务，创建独立的运行上下文并追加用户消息。
2. 构建本轮上下文，加入规则、历史、相关记忆和工具定义。
3. 调用模型，展示文本增量，并组装完整文本与工具调用。
4. 如果存在工具调用，校验参数和权限，执行后将结果回填消息，再进入下一轮。
5. 如果模型返回最终回答，按任务类型检查验收证据；代码修改任务需要约定的编译或测试结果。
6. 返回完整结果、验证摘要与明确状态：完成、未完成、失败、取消或预算耗尽。

首版工具按顺序执行；流式展示始终保留完整返回值。会话保存完整执行记录，上下文负责选择本轮输入，长期记忆保存跨会话事实。

完整流程图、时序图和恢复策略见 [架构设计](docs/architecture.md)。

## 项目目录

```text
codeY/
├── README.md
├── pom.xml
├── .env.example
├── docs/                         # 架构、模块、路线、面试和设计决策
│   ├── diagrams/                 # 可编辑 Mermaid 图
│   └── references/               # PaiCLI 分析参考
└── src/
    ├── main/
    │   ├── java/io/github/jackfeicoder/codey/
    │   │   ├── cli/
    │   │   ├── config/
    │   │   ├── agent/
    │   │   ├── llm/
    │   │   ├── tool/builtin/
    │   │   ├── context/
    │   │   ├── session/
    │   │   ├── memory/
    │   │   ├── mcp/
    │   │   ├── policy/
    │   │   └── observability/
    │   └── resources/prompts/
    └── test/
        ├── java/io/github/jackfeicoder/codey/
        └── resources/fixtures/
```

详细目录与运行时数据布局见 [架构设计](docs/architecture.md)。

## 开发路线

| 阶段 | 目标 | 状态 |
|---|---|---|
| M0 | 工程、入口与设计文档 | 已完成 |
| M1 | LLM 调用与 SSE 流式输出 | 待实现 |
| M2 | Agent 循环与只读工具 | 待实现 |
| M3 | 修改代码、执行命令与验证闭环 | 待实现 |
| M4 | 上下文预算与历史压缩 | 待实现 |
| M5 | 会话恢复与项目记忆 | 待实现 |
| M6 | MCP 工具接入 | 待实现 |
| M7 | 固定任务评估与项目展示 | 待实现 |

每阶段的验收标准见 [开发路线](docs/roadmap.md)，配套学习问题见 [面试学习笔记](docs/interview.md)。

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
