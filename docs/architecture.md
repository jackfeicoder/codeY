# CodeY 架构设计

> 版本：0.1，日期：2026-10-15。当前已实现配置、消息、基础提示词、非流式调用、可选工作区、本地会话管理、list_files、search_file、read_file、结构化 tools 请求与基础 AgentLoop。完成的工具消息组可保存和恢复；执行中恢复、SSE、内容搜索/写入/命令工具仍待实现。验证使用本地替身与临时文件，真实厂商兼容性尚待验证。开发进度见 [开发路线](#roadmap)。

## 阅读导航

- 第 1—9 节：定位、技术栈、目录、架构图、执行流程与核心策略。
- [模块职责与接口契约](#modules)
- [已实现的消息模型](#messages)
- [工具模块骨架与工具描述](#tool-foundation)
- [读文件工具实现与独立调用](#read-file)
- [Agent 循环与工具请求接入](#agent-loop)
- [简洁控制台进度与 exit 返回列表](#console-debug)
- [模型切换、目录、帮助与最后使用默认](#model-commands)
- [Windows CMD 全局启动](#global-cmd)
- [已实现的非流式 LLM 调用](#llm)
- [已实现的 Session 与连续对话](#session-chat)
- [代码阅读顺序与当前调用流程](#code-guide)
- [Prompt 模块设计与文件约定](#prompt-design)
- [工作区选择、历史列表与会话恢复方案](#workspace-sessions)
- [配置加载与多模型选择](#configuration)
- [开发路线与验收标准](#roadmap)
- [设计决策](#decisions)
- [面试学习与项目证据](#interview)

## 1. 项目定位与范围

CodeY 是使用 Java 从零实现的 CLI 编程 Agent。通过手写模型调用、Agent 循环、工具执行与上下文管理，理解 Claude Code、Codex 等产品的运行原理，并形成可演示、可验证的学习项目。

首版场景：在用户指定的 Java 工作区中读取代码、定位问题、修改文件、执行编译与测试，根据实际结果继续修复。最终输出改动说明、验证证据和剩余问题。

Harness 在本项目中指运行核心及其策略：Agent 循环、上下文构建、工具调度、预算、权限、状态和结果验证。LLM 是提出行动的组件；Java 程序负责执行和维护真实状态。

首版采用单 Agent。多 Agent、复杂 Plan、向量 RAG、Web UI、远程 API、微信与后台任务属于后续扩展，暂不建立对应模块。

## 2. 技术基线

| 组件 | 选择 | 实现阶段与原因 |
|---|---|---|
| 语言 | Java 17 | M0；本机可用，records 等能力足够支持首版 |
| 工程 | Maven 单模块 | M0；按包组织，降低初期装配成本 |
| CLI | JLine 3.30.17 + JDK 标准输入输出 | 原生终端补全、候选选择；管道/测试保留逐行输入 |
| HTTP | JDK HttpClient | M1 已实现非流式请求；共享连接客户端，单次配置绑定 |
| JSON | Jackson 2.18.2 | M1 已实现消息映射与响应解析 |
| 日志 | 初期标准输出；后续 SLF4J | M1/M3；模型内容与日志分流，避免输出密钥 |
| 测试 | JUnit 5.10.2 + JDK HttpServer | 配置、消息及非流式 HTTP 测试已实现，默认不调用真实模型 |
| 状态存储 | 用户目录 JSON 快照，后续 JSONL 事件 | 会话选择阶段先恢复文本；M5 补工具执行状态 |
| MCP | JSON-RPC + stdio | M6；先支持一条传输链，HTTP 后续增加 |

当前运行依赖为 Jackson 2.18.2；JUnit 5.10.2 为测试依赖。Maven Shade 3.5.1 在 package 阶段生成包含依赖与 Main 入口的可执行 JAR。此处记录已验证的项目版本，不声明其为各组件的最新版本。

## 3. 目录结构

```text
codeY/
├── README.md
├── pom.xml
├── .gitignore
├── .gitattributes
├── .env.example
├── docs/
│   ├── architecture.md     # 完整设计、配置、路线与学习笔记
│   └── diagrams/           # 可编辑 Mermaid 源文件
│       ├── architecture.mmd
│       ├── agent-flow.mmd
│       └── request-sequence.mmd
└── src/
    ├── main/
    │   ├── java/io/github/jackfeicoder/codey/
    │   │   ├── cli/          # Main、ChatCli、装配、输入与展示
    │   │   ├── config/       # 环境配置、模型配置、工作区
    │   │   ├── prompt/       # PromptLoader、PromptException
    │   │   ├── agent/        # AgentLoop、预算、取消、结果
    │   │   ├── llm/          # LlmClient、请求响应、SSE
    │   │   ├── tool/
    │   │   │   └── builtin/  # 文件、搜索、命令工具
    │   │   ├── context/      # 上下文收集、选择、预算与压缩
    │   │   ├── session/      # Message、Session、快照、存储与标题；工具事件后续实现
    │   │   ├── workspace/    # 可选目录绑定与真实路径解析
    │   │   ├── memory/       # 长期记忆的检索与保存
    │   │   ├── mcp/          # MCP 客户端及工具适配
    │   │   ├── policy/       # 路径范围、审批与执行策略
    │   │   └── observability/ # 执行轨迹、Token 与耗时
    │   └── resources/prompts/
    │       └── base.md       # 随 JAR 打包的基础提示词
    └── test/
        ├── java/io/github/jackfeicoder/codey/
        └── resources/fixtures/
```

模块包使用 package-info.java 声明用途，避免空目录在 Git 中丢失。资源空目录使用 .gitkeep 占位，不提前加入复杂抽象。prompts/base.md 保存已接入的基础系统提示词，fixtures 保存脱敏协议响应和小型测试项目；测试按主代码的包结构组织，默认不依赖真实 API Key。

会话数据按需创建，已实现布局与后续项目数据如下。会话集中放在用户目录，便于统一展示不同项目及普通聊天；项目数据目录忽略提交。详细实现见 [工作区与会话方案](#workspace-sessions)。

```text
用户目录/.codey/
└── sessions/<session-id>/
    ├── session.json           # 第一版：元数据、基础提示词与成功文本历史
    ├── session.lock           # 使用期间持有操作系统文件锁
    └── events.jsonl           # 工具阶段再增加，不提前实现

工作区/.codey/                 # 后续项目缓存、记忆与运行结果
├── cache/
├── memory/project.json
└── runs/<run-id>/summary.json
```

执行轨迹可能含源码与命令输出，日志内容应可截断、脱敏并由用户控制留存。

## 4. 模块架构

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

CLI 是装配入口，负责创建组件和展示事件。AgentLoop 面向接口工作，避免直接操作终端。上下文、会话与记忆职责明确：会话保存发生过什么，上下文决定当前发什么，记忆保存跨会话事实。

接口和数据契约详见 [模块与接口](#modules)。图中的持久化、记忆、MCP 是后续阶段能力。

## 5. 启动流程

1. 解析参数：help/version，后续加入 workspace/model/session。
2. 加载配置并校验模型名、地址、密钥、超时、工作区。
3. 解析工作区真实路径；按策略注册当前可用工具。
4. 创建 LlmClient、Session、ContextBuilder、ToolExecutor 和 AgentLoop。
5. M5 开始按需恢复会话；M6 开始初始化 MCP，并负责关闭资源。
6. 接收输入，启动 run，展示流式事件和最终 AgentResult。
7. 退出时关闭响应流、执行器、MCP 进程和会话存储。

配置字段来源为系统环境变量 > 启动目录 `.env` > 默认值。模型首次选择为 --profile > 用户最后选择 > 恢复会话保存别名 > CODEY_ACTIVE_PROFILE；后续 /new 采用最后选择。`.env.example` 是模板，旧 CODEY_LLM_* 兼容为 default。--check-config 只校验配置；/list model 展示目录，/model 切换会话模型。详见 [模型命令](#model-commands)。

## 6. 一次任务的执行流程

```mermaid
flowchart TD
    start["接收任务并创建 Run"] --> append["追加用户消息"]
    append --> guard{"取消或预算耗尽？"}
    guard -- "是" --> stopped["返回 CANCELLED 或 BUDGET_EXHAUSTED"]
    guard -- "否" --> build["构建上下文并预留输出预算"]
    build --> call["调用 LLM 并展示事件"]
    call --> valid{"响应完整且合法？"}
    valid -- "否" --> failed["分类失败与有限重试"]
    failed --> exhausted{"重试耗尽？"}
    exhausted -- "是" --> error["返回 FAILED"]
    exhausted -- "否" --> guard
    valid -- "是" --> tools{"存在工具调用？"}
    tools -- "是" --> policy["校验参数与权限"]
    policy --> execute["执行工具并回填全部结果"]
    execute --> guard
    tools -- "否" --> verify{"任务验收证据满足？"}
    verify -- "是" --> done["返回 COMPLETED 与证据"]
    verify -- "否" --> continueRun{"仍可继续？"}
    continueRun -- "是" --> feedback["追加缺失证据或失败反馈"]
    feedback --> guard
    continueRun -- "否" --> incomplete["返回 INCOMPLETE"]
```

```mermaid
sequenceDiagram
    actor User as 用户
    participant CLI
    participant Agent as AgentLoop
    participant Context as ContextBuilder
    participant LLM as LlmClient
    participant Tools as ToolExecutor
    participant Session
    User->>CLI: 输入任务
    CLI->>Agent: run(task, runContext)
    Agent->>Session: 追加用户消息
    loop 每一轮，直至明确退出
        Agent->>Context: build(session, budget)
        Context-->>Agent: messages 与工具定义
        Agent->>LLM: generate(request, listener)
        LLM-->>CLI: 经事件监听器显示文本增量
        LLM-->>Agent: 完整 LlmResponse
        Agent->>Session: 追加完整 assistant 消息
        alt 模型提出工具调用
            Agent->>Tools: execute(toolCalls, runContext)
            Tools-->>Agent: 与调用 ID 对应的 ToolResult
            Agent->>Session: 追加 tool 消息与执行事件
        else 模型返回最终回答
            Agent->>Agent: 检查任务对应的验收证据
        end
    end
    Agent-->>CLI: AgentResult，包含完整最终文本与状态
    CLI-->>User: 展示结果与验证摘要
```

流式回调只负责展示，完整响应始终返回调用方。模型的 assistant tool_calls 与每个 tool_call_id 对应的结果作为完整组保留，压缩时避免孤立结果。

## 7. 核心策略

### Agent 与退出

- 每个 run 创建独立 RunContext，携带 runId、workspace、budget、cancelToken。
- 限制轮次、墙钟时间、单次工具时间；Token 预算接入后单独累计。
- 区分 COMPLETED、INCOMPLETE、FAILED、CANCELLED、BUDGET_EXHAUSTED。
- 模型结束回答并不自动证明代码任务完成。代码修改任务检查约定验证命令的退出码、验证发生在最新修改之后等证据。
- 验证通过仅表示约定检查通过，未覆盖的行为在结果中说明；解释代码类任务采用不同完成条件。

### 工具

工具按职责分为 read_file（内容阅读）、list_files（目录浏览）、search_file（名称/路径定位），后续 grep_code（内容搜索）、write_file、execute_command。read_file 默认分页，返回路径、行号、截断信息。工具输出保留执行元数据，与发给模型的截断文本分开。

工具定义、注册与执行分开。当前 ToolResult 保存调用 ID、工具名称、status、errorCode、output、durationMillis 与 truncated；exitCode、artifacts、路径策略及命令审批随具体工具接入。基础执行器只解析 JSON 对象，并调用工具的显式校验方法。

初期工具顺序执行。具备依赖与资源冲突判断后才并行只读操作；文件写入和改变工作区的命令按顺序执行。命令通过平台适配运行，明确工作目录、退出码和超时，不固定使用 bash。

### 上下文

每次调用模型前统一构建：项目规则、当前目标、历史摘要、近期完整消息组、相关记忆和工具定义。采用收集 → 筛选 → 组织 → 压缩的流程。

预算同时考虑工具 schema、系统规则、近期历史和预留输出。估算值与 API 返回实际 usage 分开记录。大工具输出通过限制、分页和按需读取控制。

压缩使用副本构建请求，完整会话事件保留。摘要记录目标、约束、已完成步骤、关键文件、失败与下一步；需要重新检查文件时读取最新内容。

### 记忆与 MCP

长期记忆初期只保存用户明确确认的偏好、项目约定与重要事实，携带范围、来源与时间；不自动保存每条模型推断。保存操作统一经过存储层，并采用临时文件替换等方式避免半写文件。

MCP 工具经适配器进入同一 ToolRegistry/ToolExecutor，复用权限、超时、事件与结果处理。先实现 stdio 的 initialize、initialized、tools/list、tools/call；未知能力不注册为可用功能。协议版本、能力协商和分页以接入阶段文档与测试为准。

## 8. 会话恢复与操作边界

会话保存可选的工作区绑定与模型配置引用，不持久化 API Key；无工作区会话同样可恢复。第一版只恢复文本快照，工作区与模型缺失的处理见 [详细方案](#workspace-sessions)。工具阶段再持久化步骤事件和执行状态。进程意外退出后，先检查最后一条工具是否已完成；不直接重放写文件、命令或其他有副作用的调用。

文件路径校验基于规范化路径和真实路径处理已有文件、符号链接及目标父目录；路径前缀的字符串判断不足以覆盖边界。命令工具能操作更大范围，因此路径限制与进程隔离是不同能力。初期使用命令审批，后续根据需要引入进程隔离。

## 9. 实现验证与已知边界

- 当前验收 Maven 构建、可执行 JAR、配置/消息/提示词/Session/HTTP/Agent 测试，以及 --prompt、--chat 和工具闭环的本地 HTTP 请求；真实服务商调用和 SSE 后续验证。
- LLM 接入用本地 HTTP/SSE 替身验证，不使用真实密钥作为默认测试前提。
- 工具使用临时工作区验证路径、分页、错误和超时；命令用无网络的测试程序。
- 上下文验证工具调用组完整性、预算和规则保留；恢复验证副作用不会被盲目重放。
- 最终用固定小型 Java 项目评估，记录模型配置和任务集版本，避免把模型变化误认为架构提升。

图表提供 Mermaid 源文件，并人工检查流程与顺序；已核对独立源文件与本文内嵌图一致。初始化时尝试本地渲染，因环境缺少 Mermaid CLI（mmdc）未生成 SVG/PNG；尚未经过渲染器语法验证。可在支持 Mermaid 的 Markdown 查看器中预览，构建 Java 工程不依赖图表渲染工具。

<a id="modules"></a>

## 10. 模块职责与接口契约

> 以下含目标设计。当前已实现 CLI、配置、提示词、消息/工具、AgentLoop/AgentResult、Session/MessageHistory 及快照/存储/服务、可选工作区和非流式 LLM。其余具体类型按阶段实现。

### 模块边界

| 包 | 职责 | 计划核心类型 | 约束 |
|---|---|---|---|
| cli | 装配、输入、渲染、退出 | Main、ChatCli、SessionLauncher | 不持有 Agent 算法；核心代码不直接输出终端 |
| prompt | 内置提示词加载与后续片段组合 | PromptLoader、PromptException；后续 PromptAssembler | 按 classpath 读取，与模型厂商和密钥无关 |
| config | 加载、选择并验证配置 | LlmConfig、ModelProfiles、AuthMode、SessionSettings | 密钥不进入日志、会话或仓库 |
| workspace | 可选工作区与真实目录校验 | WorkspaceBinding、WorkspaceResolver | 不改变 .env 来源，不代替工具权限 |
| agent | 执行循环、预算、取消、完成判定 | AgentLoop、RunContext、AgentResult、RunBudget | 一次 run 的状态独立，明确退出原因 |
| llm | 请求编码、HTTP、SSE、响应组装 | LlmClient、LlmRequest、LlmResponse、StreamListener | 总返回完整结果；展示回调不影响返回值 |
| tool | 定义、注册、调度、参数验证 | Tool、ToolDefinition、ToolCall、ToolResult、ToolRegistry、ToolExecutor | Agent 依赖执行器，工具不反向调用 Agent |
| tool.builtin | 文件搜索、读取、修改、命令 | ReadFileTool、SearchTextTool、WriteFileTool、ExecuteCommandTool | 输出有边界，所有路径基于工作区解析 |
| context | 选择和组织本轮输入 | ContextBuilder、ContextSnapshot、TokenEstimator、HistoryCompactor | 不把长记忆与会话副本重复堆入请求 |
| session | 完整会话状态、消息、事件和存储 | Message、Session、SessionEvent、SessionStore | assistant 调用与 tool 结果具备关联关系 |
| memory | 保存和召回长期事实 | MemoryEntry、MemoryStore、MemoryRetriever | 支持项目范围、来源、时间与删除 |
| mcp | 外部协议与工具适配 | McpClient、StdioTransport、McpToolAdapter | 处理 pending request、分页与资源关闭 |
| policy | 路径范围、审批、操作策略 | ExecutionPolicy、PathPolicy、ApprovalHandler | 程序执行策略；提示词不能替代限制 |
| observability | 轨迹与指标 | AgentEvent、RunMetrics、TraceWriter | 展示信息与持久化信息可分别脱敏 |

### 最小数据模型

| 类型 | 关键字段 | 作用 |
|---|---|---|
| Message | role、content、toolCalls、toolCallId | 保持模型协议中的消息结构 |
| ToolCall | id、name、arguments | arguments 从 JSON 校验后执行；流式碎片按 index/id 拼接 |
| ToolResult | callId、name、status、output、errorCode、durationMillis、truncated | 已实现同步结果，exitCode/artifacts 后续按需加入 |
| LlmResponse | message、finishReason、usage；text()/toolCalls() | 复用 assistant Message 保存内容，避免重复存储文本和调用 |
| RunContext | runId、workspace、budget、cancelToken | 每次任务的明确执行环境 |
| AgentResult | status、turnMessages、response、modelCalls、toolCalls | 当前为不可变任务结果；verification/artifacts 后续增加 |
| Session | systemMessage/history；元数据由 SessionSnapshot 保存 | 内存保存成功轮次，SessionService 关联 ID/工作区/存储 |
| MemoryEntry | id、scope、projectId、content、source、createdAt | 避免不同项目的相同事实被错误合并 |

只有实际需要的字段进入首版。usage 缺失表示未知，不视为零。模型 capabilities（工具支持、上下文窗口等）通过显式配置提供。

<a id="messages"></a>

### 已实现的消息模型

消息放在 session 包，作为会话和后续模型调用的共同数据结构；ToolCall 放在 tool 包，表示模型提出的调用请求。

| 类型 | 字段/方法 | 当前语义 |
|---|---|---|
| MessageRole | SYSTEM、USER、ASSISTANT、TOOL | wireValue() 映射为小写协议值，fromWireValue() 解析支持的角色 |
| Message | role、content、toolCalls、toolCallId | 不可变 record，工具调用列表进行防御性复制 |
| ToolCall | id、name、arguments | 调用 ID、工具名与完整原始 JSON 参数文本 |

四种消息的分工：system 保存指令，user 保存用户输入，assistant 保存模型文本或工具调用请求，tool 保存工具执行结果。

```java
ToolCall call = new ToolCall("call_1", "read_file", "{\"path\":\"Main.java\"}");
List<Message> history = List.of(
        Message.system("你是编程助手。"),
        Message.user("解释 Main.java。"),
        Message.assistantToolCalls(List.of(call)),
        Message.tool(call.id(), "public class Main {}"),
        Message.assistant("这是项目入口类。")
);
```

`Message.assistant(text, calls)` 支持同时保存说明文本和工具调用。一条 assistant 可提出多个调用，每个调用在后续执行后产生独立 tool 消息，toolCallId 指向原始 id。工具失败也通过对应的结果消息反馈。

单条消息约束：

- 只有 assistant 可携带 toolCalls，同一消息中调用 ID 唯一。
- 只有 tool 可携带 toolCallId，且必须提供非空 ID。
- 有工具调用的 assistant 可以让 content 为 null；其他消息必须有 content，空字符串允许表示空输出。
- ToolCall 的三个字段均需非空；arguments 保存原文，JSON 对象语法已由 LLM 适配器校验，工具 schema 校验留给执行层。
- 非法构造参数抛出 IllegalArgumentException；当前不为每种数据结构单独增加异常类。

Chat Completions 适配器已显式映射 role.wireValue()、tool_calls、tool_call_id，并把 ToolCall 转成接口要求的 function 结构；不依赖 record 的默认 JSON 形状。跨消息的调用 ID 匹配、全部结果回填和工具调用组完整性由后续 Session/Agent 层检查，当前 Message 仅校验单条消息。

当前消息内容采用文本，已接入 JSON 映射、真实只读工具与完整历史配对校验；流式工具参数组装待实现。相关测试覆盖普通对话、多个调用的结果关联、角色约束、空工具输出、重复 ID 和防御性复制。

<a id="tool-foundation"></a>

### 已实现的工具模块骨架与描述

> 基础模块与 list_files/search_file/read_file 已支持独立调用，并已在工作区 CLI 会话通过 tools 字段和 AgentLoop 接通。普通聊天筛掉工作区工具；模型客户端只编解码协议，执行由 Agent 层调度。

```text
tool/
├── Tool.java              # 实现契约：定义、具体参数校验、执行
├── ToolDefinition.java    # name、description、parameters JSON Schema
├── ToolCall.java          # 已有：模型请求的 id/name/arguments
├── ToolContext.java       # 宿主提供的工作区与输出上限
├── ToolRegistry.java      # 注册、查找、稳定定义快照、可用能力筛选
├── ToolExecutor.java      # 统一同步调度与错误归一化
├── ToolResult.java        # 成功/错误、调用关联、耗时、截断；可转 tool 消息
├── ToolException.java     # 工具预期业务失败，保留可展示错误类别
├── package-info.java
└── builtin/
    ├── ReadFileTool.java  # UTF-8 文本分页、路径范围与读取上限
    └── package-info.java
```

ToolDefinition 将描述与执行分开。description 用自然语言说明用途与限制；parameters 使用 Jackson JsonNode 表达 JSON Schema，当前校验其顶层为 object，并在构造和访问时复制，避免外部修改已注册说明。它不绑定服务商接口形状，后续由 LLM 适配器转换。

工具说明与参数 schema 放在具体 Tool 的 definition() 中，形成一份来源；不在 base.md 中维护另一份工具清单。较长的操作建议以后可单独放提示词资源，但不代替工具 schema、Java 实现或参数校验。当前调用协议的结构参考 [OpenAI Function Calling 官方说明](https://developers.openai.com/api/docs/guides/function-calling)。

read_file 的定义映射为下面的请求片段；此处是格式示例，实际元数据以 ReadFileTool.definition() 为准，已接入 LlmRequest：

```json
{
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "read_file",
        "description": "读取当前工作区内的文本文件。path 使用相对工作区的路径。",
        "parameters": {
          "type": "object",
          "properties": {
            "path": { "type": "string", "minLength": 1, "description": "文件的相对路径" },
            "startLine": { "type": "integer", "minimum": 1, "maximum": 2147483647, "default": 1 },
            "maxLines": { "type": "integer", "minimum": 1, "maximum": 500, "default": 200 }
          },
          "required": ["path"],
          "additionalProperties": false
        }
      }
    }
  ]
}
```

基础执行流程：

```text
Java 装配 Tool → registry.register(tool)
registry.definitions(context) → 当前可用定义 → LlmRequest.tools → HTTP tools
ToolCall → ToolExecutor
  → 查已注册名称，不反射加载任意类
  → 解析完整 JSON 对象，拒绝重复键、尾随数据和超过 64 Ki 字符的输入
  → 按 requiresWorkspace 检查宿主绑定与真实目录是否可用
  → Tool.validateArguments：具体字段、类型、业务限制
  → Tool.execute：得到文本或预期异常
  → ToolResult：保留原调用 ID、状态/错误码、毫秒耗时、输出截断
  → toMessage()：结构化正文 + Message.tool(callId, ...)
```

Registry 以名称绑定定义和实现，按注册顺序展示；重复名称明确失败，定义列表不可修改。需要工作区的工具在 NONE 或失效目录下不展示给模型，执行器也会拒绝对应调用；这只是基础可用性检查。ReadFileTool 已负责具体路径与链接范围检查；统一路径策略和命令隔离仍待实现。

ToolContext 由宿主提供，默认输出上限为 16000 个 Unicode 码点。模型 arguments 不决定工作区或宿主上限。输出截断保留完整字符，truncated 字段表明信息不完整；该限制在执行后作用于返回文本，读取工具仍需先限制实际读取量。

ToolResult 的基础失败类别包括 UNKNOWN_TOOL、INVALID_ARGUMENTS、WORKSPACE_REQUIRED、WORKSPACE_UNAVAILABLE、EXECUTION_FAILED、INTERRUPTED，读文件的专用类别见下一节。预期 ToolException 保留可展示说明；未知 RuntimeException 使用通用错误，不回显原始异常或参数。InterruptedException 恢复线程标志；JVM Error 不当作业务错误吞掉。成功和失败结果都可转为原 toolCallId 对应的消息，转换本身不追加历史或调用模型。

当前没有通用 JSON Schema 校验器：Tool 必须显式实现 validateArguments，schema 用于声明和发送，不能假设定义 schema 就自动完成业务校验。执行器为同步串行调用，durationMillis 只是测量值；工具超时、审批、取消令牌、并行调度与审计事件尚未实现。接 MCP 的复杂 schema 时再引入完整校验能力。

ReadFileTool 已实现定义、参数/路径校验与有界读取；LlmRequest 与协议适配器已发送 tools，AgentLoop 回填 assistant/tool 后继续请求，v2 SessionSnapshot 保存完整成功工具轮次。list_files 已接入目录探索，search_file 已接入文件名/路径定位；grep_code 内容搜索、统一权限策略后续加入。

验证：工具基础结构有 23 项、读文件有 36 项、Agent 首版新增 35 项；进度/exit 3 项、模型切换/默认偏好 19 项、共享配置目录 3 项，当前完整 mvn verify 共 415 项通过，无失败或跳过。仅使用本地模型替身与临时测试文件。

<a id="read-file"></a>

### 已实现的读文件工具：read_file

实现文件：[ReadFileTool.java](../src/main/java/io/github/jackfeicoder/codey/tool/builtin/ReadFileTool.java)。一个类提供定义、实际参数校验、路径检查和读取逻辑；复用 ToolRegistry / ToolExecutor / ToolResult，不依赖模型提供商。该工具可独立执行，Main 已装配到工作区会话。

| 参数 | 类型与默认值 | 用途 |
|---|---|---|
| path | 必填非空字符串 | 工作区相对路径，保留文件名空格，拒绝绝对/带根路径 |
| startLine | 整数，默认 1，范围 1～2147483647 | 第一条要返回的文件行号 |
| maxLines | 整数，默认 200，范围 1～500 | 本次最多返回的完整行数，可能受输出预算提前分页 |

拒绝未知参数、显式 null、浮点数、字符串行数与越界数字；工作区和输出预算由宿主 ToolContext 提供。

```text
ToolExecutor → validateArguments → execute
  → 检查工作区存在且仍是绑定的真实目录
  → resolve(path).normalize()：拒绝词法越界
  → toRealPath()：解析目录及文件链接，拒绝真实目标越界
  → 普通文件 / 可读 / 文件 ≤ 4 MiB
  → 打开真实路径（最终路径 NOFOLLOW_LINKS）
  → 有界字节流 → 严格 UTF-8 Reader → 移除开头 BOM
  → 顺序跳过前文 → 完整行读取 → JSON 序列化长度检查
  → 生成分页 JSON → ToolResult → 原调用 ID 的 tool 消息
```

内存只保留当前页；文件大小及实际读取字节上限均为 4 MiB，限制实际读取可应对检查后文件增长。每行上限 16384 个 UTF-16 单元，包括跳过的行；不把整文件装入内存。顺序跳过意味着深页仍需从头扫描，首版不建立文件索引。

兼容 LF / CRLF / CR，保留空行和行内空白，末尾换行不额外生成一行。严格 UTF-8 解码会拒绝非法字节，NUL 被视为非文本。只验证实际读取区域；解码器有预读，附近非法字节也可能触发编码错误。该工具不是完整文件格式检测器。

返回值为 JSON **文本**，存放在 ToolResult.output；例如读取第 2 行、最多 1 行：

```json
{
  "path": "src/Main.java",
  "startLine": 2,
  "returnedLines": 1,
  "endLine": 2,
  "lines": [{ "line": 2, "text": "public class Main {" }],
  "hasMore": true,
  "nextStartLine": 3
}
```

输出预算计入元数据、行号和 JSON 转义，并按 Unicode 码点测量。放不下下一条完整行时提前结束，下一次从未返回的行继续；连第一行或空页元数据都放不下时返回 OUTPUT_LIMIT_TOO_SMALL。成功输出保持完整 JSON，ToolResult.truncated 为 false；`hasMore` 表示分页未结束。EOF 时 nextStartLine 为 null；空文件或起始行超出 EOF 时 lines 为空、endLine 为 null。不扫描尾部计算总行数。

独立装配示例（导入 tool、tool.builtin、workspace 包中的对应类）：

```java
// 工作区由宿主解析，模型只给相对文件路径。
var root = java.nio.file.Path.of("E:/Desktop/github/codeY").toRealPath();
var binding = new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, root.toString());
var context = new ToolContext(binding);
var registry = new ToolRegistry().register(new ReadFileTool());
var executor = new ToolExecutor(registry);
var call = new ToolCall("call-read-1", "read_file",
        "{\"path\":\"README.md\",\"startLine\":1,\"maxLines\":20}");
ToolResult result = executor.execute(call, context);
System.out.println(result.output());
// 在生产 CLI 中，由 AgentLoop 把 result.toMessage() 回填给模型。
```

| 错误码 | 含义 |
|---|---|
| PATH_OUTSIDE_WORKSPACE | 绝对/带根路径、归一化越界或链接目标在工作区外 |
| FILE_NOT_FOUND / NOT_A_FILE | 文件不存在，或目标不是普通文件 |
| FILE_NOT_READABLE / FILE_READ_FAILED | 无读取权限，或其他 I/O 失败 |
| FILE_TOO_LARGE / LINE_TOO_LONG | 文件/实际读取量超过 4 MiB，或单行超限 |
| INVALID_ENCODING / NOT_TEXT_FILE | 读取区域不是有效 UTF-8，或出现 NUL |
| OUTPUT_LIMIT_TOO_SMALL | 完整单行或元数据放不进宿主输出预算 |

工作区内链接可读，输出 path 使用真实目标的相对路径；工作区外链接被拒绝。打开时不跟随新出现的最终符号链接，但目录在检查与打开之间仍可能被并发替换，因此这不是完整操作系统沙箱。后续统一权限模块再处理强隔离、敏感路径策略等需求。当前没有写文件能力，也没有工具执行超时；顺序读取期间检查线程中断。

测试：[ReadFileToolTest.java](../src/test/java/io/github/jackfeicoder/codey/tool/builtin/ReadFileToolTest.java)，36 项使用临时真实文件，覆盖编码、换行、默认参数、分页、空文件、输出预算、非法参数、路径/链接范围、大小边界与中断。当前环境符号链接专项已执行，无跳过。独立运行：`mvn -Dtest=ReadFileToolTest test`；完整构建：`mvn verify`。

<a id="list-files"></a>

### 已实现的目录工具：list_files

实现文件：[ListFilesTool.java](../src/main/java/io/github/jackfeicoder/codey/tool/builtin/ListFilesTool.java)。在 Main.createTools 注册，复用 ToolRegistry / ToolExecutor / AgentLoop，LLM 只接收结构化工具说明，不新增目录专用客户端。

| 参数 | 默认值 | 约束 |
|---|---|---|
| path | `.` | 相对工作区的目录路径；拒绝空白、绝对/带根和越界路径 |
| maxDepth | 10（可由 CODEY_LIST_FILES_DEFAULT_DEPTH 配置） | 整数 1–20；直接子项算第一层，所选目录本身不算结果条目 |
| maxEntries | 200 | 整数 1–1000，文件/目录/链接都占一条 |

执行流程：校验字段类型与范围 → 检查真实工作区及目标目录 → 打开目录流，收集扫描预算内的子项并关闭流 → 按文件名自然顺序排序 → 深度优先返回并递归下一层 → 在加入每个条目时验证完整 JSON 输出预算 → 用原 callId 回填 tool 结果。扫描总量上限 10000，限制单目录排序缓存及全次遍历工作量；线程中断在目录枚举和条目处理期间检查。

工具输出是 ToolResult.output 中的 JSON 字符串，例如对 path=`src`、maxDepth=2 的结果：

```json
{
  "path": "src",
  "maxDepth": 2,
  "maxEntries": 200,
  "returnedEntries": 2,
  "entries": [
    {"path": "src/main", "type": "directory", "depth": 1},
    {"path": "src/main/java", "type": "directory", "depth": 2}
  ],
  "truncated": false,
  "limitReason": null,
  "scannedEntries": 2,
  "ignoredDirectoryNames": [".git", "target", "node_modules", ".gradle", "build", "dist"]
}
```

每个 entry.path 相对工作区，而不是相对参数 path，可直接用于 read_file。条目类型为 file/directory/symlink/other，不包含正文、链接目标或工作区绝对路径。scannedEntries 包括被忽略项，不是整个工作区文件总数；truncated=false 只表示指定深度及默认过滤范围内未因预算提前终止，不能据此声称查看了整个项目。

达到 maxEntries、输出预算、扫描上限时，内部 JSON 的 truncated=true，limitReason 分别为 maxEntries/outputLimit/scanLimit。工具预留扫描计数及最长原因的元数据空间，保证成功结果没有被 ToolExecutor 切成残缺 JSON；外层 ToolResult.truncated 因此为 false，与内部“目录结果不完整”不同。若一个条目或元数据都放不下，返回 OUTPUT_LIMIT_TOO_SMALL。第一版无分页游标，模型应选择具体子目录、适度调整深度/条数继续探索；巨大平坦目录不保证可通过多次调用遍历全部内容。

默认跳过遍历中遇到的 .git、target、node_modules、.gradle、build、dist 目录；同名普通文件仍列出，显式以这些目录为起点也可查看其内部。这是减少噪声的固定规则，不代表 .gitignore 解析、敏感文件保护或权限策略。

发现的符号链接（含损坏/循环/外部链接）只按 symlink 列出，不跟随或输出目标；真实路径发生变化的 junction 等目录也不进入。显式 path 会先解析真实位置：工作区内链接目录可作为起点，指向工作区外则拒绝。目录与子项读取失败返回明确错误，当前不返回局部成功及 warning 列表。目录并发变化可能触发失败；路径检查到打开之间仍有竞争窗口，不是操作系统沙箱。

| 错误码 | 含义 |
|---|---|
| INVALID_ARGUMENTS | 未知字段、类型/范围错误或非法路径语法 |
| WORKSPACE_REQUIRED / WORKSPACE_UNAVAILABLE | 无工作区或绑定已失效 |
| PATH_OUTSIDE_WORKSPACE | 带根/绝对/越界路径、外部链接目标或遍历目录被替换 |
| DIRECTORY_NOT_FOUND / NOT_A_DIRECTORY | 目录/条目已不存在，或传入普通文件 |
| DIRECTORY_NOT_READABLE / DIRECTORY_READ_FAILED | 无读取权限或其他目录 I/O 失败 |
| OUTPUT_LIMIT_TOO_SMALL / INTERRUPTED | 输出容不下完整数据或线程中断 |

提示词 base.md 增加“未知路径时先 list_files，再 read_file；结果截断时选择更具体目录”的行为指导，参数说明仍以工具 schema 为准。新建会话使用新版提示词；恢复旧会话继续使用原快照，但请求 tools 仍按实际工作区动态提供当前注册的三个工具。

测试：[ListFilesToolTest.java](../src/test/java/io/github/jackfeicoder/codey/tool/builtin/ListFilesToolTest.java) 使用真实临时目录覆盖默认/显式参数、顺序/深度/相对路径、忽略规则、空目录/恰好达到条数、Unicode/输出预算、非法参数、路径/链接边界、扫描上限与中断；AgentLoopTest 验证列目录 → 读取发现的文件 → 最终回答的三次模型请求及历史完整性。

验证结果：ListFilesToolTest 29 项、Agent 组合 1 项，该阶段新增 30 项；当时完整 mvn verify 共 343 项通过，无失败或跳过，符号链接测试已执行。6 项本地 HTTP JAR 场景验证生产入口注册两个 tools、目录结果用于读文件、工具历史保存/恢复不重放、无工作区的 --prompt/会话隐藏两个工具、越界错误回填和条数限制。未读取项目真实 .env，也未调用真实模型厂商。

<a id="search-file"></a>

### 已实现的文件定位工具：search_file

职责是按文件名或路径模式找到文件位置，不读取正文、不匹配代码内容。它采用之前讨论的 glob 文件定位语义，统一命名为 `search_file`，不另建同义的 `glob_files`、`search_files` 或 `search_code`。后续 `grep_code` 独立负责文件内容搜索。

| 工具 | 解决的问题 | 结果 |
|---|---|---|
| list_files | 项目目录是什么结构 | 文件/目录条目、类型、层级 |
| search_file | 某个名称或路径模式对应哪些文件 | 匹配的工作区相对文件路径 |
| read_file | 已知文件的具体内容是什么 | 连续文本行、行号与分页信息 |
| grep_code（待实现） | 某段文本在哪些代码位置出现 | 路径、行号和匹配行 |

实现文件与依赖：

| 文件 | 职责 |
|---|---|
| [SearchFileTool.java](../src/main/java/io/github/jackfeicoder/codey/tool/builtin/SearchFileTool.java) | 工具定义/参数校验，筛选普通文件，生成有界路径结果 |
| [WorkspaceFiles.java](../src/main/java/io/github/jackfeicoder/codey/tool/filesystem/WorkspaceFiles.java) | 与 ListFilesTool 共用的工作区目录校验、忽略/链接规则、有界排序和深度优先遍历 |
| [FileGlob.java](../src/main/java/io/github/jackfeicoder/codey/tool/filesystem/FileGlob.java) | 跨平台通配符校验与匹配，按 Unicode 码点处理，不使用模型输入构造正则 |
| [SearchFileToolTest.java](../src/test/java/io/github/jackfeicoder/codey/tool/builtin/SearchFileToolTest.java) | 文件名/路径模式、范围、大小写、Unicode、无正文读取、链接和预算测试 |

工具互不调用：SearchFileTool 不调用 ListFilesTool 或 ReadFileTool；ListFilesTool 与 SearchFileTool 依赖同一个 WorkspaceFiles。目录遍历器只提供 entry 与扫描统计，由各工具决定返回结构或匹配条件。ReadFileTool 继续独立负责 UTF-8 文本读取。ToolRegistry/ToolExecutor/AgentLoop 沿用原接口，无需每加一个工具就新增循环分支。

| 参数 | 默认 / 范围 | 规则 |
|---|---|---|
| pattern | 必填，最多 256 个 Unicode 字符 | 精确文件名或相对 path 的通配符模式 |
| path | `.` | 工作区相对目录，作为本次匹配起点 |
| maxResults | 100 / 1–1000 | 返回文件路径条数，不是扫描文件数 |
| caseSensitive | false | 明确控制大小写，跨平台一致；结果保留实际名称 |

匹配约定：

- `AgentLoop.java`：递归查找任意层的精确同名文件。`Config` 不会自动匹配 ConfigLoader.java，需要 `*Config*`。
- `*Config*.java`：按文件名匹配，星号可匹配零个或多个字符；问号匹配一个 Unicode 字符。
- `src/*.java`：只匹配搜索起点的 src 目录下直接 Java 文件，单星不跨越目录。
- `**/prompts/*.md`：独立的双星目录段匹配零层或多层，包括搜索起点直接存在 prompts 的情况。末尾双星表示所选目录下的文件，例如 `src/**` 不会命中名为 src 的普通文件。
- 模式包含 `/` 时相对 path 匹配，不相对工作区根目录；结果却始终相对工作区，可直接交给 read_file。
- 模式中的 Windows 反斜杠统一为 `/`。不支持正则、字符类或花括号分组，`[]`、`{}` 和其他普通字符按字面匹配。拒绝绝对模式、`.`/`..` 段、空段、控制字符及不独立占段的双星。
- FileGlob 分别对路径段与 Unicode 字符执行动态规划，避免依赖操作系统 glob 大小写规则或产生正则回溯。模式长度及扫描量有上限；文件定位不设置人为目录深度限制。

调用示例及流程：

```json
{"pattern":"*Config*.java","path":"src","maxResults":50}
```

```text
模型返回 search_file 调用
  → AgentLoop → ToolExecutor：解析 JSON、检查工作区、校验参数
  → SearchFileTool：构造 FileGlob，调用 WorkspaceFiles.directory/scan
  → 遍历 entry：只保留普通文件，按相对搜索起点的名称/路径匹配
  → 生成 {pattern, path, caseSensitive, maxResults, returnedFiles, files,
           truncated, limitReason, scannedEntries, ignoredDirectoryNames}
  → ToolResult 关联原调用 ID → 下一次模型请求
  → 模型可直接回答文件位置，或按需调用 read_file 阅读
```

不要求先 list_files；知道命名特征时可直接定位。结果只包含路径和搜索元数据，二进制文件也能按名称定位，不尝试解码正文。发现的符号链接、junction、目录和特殊文件不作为命中；显式搜索起点可通过指向工作区内的链接解析到真实目录，越界链接拒绝。忽略目录为 `.git`、`target`、`node_modules`、`.gradle`、`build`、`dist`；显式指定这些目录为 path 时允许检查。

限制与错误：扫描最多 10000 个条目，包含目录与忽略项；每层关闭目录流后按名称排序，通过显式栈执行深度优先遍历，不依赖 Java 方法递归层数。search_file 遍历到目录末端，不接受 maxDepth 参数，schema 与结果均移除该字段；list_files 继续通过有深度参数的遍历入口控制展示范围。结果还受 maxResults 与宿主 16000 码点输出预算限制。条数、输出、扫描未覆盖全部区域时返回 `truncated=true`，limitReason 分别为 maxResults/outputLimit/scanLimit。空 files 仅表示已检查范围内无命中。没有分页游标或 `.gitignore` 解析，截断后优先缩小 path。刚好达到条数上限且无额外命中不误报截断。

成功输出预留最坏扫描统计和截断字段，始终保持完整 JSON；元数据或第一条完整路径放不下时返回 OUTPUT_LIMIT_TOO_SMALL。非法模式/参数使用 INVALID_ARGUMENTS；工作区、目录越界、缺失、非目录与读取错误复用现有错误码。共享遍历器保留线程中断，权限/读取失败不伪装为空结果。路径检查仍不等于完整操作系统沙箱。

Main 注册三个工具，NONE 工作区全部隐藏。工具说明仍由 SearchFileTool.definition → Registry → LlmRequest.tools → ChatCompletionsClient 编码，无需增加工具说明 Markdown。base.md 仅补充“按名称定位优先 search_file，阅读正文用 read_file”的行为指导；旧会话继续使用提示词快照，`/new` 或 `/clear` 采用新版指导。控制台只增加搜索范围和模式摘要，例如 `[第 1 轮] 调用 search_file · src · *Config*.java`，不打印搜索结果正文。

验证：SearchFileToolTest 现有 42 项，AgentLoopTest 新增 1 项“按名称定位 → 读取 → 回答及进度/历史”组合测试；原 ListFilesToolTest 30 项全部通过共享遍历回归。完整 `mvn verify` 共 408 项通过，无失败、错误或跳过。另通过 7 组本地 HTTP JAR 验证：生产工具注册与模型驱动搜索/读取/保存、跨进程恢复不重放、无工作区的单次/会话隐藏工具、越界错误回填、条数截断结果、真实仓库十层 Java 文件定位与目录工具资源发现、超过二十层查找与目录展示深度回归。配置/会话隔离，使用本地模型替身；真实仓库只读取路径元数据，不读取真实 .env，也不调用真实厂商。

<a id="agent-loop"></a>

### 已实现的 AgentLoop 与结构化工具请求

实现位置是 `src/main/java/io/github/jackfeicoder/codey/agent/`。核心是有次数限制的 for 循环，不使用 ReAct.md 实现调度，也不要求模型展示内部思考过程。这是基于 Function Calling 的 ReAct 风格执行机制；CLI 接收下一条用户输入的外层循环与它不同。

| 文件 | 职责 |
|---|---|
| [AgentLoop.java](../src/main/java/io/github/jackfeicoder/codey/agent/AgentLoop.java) | 一条用户任务的请求、工具执行、结果回填、中断与预算退出 |
| [AgentResult.java](../src/main/java/io/github/jackfeicoder/codey/agent/AgentResult.java) | COMPLETED/BUDGET_EXHAUSTED、本轮不可变消息、最终响应、模型/工具次数、budgetLimited 收尾标记 |
| [LlmRequest.java](../src/main/java/io/github/jackfeicoder/codey/llm/LlmRequest.java) | messages/tools 两份不可变列表，工具名称唯一；toolCallsAllowed 控制收尾禁用工具，保留原构造方式 |
| [ChatCompletionsClient.java](../src/main/java/io/github/jackfeicoder/codey/llm/ChatCompletionsClient.java) | 显式编码 tools 的 function 包装，不执行工具 |
| [MessageHistory.java](../src/main/java/io/github/jackfeicoder/codey/session/MessageHistory.java) | 完整轮次与调用 ID/结果配对校验，供提交和磁盘恢复共用 |

实际装配入口是 `Main.main()` 与 `startChat()`，Main 当前没有 run 方法。`Main.createTools(AgentSettings)` 注册 ReadFileTool、ListFilesTool 与 SearchFileTool，传给 SessionLauncher；后者为当前会话装配 AgentLoop 和 ToolContext，ChatCli 调用它并处理最终展示/提交。Main 不实现工具循环。

```text
AgentLoop.run(session, input, context)
  → messages = Session.messagesFor(input) 的可变副本
  → 每步检查线程中断
  → registry.definitions(context) 筛选本次可用工具
  → 剩余 3/2 次时在 system 副本提醒预算；最后一次 toolCallsAllowed=false
  → client.generate(new LlmRequest(requestMessages, definitions, !finalRound))
      ├─ 无工具调用、正文非空白且 finish_reason=stop/length
      │    → 追加最终 assistant → 返回 COMPLETED
      └─ 工具调用且 finish_reason=tool_calls
           → 整组检查：最多 16 个、同一任务 ID 不重复
           → 追加原始 assistant（含所有 tool_calls）
           → 顺序执行全部调用，每个结果生成对应 tool 消息
           → 下一次 generate 携带观察结果和工具定义
  → 最后一次请求使用 tool_choice=none，只生成基于已有证据的回答
      ├─ 有效文本 → COMPLETED，budgetLimited=true，提示预算受限并保存完整消息组
      └─ 仍返回工具调用 → 不执行、不追加该调用，返回 BUDGET_EXHAUSTED
```

工具定义传输的职责分工：具体 Tool 声明自身 name/description/parameters，ToolRegistry 汇总并筛选，AgentLoop 放入 LlmRequest，ChatCompletionsClient 编码成 `tools[].type="function"` 与 `function.name/description/parameters`。`base.md` 的工具使用指导作为 system 消息发送，两者不拼成一份字符串。无工具时省略 tools 字段；普通轮次不强制 tool_choice；最后一次存在工具定义时显式发送 tool_choice="none"，程序也拦截该轮工具。无工具定义时省略 tools 和 tool_choice。

AgentLoop 本身不修改 Session。COMPLETED 返回仅本轮的 user、所有 assistant/tool 与最终 assistant；ChatCli 调用 `completeAgentTurn()` 完整校验后提交，Launcher 再 capture/save。最后一次获得有效文本时，仍提交完整消息组并提示预算受限，未确认部分由模型在回答中说明；若服务商仍请求工具或网络/协议失败、中断，则不提交残缺本轮。预算提醒只附加到当次 system 消息副本，不进入历史或磁盘，下一用户任务重新计数；强制执行由代码保证，提示词仅用于引导。当前工具只有读取，所以失败任务不存在文件修改；引入写入工具前要增加执行事件和未完成任务管理。

空最终回答校验：finish_reason=stop/length 只表示服务端结束生成，不代表任务已经完成。AgentLoop 在没有工具调用时要求正文非空、非纯空白，否则抛 INVALID_RESPONSE，CLI 明确报错并继续等待用户输入，不打印空 CodeY 回答，也不把本轮工具轨迹与空回答保存为成功轮次。不会自动请求下一轮或重放工具；已有历史不自动改写。HTTP 429 属于独立的服务端请求限制错误，当前仅分类为可重试，不自动重试；频率、并发或额度原因需结合厂商信息确认。失败任务不提交，随后输入“继续”只会携带已成功保存的历史。

验证：新增 7 项 Agent/CLI 测试，完整 mvn verify 共 415 项通过；本地 HTTP JAR 另验证工具后空正文报错且不保存、后续问题可继续、429 单次失败且保留旧成功历史、预算末轮 length 空正文不保存。

工具业务失败、未知工具等转换为 tool 错误结果，可以让模型下一轮纠正。LLM 适配器拒绝语法错误的 arguments JSON；这种协议错误直接终止任务。工具调用组结束原因异常、过多调用或跨步骤重复 ID 在整组执行前拒绝；纯文本 length 响应仍保存并提示截断。线程中断传播，不继续下一工具或模型请求。

v2 磁盘格式支持 `user → (assistant tool_calls → 全部 tool 结果)* → assistant 最终回答`。调用 ID 在一条用户任务内唯一；每组结果可按不同顺序保存，但必须全部匹配且每个仅一次。历史不能夹入 system、额外 tool、下一用户问题或缺失最终回答。v1 仅允许旧文本轮次，恢复并保存时升级 v2。恢复只是加载原始工具结果进入上下文，不重放旧调用。

边界：默认最多 20 次模型请求，包含最后一次仅文本请求；AgentSettings 从同一配置目录的 .env 和环境加载 CODEY_MAX_ITERATIONS，范围 1～100，Main 同时接入交互与 --prompt 入口。最大值为 20 时最多 19 轮可执行工具；设为 1 时只回答。运行设置启动时固定，修改后需重启。每次最多 16 个工具，因此单任务调度次数有界；toolCalls 计入未知工具和校验失败等执行器调用。但尚无总时间/Token 预算、重复无进展检测、工具超时或完整审计日志。COMPLETED 只表示最终文本响应，不代表修改/测试验收已通过。中间步骤显示轮次、工具与文件摘要，事件回调和 SSE 后续加入。

运行配置与收尾实现（已实现）：

| 字段 | 默认 / 范围 | 用途 |
|---|---|---|
| CODEY_MAX_ITERATIONS | 20 / 1–100 | 每条用户任务的模型请求上限，包含最后一次仅文本收尾；不限制会话用户输入总数 |
| CODEY_LIST_FILES_DEFAULT_DEPTH | 10 / 1–20 | 模型省略 list_files.maxDepth 时使用；模型显式传参仍受硬上限校验 |

[AgentSettings.java](../src/main/java/io/github/jackfeicoder/codey/config/AgentSettings.java) 从同一配置目录的 `.env` 读取，进程环境覆盖，缺字段使用默认；显式空值、非整数与越界值报 ConfigException，不回显原值。Main 将 maxIterations 注入 SessionLauncher/AgentLoop，将 defaultDepth 注入 ListFilesTool。工具动态生成 schema，保证展示的默认值与执行一致。修改后重启；`/model` 不重载这两项运行设置。

当前仓库从 `.` 开始计数，最深 Java 文件为第 10 层，`src/main/resources/prompts/base.md` 为第 5 层。默认深度可覆盖这些路径，仍受 200 条返回条数、16000 码点输出预算与 10000 扫描条数限制；大项目需根据 truncated/limitReason 选择子目录继续，深度不保证整棵树一次返回。新版 base.md 指导复用已有目录结果、沿关键调用链阅读和批量读取独立文件；旧会话沿用提示词快照，需 `/new` 或 `/clear` 采用新版基础指导，程序预算限制则启动后对所有会话生效。

轮次配置阶段验证：新增 22 项测试，当时完整 `mvn verify` 共 365 项通过，无失败/错误/跳过；6 组打包 JAR 本地 HTTP 验证覆盖真实仓库目录深度与默认配置、三次预算/自定义深度/收尾保存/下一任务重置、跨进程恢复不重放、服务商忽略 none 的程序拦截、无工作区单次入口、非法运行配置在网络请求前报错。HTTP 使用隔离配置和本地模型替身，真实仓库验证只列路径，不读取文件正文，未请求真实厂商。

可运行示例（CMD/PowerShell 都可用）：

```shell
java -jar target/codey-0.1.0-SNAPSHOT.jar --chat --workspace E:/Desktop/github/codeY --title 了解项目
```

输入“请读取 README.md，说明已实现的模块”。没有工作区的聊天及 `--prompt` 不展示 list_files/search_file/read_file。模型可用 list_files 浏览或 search_file 按名称定位，再用 read_file 阅读具体文件；内容搜索仍待接入。旧会话保留旧基础提示词，会显示摘要变化提示；`/new` 或 `/clear` 可采用新版工具指导。

首版验证记录：AgentLoopTest 15 项；工具请求对象/HTTP 映射 4 项；完整历史校验 12 项；工具快照/旧版本迁移 3 项；CLI 预算退出 1 项，合计新增 35 项。旧的“工具请求只提示”测试已更新为“未知工具反馈并提交完整轮次”。当时完整 `mvn verify` 为 272 项，调试阶段最新记录见下一节。

另以临时目录、假配置和本地 HTTP 替身执行 5 项 JAR 场景：read_file 定义发送/真实读取/回填/保存；关闭进程后恢复纯工具 assistant 与关联结果再继续；无工作区不发送 tools；--prompt 兼容；8 次预算退出不保存残缺任务。没有使用项目真实 .env 或真实模型服务。

<a id="console-debug"></a>

### 简洁控制台进度与 exit 返回列表

采用直接打印：AgentLoop 在请求和工具执行位置调用 [DebugPrinter.java](../src/main/java/io/github/jackfeicoder/codey/observability/DebugPrinter.java)。只显示轮次、工具名称、相关文件以及工具失败类别，不增加事件观察接口、日志框架或消息类型。ChatCompletionsClient 不打印请求/响应正文。

Main 在 --chat 和 --prompt 默认启用进度；旧 AgentLoop 构造器默认 DebugPrinter.NONE。终端沿用 Console 编码，重定向输出为 UTF-8。每条任务的输出例如：

```text
[第 1 轮] 请求模型
  [第 1 轮] 调用 read_file · missing.txt
  [第 1 轮] read_file 失败 · FILE_NOT_FOUND
[第 2 轮] 请求模型
  [第 2 轮] 调用 read_file · src/main/java/.../ConfigLoader.java
[第 3 轮] 请求模型
CodeY> 配置加载流程是……
```

工具参数只提取字符串 path 作为涉及文件，不打印 arguments、调用 ID、工具定义或执行结果正文；缺失或非法参数没有文件摘要，错误仍由执行器正常处理。长名称/路径按码点限制显示到 240 个，控制字符替换为空格，保持一行摘要；实际模型参数不裁剪。

系统提示词、历史、HTTP 请求/响应和工具文件内容不作为日志显示；最终回答由 CLI 正常输出，仍可能根据任务引用文件内容。完整上下文和工具结果继续发送模型并按原逻辑保存，简化显示不影响推理与恢复。打印不进入 Session、不保存额外日志。自动标题不显示请求正文、不算作 ReAct 步骤；新用户输入从第 1 轮重新计数。

会话管理导航：

| 输入位置 | 命令 | 行为 |
|---|---|---|
| 聊天中 | exit（忽略大小写、两侧空白） | 先重试待保存数据，再刷新全局最近列表；保留活动会话，可 /back 继续 |
| 管理列表中 | exit | 保存待写数据并退出程序 |
| 任意输入阶段 | /exit 或 EOF | 保留原行为：保存待写数据并退出程序 |

exit 不发送给模型，不新增消息或会话；返回列表后可 /new、选择其他标题/ID 或 /back。保存失败时停留在原状态并提示重试，避免丢失结果。旧的独立 ChatCli.run 内存入口没有管理页面，exit 与 /exit 都结束该内存入口；实际程序的导航由 SessionLauncher 实现。

当前进度/exit 有 3 项：简洁轮次/工具/文件与上下文隔离、exit/back 保持同一会话、exit 后新建与列表退出。移除旧完整 HTTP 打印的 2 项专用检查，完整 mvn verify 为 275 项，无失败或跳过。另通过本地 HTTP 的 3 项 JAR 检查：文件失败修正的摘要与完整结果持久化/exit/back；--prompt 仅一行进度和最终文本；HTTP 错误只展示业务错误、无原始响应正文。没有调用真实模型服务。

<a id="llm"></a>

### 已实现的非流式 LLM 调用

| 文件 | 职责 |
|---|---|
| LlmClient.java | 定义 generate(LlmRequest)，供 Agent 和标题生成使用 |
| ChatCompletionsClient.java | HTTP 传输、显式 JSON 映射、完整响应解析 |
| LlmRequest.java | 防御性复制非空消息与 tools 定义；toolCallsAllowed=false 时禁用工具，模型配置由客户端提供 |
| LlmResponse.java | assistant 消息、结束原因和可空 TokenUsage |
| LlmException.java | HTTP、网络、超时、中断、请求/响应错误与模型拒绝结果分类 |

```java
LlmConfig config = ConfigLoader.loadProfiles().activeConfig();
LlmClient client = new ChatCompletionsClient(config);
LlmResponse response = client.generate(new LlmRequest(List.of(
        Message.system("You are CodeY, a coding assistant."),
        Message.user("解释 Java record。")
)));
System.out.println(response.text());
```

实际执行：读取选中的 LlmConfig → 构建 model/messages/stream=false JSON → 向 BASE_URL 后追加 /chat/completions 发送 POST → 检查 HTTP 状态 → 解析 choices[0].message、finish_reason、usage → 返回 LlmResponse。

实现规则：

- 一个实现适配兼容接口，各实例绑定自己的不可变配置，复用默认 HttpClient；默认不跟随重定向。
- bearer 认证发送 Authorization，none 不发送；请求体不包含 API Key。
- 总等待超时覆盖响应头及完整响应体；超时取消 future，线程中断时取消请求并恢复中断标志。
- 明确返回完整结果；工具请求转换为 ToolCall，只解析不执行。参数必须为完整 JSON 对象，但不在此处验证具体工具 schema。
- 保留 finish_reason，length 表示截断；tool_calls 结束但没有调用时视为不完整响应。旧式 function_call、图片内容和非 function 工具类型暂不支持。
- usage 或某计数字段缺失时为 null；拒绝非法或负数计数，不把未知值计为零。
- HTTP 错误保留状态码；不输出原始响应、请求或可能包含内容的 Jackson 异常。不自动重试，isRetryable 仅为后续策略提供分类提示。
- 当前请求包含 messages 和可选 tools schema；生成参数和流式能力后续加入。

CLI 用法（CMD/PowerShell）：

```shell
mvn clean verify
java -jar target/codey-0.1.0-SNAPSHOT.jar --prompt "你好"
java -jar target/codey-0.1.0-SNAPSHOT.jar --prompt "解释 Java record" --profile qwen
```

每次 --prompt 是独立的一次请求，不恢复历史。help/version/list/check 不发起模型调用。参数或配置错误退出码为 2，模型调用错误为 1；获得响应时输出文本，截断时另外提示，工具调用暂不执行。

测试使用本地 HTTP 服务与假密钥，覆盖配置、协议、错误、超时及中断。工具前阶段为 178 项，工具基础 23 项、读文件 36 项、Agent 35 项、进度/exit 3 项、模型命令/偏好 19 项、共享配置目录 3 项，加上运行配置与收尾新增 22 项、文件定位与取消深度限制共新增 43 项，再加空回答处理 7 项，当前总计 415 项通过。没有发送真实服务商请求。

<a id="session-chat"></a>

### 已实现的 Session 与连续对话

| 类型/方法 | 当前职责 |
|---|---|
| Session(basePrompt) | 初始化一个内存会话，第一条为 system 消息 |
| messages() | 返回已保存历史的不可修改快照 |
| messagesFor(input) | 历史副本追加本轮问题，构造请求但不修改历史 |
| completeTurn(input, answer) | 校验后保存 user 与普通 assistant，形成完整成功轮次 |
| completeAgentTurn(turn) | 校验完整 user/assistant/tool 消息组后一次提交 |
| clear() | 删除所有对话轮次，保留原始 system 消息 |
| Session.restore(snapshot) | 从校验后的快照恢复，system 只出现一次 |
| ChatCli.answer(input) | 调用 AgentLoop、展示最终回答、提交完整轮次；返回是否需要保存 |
| ChatCli.run() | 保留供内存调用者使用；实际 CLI 使用 SessionLauncher 管理持久化与命令 |

```text
Main（无参数或 --chat）
  → SessionLauncher 全局历史选择 / 新建
  → 创建或恢复 SessionService，获得会话写锁
  → SessionLauncher 读取一行
      ├─ /exit 或 EOF：保存待写内容并退出
      ├─ 聊天 exit：保存并返回列表；列表 exit：退出程序
      ├─ /clear：保存旧会话，新建 ID，保留旧记录
      ├─ 会话命令：分页、改名、切换；不作为普通问题发送
      └─ 普通问题：ChatCli.answer → AgentLoop → Session.messagesFor → LlmRequest → generate
          ├─ 工具请求：执行各工具、回填关联结果、继续 generate
          ├─ 最终回答：completeAgentTurn → 显示 → captureTurn/save → 首轮标题任务
          └─ 模型失败/中断/预算结束仍无文本：提示，不提交残缺本轮
```

第二轮请求为 system → 第一问 → 第一答 → 第二问。请求快照和已保存历史独立，失败无需删除尾部消息；校验非法提交时历史也保持不变。截断回答保存已收到文本，同时提示 length，用户可继续追问。请求中断时结束交互，不再阻塞等待输入。

交互启动方式：

```shell
java -jar target/codey-0.1.0-SNAPSHOT.jar --chat
java -jar target/codey-0.1.0-SNAPSHOT.jar --chat --profile qwen
```

每行一个问题，/model 可在任务输入边界切换客户端并保留历史。默认采用最后选择，首次 --profile 可覆盖。终端使用 Console，重定向使用 UTF-8。正常退出返回 0；非法参数/单次入口配置错误返回 2；存储、提示词、输入错误返回 1。交互模型错误可继续，配置无效时保留原模型，未保存结果先重试。

Session 保存完整文本/工具对话，并非完整执行审计日志；不保存模型失败、中断或预算耗尽的轮次。SessionService/JsonSessionStore 已提供跨进程恢复，不重放旧工具调用。当前仍发送全部历史，没有 Token 预算、裁剪或摘要；超长时可 /clear，新会话不删除旧历史。

模块边界：Session 保存发生过的对话，ContextBuilder 后续选择和组织本次请求，Memory 后续提取/保存/召回可复用事实。当前不增加 context 或 memory 的具体实现。

阶段记录：最初新增 11 项 Session 与 7 项 ChatCli 测试，共 151 项通过，并完成 8 项内存交互 JAR 检查。目前入口与 /clear 已升级为持久化语义，最新验证见 [第 16 节](#workspace-sessions)。

协议依据：[OpenAI Chat Completions API Reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)。其他提供商的实际兼容性需要各自的请求样例验证。

<a id="code-guide"></a>

### 代码阅读顺序与当前调用流程

主代码与测试文件已加入中文说明。类注释描述职责，方法注释描述输入输出或验证目标，字段/关键局部变量注释说明数据含义；package-info.java 说明各包职责及当前/后续状态。

建议按下面顺序读已经实现的代码：

| 顺序 | 文件 | 阅读重点 |
|---|---|---|
| 1 | cli/Main.java | action/profile/prompt 三个参数变量，操作分发与退出码 |
| 2 | config/ConfigLoader.java | 合并来源、生成别名列表、确定默认选择、转换字段 |
| 3 | config/DotEnvReader.java | 逐行解析、引号和注释规则，错误只报告行号 |
| 4 | config/ModelProfiles.java | 别名与模型 ID 区别，按需加载，不可变切换 |
| 5 | config/LlmConfig.java、AuthMode.java、ConfigException.java | 强类型配置、认证约束与配置错误 |
| 6 | session/Message.java、MessageRole.java、tool/ToolCall.java | 四种消息来源，工具请求与结果的 ID 关联 |
| 7 | llm/LlmRequest.java、LlmResponse.java | 输入/输出数据契约与完整结果保存 |
| 8 | llm/LlmClient.java | 统一接口中 generate 的调用契约 |
| 9 | llm/ChatCompletionsClient.java | encode → HTTP 等待 → decode，以及错误和超时处理 |
| 10 | llm/LlmException.java | 错误类别、可空状态码与后续重试提示 |
| 11 | prompt/PromptLoader.java、PromptException.java、resources/prompts/base.md | classpath UTF-8 加载、资源错误，以及 system 消息注入 |
| 12 | session/Session.java、cli/ChatCli.java | 请求快照、成功提交、清空与输入循环；失败如何保持历史不变 |
| 13 | cli/SessionLauncher.java、config/SessionSettings.java、workspace 包 | 列表/对话状态、延迟模型校验、可选工作区与配置来源 |
| 14 | SessionSnapshot、SessionService、SessionStore、JsonSessionStore | 版本化 DTO、dirty 重试、文件锁、原子保存和恢复 |
| 15 | SessionTitleGenerator | 一次独立短请求，输入有界，结果不进入历史，失败回退 |
| 16 | tool/Tool、ToolDefinition、ToolContext、ToolRegistry、ToolExecutor、ToolResult、ToolException | 描述与实现分开、注册与执行分开、具体参数校验契约和结果回填 |
| 17 | tool/builtin/ReadFileTool、agent/AgentLoop、AgentResult、session/MessageHistory | 有界文件读取、工具定义发送、行动/观察循环、完整历史提交 |

运行 `--prompt` 时的实际调用链：

```text
Main.main(args)
  ├─ 解析 action、profile、prompt
  ├─ ConfigLoader.loadProfiles(...)
  │    ├─ DotEnvReader.read(.env) → 文件键值
  │    ├─ 合并 System.getenv() → 环境值覆盖文件值
  │    └─ ModelProfiles → 别名列表与当前选择的快照
  ├─ ModelProfiles.activeConfig()
  │    └─ ConfigLoader.buildConfig(prefix) → 已校验的 LlmConfig
  ├─ LlmClient client = new ChatCompletionsClient(config)
  ├─ PromptLoader.loadBase() → classpath /prompts/base.md 的 UTF-8 全文
  ├─ 创建临时 Session、工具注册表、无工作区 ToolContext → AgentLoop.run
  ├─ AgentLoop 创建 LlmRequest（无可用工具时省略 tools）→ client.generate(request)
  │    ├─ encode → model/messages/stream=false/可选 tools JSON
  │    ├─ HttpRequest → 地址、认证头、超时、正文
  │    ├─ sendAsync → future.get 限时等待完整响应
  │    ├─ 校验 HTTP 状态
  │    └─ decode → assistant Message、finishReason、TokenUsage
  └─ AgentLoop 正常结束输出 response.text()；截断或预算耗尽时提示状态
```

接口、多态与 record 的区别：

- LlmClient 声明方法，ChatCompletionsClient 实现方法；变量类型为接口时，实际执行仍由具体对象决定。
- LlmRequest/LlmResponse 等 record 保存数据，访问方法和 equals/hashCode/toString 由编译器提供；源码中的紧凑构造器主要负责校验。
- record 固定字段引用，但集合需要 List.copyOf/Map.copyOf 保护内容。局部变量保存本轮中间结果，字段保存对象需要长期持有的状态。
- role.wireValue() 和 JSON 编解码明确处理协议命名，内部 Java 字段不必直接使用接口下划线格式。

测试文件也按场景加了说明。配置测试使用临时文件和假环境，消息测试检查数据约束，HTTP 测试使用本地服务器与假密钥。阅读测试可先看方法注释，再按“准备输入 → 执行 → 断言结果 → 清理”追踪代码。

### 核心接口示意

以下是职责说明，不是当前可复制编译的完整接口：

```java
interface LlmClient {
    LlmResponse generate(LlmRequest request); // 当前接口，流式/取消接口后续扩展
}

interface Tool {
    ToolDefinition definition();
    boolean requiresWorkspace();
    void validateArguments(JsonNode arguments, ToolContext context);
    String execute(JsonNode arguments, ToolContext context) throws InterruptedException;
}

interface ContextBuilder {
    ContextSnapshot build(Session session, RunContext context);
}

interface SessionStore {
    void append(String sessionId, SessionEvent event);
    Session load(String sessionId);
}
```

选择 record 表达不可变数据，接口表达可替换边界。首轮实现中只为 LLM、工具、上下文、存储等真实边界建立接口；不机械地给所有类增加接口。

### 流式协议处理

1. 检查 HTTP 状态和响应类型，读取流时支持取消。
2. 按 SSE 事件边界收集 data 行，支持事件分片和多行 data。
3. 解析文本 delta；tool_calls 按索引组装 name 和 arguments。
4. 只有完整响应通过检查后，才执行工具。部分 JSON 参数不会直接执行。
5. 明确 finish_reason、正常终止和提前 EOF 的处理策略。服务商差异通过适配或配置显式处理。
6. 回调输出文本增量，返回值保留完整文本与工具调用；请求结束关闭流。

初期只支持一种兼容 Chat Completions 的请求格式。兼容不代表所有提供商行为相同，新增提供商时补充协议样例。

### 工具执行契约

- 工具定义包含名称、描述、JSON Schema、操作类别、超时与输出上限。
- ToolRegistry 只负责注册与查找；ToolExecutor 负责验证、审批、调度、取消、事件和归一化结果。
- 未知工具和非法参数作为工具失败回填，让模型收到可处理的原因。
- 读取默认带 offset/limit；列表和搜索分页；输出包含是否截断和继续读取方式。
- 写文件先展示或记录差异，执行后记录改动版本；初期完整写入，随后加入期望旧内容匹配的 patch。
- 同一轮有多个工具调用时，初期按模型给出的顺序执行；全部结果回填后才调用下一轮模型。
- stdout/stderr 有容量上限；保留退出码。超时后终止进程及相关子进程，并确认资源关闭。

### 错误与取消

| 场景 | 处理 |
|---|---|
| 配置缺失 | 启动前报错，说明字段名，不显示密钥 |
| HTTP 认证失败 | FAILED，不重复发送同一错误配置 |
| 限流或临时服务异常 | 有次数与总时间限制的退避；记录尝试 |
| 响应截断或解析失败 | 本轮不执行残缺工具调用，分类报错或有限重试 |
| 工具参数/路径错误 | ToolResult 失败回填，可供模型修正 |
| 写入或命令完成后结果丢失 | 先核对副作用，不自动重放 |
| 用户取消 | 每轮与工具前检查，传播到 HTTP 和进程 |
| 预算接近上限 | 提醒剩余次数，最后一次禁用工具；有效总结可保存，仍请求工具则返回 BUDGET_EXHAUSTED 且不提交残缺本轮 |

错误通过类型与状态传递，不仅返回一条包含“失败”的字符串。重试策略区分模型请求与有副作用的工具调用。

### 事件与完成验证

事件建议包括 RunStarted、LlmStarted、TextDelta、ToolStarted、ToolFinished、ContextCompacted、RunFinished。持久化关键状态事件；高频文本增量可另行汇总，避免每个字符都写状态快照。

代码修改任务的 verification 至少记录命令、工作区、执行时间、对应改动版本、退出码和输出摘要。AgentResult 中说明检查范围与未覆盖项。普通问答与代码修改采用不同的完成条件。

### 扩展边界

- 新模型：实现 LlmClient 或协议适配器。
- 新工具：实现 Tool，经统一执行器执行。
- MCP：将外部 schema 和调用结果适配成 ToolDefinition/ToolResult。
- CLI 改善或 API 接入：复用 AgentResult 与事件，不复制执行循环。
- 代码 RAG：作为检索来源进入 ContextBuilder，按实际任务需求加入。
- 多 Agent：各自拥有 Session/RunContext，资源共享必须显式协调，后续另写设计决策。

<a id="configuration"></a>

## 11. 配置加载与多模型选择

> 当前已实现：LlmConfig、AuthMode、ModelProfiles、ConfigLoader、DotEnvReader，以及 CLI 的 --list-models、--check-config、--profile、--prompt 和 --chat。支持命名 Profile 与旧版单模型配置。--check-config 仅本地校验，--prompt/--chat 才发起真实模型请求。

### .env、环境变量、YAML 和配置类

| 概念 | 是什么 | CodeY 当前行为 |
|---|---|---|
| 系统环境变量 | 进程从操作系统/父进程继承的键值 | 使用 System.getenv() 读取 |
| .env | 保存 KEY=VALUE 的普通文本文件 | DotEnvReader 显式读取启动目录的文件 |
| application.yml | YAML 格式的配置文件，常见于 Spring Boot | 当前不加载；普通 Java 需要解析与映射 |
| LlmConfig | 应用内部使用的强类型配置对象 | 将解析结果转换为 URI、Duration 等并验证 |

System.getenv() 不会因为目录里有 .env 就自动读取它。读 .env 后得到的键值只在 CodeY 配置加载器中合并，没有修改系统环境变量。

Spring Boot 有自动寻找 application.properties/application.yaml、合并多种配置来源和绑定对象的机制。普通 Java 的文件名没有这种自动行为。Spring Boot 默认也不会把 .env 当作 dotenv 文件解析，需要额外加载方式或在启动前注入环境变量。

两种文件可以协作。例如在 Spring Boot 中：

```yaml
llm:
  base-url: ${CODEY_LLM_BASE_URL}
  api-key: ${CODEY_LLM_API_KEY}
  model: ${CODEY_LLM_MODEL}
  timeout-seconds: ${CODEY_LLM_TIMEOUT_SECONDS:120}
```

这里占位符从 Spring 的配置环境解析；只创建 .env 并不会自动让这些占位符获得值。上述 YAML 是原理示例，不是 CodeY 的有效配置文件。

### 当前优先级与查找规则

```text
进程环境变量 > 启动目录的 .env > 代码默认值
            ↓
       ConfigLoader 合并
            ↓
       ModelProfiles 选择配置
            ↓
       选中的 LlmConfig 校验
            ↓
       后续提供给 LlmClient
```

- .env 只在当前工作目录查找，不搜索父目录、用户目录或 resources。
- .env 可缺失，只要环境变量提供全部必填值。
- 环境变量中存在空值时同样覆盖文件；必填配置校验失败，不偷偷回退。
- bearer 认证需要 API Key，none 认证不需要；模型和 URL 无默认值。
- 单 Profile 超时 > 公共 CODEY_LLM_TIMEOUT_SECONDS > 120 秒。字段优先级在文件与环境变量合并后应用。
- .env.example 是可提交的字段模板，不会作为运行配置读取。
- .env 无法读取、内容不符合约定或有重复字段时报错，只输出字段/行号，不回显内容。

启动目录与 Agent 工作区不同。模型配置一直从启动目录加载；SessionSettings 已加载 CODEY_WORKSPACE 作为新建菜单的建议，CODEY_DATA_DIR 控制存储位置，CODEY_AUTO_TITLE 控制额外标题请求。环境变量仍优先于启动目录 .env。

### 多 Profile 配置与选择

Profile 是一套地址、认证和超时的配置别名，不要求与服务商名字一致。MODEL 支持英文逗号分隔的多个模型 ID，同一 Profile 的模型共用连接配置。

```dotenv
CODEY_PROFILE_NAMES=deepseek,qwen,local
CODEY_ACTIVE_PROFILE=deepseek

CODEY_PROFILE_DEEPSEEK_BASE_URL=https://api.example.com/v1
CODEY_PROFILE_DEEPSEEK_API_KEY=YOUR_API_KEY
CODEY_PROFILE_DEEPSEEK_MODEL=YOUR_MODEL

CODEY_PROFILE_QWEN_BASE_URL=https://qwen.example.com/v1
CODEY_PROFILE_QWEN_API_KEY=YOUR_API_KEY
CODEY_PROFILE_QWEN_MODEL=YOUR_MODEL

CODEY_PROFILE_LOCAL_BASE_URL=http://localhost:8000/v1
CODEY_PROFILE_LOCAL_MODEL=YOUR_MODEL
CODEY_PROFILE_LOCAL_AUTH=none

CODEY_LLM_TIMEOUT_SECONDS=120
CODEY_PROFILE_LOCAL_TIMEOUT_SECONDS=60
```

上述地址是格式示例，需填写实际基础地址和模型标识。仅检查选中的配置，因此其他配置的占位符不会阻止已填写的 Profile 使用。

规则：

- CODEY_PROFILE_NAMES 声明别名，英文逗号分隔。别名以字母开头，只包含字母、数字和下划线，忽略大小写，重复报错。
- CODEY_ACTIVE_PROFILE 指定首次无偏好时的候选默认；无最后选择/保存别名/--profile 时，多 Profile 模式需填写。
- 字段前缀是 CODEY_PROFILE_ 加大写别名及下划线，例如 qwen 对应 CODEY_PROFILE_QWEN_。
- 支持 BASE_URL、MODEL、API_KEY、AUTH、TIMEOUT_SECONDS 五个后缀。
- AUTH 默认 bearer，必须有密钥；none 忽略密钥，LlmConfig 中不保留它，后续 HTTP 客户端应不发送认证头。
- 选择优先级：首次 --profile > 最后使用 > 恢复会话保存别名 > CODEY_ACTIVE_PROFILE；同名环境字段仍覆盖 .env。
- 有 CODEY_PROFILE_NAMES 就进入命名模式，缺失字段不会回退到旧版 CODEY_LLM_*，避免串用凭证；只有公共超时可共享。
- MODEL 可填多个 ID，英文逗号分隔；省略具体 ID 选择第一个，明确 ID 必须在该 Profile 列表内。
- --list-models 每个已配置模型显示一行，并标记本地配置校验状态；它不表示远端目录或可达性检查。

ConfigLoader.loadProfiles() 返回不可变配置快照。profileNames() 列出别名，activeConfig() 校验并返回当前配置，configFor(name) 读取指定配置。select(name) 校验目标后返回新快照，失败时原快照保持不变。

模型客户端不自动热更新；新建、恢复和 /model 明确切换时重新从启动目录解析配置。最后使用的模型作为默认，选择无效时要求明确输入别名。切换在两条用户任务之间生效，不改变正在进行的请求。

### 旧版单模型兼容

没有 CODEY_PROFILE_NAMES 时，旧配置仍可使用，并自动命名为 default：

| 配置项 | 必填 | 规则 |
|---|---|---|
| CODEY_LLM_BASE_URL | 是 | HTTP(S) 基础 URL，含服务商路径前缀；不含 userInfo、query、fragment |
| CODEY_LLM_API_KEY | bearer 时必填 | 非空；示例 YOUR_API_KEY 必须替换 |
| CODEY_LLM_MODEL | 是 | 服务商的模型标识，非空；YOUR_MODEL 必须替换 |
| CODEY_LLM_TIMEOUT_SECONDS | 否 | 正整数，默认 120 |
| CODEY_LLM_AUTH | 否 | bearer 或 none，默认 bearer |

BASE_URL 填服务商基础路径，例如 https://api.example.com/v1，不包含 /chat/completions。当前仅验证 URL 格式，不测试网络可达性。

模型名原样用于后续请求，不代表 CodeY 已确认该模型存在。例子中的 example.com 和占位 Key 仅说明格式。

<a id="model-commands"></a>

### 模型切换、目录、帮助与最后使用默认

交互入口位于 cli/SessionLauncher，仍用直接命令分发，无需新建命令框架：

| 命令 | 行为 |
|---|---|
| /help | 查看全部交互命令，管理页和聊天均可用 |
| /list model | 列出配置别名、实际模型 ID、本地配置状态，标记当前会话模型 |
| /model | 查看当前模型和切换用法 |
| /model qwen | 校验并切换到别名 qwen，保留当前会话的全部历史 |
| /model qwen MODEL_ID | 校验 MODEL_ID 属于 qwen 的 MODEL 列表，再切换到指定模型 |

第一个参数填写左列的 Profile 别名，第二个参数填写该供应商的真实模型 ID；省略第二个参数使用列表第一个模型。同一真实模型 ID 可出现在不同 Profile 下，由别名明确选择凭证与地址。未知别名、未登记的 ID 或未填齐配置会报告错误并保留原选择。目录通过 ConfigLoader.loadModelCatalog / ModelProfiles.listInfo 构造，每个模型单独一行，只展示非敏感字段，本地配置有效不表示远端一定连通。

配置例子（模型名称为占位示例，替换为平台提供的实际 ID）：

```dotenv
CODEY_PROFILE_QWEN_MODEL=model-a,model-b
CODEY_PROFILE_SENSENOVA_MODEL=nova-a,nova-b
```

```text
/model qwen model-b
/model sensenova nova-a
```

[ProfileSelection.java](../src/main/java/io/github/jackfeicoder/codey/config/ProfileSelection.java) 统一解析 `别名 [模型ID]`，最多两个参数；别名忽略大小写，模型 ID 大小写原样保存，支持斜杠/冒号/连字符。ModelProfiles.modelIdsFor 拆分逗号、去除首尾空白，拒绝空项和重复项；configFor 确认指定 ID 在列表内，再复制配置并用单个 MODEL 值构建原来的 LlmConfig。ChatCompletionsClient、AgentLoop 和消息组织不感知列表，不会把逗号串发给模型。--profile "qwen model-b" 和 CODEY_ACTIVE_PROFILE="qwen model-b" 也能指定模型；原来的单别名、单模型配置继续有效。

```text
/model 别名
  → 模型工厂重新加载启动目录配置，校验目标并创建聊天/标题客户端
  → SessionService.switchProfile：持锁保存新别名，成功后才改内存快照
  → 替换当前 ModelChoice（Session、ID、标题、工作区、提示词和历史不变）
  → ModelPreferenceStore 保存最后选择
下一条问题 → AgentLoop → 同一 Session 的 system/文本/完整工具消息组 → 新模型
```

新模型可以获得原会话上下文，因为宿主程序每次都发送完整历史，不是服务商内部记住了先前请求。模型切换本身不调用远端、不重放旧工具调用、不自动生成第二次标题；下一任务才使用新客户端。不同模型的上下文容量与工具协议兼容性可能不同，当前没有 Token 裁剪或转换为其他协议。

默认规则：首次显式 --profile > 用户最后选择 > 旧会话保存别名（恢复时）> .env CODEY_ACTIVE_PROFILE。启动覆盖只用于本次运行第一次选择；用户随后 /model 改选后，/new 使用新选择。新建、恢复、成功 /model 会记住选择，成功 --prompt 也更新默认；帮助、目录和 --check-config 不修改默认。新配置验证失败或会话别名保存失败保持旧客户端/默认；不存在用户偏好时才回退至会话/配置候选。

实现文件：[ModelPreferenceStore.java](../src/main/java/io/github/jackfeicoder/codey/config/ModelPreferenceStore.java)，位置为 CODEY_DATA_DIR/model-preference.json（默认 user.home/.codey）：

```json
{"schemaVersion":1,"lastUsedProfile":"qwen model-b"}
```

偏好只保存供应商别名与实际模型 ID，不保存密钥、地址或完整配置，不改 .env。会话的 profileAlias 字段同样保存 `供应商 模型ID`，保持快照版本兼容；旧单别名记录仍读取为该供应商的第一个模型。明确 ID 的记录不受 MODEL 列表重排影响；已保存模型被移除时报告错误并要求选择，不静默换模型。读取最多 4 KiB，校验版本、选择格式、重复键和尾随数据；保存到同目录临时文件、force 后原子替换，多进程最后成功写入生效。偏好损坏会提示并忽略；默认保存失败会报告重启默认未保存，但不撤销已经持锁保存成功的会话切换。偏好缓存属于当前进程，不实时监视其他进程或 .env。

SessionService.switchProfile 不重建内存 Session；会话快照只更新别名和 revision，不清空 user/assistant/tool。未保存的对话必须先保存；别名写入失败时不更新内存快照或客户端，可继续使用旧模型。切换在用户任务之间进行，当前 CLI 为单线程，不会在一轮工具执行途中改变客户端。

验证新增 19 项：CLI 模型命令 6 项、偏好存储 11 项、目录 1 项、工具历史保留 1 项。完整 mvn verify 为 294 项，无失败或跳过。最初一项既有快照测试遇到原子写入 I/O 失败，单独检查与随后完整重跑均通过，未调整原存储逻辑。另通过 8 项本地 HTTP JAR 场景，确认真实请求的 model ID 改变且历史保留、错误配置保持原选择、跨进程默认、--prompt 默认、启动覆盖、旧会话默认、浏览页命令无 HTTP、配置检查/模型列表；未向真实厂商发请求。

<a id="command-completion"></a>

### 命令与模型参数补全

使用 [JLine 的补全 API](https://jline.org/docs/tab-completion/)，版本 3.30.17，依赖 jline-reader、jline-terminal-jni；JNI provider 支持 Java 17 的 Windows 原生终端，Maven Shade 合并服务发现与原生资源。

| 文件 | 职责 |
|---|---|
| [ConsoleInput.java](../src/main/java/io/github/jackfeicoder/codey/cli/ConsoleInput.java) | 选择原生终端或逐行输入，配置编辑器与按键，关闭恢复终端状态 |
| [CommandCatalog.java](../src/main/java/io/github/jackfeicoder/codey/cli/CommandCatalog.java) | /help 与命令候选共用名称、参数格式、说明 |
| [CommandCompleter.java](../src/main/java/io/github/jackfeicoder/codey/cli/CommandCompleter.java) | 根据当前位置生成前缀匹配候选，按供应商筛选模型 ID |

流程：Main 打开 ConsoleInput → JLine 读取按键 → Tab 调用 CommandCompleter → 返回 Candidate → 唯一匹配直接补全，多个匹配在输入下方显示菜单 → 上下/Tab 选择、Enter 确认 → 再 Enter 提交完整命令 → SessionLauncher 分派。补全时不执行命令、不创建 LLM 客户端、不请求远端、不写模型偏好。

`/h` 补全 `/help`；`/model q` 补全供应商；`/model qwen ` 提示该供应商的模型；`/list m` 补全 `model`。第一词之后只补全约定的参数位置，普通聊天、标题和路径不提供命令候选。辅助输入关闭补全；Ctrl+C 取消当前行，EOF 沿用保存退出行为。JLine 只保留进程内编辑历史，没有新增命令历史文件。

DefaultParser 关闭引号/反斜杠解析和事件展开，外层引号仍由原 CLI unquote 处理，Windows 路径保持原样。移除部分终端的字面量鼠标前缀 `\\E[M`，并重建按键映射，避免反斜杠后接中文时被前缀匹配吞掉。无原生控制台的管道/IDE或终端初始化失败沿用 BufferedReader，不提供 Tab 菜单。命令执行仍在 SessionLauncher，CommandCatalog 只是说明目录，新增命令要同步实现分派。

TERM=dumb 或没有原生 Console 的输入继续逐行读取；窗口太小（不足 4 行或 20 列）时 Tab 只补全文字，不打开菜单，尺寸恢复后恢复菜单。

本次新增 16 项测试：多模型列表/隔离/指定选择/非法值/偏好与重启、命令候选与供应商筛选、虚拟终端实际 Tab/方向键/中文路径/小终端输入。完整 mvn verify 共 313 项通过，无失败或跳过。

打包验证：7 项本地 HTTP 场景确认单 ID 请求、历史保留、无效切换保持原模型、重启恢复、跨供应商认证隔离和 list/check 不发 HTTP；Windows 终端（xterm-256color，80×24）验证 `/h` + Tab、`/model qwen M` 候选菜单、下键选 Model-B、Enter 确认、再次 Enter 切换，以及 `/model` 显示确切选择。使用临时 .env 与数据目录，未修改用户实际凭证。

<a id="global-cmd"></a>

### Windows CMD 全局启动

入口文件：[bin/codey.cmd](../bin/codey.cmd)。当前开发安装将仓库 bin 目录加入用户 PATH，命令名称为 codey；系统 PATH 不需要修改。重新打开 CMD 即可从任意目录调用，无参数仍进入会话管理页面。

```text
CMD 当前目录 → PATH 查找 codey.cmd
  → %~dp0 定位脚本和 ../target/codey-0.1.0-SNAPSHOT.jar
  → 未显式设置时：CODEY_CONFIG_DIR = 仓库根目录
  → java -jar JAR %*：转发原参数，保留调用者当前目录
  → 返回 Java 原退出码；setlocal 不改变调用者配置变量
```

配置目录与工作区分开：ConfigLoader.configurationDirectory 从进程环境 CODEY_CONFIG_DIR 读取显式目录，缺失时保持原启动目录语义。指定值应是存在的目录，可相对启动目录解析；变量来自进程环境，不从 .env 自引用。模型和会话设置都从该目录 .env 加载，字段仍由进程环境覆盖。

SessionSettings 的相对 CODEY_DATA_DIR 以配置目录为基准；默认 user.home/.codey 是绝对路径，保持不变。Launcher 的启动目录和工作区相对路径仍来自原 CMD 目录，所以 `codey --chat --workspace .` 可操作当前项目而不丢失共享配置。脚本不会自动把本仓库当工作区，也不会扫描项目。

```cmd
codey
codey --help
codey --chat --workspace .
codey --chat --no-workspace
```

缺少 JAR 时提示在仓库执行 mvn package 并返回 1；脚本不隐式构建。需要 Java 17+ 位于 PATH，更新代码后重新构建；开发版脚本固定当前 JAR 版本，升级版本号时同步更新。首次安装可在 Windows 用户环境变量 Path 中添加仓库 bin 目录；当前窗口临时运行可用 `set "PATH=E:\Desktop\github\codeY\bin;%PATH%"`。

验证：新增 3 项共享配置目录测试，完整 mvn verify 共 297 项通过，无失败或跳过；5 项 CMD 场景验证解析命令、版本/帮助、带空格参数和当前工作区、共享配置/数据、跨目录历史、退出码及缺包提示。使用临时配置和本地 HTTP 模型替身，没有读取用户项目真实密钥或调用真实厂商。

### .env 支持范围

为了学习配置过程，当前用一个小型读取器，采用明确的单行格式：

```dotenv
# 注释行
CODEY_LLM_BASE_URL=https://api.example.com/v1
CODEY_LLM_API_KEY="replace-with-your-key"
CODEY_LLM_MODEL='replace-with-your-model'
CODEY_LLM_TIMEOUT_SECONDS=120 # 行末注释
```

支持 UTF-8（含 BOM）、空行、注释、可选 export 前缀、KEY=VALUE、单/双引号包围的单行值。首个等号后是值，值中的等号保留；引号中的 # 保留。无引号的 # 在行首或前面为空白时开始注释。

当前不做变量插值、命令替换、多行值和引号转义；反斜杠按字面保留。重复字段视为错误。它是 CodeY 的简化语法，不声称兼容所有 dotenv 实现。

### 使用方法

新建配置时，在项目目录运行。已有 .env 时手工补充字段，避免覆盖已填写的配置。

CMD：

```cmd
copy .env.example .env
```

PowerShell：

```powershell
Copy-Item .env.example .env
```

编辑 .env，替换当前需要使用的 Profile 地址、API Key 和模型名，保存为 UTF-8，然后执行（CMD/PowerShell 均可）：

```powershell
mvn clean verify
java -jar target/codey-0.1.0-SNAPSHOT.jar --check-config
java -jar target/codey-0.1.0-SNAPSHOT.jar --list-models
java -jar target/codey-0.1.0-SNAPSHOT.jar --check-config --profile qwen
java -jar target/codey-0.1.0-SNAPSHOT.jar --check-config --profile local
```

成功时输出配置已通过本地校验，并用 apiKey=<redacted> 隐藏密钥。失败时退出码为 2。help/version 不加载配置；无参数会话列表不要求模型凭证，但会读取会话设置与存储。

--profile 不修改 .env，必须配合 --check-config、--list-models、--prompt 或 --chat。每次只指定一种操作。/model 切换当前会话并更新最后选择，/help 和 /list model 已实现。

环境变量覆盖示例（PowerShell）：

```powershell
$env:CODEY_ACTIVE_PROFILE = "qwen"
java -jar target/codey-0.1.0-SNAPSHOT.jar --check-config
Remove-Item Env:CODEY_ACTIVE_PROFILE
```

.gitignore 已忽略 .env。当前不创建真实配置文件，也不将密钥打入 JAR。LlmConfig 的 toString 主动隐藏密钥，调用方仍需避免直接打印 apiKey()。

### 代码阅读顺序与测试

1. LlmConfig / AuthMode：理解 record、强类型、认证约束与重写 toString。
2. DotEnvReader：理解文件读取和简化语法边界。
3. ConfigLoader：理解配置合并、别名列表、类型转换与默认值。
4. ModelProfiles：理解选中时校验和不可变切换。
5. Main 的 --check-config / --list-models / --profile：理解启动前选择、校验和退出码。
6. ConfigLoaderTest / DotEnvReaderTest / ModelProfilesTest：用临时文件和显式 Map 测试，不依赖真实密钥。

测试覆盖：文件读取、环境覆盖、空值覆盖、必填字段、非法 URL/超时、示例占位符、BOM/引号/注释/等号、重复字段、错误不回显与对象输出脱敏；新增多 Profile、CLI 选择优先级、别名大小写、未知/重复别名、按需校验、认证方式、超时继承、配置隔离、切换失败保留原状态和旧配置兼容。

### 官方资料

- [Java 17 System.getenv](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/System.html#getenv())：读取当前进程环境。
- [Spring Boot 外部化配置](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)：文件加载、环境变量、占位符和属性绑定。

<a id="roadmap"></a>

## 12. 开发路线与验收标准

> 当前：非流式对话、list_files/search_file/read_file Agent 循环、完整工具对话保存/恢复、工作区选择和标题已完成。SSE、内容搜索工具与执行中恢复继续按路线推进。

### M0：工程骨架

- [x] 克隆仓库，建立 Java 17 单模块 Maven 工程。
- [x] 建立模块包、资源和测试目录。
- [x] 提供项目介绍、架构、模块、开发路线与面试笔记。
- [x] 验证 `mvn clean verify` 与 JAR 的默认、help、version 启动；非法参数返回退出码 2。

本阶段没有业务测试用例；构建通过表示编译与打包通过，入口通过手工命令检查。LLM/工具测试从后续阶段加入。

### M1：LLM 与基础 CLI

先实现 LlmConfig，按需扩展 AppConfig；再实现消息模型、LlmClient、兼容 Chat Completions 的 HTTP 调用和 SSE 拼接，先接入一个模型提供商。

- [x] LlmConfig 从启动目录 .env 和系统环境变量加载，支持默认超时与 --check-config。
- [x] 缺少密钥、模型和地址时给出字段级错误；打印配置隐藏 API Key。
- [x] 支持多 Profile、默认选择、--profile 覆盖、--list-models、none 认证与旧格式兼容；切换接口先校验目标配置。
- [x] Message/MessageRole 支持四种消息角色；ToolCall 和 toolCallId 表达请求与结果关联，校验单条消息约束。
- [x] 实现五个核心 LLM 文件，支持非流式 Chat Completions、--prompt 和分类错误。
- [x] 本地 HTTP 测试验证请求、文本/工具响应、usage、超时、线程中断与错误处理。
- [x] 加入 Jackson 与 Shade 打包，生成包含依赖的可执行 JAR。
- [x] 从 resources/prompts/base.md 加载基础提示词，通过 system 消息注入；缺失或空白时发送前失败。
- [x] 内存 Session 与 --chat 连续非流式对话，支持 /clear、/exit、EOF；失败不提交本轮。
- [x] 全局历史启动器、每页 10 条、/new、/resume、/rename；/clear 保留旧会话并新建 ID。
- [x] 可选工作区、文本快照、写锁、dirty 重试与一次独立自动标题请求。
- [x] 连续两轮保持历史顺序，使用本地接口验证交互 JAR。
- [ ] 使用真实平台配置验证非流式调用与提供商实际兼容性。
- [x] 支持 /model、/help、/list model 与最后选择默认，切换在任务输入边界生效。
- [ ] 流式输出接通后验证最终文本与流式展示一致。
- [ ] 测试多行 SSE、拆分参数、正常结束、提前 EOF，并补充流式错误和超时场景。
- [ ] 支持取消并关闭响应流；日志不输出 API Key。
- [ ] 加入流式能力后更新独立启动验证。

产出证据：脱敏请求/响应、流式样例、协议测试结果。面试：HTTP、SSE、超时、适配。

### M2：Agent 循环与只读工具

实现 AgentLoop、ToolRegistry、ToolExecutor、read_file/list_files/search_file/grep_code，使用结构化工具结果与运行状态。

- [x] 工具定义/接口、宿主上下文、注册表、基础同步执行器、结果与异常类型。
- [x] 校验完整 JSON 对象、调用具体参数校验、工作区可用性检查、有界输出与关联 tool 消息。
- [x] 内置 ReadFileTool，可通过 Java 注册表与执行器独立调用。
- [x] 工具请求 tools 字段与 CLI 装配接入。
- [ ] 输入“找到项目入口并解释”，模型自动搜索和读取文件。
- [x] assistant tool_calls 与全部 tool 结果配对，未知工具也有结果。
- [x] list_files 支持工作区目录探索，深度/条数/扫描量/输出预算有界；不跟随发现的链接。
- [x] search_file 按名称/路径通配符定位文件；与 list_files 共用路径/遍历基础能力，不读取正文。
- [ ] grep_code 按关键词/正则搜索文件内容，返回路径、行号和匹配行。
- [x] read_file 默认分页，大文件读取与输出有上限。
- [x] read_file 路径越界、符号链接等边界有明确处理；统一策略和强隔离后续完善。
- [x] 轮次与目录默认深度支持启动配置；临近上限提醒，最后一次仅回答；服务商仍请求工具时返回 BUDGET_EXHAUSTED。工具失败可在剩余预算内修正。
- [x] 用可控 LLM 替身验证完整循环，无真实 API 依赖。

产出证据：一份任务轨迹、工具参数与失败反馈。面试：ReAct、Function Calling、Harness。

### M3：修改、命令与验证闭环

加入 write_file、execute_command、执行策略、命令审批与验收证据。初期工具顺序执行。

- [ ] 在小型 Java 项目修复一个已知 Bug，先记录失败，再修改并通过测试。
- [ ] 命令工作目录正确；收集退出码和有上限的 stdout/stderr。
- [ ] 超时或取消能终止相关进程；取消不会被标记为完成。
- [ ] 验证证据在最新修改之后产生，测试失败不会返回 COMPLETED。
- [ ] 最终结果说明改动文件、验证命令及剩余问题。

产出证据：修改 diff、失败/成功测试记录、最终 AgentResult。面试：进程管理、副作用、完成判定。

### M4：上下文工程

统一 ContextBuilder，加入规则、历史、工具输出预算和摘要。规则约定文件名及优先级在本阶段确定。

- [ ] 每次模型调用前重新构建上下文，新增规则或 Skill 内容及时生效。
- [ ] 预算包含系统提示词、工具 schema、历史和预留输出；估算与实际 usage 分开。
- [ ] 压缩保留近期完整工具调用组、当前目标和项目约束。
- [ ] 完整事件历史留存，请求压缩不破坏源记录。
- [ ] 用同一模型和任务比较策略前后的任务结果、Token 与耗时。

产出证据：压缩前后样例、预算统计、失败案例。面试：Context Engineering、Token、摘要取舍。

### M5：会话与记忆

引入 JSONL 事件和状态快照、项目级长期记忆。先完成明确的本地存储和恢复逻辑。

- [x] 新进程可恢复文本消息、可选工作区与模型配置引用，凭证不写入会话 DTO。
- [x] JSON 文本快照具备文件锁、版本检查、原子替换与损坏记录隔离。
- [ ] 工具阶段增加执行事件与状态恢复。
- [ ] 项目记忆带范围、来源与时间，跨项目不串用。
- [ ] 写入采用明确单写者/锁和原子替换；损坏尾行可检测处理。
- [ ] 中断于工具执行前后时，避免盲目重放有副作用的操作。

产出证据：恢复实验、存储样例、记忆召回记录。面试：记忆与上下文、幂等、持久化。

### M6：MCP

先实现 stdio 与一个本地 MCP 服务。HTTP、多服务管理和资源协议另行扩展。

- [ ] 完成初始化、能力协商、工具发现与调用。
- [ ] 工具接入同一注册、权限和执行路径。
- [ ] 处理工具列表分页、并发请求 ID、超时、服务退出。
- [ ] 通知处理不会阻塞响应读取线程。
- [ ] 关闭客户端时完成或取消 pending futures，并关闭线程和子进程。
- [ ] 协议版本和支持能力在文档中明确。

产出证据：协议交互轨迹、工具演示、生命周期测试。面试：MCP、JSON-RPC、stdio、异步并发。

### M7：评估与展示

- [ ] 整理不少于 6 个固定任务：解释、搜索、修复、添加功能、测试失败反馈、取消/恢复。
- [ ] 保存任务集版本、模型配置、成功标准与每次执行结果。
- [ ] 报告成功率、Token、耗时、工具错误及主要失败原因。
- [ ] 提供可复现运行方式、演示过程与三项设计取舍。
- [ ] 更新 README，只列出已完成能力；面试笔记补充代码位置和实际证据。

多 Agent、复杂 RAG、HTTP API、TUI 以实际需求触发，另写设计与验收标准。

<a id="decisions"></a>

## 13. 设计决策

> 日期：2026-10-11。状态：采用为初始方案；未完成模块的细节可在实现时修订。

### D01：Java 17 与 Maven 单模块

原因：当前环境已有 Java 17，单模块足够支撑学习与首版。包边界表达职责，便于读完整调用链。

代价：无法利用 Java 21 虚拟线程；模块边界通过设计与检查维护。

重新评估：出现独立发布组件或明确并发需求时，再讨论升级语言版本或拆 Maven 模块。

### D02：先完成单 Agent 编程闭环

原因：核心循环、工具、上下文和验证是后续编排的基础。先在小型 Java 项目中验证实际可用性。

代价：初期不覆盖大任务并行拆解。

重新评估：已有任务集证明单 Agent 的具体瓶颈时，再引入 Plan、子 Agent 或 RAG。

### D03：使用 JDK HTTP，接入时引入 JSON 库

原因：直接理解请求、流、超时和关闭行为；JSON 使用成熟解析库，聚焦 Agent 逻辑。

代价：需要自己完成协议组装、SSE 和兼容差异处理。

重新评估：多提供商、多协议造成明确维护成本时，考虑复用 SDK，同时保留核心接口。

### D04：结构化结果与完整返回值

原因：避免流式展示后返回空文本，以及将错误字符串误判为完成。ToolResult 与 AgentResult 明确表达状态。

代价：初期模型较字符串返回更复杂。

重新评估：新增入口时继续复用结果和事件，不复制 Agent 循环。

### D05：每轮统一构建上下文

原因：让规则、历史、记忆和预算有统一入口，新信息能在下一轮生效。完整事件历史与请求视图分开。

代价：需要选择与压缩策略，估算 Token 存在误差。

重新评估：结合任务集调整预算、摘要格式与检索，避免没有证据地增加复杂度。

### D06：先串行工具，再加入受控并行

原因：写文件、命令和连续读写存在顺序依赖。首先保证效果与轨迹一致。

代价：多次独立读取可能较慢。

重新评估：指标证明只读工具等待占比高，再引入有边界的并行与资源协调。

### D07：阶段式持久化与明确恢复

原因：先跑通内存会话，再用用户目录的单个 JSON 快照保存文本会话，支持跨项目历史列表与无工作区聊天；工具阶段补充 JSONL 执行事件。详细方案见 [工作区与会话方案](#workspace-sessions)。

代价：JSON 快照每轮重写完整文件，适合初期小会话；需要文件锁、版本校验和原子替换。后续 JSONL 另处理尾行损坏和事件重放。

重新评估：出现并发存储或复杂查询时考虑 SQLite。任何存储形式都不自动保证有副作用的任务可安全重放。

### D08：有证据的完成判定

原因：编码任务需要实际测试或约定检查；模型回答与验收证据分别记录。

代价：增加执行耗时，验证不覆盖全部行为。

重新评估：按任务类型定义验证策略，持续扩展任务集，不用多 Agent 的文字审查替代实际检查。

### D09：显式加载 .env，环境变量优先

原因：用户希望本地使用 .env 保存模型配置。保持纯 Java 工程，用有明确语法范围的读取器演示文件读取、配置合并、类型转换和校验。

规则：系统环境变量 > 启动目录 .env > 默认值；空环境变量同样覆盖文件，必填字段失败时显式报错。当前不加载 YAML，不修改系统环境。

代价：简化 dotenv 不支持多行、引号转义或变量插值。未来语法需求复杂时可引入成熟读取库并保持现有优先级契约。

重新评估：出现分层配置和多环境需求时再增加配置来源；不为了文件名 application.yml 单独引入 Spring Boot。

### D10：命名 Profile 与不可变配置选择

原因：官方平台、第三方服务和本地模型都可以表达为命名配置。同一平台的不同模型也可定义多个别名，通过统一前缀解析。

规则：显式别名列表 + 默认别名；CLI 选择覆盖环境/文件。选中时才校验，避免未填写的备用配置阻塞当前使用。select 返回新快照，目标无效时保留原快照。none 认证不保留 API Key。

代价：当前重复填写同一平台的地址与密钥；当前不自动热加载文件，也不识别提供商的所有协议差异。

重新评估：多个模型共享配置造成实际维护问题时考虑 provider 与 model 分层。新增请求协议通过 LlmClient 适配，不凭服务商别名推断兼容性。

<a id="interview"></a>

## 14. 面试学习与项目证据

> 随实现积累。下面是问题和回答骨架，不代表已经做出所有能力，也不要把待验证的设计写成性能结论。

### 每个问题的记录模板

```text
问题：
原理：用自己的语言说明工作机制。
CodeY 实现：类/方法、输入输出与关键控制点。
取舍：为什么这样做，有什么代价。
遇到的问题：具体触发条件和处理过程。
证据：测试、运行轨迹、diff、指标或复现步骤。
后续改进：什么情况下需要改变设计。
```

每完成模块，整理原理、设计取舍、实际问题各一道。记录真实代码位置；功能完成前使用“计划实现”。

### M1：LLM 与流式调用

配置面试问题：为什么分开读取、合并、对象转换与用户默认偏好？如何隔离 Profile 凭证、保证切换失败保持原状态？为什么新模型能收到旧历史？相关实现见 config 包、SessionService.switchProfile 与 SessionLauncher 的模型命令。

已完成的消息部分可准备：为什么工具调用请求属于 assistant，而执行结果属于 tool？为什么 tool 消息需要 toolCallId？为什么只读消息仍需复制传入的 List？为什么有工具调用的 assistant 可以没有文本？代码和边界见 [消息模型](#messages)。

| 问题 | 回答要点 | 待补证据 |
|---|---|---|
| LLM 和 Agent 的区别？ | LLM 生成文本/行动；Agent 维护循环、状态并执行工具 | 一次完整请求流程 |
| SSE 与普通 HTTP 响应有什么区别？ | 同一 HTTP 响应逐事件读取；事件边界与 TCP 分片不同 | 拆分响应测试 |
| 流式 tool arguments 怎么处理？ | 按调用索引/ID 拼接，完整后解析和校验 | 多工具交错碎片 |
| 为什么流式展示后仍要返回完整结果？ | CLI、持久化和后续 API 都需要完整响应 | 文本一致性测试 |
| 超时、重试、取消如何区分？ | 连接/请求/总预算；可重试错误分类；取消传播 | 超时与取消轨迹 |
| 如何接入多个模型提供商？ | 协议适配、显式能力配置、同一业务接口 | 提供商样例差异 |

### M2：Agent 与工具

| 问题 | 回答要点 | 待补证据 |
|---|---|---|
| ReAct 是什么？ | 根据工具观察结果继续决定下一步；描述流程不要求展示模型内部思维 | 搜索到读取的轨迹 |
| Function Calling 谁执行？ | 模型返回结构化请求，宿主校验和执行 | 工具调用消息组 |
| Harness 是什么？ | 模型周围的循环、上下文、工具、策略和状态机制 | 模块架构图 |
| 为什么分开 Registry 与 Executor？ | 注册查找和执行策略的变化原因不同 | 核心类依赖关系 |
| 工具错误怎么反馈？ | 对应调用 ID 的结构化失败结果，让模型有机会纠正 | 参数修正案例 |
| 如何避免无限循环？ | 轮次、总时间、重复无进展检测和明确退出状态 | 预算耗尽测试 |

### M3：编程闭环

| 问题 | 回答要点 | 待补证据 |
|---|---|---|
| Agent 如何判定任务完成？ | 按任务类型检查验收证据，代码验证绑定最新修改 | 失败到成功的测试记录 |
| 为什么不能只相信模型说完成？ | 自然语言声明不是实际执行证据 | 提前结束案例 |
| 工具都并行执行会怎样？ | 数据依赖、写冲突、结果顺序与副作用顺序不同 | 冲突场景 |
| 命令工具如何管理输出？ | 并发消耗输出流、有界缓冲、退出码与超时 | 大输出与超时测试 |
| 重试写入和命令有什么问题？ | 副作用可能已发生，需检查结果和幂等条件 | 中断位置实验 |
| 路径限制等于沙箱吗？ | 文件路径校验与进程隔离是不同边界 | 路径/进程策略说明 |

### M4/M5：上下文、记忆与恢复

| 问题 | 回答要点 | 待补证据 |
|---|---|---|
| 上下文工程与提示词工程的关系？ | 上下文包含规则、历史、工具、检索与预算；提示词是其中部分 | ContextBuilder 输入输出 |
| 记忆、会话历史、RAG 有何区别？ | 长期事实、完整执行记录、外部知识检索的生命周期不同 | 实际存储与召回样例 |
| 为什么要压缩历史？ | 上下文有限且有成本，大量无关信息降低有效信息密度 | 对照任务结果与 Token |
| 压缩应该保留哪些内容？ | 目标、约束、关键发现、失败、下一步以及完整调用组 | 摘要样例 |
| Token 估算为什么不等于实际 usage？ | 分词器、schema、服务商协议和计费字段不同 | 估算/实际对照 |
| 会话恢复如何避免重复副作用？ | 持久化执行事件，检查未确认步骤与真实工作区状态 | 恢复实验 |
| 记忆如何避免项目污染？ | scope/projectId、来源、显式保存与可删除 | 两项目隔离测试 |

### M6/M7：MCP 与评估

| 问题 | 回答要点 | 待补证据 |
|---|---|---|
| MCP 与 Function Calling 的区别？ | 模型请求工具与宿主连接外部服务处于不同边界 | 两段协议流程 |
| JSON-RPC 怎么匹配异步响应？ | 请求 ID、pending future、超时清理与关闭 | 乱序响应测试 |
| 通知处理为何可能死锁？ | 在读取响应线程同步等待同一通道的响应 | 通知异步测试 |
| Agent 怎么评估？ | 固定任务、环境、模型配置、验收标准和重复实验 | 任务集与指标 |
| 如何证明优化来自 Harness？ | 保持模型与任务一致，只改变策略，记录失败原因 | 对照结果 |
| 多 Agent 一定更好吗？ | 额外协调、上下文与成本，需要任务证据支持 | 后续实验，当前未实现 |

### 项目介绍模板

完成后填入真实内容：

> CodeY 是我使用 Java 实现的 CLI 编程 Agent。它通过【已完成的工具与流程】执行代码任务。我重点处理了【实际问题一】、【实际问题二】和【实际问题三】，采用【具体设计】。在【任务集及模型配置】上，记录了【真实成功率、Token、耗时】，主要限制是【验证过的边界】。

### 三项值得积累的案例

1. 一次协议问题：例如工具参数碎片拼接或提前 EOF。
2. 一次执行问题：例如进程超时、文件冲突或错误完成状态。
3. 一次上下文问题：例如摘要遗漏约束、工具结果过大或规则注入时机。

每个案例保留触发条件、最小复现、修改和验证。案例由实际开发产生，不预先编造经历或收益。

<a id="prompt-design"></a>

## 15. Prompt 模块设计与文件约定

> 第一阶段已实现：PromptLoader 从 classpath 读取 prompts/base.md，Main 将全文作为 system 消息发送。项目指令、提示词组合、运行模式仍为后续设计。

### 主流实现的共同点与差异

提示词文件名没有统一标准。Markdown 便于阅读，但加载后仍是文本；文件发挥什么作用，取决于宿主代码的读取、组合和注入规则。

- Claude Code 使用 CLAUDE.md 保存项目约定、构建命令和工作习惯，具体发现范围与优先级由产品定义。见 [Claude Code 项目说明](https://code.claude.com/docs/en/memory)。
- Codex 使用 AGENTS.md 作为项目指令约定，并按目录范围组织指令。它与内置系统提示词属于不同来源。见 [Codex 提示词指南](https://developers.openai.com/cookbook/examples/gpt-5/codex_prompting_guide)。
- Deep Agents 组合基础提示词，并由中间件补充文件、记忆、子 Agent 等能力说明。见 [Deep Agents 架构](https://github.com/langchain-ai/deepagents/blob/main/libs/ARCHITECTURE.md)。
- Pi 选择较小的核心，允许通过模板、技能和扩展增加能力；其默认实现没有计划模式和子 Agent。见 [Pi Coding Agent](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/README.md)。

因此，CodeY 根据实际能力增加提示词片段，不预先复制其他项目的完整模板集合。

### 常见文件名的含义

| 文件名 | 常见职责 | CodeY 的处理 |
|---|---|---|
| system.md / base.md | 身份、回复习惯、证据要求、基础工作规则 | 第一阶段只做一个基础文件 |
| 项目名.md / CODEY.md | 自定义项目说明；也可能被作者用作内部模板 | 不是通用约定，先不增加 |
| AGENTS.md | 工作区中的项目指令，例如测试命令、代码规范 | 后续优先支持这个约定 |
| agent.md / Agent.md | 某个项目自定义的默认 Agent 模式或角色 | 与 AGENTS.md 区分，名称相近不代表同一机制 |
| CLAUDE.md | Claude Code 常用的项目规则文件 | 后续有兼容需求时做显式回退 |
| ReAct.md | 根据行动结果继续决策的工作指导 | 工具循环实现后按需增加，不要求模型输出内部思维 |
| plan.md | 规划行为或计划任务模板 | 先定义运行模式，再决定是否增加 |
| multi-agent.md | 协作角色、委派和交接规则 | 实现任务编排后再考虑 |
| auto.md / approve.md | 审批模式下的行为说明 | 与 Java 审批策略配套，文本本身不授予权限 |

PaiCLI 的实际目录将 base.md、modes、approvals、context 等分开。特别注意：其 modes/plan.md 是带 taskType、taskDescription 占位符的任务执行模板，含义与 Claude Code 的只读计划模式不同。不要根据文件名推断运行能力。

### 第一阶段：基础提示词加载

按当前约定使用 base.md，保存在 src/main/resources/prompts 中，作为运行资源随 JAR 打包。加载后直接使用 Markdown 原文，不转换成 HTML。说明性文档仍只维护 README 和本设计文档。

```text
src/main/java/io/github/jackfeicoder/codey/
└── prompt/
    ├── PromptLoader.java
    ├── PromptException.java
    └── package-info.java
src/main/resources/
└── prompts/
    └── base.md
```

PromptLoader 使用 UTF-8 从 classpath 读取内置资源，支持打包后的 JAR。基础资源缺失或为空时明确报错；不要依赖开发机上的 src/main/resources 文件路径。

base.md 包含：CodeY 的编程助手身份、默认中文回复、清晰解释代码、准确说明依据、只报告实际执行的操作；已加入按 tools 使用读取能力、分页、错误修正与文件内容作为数据的指导。没有相应工具时依据用户提供材料分析。

Main 的调用流程调整为：加载配置 → 选择模型 Profile → 读取基础提示词 → 组装 system 和 user 消息 → LlmClient.generate → 显示响应。提示词模块不依赖厂商别名，也不读取 API Key。

PromptException 表示资源缺失、空白或读取失败，由 Main 展示 Prompt error 并以退出码 1 结束；配置与模型异常继续使用各自类型。修改 base.md 后重新构建 JAR 即生效，无需调整 LLM 适配器。

### 第二阶段：项目指令与提示词组合

有工作区与会话后再增加两个类：

- ProjectInstructionsLoader：读取明确指定工作区根目录的 AGENTS.md；文件可选，缺失时继续运行。首版不递归扫描、不读取任意父目录；限制文件大小，例如 16 KiB，并校验实际路径属于工作区，记录来源路径。
- PromptAssembler：组合基础提示词、已启用模式和运行环境信息；将项目指令作为带来源标签的独立上下文块供 ContextBuilder 使用，避免与内置规则混成无来源文本。

首版只选一个项目规则来源。后续若支持 CLAUDE.md，明确使用“AGENTS.md 优先，缺失时回退 CLAUDE.md”的 CodeY 自身规则，不同时重复注入多个内容近似的文件。

```text
PromptLoader ─────────────── 基础行为说明 ─┐
ProjectInstructionsLoader ── 项目规则/来源 ─┼→ ContextBuilder → LlmRequest
PromptAssembler ──────────── 模式/运行环境 ─┤
Session ──────────────────── 历史/当前请求 ─┘
```

PromptAssembler 负责提示词片段组合；ContextBuilder 负责消息顺序、会话历史、工具结果和上下文预算。基础内容可以在会话开始时加载，模式与工具变化时重新组装相关片段；不要让 PromptLoader 同时承担记忆管理。

### 第三阶段：工具与模式提示词

工具接入后，由 ToolRegistry 提供真实的工具名称、描述和参数 schema，避免在提示词文件中维护另一份完整工具清单。只注入已实现且当前启用的能力说明；工具 schema 随后通过 LlmRequest 的协议字段传给模型。

ReAct 依赖 AgentLoop 实现“请求模型 → 校验工具调用 → 执行 → 写入 tool 消息 → 再次请求模型”，一个 ReAct.md 只是行为指导，不实现循环。原生 tool_calls 足以表达行动，不要求输出 Thought/Action/Observation 格式或模型内部思维。

如增加 PLAN 模式，先规定允许的工具范围和退出行为，再补充 plan.txt。审批模式同样先由 ToolExecutor 与 ApprovalPolicy 执行，再注入必要的说明。Claude Code 的计划与审批行为也由产品的权限机制控制，见 [Claude Code 权限说明](https://code.claude.com/docs/en/permissions)。

### 验收与面试要点

第一阶段验证 UTF-8 中文、缺失/空白基础资源，以及打包 JAR 后仍可读取。第二阶段验证可选项目文件缺失、大小限制、来源标记和路径范围；后续模式验证实际允许的工具，不以模型口头遵守规则作为权限测试。

提示词阶段的验证：PromptLoader 的 3 项测试与其余测试当时合计 133 项全部通过。另使用临时工作目录和本地模拟 HTTP 服务验证可执行 JAR：请求中的 system 内容与 base.md 全文一致且中文完整；移除资源的测试 JAR 在请求前以退出码 1 报错；--help 不依赖提示词资源。这 3 项检查没有调用真实模型。当前总测试数与交互验证见 [Session 与连续对话](#session-chat)。

面试可说明：Prompt 是给模型的行为与背景文本；Harness 通过代码控制上下文、循环、工具执行、审批和预算。MD 的数量不代表 Agent 的完整程度，应让提示词与实际能力保持一致。

<a id="workspace-sessions"></a>

## 16. 工作区选择、历史列表与会话恢复方案

> 实现状态：会话、完整工具对话与模型切换已完成；支持全局列表、分页、标题/ID 恢复、工作区、自动标题、JSON 快照、单写者锁和最后选择默认。执行中任务恢复与云端同步仍待实现。

### 参考依据与 CodeY 的取舍

本项目是 CLI，主要参考两者的 CLI 会话行为；不推断桌面端、云端与 CLI 使用同一套存储。

- Codex 提供 resume、--last、--all 与工作目录参数 --cd；恢复时当前目录与原目录不同会询问使用哪一个。借鉴“目录关联、可筛选历史、显式恢复”。见 [OpenAI 官方命令说明](https://learn.chatgpt.com/docs/developer-commands?surface=cli)。
- Claude Code 支持 --continue、--resume 和会话选择器，本地记录与项目目录关联；/clear 开始新上下文并保留原会话。借鉴“持续保存、恢复入口、清空与删除分离”。见 [Claude Code 会话说明](https://code.claude.com/docs/en/sessions)。

下面是 CodeY 自己的产品与工程决策，不是对上述产品内部类结构或文件格式的复制。按当前产品要求，入口默认展示所有工作区的近期会话，以标题或 ID 选择；目录作为会话属性。保留“有工作区 / 无工作区”选择，不引入云端同步、多根目录、Git worktree 和会话分支。

### 首版启动与选择流程

无参数与 --chat 使用同一会话启动器，--help/--version/--prompt 保持明确用途。启动器自动显示全局最近 10 条会话，不先显示目录筛选菜单。只有 /new 新建时才选择工作区；显式 --workspace/--no-workspace 可跳过选择。

```text
CodeY
最近会话（按最后更新时间倒序，所有工作区）
序号  会话 ID   标题              工作区             更新时间
1     a21f8b93  CodeY 配置解析    E:\...\codeY        ...
2     b34d7a62  Java record 学习  普通聊天           ...

输入序号、标题或 ID 继续会话
/new 新建   /more_session 下一批   /exit 退出

/new → 选择工作区
1. 当前目录：E:\Desktop\github\codeY
2. 输入其他目录
3. 不使用工作区，普通聊天
0. 返回

选择工作区 → 输入标题（可留空，第一轮后自动生成）→ 开始对话
```

启动器与历史列表只加载数据，不调用模型；没有历史时仍显示 /new 与 /exit。输入结束时正常退出，不在无输入的环境中反复等待或隐式新建。恢复后显示标题、工作区、选中的 Profile 与近期对话，等待下一条输入；不会自动重发上次问题。无成功轮次的空会话默认不列出。

分页采用全局最近更新顺序，更新时间相同用完整 ID 稳定排序。/more_session 明确定义为追加下一批 10 条，连续使用即可查看全部；到底时提示没有更多会话，不循环回第一页。启动器持有一次列表快照，序号在已显示项目中连续编号，不因其他进程更新会话而改变对应关系。/sessions 显式刷新并回到最近 10 条；大量数据以后改用数据库游标。

选择规则：序号只针对当前已显示列表；标题按完整标题精确匹配，ID 支持完整 UUID 或唯一前缀，标题/ID 搜索整个会话库，不只搜索最近一页。短 ID 默认展示前 8 位，冲突时增加展示长度；前缀匹配多个会话时显示候选项，不任意选择。标题允许重复，重名或不同选择方式产生歧义时同样列出候选项，要求按序号或完整 ID 指定。

启动器中的普通输入用于选择会话；进入对话后的普通输入视为用户问题，即使与某个会话标题相同，但保留 exit 作为返回列表命令。会话内使用 /resume 标题或ID 切换，避免标题和问题混淆。纯数字标题或标题 exit 可用 /resume 标题 明确指定。首版不自动用模糊匹配结果恢复，拼写不匹配时提示并展示可选项。

已提供直接入口，避免重复选择：

```shell
java -jar target/codey-0.1.0-SNAPSHOT.jar --chat --workspace "E:\projects\demo"
java -jar target/codey-0.1.0-SNAPSHOT.jar --chat --no-workspace
java -jar target/codey-0.1.0-SNAPSHOT.jar --chat --resume SESSION_ID_OR_TITLE
java -jar target/codey-0.1.0-SNAPSHOT.jar --chat --resume "CodeY 配置解析" --profile qwen
```

--workspace 或 --no-workspace 直接新建，跳过启动列表，二者互斥；--title 可直接提供标题，留空标题通过交互回车选择自动生成。--resume 与工作区/标题覆盖互斥，--profile 可覆盖恢复时别名。--prompt 不自动保存。只有完整 UUID 可恢复未出现在列表的空会话，其他选择方式针对已有历史。

### 标题生成、修改与唯一标识

每个会话有稳定 UUID 和可修改标题。UUID 用于文件目录、保存、恢复和引用；标题只是显示与查找字段，改名不移动目录，也不产生新会话。

- 新建时标题可由 --title 或交互输入指定，要求单行、非空白且不超过 40 个 Unicode 码点。标题可重复，不限制为文件名。
- 用户未指定时，初始为“新会话”；第一轮成功后先从第一次用户问题生成本地摘要占位，立即保存对话，再用独立模型请求生成一次简洁标题。标题不是第一轮正常回答，也不直接取模型整段回答作为标题。
- 标题请求只带生成标题的指令与第一条问题的有界副本，最多 2000 个码点；不添加到 Session.messages，不携带整个历史，不调用工具，也不触发下一次标题生成。
- 标题模型使用第一轮成功时的 Profile，超时取模型配置与 5 秒中的较小值。正常回答展示与保存后执行，可能短暂等待；后续再考虑后台任务。标题失败单独提示，不算用户的一轮对话；当前未持久化独立成本指标。
- 校验生成结果为非空、单行且长度合规；失败、超时或结果无效时保留本地摘要，不影响已保存回答，不在每次启动或每轮对话时重复请求。配置可关闭额外模型调用，采用本地摘要。
- 发起生成前保存已尝试标记；若进程在标题生成期间结束，恢复时保留本地摘要并按回退处理，不自动再发一次。当前只保存成功轮次，标题的首问指首次成功提交的用户问题。
- /rename 新标题 允许用户随时修改。用户指定或手动修改的标题优先；将来异步生成时检查会话 ID、标题来源及生成任务版本，避免旧结果覆盖用户改名或写入另一个会话。

标题生成只决定列表中的名称，不代表准确的全会话摘要。第一版不随话题变化持续改名，用户可手动修改；未来可增加显式重新生成命令。

### 工作区的语义与配置来源

WorkspaceBinding 明确区分 DIRECTORY 与 NONE：DIRECTORY 保存绝对真实路径；NONE 没有目录字段。不要用空字符串、当前目录或 user.home 冒充“无工作区”。工作区绑定在创建会话时确定，首版不支持会话内 /cd。

- 当前启动目录只用于查找 .env；选择工作区不调用 chdir，不改写 user.dir，也不自动读取选中项目的密钥文件。
- 相对路径相对启动目录解析；接受带空格的路径，使用 Path.toRealPath 校验存在且为目录，不要求必须是 Git 仓库，也不要求目录一定可写。
- 启动参数优先；没有参数时通过菜单明确选择。CODEY_WORKSPACE 提供“配置建议目录”菜单项，进程环境覆盖启动目录 .env；模板中的点号不自动跳过选择，也不覆盖历史会话的绑定。
- 配置建议目录作为额外选项展示，即使与当前目录相同也不会自动选定；失效建议不阻止选择其他目录。显式 --workspace 无效时报告参数错误。
- Main 注册 list_files、search_file 与 read_file，按会话工作区筛选是否展示；NONE 不展示且拒绝执行工作区工具。内容搜索/命令和项目指令后续接入。选目录本身不扫描整个项目或把全项目注入模型。

工作区是文件和命令的定位依据；read_file 已按真实工作区检查路径，审批与进程隔离后续由策略模块处理。选定目录后模型可浏览目录、按名称定位文件并读取其内容，但当前没有按内容搜索、写入或命令能力。

### 会话数据结构与保存内容

第一版采用单个版本化 SessionSnapshot，避免元数据与消息分文件更新造成不一致：

| 字段 | 含义 |
|---|---|
| schemaVersion | 当前为 2，支持完整工具轮次；旧 v1 文本记录可加载并升级 |
| id | UUID，与目录名一致；标题/前缀解析为合法完整 ID 后再加载 |
| title | 用户标题、模型生成标题，或首问的本地摘要 |
| titleSource | MANUAL、PENDING、LLM、FALLBACK，表示标题来源与生成状态 |
| titleGenerationAttempted | 自动生成已尝试或由手动改名终止，避免恢复后重复调用 |
| createdAt / updatedAt | UTC 时间，显示时转换为本地时间 |
| revision | 每次成功保存递增，检查是否覆盖了陈旧快照 |
| workspace | DIRECTORY + 实际路径，或 NONE |
| profileAlias | 当前继续使用的模型配置别名，不包含凭证 |
| systemPrompt / promptHash | 创建时的基础提示词快照及摘要 |
| messages | 已提交 user/assistant/tool 列表，完整轮次与调用 ID 配对，按原顺序保留 |

systemPrompt 单独存储，恢复时仅生成一条 system 消息，再追加保存的对话；不向已包含 system 的列表重复注入 base.md。数据 DTO 与 LLM 请求 JSON 分开，磁盘版本不依赖服务商 wire 格式。

文件中不序列化 LlmConfig、完整环境变量或 API Key；Profile 的当前凭证只在启动时重新解析。消息是用户与模型产生的原文，因此仍可能含用户主动粘贴的敏感内容。恢复旧会话首版沿用其基础提示词快照，若与当前 base.md 摘要不同则显示提示；后续项目规则与真实工具能力需重新加载，不恢复过期权限。

当前保存有最终回答的完整文本/工具轮次；工具业务失败可作为结果随完成轮次保存，模型失败、中断或预算结束仍无文本时不提交残缺本轮；有效收尾总结可保存并提示预算受限。执行状态和事件日志后续增加，不承诺恢复正在执行的任务。

### 本地存储与写入策略

默认存储根为 Java user.home 下的 .codey；可通过 CODEY_DATA_DIR 显式指定，进程环境覆盖启动目录 .env。会话文件独立于工作区，项目移动或无工作区聊天不影响数据位置。保存根与工作区路径是两个字段。

```text
用户目录/.codey/sessions/<UUID>/
├── session.json       # 完整快照，唯一数据来源
└── session.lock       # 写入期间及会话使用期间的文件锁
```

SessionStore 提供 create/save/load/list；实现使用已引入的 Jackson，无需新增数据库依赖。首版扫描 session.json 形成所有会话的列表，排序后分页，不默认按工作区过滤，也不先维护另一份全局索引。代价是小会话适合，大量/大型会话后再考虑 SQLite 或可重建索引；工作区筛选可作为后续可选条件。

每个成功轮次立即保存，不能只依赖 /exit 或 shutdown hook。保存时持有会话文件锁，校验 revision，UTF-8 写同目录临时文件并 force，再原子替换 session.json。目标文件系统不支持原子替换时明确报错并保留旧快照，不把非原子覆盖描述为同等保证；正常进程中断时恢复最后完成的快照，断电耐久性仍取决于文件系统。

同一会话只允许一个进程继续写，第二个进程可以查看列表但不能取得写锁；锁由操作系统释放，不通过删除锁文件判定解锁。不同会话各用自己的锁。加载检查版本、字段、消息角色和完整轮次；损坏/不支持的文件标记错误，不覆盖为空会话，一个损坏项不阻止其他历史显示。

存储失败时保留本轮内存结果并明确标记“未保存”；先重试保存，再继续下一轮模型调用。退出时再尝试，仍失败以错误退出码报告；不把只展示了回答当作已经保存，也不自动重新请求模型生成相同回答。

### 恢复、模型选择与目录变化

恢复顺序：加载并校验快照 → 检查工作区 → 选择并校验模型配置 → 获得会话写锁后复核 revision → 重建 Session → 显示近期历史 → 等待输入。列表和历史查看不依赖有效 API Key，准备继续对话时才要求可用配置。

首次 Profile 选择为 --profile > 用户最后选择 > 历史保存别名 > .env 默认；最后选择失效时报告并要求明确选择，不静默换厂商。成功进入/切换后保存最后选择，旧会话恢复也采用该默认；只有缺少用户偏好时才参考旧会话别名。不复制旧凭证或清空消息。

原工作区存在则沿用；原目录失效仍可查看历史，但不自动使用启动目录。首版提供返回菜单、新建普通聊天或新建另一工作区会话；保留原会话绑定与记录。目录迁移、复制历史后换工作区属于后续显式迁移/分支功能。

恢复会话不恢复旧进程、不还原文件版本、不重放写入或命令。后续接入工具时，重新读取当前项目状态，检查未完成执行，再决定如何继续。

### 会话内命令的语义

| 命令 | 持久化阶段计划行为 |
|---|---|
| /exit | 保存待写快照后退出，保存失败报告错误退出码 |
| exit | 聊天返回列表，列表中退出；不发模型，保留可 /back 的活动会话 |
| /new | 保留当前记录，进入新会话工作区选择 |
| /sessions | 保留当前记录，显示所有工作区最近 10 条会话，重置分页快照 |
| /more_session | 在列表中追加下一批 10 条；会话内先打开历史列表，展示最近一批 |
| /resume [标题或ID] | 无参数打开历史选择器，有参数按精确标题或唯一 ID 解析后恢复 |
| /rename 新标题 | 只修改当前会话标题和标题来源，不改变 ID 与消息历史 |
| /clear | 保存当前会话，创建新 ID，沿用工作区与当前 Profile，使用当前 base.md；不删除原历史 |
| /model [配置别名] | 查看当前模型或切换会话模型，保留历史并保存最后选择 |
| /list model | 模型配置目录：别名、模型 ID、配置状态；不探测远端 |
| /help | 列出全部交互命令，不调用模型 |

上表均已实现。/clear 新建无手动标题的会话，原会话仍可恢复。菜单、模型目录、模型切换、分页、改名本身不调用模型，只有首轮成功后的标题任务可能额外调用。删除会话后续另设功能。

### 模块职责与实施顺序

| 类型/模块 | 实现职责 |
|---|---|
| workspace/WorkspaceBinding、WorkspaceResolver | 表达可选工作区，解析与校验目录 |
| session/SessionSnapshot | 合并元数据与消息的版本化 DTO，校验 ID、角色、提示词摘要和时间 |
| session/Session | 保持轻量内存消息，增加 restore；元数据和工作区通过 SessionService 的快照关联 |
| session/SessionStore、JsonSessionStore | 本地读写、列表、版本检查和会话写锁 |
| session/SessionService | 协调新建、恢复、保存、标题修改与切换，不直接输出终端 |
| session/SessionTitleGenerator | 首问标题生成、长度校验与本地回退，独立于普通对话历史 |
| cli/SessionLauncher | 全局最近列表、稳定分页、标题/ID 选择与工作区输入；ChatCli 继续负责对话输入 |
| config | 解析存储位置与建议工作区，保持模型凭证来源不变 |

这些步骤已接通。SessionService 维持 dirty 快照，失败时先重试保存；switchProfile 先保存成功再更新属性。JsonSessionStore 限制快照 16 MiB，严格校验并原子替换。/model 和用户默认已实现，项目规则、上下文预算、执行中恢复和云端接口后续增加。

实现调用流程：

```text
Main → SessionSettings + JsonSessionStore → SessionLauncher
  ├─ list → 最近 10 条 / more → 稳定列表快照
  ├─ /new → WorkspaceResolver → 标题 → 延迟选择模型 → SessionService.create
  └─ /resume → 标题/ID 消歧 → 历史预览 → 检查目录/模型 → lock/load → Session.restore
活动会话：ChatCli.answer → Session.completeTurn → captureTurn → save
首轮无手动标题：保存摘要 → 保存尝试标记 → 独立标题请求 → 校验/回退 → 再保存
```

验收：全局列表包含不同目录与普通聊天；首次展示 10 条、下一批无重复且序号稳定、到底明确提示；标题与完整/短 ID 选择、重名与前缀冲突处理；手动标题优先、自动任务仅一次且不入历史、失败回退、改名后 ID 不变；无工作区与不同项目分别新建；相对路径/空格/链接目录解析；选工作区不改变 .env 来源；成功轮次重启恢复；失败不入历史；系统消息只有一条；/clear 后原会话仍可恢复；菜单不发请求；损坏文件与失效工作区可定位；Profile 缺失不静默切换；保存失败不谎报成功；同会话双进程仅一写者。所有测试使用临时目录和本地模型替身。

验证记录：新增 27 项持久化、启动器、标题、配置与目录测试，连同原有测试共 178 项通过。已通过本地模拟 HTTP 的 12 项 JAR 检查：默认入口与新建、跨进程按标题恢复及 Profile 覆盖、无凭证查看列表、/clear 留存旧记录、标题回退及失败/工具轮次排除、全局 23 条分页、重名/短 ID 消歧、双进程互斥写锁、单次调用兼容、参数冲突/帮助/版本，以及真实慢标题响应触发 5 秒请求超时、强制结束进程后锁释放并恢复最后快照。未向真实厂商发送请求。

## 17. 参考资料与维护方式

- [PaiCLI](https://gitcode.com/javabetter/paicli)：参考实际编程 Agent 的工程场景。本机源码分析原文保留在 `E:\Desktop\github\paicli\docs\paicli-source-architecture-guide.md`；其中描述的是 PaiCLI，不代表 CodeY 已实现的能力。
- [Hello-Agents](https://github.com/datawhalechina/hello-agents)：按需参考框架、记忆、上下文工程相关章节。
- [Agent Harness 概念划分](https://www.langchain.com/blog/agent-frameworks-runtimes-and-harnesses-oh-my)：理解框架、运行时与 Harness 的职责边界。
- [长期任务 Harness 实践](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)：理解进度交接、验收与跨会话工作。

说明性文档仅维护两份 Markdown：README 提供快速使用，并按模块记录功能、涉及文件、调用流程与实现方式；本设计文档集中保留完整架构、配置契约、实施顺序、取舍和学习笔记。后续实现模块时同步更新 README 导航、对应模块章节与此处状态。resources/prompts/base.md 是运行资源。完成阶段后补充真实验证证据，规划能力始终明确标注为待实现。
