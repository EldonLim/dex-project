package com.dex.backend;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
            "execution_attempt_legs",
            "processed_events",
            "indexer_checkpoints",
            "flyway_schema_history"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

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
                  AND version IN ('1', '2', '3', '4', '5')
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

        assertEquals(5, successfulMigrations);
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
                      (table_name = 'execution_attempt_legs'
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
                      'ck_execution_attempt_legs_execution_price'
                  )
                """,
                Integer.class
        );

        assertEquals(2, columnCount);
        assertEquals(3, constraintCount);
    }

    @Test
    void batchedExecutionLegSchemaReplacesDuplicatedHeaderFields() {
        Integer legColumns = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'execution_attempt_legs'
                  AND column_name IN (
                      'attempt_id', 'deployment_id', 'leg_index', 'market_id',
                      'buy_order_id', 'sell_order_id', 'fill_lots',
                      'execution_price_quote_atoms_per_lot',
                      'confirmed_event_log_index'
                  )
                """,
                Integer.class
        );
        Integer removedHeaderColumns = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'execution_attempts'
                  AND column_name IN (
                      'market_id', 'buy_order_id', 'sell_order_id',
                      'fill_lots', 'execution_price_quote_atoms_per_lot'
                  )
                """,
                Integer.class
        );
        Integer legConstraints = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'execution_attempt_legs'
                  AND constraint_name IN (
                      'pk_execution_attempt_legs',
                      'fk_execution_attempt_legs_attempt',
                      'fk_execution_attempt_legs_market',
                      'fk_execution_attempt_legs_buy_order',
                      'fk_execution_attempt_legs_sell_order',
                      'uq_execution_attempt_legs_pair',
                      'uq_execution_attempt_legs_event',
                      'ck_execution_attempt_legs_index',
                      'ck_execution_attempt_legs_distinct_orders',
                      'ck_execution_attempt_legs_fill_lots',
                      'ck_execution_attempt_legs_execution_price',
                      'ck_execution_attempt_legs_confirmed_log_index'
                  )
                """,
                Integer.class
        );

        assertEquals(9, legColumns);
        assertEquals(0, removedHeaderColumns);
        assertEquals(12, legConstraints);
    }

    @Test
    void v5BackfillsAnExistingSingleLegAttempt() {
        String schema = "migration_batch_"
                + UUID.randomUUID().toString().replace("-", "");
        String attemptId = UUID.randomUUID().toString();

        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("4"))
                    .load()
                    .migrate();

            insertV4ExecutionFixture(schema, attemptId);

            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            var backfilled = jdbcTemplate.queryForMap("""
                    SELECT deployment_id, leg_index, market_id,
                           buy_order_id, sell_order_id, fill_lots,
                           execution_price_quote_atoms_per_lot,
                           confirmed_event_log_index
                    FROM %s.execution_attempt_legs
                    WHERE attempt_id = ?::uuid
                    """.formatted(schema), attemptId);

            assertEquals("legacy-batch", backfilled.get("deployment_id"));
            assertEquals(0, backfilled.get("leg_index"));
            assertEquals("1", backfilled.get("market_id").toString());
            assertEquals("10", backfilled.get("buy_order_id").toString());
            assertEquals("11", backfilled.get("sell_order_id").toString());
            assertEquals("4", backfilled.get("fill_lots").toString());
            assertEquals(
                    "2500000",
                    backfilled.get("execution_price_quote_atoms_per_lot")
                            .toString()
            );
            assertNull(backfilled.get("confirmed_event_log_index"));
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private void insertV4ExecutionFixture(String schema, String attemptId) {
        jdbcTemplate.update("""
                INSERT INTO %s.chain_deployments (
                    deployment_id, adapter_mode, chain_id, dex_address,
                    deployment_block_number, deployment_block_hash,
                    market_registry_address
                ) VALUES (?, 'MOCK', 31337, ?, 1, ?, ?)
                """.formatted(schema),
                "legacy-batch",
                address('d'),
                hash('a'),
                address('e'));
        jdbcTemplate.update("""
                INSERT INTO %s.markets (
                    deployment_id, market_id, base_token_address,
                    quote_token_address, base_symbol, quote_symbol,
                    base_decimals, quote_decimals, base_lot_size_atoms
                ) VALUES ('legacy-batch', 1, ?, ?, 'TKA', 'TKB', 18, 18, 1000)
                """.formatted(schema), address('1'), address('2'));
        insertV4Order(schema, 10, "BUY", address('3'), hash('b'), 0);
        insertV4Order(schema, 11, "SELL", address('4'), hash('c'), 1);
        jdbcTemplate.update("""
                INSERT INTO %s.execution_attempts (
                    attempt_id, deployment_id, market_id, buy_order_id,
                    sell_order_id, fill_lots, executor_address, status,
                    created_at, updated_at,
                    execution_price_quote_atoms_per_lot
                ) VALUES (?::uuid, 'legacy-batch', 1, 10, 11, 4, ?,
                          'RESERVED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                          2500000)
                """.formatted(schema), attemptId, address('5'));
    }

    private void insertV4Order(
            String schema,
            int orderId,
            String side,
            String owner,
            String transactionHash,
            int logIndex
    ) {
        jdbcTemplate.update("""
                INSERT INTO %s.orders (
                    deployment_id, order_id, market_id, owner_address, side,
                    limit_price_quote_atoms_per_lot, original_lots,
                    remaining_lots, status, creation_block_number,
                    creation_transaction_index, creation_log_index,
                    creation_transaction_hash, creation_block_hash,
                    block_timestamp
                ) VALUES ('legacy-batch', ?, 1, ?, ?, 2500000, 10, 10,
                          'OPEN', 2, 0, ?, ?, ?, CURRENT_TIMESTAMP)
                """.formatted(schema),
                orderId,
                owner,
                side,
                logIndex,
                transactionHash,
                hash('d'));
    }

    private static String address(char value) {
        return "0x" + String.valueOf(value).repeat(40);
    }

    private static String hash(char value) {
        return "0x" + String.valueOf(value).repeat(64);
    }
}
