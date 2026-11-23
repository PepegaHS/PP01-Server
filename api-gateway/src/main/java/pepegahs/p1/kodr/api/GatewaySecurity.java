package pepegahs.p1.kodr.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.List;

@Configuration(proxyBeanMethods = false)
class GatewaySecurity {
    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http, @Value("${kodr.cors.origins}") List<String> origins) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
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
            return Flux.fromIterable(roles).ofType(String.class).map(role -> new SimpleGrantedAuthority("ROLE_" + role));
        });

        return http
                .cors(spec -> spec.configurationSource(corsSource))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**", "/docs/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .pathMatchers(HttpMethod.GET, "/oauth2/jwks").permitAll()
                        .pathMatchers("/actuator/**").hasRole("admin")
                        .pathMatchers("/api/**").hasAnyRole("student", "teacher", "admin")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
                .build();
    }
}
