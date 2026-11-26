package pepegahs.p1.kodr.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import pepegahs.p1.kodr.common.Json;
import pepegahs.p1.kodr.common.KodrWeb;
import pepegahs.p1.kodr.common.Language;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/execution")
class ExecutionController {
    private static final Logger LOG = LoggerFactory.getLogger(ExecutionController.class);
    private final Judge0 judge;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration resultTtl;

    ExecutionController(Judge0 judge, StringRedisTemplate redis, ObjectMapper mapper,
                        @Value("${kodr.execution.result-ttl}") Duration resultTtl) {
        this.judge = judge;
        this.redis = redis;
        this.mapper = mapper;
        this.resultTtl = resultTtl;
    }

    @GetMapping("/languages")
    @Operation(summary = "Получить список языков для запуска кода")
    List<SupportedLanguage> languages() {
        return Arrays.stream(Language.values()).map(value -> new SupportedLanguage(value, value.displayName())).toList();
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Запустить код со своим stdin и получить вывод, ошибки, время и память")
    RunResult run(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RunRequest request) {
        String stdin = request.stdin() == null ? "" : request.stdin();
        Judge0.Result output;
        try {
            output = judge.execute(request.language(), request.sourceCode(), stdin, null, 2, 256000);
        } catch (Judge0.Unavailable error) {
            LOG.warn("Interactive run failed: {}", error.getMessage());
            throw KodrWeb.unavailable();
        }
        UUID id = UUID.randomUUID();
        RunResult result = new RunResult(id, request.language().name(), stdin, output);
        try {
            redis.opsForValue().set(cacheKey(jwt, id), Json.write(mapper, result), resultTtl);
        } catch (DataAccessException error) {
            LOG.warn("Could not store interactive run {}", id);
            throw KodrWeb.unavailable();
        }
        return result;
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "Повторно получить сохранённый результат своего запуска до истечения срока хранения")
    RunResult result(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        String payload = redis.opsForValue().get(cacheKey(jwt, id));
        if (payload == null) {
            throw KodrWeb.notFound("Запуск не найден или срок хранения истёк");
        }
        return Json.read(mapper, payload, RunResult.class);
    }

    private String cacheKey(Jwt jwt, UUID id) {
        return "kodr:runs:" + jwt.getSubject() + ":" + id;
    }

    record SupportedLanguage(Language id, String name) {
    }

    record RunRequest(@NotNull Language language, @NotBlank @Size(max = 65536) String sourceCode,
                      @Size(max = 8192) String stdin) {
    }

    record RunResult(UUID id, String language, String stdin, Judge0.Result result) {
    }
}
