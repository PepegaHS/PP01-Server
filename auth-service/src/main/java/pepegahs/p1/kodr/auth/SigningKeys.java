package pepegahs.p1.kodr.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

@Configuration(proxyBeanMethods = false)
class SigningKeys {
    @Bean
    @DependsOnDatabaseInitialization
    JWKSource<SecurityContext> jwkSource(JdbcTemplate db) throws Exception {
        String findActive = "SELECT jwk FROM auth_signing_keys WHERE name = 'active'";
        var stored = db.queryForList(findActive, String.class);
        if (stored.isEmpty()) {
            var key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString())
                    .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
            db.update("INSERT INTO auth_signing_keys (name, jwk) VALUES ('active', ?) ON CONFLICT DO NOTHING",
                    key.toJSONString());
        }
        RSAKey signingKey = RSAKey.parse(db.queryForObject(findActive, String.class));
        return new ImmutableJWKSet<>(new JWKSet(signingKey));
    }
}
