package pepegahs.p1.kodr.user;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import pepegahs.p1.kodr.common.KodrWeb;

import java.net.URI;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/users")
class ProfilesController {
    private static final RowMapper<Profile> PROFILE = (row, index) -> new Profile(
            row.getString("subject"), row.getString("display_name"), row.getString("bio"), row.getString("avatar_url"),
            row.getString("avatar_image"), new AvatarCrop(row.getInt("avatar_x"), row.getInt("avatar_y"),
                    row.getInt("avatar_zoom")));
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
    @Operation(summary = "Получить свой профиль и настройки, создать профиль администратора при первом обращении")
    Account me(@AuthenticationPrincipal Jwt jwt) {
        List<String> roles = KodrWeb.roles(jwt);
        Optional<Account> account = findAccount(jwt.getSubject(), roles);
        if (account.isEmpty() && roles.contains("admin")) {
            initializeProfile(jwt);
            account = findAccount(jwt.getSubject(), roles);
        }
        return account.orElseThrow(() -> KodrWeb.notFound("Профиль не найден"));
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
    @Operation(summary = "Сохранить URL и кадрирование аватара, загрузить изображение или сбросить аватар")
    Account avatar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Avatar data) {
        if (data.url() != null && data.image() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите один источник аватара");
        }
        if (data.url() != null) {
            validateAvatarUrl(data.url());
        }
        String image = AvatarImages.normalize(data.image());
        AvatarCrop crop = data.url() != null && data.crop() != null ? data.crop() : new AvatarCrop(50, 50, 100);
        initializeProfile(jwt);
        db.update("""
                UPDATE profiles SET avatar_url = ?, avatar_image = ?, avatar_x = ?, avatar_y = ?, avatar_zoom = ?
                WHERE subject = ?
                """, data.url(), image, crop.x(), crop.y(), crop.zoom(), jwt.getSubject());
        return me(jwt);
    }

    private static void validateAvatarUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() != null && uri.getUserInfo() == null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid URL.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите корректный URL изображения");
    }

    @PutMapping("/me/settings")
    @Operation(summary = "Обновить язык интерфейса и настройки уведомлений")
    Account settings(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Settings data) {
        requireProfile(db.update("UPDATE profiles SET locale = ?, email_notifications = ? WHERE subject = ?",
                data.locale(), data.emailNotifications(), jwt.getSubject()));
        return me(jwt);
    }

    private Optional<Account> findAccount(String subject, List<String> roles) {
        return db.query("SELECT * FROM profiles WHERE subject = ?", (row, index) ->
                        new Account(PROFILE.mapRow(row, index), new Settings(row.getString("locale"),
                                row.getBoolean("email_notifications")), roles), subject)
                .stream().findFirst();
    }

    private void initializeProfile(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        String fallback = KodrWeb.roles(jwt).contains("admin") ? "Администратор" : "Пользователь";
        String name = username == null || username.isBlank() ? fallback : username;
        db.update("""
                INSERT INTO profiles (subject, display_name, bio) VALUES (?, left(?, 100), '')
                ON CONFLICT (subject) DO NOTHING
                """, jwt.getSubject(), name);
    }

    private void requireProfile(int updatedRows) {
        if (updatedRows == 0) {
            throw KodrWeb.notFound("Профиль не найден");
        }
    }

    record Profile(String subject, String displayName, String bio, String avatarUrl, String avatarImage, AvatarCrop avatarCrop) {
    }

    record ProfileData(@NotBlank @Size(max = 100) String displayName, @Size(max = 2000) String bio) {
    }

    record Avatar(@Size(max = 2048) String url, @Size(max = 360000) String image, @Valid AvatarCrop crop) {
    }

    record AvatarCrop(@Min(0) @Max(100) int x, @Min(0) @Max(100) int y, @Min(100) @Max(400) int zoom) {
    }

    record Settings(@NotBlank @Size(max = 20) @Pattern(regexp = "[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*") String locale,
                    boolean emailNotifications) {
    }

    record Account(Profile profile, Settings settings, List<String> roles) {
    }
}
