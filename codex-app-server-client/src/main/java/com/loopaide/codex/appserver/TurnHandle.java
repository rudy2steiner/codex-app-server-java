package com.loopaide.codex.appserver;

import com.loopaide.codex.appserver.protocol.v2.CodexEvent;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** An active Codex turn and its ordered event stream. */
public final class TurnHandle {
  private final CodexClient client;
  private final String threadId;
  private final Consumer<CodexEvent> listener;
  private final CompletableFuture<TurnResult> completion = new CompletableFuture<>();
  private final ExecutorService callbackDispatcher = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "codex-app-server-turn-callback");
    thread.setDaemon(true);
    return thread;
  });
  private volatile String turnId;

  TurnHandle(CodexClient client, String threadId, Consumer<CodexEvent> listener) {
    this.client = Objects.requireNonNull(client, "client");
    this.threadId = Objects.requireNonNull(threadId, "threadId");
    this.listener = Objects.requireNonNull(listener, "listener");
  }

  public String threadId() {
    return threadId;
  }

  public String turnId() {
    return turnId;
  }

  public CompletableFuture<TurnResult> completion() {
    return completion;
  }

  public CompletableFuture<Void> interrupt() {
    return client.interrupt(this);
  }

  void setTurnId(String turnId) {
    this.turnId = Objects.requireNonNull(turnId, "turnId");
  }

  void deliver(CodexEvent event) {
    if (event.method().equals("turn/completed")) {
      String completedTurnId = event.turnId().orElse(turnId);
      if (completedTurnId != null) {
        completion.complete(new TurnResult(threadId, completedTurnId, event.raw()));
      }
    }
    dispatch(event);
    if (completion.isDone()) {
      callbackDispatcher.shutdown();
    }
  }

  void fail(Throwable failure) {
    completion.completeExceptionally(failure);
    callbackDispatcher.shutdownNow();
  }

  private void dispatch(CodexEvent event) {
    try {
      callbackDispatcher.execute(() -> {
        try {
          listener.accept(event);
        } catch (Throwable ignored) {
          // Listener failures must not break protocol state or terminal completion.
        }
      });
    } catch (RejectedExecutionException ignored) {
      // A terminal/process-exit transition may close the callback queue concurrently.
    }
  }
}
