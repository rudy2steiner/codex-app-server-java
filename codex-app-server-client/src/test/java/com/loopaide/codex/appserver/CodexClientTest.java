package com.loopaide.codex.appserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopaide.codex.appserver.transport.CodexTransport;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexClientTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void initializesThenStartsThreadAndTurnWithStableProtocolParameters() throws Exception {
    FakeTransport transport = new FakeTransport();
    transport.onSend(message -> {
      JsonNode request = read(message);
      switch (request.path("method").asText()) {
        case "initialize" -> transport.receive("{\"id\":1,\"result\":{}}");
        case "thread/start" -> transport.receive("{\"id\":2,\"result\":{\"thread\":{\"id\":\"thread-1\"}}}");
        case "turn/start" -> {
          transport.receive("{\"id\":3,\"result\":{\"turn\":{\"id\":\"turn-1\"}}}");
          transport.receive("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\"}}");
        }
        default -> { }
      }
    });

    try (CodexClient client = CodexClient.connect(transport, CodexClientOptions.builder()
        .requestTimeout(Duration.ofSeconds(1))
        .build())) {
      CodexThread thread = client.startThread(ThreadOptions.builder()
          .cwd(Path.of("/workspace/project"))
          .model("gpt-5.3-codex")
          .build());
      TurnHandle turn = thread.startTurn(TurnInput.text("Inspect this repository"), event -> { });

      assertEquals("turn-1", turn.completion().join().turnId());
    }

    List<JsonNode> sent = transport.sentMessages().stream().map(this::read).toList();
    assertEquals(List.of("initialize", "initialized", "thread/start", "turn/start"),
        sent.stream().map(message -> message.path("method").asText()).toList());
    assertEquals("/workspace/project", sent.get(2).path("params").path("cwd").asText());
    assertEquals("gpt-5.3-codex", sent.get(2).path("params").path("model").asText());
    assertEquals("never", sent.get(2).path("params").path("approvalPolicy").asText());
    assertEquals("thread-1", sent.get(3).path("params").path("threadId").asText());
    assertEquals("text", sent.get(3).path("params").path("input").get(0).path("type").asText());
    assertEquals("Inspect this repository", sent.get(3).path("params").path("input").get(0)
        .path("text").asText());
  }

  @Test
  void deliversFirstTurnDeltaWhenItArrivesBeforeTheTurnStartResponse() throws Exception {
    FakeTransport transport = new FakeTransport();
    CountDownLatch deltaDelivered = new CountDownLatch(1);
    transport.onSend(message -> {
      JsonNode request = read(message);
      switch (request.path("method").asText()) {
        case "initialize" -> transport.receive("{\"id\":1,\"result\":{}}");
        case "thread/start" -> transport.receive("{\"id\":2,\"result\":{\"thread\":{\"id\":\"thread-1\"}}}");
        case "turn/start" -> {
          transport.receive("{\"method\":\"item/agentMessage/delta\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"itemId\":\"item-1\",\"delta\":\"Hello\"}}");
          await(deltaDelivered, "delta was not processed before turn/start response");
          transport.receive("{\"id\":3,\"result\":{\"turn\":{\"id\":\"turn-1\"}}}");
          transport.receive("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\"}}");
        }
        default -> { }
      }
    });
    List<String> callbackThreads = new CopyOnWriteArrayList<>();

    try (CodexClient client = CodexClient.connect(transport, CodexClientOptions.defaults())) {
      CodexThread thread = client.startThread(ThreadOptions.builder().cwd(Path.of("/workspace/project")).build());
      TurnHandle turn = thread.startTurn(TurnInput.text("hello"), event -> {
        if (event.method().equals("item/agentMessage/delta")) {
          callbackThreads.add(Thread.currentThread().getName());
          deltaDelivered.countDown();
        }
      });

      assertTrue(deltaDelivered.await(1, TimeUnit.SECONDS));
      assertEquals("turn-1", turn.completion().join().turnId());
    }

    assertEquals(List.of("codex-app-server-turn-callback"), callbackThreads);
    assertNotEquals("codex-app-server-reader", callbackThreads.get(0));
  }

  @Test
  void buffersThreadStartedAfterItIsProcessedBeforeThreadStartResponse() throws Exception {
    FakeTransport transport = new FakeTransport();
    transport.onSend(message -> {
      JsonNode request = read(message);
      switch (request.path("method").asText()) {
        case "initialize" -> transport.receive("{\"id\":1,\"result\":{}}");
        case "thread/start" -> {
          transport.receive("{\"method\":\"thread/started\",\"params\":{\"thread\":{\"id\":\"thread-1\"}}}");
          transport.receive("{\"id\":90,\"method\":\"test/barrier\",\"params\":{}}");
          awaitCondition(() -> transport.sentMessages().size() == 4,
              "dispatcher did not process early thread event before response");
          transport.receive("{\"id\":2,\"result\":{\"thread\":{\"id\":\"thread-1\"}}}");
        }
        default -> { }
      }
    });
    CountDownLatch delivered = new CountDownLatch(1);
    try (CodexClient client = CodexClient.connect(transport, CodexClientOptions.builder()
        .serverRequestHandler(request -> JSON.createObjectNode())
        .build())) {
      CodexThread thread = client.startThread(ThreadOptions.builder().build());
      thread.addEventListener(event -> {
        if (event.method().equals("thread/started")) {
          delivered.countDown();
        }
      });

      assertTrue(delivered.await(1, TimeUnit.SECONDS));
    }
  }

  @Test
  void closesTransportWhenInitializationFails() {
    FakeTransport transport = new FakeTransport();
    transport.onSend(message -> transport.receive(
        "{\"id\":1,\"error\":{\"code\":-32000,\"message\":\"initialize failed\"}}"));

    assertThrows(CompletionException.class,
        () -> CodexClient.connect(transport, CodexClientOptions.defaults()));

    assertEquals(1, transport.closeCount);
  }

  @Test
  void reportsWhetherTheSharedConnectionIsAlive() {
    FakeTransport transport = respondingTransport();
    try (CodexClient client = CodexClient.connect(transport, CodexClientOptions.defaults())) {
      assertTrue(client.isAlive());

      transport.fail(new CodexProcessException("process exited"));

      assertFalse(client.isAlive());
    }
  }

  @Test
  void tearsDownProcessWhenInitializationTimesOut(@TempDir Path tempDir) throws Exception {
    Path marker = tempDir.resolve("timeout-closed");
    Path executable = appServerScript(tempDir, "timeout-server", marker, "");

    assertThrows(CompletionException.class, () -> CodexClient.start(CodexClientOptions.builder()
        .codexExecutable(executable.toString())
        .requestTimeout(Duration.ofSeconds(2))
        .build()));

    assertProcessStopped(marker);
  }

  @Test
  void tearsDownProcessWhenInitializationReturnsAnError(@TempDir Path tempDir) throws Exception {
    Path marker = tempDir.resolve("error-closed");
    Path executable = appServerScript(tempDir, "error-server", marker,
        "printf '%s\\n' '{\"id\":1,\"error\":{\"code\":-32000,\"message\":\"initialize failed\"}}'");

    assertThrows(CompletionException.class, () -> CodexClient.start(CodexClientOptions.builder()
        .codexExecutable(executable.toString())
        .requestTimeout(Duration.ofSeconds(5))
        .build()));

    assertProcessStopped(marker);
  }

  @Test
  void completesTurnBeforeInvokingAThrowingTerminalListener() {
    FakeTransport transport = respondingTransport();
    try (CodexClient client = CodexClient.connect(transport, CodexClientOptions.defaults())) {
      CodexThread thread = client.startThread(ThreadOptions.builder().build());
      TurnHandle turn = thread.startTurn(TurnInput.text("hello"), event -> {
        throw new IllegalStateException("listener failed");
      });

      transport.receive("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\"}}");

      assertEquals("turn-1", turn.completion().orTimeout(1, TimeUnit.SECONDS).join().turnId());
    }
  }

  @Test
  void processExitFailsTurnWhileItsEventListenerIsBlocked() throws Exception {
    FakeTransport transport = respondingTransport();
    CountDownLatch callbackEntered = new CountDownLatch(1);
    CountDownLatch releaseCallback = new CountDownLatch(1);
    try (CodexClient client = CodexClient.connect(transport, CodexClientOptions.defaults())) {
      CodexThread thread = client.startThread(ThreadOptions.builder().build());
      TurnHandle turn = thread.startTurn(TurnInput.text("hello"), event -> {
        callbackEntered.countDown();
        await(releaseCallback, "blocked callback was not released");
      });
      transport.receive("{\"method\":\"item/agentMessage/delta\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"itemId\":\"item-1\",\"delta\":\"hello\"}}");
      assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));

      transport.fail(new CodexProcessException("process exited"));
      CompletionException failure = assertThrows(CompletionException.class,
          () -> turn.completion().orTimeout(300, TimeUnit.MILLISECONDS).join());

      assertInstanceOf(CodexProcessException.class, failure.getCause());
      releaseCallback.countDown();
    } finally {
      releaseCallback.countDown();
    }
  }

  @Test
  void deliversIngressedTurnCompletionBeforeProcessExitFailure() throws Exception {
    FakeTransport transport = respondingTransport();
    CountDownLatch handlerEntered = new CountDownLatch(1);
    CountDownLatch releaseHandler = new CountDownLatch(1);
    CountDownLatch completionSettled = new CountDownLatch(1);
    try (CodexClient client = CodexClient.connect(transport, CodexClientOptions.builder()
        .serverRequestHandler(request -> {
          handlerEntered.countDown();
          await(releaseHandler, "blocked handler was not released");
          return JSON.createObjectNode();
        })
        .build())) {
      CodexThread thread = client.startThread(ThreadOptions.builder().build());
      TurnHandle turn = thread.startTurn(TurnInput.text("hello"), event -> { });
      turn.completion().whenComplete((result, failure) -> completionSettled.countDown());

      transport.receive("{\"id\":77,\"method\":\"test/block\",\"params\":{}}");
      assertTrue(handlerEntered.await(1, TimeUnit.SECONDS));
      transport.receive("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\"}}");
      transport.fail(new CodexProcessException("process exited"));

      assertFalse(completionSettled.await(300, TimeUnit.MILLISECONDS),
          "process-exit failure overtook an already-ingressed terminal event");
      releaseHandler.countDown();
      assertEquals("turn-1", turn.completion().orTimeout(1, TimeUnit.SECONDS).join().turnId());
    } finally {
      releaseHandler.countDown();
    }
  }

  @Test
  void resumesAndInterruptsUsingExactThreadAndTurnIds() {
    FakeTransport transport = respondingTransport();
    try (CodexClient client = CodexClient.connect(transport, CodexClientOptions.defaults())) {
      CodexThread thread = client.resumeThread("thread-1", ThreadOptions.builder()
          .cwd(Path.of("/workspace/resumed"))
          .model("gpt-5.3-codex")
          .build());
      TurnHandle turn = thread.startTurn(TurnInput.text("continue"), event -> { });
      turn.interrupt().join();
    }

    List<JsonNode> sent = transport.sentMessages().stream().map(this::read).toList();
    assertEquals(List.of("initialize", "initialized", "thread/resume", "turn/start", "turn/interrupt"),
        sent.stream().map(message -> message.path("method").asText()).toList());
    assertEquals("thread-1", sent.get(2).path("params").path("threadId").asText());
    assertEquals("/workspace/resumed", sent.get(2).path("params").path("cwd").asText());
    assertEquals("thread-1", sent.get(4).path("params").path("threadId").asText());
    assertEquals("turn-1", sent.get(4).path("params").path("turnId").asText());
  }

  @Test
  void routesInterleavedEventsOnlyToTheirOwningThreadsInWireOrder() throws Exception {
    FakeTransport transport = new FakeTransport();
    transport.onSend(message -> {
      JsonNode request = read(message);
      long id = request.path("id").asLong();
      switch (request.path("method").asText()) {
        case "initialize" -> transport.receive("{\"id\":" + id + ",\"result\":{}}");
        case "thread/start" -> transport.receive("{\"id\":" + id
            + ",\"result\":{\"thread\":{\"id\":\"thread-" + id + "\"}}}");
        case "turn/start" -> transport.receive("{\"id\":" + id
            + ",\"result\":{\"turn\":{\"id\":\"turn-" + id + "\"}}}");
        default -> { }
      }
    });
    List<String> first = new CopyOnWriteArrayList<>();
    List<String> second = new CopyOnWriteArrayList<>();
    CountDownLatch delivered = new CountDownLatch(4);
    try (CodexClient client = CodexClient.connect(transport, CodexClientOptions.defaults())) {
      CodexThread firstThread = client.startThread(ThreadOptions.builder().build());
      CodexThread secondThread = client.startThread(ThreadOptions.builder().build());
      firstThread.addEventListener(event -> { first.add(event.params().path("delta").asText()); delivered.countDown(); });
      secondThread.addEventListener(event -> { second.add(event.params().path("delta").asText()); delivered.countDown(); });

      transport.receive(delta("thread-2", "a"));
      transport.receive(delta("thread-3", "x"));
      transport.receive(delta("thread-2", "b"));
      transport.receive(delta("thread-3", "y"));

      assertTrue(delivered.await(1, TimeUnit.SECONDS));
      assertEquals(List.of("a", "b"), first);
      assertEquals(List.of("x", "y"), second);
    }
  }

  @Test
  void reportsUnhandledServerRequestAndStillRepliesMethodNotFound() {
    FakeTransport transport = respondingTransport();
    List<CodexDiagnostic> diagnostics = new CopyOnWriteArrayList<>();
    try (CodexClient client = CodexClient.connect(transport, CodexClientOptions.builder()
        .diagnosticConsumer(diagnostics::add)
        .build())) {
      transport.receive("{\"id\":77,\"method\":\"item/approval\",\"params\":{}}");

      awaitCondition(() -> diagnostics.size() == 1 && transport.sentMessages().size() == 3,
          "unhandled request diagnostic/response was not produced");
      assertEquals(CodexDiagnostic.Kind.UNHANDLED_SERVER_REQUEST, diagnostics.get(0).kind());
      assertEquals(77, diagnostics.get(0).raw().path("id").asInt());
      JsonNode reply = read(transport.sentMessages().get(2));
      assertEquals(77, reply.path("id").asInt());
      assertEquals(-32601, reply.path("error").path("code").asLong());
    }
  }

  @Test
  void reportsThrowingServerRequestHandlerAndRepliesInternalError() {
    FakeTransport transport = respondingTransport();
    List<CodexDiagnostic> diagnostics = new CopyOnWriteArrayList<>();
    try (CodexClient client = CodexClient.connect(transport, CodexClientOptions.builder()
        .serverRequestHandler(request -> { throw new IllegalStateException("handler failed"); })
        .diagnosticConsumer(diagnostics::add)
        .build())) {
      transport.receive("{\"id\":78,\"method\":\"item/approval\",\"params\":{}}");

      awaitCondition(() -> diagnostics.size() == 1 && transport.sentMessages().size() == 3,
          "handler failure diagnostic/response was not produced");
      assertEquals(CodexDiagnostic.Kind.SERVER_REQUEST_HANDLER_FAILURE, diagnostics.get(0).kind());
      assertInstanceOf(IllegalStateException.class, diagnostics.get(0).cause());
      assertEquals(-32603, read(transport.sentMessages().get(2)).path("error").path("code").asLong());
    }
  }

  @Test
  void reportsMalformedAndDuplicateResponses() {
    FakeTransport transport = respondingTransport();
    List<CodexDiagnostic> diagnostics = new CopyOnWriteArrayList<>();
    try (CodexClient client = CodexClient.connect(transport, CodexClientOptions.builder()
        .diagnosticConsumer(diagnostics::add)
        .build())) {
      transport.receive("not-json");
      transport.receive("{\"id\":1,\"result\":{}}");

      awaitCondition(() -> diagnostics.size() == 2, "protocol diagnostics were not delivered");
      assertEquals(List.of(CodexDiagnostic.Kind.MALFORMED_MESSAGE, CodexDiagnostic.Kind.UNEXPECTED_RESPONSE),
          diagnostics.stream().map(CodexDiagnostic::kind).toList());
    }
  }

  private static String delta(String threadId, String text) {
    return "{\"method\":\"item/agentMessage/delta\",\"params\":{\"threadId\":\"" + threadId
        + "\",\"turnId\":\"turn\",\"itemId\":\"item\",\"delta\":\"" + text + "\"}}";
  }

  private static Path appServerScript(Path directory, String name, Path marker, String response) throws Exception {
    Path script = directory.resolve(name);
    String source = "#!/bin/sh\n"
        + "printf '%s' $$ > '" + marker.toString().replace("'", "'\\''") + "'\n"
        + "IFS= read -r request\n"
        + response + "\n"
        + "while IFS= read -r request; do :; done\n";
    Files.writeString(script, source, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    return script;
  }

  private static void assertProcessStopped(Path pidFile) throws Exception {
    awaitCondition(() -> Files.exists(pidFile), "app-server pid was not recorded");
    long pid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8));
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
        "app-server process remained alive after initialization failure");
  }

  private static void await(CountDownLatch latch, String message) {
    try {
      if (!latch.await(1, TimeUnit.SECONDS)) {
        throw new AssertionError(message);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  private static void awaitCondition(java.util.function.BooleanSupplier condition, String message) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertTrue(condition.getAsBoolean(), message);
  }

  private FakeTransport respondingTransport() {
    FakeTransport transport = new FakeTransport();
    transport.onSend(message -> {
      JsonNode request = read(message);
      long id = request.path("id").asLong();
      switch (request.path("method").asText()) {
        case "initialize", "turn/interrupt" -> transport.receive("{\"id\":" + id + ",\"result\":{}}");
        case "thread/start", "thread/resume" -> transport.receive(
            "{\"id\":" + id + ",\"result\":{\"thread\":{\"id\":\"thread-1\"}}}");
        case "turn/start" -> transport.receive(
            "{\"id\":" + id + ",\"result\":{\"turn\":{\"id\":\"turn-1\"}}}");
        default -> { }
      }
    });
    return transport;
  }

  private JsonNode read(String source) {
    try {
      return JSON.readTree(source);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private static final class FakeTransport implements CodexTransport {
    private final List<String> sentMessages = new CopyOnWriteArrayList<>();
    private volatile Listener listener;
    private volatile Consumer<String> onSend = ignored -> { };
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private int closeCount;

    @Override
    public void start(Listener listener) {
      this.listener = listener;
    }

    @Override
    public void send(String message) {
      sentMessages.add(message);
      onSend.accept(message);
    }

    @Override
    public boolean isAlive() {
      return alive.get();
    }

    @Override
    public void close() {
      if (alive.compareAndSet(true, false)) {
        closeCount++;
        listener.onClosed(null);
      }
    }

    void receive(String message) {
      listener.onMessage(message);
    }

    void fail(Throwable failure) {
      alive.set(false);
      listener.onClosed(failure);
    }

    List<String> sentMessages() {
      return new ArrayList<>(sentMessages);
    }

    void onSend(Consumer<String> callback) {
      this.onSend = callback;
    }
  }
}
