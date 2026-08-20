package com.loopaide.codex.appserver.protocol.v2;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/** A notification with a method known to this SDK version. */
public record KnownCodexEvent(
        long sequence,
        String method,
        CodexEventKind kind,
        Optional<String> threadId,
        Optional<String> turnId,
        Optional<String> itemId,
        JsonNode params,
        JsonNode raw) implements CodexEvent {
}
