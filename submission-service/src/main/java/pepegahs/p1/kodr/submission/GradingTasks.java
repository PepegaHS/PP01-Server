package pepegahs.p1.kodr.submission;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import pepegahs.p1.kodr.common.KodrWeb;
import pepegahs.p1.kodr.common.Learning.GradingTask;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

@Component
class GradingTasks {
    private final RestClient authorizationServer;
    private final RestClient tasks;
    private final String tokenUri;
    private final String clientId;
    private final String secret;

    GradingTasks(RestClientBuilderConfigurer configurer, @LoadBalanced RestClient.Builder discovery,
                 @Value("${kodr.grader.token-uri}") String tokenUri,
                 @Value("${kodr.grader.client-id}") String clientId, @Value("${kodr.grader.secret}") String secret) {
        this.authorizationServer = configurer.configure(RestClient.builder()).build();
        this.tasks = discovery.clone().baseUrl("http://task-service").build();
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.secret = secret;
    }

    GradingTask get(UUID id) {
        String token = serviceToken();
        try {
            return KodrWeb.requiredBody(tasks.get().uri("/internal/tasks/{id}/grading", id).headers(headers -> headers.setBearerAuth(token))
                    .retrieve(), GradingTask.class);
        } catch (HttpClientErrorException.NotFound error) {
            throw KodrWeb.notFound("Опубликованное задание не найдено");
        } catch (RestClientException error) {
            throw KodrWeb.unavailable();
        }
    }

    private String serviceToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "kodr.internal");
        try {
            return KodrWeb.requiredBody(authorizationServer.post().uri(tokenUri).headers(headers -> headers.setBasicAuth(clientId, secret))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve(), Token.class).accessToken();
        } catch (RestClientException error) {
            throw KodrWeb.unavailable();
        }
    }

    record Token(@JsonProperty("access_token") String accessToken) {
    }
}
