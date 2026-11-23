package pepegahs.p1.kodr.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pepegahs.p1.kodr.common.KodrWeb;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
class PasswordLoginController {
    private final AuthenticationManager authenticationManager;
    private final Accounts accounts;
    private final JwtEncoder encoder;
    private final AuthorizationServerSettings settings;

    PasswordLoginController(AuthenticationManager authenticationManager, Accounts accounts,
                            JwtEncoder encoder, AuthorizationServerSettings settings) {
        this.authenticationManager = authenticationManager;
        this.accounts = accounts;
        this.encoder = encoder;
        this.settings = settings;
    }

    @PostMapping(value = "/api/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Войти в аккаунт и получить JWT")
    @ApiResponse(responseCode = "200", description = "JWT выдан")
    @ApiResponse(responseCode = "400", description = "Проверьте поля username и password")
    @ApiResponse(responseCode = "401", description = "Неверный логин или пароль")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody Credentials credentials) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(credentials.username(), credentials.password()));
        } catch (BadCredentialsException | AccountStatusException error) {
            throw invalidCredentials();
        }

        Accounts.Identity user = accounts.activeIdentity(authentication.getName())
                .orElseThrow(this::invalidCredentials);
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(settings.getIssuer())
                .subject(user.id().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plus(AuthSecurity.API_TOKEN_LIFETIME))
                .id(UUID.randomUUID().toString())
                .claim("scope", List.of(KodrWeb.API_SCOPE));
        String token = encoder.encode(JwtEncoderParameters.from(AuthSecurity.apiClaims(claims, user.role()).build()))
                .getTokenValue();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new TokenResponse(token, "Bearer", AuthSecurity.API_TOKEN_LIFETIME.toSeconds()));
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Неверный логин или пароль");
    }

    record Credentials(@NotBlank @Size(max = 255) @Schema(example = "admin") String username,
                       @NotBlank @Size(max = 1024)
                       @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password) {
    }

    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("token_type") @Schema(example = "Bearer") String tokenType,
                         @JsonProperty("expires_in") @Schema(example = "600") long expiresIn) {
    }
}
