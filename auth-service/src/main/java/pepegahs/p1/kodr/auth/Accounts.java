package pepegahs.p1.kodr.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
class Accounts {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final String absentAccountHash;

    Accounts(JdbcTemplate db, PasswordEncoder passwords) {
        this.db = db;
        this.passwords = passwords;
        this.absentAccountHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public Account register(String username, String email, String password) {
        try {
            return insert(normalize(username), normalize(email), password, "student", false).orElseThrow();
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Имя пользователя или почта уже используются");
        }
    }

    @Transactional
    public void seed(String username, String email, String password, String role) {
        boolean administrator = role.equals("admin");
        if (administrator) {
            db.execute("LOCK TABLE auth_accounts IN SHARE ROW EXCLUSIVE MODE");
            boolean administratorExists = Boolean.TRUE.equals(db.queryForObject(
                    "SELECT EXISTS (SELECT FROM auth_accounts WHERE role = 'admin')", Boolean.class));
            if (administratorExists) {
                return;
            }
        }
        if (password.length() < 12 || password.length() > 128) {
            throw new IllegalArgumentException("Bootstrap passwords must have 12 to 128 characters");
        }
        Optional<Account> created = insert(normalize(username), normalize(email), password, role, true);
        if (administrator && created.isEmpty()) {
            throw new IllegalStateException("The administrator login or email belongs to another account");
        }
    }

    private Optional<Account> insert(String username, String email, String password, String role, boolean demo) {
        UUID id = UUID.randomUUID();
        String sql = """
                INSERT INTO auth_accounts (id, username, email, password_hash, role) VALUES (?, ?, ?, ?, ?)
                """;
        if (demo) {
            sql += " ON CONFLICT DO NOTHING";
        }
        if (db.update(sql, id, username, email, passwords.encode(password), role) == 0) {
            return Optional.empty();
        }
        return Optional.of(new Account(id, username, email));
    }

    Optional<Identity> authenticate(String email, String password) {
        Optional<Credentials> credentials = db.query("""
                SELECT id, username, role, session_version, password_hash FROM auth_accounts WHERE email = ? AND enabled
                """, (row, index) -> new Credentials(
                new Identity(row.getObject("id", UUID.class), row.getString("username"),
                        row.getString("role"), row.getLong("session_version")),
                row.getString("password_hash")), normalize(email)).stream()
                .findFirst();
        String hash = credentials.map(Credentials::passwordHash).orElse(absentAccountHash);
        return passwords.matches(password, hash) ? credentials.map(Credentials::identity) : Optional.empty();
    }

    boolean activeSession(UUID id, long version) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT FROM auth_accounts WHERE id = ? AND enabled AND session_version = ?)
                """, Boolean.class, id, version));
    }

    void changePassword(UUID id, long version, String currentPassword, String newPassword) {
        verifyPassword(id, version, currentPassword);
        if (currentPassword.equals(newPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Новый пароль должен отличаться от текущего");
        }
        int updated = db.update("""
                UPDATE auth_accounts SET password_hash = ?, session_version = session_version + 1
                WHERE id = ? AND enabled AND session_version = ?
                """, passwords.encode(newPassword), id, version);
        if (updated == 0) {
            throw invalidSession();
        }
    }

    String email(UUID id, long version) {
        return db.queryForList("""
                SELECT email FROM auth_accounts WHERE id = ? AND enabled AND session_version = ?
                """, String.class, id, version).stream().findFirst().orElseThrow(Accounts::invalidSession);
    }

    String changeEmail(UUID id, long version, String password, String email) {
        verifyPassword(id, version, password);
        String normalized = normalize(email);
        try {
            int updated = db.update("""
                    UPDATE auth_accounts SET email = ? WHERE id = ? AND enabled AND session_version = ?
                    """, normalized, id, version);
            if (updated == 0) {
                throw invalidSession();
            }
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Этот адрес электронной почты уже используется");
        }
        return normalized;
    }

    private void verifyPassword(UUID id, long version, String currentPassword) {
        String passwordHash = db.queryForList("""
                SELECT password_hash FROM auth_accounts WHERE id = ? AND enabled AND session_version = ?
                """, String.class, id, version).stream()
                .findFirst().orElseThrow(Accounts::invalidSession);
        if (!passwords.matches(currentPassword, passwordHash)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Неверный текущий пароль");
        }
    }

    static ResponseStatusException invalidSession() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Сеанс завершён");
    }

    private record Credentials(Identity identity, String passwordHash) {
    }

    record Identity(UUID id, String username, String role, long sessionVersion) {
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    record Account(UUID id, String username, String email) {
    }
}
