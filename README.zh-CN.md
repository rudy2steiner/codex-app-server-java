# codex-app-server-java

[English](README.md) | [简体中文](README.zh-CN.md)

一个面向 [Codex app-server](https://github.com/openai/codex/tree/main/codex-rs/app-server) 协议、独立且不绑定框架的 Java 客户端。

> [!IMPORTANT]
> 本项目目前处于设计和初始化阶段，尚未发布到 Maven Central，也暂未提供可用的客户端 API。

`codex-app-server-java` 旨在让 Java 应用无需解析信息损失较大的 `codex exec --json` 输出，也能集成完整、可交互的 Codex 体验。它将负责管理本地 `codex app-server` 进程、使用双向类 JSON-RPC 协议通信、暴露强类型 Java 事件，并保留未知协议数据以兼容未来版本。

这是一个独立的社区项目，不是 OpenAI 官方 SDK，也不隶属于 OpenAI 或受其背书。

## 为什么需要这个项目

Codex 提供了两种不同的集成方式：

- `codex exec --json` 是面向脚本、自动化和 CI 的非交互式 JSONL 事件流。
- `codex app-server` 是面向富客户端的双向接口，提供线程、轮次、流式条目、审批、用户输入请求、命令输出、文件修改、计划、Token 用量、中断、动态引导以及持久化会话历史。

OpenAI 目前提供了能够与本地 app-server 通信的 Python SDK。TypeScript Codex SDK 封装的是非交互式 CLI 流程，目前还没有官方 Java app-server SDK。因此，Java 应用需要自行处理进程管理、请求路由、通知、服务端主动请求以及协议版本演进。

本项目希望提供一个可复用的 Java 集成库，避免每个应用分别重复实现这些能力。

## 项目目标

- 启动并管理本地 `codex app-server` 子进程。
- 优先支持默认的 JSONL/stdio 传输。
- 实现初始化握手和双向请求路由。
- 为线程、轮次、条目、审批和用户输入提供强类型 Java API。
- 按原始顺序传递消息、推理、计划、工具、文件修改、用量、警告和生命周期事件。
- 显式保留未知方法、条目类型、枚举值和字段，避免 Codex 升级后客户端直接失败。
- 跟踪受支持 Codex CLI 版本生成的协议 Schema。
- 为下游契约测试提供确定性 fixtures 和模拟 app-server。
- 公共 API 不依赖 Spring、Reactor 或特定 UI 框架。
- 保持可迁移架构，以便未来并入官方上游 Java SDK，或被官方实现替换。

## 非目标

- 重新实现 Codex Agent 或模型运行时。
- 封装 OpenAI REST API；此场景请使用官方 [OpenAI Java 库](https://github.com/openai/openai-java)。
- 提供终端或 Web UI。
- 定义特定应用的会话记录、持久化或展示模型。
- 通过自动批准所有请求来隐藏安全决策。
- 在 Codex 正式声明 WebSocket 可用于生产前，将其视为稳定传输。

## 协议概览

app-server 协议类似 JSON-RPC 2.0，但线上消息不包含 `"jsonrpc": "2.0"` 字段。

请求包含 ID、方法和可选参数：

```json
{"id":1,"method":"thread/start","params":{"cwd":"/workspace/project"}}
```

响应包含匹配的 ID，以及结果或错误：

```json
{"id":1,"result":{"thread":{"id":"019..."}}}
```

通知包含方法，但没有 ID：

```json
{"method":"item/agentMessage/delta","params":{"threadId":"019...","turnId":"019...","itemId":"item_1","delta":"你好"}}
```

服务端也可以主动发起请求，例如命令审批或结构化用户输入。正确的客户端必须在轮次运行期间持续读取消息，并路由以下四种消息形态：

| 消息形态 | 含义 |
| --- | --- |
| `id` + `method` | 服务端或客户端发起、需要响应的请求 |
| `id` + `result` | 成功响应 |
| `id` + `error` | 失败响应 |
| 只有 `method`、没有 `id` | 通知 |

典型生命周期：

```text
启动进程
    -> initialize
    -> initialized
    -> thread/start 或 thread/resume
    -> turn/start
    -> turn/started
    -> item/started
    -> 零个或多个条目增量事件
    -> item/completed
    -> turn/completed
```

## 规划架构

```text
Java 应用
      |
      v
CodexClient
      |-- 请求关联与超时
      |-- 通知订阅
      |-- 审批与用户输入处理器
      |-- 线程和轮次生命周期
      v
CodexTransport
      |-- JSONL/stdio 进程传输
      `-- 未来传输方式使用相同接口
      v
codex app-server
```

初始仓库计划包含三个模块：

| 模块 | 职责 |
| --- | --- |
| `codex-app-server-protocol` | Wire Envelope、强类型 v2 Payload、未知事件回退和 Schema 元数据 |
| `codex-app-server-client` | 进程生命周期、stdio 传输、RPC 路由、重试、订阅以及高层线程/轮次 API |
| `codex-app-server-testing` | 模拟服务端、协议 fixtures、契约测试辅助工具和兼容性断言 |

特定应用的事件归一化保留在 SDK 外部：

```text
Codex Wire Event
    -> codex-app-server-java
    -> 应用适配器
    -> 应用事件模型
    -> 持久化和 UI
```

通过这个边界，应用未来可以接入官方 Java SDK，而无需重写存储和展示层。

## API 规划方向

最终 API 将通过测试和首个真实集成来确定。预期使用方式如下：

```java
try (CodexClient client = CodexClient.start()) {
    CodexThread thread = client.startThread(
        ThreadOptions.builder()
            .cwd(Path.of("/workspace/project"))
            .build());

    TurnHandle turn = thread.startTurn("检查这个仓库。");
    turn.events().subscribe(event -> System.out.println(event));

    TurnResult result = turn.await();
    System.out.println(result.finalResponse());
}
```

这个示例仅用于说明 API 方向；其中的类尚未实现。

## 兼容策略

Codex app-server 协议会随 Codex CLI 持续演进。CLI 可以生成与当前安装版本匹配的 Schema：

```bash
codex app-server generate-json-schema --out ./schemas
codex app-server generate-ts --out ./schemas-ts
```

SDK 会将这些 Schema 作为版本化兼容输入，但不会因此拒绝未来新增的数据。

兼容规则：

1. 防御性解析消息 Envelope。
2. 为每条消息保留完整原始 Payload。
3. 显式表示未知通知和未知条目类型。
4. 解码已知类型时忽略未知字段。
5. 不将未知枚举值静默解释为某个已知值。
6. 使用录制和生成的 fixtures 测试每个受支持的 Codex CLI 版本。
7. 在首个稳定版本发布前公布兼容矩阵。

初始兼容目标是 stdio 传输。Codex 当前仍将 WebSocket 标记为实验能力，因此本项目只会在后续考虑支持。

## 安全模型

SDK 不会代替应用做出审批决定。

- 应用必须显式选择沙箱和审批策略。
- 服务端主动发起的审批请求必须被明确处理或拒绝。
- 子进程环境变量和工作目录继承必须可配置。
- 原始协议 Trace 可能包含提示词、路径、命令输出或其他敏感数据，因此必须显式启用并支持脱敏。
- Java 请求关闭或超时后，不能让 Codex 轮次在未被感知的情况下继续消耗资源。

## 首个集成项目：LoopAide

[LoopAide](https://github.com/rudy2steiner/loopaide) 将作为本 SDK 的第一个生产级使用方。它当前通过 `codex exec --json` 集成 Codex；本 SDK 将帮助它迁移到更丰富的 app-server 协议，同时保留自身与供应商无关的事件、持久化和 UI 模型。

LoopAide 会作为集成测试，而不是 SDK 的应用依赖：本 SDK 必须能够被任何 Java 应用独立使用。

## 路线图

### 阶段 0：协议基础

- [ ] 建立 Java 多模块构建。
- [ ] 引入版本化 app-server Schema 和脱敏 fixtures。
- [ ] 实现容错的消息 Envelope 解码。
- [ ] 添加模拟服务端和契约测试基础设施。

### 阶段 1：可用的 stdio 客户端

- [ ] 管理 `codex app-server` 子进程。
- [ ] 实现 `initialize` / `initialized`。
- [ ] 关联请求、响应、错误和超时。
- [ ] 启动、恢复、读取和列出线程。
- [ ] 启动、观察、中断和动态引导轮次。
- [ ] 传递核心消息、推理、命令、文件修改、MCP 和计划事件。

### 阶段 2：完整交互

- [ ] 处理命令和文件修改审批。
- [ ] 处理结构化用户输入请求。
- [ ] 暴露命令输出、Patch、计划、Token 用量和警告。
- [ ] 增加重连和进程失败恢复能力。
- [ ] 验证 LoopAide 迁移。

### 阶段 3：公开发布和上游对齐

- [ ] 向 Maven Central 发布签名构件。
- [ ] 发布 Javadocs、示例和 Codex 版本兼容矩阵。
- [ ] 使用真实集成证据向上游提交设计 Issue。
- [ ] 仅在 Codex 贡献政策允许并获得邀请后提交代码。
- [ ] 保持公共 API 可被未来官方 Java SDK 替换。

## 上游策略

本项目会有意识地保持与上游对齐的架构。OpenAI Codex 仓库目前只接受受邀的外部代码贡献，因此计划遵循以下流程：

1. 独立构建并验证本库。
2. 在真实 Java 应用中使用。
3. 发布协议 fixtures、兼容性结果和实现经验。
4. 提交代码前先向上游提出方案。
5. 仅在 Codex 维护者明确邀请后提交聚焦的 Pull Request。

最终结果可能是将 SDK 迁入 `openai/codex`、贡献共享一致性 fixtures、作为社区客户端写入官方文档，或者在相同应用边界后由官方实现替换本 SDK。

参见 [Codex 贡献政策](https://github.com/openai/codex/blob/main/docs/contributing.md)。

## 开发状态

目前尚未发布任何构件。构建命令、依赖坐标、支持的 Java 版本和贡献说明将在协议基础阶段补充。

计划以 Java 17 为最低版本，使 SDK 能够使用 record、sealed type 和现代并发 API，同时供 LoopAide 等 Java 21 应用使用。

## 参考资料

- [Codex app-server 文档和协议](https://github.com/openai/codex/tree/main/codex-rs/app-server)
- [Codex app-server 协议源码](https://github.com/openai/codex/tree/main/codex-rs/app-server-protocol)
- [官方 Codex Python SDK](https://github.com/openai/codex/tree/main/sdk/python)
- [官方 Codex TypeScript SDK](https://github.com/openai/codex/tree/main/sdk/typescript)
- [官方 OpenAI Java 库](https://github.com/openai/openai-java)

## 许可证

本项目使用 [Apache License 2.0](LICENSE) 许可证。
