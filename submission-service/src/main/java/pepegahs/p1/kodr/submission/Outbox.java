package pepegahs.p1.kodr.submission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import pepegahs.p1.kodr.common.GradingBroker.Publisher;
import pepegahs.p1.kodr.common.Json;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
class Outbox {
    private static final Logger LOG = LoggerFactory.getLogger(Outbox.class);
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final Publisher publisher;
    private final Tracer tracer;
    private final Propagator propagator;

    Outbox(JdbcTemplate db, ObjectMapper mapper, TransactionTemplate transaction,
           Publisher publisher, Tracer tracer, Propagator propagator) {
        this.db = db;
        this.mapper = mapper;
        this.transaction = transaction;
        this.publisher = publisher;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    void add(String route, Object event) {
        Map<String, String> headers = new HashMap<>();
        Span current = tracer.currentSpan();
        if (current != null) {
            propagator.inject(current.context(), headers, Map::put);
        }
        db.update("INSERT INTO outbox (id, route, payload, trace_headers) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), route, Json.write(mapper, event), Json.write(mapper, headers));
    }

    @Scheduled(fixedDelay = 250)
    void dispatch() {
        transaction.executeWithoutResult(status -> db.query("""
                SELECT id, route, payload, trace_headers FROM outbox
                WHERE sent_at IS NULL AND next_attempt <= now()
                ORDER BY next_attempt, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (row, index) -> new Pending(row.getObject("id", UUID.class), row.getString("route"),
                row.getString("payload"), row.getString("trace_headers"))).stream().findFirst().ifPresent(this::send));
    }

    private void send(Pending event) {
        Map<String, String> headers = Json.read(mapper, event.traceHeaders(),
                mapper.constructType(new TypeReference<Map<String, String>>() {}));
        Span span = propagator.extract(headers, Map::get).name("grading.outbox").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            publisher.sendJson(event.route(), event.payload(), event.id().toString());
            db.update("UPDATE outbox SET sent_at = now() WHERE id = ?", event.id());
        } catch (RuntimeException error) {
            span.error(error);
            LOG.warn("Could not publish outbox entry {} ({})", event.id(), error.getClass().getSimpleName());
            db.update("UPDATE outbox SET next_attempt = now() + interval '5 seconds' WHERE id = ?", event.id());
        } finally {
            span.end();
        }
    }

    @Scheduled(fixedDelayString = "PT1H")
    void prune() {
        db.update("DELETE FROM outbox WHERE sent_at < now() - interval '1 day'");
    }

    private record Pending(UUID id, String route, String payload, String traceHeaders) {
    }
}
