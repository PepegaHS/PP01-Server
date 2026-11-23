package pepegahs.p1.kodr.user;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import pepegahs.p1.kodr.common.KodrWeb;

@RestController
@RequestMapping("/api/users")
class ProfilesController {
    private static final RowMapper<Profile> PROFILE = (row, index) -> new Profile(
            row.getString("subject"), row.getString("display_name"), row.getString("bio"), row.getString("avatar_url"));
    private final JdbcTemplate db;

    ProfilesController(JdbcTemplate db) {
        this.db = db;
    }

    @GetMapping("/{subject}")
    @Operation(summary = "Получить публичный профиль ученика или преподавателя")
    Profile profile(@PathVariable String subject) {
        return db.query("SELECT * FROM profiles WHERE subject = ?", PROFILE, subject)
                .stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Профиль не найден"));
    }

    @GetMapping("/me")
    @Operation(summary = "Получить свой профиль и настройки")
    Account me(@AuthenticationPrincipal Jwt jwt) {
        return db.query("SELECT * FROM profiles WHERE subject = ?", (row, index) ->
                        new Account(PROFILE.mapRow(row, index), new Settings(row.getString("locale"),
                                row.getBoolean("email_notifications")), KodrWeb.roles(jwt.getClaimAsMap("realm_access"))),
                        jwt.getSubject())
                .stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Профиль не найден"));
    }

    @PutMapping("/me")
    @Operation(summary = "Зарегистрировать или обновить свой профиль")
    Account save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileData data) {
        db.update("""
                INSERT INTO profiles (subject, display_name, bio) VALUES (?, ?, ?)
                ON CONFLICT (subject) DO UPDATE SET display_name = EXCLUDED.display_name, bio = EXCLUDED.bio
                """, jwt.getSubject(), data.displayName(), data.bio() == null ? "" : data.bio());
        return me(jwt);
    }

    @PutMapping("/me/avatar")
    @Operation(summary = "Указать URL аватарки или удалить аватарку")
    Account avatar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Avatar data) {
        requireProfile(db.update("UPDATE profiles SET avatar_url = ? WHERE subject = ?", data.url(), jwt.getSubject()));
        return me(jwt);
    }

    @PutMapping("/me/settings")
    @Operation(summary = "Обновить язык интерфейса и настройки уведомлений")
    Account settings(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Settings data) {
        requireProfile(db.update("UPDATE profiles SET locale = ?, email_notifications = ? WHERE subject = ?",
                data.locale(), data.emailNotifications(), jwt.getSubject()));
        return me(jwt);
    }

    private void requireProfile(int updatedRows) {
        if (updatedRows == 0) {
            throw KodrWeb.notFound("Профиль не найден");
        }
    }

    record Profile(String subject, String displayName, String bio, String avatarUrl) {
    }

    record ProfileData(@NotBlank @Size(max = 100) String displayName, @Size(max = 2000) String bio) {
    }

    record Avatar(@Size(max = 2048) @Pattern(regexp = "https?://[^\\s/]+[^\\s]*") String url) {
    }

    record Settings(@NotBlank @Size(max = 20) @Pattern(regexp = "[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*") String locale,
                    boolean emailNotifications) {
    }

    record Account(Profile profile, Settings settings, java.util.List<String> roles) {
    }
}
