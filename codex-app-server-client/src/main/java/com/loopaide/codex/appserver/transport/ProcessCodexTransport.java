package com.loopaide.codex.appserver.transport;

import com.loopaide.codex.appserver.CodexProcessException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** JSONL transport backed by a {@code codex app-server --stdio} child process. */
public final class ProcessCodexTransport implements CodexTransport {
  private static final Logger LOGGER = Logger.getLogger(ProcessCodexTransport.class.getName());
  private static final Duration GRACEFUL_CLOSE_TIMEOUT = Duration.ofSeconds(1);

  private final String codexExecutable;
  private final ProcessStarter processStarter;
  private final Object lifecycleLock = new Object();
  private final ExecutorService readers = Executors.newFixedThreadPool(2, runnable -> {
    Thread thread = new Thread(runnable, "codex-app-server-reader");
    thread.setDaemon(true);
    return thread;
  });
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean closedNotified = new AtomicBoolean();

  private volatile Process process;
  private volatile BufferedWriter writer;
  private volatile Listener listener;

  public ProcessCodexTransport(String codexExecutable) {
    this(codexExecutable, command -> new ProcessBuilder(command).start());
  }

  ProcessCodexTransport(String codexExecutable, ProcessStarter processStarter) {
    this.codexExecutable = Objects.requireNonNull(codexExecutable, "codexExecutable");
    this.processStarter = Objects.requireNonNull(processStarter, "processStarter");
  }

  @Override
  public void start(Listener listener) {
    Objects.requireNonNull(listener, "listener");
    CodexProcessException failure = null;
    Listener listenerToNotify = null;
    synchronized (lifecycleLock) {
      if (!started.compareAndSet(false, true)) {
        throw new IllegalStateException("Transport has already been started");
      }
      if (closed.get()) {
        throw new CodexProcessException("Transport is closed");
      }

      this.listener = listener;
      try {
        process = processStarter.start(List.of(codexExecutable, "app-server", "--stdio"));
        writer = new BufferedWriter(new OutputStreamWriter(
            process.getOutputStream(), StandardCharsets.UTF_8));
        readers.execute(this::readStdout);
        readers.execute(this::readStderr);
      } catch (IOException exception) {
        failure = new CodexProcessException("Unable to start Codex app-server", exception);
        listenerToNotify = closeLocked();
      }
    }
    if (listenerToNotify != null) {
      listenerToNotify.onClosed(failure);
    }
    if (failure != null) {
      throw failure;
    }
  }

  @Override
  public void send(String message) {
    Objects.requireNonNull(message, "message");
    BufferedWriter currentWriter = writer;
    if (!isAlive() || currentWriter == null) {
      throw new CodexProcessException("Codex app-server process is not running");
    }
    CodexProcessException failure = null;
    synchronized (currentWriter) {
      try {
        currentWriter.write(message);
        currentWriter.newLine();
        currentWriter.flush();
      } catch (IOException exception) {
        failure = new CodexProcessException("Unable to write to Codex app-server", exception);
      }
    }
    if (failure != null) {
      close(failure);
      throw failure;
    }
  }

  @Override
  public boolean isAlive() {
    Process currentProcess = process;
    return !closed.get() && currentProcess != null && currentProcess.isAlive();
  }

  @Override
  public void close() {
    close(null);
  }

  private void close(Throwable cause) {
    Listener listenerToNotify;
    synchronized (lifecycleLock) {
      listenerToNotify = closeLocked();
    }
    if (listenerToNotify != null) {
      listenerToNotify.onClosed(cause);
    }
  }

  private Listener closeLocked() {
    if (!closed.compareAndSet(false, true)) {
      return null;
    }
    Listener listenerToNotify = claimClosedListener();
    closeWriter();
    Process currentProcess = process;
    if (currentProcess != null && currentProcess.isAlive()) {
      currentProcess.destroy();
      try {
        if (!currentProcess.waitFor(GRACEFUL_CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
          currentProcess.destroyForcibly();
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        currentProcess.destroyForcibly();
      }
    }
    readers.shutdownNow();
    return listenerToNotify;
  }

  private void readStdout() {
    Process currentProcess = process;
    if (currentProcess == null) {
      return;
    }
    try (BufferedReader stdout = new BufferedReader(new InputStreamReader(
        currentProcess.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = stdout.readLine()) != null) {
        listener.onMessage(line);
      }
      close(null);
    } catch (IOException exception) {
      if (!closed.get()) {
        close(new CodexProcessException("Unable to read Codex app-server stdout", exception));
      }
    }
  }

  private void readStderr() {
    Process currentProcess = process;
    if (currentProcess == null) {
      return;
    }
    try (BufferedReader stderr = new BufferedReader(new InputStreamReader(
        currentProcess.getErrorStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = stderr.readLine()) != null) {
        LOGGER.fine("codex app-server stderr: " + line);
      }
    } catch (IOException exception) {
      if (!closed.get()) {
        LOGGER.log(Level.FINE, "Unable to read Codex app-server stderr", exception);
      }
    }
  }

  private void closeWriter() {
    BufferedWriter currentWriter = writer;
    if (currentWriter == null) {
      return;
    }
    synchronized (currentWriter) {
      try {
        currentWriter.close();
      } catch (IOException exception) {
        LOGGER.log(Level.FINE, "Unable to close Codex app-server stdin", exception);
      }
    }
  }

  private Listener claimClosedListener() {
    return closedNotified.compareAndSet(false, true) ? listener : null;
  }

  @FunctionalInterface
  interface ProcessStarter {
    Process start(List<String> command) throws IOException;
  }
}
