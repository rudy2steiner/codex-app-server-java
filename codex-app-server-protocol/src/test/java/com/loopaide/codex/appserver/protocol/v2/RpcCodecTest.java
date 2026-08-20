package com.loopaide.codex.appserver.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class RpcCodecTest {

    private final RpcCodec codec = new RpcCodec();

    @Test
    void decodesRequestWithNumericIdAndPreservesUnknownFields() {
        RpcMessage decoded = codec.decode("{\"id\":7,\"method\":\"thread/start\",\"params\":{\"cwd\":\"/repo\"},\"future\":true}");

        RpcRequest request = assertInstanceOf(RpcRequest.class, decoded);
        assertTrue(request.id().isIntegralNumber());
        assertEquals(7, request.id().asInt());
        assertEquals("thread/start", request.method());
        assertEquals("/repo", request.params().path("cwd").asText());
        assertTrue(request.raw().path("future").asBoolean());
    }

    @Test
    void decodesSuccessResponseWithStringId() {
        RpcMessage decoded = codec.decode("{\"id\":\"request-42\",\"result\":{\"thread\":\"t-1\"}}");

        RpcSuccess success = assertInstanceOf(RpcSuccess.class, decoded);
        assertEquals("request-42", success.id().asText());
        assertEquals("t-1", success.result().path("thread").asText());
    }

    @Test
    void decodesFailureResponseAndPreservesErrorPayload() {
        RpcMessage decoded = codec.decode("{\"id\":\"request-42\",\"error\":{\"code\":-32001,\"message\":\"bad request\",\"data\":{\"future\":true}}}");

        RpcFailure failure = assertInstanceOf(RpcFailure.class, decoded);
        assertEquals("request-42", failure.id().asText());
        assertEquals(-32001, failure.error().code());
        assertEquals("bad request", failure.error().message());
        assertTrue(failure.error().data().path("future").asBoolean());
        assertTrue(failure.raw().path("error").path("data").path("future").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, Long.MAX_VALUE})
    void preservesSignedInt64ErrorCodes(long code) {
        RpcMessage decoded = codec.decode("{\"id\":1,\"error\":{\"code\":" + code + ",\"message\":\"boundary\"}}");

        RpcFailure failure = assertInstanceOf(RpcFailure.class, decoded);
        assertEquals(code, failure.error().code());
    }

    @Test
    void decodesNotificationAndPreservesUnknownFields() {
        RpcMessage decoded = codec.decode("{\"method\":\"turn/started\",\"params\":{\"x\":1},\"future\":true}");

        RpcNotification notification = assertInstanceOf(RpcNotification.class, decoded);
        assertEquals("turn/started", notification.method());
        assertEquals(1, notification.params().path("x").asInt());
        assertTrue(notification.raw().path("future").asBoolean());
    }

    @Test
    void encodesJsonWithoutDiscardingFields() throws Exception {
        String encoded = codec.encode(new ObjectMapper().readTree("{\"method\":\"turn/started\",\"future\":true}"));

        assertEquals("turn/started", new ObjectMapper().readTree(encoded).path("method").asText());
        assertTrue(new ObjectMapper().readTree(encoded).path("future").asBoolean());
    }

    @Test
    void rejectsMessageWithoutMethodOrResponsePayload() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> codec.decode("{\"id\":1,\"params\":{}}"));

        assertTrue(error.getMessage().contains("shape"));
    }

    @Test
    void rejectsAmbiguousResponseWithResultAndError() {
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode("{\"id\":1,\"result\":{},\"error\":{\"code\":1,\"message\":\"no\"}}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "1", "{}", "[]"})
    void rejectsNonStringMethods(String invalidMethod) {
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode("{\"method\":" + invalidMethod + ",\"params\":{}}"));
    }

    @ParameterizedTest
    @MethodSource("invalidIdEnvelopes")
    void rejectsIdsThatAreNotStringsOrSignedInt64Values(String envelope) {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(envelope));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null",
            "true",
            "\"error\"",
            "[]",
            "{}",
            "{\"code\":1}",
            "{\"message\":\"bad\"}",
            "{\"code\":1.5,\"message\":\"bad\"}",
            "{\"code\":9223372036854775808,\"message\":\"bad\"}",
            "{\"code\":1,\"message\":null}",
            "{\"code\":1,\"message\":7}"
    })
    void rejectsMalformedErrorPayloads(String invalidError) {
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode("{\"id\":1,\"error\":" + invalidError + "}"));
    }

    private static Stream<String> invalidIdEnvelopes() {
        return Stream.of("null", "1.5", "true", "{}", "[]", "9223372036854775808")
                .flatMap(id -> Stream.of(
                        "{\"id\":" + id + ",\"method\":\"thread/start\"}",
                        "{\"id\":" + id + ",\"result\":{}}",
                        "{\"id\":" + id + ",\"error\":{\"code\":1,\"message\":\"bad\"}}"));
    }
}
