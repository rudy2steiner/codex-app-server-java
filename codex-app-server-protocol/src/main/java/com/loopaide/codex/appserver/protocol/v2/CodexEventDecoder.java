package com.loopaide.codex.appserver.protocol.v2;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Converts protocol notifications into ordered, forward-compatible Codex events. */
public final class CodexEventDecoder {

    private static final Map<String, CodexEventKind> KINDS = Map.ofEntries(
            Map.entry("thread/started", CodexEventKind.THREAD_STARTED),
            Map.entry("thread/status/changed", CodexEventKind.THREAD_STATUS_CHANGED),
            Map.entry("thread/archived", CodexEventKind.THREAD_ARCHIVED),
            Map.entry("thread/deleted", CodexEventKind.THREAD_DELETED),
            Map.entry("thread/unarchived", CodexEventKind.THREAD_UNARCHIVED),
            Map.entry("thread/closed", CodexEventKind.THREAD_CLOSED),
            Map.entry("thread/name/updated", CodexEventKind.THREAD_NAME_UPDATED),
            Map.entry("thread/goal/updated", CodexEventKind.THREAD_GOAL_UPDATED),
            Map.entry("thread/goal/cleared", CodexEventKind.THREAD_GOAL_CLEARED),
            Map.entry("thread/environment/connected", CodexEventKind.THREAD_ENVIRONMENT_CONNECTED),
            Map.entry("thread/environment/disconnected", CodexEventKind.THREAD_ENVIRONMENT_DISCONNECTED),
            Map.entry("thread/settings/updated", CodexEventKind.THREAD_SETTINGS_UPDATED),
            Map.entry("thread/tokenUsage/updated", CodexEventKind.THREAD_TOKEN_USAGE_UPDATED),
            Map.entry("thread/compacted", CodexEventKind.THREAD_COMPACTED),
            Map.entry("turn/started", CodexEventKind.TURN_STARTED),
            Map.entry("turn/completed", CodexEventKind.TURN_COMPLETED),
            Map.entry("turn/diff/updated", CodexEventKind.TURN_DIFF_UPDATED),
            Map.entry("turn/plan/updated", CodexEventKind.TURN_PLAN_UPDATED),
            Map.entry("turn/moderationMetadata", CodexEventKind.TURN_MODERATION_METADATA),
            Map.entry("item/started", CodexEventKind.ITEM_STARTED),
            Map.entry("item/autoApprovalReview/started", CodexEventKind.ITEM_AUTO_APPROVAL_REVIEW_STARTED),
            Map.entry("item/autoApprovalReview/completed", CodexEventKind.ITEM_AUTO_APPROVAL_REVIEW_COMPLETED),
            Map.entry("item/completed", CodexEventKind.ITEM_COMPLETED),
            Map.entry("item/agentMessage/delta", CodexEventKind.AGENT_MESSAGE_DELTA),
            Map.entry("item/plan/delta", CodexEventKind.PLAN_DELTA),
            Map.entry("item/commandExecution/outputDelta", CodexEventKind.COMMAND_EXECUTION_OUTPUT_DELTA),
            Map.entry("item/commandExecution/terminalInteraction", CodexEventKind.COMMAND_EXECUTION_TERMINAL_INTERACTION),
            Map.entry("item/fileChange/outputDelta", CodexEventKind.FILE_CHANGE_OUTPUT_DELTA),
            Map.entry("item/fileChange/patchUpdated", CodexEventKind.FILE_CHANGE_PATCH_UPDATED),
            Map.entry("item/mcpToolCall/progress", CodexEventKind.MCP_TOOL_CALL_PROGRESS),
            Map.entry("mcpServer/oauthLogin/completed", CodexEventKind.MCP_SERVER_OAUTH_LOGIN_COMPLETED),
            Map.entry("mcpServer/startupStatus/updated", CodexEventKind.MCP_SERVER_STARTUP_STATUS_UPDATED),
            Map.entry("item/reasoning/summaryTextDelta", CodexEventKind.REASONING_SUMMARY_TEXT_DELTA),
            Map.entry("item/reasoning/summaryPartAdded", CodexEventKind.REASONING_SUMMARY_PART_ADDED),
            Map.entry("item/reasoning/textDelta", CodexEventKind.REASONING_TEXT_DELTA),
            Map.entry("warning", CodexEventKind.WARNING),
            Map.entry("error", CodexEventKind.ERROR));

    public CodexEvent decode(long sequence, RpcNotification notification) {
        JsonNode params = notification.params();
        Optional<String> threadId = firstText(params, List.of(
                new String[]{"threadId"}, new String[]{"thread", "id"},
                new String[]{"turn", "threadId"}, new String[]{"turn", "thread", "id"},
                new String[]{"item", "threadId"}, new String[]{"item", "thread", "id"}));
        Optional<String> turnId = firstText(params, List.of(
                new String[]{"turnId"}, new String[]{"turn", "id"},
                new String[]{"item", "turnId"}, new String[]{"item", "turn", "id"}));
        Optional<String> itemId = firstText(params, List.of(
                new String[]{"itemId"}, new String[]{"item", "id"}));

        CodexEventKind kind = KINDS.get(notification.method());
        if (kind == null) {
            return new UnknownCodexEvent(sequence, notification.method(), threadId, turnId, itemId, params, notification.raw());
        }
        return new KnownCodexEvent(sequence, notification.method(), kind, threadId, turnId, itemId, params, notification.raw());
    }

    private static Optional<String> firstText(JsonNode params, List<String[]> paths) {
        for (String[] path : paths) {
            JsonNode candidate = params;
            for (String component : path) {
                candidate = candidate.path(component);
            }
            if (candidate.isTextual() && !candidate.asText().isBlank()) {
                return Optional.of(candidate.asText());
            }
        }
        return Optional.empty();
    }
}
