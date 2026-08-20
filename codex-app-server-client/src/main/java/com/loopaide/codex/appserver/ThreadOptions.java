package com.loopaide.codex.appserver;

import java.nio.file.Path;

/** Stable parameters used when starting or resuming a Codex thread. */
public final class ThreadOptions {
  private final Path cwd;
  private final String model;

  private ThreadOptions(Builder builder) {
    this.cwd = builder.cwd;
    this.model = builder.model;
  }

  public static Builder builder() {
    return new Builder();
  }

  public Path cwd() {
    return cwd;
  }

  public String model() {
    return model;
  }

  public static final class Builder {
    private Path cwd;
    private String model;

    public Builder cwd(Path cwd) {
      this.cwd = cwd;
      return this;
    }

    public Builder model(String model) {
      if (model != null && model.isBlank()) {
        throw new IllegalArgumentException("model must not be blank");
      }
      this.model = model;
      return this;
    }

    public ThreadOptions build() {
      return new ThreadOptions(this);
    }
  }
}
