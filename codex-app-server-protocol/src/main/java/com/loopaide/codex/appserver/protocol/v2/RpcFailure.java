package com.loopaide.codex.appserver.protocol.v2;

import com.fasterxml.jackson.databind.JsonNode;

/** A failed response envelope, identified by {@code id} and {@code error}. */
public record RpcFailure(JsonNode id, RpcError error, JsonNode raw) implements RpcMessage {
}
