package com.loopaide.codex.appserver.protocol.v2;

import com.fasterxml.jackson.databind.JsonNode;

/** The structured error payload carried by a failed response. */
public record RpcError(long code, String message, JsonNode data, JsonNode raw) {
}
