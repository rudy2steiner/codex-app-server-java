# LoopAide MVP Design

## Goal

Build the first usable release of `codex-app-server-java` and use it from LoopAide for Codex chat sessions. The MVP replaces the normal `codex exec --json` path with the richer `codex app-server` protocol while retaining the exec implementation as a startup fallback.

The first release optimizes for a real LoopAide integration. It deliberately defers interactive approval and structured user-input UI while keeping protocol extension points for both.

## Scope

The MVP supports:

- starting and stopping a local `codex app-server` process over JSONL stdio;
- the `initialize` request and `initialized` notification handshake;
- correlated client requests, responses, errors, and timeouts;
- starting and resuming threads;
- starting and interrupting turns;
- ordered thread, turn, item, message, reasoning, command, file-change, MCP, plan, usage, warning, and error notifications;
- preservation of unknown methods, fields, enum values, and raw JSON;
- deterministic fake-transport tests and fixtures based on Codex CLI 0.146.0;
- one shared app-server process per LoopAide daemon;
- one Codex thread per LoopAide conversation;
- an explicit fallback to the existing `codex exec --json` path when app-server cannot start or initialize.

The MVP does not implement approval dialogs, structured user-input dialogs, WebSocket transport, automatic reconnection of an active turn, Maven Central publication, or complete coverage of every experimental app-server method.

## Repository and package structure

The SDK remains an independent Maven repository with Java 17 as its baseline:

```text
codex-app-server-java
├── codex-app-server-protocol
├── codex-app-server-client
└── codex-app-server-testing
```

The Maven group is `com.loopaide.codex`. Public Java packages are rooted at `com.loopaide.codex.appserver`, with protocol payloads under `com.loopaide.codex.appserver.protocol.v2`. Internal implementation classes use `com.loopaide.codex.appserver.internal` and are not part of the compatibility contract.

The modules have these responsibilities:

- `codex-app-server-protocol` contains envelopes, identifiers, known v2 payloads, event types, raw-data preservation, and tolerant JSON decoding.
- `codex-app-server-client` contains process lifecycle, stdio transport, request routing, ordered dispatch, thread and turn APIs, timeouts, interruption, and server-request handling.
- `codex-app-server-testing` contains an in-memory transport, scripted fake server, fixture replay, and downstream contract-test helpers.

The SDK depends only on Java 17 and Jackson. It does not depend on Spring, Reactor, LoopAide, or a UI toolkit.

## Process and concurrency model

`CodexClient.start` launches one `codex app-server` child process and performs the initialization handshake before returning. A dedicated reader continuously parses stdout. Response correlation happens immediately on that reader path so a slow event consumer cannot block RPC completion.

Notifications receive a monotonically increasing local `sequence` at ingress. They are routed into a serial dispatch queue, which preserves their wire arrival order. Turn listeners are registered before `turn/start` is written, so fast initial notifications cannot be lost. User callbacks never run on the stdout reader thread.

LoopAide owns one client for the daemon lifetime. Each LoopAide Codex session owns a lightweight thread handle. When the app-server process exits, the SDK fails all pending requests and active turns with a process-exit exception. LoopAide may create a new client for the next message and resume the stored thread, but it never automatically replays a failed active turn because commands might otherwise execute twice.

## Protocol model

App-server messages are JSON-RPC-like envelopes without a `jsonrpc` field. The decoder distinguishes:

- request: `id` and `method`;
- successful response: `id` and `result`;
- failed response: `id` and `error`;
- notification: `method` without `id`.

Every decoded message retains its complete source `JsonNode`. Known notifications are represented by typed event records. Unknown notifications produce `UnknownEvent`, rather than being discarded or treated as fatal. Known payload decoders ignore additional fields. Unknown enum text is retained as text and is never silently mapped to a known value.

Every client event exposes `sequence`, `method`, optional `threadId`, `turnId`, and `itemId`, plus the raw payload. The first compatibility fixture set and checked-in generated schema metadata correspond to Codex CLI 0.146.0. Runtime decoding remains tolerant of newer protocol additions.

## Public API

The initial high-level API is synchronous for setup and uses `CompletableFuture` for turn completion:

```java
try (CodexClient client = CodexClient.start(options)) {
    CodexThread thread = client.startThread(threadOptions);
    // Or: client.resumeThread(threadId, threadOptions)

    TurnHandle turn = thread.startTurn(
        TurnInput.text("Inspect this repository"),
        event -> render(event));

    TurnResult result = turn.completion().join();
}
```

`TurnHandle.interrupt()` sends `turn/interrupt`. `CodexClient` also exposes a low-level request method and a server-request handler so later releases can add approval and structured-input behavior without replacing the transport.

The default unhandled server-request behavior is an explicit error response and a diagnostic event. LoopAide sets `approvalPolicy=never` in the MVP, matching its current automatic-execution behavior and minimizing server-initiated approval requests.

## Event semantics

The SDK preserves app-server ordering and does not reorder events by display category. Message and reasoning deltas are emitted as received. `item/completed` finalizes an existing item and does not create a second logical assistant message.

The SDK provides typed coverage for:

- thread and turn lifecycle;
- agent-message deltas;
- reasoning summary and reasoning-text deltas;
- item start and completion;
- command execution and command output;
- file changes;
- MCP tool calls;
- plan updates;
- token usage;
- warnings and errors;
- unknown notifications.

The complete item remains available on item lifecycle events so consumers can recover final content when a delta was missed or not emitted.

## LoopAide integration

LoopAide adds an app-server-backed Codex persistent session and a daemon-scoped client manager. Creating a new LoopAide conversation calls `thread/start`. Reopening a stored `codex:<threadId>` conversation calls `thread/resume` using the saved working directory, model, and mode.

The integration retains the original app-server notification JSON in LoopAide's append-only session journal. `CodexSubAgent` and `ChatDisplayParser` learn the app-server envelope and method names directly; app-server messages are not disguised as legacy exec events. The adapter maps typed protocol meaning onto the existing provider-neutral `WorkerTypes.StreamEvent` and `ChatDisplayEvent` models.

The native thread ID is bound to the pending LoopAide session as soon as thread creation completes. Existing session aliasing then keeps the URL's single `sessionId` parameter valid whether it contains the local session reference or the native worker ID.

For rendering, `itemId` is the stable identity for an assistant segment or tool card. Deltas update that identity; completion finalizes it. This prevents duplicate avatars and avoids displaying a completed message as a second response. Reasoning summary deltas map to LoopAide thinking events, while tool and plan events remain in their original relative order.

Stopping an active app-server turn sends `turn/interrupt`. Closing the LoopAide daemon closes the client and child process. If app-server startup or initialization fails, LoopAide records a warning and uses the existing one-shot exec path. Once a turn has started through app-server, failures are surfaced and are not silently retried through exec.

## Dependency strategy

During MVP development, LoopAide consumes:

```xml
<dependency>
  <groupId>com.loopaide.codex</groupId>
  <artifactId>codex-app-server-client</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Developers install the sibling SDK repository into their local Maven repository before building LoopAide. The integration does not use `systemPath`, copied SDK sources, or committed binary JARs. A later release publishes the same coordinates to Maven Central.

## Error handling

Malformed stdout lines produce protocol diagnostics containing the line, without terminating the process for an isolated malformed notification. Malformed responses, duplicate response IDs, process exit, write failure, initialization failure, and request timeout fail the affected request with a typed exception.

Closing a client is idempotent. It stops accepting new requests, fails pending operations, closes stdin, terminates the process with a bounded graceful wait, and then forcibly terminates it if necessary. Turn interruption and client closure cannot leave a Java completion future pending forever.

## Testing

SDK tests follow these layers:

1. Protocol decoder tests for all four envelope shapes, known events, unknown data, and malformed input.
2. In-memory transport tests for initialization, correlation, timeout, ordered dispatch, server requests, process failure, and close behavior.
3. Thread and turn tests for start, resume, deltas, completion, and interruption.
4. An opt-in live smoke test against an installed Codex CLI; it is not required for normal unit-test execution.

LoopAide tests replay app-server fixtures through the real adapter and assert exact display order, stable item identity, no duplicate assistant completion, native thread binding, history recovery, interruption, and exec fallback. Existing backend and web suites remain green.

## Delivery sequence

Implementation proceeds in four independently testable slices:

1. Maven modules, tolerant protocol envelopes, and fixtures.
2. Process transport, initialization, routing, ordered events, and thread/turn API.
3. LoopAide dependency, app-server session manager, protocol mapping, and fallback.
4. End-to-end fixture replay, live CLI smoke test, documentation, and compatibility notes.
