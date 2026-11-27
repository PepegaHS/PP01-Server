package pepegahs.p1.kodr.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

import java.util.Collection;
import java.util.List;

@Configuration(proxyBeanMethods = false)
class GatewaySecurity {
    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http, @Value("${kodr.cors.origins}") List<String> origins,
                                    @Value("${kodr.auth.session-url}") String sessionUrl, WebClient.Builder webClient) {
        WebClient auth = webClient.observationConvention(new DefaultClientRequestObservationConvention("kodr.auth.session.check")).build();
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        var corsSource = new UrlBasedCorsConfigurationSource();
        corsSource.registerCorsConfiguration("/api/**", cors);

        var jwtConverter = new ReactiveJwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(jwt -> {
            if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
                return Flux.error(new InvalidBearerTokenException("Missing JWT subject"));
            }
            if (!(jwt.getClaim("roles") instanceof Collection<?> roles)) {
                return Flux.empty();
            }
            Flux<GrantedAuthority> authorities = Flux.fromIterable(roles).ofType(String.class)
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role));
            if (!roles.contains("student") && !roles.contains("teacher") && !roles.contains("admin")) {
                return authorities;
            }
            return checkSession(auth, sessionUrl, jwt.getTokenValue()).thenMany(authorities);
        });

        return http
                .cors(spec -> spec.configurationSource(corsSource))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**", "/docs/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/session", "/api/auth/logout").permitAll()
                        .pathMatchers(HttpMethod.GET, "/oauth2/jwks").permitAll()
                        .pathMatchers("/actuator/**").hasRole("admin")
                        .pathMatchers("/api/**").hasAnyRole("student", "teacher", "admin")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
                .build();
    }

    private Mono<Void> checkSession(WebClient auth, String url, String token) {
        return auth.get().uri(url).headers(headers -> headers.setBearerAuth(token))
                .exchangeToMono(response -> {
                    if (response.statusCode().equals(HttpStatus.NO_CONTENT)) {
                        return Mono.<Void>empty();
                    }
                    if (response.statusCode().equals(HttpStatus.UNAUTHORIZED)) {
                        return Mono.error(new InvalidBearerTokenException("Session is no longer active"));
                    }
                    return Mono.error(authUnavailable());
                })
                .timeout(Duration.ofSeconds(3), Mono.error(authUnavailable()))
                .onErrorMap(WebClientRequestException.class, error -> authUnavailable());
    }

    private ResponseStatusException authUnavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Проверка сеанса временно недоступна");
    }
}
