package com.loopaide.codex.appserver.protocol.v2;

import com.fasterxml.jackson.databind.JsonNode;

/** A successful response envelope, identified by {@code id} and {@code result}. */
public record RpcSuccess(JsonNode id, JsonNode result, JsonNode raw) implements RpcMessage {
}
