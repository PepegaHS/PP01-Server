package pepegahs.p1.kodr.progress;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import pepegahs.p1.kodr.common.GradingBroker;
import pepegahs.p1.kodr.common.KodrWeb;
import pepegahs.p1.kodr.common.ServiceCalls;

@SpringBootApplication
@Import({KodrWeb.class, ServiceCalls.class, GradingBroker.class})
public class ProgressServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProgressServiceApplication.class, args);
    }
}
