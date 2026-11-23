package pepegahs.p1.kodr.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RegistrationController {
    private final Accounts accounts;

    RegistrationController(Accounts accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Зарегистрировать локальную учётную запись ученика")
    @ApiResponse(responseCode = "201", description = "Учётная запись создана")
    @ApiResponse(responseCode = "400", description = "Проверьте имя пользователя, почту и пароль")
    @ApiResponse(responseCode = "409", description = "Имя пользователя или почта уже используются")
    Accounts.Account register(@Valid @RequestBody Registration data) {
        return accounts.register(data.username(), data.email(), data.password());
    }

    record Registration(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}")
                        @Schema(example = "student1") String username,
                        @NotBlank @Email @Size(max = 254)
                        @Schema(example = "student1@example.test") String email,
                        @NotBlank @Size(min = 12, max = 128)
                        @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password) {
    }
}
