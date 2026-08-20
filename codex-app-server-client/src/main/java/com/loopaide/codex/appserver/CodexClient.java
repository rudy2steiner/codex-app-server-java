package com.loopaide.codex.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopaide.codex.appserver.internal.RpcConnection;
import com.loopaide.codex.appserver.protocol.v2.CodexEvent;
import com.loopaide.codex.appserver.protocol.v2.CodexEventDecoder;
import com.loopaide.codex.appserver.protocol.v2.RpcNotification;
import com.loopaide.codex.appserver.transport.CodexTransport;
import com.loopaide.codex.appserver.transport.ProcessCodexTransport;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** High-level lifecycle API for one shared Codex app-server process. */
public final class CodexClient implements AutoCloseable {
  private final CodexClientOptions options;
  private final RpcConnection connection;
  private final CodexEventDecoder eventDecoder = new CodexEventDecoder();
  private final AtomicLong nextEventSequence = new AtomicLong();
  private final ConcurrentMap<String, ThreadState> threadStates = new ConcurrentHashMap<>();

  private CodexClient(CodexTransport transport, CodexClientOptions options) {
    this.options = Objects.requireNonNull(options, "options");
    this.connection = new RpcConnection(Objects.requireNonNull(transport, "transport"), this::onNotification,
        options.serverRequestHandler(), this::onConnectionClosed, options.diagnosticConsumer());
    try {
      initialize();
    } catch (RuntimeException | Error failure) {
      try {
        connection.close();
      } catch (RuntimeException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  public static CodexClient start() {
    return start(CodexClientOptions.defaults());
  }

  public static CodexClient start(CodexClientOptions options) {
    CodexClientOptions resolvedOptions = Objects.requireNonNull(options, "options");
    return new CodexClient(new ProcessCodexTransport(resolvedOptions.codexExecutable()), resolvedOptions);
  }

  /** Connects a supplied transport; primarily useful for deterministic integration tests. */
  public static CodexClient connect(CodexTransport transport, CodexClientOptions options) {
    return new CodexClient(transport, options);
  }

  public CodexThread startThread(ThreadOptions options) {
    return createThread("thread/start", threadParams(options, null));
  }

  public CodexThread resumeThread(String threadId, ThreadOptions options) {
    Objects.requireNonNull(threadId, "threadId");
    return createThread("thread/resume", threadParams(options, threadId));
  }

  /** Sends a lower-level request using this client's configured timeout. */
  public CompletableFuture<JsonNode> request(String method, JsonNode params) {
    return connection.request(method, params, options.requestTimeout());
  }

  /** Returns whether the shared app-server connection can still accept work. */
  public boolean isAlive() {
    return connection.isAlive();
  }

  @Override
  public void close() {
    CodexProcessException failure = new CodexProcessException("Codex client was closed");
    onConnectionClosed(failure);
    threadStates.forEach((threadId, state) -> state.closeCallbacks());
    connection.close();
  }

  TurnHandle startTurn(CodexThread thread, TurnInput input, Consumer<CodexEvent> listener) {
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(input, "input");
    TurnHandle handle = new TurnHandle(this, thread.id(), listener);
    ThreadState state = threadStates.computeIfAbsent(thread.id(), ignored -> new ThreadState());
    state.registerTurn(handle);

    ObjectNode params = JsonNodeFactory.instance.objectNode();
    params.put("threadId", thread.id());
    ObjectNode text = params.putArray("input").addObject();
    text.put("type", "text");
    text.put("text", input.text());
    try {
      JsonNode result = request("turn/start", params).join();
      handle.setTurnId(requiredId(result, "turn", "turn/start"));
      return handle;
    } catch (RuntimeException failure) {
      state.clearTurn(handle);
      handle.fail(unwrapCompletionFailure(failure));
      throw failure;
    }
  }

  CompletableFuture<Void> interrupt(TurnHandle handle) {
    String turnId = handle.turnId();
    if (turnId == null) {
      return CompletableFuture.failedFuture(new CodexProtocolException("Turn has not started"));
    }
    ObjectNode params = JsonNodeFactory.instance.objectNode();
    params.put("threadId", handle.threadId());
    params.put("turnId", turnId);
    return request("turn/interrupt", params).thenApply(ignored -> null);
  }

  private void initialize() {
    ObjectNode params = JsonNodeFactory.instance.objectNode();
    ObjectNode clientInfo = params.putObject("clientInfo");
    clientInfo.put("name", "codex-app-server-java");
    clientInfo.put("version", "0.1.0-SNAPSHOT");
    request("initialize", params).join();
    connection.notify("initialized", JsonNodeFactory.instance.objectNode());
  }

  private CodexThread createThread(String method, ObjectNode params) {
    JsonNode result = request(method, params).join();
    String threadId = requiredId(result, "thread", method);
    ThreadState state = threadStates.computeIfAbsent(threadId, ignored -> new ThreadState());
    return new CodexThread(this, threadId, state);
  }

  private ObjectNode threadParams(ThreadOptions options, String threadId) {
    ThreadOptions resolvedOptions = Objects.requireNonNull(options, "options");
    ObjectNode params = JsonNodeFactory.instance.objectNode();
    if (threadId != null) {
      params.put("threadId", threadId);
    }
    if (resolvedOptions.cwd() != null) {
      params.put("cwd", resolvedOptions.cwd().toString());
    }
    if (resolvedOptions.model() != null) {
      params.put("model", resolvedOptions.model());
    }
    params.put("approvalPolicy", "never");
    return params;
  }

  private void onNotification(RpcNotification notification) {
    CodexEvent event = eventDecoder.decode(nextEventSequence.incrementAndGet(), notification);
    event.threadId().ifPresent(threadId -> threadStates.computeIfAbsent(threadId, ignored -> new ThreadState())
        .route(event));
  }

  private void onConnectionClosed(Throwable failure) {
    threadStates.forEach((threadId, state) -> state.failActiveTurn(failure));
  }

  private static String requiredId(JsonNode result, String objectName, String method) {
    JsonNode id = result.path(objectName).path("id");
    if (!id.isTextual() || id.asText().isBlank()) {
      throw new CodexProtocolException("Codex app-server " + method + " response did not contain "
          + objectName + ".id");
    }
    return id.asText();
  }

  private static Throwable unwrapCompletionFailure(RuntimeException failure) {
    return failure.getCause() == null ? failure : failure.getCause();
  }

  static final class ThreadState {
    private final List<ListenerRegistration> threadListeners = new ArrayList<>();
    private final ArrayDeque<CodexEvent> bufferedEvents = new ArrayDeque<>();
    private TurnHandle activeTurn;

    void addThreadListener(Consumer<CodexEvent> listener) {
      ListenerRegistration registration = new ListenerRegistration(listener);
      List<CodexEvent> replay;
      synchronized (this) {
        threadListeners.add(registration);
        replay = List.copyOf(bufferedEvents);
        bufferedEvents.clear();
      }
      registration.start(replay);
    }

    synchronized void registerTurn(TurnHandle handle) {
      if (activeTurn != null && !activeTurn.completion().isDone()) {
        throw new IllegalStateException("A turn is already active for this thread");
      }
      activeTurn = handle;
    }

    synchronized void clearTurn(TurnHandle handle) {
      if (activeTurn == handle) {
        activeTurn = null;
      }
    }

    void route(CodexEvent event) {
      List<ListenerRegistration> listeners;
      TurnHandle currentTurn;
      synchronized (this) {
        listeners = List.copyOf(threadListeners);
        currentTurn = activeTurn;
        if (currentTurn != null && event.turnId().isPresent() && (currentTurn.turnId() == null
            || currentTurn.turnId().equals(event.turnId().get()))) {
          if (event.method().equals("turn/completed")) {
            activeTurn = null;
          }
        } else {
          currentTurn = null;
          if (threadListeners.isEmpty()) {
            bufferedEvents.addLast(event);
          }
        }
      }
      listeners.forEach(listener -> listener.dispatch(event));
      if (currentTurn != null) {
        currentTurn.deliver(event);
      }
    }

    void failActiveTurn(Throwable failure) {
      TurnHandle turn;
      synchronized (this) {
        turn = activeTurn;
        activeTurn = null;
      }
      if (turn != null) {
        turn.fail(failure);
      }
    }

    void closeCallbacks() {
      List<ListenerRegistration> listeners;
      synchronized (this) {
        listeners = List.copyOf(threadListeners);
        threadListeners.clear();
      }
      listeners.forEach(ListenerRegistration::close);
    }
  }

  private static final class ListenerRegistration {
    private final Consumer<CodexEvent> listener;
    private final ArrayDeque<CodexEvent> pendingBeforeStart = new ArrayDeque<>();
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "codex-app-server-thread-callback");
      thread.setDaemon(true);
      return thread;
    });
    private boolean started;

    private ListenerRegistration(Consumer<CodexEvent> listener) {
      this.listener = Objects.requireNonNull(listener, "listener");
    }

    private synchronized void start(List<CodexEvent> replay) {
      replay.forEach(this::submit);
      while (!pendingBeforeStart.isEmpty()) {
        submit(pendingBeforeStart.removeFirst());
      }
      started = true;
    }

    private synchronized void dispatch(CodexEvent event) {
      if (!started) {
        pendingBeforeStart.addLast(event);
        return;
      }
      submit(event);
    }

    private void submit(CodexEvent event) {
      try {
        dispatcher.execute(() -> {
          try {
            listener.accept(event);
          } catch (Throwable ignored) {
            // Listener failures are isolated from state routing.
          }
        });
      } catch (RejectedExecutionException ignored) {
        // Client closure may race an already-ingressed event.
      }
    }

    private void close() {
      dispatcher.shutdownNow();
    }
  }
}
