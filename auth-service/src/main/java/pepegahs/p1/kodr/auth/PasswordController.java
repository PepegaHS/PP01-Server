package pepegahs.p1.kodr.auth;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pepegahs.p1.kodr.common.KodrWeb;

import java.util.UUID;

@RestController
class PasswordController {
    private final Accounts accounts;

    PasswordController(Accounts accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/api/auth/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Изменить свой пароль и завершить все пользовательские сеансы",
            security = @SecurityRequirement(name = KodrWeb.BEARER_SCHEME))
    @ApiResponse(responseCode = "204", description = "Пароль изменён")
    @ApiResponse(responseCode = "400", description = "Новый пароль не соответствует требованиям")
    @ApiResponse(responseCode = "401", description = "Сеанс завершён")
    @ApiResponse(responseCode = "422", description = "Неверный текущий пароль")
    void change(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePassword data) {
        accounts.changePassword(UUID.fromString(jwt.getSubject()), sessionVersion(jwt), data.currentPassword(), data.newPassword());
    }

    @GetMapping("/api/auth/email")
    @Operation(summary = "Получить свой адрес электронной почты",
            security = @SecurityRequirement(name = KodrWeb.BEARER_SCHEME))
    EmailAddress email(@AuthenticationPrincipal Jwt jwt) {
        return new EmailAddress(accounts.email(UUID.fromString(jwt.getSubject()), sessionVersion(jwt)));
    }

    @PutMapping("/api/auth/email")
    @Operation(summary = "Изменить адрес электронной почты после проверки пароля",
            security = @SecurityRequirement(name = KodrWeb.BEARER_SCHEME))
    EmailAddress email(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangeEmail data) {
        return new EmailAddress(accounts.changeEmail(UUID.fromString(jwt.getSubject()), sessionVersion(jwt),
                data.currentPassword(), data.email()));
    }

    @Hidden
    @GetMapping("/internal/auth/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void checkSession(@AuthenticationPrincipal Jwt jwt) {
        if (!accounts.activeSession(UUID.fromString(jwt.getSubject()), sessionVersion(jwt))) {
            throw Accounts.invalidSession();
        }
    }

    private static long sessionVersion(Jwt jwt) {
        Object version = jwt.getClaim("session_version");
        // Tokens issued before password changes were supported belong to version zero.
        return version instanceof Number number ? number.longValue() : 0;
    }

    record EmailAddress(String email) {
    }

    record ChangeEmail(@NotBlank @Email @Size(max = 254) String email,
                       @NotBlank @Size(max = 1024) @Schema(format = "password") String currentPassword) {
    }

    record ChangePassword(
            @NotBlank @Size(max = 1024) @Schema(format = "password") String currentPassword,
            @NotBlank @Size(min = 12, max = 128) @Schema(format = "password") String newPassword) {
    }
}
