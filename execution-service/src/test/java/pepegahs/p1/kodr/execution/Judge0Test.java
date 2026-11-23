package pepegahs.p1.kodr.execution;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pepegahs.p1.kodr.common.Grading.Verdict;
import pepegahs.p1.kodr.common.Language;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class Judge0Test {
    private static final String CREATE = "http://judge0.test/submissions?base64_encoded=true&wait=false";
    private static final String POLL = "http://judge0.test/submissions/job?";
    private MockRestServiceServer server;
    private Judge0 judge;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        judge = new Judge0(builder, CircuitBreakerRegistry.ofDefaults(), "http://judge0.test", "X-Auth-Token",
                "test-token", Duration.ofSeconds(2), Duration.ZERO);
    }

    @Test
    void submitsBase64AndPollsForOutputAndMetrics() {
        server.expect(requestTo(CREATE)).andExpect(method(HttpMethod.POST)).andExpect(header("X-Auth-Token", "test-token"))
                .andExpect(content().json("""
                        {"language_id":71,"source_code":"cHJpbnQoaW5wdXQoKSk=","stdin":"0LQ=",
                         "expected_output":"0LQK","cpu_time_limit":1.5,"memory_limit":128000,"enable_network":false}
                        """))
                .andRespond(withStatus(HttpStatus.CREATED).body("{\"token\":\"job\"}").contentType(MediaType.APPLICATION_JSON));
        expectPoll("{\"status\":{\"id\":1}}");
        expectPoll("{\"status\":{\"id\":2}}");
        expectPoll("{\"status\":{\"id\":3},\"stdout\":\"0LQK\",\"time\":\"0.034\",\"memory\":10024}");

        Judge0.Result result = judge.execute(Language.PYTHON, "print(input())", "д", "д\n", 1.5, 128000);

        assertThat(result).isEqualTo(new Judge0.Result(Verdict.ACCEPTED, "д\n", null, null, 0.034, 10024));
        server.verify();
    }

    @Test
    void retriesPollingWithoutResubmittingAndReturnsCompilationError() {
        expectCreate();
        server.expect(requestTo(startsWith(POLL))).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectPoll("{\"status\":{\"id\":6},\"compile_output\":\"ZXJyb3I=\"}");

        Judge0.Result result = judge.execute(Language.C, "invalid source", "", "result", 1, 128000);

        assertThat(result.verdict()).isEqualTo(Verdict.COMPILATION_ERROR);
        assertThat(result.compileOutput()).isEqualTo("error");
        server.verify();
    }

    @Test
    void rejectsUnexpectedOutputWhenExpectedOutputIsEmpty() {
        expectCreate();
        expectPoll("{\"status\":{\"id\":3},\"stdout\":\"dW5leHBlY3RlZA==\"}");

        Judge0.Result result = judge.execute(Language.PHP, "<?php echo 'unexpected';", "", "", 1, 128000);

        assertThat(result.verdict()).isEqualTo(Verdict.WRONG_ANSWER);
        server.verify();
    }

    private void expectCreate() {
        server.expect(requestTo(CREATE)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CREATED).body("{\"token\":\"job\"}").contentType(MediaType.APPLICATION_JSON));
    }

    private void expectPoll(String body) {
        server.expect(requestTo(startsWith(POLL))).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
