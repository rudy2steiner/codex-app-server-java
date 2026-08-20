package com.loopaide.codex.appserver.protocol.v2;

import com.fasterxml.jackson.databind.JsonNode;

/** A request envelope, identified by the presence of both {@code id} and {@code method}. */
public record RpcRequest(JsonNode id, String method, JsonNode params, JsonNode raw) implements RpcMessage {
}
