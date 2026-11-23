package pepegahs.p1.kodr.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
class Accounts implements UserDetailsService {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;

    Accounts(JdbcTemplate db, PasswordEncoder passwords) {
        this.db = db;
        this.passwords = passwords;
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

    @Override
    public UserDetails loadUserByUsername(String username) {
        String login = normalize(username);
        return db.query("SELECT * FROM auth_accounts WHERE username = ?", (row, index) -> {
            return User.withUsername(row.getString("username"))
                    .password(row.getString("password_hash"))
                    .disabled(!row.getBoolean("enabled"))
                    .roles(row.getString("role"))
                    .build();
        }, login).stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Account not found"));
    }

    Optional<Identity> activeIdentity(String username) {
        return db.query("SELECT id, role FROM auth_accounts WHERE username = ? AND enabled",
                        (row, index) -> new Identity(row.getObject("id", UUID.class), row.getString("role")), username)
                .stream().findFirst();
    }

    record Identity(UUID id, String role) {
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    record Account(UUID id, String username, String email) {
    }
}
