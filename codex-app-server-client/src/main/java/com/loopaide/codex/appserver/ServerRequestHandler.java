package com.loopaide.codex.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopaide.codex.appserver.protocol.v2.RpcRequest;

/** Handles a request initiated by the Codex app-server and returns its result payload. */
@FunctionalInterface
public interface ServerRequestHandler {

  JsonNode handle(RpcRequest request);
}
