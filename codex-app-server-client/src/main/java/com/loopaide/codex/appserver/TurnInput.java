package com.loopaide.codex.appserver;

import java.util.Objects;

/** A turn input supported by the stable app-server protocol. */
public record TurnInput(String text) {

  public TurnInput {
    Objects.requireNonNull(text, "text");
  }

  public static TurnInput text(String text) {
    return new TurnInput(text);
  }
}
