package pepegahs.p1.kodr.task;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import pepegahs.p1.kodr.common.KodrWeb;
import pepegahs.p1.kodr.common.ServiceCalls;

@SpringBootApplication
@Import({KodrWeb.class, ServiceCalls.class})
public class TaskServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TaskServiceApplication.class, args);
    }
}
