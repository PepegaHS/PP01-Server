package pepegahs.p1.kodr.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Configuration(proxyBeanMethods = false)
public class GradingBroker {
    public static final String REQUESTED = "grading.requested";
    public static final String FINISHED = "grading.finished";
    public static final String ACCEPTED = "grading.accepted";
    private static final String EXCHANGE = "kodr.grading";
    private static final String DEAD_LETTER_EXCHANGE = "kodr.grading.dead";

    @Bean
    Declarables gradingTopology() {
        DirectExchange events = new DirectExchange(EXCHANGE);
        DirectExchange deadLetters = new DirectExchange(DEAD_LETTER_EXCHANGE);
        Queue requested = queue(REQUESTED);
        Queue finished = queue(FINISHED);
        Queue accepted = queue(ACCEPTED);
        Queue requestedDead = QueueBuilder.durable(REQUESTED + ".dead").build();
        Queue finishedDead = QueueBuilder.durable(FINISHED + ".dead").build();
        Queue acceptedDead = QueueBuilder.durable(ACCEPTED + ".dead").build();
        return new Declarables(events, deadLetters, requested, finished, accepted,
                requestedDead, finishedDead, acceptedDead,
                BindingBuilder.bind(requested).to(events).with(REQUESTED),
                BindingBuilder.bind(finished).to(events).with(FINISHED),
                BindingBuilder.bind(accepted).to(events).with(ACCEPTED),
                BindingBuilder.bind(requestedDead).to(deadLetters).with(REQUESTED),
                BindingBuilder.bind(finishedDead).to(deadLetters).with(FINISHED),
                BindingBuilder.bind(acceptedDead).to(deadLetters).with(ACCEPTED));
    }

    private Queue queue(String name) {
        return QueueBuilder.durable(name).deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(name).build();
    }

    @Bean
    Publisher gradingPublisher(RabbitTemplate rabbit, ObjectMapper mapper) {
        return new Publisher(rabbit, mapper);
    }

    public record Publisher(RabbitTemplate rabbit, ObjectMapper mapper) {
        public void send(String route, Object event) {
            sendJson(route, Json.write(mapper, event), UUID.randomUUID().toString());
        }

        public void sendJson(String route, String json, String messageId) {
            var message = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setMessageId(messageId)
                    .build();
            var confirm = new CorrelationData(messageId);
            rabbit.send(EXCHANGE, route, message, confirm);
            try {
                if (!confirm.getFuture().get(5, TimeUnit.SECONDS).isAck() || confirm.getReturned() != null) {
                    throw new IllegalStateException("RabbitMQ did not route " + route);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while publishing " + route, error);
            } catch (ExecutionException | TimeoutException error) {
                throw new IllegalStateException("Cannot confirm delivery to " + route, error);
            }
        }
    }
}
