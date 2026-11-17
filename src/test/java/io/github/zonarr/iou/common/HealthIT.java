package io.github.zonarr.iou.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zonarr.iou.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class HealthIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void healthIsPublicAndUp() {
        assertThat(api().get("/api/health").expect(200).get("status").asString()).isEqualTo("UP");
    }

    @Test
    void databaseHealthReachesPostgres() {
        assertThat(api().get("/api/health/db").expect(200).get("database").asString()).isEqualTo("UP");
    }

    @Test
    void flywayCreatedTheBaselineSchema() {
        Integer tables = jdbc.sql("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('users', 'groups', 'group_members', 'expenses', 'expense_payers',
                                     'expense_shares', 'debts', 'payments', 'audit_log')
                """).query(Integer.class).single();
        assertThat(tables).isEqualTo(9);
    }
}
