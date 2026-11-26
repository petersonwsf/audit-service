package com.petersonwsf.audit.domain;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ApiKeyRepository {

    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final JdbcClient jdbc;

    public ApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ApiKey key, byte[] hash) {
        jdbc.sql("""
                        insert into api_key (id, application_code, key_prefix, key_hash, created_at)
                        values (:id, :app, :prefix, :hash, :createdAt)
                        """)
                .param("id", key.id())
                .param("app", key.applicationCode())
                .param("prefix", key.prefix())
                .param("hash", hash)
                .param("createdAt", Db.ts(key.createdAt()))
                .update();
    }

    /** Chave ativa (não revogada) de uma aplicação também ativa, pelo hash. */
    public Optional<ApiKey> findActiveByHash(byte[] hash) {
        return jdbc.sql("""
                        select k.* from api_key k
                        join application a on a.code = k.application_code
                        where k.key_hash = :hash and k.revoked_at is null and a.active
                        """)
                .param("hash", hash)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public Optional<ApiKey> find(String applicationCode, UUID id) {
        return jdbc.sql("select * from api_key where id = :id and application_code = :app")
                .param("id", id)
                .param("app", applicationCode)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public List<ApiKey> findByApplication(String applicationCode) {
        return jdbc.sql("select * from api_key where application_code = :app order by created_at")
                .param("app", applicationCode)
                .query((rs, n) -> map(rs))
                .list();
    }

    public int countActive(String applicationCode) {
        return jdbc.sql("select count(*) from api_key where application_code = :app and revoked_at is null")
                .param("app", applicationCode)
                .query(Integer.class)
                .single();
    }

    public void revoke(UUID id, Instant when) {
        jdbc.sql("update api_key set revoked_at = :when where id = :id and revoked_at is null")
                .param("id", id)
                .param("when", Db.ts(when))
                .update();
    }

    /** Atualiza o último uso no máximo uma vez por minuto (evita uma escrita por requisição). */
    public void touch(UUID id, Instant now) {
        jdbc.sql("""
                        update api_key set last_used_at = :now
                        where id = :id and (last_used_at is null or last_used_at < :limit)
                        """)
                .param("id", id)
                .param("now", Db.ts(now))
                .param("limit", Db.ts(now.minus(TOUCH_INTERVAL)))
                .update();
    }

    private static ApiKey map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiKey(Db.uuid(rs, "id"), rs.getString("application_code"), rs.getString("key_prefix"),
                Db.instant(rs, "created_at"), Db.instant(rs, "last_used_at"), Db.instant(rs, "revoked_at"));
    }
}
