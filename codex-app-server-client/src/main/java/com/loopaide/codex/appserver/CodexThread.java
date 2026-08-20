package com.loopaide.codex.appserver;

import com.loopaide.codex.appserver.protocol.v2.CodexEvent;
import java.util.Objects;
import java.util.function.Consumer;

/** A lightweight handle to one thread on a shared Codex app-server process. */
public final class CodexThread {
  private final CodexClient client;
  private final String id;
  private final CodexClient.ThreadState state;

  CodexThread(CodexClient client, String id, CodexClient.ThreadState state) {
    this.client = Objects.requireNonNull(client, "client");
    this.id = Objects.requireNonNull(id, "id");
    this.state = Objects.requireNonNull(state, "state");
  }

  public String id() {
    return id;
  }

  /** Adds a listener for raw ordered events for this thread, including buffered early events. */
  public void addEventListener(Consumer<CodexEvent> listener) {
    state.addThreadListener(listener);
  }

  public TurnHandle startTurn(TurnInput input, Consumer<CodexEvent> listener) {
    return client.startTurn(this, input, listener);
  }
}
