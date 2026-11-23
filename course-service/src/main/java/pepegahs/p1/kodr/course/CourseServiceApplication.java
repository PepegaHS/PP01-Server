package pepegahs.p1.kodr.course;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import pepegahs.p1.kodr.common.KodrWeb;

@SpringBootApplication
@Import(KodrWeb.class)
public class CourseServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CourseServiceApplication.class, args);
    }
}
