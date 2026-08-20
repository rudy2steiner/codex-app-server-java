package com.loopaide.codex.appserver.protocol.v2;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/** An ordered notification emitted by Codex during a thread or turn. */
public sealed interface CodexEvent permits KnownCodexEvent, UnknownCodexEvent {

    long sequence();

    String method();

    Optional<String> threadId();

    Optional<String> turnId();

    Optional<String> itemId();

    JsonNode params();

    JsonNode raw();
}
