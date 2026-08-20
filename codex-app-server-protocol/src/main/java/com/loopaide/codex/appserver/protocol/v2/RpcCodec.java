package com.loopaide.codex.appserver.protocol.v2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Decodes and encodes the JSONL envelopes used by the Codex app-server. */
public final class RpcCodec {

    private final ObjectMapper objectMapper;

    public RpcCodec() {
        this(new ObjectMapper());
    }

    public RpcCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RpcMessage decode(String line) {
        final JsonNode raw;
        try {
            raw = objectMapper.readTree(line);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid JSON-RPC message", exception);
        }

        if (raw == null || !raw.isObject()) {
            throw new IllegalArgumentException("Invalid JSON-RPC message shape: expected an object");
        }

        boolean hasId = raw.has("id");
        boolean hasMethod = raw.has("method");
        boolean hasResult = raw.has("result");
        boolean hasError = raw.has("error");

        if (hasMethod && !raw.path("method").isTextual()) {
            throw new IllegalArgumentException("Invalid JSON-RPC method: expected a string");
        }
        if (hasId && !isValidId(raw.path("id"))) {
            throw new IllegalArgumentException("Invalid JSON-RPC id: expected a string or signed 64-bit integer");
        }

        if (hasId && hasMethod) {
            return new RpcRequest(raw.path("id"), raw.path("method").asText(), raw.path("params"), raw);
        }
        if (hasMethod) {
            return new RpcNotification(raw.path("method").asText(), raw.path("params"), raw);
        }
        if (hasId && hasResult && hasError) {
            throw new IllegalArgumentException("Ambiguous JSON-RPC response shape: both result and error are present");
        }
        if (hasId && hasResult) {
            return new RpcSuccess(raw.path("id"), raw.path("result"), raw);
        }
        if (hasId && hasError) {
            JsonNode error = raw.path("error");
            return new RpcFailure(raw.path("id"), decodeError(error), raw);
        }

        throw new IllegalArgumentException("Invalid JSON-RPC message shape");
    }

    public String encode(JsonNode message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to encode JSON-RPC message", exception);
        }
    }

    private static boolean isValidId(JsonNode id) {
        return id.isTextual() || (id.isIntegralNumber() && id.canConvertToLong());
    }

    private static RpcError decodeError(JsonNode error) {
        if (!error.isObject()) {
            throw new IllegalArgumentException("Invalid JSON-RPC error: expected an object");
        }

        JsonNode code = error.path("code");
        if (!code.isIntegralNumber() || !code.canConvertToLong()) {
            throw new IllegalArgumentException("Invalid JSON-RPC error code: expected a signed 64-bit integer");
        }

        JsonNode message = error.path("message");
        if (!message.isTextual()) {
            throw new IllegalArgumentException("Invalid JSON-RPC error message: expected a string");
        }

        return new RpcError(code.longValue(), message.textValue(), error.path("data"), error);
    }
}
