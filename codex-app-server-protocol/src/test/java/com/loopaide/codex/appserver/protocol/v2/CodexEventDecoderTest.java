package com.loopaide.codex.appserver.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodexEventDecoderTest {

    private final RpcCodec codec = new RpcCodec();
    private final CodexEventDecoder decoder = new CodexEventDecoder();

    @Test
    void decodesKnownEventFamiliesInFixtureOrder() throws IOException {
        List<String> lines = new String(getClass().getResourceAsStream("/protocol/codex-0.146.0-turn.jsonl").readAllBytes(),
                StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).toList();

        List<CodexEvent> events = java.util.stream.IntStream.range(0, lines.size())
                .mapToObj(index -> decoder.decode(index + 1L,
                        assertInstanceOf(RpcNotification.class, codec.decode(lines.get(index)))))
                .toList();

        assertEquals(java.util.stream.IntStream.rangeClosed(1, lines.size()).mapToObj(Long::valueOf).toList(),
                events.stream().map(CodexEvent::sequence).toList());
        assertEquals(List.of(
                CodexEventKind.THREAD_STARTED,
                CodexEventKind.TURN_STARTED,
                CodexEventKind.ITEM_STARTED,
                CodexEventKind.AGENT_MESSAGE_DELTA,
                CodexEventKind.REASONING_TEXT_DELTA,
                CodexEventKind.COMMAND_EXECUTION_OUTPUT_DELTA,
                CodexEventKind.FILE_CHANGE_PATCH_UPDATED,
                CodexEventKind.MCP_TOOL_CALL_PROGRESS,
                CodexEventKind.TURN_PLAN_UPDATED,
                CodexEventKind.THREAD_TOKEN_USAGE_UPDATED,
                CodexEventKind.WARNING,
                CodexEventKind.ERROR),
                events.stream().limit(12).map(event -> assertInstanceOf(KnownCodexEvent.class, event).kind()).toList());
        assertEquals("thread-1", events.get(2).threadId().orElseThrow());
        assertEquals("turn-1", events.get(2).turnId().orElseThrow());
        assertEquals("item-1", events.get(2).itemId().orElseThrow());

        UnknownCodexEvent unknown = assertInstanceOf(UnknownCodexEvent.class, events.get(12));
        assertEquals("future/notification", unknown.method());
        assertTrue(unknown.raw().path("futureEnvelopeField").asBoolean());
    }

    @Test
    void extractsIdsFromNestedThreadTurnAndItemObjects() {
        RpcNotification notification = assertInstanceOf(RpcNotification.class, codec.decode("""
                {"method":"item/completed","params":{"thread":{"id":"thread-nested"},"turn":{"id":"turn-nested"},"item":{"id":"item-nested"}}}
                """));

        CodexEvent event = decoder.decode(9, notification);

        KnownCodexEvent known = assertInstanceOf(KnownCodexEvent.class, event);
        assertEquals(CodexEventKind.ITEM_COMPLETED, known.kind());
        assertEquals("thread-nested", known.threadId().orElseThrow());
        assertEquals("turn-nested", known.turnId().orElseThrow());
        assertEquals("item-nested", known.itemId().orElseThrow());
    }
}
