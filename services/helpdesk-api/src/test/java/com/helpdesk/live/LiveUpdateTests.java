package com.helpdesk.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.helpdesk.IntegrationTest;
import com.helpdesk.MutableClock;
import com.helpdesk.TestUsers;
import com.helpdesk.ticket.Priority;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Live updates over a real WebSocket, through the real STOMP relay (RabbitMQ). The client
 * authenticates its handshake with a bearer token, as a script or a second service would;
 * browsers use their session cookie instead.
 */
class LiveUpdateTests extends IntegrationTest {

    @LocalServerPort
    int port;

    private WebSocketStompClient client;

    @BeforeEach
    void setUp() {
        clock.set(MutableClock.MONDAY_10AM_LONDON);
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler(); // for receipts and heartbeats
        scheduler.initialize();
        client.setTaskScheduler(scheduler);
    }

    @AfterEach
    void tearDown() {
        client.stop();
    }

    @Test
    void aChangeMadeByAnyoneIsPushedToEverySubscribedBrowser() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        long queue = newQueue(ann);
        StompSession session = connect(TestUsers.agent("watcher"));
        BlockingQueue<Map<String, Object>> received = subscribe(session, LiveUpdateListener.TICKETS_TOPIC);

        long id = createTicket(ann, queue, Priority.HIGH);
        publishOutbox(); // relay -> RabbitMQ -> LiveUpdateListener -> STOMP relay -> this socket

        await().atMost(Duration.ofSeconds(10)).until(() -> received.stream().anyMatch(update ->
                ((Number) update.get("ticketId")).longValue() == id && "ticket.created".equals(update.get("type"))));
        session.disconnect();
    }

    @Test
    void agentsMaySubscribeToTheirOwnTopicButNotToSomeoneElses() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        long annId = provision(ann);
        long bobId = provision(TestUsers.agent("bob"));

        StompSession own = connect(ann);
        subscribe(own, AgentTopics.topicOf(annId)); // receipt arrives: allowed
        own.disconnect();

        ErrorCatcher errors = new ErrorCatcher();
        StompSession snooping = connect(ann, errors);
        snooping.subscribe(AgentTopics.topicOf(bobId), new MapFrames(new LinkedBlockingQueue<>()));
        // The server answers with a STOMP ERROR frame and closes the connection. Its message is
        // deliberately generic ("Failed to send message to ...clientInboundChannel"): no details leak.
        assertThat(errors.received.await(10, TimeUnit.SECONDS)).as("the server answered with an ERROR frame").isTrue();
        assertThat(errors.message).isNotBlank();
        await().atMost(Duration.ofSeconds(5)).until(() -> !snooping.isConnected());
    }

    @Test
    void theHandshakeNeedsAValidToken() {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setBearerAuth("not-a-real-token");
        assertThatThrownBy(() -> client.connectAsync(url(), headers, new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS))
                .hasMessageContaining("401");
    }

    // ---- helpers -----------------------------------------------------------------------

    private StompSession connect(TestUsers.User user) throws Exception {
        return connect(user, new StompSessionHandlerAdapter() {});
    }

    private StompSession connect(TestUsers.User user, StompSessionHandlerAdapter handler) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setBearerAuth(user.bearerToken());
        StompSession session = client.connectAsync(url(), headers, handler).get(10, TimeUnit.SECONDS);
        session.setAutoReceipt(true);
        return session;
    }

    /** Subscribes and waits until the broker confirms (a RECEIPT frame), so no message is missed. */
    private BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination) throws Exception {
        BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        CountDownLatch subscribed = new CountDownLatch(1);
        session.subscribe(destination, new MapFrames(received)).addReceiptTask(subscribed::countDown);
        assertThat(subscribed.await(10, TimeUnit.SECONDS)).as("subscribed to " + destination).isTrue();
        return received;
    }

    private String url() {
        return "ws://localhost:" + port + "/ws";
    }

    /** Collects JSON frames as maps. */
    private record MapFrames(BlockingQueue<Map<String, Object>> sink) implements StompFrameHandler {

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return Map.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleFrame(StompHeaders headers, Object payload) {
            sink.add((Map<String, Object>) payload);
        }
    }

    /** Remembers the first STOMP ERROR frame the server sends. */
    private static final class ErrorCatcher extends StompSessionHandlerAdapter {

        final CountDownLatch received = new CountDownLatch(1);
        volatile String message;

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            message = headers.getFirst("message");
            received.countDown();
        }
    }
}
