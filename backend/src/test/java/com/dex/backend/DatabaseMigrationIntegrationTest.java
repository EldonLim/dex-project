package com.dex.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DatabaseMigrationIntegrationTest {

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "chain_deployments",
            "markets",
            "orders",
            "trades",
            "execution_attempts",
            "processed_events",
            "indexer_checkpoints",
            "flyway_schema_history"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatesExpectedSchema() {
        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE'
                """,
                String.class
        );

        assertEquals(EXPECTED_TABLES, new HashSet<>(tables));
    }

    @Test
    void bothMigrationsWereSuccessful() {
        Integer successfulMigrations = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE success = TRUE
                  AND version IN ('1', '2')
                """,
                Integer.class
        );

        Integer failedMigrations = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE success = FALSE
                """,
                Integer.class
        );

        assertEquals(2, successfulMigrations);
        assertEquals(0, failedMigrations);
    }
}
