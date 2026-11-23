package pepegahs.p1.kodr.submission;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Import;
import pepegahs.p1.kodr.common.GradingBroker;
import pepegahs.p1.kodr.common.KodrWeb;
import pepegahs.p1.kodr.common.ServiceCalls;

@SpringBootApplication
@EnableScheduling
@Import({KodrWeb.class, ServiceCalls.class, GradingBroker.class})
public class SubmissionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SubmissionServiceApplication.class, args);
    }
}
