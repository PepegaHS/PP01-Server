package pepegahs.p1.kodr.auth;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import pepegahs.p1.kodr.common.KodrWeb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class AuthSecurity {
    static final Duration API_TOKEN_LIFETIME = Duration.ofHours(24);
    private static final String GRADER = "kodr-grader";

    @Bean
    @Order(1)
    SecurityFilterChain authorizationEndpoints(HttpSecurity http, AuthorizationServerSettings settings) throws Exception {
        return http.securityMatcher(settings.getTokenEndpoint(), settings.getJwkSetEndpoint())
                .with(OAuth2AuthorizationServerConfigurer.authorizationServer(), Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain accountEndpoints(HttpSecurity http) throws Exception {
        var registration = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/register");
        var login = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/login");
        var restore = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/session");
        var logout = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/logout");
        var email = PathPatternRequestMatcher.withDefaults().matcher("/api/auth/email");
        var changePassword = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/password");
        var sessionStatus = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/internal/auth/session");
        return http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.requestMatchers(registration, login, restore, logout).permitAll()
                        .requestMatchers(changePassword, email, sessionStatus).hasAuthority("SCOPE_" + KodrWeb.API_SCOPE)
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/v3/api-docs",
                                "/v3/api-docs/**", "/error").permitAll()
                        .anyRequest().denyAll())
                .csrf(csrf -> csrf.ignoringRequestMatchers(registration, login, restore, logout, changePassword, email))
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .logout(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    RegisteredClientRepository registeredClients(AuthProperties settings, PasswordEncoder passwordEncoder) {
        RegisteredClient grader = RegisteredClient.withId(GRADER)
                .clientId(GRADER)
                .clientSecret("{argon2@SpringSecurity_v5_8}" + passwordEncoder.encode(settings.graderSecret()))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("kodr.internal")
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(5)).build())
                .build();
        return new InMemoryRegisteredClientRepository(grader);
    }

    @Bean
    @DependsOnDatabaseInitialization
    OAuth2AuthorizationService authorizations(JdbcTemplate db, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(db, clients);
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> tokenClaims() {
        return context -> {
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())
                    && GRADER.equals(context.getRegisteredClient().getClientId())) {
                context.getClaims().subject(GRADER);
                apiClaims(context.getClaims(), "grader");
            }
        };
    }

    static JwtClaimsSet.Builder apiClaims(JwtClaimsSet.Builder claims, String role) {
        return claims.audience(new ArrayList<>(List.of("kodr-api")))
                .claim("roles", new ArrayList<>(List.of(role)));
    }

    @Bean
    AuthorizationServerSettings authorizationSettings(AuthProperties settings) {
        return AuthorizationServerSettings.builder().issuer(settings.issuer()).build();
    }

    @Bean
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> keys) {
        return new NimbusJwtEncoder(keys);
    }

    @Bean
    ApplicationRunner bootstrapAccounts(Accounts accounts, AuthProperties settings) {
        return args -> {
            if (!settings.adminPassword().isBlank()) {
                accounts.seed(settings.adminUsername(), settings.adminEmail(), settings.adminPassword(), "admin");
            }
            if (settings.demoUsers()) {
                accounts.seed("student", "student@example.test", settings.studentPassword(), "student");
                accounts.seed("teacher", "teacher@example.test", settings.teacherPassword(), "teacher");
            }
        };
    }

    @Bean
    OpenAPI accountsOpenApi() {
        return new OpenAPI().info(new Info().title("Kodr — авторизация").version("v1"))
                .addServersItem(new Server().url("/"))
                .components(new Components().addSecuritySchemes(KodrWeb.BEARER_SCHEME, KodrWeb.bearerScheme()));
    }
}
