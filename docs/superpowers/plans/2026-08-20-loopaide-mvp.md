# LoopAide Codex App-Server MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a Java 17 Codex app-server SDK and make it the preferred Codex chat transport in LoopAide, with the existing exec transport as startup fallback.

**Architecture:** A tolerant protocol module decodes JSON-RPC-like envelopes and ordered Codex events. A framework-neutral client module owns stdio process transport, request correlation, and thread/turn handles. LoopAide owns one client manager, adapts raw ordered notifications to its existing transcript/display model, and resumes native thread IDs.

**Tech Stack:** Java 17 SDK, Java 21 LoopAide, Maven, Jackson, JUnit 5, AssertJ, Spring Boot 3.4.5.

**Spec:** `docs/superpowers/specs/2026-08-20-loopaide-mvp-design.md`

## Global Constraints

- Maven groupId is `com.loopaide.codex`.
- Public SDK packages start with `com.loopaide.codex.appserver`; wire payloads live under `protocol.v2`.
- SDK source and bytecode target Java 17 and depend only on JDK and Jackson at runtime.
- Preserve raw JSON and unknown protocol data; never fail solely because a known payload has extra fields.
- Preserve notification arrival order and never invoke application callbacks on the stdout reader thread.
- LoopAide stores the original app-server notification JSON and keeps `sessionId` as its only conversation URL parameter.
- A turn that started through app-server is never automatically replayed through exec.
- Existing uncommitted LoopAide work is user-owned and must be preserved.
- All commits use `rudy2steiner <rudy_steiner@163.com>`.

---

### Task 1: Maven foundation and tolerant protocol envelopes

**Files:**
- Create: `pom.xml`
- Create: `codex-app-server-protocol/pom.xml`
- Create: `codex-app-server-client/pom.xml`
- Create: `codex-app-server-testing/pom.xml`
- Create: `codex-app-server-protocol/src/main/java/com/loopaide/codex/appserver/protocol/v2/RpcMessage.java`
- Create: `codex-app-server-protocol/src/main/java/com/loopaide/codex/appserver/protocol/v2/RpcRequest.java`
- Create: `codex-app-server-protocol/src/main/java/com/loopaide/codex/appserver/protocol/v2/RpcSuccess.java`
- Create: `codex-app-server-protocol/src/main/java/com/loopaide/codex/appserver/protocol/v2/RpcFailure.java`
- Create: `codex-app-server-protocol/src/main/java/com/loopaide/codex/appserver/protocol/v2/RpcNotification.java`
- Create: `codex-app-server-protocol/src/main/java/com/loopaide/codex/appserver/protocol/v2/RpcError.java`
- Create: `codex-app-server-protocol/src/main/java/com/loopaide/codex/appserver/protocol/v2/RpcCodec.java`
- Test: `codex-app-server-protocol/src/test/java/com/loopaide/codex/appserver/protocol/v2/RpcCodecTest.java`

**Interfaces:**
- Produces: `RpcMessage RpcCodec.decode(String line)` and `String RpcCodec.encode(JsonNode message)`.
- Produces: four immutable envelope variants, each exposing the complete raw `JsonNode`.

- [ ] **Step 1: Write failing envelope tests**

Cover numeric and string IDs, requests, success responses, error responses, notifications, missing method, ambiguous response shape, and extra fields. A representative assertion is:

```java
RpcMessage decoded = codec.decode("{\"method\":\"turn/started\",\"params\":{\"x\":1},\"future\":true}");
RpcNotification notification = assertInstanceOf(RpcNotification.class, decoded);
assertThat(notification.method()).isEqualTo("turn/started");
assertThat(notification.raw().path("future").asBoolean()).isTrue();
```

- [ ] **Step 2: Run `mvn -pl codex-app-server-protocol test` and verify compilation fails because the protocol types do not exist.**
- [ ] **Step 3: Add the parent/module POMs and minimal envelope implementation.**
- [ ] **Step 4: Run `mvn -pl codex-app-server-protocol test` and verify all protocol tests pass.**
- [ ] **Step 5: Commit with `feat: add tolerant app-server protocol envelopes`.**

### Task 2: Ordered Codex event decoding

**Files:**
- Create: `codex-app-server-protocol/src/main/java/com/loopaide/codex/appserver/protocol/v2/CodexEvent.java`
- Create: `codex-app-server-protocol/src/main/java/com/loopaide/codex/appserver/protocol/v2/CodexEventKind.java`
- Create: `codex-app-server-protocol/src/main/java/com/loopaide/codex/appserver/protocol/v2/KnownCodexEvent.java`
- Create: `codex-app-server-protocol/src/main/java/com/loopaide/codex/appserver/protocol/v2/UnknownCodexEvent.java`
- Create: `codex-app-server-protocol/src/main/java/com/loopaide/codex/appserver/protocol/v2/CodexEventDecoder.java`
- Create: `codex-app-server-protocol/src/test/resources/protocol/codex-0.146.0-turn.jsonl`
- Test: `codex-app-server-protocol/src/test/java/com/loopaide/codex/appserver/protocol/v2/CodexEventDecoderTest.java`

**Interfaces:**
- Consumes: `RpcNotification` from Task 1.
- Produces: `CodexEvent decode(long sequence, RpcNotification notification)`.
- `CodexEvent` exposes `sequence()`, `method()`, optional text IDs, `params()`, and `raw()`.

- [ ] **Step 1: Write failing tests for lifecycle, message delta, reasoning delta, command, file change, MCP, plan, usage, warning, error, and unknown methods.**
- [ ] **Step 2: Run the focused test and verify it fails because the decoder is missing.**
- [ ] **Step 3: Implement a method-to-kind table with defensive ID extraction from params and nested item/turn/thread objects.**
- [ ] **Step 4: Replay the fixture and assert sequences remain exactly `1..N` and unknown fields remain in `raw()`.**
- [ ] **Step 5: Run the protocol module tests and commit with `feat: decode ordered Codex events`.**

### Task 3: Transport, RPC routing, and fake server

**Files:**
- Create: `codex-app-server-client/src/main/java/com/loopaide/codex/appserver/transport/CodexTransport.java`
- Create: `codex-app-server-client/src/main/java/com/loopaide/codex/appserver/transport/ProcessCodexTransport.java`
- Create: `codex-app-server-client/src/main/java/com/loopaide/codex/appserver/internal/RpcConnection.java`
- Create: `codex-app-server-client/src/main/java/com/loopaide/codex/appserver/CodexException.java`
- Create: `codex-app-server-client/src/main/java/com/loopaide/codex/appserver/CodexProtocolException.java`
- Create: `codex-app-server-client/src/main/java/com/loopaide/codex/appserver/CodexProcessException.java`
- Create: `codex-app-server-testing/src/main/java/com/loopaide/codex/appserver/testing/InMemoryCodexTransport.java`
- Test: `codex-app-server-client/src/test/java/com/loopaide/codex/appserver/internal/RpcConnectionTest.java`
- Test: `codex-app-server-client/src/test/java/com/loopaide/codex/appserver/transport/ProcessCodexTransportTest.java`

**Interfaces:**
- Produces: `CodexTransport.start(Listener)`, `send(String)`, `isAlive()`, and idempotent `close()`.
- Produces: `CompletableFuture<JsonNode> RpcConnection.request(String method, JsonNode params, Duration timeout)` and `notify(String method, JsonNode params)`.

- [ ] **Step 1: Write failing in-memory tests for correlation, timeout, server error, notification order, unhandled server request, transport failure, and idempotent close.**
- [ ] **Step 2: Run client tests and verify the missing implementation failure.**
- [ ] **Step 3: Implement request IDs with `AtomicLong`, a concurrent pending map, scheduled timeouts, and a single-thread event dispatcher.**
- [ ] **Step 4: Implement process transport using `ProcessBuilder(codex, app-server, --stdio)` with separate stdout and stderr readers; stderr is diagnostic only.**
- [ ] **Step 5: Run client/testing module tests and commit with `feat: add app-server transport and RPC routing`.**

### Task 4: High-level client, thread, and turn lifecycle

**Files:**
- Create: `codex-app-server-client/src/main/java/com/loopaide/codex/appserver/CodexClient.java`
- Create: `codex-app-server-client/src/main/java/com/loopaide/codex/appserver/CodexClientOptions.java`
- Create: `codex-app-server-client/src/main/java/com/loopaide/codex/appserver/CodexThread.java`
- Create: `codex-app-server-client/src/main/java/com/loopaide/codex/appserver/ThreadOptions.java`
- Create: `codex-app-server-client/src/main/java/com/loopaide/codex/appserver/TurnInput.java`
- Create: `codex-app-server-client/src/main/java/com/loopaide/codex/appserver/TurnHandle.java`
- Create: `codex-app-server-client/src/main/java/com/loopaide/codex/appserver/TurnResult.java`
- Create: `codex-app-server-client/src/main/java/com/loopaide/codex/appserver/ServerRequestHandler.java`
- Test: `codex-app-server-client/src/test/java/com/loopaide/codex/appserver/CodexClientTest.java`

**Interfaces:**
- Produces the API shown in the design spec.
- `startThread` sends `thread/start`; `resumeThread` sends `thread/resume`; `startTurn` installs its listener before sending `turn/start`; `interrupt` sends `turn/interrupt`.

- [ ] **Step 1: Write a failing scripted handshake test asserting `initialize`, then `initialized`, then thread and turn calls with `cwd`, model, `approvalPolicy: never`, and text input.**
- [ ] **Step 2: Write a failing race test where the fake server emits the first delta before returning the `turn/start` response.**
- [ ] **Step 3: Implement the minimal client API, per-turn listener registration, completion, interruption, and process-exit failure.**
- [ ] **Step 4: Run all SDK tests plus `mvn verify`; verify callback thread names differ from the transport reader.**
- [ ] **Step 5: Commit with `feat: add Codex thread and turn client API`.**

### Task 5: LoopAide protocol rendering

**Files:**
- Modify: `../loop/src/main/java/com/loopaide/workers/CodexSubAgent.java`
- Modify: `../loop/src/main/java/com/loopaide/workers/ChatDisplayParser.java`
- Modify: `../loop/src/main/java/com/loopaide/workers/AssistantStream.java`
- Test: `../loop/src/test/java/com/loopaide/workers/ChatDisplayParserTest.java`
- Test: `../loop/src/test/java/com/loopaide/workers/ProtocolFixtureTest.java`
- Create: `../loop/src/test/resources/protocol-fixtures/codex-app-server-turn.ndjson`

**Interfaces:**
- Consumes raw app-server notification JSON.
- Produces the existing `WorkerTypes.StreamEvent` and `ChatDisplayEvent` without changing web API shapes.

- [ ] **Step 1: Add failing fixture tests for exact message/Think/tool/plan ordering and stable item IDs.**
- [ ] **Step 2: Add a failing regression asserting `item/agentMessage/delta` followed by `item/completed` produces one assistant segment, not two avatars.**
- [ ] **Step 3: Implement direct method-based parsing; use `itemId` as `callId`/segment identity and mark completion without duplicating accumulated delta text.**
- [ ] **Step 4: Run focused worker tests and the web protocol replay tests.**
- [ ] **Step 5: Commit with `feat: render Codex app-server events in order`.**

### Task 6: LoopAide app-server session and exec fallback

**Files:**
- Modify: `../loop/pom.xml`
- Modify: `../loop/src/main/java/com/loopaide/workers/SubAgent.java`
- Modify: `../loop/src/main/java/com/loopaide/workers/WorkerTypes.java`
- Modify: `../loop/src/main/java/com/loopaide/workers/WorkersConfig.java`
- Modify: `../loop/src/main/java/com/loopaide/session/SessionChatRunner.java`
- Create: `../loop/src/main/java/com/loopaide/workers/CodexClientManager.java`
- Create: `../loop/src/main/java/com/loopaide/workers/CodexAppServerSession.java`
- Test: `../loop/src/test/java/com/loopaide/workers/CodexAppServerSessionTest.java`
- Modify: `../loop/src/test/java/com/loopaide/session/SessionChatRunnerTest.java`

**Interfaces:**
- `PersistentSessionRequest` gains optional `nativeSessionId` and `mode` while preserving its existing convenience constructor.
- `CodexSubAgent.createPersistentSession` creates an app-server session through the daemon-scoped manager.
- Startup/handshake failure returns an exec-backed persistent session; an active app-server turn never falls back.

- [ ] **Step 1: Write failing tests for start, resume, native ID binding, raw notification queueing, interrupt, manager restart after process exit, and startup-only fallback.**
- [ ] **Step 2: Run the focused LoopAide tests and verify failures are caused by the missing session integration.**
- [ ] **Step 3: Add the snapshot dependency and implement the manager/session adapter without overwriting existing dirty-worktree behavior.**
- [ ] **Step 4: Install the SDK with `mvn install`, run LoopAide backend tests, and run `npm test -- --run` in `web`.**
- [ ] **Step 5: Commit only the relevant LoopAide paths with `feat: use Codex app-server sessions`.**

### Task 7: Live smoke test and documentation

**Files:**
- Modify: `README.md`
- Modify: `README.zh-CN.md`
- Create: `codex-app-server-client/src/test/java/com/loopaide/codex/appserver/CodexLiveSmokeTest.java`
- Modify: `../loop/docs/sdk.md`

**Interfaces:**
- The live test runs only when `CODEX_LIVE_TEST=true`; normal tests skip it.
- Documentation gives the exact sibling-repository install/build commands and the Codex 0.146.0 compatibility baseline.

- [ ] **Step 1: Add the disabled-by-default live test and verify normal `mvn verify` reports it skipped.**
- [ ] **Step 2: Run the live test against the installed `codex` binary with a harmless prompt in a temporary Git repository.**
- [ ] **Step 3: Run both complete test suites, `git diff --check`, and inspect both repository diffs for unrelated files.**
- [ ] **Step 4: Update English and Chinese documentation with implemented APIs rather than planned examples.**
- [ ] **Step 5: Commit SDK docs with `docs: document the LoopAide MVP` and push both repositories after final review.**
