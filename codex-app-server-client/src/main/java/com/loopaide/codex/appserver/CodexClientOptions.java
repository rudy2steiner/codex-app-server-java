package com.loopaide.codex.appserver;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/** Process and protocol settings for a {@link CodexClient}. */
public final class CodexClientOptions {
  private final String codexExecutable;
  private final Duration requestTimeout;
  private final ServerRequestHandler serverRequestHandler;
  private final Consumer<CodexDiagnostic> diagnosticConsumer;

  private CodexClientOptions(Builder builder) {
    this.codexExecutable = builder.codexExecutable;
    this.requestTimeout = builder.requestTimeout;
    this.serverRequestHandler = builder.serverRequestHandler;
    this.diagnosticConsumer = builder.diagnosticConsumer;
  }

  public static CodexClientOptions defaults() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public String codexExecutable() {
    return codexExecutable;
  }

  public Duration requestTimeout() {
    return requestTimeout;
  }

  public ServerRequestHandler serverRequestHandler() {
    return serverRequestHandler;
  }

  public Consumer<CodexDiagnostic> diagnosticConsumer() {
    return diagnosticConsumer;
  }

  public static final class Builder {
    private String codexExecutable = "codex";
    private Duration requestTimeout = Duration.ofSeconds(30);
    private ServerRequestHandler serverRequestHandler;
    private Consumer<CodexDiagnostic> diagnosticConsumer = diagnostic -> { };

    public Builder codexExecutable(String codexExecutable) {
      this.codexExecutable = requireNonBlank(codexExecutable, "codexExecutable");
      return this;
    }

    public Builder requestTimeout(Duration requestTimeout) {
      this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
      if (requestTimeout.isZero() || requestTimeout.isNegative()) {
        throw new IllegalArgumentException("requestTimeout must be positive");
      }
      return this;
    }

    public Builder serverRequestHandler(ServerRequestHandler serverRequestHandler) {
      this.serverRequestHandler = Objects.requireNonNull(serverRequestHandler, "serverRequestHandler");
      return this;
    }

    public Builder diagnosticConsumer(Consumer<CodexDiagnostic> diagnosticConsumer) {
      this.diagnosticConsumer = Objects.requireNonNull(diagnosticConsumer, "diagnosticConsumer");
      return this;
    }

    public CodexClientOptions build() {
      return new CodexClientOptions(this);
    }
  }

  private static String requireNonBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
