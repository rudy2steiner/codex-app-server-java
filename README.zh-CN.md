# codex-app-server-java

[English](README.md) | [简体中文](README.zh-CN.md)

一个面向 [Codex app-server](https://github.com/openai/codex/tree/main/codex-rs/app-server) 协议、独立且不绑定框架的 Java 17 客户端。

> [!IMPORTANT]
> 可用 MVP 已实现。目前仅以本地 Maven Snapshot（`0.1.0-SNAPSHOT`）提供，尚未发布到 Maven Central。

`codex-app-server-java` 启动本地 `codex app-server` 进程，通过 JSONL/stdio 协议通信，并提供线程、轮次、中断和有序协议事件的 Java API。它会保留原始 JSON 与未知通知，因此 Codex 协议新增字段或方法不会自动破坏集成。

这是一个独立的社区项目，不是 OpenAI 官方 SDK，也不隶属于 OpenAI 或受其背书。

## MVP 已提供的能力

- 基于 Java 17 的多模块 Maven 构建；不依赖 Spring、Reactor 或 UI 框架。
- `codex app-server --stdio` 子进程生命周期管理，以及幂等关闭。
- `initialize` / `initialized` 握手、请求关联、协议错误、超时和进程退出失败传播。
- 创建和恢复 Codex 线程；创建和中断轮次。
- 按到达顺序回调生命周期、消息、推理、命令、文件修改、MCP、计划、用量、警告、错误和未知事件。
- 容错解析类 JSON-RPC Envelope，保留每条消息的原始 JSON 和未知字段。
- 面向确定性客户端测试的内存传输实现。

仓库中提交的协议 fixture 与兼容性基线为 **Codex CLI 0.146.0**。运行时解码会容忍新增字段和未知通知方法，但尚未对更高版本 CLI 完成兼容性认证。

## 模块

| 模块 | 职责 |
| --- | --- |
| `codex-app-server-protocol` | 类 JSON-RPC Envelope、容错 v2 事件解码、事件类型与原始 JSON 保留。 |
| `codex-app-server-client` | 进程传输、RPC 路由、客户端生命周期、线程/轮次 API 与诊断。 |
| `codex-app-server-testing` | 面向确定性 SDK 与下游测试的内存 `CodexTransport`。 |

## 环境要求

- SDK 需要 JDK **17** 或更新版本（使用 `--release 17` 编译）；构建 LoopAide 集成需要 JDK **21**。
- Maven 3.9+。
- 使用真实 app-server 时，需要 `PATH` 中存在已安装且已登录的 `codex` 可执行文件。
- 文档中的兼容性基线为 Codex CLI **0.146.0**。

## 本地构建与安装

在本仓库中构建并执行 SDK 测试：

```bash
cd codex-app-server-java
mvn verify
```

若要在同级目录的 LoopAide 检出中使用该 Snapshot，请从 `loop` 仓库目录执行：

```bash
cd ../codex-app-server-java
mvn install
cd ../loop
mvn verify
```

必须执行 `mvn install`：在 SDK 发布前，LoopAide 通过本地 Maven 仓库解析 `com.loopaide.codex` 构件。

## 快速开始

```java
import com.loopaide.codex.appserver.CodexClient;
import com.loopaide.codex.appserver.CodexThread;
import com.loopaide.codex.appserver.ThreadOptions;
import com.loopaide.codex.appserver.TurnHandle;
import com.loopaide.codex.appserver.TurnInput;
import com.loopaide.codex.appserver.TurnResult;
import java.nio.file.Path;

try (CodexClient client = CodexClient.start()) {
    CodexThread thread = client.startThread(
        ThreadOptions.builder()
            .cwd(Path.of("/workspace/project"))
            .build());

    TurnHandle turn = thread.startTurn(
        TurnInput.text("请总结这个项目。"),
        event -> System.out.println(event.method()));

    TurnResult result = turn.completion().join();
    System.out.println("已完成轮次：" + result.turnId());
}
```

使用 `client.resumeThread(threadId, options)` 可以继续已有 Codex 线程。`turn.interrupt()` 会发送 `turn/interrupt`，并返回 `CompletableFuture<Void>`。`CodexClientOptions` 可配置 `codex` 可执行文件、请求超时、诊断消费者，以及可选的服务端主动请求处理器。

确定性测试可通过 `CodexClient.connect(transport, options)` 注入 `CodexTransport` 实现；testing 模块提供了内存传输。

## 协议行为

app-server 的线上格式类似 JSON-RPC，但不包含 `"jsonrpc": "2.0"` 字段。消息有四种 Envelope：

| 形态 | 含义 |
| --- | --- |
| `id` + `method` | 需要响应的请求 |
| `id` + `result` | 成功响应 |
| `id` + `error` | 失败响应 |
| 只有 `method`、没有 `id` | 通知 |

回调按通知到达顺序派发，绝不会在 stdout 读取线程上执行。客户端在写入 `turn/start` 前注册轮次监听器，因此不会丢失过早到达的首个增量事件。每个解码后的事件都会保留序号、方法、可识别的 ID、参数和原始 JSON。

## Live smoke test

真实环境测试默认禁用。它会创建临时 Git 仓库，启动已安装的 `codex`，发送一个不需要工具的无害问题，最多等待 90 秒完成，并关闭客户端及子进程。

```bash
CODEX_LIVE_TEST=true mvn -pl codex-app-server-client test
```

请仅在已安装且已登录 Codex 的机器上运行。普通 `mvn verify` 会将该测试报告为 skipped。

## 当前限制

- SDK 仍是 MVP Snapshot，尚未发布 Maven Central，也没有稳定兼容性承诺。
- 尚未实现交互式审批和结构化用户输入。MVP 固定发送 `approvalPolicy: "never"`；需要观察或响应服务端请求的应用应显式提供 server-request handler。
- WebSocket 传输、steering、线程读取/列表、自动重连以及失败中活动轮次的自动重放均不在当前范围内。
- SDK 不定义对话记录、展示层或应用特有的持久化模型。
- 原始协议数据可能包含提示词、路径或命令输出；记录日志时应视为敏感数据处理。

## 首个集成：LoopAide

[LoopAide](https://github.com/rudy2steiner/loopaide) 是第一个生产级风格的使用方。它仅是应用层集成：SDK 不依赖 LoopAide，任何 Java 应用都可以使用。

## 许可证

采用 [Apache License 2.0](LICENSE)。
