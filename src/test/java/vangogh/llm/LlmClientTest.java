package vangogh.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openai.core.Sleeper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.vavr.control.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vangogh.error.FetchError;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transport-level tests against a local HTTP server: no provider, no key, no
 * network. What matters here is the contract the recommendation service leans
 * on — the SDK sends OpenAI-compatible chat completions, retries 429/5xx but
 * not 4xx, and every failure arrives as a {@link FetchError} value.
 *
 * <p>The SDK's retry backoff is replaced with a recording sleeper, so the
 * suite never waits.
 */
class LlmClientTest {

    /** Records retry delays instead of sleeping; `mvn test` stays fast. */
    static class RecordingSleeper implements Sleeper {
        final List<Duration> sleeps = new CopyOnWriteArrayList<>();

        @Override
        public void sleep(Duration duration) {
            sleeps.add(duration);
        }

        @Override
        public CompletableFuture<Void> sleepAsync(Duration duration) {
            sleeps.add(duration);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
        }
    }

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static LlmConfig config(String baseUrl) {
        return new LlmConfig(baseUrl, "test-key", "test-model", 10, 5, 0.3);
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private String serve(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String replyBody(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject root = new JsonObject();
        root.add("choices", choices);
        return root.toString();
    }

    @Test
    void aSuccessfulCallReturnsTheAssistantTextAndSendsTheExpectedRequest() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> auth = new CopyOnWriteArrayList<>();
        String baseUrl = serve(exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            answer(exchange, 200, replyBody("Inception | 2010"));
        });

        Either<FetchError, String> result = new LlmClient(new RecordingSleeper())
                .complete(config(baseUrl), "system text", "user text");

        assertTrue(result.isRight());
        assertEquals("Inception | 2010", result.get());

        assertEquals("Bearer test-key", auth.get(0));
        JsonObject body = JsonParser.parseString(bodies.get(0)).getAsJsonObject();
        assertEquals("test-model", body.get("model").getAsString());
        // The temperature comes from the config, not a constant: the Settings
        // page's value is what the model receives.
        assertEquals(0.3, body.get("temperature").getAsDouble());
        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("system text", messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals("user text", messages.get(1).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void a429IsRetriedAndRetryAfterDrivesTheDelay() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl = serve(exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                answer(exchange, 429, "{\"error\":\"rate limited\"}");
            } else {
                answer(exchange, 200, replyBody("ok"));
            }
        });

        RecordingSleeper sleeper = new RecordingSleeper();
        Either<FetchError, String> result = new LlmClient(sleeper)
                .complete(config(baseUrl), "s", "u");

        assertTrue(result.isRight(), () -> String.valueOf(result.getLeft()));
        assertEquals("ok", result.get());
        assertEquals(2, calls.get());
        assertEquals(1, sleeper.sleeps.size());
        assertTrue(sleeper.sleeps.get(0).toMillis() >= 1000,
                "expected Retry-After to drive the delay, got " + sleeper.sleeps.get(0));
    }

    @Test
    void a500IsRetriedToo() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl = serve(exchange ->
                answer(exchange, calls.incrementAndGet() == 1 ? 500 : 200,
                        calls.get() == 1 ? "boom" : replyBody("ok")));

        Either<FetchError, String> result = new LlmClient(new RecordingSleeper())
                .complete(config(baseUrl), "s", "u");

        assertTrue(result.isRight(), () -> String.valueOf(result.getLeft()));
        assertEquals(2, calls.get());
    }

    @Test
    void a401IsReturnedAsAValueWithoutRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl = serve(exchange -> {
            calls.incrementAndGet();
            answer(exchange, 401, "{\"error\":\"bad key\"}");
        });

        RecordingSleeper sleeper = new RecordingSleeper();
        Either<FetchError, String> result = new LlmClient(sleeper)
                .complete(config(baseUrl), "s", "u");

        assertTrue(result.isLeft());
        FetchError.HttpStatus error = assertInstanceOf(FetchError.HttpStatus.class, result.getLeft());
        assertEquals(401, error.status());
        assertEquals("LLM", error.api());
        assertEquals(1, calls.get());
        assertTrue(sleeper.sleeps.isEmpty());
    }

    @Test
    void anExhausted429IsTheFriendlyRateLimitMessage() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl = serve(exchange -> {
            calls.incrementAndGet();
            answer(exchange, 429, "{\"error\":\"rate limited\"}");
        });

        RecordingSleeper sleeper = new RecordingSleeper();
        Either<FetchError, String> result = new LlmClient(sleeper)
                .complete(config(baseUrl), "s", "u");

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.BadInput.class, result.getLeft());
        assertTrue(FetchError.uiMessage(result.getLeft()).contains("rate-limited"),
                FetchError.uiMessage(result.getLeft()));
        assertEquals(LlmClient.MAX_RETRIES + 1, calls.get());
        assertEquals(LlmClient.MAX_RETRIES, sleeper.sleeps.size());
    }

    @Test
    void aTransportFailureIsATransportError() {
        // Port 1 is never bound: connecting is refused immediately. (Binding a
        // port and closing it can leave the socket half-closed on macOS, where
        // each attempt then waits out OkHttp's 10 s connect timeout.)
        Either<FetchError, String> result = new LlmClient(new RecordingSleeper())
                .complete(config("http://127.0.0.1:1/v1"), "s", "u");

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Transport.class, result.getLeft());
    }

    @Test
    void anEmptyChoicesArrayIsAPayloadError() throws Exception {
        String baseUrl = serve(exchange -> answer(exchange, 200, "{\"choices\":[]}"));

        Either<FetchError, String> result = new LlmClient(new RecordingSleeper())
                .complete(config(baseUrl), "s", "u");

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Payload.class, result.getLeft());
    }

    @Test
    void aMissingContentFieldIsAPayloadError() throws Exception {
        String baseUrl = serve(exchange ->
                answer(exchange, 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\"}}]}"));

        Either<FetchError, String> result = new LlmClient(new RecordingSleeper())
                .complete(config(baseUrl), "s", "u");

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Payload.class, result.getLeft());
    }

    @Test
    void aBodyThatIsNotChatCompletionsIsAPayloadError() throws Exception {
        String baseUrl = serve(exchange -> answer(exchange, 200, "{\"nope\":true}"));

        Either<FetchError, String> result = new LlmClient(new RecordingSleeper())
                .complete(config(baseUrl), "s", "u");

        assertTrue(result.isLeft());
        assertInstanceOf(FetchError.Payload.class, result.getLeft());
    }
}
