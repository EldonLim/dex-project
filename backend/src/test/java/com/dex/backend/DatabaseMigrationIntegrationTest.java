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
    void allMigrationsWereSuccessful() {
        Integer successfulMigrations = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE success = TRUE
                  AND version IN ('1', '2', '3', '4')
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

        assertEquals(4, successfulMigrations);
        assertEquals(0, failedMigrations);
    }

    @Test
    void signedSettlementPayloadColumnAndConstraintsExist() {
        Integer columnCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'execution_attempts'
                  AND column_name = 'signed_transaction_hex'
                  AND data_type = 'text'
                """,
                Integer.class
        );

        Integer constraintCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'execution_attempts'
                  AND constraint_name IN (
                      'ck_execution_attempts_signed_transaction',
                      'ck_execution_attempts_transaction_identity',
                      'ck_execution_attempts_resolution_state'
                  )
                """,
                Integer.class
        );

        assertEquals(1, columnCount);
        assertEquals(3, constraintCount);
    }

    @Test
    void multiContractIdentityAndExecutionPriceColumnsExist() {
        Integer columnCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND (
                      (table_name = 'chain_deployments'
                          AND column_name = 'market_registry_address'
                          AND is_nullable = 'NO')
                      OR
                      (table_name = 'execution_attempts'
                          AND column_name = 'execution_price_quote_atoms_per_lot'
                          AND is_nullable = 'NO')
                  )
                """,
                Integer.class
        );

        Integer constraintCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND constraint_name IN (
                      'ck_chain_deployments_market_registry_address',
                      'uq_chain_deployments_identity',
                      'ck_execution_attempts_execution_price'
                  )
                """,
                Integer.class
        );

        assertEquals(2, columnCount);
        assertEquals(3, constraintCount);
    }
}
