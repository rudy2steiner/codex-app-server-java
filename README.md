# codex-app-server-java

[English](README.md) | [简体中文](README.zh-CN.md)

An independent, framework-neutral Java 17 client for the [Codex app-server](https://github.com/openai/codex/tree/main/codex-rs/app-server) protocol.

> [!IMPORTANT]
> The usable MVP is implemented. It is currently a local Maven snapshot (`0.1.0-SNAPSHOT`), not a Maven Central release.

`codex-app-server-java` starts a local `codex app-server` process, communicates with its JSONL-over-stdio protocol, and exposes Java APIs for threads, turns, interruption, and ordered protocol events. It preserves raw JSON and unknown notifications so newer Codex protocol additions do not automatically break an integration.

This is an independent community project. It is not an official OpenAI SDK and is not affiliated with or endorsed by OpenAI.

## MVP capabilities

- Java 17 multi-module Maven build with no Spring, Reactor, or UI dependency.
- `codex app-server --stdio` process lifecycle and an idempotent close operation.
- `initialize` / `initialized` handshake, correlated requests, protocol errors, timeouts, and process-exit failure propagation.
- Start and resume Codex threads; start and interrupt turns.
- Ordered callbacks for lifecycle, message, reasoning, command, file-change, MCP, plan, usage, warning, error, and unknown events.
- Tolerant JSON-RPC-like envelope decoding that retains each message's raw JSON and unknown fields.
- In-memory transport support for deterministic client tests.

The checked-in protocol fixture and compatibility baseline are **Codex CLI 0.146.0**. Runtime decoding is deliberately tolerant of newer fields and unknown notification methods, but newer CLI versions have not yet been compatibility-certified.

## Modules

| Module | Responsibility |
| --- | --- |
| `codex-app-server-protocol` | JSON-RPC-like envelopes, tolerant v2 event decoding, event kinds, and raw JSON preservation. |
| `codex-app-server-client` | Process transport, RPC routing, client lifecycle, thread/turn APIs, and diagnostics. |
| `codex-app-server-testing` | In-memory `CodexTransport` for deterministic SDK and downstream tests. |

## Requirements

- JDK **17** or newer for the SDK (compiled with `--release 17`); JDK **21** is required to build the LoopAide integration.
- Maven 3.9+.
- A locally installed and authenticated `codex` executable on `PATH` for real app-server use.
- Codex CLI **0.146.0** for the documented compatibility baseline.

## Build and install locally

Build and run the SDK tests from this repository:

```bash
cd codex-app-server-java
mvn verify
```

To use the snapshot from a sibling LoopAide checkout, run these commands from the `loop` repository directory:

```bash
cd ../codex-app-server-java
mvn install
cd ../loop
mvn verify
```

`mvn install` is required: LoopAide resolves `com.loopaide.codex` artifacts from the local Maven repository until the SDK is published.

## Quick start

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
        TurnInput.text("Summarize this project."),
        event -> System.out.println(event.method()));

    TurnResult result = turn.completion().join();
    System.out.println("Completed turn: " + result.turnId());
}
```

Use `client.resumeThread(threadId, options)` to continue an existing Codex thread. `turn.interrupt()` returns a `CompletableFuture<Void>` after sending `turn/interrupt`. `CodexClientOptions` lets an application select the `codex` executable, request timeout, diagnostic consumer, and an optional handler for server-initiated requests.

For deterministic tests, use `CodexClient.connect(transport, options)` with an implementation of `CodexTransport`; the testing module supplies an in-memory transport.

## Protocol behavior

The app-server wire format is JSON-RPC-like but omits the `"jsonrpc": "2.0"` field. It has four envelope shapes:

| Shape | Meaning |
| --- | --- |
| `id` + `method` | Request expecting a response |
| `id` + `result` | Successful response |
| `id` + `error` | Failed response |
| `method` without `id` | Notification |

Callbacks are dispatched in notification arrival order and never run on the stdout reader thread. The client registers a turn listener before it writes `turn/start`, so an early first delta is not lost. Every decoded event retains its sequence number, method, parsed IDs where available, parameters, and raw JSON.

## Live smoke test

The live test is intentionally disabled in normal builds. It creates a temporary Git repository, starts the installed `codex` executable, asks a harmless no-tools question, waits no longer than 90 seconds for completion, and closes the client and process.

```bash
CODEX_LIVE_TEST=true mvn -pl codex-app-server-client test
```

Use this only on a machine where Codex is installed and authenticated. Normal `mvn verify` reports this test as skipped.

## Current limitations

- The SDK is an MVP snapshot; there is no Maven Central publication or compatibility guarantee yet.
- Interactive approval and structured user-input flows are not implemented. The MVP sends `approvalPolicy: "never"`; applications should provide an explicit server-request handler if they need to observe or respond to server requests.
- WebSocket transport, steering, thread listing/reading, automatic reconnection, and automatic replay of a failed active turn are out of scope.
- The SDK does not define transcript storage, presentation, or application-specific persistence models.
- Raw protocol data may include prompts, paths, or command output. Treat it as sensitive when logging it.

## First integration: LoopAide

[LoopAide](https://github.com/rudy2steiner/loopaide) is the first production-style consumer. It remains an application-level integration: this SDK does not depend on LoopAide and can be used from any Java application.

## License

Licensed under the [Apache License 2.0](LICENSE).
