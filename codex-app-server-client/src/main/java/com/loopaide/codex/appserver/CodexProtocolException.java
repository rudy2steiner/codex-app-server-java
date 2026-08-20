package com.loopaide.codex.appserver;

/** Indicates malformed or otherwise invalid data from the app-server protocol. */
public final class CodexProtocolException extends CodexException {

  public CodexProtocolException(String message) {
    super(message);
  }

  public CodexProtocolException(String message, Throwable cause) {
    super(message, cause);
  }
}
