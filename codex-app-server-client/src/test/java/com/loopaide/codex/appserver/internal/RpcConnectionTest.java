package com.loopaide.codex.appserver.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopaide.codex.appserver.CodexProcessException;
import com.loopaide.codex.appserver.CodexProtocolException;
import com.loopaide.codex.appserver.protocol.v2.RpcCodec;
import com.loopaide.codex.appserver.protocol.v2.RpcFailure;
import com.loopaide.codex.appserver.protocol.v2.RpcNotification;
import com.loopaide.codex.appserver.transport.CodexTransport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RpcConnectionTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void correlatesOutOfOrderResponsesToTheirOriginalRequests() {
    InMemoryTransport transport = new InMemoryTransport();
    try (RpcConnection connection = new RpcConnection(transport)) {
      var first = connection.request("first", JSON.createObjectNode(), Duration.ofSeconds(1));
      var second = connection.request("second", JSON.createObjectNode(), Duration.ofSeconds(1));

      transport.receive("{\"id\":2,\"result\":{\"value\":\"second\"}}");
      transport.receive("{\"id\":1,\"result\":{\"value\":\"first\"}}");

      assertEquals("first", first.join().path("value").asText());
      assertEquals("second", second.join().path("value").asText());
      assertEquals("first", sent(transport, 0).path("method").asText());
      assertEquals("second", sent(transport, 1).path("method").asText());
    }
  }

  @Test
  void failsRequestWhenItsTimeoutElapses() {
    InMemoryTransport transport = new InMemoryTransport();
    try (RpcConnection connection = new RpcConnection(transport)) {
      var future = connection.request("slow", JSON.createObjectNode(), Duration.ofMillis(20));

      CompletionException failure = assertThrows(CompletionException.class, future::join);

      assertInstanceOf(CodexProtocolException.class, failure.getCause());
      assertTrue(failure.getCause().getMessage().contains("timed out"));
    }
  }

  @Test
  void exposesServerErrorAsProtocolFailure() {
    InMemoryTransport transport = new InMemoryTransport();
    try (RpcConnection connection = new RpcConnection(transport)) {
      var future = connection.request("thread/start", JSON.createObjectNode(), Duration.ofSeconds(1));
      transport.receive("{\"id\":1,\"error\":{\"code\":-32001,\"message\":\"bad request\"}}");

      CompletionException failure = assertThrows(CompletionException.class, future::join);

      CodexProtocolException exception = assertInstanceOf(CodexProtocolException.class, failure.getCause());
      assertTrue(exception.getMessage().contains("bad request"));
    }
  }

  @Test
  void failsPendingRequestImmediatelyForMalformedResponseWithItsLiveId() {
    InMemoryTransport transport = new InMemoryTransport();
    try (RpcConnection connection = new RpcConnection(transport)) {
      CompletableFuture<JsonNode> future = connection.request(
          "thread/start", JSON.createObjectNode(), Duration.ofSeconds(5));

      transport.receive("{\"id\":1,\"result\":{},\"error\":{\"code\":-32000,\"message\":\"ambiguous\"}}");

      CompletionException failure = assertThrows(CompletionException.class,
          () -> future.orTimeout(300, TimeUnit.MILLISECONDS).join());
      CodexProtocolException protocolFailure = assertInstanceOf(
          CodexProtocolException.class, failure.getCause());
      assertTrue(protocolFailure.getMessage().contains("Malformed"));
    }
  }

  @Test
  void failsPendingRequestImmediatelyWhenResponsePayloadIsMissing() {
    InMemoryTransport transport = new InMemoryTransport();
    try (RpcConnection connection = new RpcConnection(transport)) {
      CompletableFuture<JsonNode> future = connection.request(
          "thread/start", JSON.createObjectNode(), Duration.ofSeconds(5));

      transport.receive("{\"id\":1,\"params\":{}}");

      CompletionException failure = assertThrows(CompletionException.class,
          () -> future.orTimeout(300, TimeUnit.MILLISECONDS).join());
      CodexProtocolException protocolFailure = assertInstanceOf(
          CodexProtocolException.class, failure.getCause());
      assertTrue(protocolFailure.getMessage().contains("Malformed"));
    }
  }

  @Test
  void dispatchesNotificationsInWireArrivalOrder() throws Exception {
    InMemoryTransport transport = new InMemoryTransport();
    List<String> methods = new CopyOnWriteArrayList<>();
    CountDownLatch delivered = new CountDownLatch(2);
    try (RpcConnection connection = new RpcConnection(transport, notification -> {
      methods.add(notification.method());
      delivered.countDown();
    })) {
      transport.receive("{\"method\":\"turn/started\",\"params\":{}}");
      transport.receive("{\"method\":\"item/started\",\"params\":{}}");

      assertTrue(delivered.await(1, TimeUnit.SECONDS));
      assertEquals(List.of("turn/started", "item/started"), methods);
    }
  }

  @Test
  void repliesWithExplicitErrorForUnhandledServerRequest() {
    InMemoryTransport transport = new InMemoryTransport();
    try (RpcConnection connection = new RpcConnection(transport)) {
      transport.receive("{\"id\":77,\"method\":\"item/approval\",\"params\":{}}");

      awaitSentMessage(transport);
      RpcFailure reply = assertInstanceOf(RpcFailure.class,
          new RpcCodec().decode(transport.sentMessages.get(0)));
      assertEquals(77, reply.id().asInt());
      assertEquals(-32601, reply.error().code());
      assertTrue(reply.error().message().contains("item/approval"));
    }
  }

  @Test
  void handlesServerRequestOffTheTransportReaderAndPreservesItsId() throws Exception {
    InMemoryTransport transport = new InMemoryTransport();
    CountDownLatch handled = new CountDownLatch(1);
    try (RpcConnection connection = new RpcConnection(transport, notification -> { }, request -> {
      assertEquals("codex-app-server-events", Thread.currentThread().getName());
      assertEquals("item/approval", request.method());
      handled.countDown();
      return JSON.createObjectNode().put("decision", "declined");
    })) {
      transport.receive("{\"id\":77,\"method\":\"item/approval\",\"params\":{\"itemId\":\"item-1\"}}");

      assertTrue(handled.await(1, TimeUnit.SECONDS));
      awaitSentMessage(transport);
      JsonNode reply = sent(transport, 0);
      assertEquals(77, reply.path("id").asInt());
      assertEquals("declined", reply.path("result").path("decision").asText());
    }
  }

  @Test
  void failsPendingRequestsWhenTransportCloses() {
    InMemoryTransport transport = new InMemoryTransport();
    try (RpcConnection connection = new RpcConnection(transport)) {
      var future = connection.request("thread/start", JSON.createObjectNode(), Duration.ofSeconds(1));
      transport.fail(new CodexProcessException("process exited"));

      CompletionException failure = assertThrows(CompletionException.class, future::join);
      assertInstanceOf(CodexProcessException.class, failure.getCause());
    }
  }

  @Test
  void closesTransportOnlyOnce() {
    InMemoryTransport transport = new InMemoryTransport();
    RpcConnection connection = new RpcConnection(transport);

    connection.close();
    connection.close();

    assertEquals(1, transport.closeCount);
  }

  @Test
  void returnsAFailedFutureWhenCloseRacesTimeoutScheduling() throws Exception {
    InMemoryTransport transport = new InMemoryTransport();
    GatedScheduler scheduler = new GatedScheduler();
    AtomicReference<java.util.concurrent.CompletableFuture<JsonNode>> request = new AtomicReference<>();
    AtomicReference<Throwable> escapedFailure = new AtomicReference<>();
    try (RpcConnection connection = new RpcConnection(transport, notification -> { }, null, failure -> { }, scheduler)) {
      Thread requester = new Thread(() -> {
        try {
          request.set(connection.request("thread/start", JSON.createObjectNode(), Duration.ofSeconds(1)));
        } catch (Throwable failure) {
          escapedFailure.set(failure);
        }
      });
      requester.start();
      assertTrue(scheduler.scheduleEntered.await(1, TimeUnit.SECONDS));

      Thread closer = new Thread(connection::close);
      closer.start();
      assertEquals(0, transport.closeCount);
      scheduler.allowSchedule.countDown();
      requester.join(TimeUnit.SECONDS.toMillis(1));
      closer.join(TimeUnit.SECONDS.toMillis(1));

      assertEquals(null, escapedFailure.get());
      assertTrue(request.get().isCompletedExceptionally());
      CompletionException failure = assertThrows(CompletionException.class, () -> request.get().join());
      assertInstanceOf(CodexProcessException.class, failure.getCause());
    }
  }

  @Test
  void closeTearsDownTransportAndReleasesAStuckSendBeforeReturning() throws Exception {
    CloseReleasesSendTransport transport = new CloseReleasesSendTransport();
    RpcConnection connection = new RpcConnection(transport);
    AtomicReference<CompletableFuture<JsonNode>> request = new AtomicReference<>();
    Thread requester = new Thread(() -> request.set(
        connection.request("thread/start", JSON.createObjectNode(), Duration.ofSeconds(1))));
    try {
      requester.start();
      assertTrue(transport.sendEntered.await(1, TimeUnit.SECONDS));

      connection.close();

      assertEquals(0, transport.closed.getCount(), "close returned before transport teardown");
      requester.join(TimeUnit.SECONDS.toMillis(1));
      assertFalse(requester.isAlive(), "transport close did not release the stuck send");
      assertTrue(request.get().isCompletedExceptionally());
    } finally {
      transport.forceRelease();
      requester.join(TimeUnit.SECONDS.toMillis(1));
    }
  }

  @Test
  void ignoresNotificationArrivingAfterDispatcherShutdown() {
    InMemoryTransport transport = new InMemoryTransport();
    RpcConnection connection = new RpcConnection(transport);
    connection.close();

    assertDoesNotThrow(() -> transport.receive("{\"method\":\"warning\",\"params\":{}}"));
  }

  private static JsonNode sent(InMemoryTransport transport, int index) {
    try {
      return JSON.readTree(transport.sentMessages.get(index));
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private static void awaitSentMessage(InMemoryTransport transport) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (transport.sentMessages.isEmpty() && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertTrue(!transport.sentMessages.isEmpty(), "server request response was not sent");
  }

  private static final class InMemoryTransport implements CodexTransport {
    private final List<String> sentMessages = new ArrayList<>();
    private Listener listener;
    private boolean alive = true;
    private int closeCount;

    @Override
    public void start(Listener listener) {
      this.listener = listener;
    }

    @Override
    public void send(String message) {
      sentMessages.add(message);
    }

    @Override
    public boolean isAlive() {
      return alive;
    }

    @Override
    public void close() {
      if (alive) {
        alive = false;
        closeCount++;
        listener.onClosed(null);
      }
    }

    private void receive(String message) {
      listener.onMessage(message);
    }

    private void fail(Throwable cause) {
      alive = false;
      listener.onClosed(cause);
    }
  }

  private static final class GatedScheduler extends ScheduledThreadPoolExecutor {
    private final CountDownLatch scheduleEntered = new CountDownLatch(1);
    private final CountDownLatch allowSchedule = new CountDownLatch(1);

    private GatedScheduler() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      scheduleEntered.countDown();
      try {
        if (!allowSchedule.await(1, TimeUnit.SECONDS)) {
          throw new AssertionError("test did not release scheduler");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError(exception);
      }
      return super.schedule(command, delay, unit);
    }
  }

  private static final class CloseReleasesSendTransport implements CodexTransport {
    private final CountDownLatch sendEntered = new CountDownLatch(1);
    private final CountDownLatch releaseSend = new CountDownLatch(1);
    private final CountDownLatch closed = new CountDownLatch(1);
    private Listener listener;

    @Override
    public void start(Listener listener) {
      this.listener = listener;
    }

    @Override
    public void send(String message) {
      sendEntered.countDown();
      try {
        if (!releaseSend.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("test did not release send");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError(exception);
      }
      throw new CodexProcessException("transport closed during send");
    }

    @Override
    public boolean isAlive() {
      return closed.getCount() != 0;
    }

    @Override
    public void close() {
      closed.countDown();
      releaseSend.countDown();
      listener.onClosed(null);
    }

    private void forceRelease() {
      releaseSend.countDown();
    }
  }
}
