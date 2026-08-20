package com.loopaide.codex.appserver.testing;

import com.loopaide.codex.appserver.transport.CodexTransport;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Deterministic in-memory JSONL transport for app-server tests and fixtures. */
public final class InMemoryCodexTransport implements CodexTransport {
  private final List<String> sentMessages = new CopyOnWriteArrayList<>();
  private final AtomicBoolean alive = new AtomicBoolean(true);
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile Listener listener;
  private volatile Consumer<String> outboundListener = message -> { };

  @Override
  public void start(Listener listener) {
    this.listener = Objects.requireNonNull(listener, "listener");
  }

  @Override
  public void send(String message) {
    if (!isAlive()) {
      throw new IllegalStateException("Transport is closed");
    }
    sentMessages.add(message);
    outboundListener.accept(message);
  }

  @Override
  public boolean isAlive() {
    return alive.get();
  }

  @Override
  public void close() {
    close(null);
  }

  /** Delivers a complete server-to-client JSONL message. */
  public void receive(String message) {
    Listener currentListener = requireListener();
    if (!isAlive()) {
      throw new IllegalStateException("Transport is closed");
    }
    currentListener.onMessage(message);
  }

  /** Closes the fake transport and reports {@code cause} to its listener. */
  public void fail(Throwable cause) {
    close(Objects.requireNonNull(cause, "cause"));
  }

  /** Returns an immutable snapshot of complete client-to-server messages. */
  public List<String> sentMessages() {
    return List.copyOf(sentMessages);
  }

  /** Registers a fake-server callback for each client-to-server message. */
  public void onSend(Consumer<String> outboundListener) {
    this.outboundListener = Objects.requireNonNull(outboundListener, "outboundListener");
  }

  private void close(Throwable cause) {
    if (closed.compareAndSet(false, true)) {
      alive.set(false);
      Listener currentListener = listener;
      if (currentListener != null) {
        currentListener.onClosed(cause);
      }
    }
  }

  private Listener requireListener() {
    Listener currentListener = listener;
    if (currentListener == null) {
      throw new IllegalStateException("Transport has not been started");
    }
    return currentListener;
  }
}
