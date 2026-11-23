package pepegahs.p1.kodr.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class KodrWeb {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
                throw new InvalidBearerTokenException("Missing JWT subject");
            }
            return roles(jwt.getClaimAsMap("realm_access")).stream()
                    .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        });
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**", "/error").permitAll()
                        .requestMatchers("/internal/**").hasRole("grader")
                        .requestMatchers("/actuator/**").hasRole("admin")
                        .requestMatchers("/api/**").hasAnyRole("student", "teacher")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }

    @Bean
    OpenAPI kodrOpenApi(@Value("${spring.application.name}") String service) {
        return new OpenAPI().info(new Info().title("Kodr " + service).version("v1"))
                .addServersItem(new Server().url("/"))
                .components(new Components().addSecuritySchemes("keycloak", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("keycloak"));
    }

    public static List<String> roles(Map<String, Object> realmAccess) {
        if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> values) {
            return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    public static <T> T requiredBody(RestClient.ResponseSpec response, Class<T> type) {
        T body = response.body(type);
        if (body == null) {
            throw new RestClientException("Empty response from upstream");
        }
        return body;
    }

    public static ResponseStatusException notFound(String detail) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, detail);
    }

    public static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Сервис временно недоступен");
    }
}
