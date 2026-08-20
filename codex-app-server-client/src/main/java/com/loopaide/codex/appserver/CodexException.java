package com.loopaide.codex.appserver;

/** Base exception for failures while communicating with Codex app-server. */
public class CodexException extends RuntimeException {

  public CodexException(String message) {
    super(message);
  }

  public CodexException(String message, Throwable cause) {
    super(message, cause);
  }
}
