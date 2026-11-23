package pepegahs.p1.kodr.execution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import pepegahs.p1.kodr.common.GradingBroker;
import pepegahs.p1.kodr.common.KodrWeb;

@SpringBootApplication
@Import({KodrWeb.class, GradingBroker.class})
public class ExecutionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExecutionServiceApplication.class, args);
    }
}
