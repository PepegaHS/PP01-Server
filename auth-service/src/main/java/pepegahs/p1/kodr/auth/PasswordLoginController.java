package pepegahs.p1.kodr.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pepegahs.p1.kodr.common.KodrWeb;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@RestController
class PasswordLoginController {
    private final Accounts accounts;
    private final BrowserSessions sessions;
    private final JwtEncoder encoder;
    private final AuthorizationServerSettings settings;

    PasswordLoginController(Accounts accounts, BrowserSessions sessions, JwtEncoder encoder, AuthorizationServerSettings settings) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.encoder = encoder;
        this.settings = settings;
    }

    @PostMapping(value = "/api/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Войти в аккаунт и получить JWT")
    @ApiResponse(responseCode = "200", description = "JWT выдан")
    @ApiResponse(responseCode = "400", description = "Проверьте поля email и password")
    @ApiResponse(responseCode = "401", description = "Неверный email или пароль")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody Credentials credentials,
                                        @CookieValue(name = BrowserSessions.COOKIE, required = false) String previous) {
        Accounts.Identity user = accounts.authenticate(credentials.email(), credentials.password())
                .orElseThrow(this::invalidCredentials);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = now.plus(AuthSecurity.API_TOKEN_LIFETIME);
        String browserToken = sessions.create(user, expires, previous);
        return tokenHeaders().header(HttpHeaders.SET_COOKIE,
                cookie(browserToken, AuthSecurity.API_TOKEN_LIFETIME).toString()).body(responseToken(user, now, expires));
    }

    @Hidden
    @PostMapping(value = "/api/auth/session", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TokenResponse> restore(
            @CookieValue(name = BrowserSessions.COOKIE, required = false) String token) {
        var session = sessions.find(token).orElseThrow(Accounts::invalidSession);
        return tokenHeaders().body(responseToken(session.identity(), Instant.now().truncatedTo(ChronoUnit.SECONDS), session.expiresAt()));
    }

    @Hidden
    @PostMapping(value = "/api/auth/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> logout(@CookieValue(name = BrowserSessions.COOKIE, required = false) String token) {
        sessions.revoke(token);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    private ResponseCookie cookie(String token, Duration lifetime) {
        return ResponseCookie.from(BrowserSessions.COOKIE, token).httpOnly(true)
                .secure(settings.getIssuer().startsWith("https://")).sameSite("Strict")
                .path("/api/auth").maxAge(lifetime).build();
    }

    private TokenResponse responseToken(Accounts.Identity user, Instant now, Instant expires) {
        var claims = JwtClaimsSet.builder()
                .issuer(settings.getIssuer())
                .subject(user.id().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expires)
                .id(UUID.randomUUID().toString())
                .claim("scope", List.of(KodrWeb.API_SCOPE))
                .claim("preferred_username", user.username())
                .claim("session_version", user.sessionVersion());
        String token = encoder.encode(JwtEncoderParameters.from(AuthSecurity.apiClaims(claims, user.role()).build()))
                .getTokenValue();
        return new TokenResponse(token, "Bearer", Math.max(0, Duration.between(now, expires).toSeconds()));
    }

    private ResponseEntity.BodyBuilder tokenHeaders() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache");
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Неверный email или пароль");
    }

    record Credentials(@NotBlank @Email @Size(max = 254) @Schema(example = "admin@kodr.native") String email,
                       @NotBlank @Size(max = 1024)
                       @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password) {
    }

    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("token_type") @Schema(example = "Bearer") String tokenType,
                         @JsonProperty("expires_in") @Schema(example = "86400") long expiresIn) {
    }
}
