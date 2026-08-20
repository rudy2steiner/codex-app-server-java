package com.loopaide.codex.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** A non-fatal protocol or callback problem observed by the client. */
public record CodexDiagnostic(Kind kind, String message, JsonNode raw, Throwable cause) {
  public CodexDiagnostic {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(message, "message");
  }

  public enum Kind {
    MALFORMED_MESSAGE,
    UNEXPECTED_RESPONSE,
    UNHANDLED_SERVER_REQUEST,
    SERVER_REQUEST_HANDLER_FAILURE
  }
}
