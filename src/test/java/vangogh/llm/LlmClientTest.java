package vangogh.llm;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.vavr.control.Try;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vangogh.net.ApiResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transport-level tests against a local HTTP server: no provider, no key, no
 * network. What matters here is the contract the recommendation service leans
 * on — statuses are values, 429/5xx retry, 4xx does not, and the request body
 * really is OpenAI-compatible chat completions.
 */
class LlmClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static LlmConfig config(String baseUrl) {
        return new LlmConfig(baseUrl, "test-key", "test-model", 10, 5);
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
        var choices = new com.google.gson.JsonArray();
        choices.add(choice);
        JsonObject root = new JsonObject();
        root.add("choices", choices);
        return root.toString();
    }

    @Test
    void aSuccessfulCallReturnsTheBodyAndSendsTheExpectedRequest() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> auth = new CopyOnWriteArrayList<>();
        String baseUrl = serve(exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            answer(exchange, 200, replyBody("Inception | 2010"));
        });

        Try<ApiResponse> result = new LlmClient().complete(
                config(baseUrl), "system text", "user text");

        assertTrue(result.isSuccess());
        assertEquals(200, result.get().statusCode);
        assertEquals("Inception | 2010", LlmClient.contentOf(result.get().body));

        assertEquals("Bearer test-key", auth.get(0));
        JsonObject body = com.google.gson.JsonParser.parseString(bodies.get(0)).getAsJsonObject();
        assertEquals("test-model", body.get("model").getAsString());
        var messages = body.getAsJsonArray("messages");
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("system text", messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("user text", messages.get(1).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void a429IsRetriedWithBackoff() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl = serve(exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                answer(exchange, 429, "{\"error\":\"rate limited\"}");
            } else {
                answer(exchange, 200, replyBody("ok"));
            }
        });

        // One retry slot, zero milliseconds: the test never actually waits.
        ApiResponse response = new LlmClient(0).complete(
                config(baseUrl), "s", "u").get();

        assertEquals(200, response.statusCode);
        assertEquals(2, calls.get());
    }

    @Test
    void a500IsRetriedToo() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl = serve(exchange ->
                answer(exchange, calls.incrementAndGet() == 1 ? 500 : 200,
                        calls.get() == 1 ? "boom" : replyBody("ok")));

        assertEquals(200, new LlmClient(0).complete(config(baseUrl), "s", "u").get().statusCode);
        assertEquals(2, calls.get());
    }

    @Test
    void a401IsReturnedAsAValueWithoutRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl = serve(exchange -> {
            calls.incrementAndGet();
            answer(exchange, 401, "{\"error\":\"bad key\"}");
        });

        ApiResponse response = new LlmClient(0).complete(config(baseUrl), "s", "u").get();

        assertEquals(401, response.statusCode);
        assertFalse(response.isSuccess());
        assertEquals(1, calls.get());
    }

    @Test
    void aTransportFailureIsATryFailure() throws Exception {
        // Bind a port, then close it: connecting there is refused.
        HttpServer closed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = closed.getAddress().getPort();
        closed.stop(0);

        Try<ApiResponse> result = new LlmClient(0).complete(
                config("http://127.0.0.1:" + port + "/v1"), "s", "u");

        assertTrue(result.isFailure());
    }

    @Test
    void contentOfReadsTheAssistantMessage() {
        assertEquals("A | 1", LlmClient.contentOf(replyBody("A | 1")));
    }

    @Test
    void contentOfRejectsAnEmptyChoicesArray() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> LlmClient.contentOf("{\"choices\":[]}"));
        assertTrue(error.getMessage().contains("no choices"));
    }

    @Test
    void contentOfRejectsAMissingMessage() {
        assertThrows(IllegalStateException.class,
                () -> LlmClient.contentOf("{\"choices\":[{\"text\":\"hi\"}]}"));
    }
}
