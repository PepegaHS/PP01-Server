package pepegahs.p1.kodr.api;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GatewayFallback {
    @RequestMapping("/fallback")
    @Operation(summary = "Сообщить о временной недоступности сервиса")
    ResponseEntity<ProblemDetail> fallback() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                        "Сервис временно недоступен"));
    }
}
