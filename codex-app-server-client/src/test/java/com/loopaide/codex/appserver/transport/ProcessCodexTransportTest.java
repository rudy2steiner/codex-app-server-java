package com.loopaide.codex.appserver.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loopaide.codex.appserver.CodexProcessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessCodexTransportTest {

  @Test
  void stdoutEofCleansUpLiveProcessBeforeSingleCallback(@TempDir Path temporaryDirectory)
      throws Exception {
    Path executable = temporaryDirectory.resolve("stdout-eof-codex");
    Files.writeString(executable, """
        #!/bin/sh
        exec 1>&-
        sleep 30
        """);
    assertTrue(executable.toFile().setExecutable(true));

    AtomicReference<Process> childReference = new AtomicReference<>();
    ProcessCodexTransport transport = new ProcessCodexTransport(executable.toString(), command -> {
      Process child = new ProcessBuilder(command).start();
      childReference.set(child);
      return child;
    });
    RecordingListener listener = new RecordingListener(0);
    transport.start(listener);
    Process child = childReference.get();
    try {
      assertTrue(listener.closed.await(5, TimeUnit.SECONDS));

      child.onExit().get(5, TimeUnit.SECONDS);
      assertFalse(child.isAlive());
      assertFalse(transport.isAlive());
      assertThrows(CodexProcessException.class, () -> transport.send("{\"id\":1}"));
      assertEquals(1, listener.closedCount);
      assertNull(listener.closeCause);

      transport.close();
      assertEquals(1, listener.closedCount);
    } finally {
      child.destroyForcibly();
    }
  }

  @Test
  void closeListenerRunsAfterCleanupWithoutHoldingLifecycleLock(@TempDir Path temporaryDirectory)
      throws Exception {
    Path executable = temporaryDirectory.resolve("reentrant-close-codex");
    Files.writeString(executable, """
        #!/bin/sh
        exec 0<&-
        printf 'ready\\n'
        sleep 30
        """);
    assertTrue(executable.toFile().setExecutable(true));

    CountDownLatch ready = new CountDownLatch(1);
    CountDownLatch callbackCompleted = new CountDownLatch(1);
    CountDownLatch reentrantCloseCompleted = new CountDownLatch(1);
    AtomicReference<Process> childReference = new AtomicReference<>();
    AtomicReference<ProcessCodexTransport> transportReference = new AtomicReference<>();
    AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    AtomicInteger callbackCount = new AtomicInteger();
    ProcessCodexTransport transport = new ProcessCodexTransport(executable.toString(), command -> {
      Process child = new ProcessBuilder(command).start();
      childReference.set(child);
      return child;
    });
    transportReference.set(transport);
    transport.start(new CodexTransport.Listener() {
      @Override
      public void onMessage(String message) {
        ready.countDown();
      }

      @Override
      public void onClosed(Throwable cause) {
        callbackCount.incrementAndGet();
        try {
          if (childReference.get().isAlive()) {
            throw new AssertionError("child process was alive during close callback");
          }
          Thread reentrantCloser = new Thread(() -> {
            transportReference.get().close();
            reentrantCloseCompleted.countDown();
          });
          reentrantCloser.start();
          if (!reentrantCloseCompleted.await(1, TimeUnit.SECONDS)) {
            throw new AssertionError("close callback retained the lifecycle lock");
          }
        } catch (Throwable failure) {
          callbackFailure.set(failure);
        } finally {
          callbackCompleted.countDown();
        }
      }
    });
    assertTrue(ready.await(5, TimeUnit.SECONDS));

    assertThrows(CodexProcessException.class, () -> transport.send("{\"id\":1}"));

    assertTrue(callbackCompleted.await(5, TimeUnit.SECONDS));
    assertNull(callbackFailure.get());
    assertEquals(1, callbackCount.get());
  }

  @Test
  void concurrentCloseCannotLeakProcessSpawnedByStart(@TempDir Path temporaryDirectory)
      throws Exception {
    Path executable = temporaryDirectory.resolve("slow-start-codex");
    Files.writeString(executable, """
        #!/bin/sh
        exec sleep 30
        """);
    assertTrue(executable.toFile().setExecutable(true));

    CountDownLatch spawned = new CountDownLatch(1);
    CountDownLatch releaseSpawn = new CountDownLatch(1);
    AtomicReference<Process> childReference = new AtomicReference<>();
    ProcessCodexTransport transport = new ProcessCodexTransport(executable.toString(), command -> {
      Process child = new ProcessBuilder(command).start();
      childReference.set(child);
      spawned.countDown();
      try {
        if (!releaseSpawn.await(5, TimeUnit.SECONDS)) {
          throw new IOException("Timed out waiting to release spawned process");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while holding spawned process", exception);
      }
      return child;
    });
    RecordingListener listener = new RecordingListener(0);
    Thread starter = new Thread(() -> transport.start(listener));
    CountDownLatch closeCompleted = new CountDownLatch(1);
    Thread closer = new Thread(() -> {
      transport.close();
      closeCompleted.countDown();
    });

    starter.start();
    assertTrue(spawned.await(5, TimeUnit.SECONDS));
    Process child = childReference.get();
    try {
      closer.start();
      awaitCloseAttempt(closer, closeCompleted);
      releaseSpawn.countDown();

      starter.join(5_000);
      closer.join(5_000);
      assertFalse(starter.isAlive());
      assertFalse(closer.isAlive());
      child.onExit().get(5, TimeUnit.SECONDS);
      assertFalse(child.isAlive());
      assertFalse(transport.isAlive());
      assertEquals(1, listener.closedCount);
    } finally {
      releaseSpawn.countDown();
      child.destroyForcibly();
    }
  }

  @Test
  void writeFailureTerminatesProcessAndNotifiesListenerOnce(@TempDir Path temporaryDirectory)
      throws Exception {
    Path pidFile = temporaryDirectory.resolve("child.pid");
    Path executable = temporaryDirectory.resolve("broken-pipe-codex");
    Files.writeString(executable, """
        #!/bin/sh
        printf '%%s' "$$" > '%s'
        exec 0<&-
        printf 'ready\\n'
        sleep 30
        """.formatted(pidFile));
    assertTrue(executable.toFile().setExecutable(true));

    RecordingListener listener = new RecordingListener(1);
    ProcessCodexTransport transport = new ProcessCodexTransport(executable.toString());
    transport.start(listener);
    assertTrue(listener.messagesReceived.await(5, TimeUnit.SECONDS));
    long childPid = Long.parseLong(Files.readString(pidFile));
    ProcessHandle child = ProcessHandle.of(childPid).orElseThrow();

    CodexProcessException failure = assertThrows(CodexProcessException.class,
        () -> transport.send("{\"id\":1}"));

    assertTrue(listener.closed.await(5, TimeUnit.SECONDS));
    child.onExit().get(5, TimeUnit.SECONDS);
    assertFalse(child.isAlive());
    assertFalse(transport.isAlive());
    assertEquals(1, listener.closedCount);
    assertSame(failure, listener.closeCause);

    transport.close();
    assertEquals(1, listener.closedCount);
  }

  @Test
  void relaysStdoutLinesWhileKeepingStderrDiagnosticOnly(@TempDir Path temporaryDirectory)
      throws Exception {
    Path executable = temporaryDirectory.resolve("fake-codex");
    Files.writeString(executable, """
        #!/bin/sh
        printf 'ready\\n'
        printf 'diagnostic only\\n' >&2
        while IFS= read -r line; do
          printf '%s\\n' \"$line\"
        done
        """);
    assertTrue(executable.toFile().setExecutable(true));

    RecordingListener listener = new RecordingListener(2);
    try (ProcessCodexTransport transport = new ProcessCodexTransport(executable.toString())) {
      transport.start(listener);
      transport.send("{\"id\":1,\"method\":\"ping\"}");

      assertTrue(listener.messagesReceived.await(5, TimeUnit.SECONDS));
      assertEquals(List.of("ready", "{\"id\":1,\"method\":\"ping\"}"), listener.messages);
      assertTrue(transport.isAlive());
      assertEquals(0, listener.closedCount);

      transport.close();
      transport.close();

      assertFalse(transport.isAlive());
      assertEquals(1, listener.closedCount);
    }
  }

  private static void awaitCloseAttempt(Thread closer, CountDownLatch closeCompleted)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (closeCompleted.getCount() != 0
        && closer.getState() != Thread.State.BLOCKED
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertTrue(closeCompleted.getCount() == 0 || closer.getState() == Thread.State.BLOCKED,
        "close thread did not reach the transport lifecycle");
  }

  private static final class RecordingListener implements CodexTransport.Listener {
    private final CountDownLatch messagesReceived;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private volatile int closedCount;
    private volatile Throwable closeCause;

    private RecordingListener(int expectedMessages) {
      this.messagesReceived = new CountDownLatch(expectedMessages);
    }

    @Override
    public void onMessage(String message) {
      messages.add(message);
      messagesReceived.countDown();
    }

    @Override
    public void onClosed(Throwable cause) {
      closeCause = cause;
      closedCount++;
      closed.countDown();
    }
  }
}
