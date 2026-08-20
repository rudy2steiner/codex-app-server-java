package com.loopaide.codex.appserver.protocol.v2;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/** A notification whose method is newer than, or otherwise unknown to, this SDK version. */
public record UnknownCodexEvent(
        long sequence,
        String method,
        Optional<String> threadId,
        Optional<String> turnId,
        Optional<String> itemId,
        JsonNode params,
        JsonNode raw) implements CodexEvent {
}
