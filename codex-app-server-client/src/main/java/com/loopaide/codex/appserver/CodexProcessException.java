package com.loopaide.codex.appserver;

/** Indicates that the Codex app-server process could not be started or used. */
public final class CodexProcessException extends CodexException {

  public CodexProcessException(String message) {
    super(message);
  }

  public CodexProcessException(String message, Throwable cause) {
    super(message, cause);
  }
}
