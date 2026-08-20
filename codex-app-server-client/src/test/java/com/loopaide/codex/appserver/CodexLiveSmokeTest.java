package com.loopaide.codex.appserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in integration coverage for a locally installed Codex CLI.
 *
 * <p>Run with {@code CODEX_LIVE_TEST=true mvn -pl codex-app-server-client test}.
 */
@EnabledIfEnvironmentVariable(named = "CODEX_LIVE_TEST", matches = "true")
class CodexLiveSmokeTest {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private static final long TURN_TIMEOUT_SECONDS = 90;

  @Test
  void completesHarmlessTurnInTemporaryGitRepository(@TempDir Path temporaryDirectory) throws Exception {
    Path repository = Files.createDirectory(temporaryDirectory.resolve("live-smoke-repository"));
    initializeGitRepository(repository);

    try (CodexClient client = CodexClient.start(CodexClientOptions.builder()
        .requestTimeout(REQUEST_TIMEOUT)
        .build())) {
      CodexThread thread = client.startThread(ThreadOptions.builder().cwd(repository).build());
      TurnHandle turn = thread.startTurn(TurnInput.text(
          "Reply with exactly: live smoke test passed. Do not read or modify files, run commands, "
              + "or use tools."), ignored -> { });

      TurnResult result = turn.completion().get(TURN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

      assertEquals(thread.id(), result.threadId());
      assertFalse(result.turnId().isBlank());
    }
  }

  private static void initializeGitRepository(Path repository) throws IOException, InterruptedException {
    Process git = new ProcessBuilder("git", "init", "--quiet")
        .directory(repository.toFile())
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
    if (!git.waitFor(10, TimeUnit.SECONDS)) {
      git.destroyForcibly();
      git.waitFor(10, TimeUnit.SECONDS);
      throw new AssertionError("Timed out creating temporary Git repository");
    }
    if (git.exitValue() != 0) {
      throw new AssertionError("Could not create temporary Git repository (exit "
          + git.exitValue() + ")");
    }
    if (!Files.isDirectory(repository.resolve(".git"))) {
      throw new AssertionError("Temporary Git repository was not initialized");
    }
  }
}
