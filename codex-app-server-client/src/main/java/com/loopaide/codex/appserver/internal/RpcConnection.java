package com.loopaide.codex.appserver.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopaide.codex.appserver.CodexException;
import com.loopaide.codex.appserver.CodexDiagnostic;
import com.loopaide.codex.appserver.CodexProcessException;
import com.loopaide.codex.appserver.CodexProtocolException;
import com.loopaide.codex.appserver.ServerRequestHandler;
import com.loopaide.codex.appserver.protocol.v2.RpcCodec;
import com.loopaide.codex.appserver.protocol.v2.RpcFailure;
import com.loopaide.codex.appserver.protocol.v2.RpcMessage;
import com.loopaide.codex.appserver.protocol.v2.RpcNotification;
import com.loopaide.codex.appserver.protocol.v2.RpcRequest;
import com.loopaide.codex.appserver.protocol.v2.RpcSuccess;
import com.loopaide.codex.appserver.transport.CodexTransport;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Correlates app-server JSON-RPC messages over a raw JSONL transport. */
public final class RpcConnection implements AutoCloseable {
  private static final Logger LOGGER = Logger.getLogger(RpcConnection.class.getName());
  private static final ObjectMapper JSON = new ObjectMapper();

  private final CodexTransport transport;
  private final RpcCodec codec;
  private final Consumer<RpcNotification> notificationListener;
  private final ServerRequestHandler serverRequestHandler;
  private final Consumer<Throwable> closeListener;
  private final Consumer<CodexDiagnostic> diagnosticConsumer;
  private final AtomicLong nextRequestId = new AtomicLong();
  private final ConcurrentMap<Long, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
  private final ScheduledExecutorService timeouts;
  private final Object lifecycleLock = new Object();
  private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "codex-app-server-events");
    thread.setDaemon(true);
    return thread;
  });
  private final AtomicBoolean closed = new AtomicBoolean();

  public RpcConnection(CodexTransport transport) {
    this(transport, notification -> { }, null, failure -> { });
  }

  public RpcConnection(CodexTransport transport, Consumer<RpcNotification> notificationListener) {
    this(transport, notificationListener, null, failure -> { });
  }

  public RpcConnection(CodexTransport transport, Consumer<RpcNotification> notificationListener,
      ServerRequestHandler serverRequestHandler) {
    this(transport, notificationListener, serverRequestHandler, failure -> { });
  }

  public RpcConnection(CodexTransport transport, Consumer<RpcNotification> notificationListener,
      ServerRequestHandler serverRequestHandler, Consumer<Throwable> closeListener) {
    this(transport, notificationListener, serverRequestHandler, closeListener, diagnostic -> { });
  }

  public RpcConnection(CodexTransport transport, Consumer<RpcNotification> notificationListener,
      ServerRequestHandler serverRequestHandler, Consumer<Throwable> closeListener,
      Consumer<CodexDiagnostic> diagnosticConsumer) {
    this(transport, notificationListener, serverRequestHandler, closeListener, diagnosticConsumer,
        newTimeoutScheduler());
  }

  RpcConnection(CodexTransport transport, Consumer<RpcNotification> notificationListener,
      ServerRequestHandler serverRequestHandler, Consumer<Throwable> closeListener,
      ScheduledExecutorService timeouts) {
    this(transport, notificationListener, serverRequestHandler, closeListener, diagnostic -> { }, timeouts);
  }

  RpcConnection(CodexTransport transport, Consumer<RpcNotification> notificationListener,
      ServerRequestHandler serverRequestHandler, Consumer<Throwable> closeListener,
      Consumer<CodexDiagnostic> diagnosticConsumer, ScheduledExecutorService timeouts) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.notificationListener = Objects.requireNonNull(notificationListener, "notificationListener");
    this.serverRequestHandler = serverRequestHandler;
    this.closeListener = Objects.requireNonNull(closeListener, "closeListener");
    this.diagnosticConsumer = Objects.requireNonNull(diagnosticConsumer, "diagnosticConsumer");
    this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
    this.codec = new RpcCodec();
    transport.start(new CodexTransport.Listener() {
      @Override
      public void onMessage(String message) {
        RpcConnection.this.onMessage(message);
      }

      @Override
      public void onClosed(Throwable cause) {
        RpcConnection.this.onTransportClosed(cause);
      }
    });
  }

  public CompletableFuture<JsonNode> request(String method, JsonNode params, Duration timeout) {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    PendingRequest pending;
    long id;
    String encodedMessage;
    synchronized (lifecycleLock) {
      if (closed.get()) {
        return CompletableFuture.failedFuture(new CodexProcessException("RPC connection is closed"));
      }

      id = nextRequestId.incrementAndGet();
      pending = new PendingRequest();
      pendingRequests.put(id, pending);
      try {
        pending.timeout = timeouts.schedule(() -> failTimedOutRequest(id, pending),
            timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException exception) {
        if (pendingRequests.remove(id, pending)) {
          pending.future.completeExceptionally(new CodexProcessException("RPC connection is closed", exception));
        }
        return pending.future;
      }

      ObjectNode message = JsonNodeFactory.instance.objectNode();
      message.put("id", id);
      message.put("method", method);
      message.set("params", params == null ? JsonNodeFactory.instance.nullNode() : params);
      encodedMessage = codec.encode(message);
    }
    try {
      transport.send(encodedMessage);
    } catch (RuntimeException exception) {
      if (pendingRequests.remove(id, pending)) {
        pending.cancelTimeout();
        pending.future.completeExceptionally(exception);
      }
    }
    return pending.future;
  }

  public void notify(String method, JsonNode params) {
    Objects.requireNonNull(method, "method");
    ObjectNode message = JsonNodeFactory.instance.objectNode();
    message.put("method", method);
    message.set("params", params == null ? JsonNodeFactory.instance.nullNode() : params);
    if (!beginSend()) {
      throw new CodexProcessException("RPC connection is closed");
    }
    transport.send(codec.encode(message));
  }

  public boolean isAlive() {
    return !closed.get() && transport.isAlive();
  }

  @Override
  public void close() {
    shutdown(new CodexProcessException("RPC connection was closed"), true);
  }

  private void onMessage(String line) {
    final RpcMessage message;
    try {
      message = codec.decode(line);
    } catch (RuntimeException exception) {
      LOGGER.log(Level.FINE, "Ignoring malformed Codex app-server message: " + line, exception);
      failMalformedResponse(line, exception);
      report(new CodexDiagnostic(CodexDiagnostic.Kind.MALFORMED_MESSAGE,
          "Malformed Codex app-server message: " + line, null, exception));
      return;
    }
    if (message instanceof RpcSuccess success) {
      completeSuccess(success);
    } else if (message instanceof RpcFailure failure) {
      completeFailure(failure);
    } else if (message instanceof RpcNotification notification) {
      dispatch(() -> notificationListener.accept(notification));
    } else if (message instanceof RpcRequest request) {
      dispatch(() -> replyToServerRequest(request));
    }
  }

  private void completeSuccess(RpcSuccess response) {
    findPending(response.id(), response.raw()).ifPresent(pending -> {
      pending.cancelTimeout();
      pending.future.complete(response.result());
    });
  }

  private void completeFailure(RpcFailure response) {
    findPending(response.id(), response.raw()).ifPresent(pending -> {
      pending.cancelTimeout();
      String message = "Codex app-server returned error " + response.error().code()
          + ": " + response.error().message();
      pending.future.completeExceptionally(new CodexProtocolException(message));
    });
  }

  private java.util.Optional<PendingRequest> findPending(JsonNode id, JsonNode raw) {
    if (!id.canConvertToLong()) {
      LOGGER.fine(() -> "Ignoring response with non-numeric id: " + id);
      report(new CodexDiagnostic(CodexDiagnostic.Kind.UNEXPECTED_RESPONSE,
          "Response has a non-numeric id: " + id, raw, null));
      return java.util.Optional.empty();
    }
    long requestId = id.longValue();
    PendingRequest pending = pendingRequests.remove(requestId);
    if (pending == null) {
      report(new CodexDiagnostic(CodexDiagnostic.Kind.UNEXPECTED_RESPONSE,
          "Response has no pending request: " + requestId, raw, null));
    }
    return java.util.Optional.ofNullable(pending);
  }

  private void failTimedOutRequest(long id, PendingRequest pending) {
    if (pendingRequests.remove(id, pending)) {
      pending.future.completeExceptionally(
          new CodexProtocolException("Codex app-server request " + id + " timed out"));
    }
  }

  private void failMalformedResponse(String line, RuntimeException cause) {
    final JsonNode raw;
    try {
      raw = JSON.readTree(line);
    } catch (Exception ignored) {
      return;
    }
    if (raw == null || !raw.isObject() || raw.has("method")) {
      return;
    }
    JsonNode id = raw.path("id");
    if (!id.isIntegralNumber() || !id.canConvertToLong()) {
      return;
    }
    long requestId = id.longValue();
    PendingRequest pending = pendingRequests.remove(requestId);
    if (pending != null) {
      pending.cancelTimeout();
      pending.future.completeExceptionally(new CodexProtocolException(
          "Malformed Codex app-server response for request " + requestId, cause));
    }
  }

  private void replyToServerRequest(RpcRequest request) {
    ObjectNode response = JsonNodeFactory.instance.objectNode();
    response.set("id", request.id());
    if (serverRequestHandler == null) {
      report(new CodexDiagnostic(CodexDiagnostic.Kind.UNHANDLED_SERVER_REQUEST,
          "Unhandled server request: " + request.method(), request.raw(), null));
      ObjectNode error = response.putObject("error");
      error.put("code", -32601);
      error.put("message", "Unhandled server request: " + request.method());
    } else {
      try {
        JsonNode result = serverRequestHandler.handle(request);
        response.set("result", result == null ? JsonNodeFactory.instance.nullNode() : result);
      } catch (RuntimeException exception) {
        report(new CodexDiagnostic(CodexDiagnostic.Kind.SERVER_REQUEST_HANDLER_FAILURE,
            "Server request handler failed: " + request.method(), request.raw(), exception));
        ObjectNode error = response.putObject("error");
        error.put("code", -32603);
        error.put("message", "Server request handler failed: " + exception.getMessage());
      }
    }
    try {
      if (beginSend()) {
        transport.send(codec.encode(response));
      }
    } catch (RuntimeException exception) {
      onTransportClosed(exception);
    }
  }

  private void onTransportClosed(Throwable cause) {
    Throwable failure = cause instanceof CodexException
        ? cause
        : new CodexProcessException("Codex app-server process exited", cause);
    shutdown(failure, false);
  }

  private void shutdown(Throwable failure, boolean closeTransport) {
    synchronized (lifecycleLock) {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      pendingRequests.forEach((id, pending) -> {
        if (pendingRequests.remove(id, pending)) {
          pending.cancelTimeout();
          pending.future.completeExceptionally(failure);
        }
      });
      timeouts.shutdownNow();
    }
    dispatch(() -> closeListener.accept(failure));
    dispatcher.shutdown();
    if (closeTransport) {
      transport.close();
    }
  }

  private boolean beginSend() {
    synchronized (lifecycleLock) {
      if (closed.get()) {
        return false;
      }
      return true;
    }
  }

  private void dispatch(Runnable task) {
    try {
      dispatcher.execute(task);
    } catch (RejectedExecutionException ignored) {
      // Transport delivery may race shutdown after the connection has stopped accepting events.
    }
  }

  private void report(CodexDiagnostic diagnostic) {
    dispatch(() -> {
      try {
        diagnosticConsumer.accept(diagnostic);
      } catch (Throwable ignored) {
        // Diagnostics must never disrupt protocol processing.
      }
    });
  }

  private static final class PendingRequest {
    private final CompletableFuture<JsonNode> future = new CompletableFuture<>();
    private volatile ScheduledFuture<?> timeout;

    private void cancelTimeout() {
      ScheduledFuture<?> scheduledTimeout = timeout;
      if (scheduledTimeout != null) {
        scheduledTimeout.cancel(false);
      }
    }
  }

  private static ScheduledExecutorService newTimeoutScheduler() {
    return Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "codex-app-server-timeouts");
      thread.setDaemon(true);
      return thread;
    });
  }
}
