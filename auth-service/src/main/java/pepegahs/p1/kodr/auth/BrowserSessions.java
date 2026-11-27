package pepegahs.p1.kodr.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
class BrowserSessions {
    static final String COOKIE = "kodr_browser_session";
    private final JdbcTemplate db;

    BrowserSessions(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public String create(Accounts.Identity user, Instant expires, String previous) {
        revoke(previous);
        db.update("DELETE FROM auth_browser_sessions WHERE expires_at <= now()");
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        db.update("""
                INSERT INTO auth_browser_sessions (token_hash, account_id, session_version, expires_at)
                VALUES (?, ?, ?, ?)
                """, hash(token), user.id(), user.sessionVersion(), Timestamp.from(expires));
        return token;
    }

    Optional<Session> find(String token) {
        if (!valid(token)) {
            return Optional.empty();
        }
        return db.query("""
                SELECT a.id, a.username, a.role, a.session_version, s.expires_at
                FROM auth_browser_sessions s JOIN auth_accounts a ON s.account_id = a.id
                WHERE s.token_hash = ? AND s.expires_at > now() AND a.enabled AND a.session_version = s.session_version
                """, (row, index) -> new Session(new Accounts.Identity(row.getObject("id", UUID.class),
                        row.getString("username"), row.getString("role"), row.getLong("session_version")),
                        row.getTimestamp("expires_at").toInstant()), hash(token)).stream().findFirst();
    }

    void revoke(String token) {
        if (valid(token)) {
            db.update("DELETE FROM auth_browser_sessions WHERE token_hash = ?", hash(token));
        }
    }

    private static boolean valid(String token) {
        return token != null && token.length() == 73;
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    record Session(Accounts.Identity identity, Instant expiresAt) {
    }
}
