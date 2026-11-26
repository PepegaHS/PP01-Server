package pepegahs.p1.kodr.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import pepegahs.p1.kodr.common.Grading;
import pepegahs.p1.kodr.common.Grading.Verdict;
import pepegahs.p1.kodr.common.GradingBroker;
import pepegahs.p1.kodr.common.GradingBroker.Publisher;
import pepegahs.p1.kodr.common.Json;
import pepegahs.p1.kodr.common.Language;

import java.nio.charset.StandardCharsets;

@Component
class SubmissionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SubmissionWorker.class);
    private final Judge0 judge;
    private final Publisher publisher;
    private final ObjectMapper mapper;

    SubmissionWorker(Judge0 judge, Publisher publisher, ObjectMapper mapper) {
        this.judge = judge;
        this.publisher = publisher;
        this.mapper = mapper;
    }

    @RabbitListener(queues = GradingBroker.REQUESTED, concurrency = "${kodr.grading.workers}")
    void grade(Message message) {
        Grading.Requested request = Json.read(mapper, new String(message.getBody(), StandardCharsets.UTF_8),
                Grading.Requested.class);
        publisher.send(GradingBroker.FINISHED, grade(request));
    }

    Grading.Finished grade(Grading.Requested request) {
        var task = request.task();
        Language language;
        try {
            language = Language.valueOf(request.language());
        } catch (IllegalArgumentException error) {
            LOG.info("Skipped submission {} for retired language {}", request.id(), request.language());
            return new Grading.Finished(request.id(), Verdict.SYSTEM_ERROR, 0, task.tests().size(), null, null, null);
        }
        Verdict verdict = Verdict.ACCEPTED;
        int passed = 0;
        Double maxTime = null;
        Integer maxMemory = null;
        String compileOutput = null;

        for (var test : task.tests()) {
            Judge0.Result result;
            try {
                result = judge.execute(language, request.sourceCode(), test.stdin(), test.expectedOutput(),
                        task.cpuSeconds(), task.memoryKb());
            } catch (Judge0.Unavailable error) {
                LOG.warn("Judge0 failed for submission {}: {}", request.id(), error.getMessage());
                verdict = Verdict.SYSTEM_ERROR;
                break;
            }
            verdict = result.verdict();
            maxTime = max(maxTime, result.timeSeconds());
            maxMemory = max(maxMemory, result.memoryKb());
            if (verdict != Verdict.ACCEPTED) {
                if (verdict == Verdict.COMPILATION_ERROR && result.compileOutput() != null) {
                    compileOutput = result.compileOutput().replace("\u0000", "");
                }
                break;
            }
            passed++;
        }
        return new Grading.Finished(request.id(), verdict, passed, task.tests().size(), maxTime, maxMemory, compileOutput);
    }

    private <T extends Comparable<T>> T max(T current, T next) {
        if (current == null) {
            return next;
        }
        return next != null && next.compareTo(current) > 0 ? next : current;
    }
}
