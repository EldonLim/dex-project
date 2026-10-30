ALTER TABLE chain_deployments
    ADD COLUMN market_registry_address VARCHAR(42);

-- Existing deployments predate the multi-contract identity model. No
-- production Ethereum gateway existed at that point, so retaining the DEX
-- address is the only safe deterministic legacy value.
UPDATE chain_deployments
SET market_registry_address = dex_address;

ALTER TABLE chain_deployments
    ALTER COLUMN market_registry_address SET NOT NULL,
    ADD CONSTRAINT ck_chain_deployments_market_registry_address
        CHECK (market_registry_address ~ '^0x[0-9a-f]{40}$'),
    DROP CONSTRAINT uq_chain_deployments_identity,
    ADD CONSTRAINT uq_chain_deployments_identity
        UNIQUE (
            chain_id,
            dex_address,
            market_registry_address,
            deployment_block_hash
        );

ALTER TABLE execution_attempts
    ADD COLUMN execution_price_quote_atoms_per_lot NUMERIC(78, 0);

-- Reconstruct the matcher-selected price for any local attempts created
-- before this column existed. The matcher defines the older order by chain
-- position and then by order ID.
UPDATE execution_attempts AS attempt
SET execution_price_quote_atoms_per_lot = CASE
    WHEN ROW(
        buy_order.creation_block_number,
        buy_order.creation_transaction_index,
        buy_order.creation_log_index,
        buy_order.order_id
    ) < ROW(
        sell_order.creation_block_number,
        sell_order.creation_transaction_index,
        sell_order.creation_log_index,
        sell_order.order_id
    )
        THEN buy_order.limit_price_quote_atoms_per_lot
    ELSE sell_order.limit_price_quote_atoms_per_lot
END
FROM orders AS buy_order,
     orders AS sell_order
WHERE buy_order.deployment_id = attempt.deployment_id
  AND buy_order.order_id = attempt.buy_order_id
  AND sell_order.deployment_id = attempt.deployment_id
  AND sell_order.order_id = attempt.sell_order_id;

ALTER TABLE execution_attempts
    ALTER COLUMN execution_price_quote_atoms_per_lot SET NOT NULL,
    ADD CONSTRAINT ck_execution_attempts_execution_price
        CHECK (execution_price_quote_atoms_per_lot > 0);
