package pepegahs.p1.kodr.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import pepegahs.p1.kodr.common.Grading.Verdict;
import pepegahs.p1.kodr.common.Language;
import pepegahs.p1.kodr.common.KodrWeb;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

@Component
class Judge0 {
    private static final Logger LOG = LoggerFactory.getLogger(Judge0.class);
    private static final String FIELDS = "status,stdout,stderr,compile_output,time,memory";
    private final RestClient client;
    private final CircuitBreaker breaker;
    private final Duration pollTimeout;
    private final Duration pollInterval;

    Judge0(RestClient.Builder builder, CircuitBreakerRegistry breakers,
           @Value("${kodr.judge0.url}") String url, @Value("${kodr.judge0.auth-header}") String authHeader,
           @Value("${kodr.judge0.token}") String token, @Value("${kodr.judge0.poll-timeout}") Duration pollTimeout,
           @Value("${kodr.judge0.poll-interval}") Duration pollInterval) {
        this.client = builder.baseUrl(url).defaultHeaders(headers -> {
            if (!token.isBlank()) {
                headers.set(authHeader, token);
            }
        }).build();
        this.breaker = breakers.circuitBreaker("judge0");
        this.pollTimeout = pollTimeout;
        this.pollInterval = pollInterval;
    }

    Result execute(Language language, String sourceCode, String stdin, String expectedOutput, double cpuSeconds,
                   int memoryKb) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("language_id", language.judge0Id());
        request.put("source_code", encode(sourceCode));
        request.put("stdin", encode(stdin));
        request.put("cpu_time_limit", cpuSeconds);
        request.put("memory_limit", memoryKb);
        request.put("enable_network", false);
        if (expectedOutput != null && !expectedOutput.isEmpty()) {
            request.put("expected_output", encode(expectedOutput));
        }
        RawResult raw = run(request);
        if (raw.status().id() == 13) {
            LOG.warn("Judge0 reported an internal error, retrying execution");
            raw = run(request);
        }
        if (raw.status().id() == 13) {
            throw new Unavailable("Judge0 reported an internal error twice");
        }
        try {
            String stdout = decode(raw.stdout());
            Verdict verdict = Verdict.fromJudge0(raw.status().id());
            if (verdict == Verdict.ACCEPTED && "".equals(expectedOutput) && stdout != null && !stdout.isBlank()) {
                verdict = Verdict.WRONG_ANSWER;
            }
            return new Result(verdict, stdout, decode(raw.stderr()), decode(raw.compileOutput()), raw.time(), raw.memory());
        } catch (IllegalArgumentException error) {
            throw new Unavailable("Judge0 returned invalid Base64 data");
        }
    }

    private RawResult run(Map<String, Object> request) {
        Token submitted = call(() -> KodrWeb.requiredBody(client.post().uri("/submissions?base64_encoded=true&wait=false")
                .body(request).retrieve(), Token.class));
        if (submitted.token() == null || submitted.token().isBlank()) {
            throw new Unavailable("Judge0 did not return a submission token");
        }

        long deadline = System.nanoTime() + pollTimeout.toNanos();
        do {
            RawResult raw = call(() -> KodrWeb.requiredBody(client.get().uri(uri -> uri.path("/submissions/{token}")
                            .queryParam("base64_encoded", true).queryParam("fields", FIELDS)
                            .build(submitted.token()))
                    .retrieve(), RawResult.class));
            if (raw.status() == null) {
                throw new Unavailable("Judge0 did not return a status");
            }
            if (raw.status().id() > 2) {
                return raw;
            }
            pause(pollInterval);
        } while (System.nanoTime() < deadline);
        throw new Unavailable("Timed out waiting for Judge0");
    }

    private <T> T call(Supplier<T> request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return breaker.executeSupplier(request);
            } catch (RestClientException error) {
                boolean transientFailure = error instanceof ResourceAccessException
                        || error instanceof RestClientResponseException response
                        && (response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429);
                if (!transientFailure || attempt >= 3) {
                    throw new Unavailable("Judge0 HTTP request failed", error);
                }
                pause(Duration.ofMillis(200L * attempt));
            } catch (CallNotPermittedException error) {
                throw new Unavailable("Judge0 circuit is open", error);
            }
        }
    }

    private void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Judge0 request was interrupted", error);
        }
    }

    private String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        return value == null ? null : new String(Base64.getMimeDecoder().decode(value), StandardCharsets.UTF_8);
    }

    record Token(String token) {
    }

    record Status(int id) {
    }

    record RawResult(Status status, String stdout, String stderr, @JsonProperty("compile_output") String compileOutput,
                     Double time, Integer memory) {
    }

    record Result(Verdict verdict, String stdout, String stderr, String compileOutput, Double timeSeconds,
                  Integer memoryKb) {
    }

    static class Unavailable extends RuntimeException {
        Unavailable(String message) {
            super(message);
        }

        Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
