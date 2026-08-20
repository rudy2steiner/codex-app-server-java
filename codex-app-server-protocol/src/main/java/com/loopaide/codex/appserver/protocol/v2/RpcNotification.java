package com.loopaide.codex.appserver.protocol.v2;

import com.fasterxml.jackson.databind.JsonNode;

/** A server notification envelope, identified by {@code method} without an {@code id}. */
public record RpcNotification(String method, JsonNode params, JsonNode raw) implements RpcMessage {
}
