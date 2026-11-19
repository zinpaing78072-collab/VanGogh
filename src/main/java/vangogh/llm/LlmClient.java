package vangogh.llm;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.Sleeper;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.RateLimitException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.vavr.control.Either;
import vangogh.error.FetchError;

import java.time.Duration;
import java.util.Optional;

/**
 * OpenAI-compatible chat-completions client built on the official
 * {@code com.openai:openai-java} SDK. The SDK owns transport, JSON, auth and
 * retries (exponential backoff, {@code Retry-After}); this class only maps its
 * typed errors onto {@link FetchError} values and hands the assistant text to
 * the recommendation pipeline.
 *
 * <p>The base URL comes from {@link LlmConfig}, so any compatible endpoint
 * works — the SDK is not bound to api.openai.com. Structured outputs are still
 * deliberately unused: many compatible servers lack them, and the contract is
 * plain text ({@code title | disambiguator} per line).
 *
 * <p>Failures are values, never exceptions. A final 429 becomes the friendly
 * rate-limit message the page shows; other statuses carry the HTTP code and a
 * body excerpt; IO failures are {@link FetchError.Transport}; an unreadable
 * 2xx reply is {@link FetchError.Payload}.
 *
 * <p>{@code InterruptedException} is re-flagged at this boundary: the SDK wraps
 * it in an IO exception, and vavr treats interrupts as fatal, so the flag is
 * restored before the failure travels on as a value.
 */
public class LlmClient {

    /** The SDK retries 429/5xx itself; four retries cover the free tier's bursts. */
    static final int MAX_RETRIES = 4;

    /** Free-tier models need some invention; the prompt pins the format, not the choice. */
    private static final double TEMPERATURE = 0.7;

    private static final int BODY_EXCERPT = 300;

    /** Null means "let the SDK use its own sleeper"; tests inject a no-wait one. */
    private final Sleeper sleeper;

    private Cached cached;

    public LlmClient() {
        this(null);
    }

    /** Test seam: a no-op sleeper keeps the SDK's retry backoff out of `mvn test`. */
    LlmClient(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    /**
     * One chat-completions request. The assistant text is the {@code Right};
     * every expected failure is a {@code Left} the caller can render.
     */
    public Either<FetchError, String> complete(LlmConfig config, String system, String user) {
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(config.model())
                .temperature(TEMPERATURE)
                .addSystemMessage(system)
                .addUserMessage(user)
                .build();

        try {
            ChatCompletion completion = clientFor(config).chat().completions().create(params);
            return contentOf(completion)
                    .<Either<FetchError, String>>map(Either::right)
                    .orElseGet(() -> Either.left(new FetchError.Payload(
                            "LLM", "chat completions",
                            new IllegalStateException("reply has no content"))));
        } catch (RateLimitException e) {
            // The free tier's most common answer. Name it in demo language
            // instead of leaking a raw HTTP status onto the card grid.
            return Either.left(new FetchError.BadInput(
                    "AI recommendations", "rate-limited — try again in a minute"));
        } catch (OpenAIServiceException e) {
            return Either.left(new FetchError.HttpStatus(
                    "LLM", e.statusCode(), excerpt(e.body())));
        } catch (OpenAIIoException e) {
            restoreInterrupt(e);
            return Either.left(new FetchError.Transport("LLM request", e));
        } catch (OpenAIException e) {
            return Either.left(new FetchError.Payload("LLM", "chat completions", e));
        } catch (RuntimeException e) {
            restoreInterrupt(e);
            return Either.left(new FetchError.Transport("LLM request", e));
        }
    }

    /**
     * The first assistant text, if the reply has one. Package-private so the
     * transport tests can pin the empty-choices and null-content cases.
     */
    static Optional<String> contentOf(ChatCompletion completion) {
        if (completion.choices().isEmpty()) {
            return Optional.empty();
        }
        return completion.choices().get(0).message().content();
    }

    /**
     * One SDK client per distinct config. Rebuilding per call would leak an
     * OkHttp connection pool and dispatcher every time; the config is stable
     * in practice, so a single cached client is enough.
     */
    private synchronized OpenAIClient clientFor(LlmConfig config) {
        ClientKey key = new ClientKey(
                config.baseUrl(), config.apiKey(), config.timeoutSeconds());
        if (cached != null && cached.key().equals(key)) {
            return cached.client();
        }
        if (cached != null) {
            cached.client().close();
        }

        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .maxRetries(MAX_RETRIES);
        if (sleeper != null) {
            builder.sleeper(sleeper);
        }

        cached = new Cached(key, builder.build());
        return cached.client();
    }

    private static void restoreInterrupt(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String excerpt(Object body) {
        String text = body == null ? "null" : body.toString();
        return text.length() > BODY_EXCERPT ? text.substring(0, BODY_EXCERPT) + "…" : text;
    }

    private record ClientKey(String baseUrl, String apiKey, int timeoutSeconds) {
        /** Same rule as {@link LlmConfig}: a key must never reach a log line. */
        @Override
        public String toString() {
            return "ClientKey[baseUrl=" + baseUrl + ", timeoutSeconds=" + timeoutSeconds
                    + ", apiKey=***]";
        }
    }

    private record Cached(ClientKey key, OpenAIClient client) {}
}
