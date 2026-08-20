package com.loopaide.codex.appserver.protocol.v2;

import com.fasterxml.jackson.databind.JsonNode;

/** A JSONL message emitted or accepted by the Codex app-server protocol. */
public sealed interface RpcMessage permits RpcRequest, RpcSuccess, RpcFailure, RpcNotification {

    JsonNode raw();
}
