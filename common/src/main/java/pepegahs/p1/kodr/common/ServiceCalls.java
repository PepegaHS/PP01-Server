package pepegahs.p1.kodr.common;

import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ServiceCalls {
    @Bean
    @LoadBalanced
    RestClient.Builder discoveryRestClientBuilder(RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder());
    }

    @Bean
    Courses courses(@LoadBalanced RestClient.Builder builder) {
        return new Courses(builder.clone().baseUrl("http://course-service").build());
    }

    public record CourseSummary(UUID id, String owner, Language language, String title, String description,
                                boolean published) {
    }

    public record Courses(RestClient client) {
        public CourseSummary summary(UUID id, Jwt jwt) {
            return get("/api/courses/{id}/summary", id, jwt, CourseSummary.class);
        }

        public Learning.Course content(UUID id, Jwt jwt) {
            return get("/api/courses/{id}", id, jwt, Learning.Course.class);
        }

        private <T> T get(String path, UUID id, Jwt jwt, Class<T> body) {
            try {
                return KodrWeb.requiredBody(client.get().uri(path, id)
                        .headers(headers -> headers.setBearerAuth(jwt.getTokenValue())).retrieve(), body);
            } catch (HttpClientErrorException.NotFound error) {
                throw KodrWeb.notFound("Курс не найден");
            } catch (RestClientException error) {
                throw KodrWeb.unavailable();
            }
        }
    }
}
