package pepegahs.p1.kodr.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("kodr.auth")
record AuthProperties(String issuer, String graderSecret,
                      boolean demoUsers, String studentPassword, String teacherPassword,
                      @NotBlank String adminUsername, @NotBlank @Email String adminEmail, String adminPassword) {
}
