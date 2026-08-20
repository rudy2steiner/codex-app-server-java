package com.loopaide.codex.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Terminal result for a Codex turn, retaining the raw completion event. */
public record TurnResult(String threadId, String turnId, JsonNode raw) {
  public TurnResult {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(turnId, "turnId");
    Objects.requireNonNull(raw, "raw");
  }
}
