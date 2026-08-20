package com.loopaide.codex.appserver.transport;

/** A line-oriented transport to a Codex app-server instance. */
public interface CodexTransport extends AutoCloseable {

  /** Starts delivery of received JSONL lines to {@code listener}. */
  void start(Listener listener);

  /** Sends one complete JSONL message. */
  void send(String message);

  /** Returns whether the underlying connection can still accept messages. */
  boolean isAlive();

  /** Closes this transport. Implementations must make this operation idempotent. */
  @Override
  void close();

  interface Listener {
    void onMessage(String message);

    void onClosed(Throwable cause);
  }
}
