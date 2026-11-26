package com.petersonwsf.audit.domain;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ApplicationRepository {

    private final JdbcClient jdbc;

    public ApplicationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Application application) {
        jdbc.sql("""
                        insert into application (code, name, active, retention_days, created_at)
                        values (:code, :name, :active, :retention, :createdAt)
                        """)
                .param("code", application.code())
                .param("name", application.name())
                .param("active", application.active())
                .param("retention", application.retentionDays())
                .param("createdAt", Db.ts(application.createdAt()))
                .update();
    }

    public Optional<Application> find(String code) {
        return jdbc.sql("select * from application where code = :code")
                .param("code", code)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public List<Application> findAll() {
        return jdbc.sql("select * from application order by code")
                .query((rs, n) -> map(rs))
                .list();
    }

    public void update(Application application) {
        jdbc.sql("update application set name = :name, active = :active, retention_days = :retention where code = :code")
                .param("code", application.code())
                .param("name", application.name())
                .param("active", application.active())
                .param("retention", application.retentionDays())
                .update();
    }

    /** Maior retenção entre as aplicações (limite para descartar partições inteiras). */
    public int maxRetentionDays() {
        Integer max = jdbc.sql("select max(retention_days) from application").query(Integer.class).single();
        return max == null ? 0 : max;
    }

    private static Application map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Instant created = Db.instant(rs, "created_at");
        return new Application(rs.getString("code"), rs.getString("name"), rs.getBoolean("active"),
                rs.getInt("retention_days"), created);
    }
}
