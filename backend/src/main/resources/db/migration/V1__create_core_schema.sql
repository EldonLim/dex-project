CREATE TABLE chain_deployments (
    deployment_id VARCHAR(128) NOT NULL,
    adapter_mode VARCHAR(16) NOT NULL,
    chain_id NUMERIC(78, 0) NOT NULL,
    dex_address VARCHAR(42) NOT NULL,
    deployment_block_number NUMERIC(78, 0) NOT NULL,
    deployment_block_hash VARCHAR(66) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_chain_deployments
        PRIMARY KEY (deployment_id),

    CONSTRAINT ck_chain_deployments_adapter_mode
        CHECK (adapter_mode IN ('MOCK', 'ETHEREUM')),

    CONSTRAINT ck_chain_deployments_chain_id
        CHECK (chain_id >= 0),

    CONSTRAINT ck_chain_deployments_block_number
        CHECK (deployment_block_number >= 0),

    CONSTRAINT ck_chain_deployments_dex_address
        CHECK (dex_address ~ '^0x[0-9a-f]{40}$'),

    CONSTRAINT ck_chain_deployments_block_hash
        CHECK (deployment_block_hash ~ '^0x[0-9a-f]{64}$'),

    CONSTRAINT uq_chain_deployments_identity
        UNIQUE (chain_id, dex_address, deployment_block_hash)
);

CREATE TABLE markets (
    deployment_id VARCHAR(128) NOT NULL,
    market_id NUMERIC(78, 0) NOT NULL,
    base_token_address VARCHAR(42) NOT NULL,
    quote_token_address VARCHAR(42) NOT NULL,
    base_symbol VARCHAR(32) NOT NULL,
    quote_symbol VARCHAR(32) NOT NULL,
    base_decimals SMALLINT NOT NULL,
    quote_decimals SMALLINT NOT NULL,
    base_lot_size_atoms NUMERIC(78, 0) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_markets
        PRIMARY KEY (deployment_id, market_id),

    CONSTRAINT fk_markets_deployment
        FOREIGN KEY (deployment_id)
        REFERENCES chain_deployments (deployment_id)
        ON DELETE RESTRICT,

    CONSTRAINT ck_markets_market_id
        CHECK (market_id >= 0),

    CONSTRAINT ck_markets_base_token_address
        CHECK (base_token_address ~ '^0x[0-9a-f]{40}$'),

    CONSTRAINT ck_markets_quote_token_address
        CHECK (quote_token_address ~ '^0x[0-9a-f]{40}$'),

    CONSTRAINT ck_markets_distinct_tokens
        CHECK (base_token_address <> quote_token_address),

    CONSTRAINT ck_markets_base_symbol
        CHECK (char_length(trim(base_symbol)) > 0),

    CONSTRAINT ck_markets_quote_symbol
        CHECK (char_length(trim(quote_symbol)) > 0),

    CONSTRAINT ck_markets_base_decimals
        CHECK (base_decimals BETWEEN 0 AND 255),

    CONSTRAINT ck_markets_quote_decimals
        CHECK (quote_decimals BETWEEN 0 AND 255),

    CONSTRAINT ck_markets_lot_size
        CHECK (base_lot_size_atoms > 0)
);

CREATE TABLE orders (
    deployment_id VARCHAR(128) NOT NULL,
    order_id NUMERIC(78, 0) NOT NULL,
    market_id NUMERIC(78, 0) NOT NULL,
    owner_address VARCHAR(42) NOT NULL,
    side VARCHAR(4) NOT NULL,
    limit_price_quote_atoms_per_lot NUMERIC(78, 0) NOT NULL,
    original_lots NUMERIC(78, 0) NOT NULL,
    remaining_lots NUMERIC(78, 0) NOT NULL,
    status VARCHAR(20) NOT NULL,
    creation_block_number NUMERIC(78, 0) NOT NULL,
    creation_transaction_index NUMERIC(78, 0) NOT NULL,
    creation_log_index NUMERIC(78, 0) NOT NULL,
    creation_transaction_hash VARCHAR(66) NOT NULL,
    creation_block_hash VARCHAR(66) NOT NULL,
    block_timestamp TIMESTAMPTZ NOT NULL,
    projection_updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_orders
        PRIMARY KEY (deployment_id, order_id),

    CONSTRAINT fk_orders_market
        FOREIGN KEY (deployment_id, market_id)
        REFERENCES markets (deployment_id, market_id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_orders_market_identity
        UNIQUE (deployment_id, market_id, order_id),

    CONSTRAINT uq_orders_creation_event
        UNIQUE (
            deployment_id,
            creation_transaction_hash,
            creation_log_index
        ),

    CONSTRAINT ck_orders_order_id
        CHECK (order_id >= 0),

    CONSTRAINT ck_orders_owner_address
        CHECK (owner_address ~ '^0x[0-9a-f]{40}$'),

    CONSTRAINT ck_orders_side
        CHECK (side IN ('BUY', 'SELL')),

    CONSTRAINT ck_orders_limit_price
        CHECK (limit_price_quote_atoms_per_lot > 0),

    CONSTRAINT ck_orders_original_lots
        CHECK (original_lots > 0),

    CONSTRAINT ck_orders_remaining_lots
        CHECK (
            remaining_lots >= 0
            AND remaining_lots <= original_lots
        ),

    CONSTRAINT ck_orders_status
        CHECK (
            status IN (
                'OPEN',
                'PARTIALLY_FILLED',
                'FILLED',
                'CANCELLED'
            )
        ),

    CONSTRAINT ck_orders_status_quantity_consistency
        CHECK (
            (status = 'OPEN'
                AND remaining_lots = original_lots)
            OR
            (status = 'PARTIALLY_FILLED'
                AND remaining_lots > 0
                AND remaining_lots < original_lots)
            OR
            (status = 'FILLED'
                AND remaining_lots = 0)
            OR
            (status = 'CANCELLED'
                AND remaining_lots >= 0
                AND remaining_lots <= original_lots)
        ),

    CONSTRAINT ck_orders_creation_block_number
        CHECK (creation_block_number >= 0),

    CONSTRAINT ck_orders_creation_transaction_index
        CHECK (creation_transaction_index >= 0),

    CONSTRAINT ck_orders_creation_log_index
        CHECK (creation_log_index >= 0),

    CONSTRAINT ck_orders_creation_transaction_hash
        CHECK (
            creation_transaction_hash
            ~ '^0x[0-9a-f]{64}$'
        ),

    CONSTRAINT ck_orders_creation_block_hash
        CHECK (
            creation_block_hash
            ~ '^0x[0-9a-f]{64}$'
        )
);

CREATE TABLE processed_events (
    deployment_id VARCHAR(128) NOT NULL,
    transaction_hash VARCHAR(66) NOT NULL,
    log_index NUMERIC(78, 0) NOT NULL,
    contract_address VARCHAR(42) NOT NULL,
    event_name VARCHAR(64) NOT NULL,
    block_number NUMERIC(78, 0) NOT NULL,
    block_hash VARCHAR(66) NOT NULL,
    transaction_index NUMERIC(78, 0) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_processed_events
        PRIMARY KEY (
            deployment_id,
            transaction_hash,
            log_index
        ),

    CONSTRAINT fk_processed_events_deployment
        FOREIGN KEY (deployment_id)
        REFERENCES chain_deployments (deployment_id)
        ON DELETE RESTRICT,

    CONSTRAINT ck_processed_events_transaction_hash
        CHECK (transaction_hash ~ '^0x[0-9a-f]{64}$'),

    CONSTRAINT ck_processed_events_contract_address
        CHECK (contract_address ~ '^0x[0-9a-f]{40}$'),

    CONSTRAINT ck_processed_events_block_hash
        CHECK (block_hash ~ '^0x[0-9a-f]{64}$'),

    CONSTRAINT ck_processed_events_log_index
        CHECK (log_index >= 0),

    CONSTRAINT ck_processed_events_block_number
        CHECK (block_number >= 0),

    CONSTRAINT ck_processed_events_transaction_index
        CHECK (transaction_index >= 0),

    CONSTRAINT ck_processed_events_event_name
        CHECK (char_length(trim(event_name)) > 0)
);

CREATE TABLE trades (
    deployment_id VARCHAR(128) NOT NULL,
    transaction_hash VARCHAR(66) NOT NULL,
    log_index NUMERIC(78, 0) NOT NULL,
    trade_id NUMERIC(78, 0),
    market_id NUMERIC(78, 0) NOT NULL,
    buy_order_id NUMERIC(78, 0) NOT NULL,
    sell_order_id NUMERIC(78, 0) NOT NULL,
    fill_lots NUMERIC(78, 0) NOT NULL,
    execution_price_quote_atoms_per_lot NUMERIC(78, 0) NOT NULL,
    buy_order_remaining_lots NUMERIC(78, 0) NOT NULL,
    sell_order_remaining_lots NUMERIC(78, 0) NOT NULL,
    block_number NUMERIC(78, 0) NOT NULL,
    transaction_index NUMERIC(78, 0) NOT NULL,
    block_hash VARCHAR(66) NOT NULL,
    block_timestamp TIMESTAMPTZ NOT NULL,
    projection_created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_trades
        PRIMARY KEY (
            deployment_id,
            transaction_hash,
            log_index
        ),

    CONSTRAINT fk_trades_deployment
        FOREIGN KEY (deployment_id)
        REFERENCES chain_deployments (deployment_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_trades_market
        FOREIGN KEY (deployment_id, market_id)
        REFERENCES markets (deployment_id, market_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_trades_buy_order
        FOREIGN KEY (
            deployment_id,
            market_id,
            buy_order_id
        )
        REFERENCES orders (
            deployment_id,
            market_id,
            order_id
        )
        ON DELETE RESTRICT,

    CONSTRAINT fk_trades_sell_order
        FOREIGN KEY (
            deployment_id,
            market_id,
            sell_order_id
        )
        REFERENCES orders (
            deployment_id,
            market_id,
            order_id
        )
        ON DELETE RESTRICT,

    CONSTRAINT uq_trades_trade_id
        UNIQUE (deployment_id, trade_id),

    CONSTRAINT ck_trades_trade_id
        CHECK (trade_id IS NULL OR trade_id >= 0),

    CONSTRAINT ck_trades_distinct_orders
        CHECK (buy_order_id <> sell_order_id),

    CONSTRAINT ck_trades_fill_lots
        CHECK (fill_lots > 0),

    CONSTRAINT ck_trades_execution_price
        CHECK (execution_price_quote_atoms_per_lot > 0),

    CONSTRAINT ck_trades_buy_remaining_lots
        CHECK (buy_order_remaining_lots >= 0),

    CONSTRAINT ck_trades_sell_remaining_lots
        CHECK (sell_order_remaining_lots >= 0),

    CONSTRAINT ck_trades_block_number
        CHECK (block_number >= 0),

    CONSTRAINT ck_trades_transaction_index
        CHECK (transaction_index >= 0),

    CONSTRAINT ck_trades_log_index
        CHECK (log_index >= 0),

    CONSTRAINT ck_trades_transaction_hash
        CHECK (transaction_hash ~ '^0x[0-9a-f]{64}$'),

    CONSTRAINT ck_trades_block_hash
        CHECK (block_hash ~ '^0x[0-9a-f]{64}$')
);

CREATE TABLE execution_attempts (
    attempt_id UUID NOT NULL,
    deployment_id VARCHAR(128) NOT NULL,
    market_id NUMERIC(78, 0) NOT NULL,
    buy_order_id NUMERIC(78, 0) NOT NULL,
    sell_order_id NUMERIC(78, 0) NOT NULL,
    fill_lots NUMERIC(78, 0) NOT NULL,
    executor_address VARCHAR(42) NOT NULL,
    executor_nonce NUMERIC(78, 0),
    transaction_hash VARCHAR(66),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    error_message TEXT,
    revert_reason TEXT,

    CONSTRAINT pk_execution_attempts
        PRIMARY KEY (attempt_id),

    CONSTRAINT fk_execution_attempts_deployment
        FOREIGN KEY (deployment_id)
        REFERENCES chain_deployments (deployment_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_execution_attempts_market
        FOREIGN KEY (deployment_id, market_id)
        REFERENCES markets (deployment_id, market_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_execution_attempts_buy_order
        FOREIGN KEY (
            deployment_id,
            market_id,
            buy_order_id
        )
        REFERENCES orders (
            deployment_id,
            market_id,
            order_id
        )
        ON DELETE RESTRICT,

    CONSTRAINT fk_execution_attempts_sell_order
        FOREIGN KEY (
            deployment_id,
            market_id,
            sell_order_id
        )
        REFERENCES orders (
            deployment_id,
            market_id,
            order_id
        )
        ON DELETE RESTRICT,

    CONSTRAINT uq_execution_attempts_transaction_hash
        UNIQUE (deployment_id, transaction_hash),

    CONSTRAINT uq_execution_attempts_executor_nonce
        UNIQUE (
            deployment_id,
            executor_address,
            executor_nonce
        ),

    CONSTRAINT ck_execution_attempts_distinct_orders
        CHECK (buy_order_id <> sell_order_id),

    CONSTRAINT ck_execution_attempts_fill_lots
        CHECK (fill_lots > 0),

    CONSTRAINT ck_execution_attempts_executor_address
        CHECK (executor_address ~ '^0x[0-9a-f]{40}$'),

    CONSTRAINT ck_execution_attempts_executor_nonce
        CHECK (
            executor_nonce IS NULL
            OR executor_nonce >= 0
        ),

    CONSTRAINT ck_execution_attempts_transaction_hash
        CHECK (
            transaction_hash IS NULL
            OR transaction_hash ~ '^0x[0-9a-f]{64}$'
        ),

    CONSTRAINT ck_execution_attempts_status
        CHECK (
            status IN (
                'RESERVED',
                'SIGNED',
                'SUBMITTED',
                'CONFIRMED',
                'REVERTED',
                'UNCERTAIN',
                'ABANDONED'
            )
        )
);

CREATE TABLE indexer_checkpoints (
    deployment_id VARCHAR(128) NOT NULL,
    last_processed_block_number NUMERIC(78, 0) NOT NULL,
    last_processed_block_hash VARCHAR(66) NOT NULL,
    latest_observed_block_number NUMERIC(78, 0) NOT NULL,
    latest_observed_block_hash VARCHAR(66) NOT NULL,
    last_successful_sync_at TIMESTAMPTZ NOT NULL,
    last_error_at TIMESTAMPTZ,
    last_error_message TEXT,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    pause_reason TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_indexer_checkpoints
        PRIMARY KEY (deployment_id),

    CONSTRAINT fk_indexer_checkpoints_deployment
        FOREIGN KEY (deployment_id)
        REFERENCES chain_deployments (deployment_id)
        ON DELETE RESTRICT,

    CONSTRAINT ck_indexer_checkpoints_processed_block
        CHECK (last_processed_block_number >= 0),

    CONSTRAINT ck_indexer_checkpoints_observed_block
        CHECK (latest_observed_block_number >= 0),

    CONSTRAINT ck_indexer_checkpoints_block_order
        CHECK (
            latest_observed_block_number
            >= last_processed_block_number
        ),

    CONSTRAINT ck_indexer_checkpoints_processed_hash
        CHECK (
            last_processed_block_hash
            ~ '^0x[0-9a-f]{64}$'
        ),

    CONSTRAINT ck_indexer_checkpoints_observed_hash
        CHECK (
            latest_observed_block_hash
            ~ '^0x[0-9a-f]{64}$'
        ),

    CONSTRAINT ck_indexer_checkpoints_error_details
        CHECK (
            (
                last_error_at IS NULL
                AND last_error_message IS NULL
            )
            OR
            (
                last_error_at IS NOT NULL
                AND char_length(trim(last_error_message)) > 0
            )
        ),

    CONSTRAINT ck_indexer_checkpoints_pause_details
        CHECK (
            (
                paused = FALSE
                AND pause_reason IS NULL
            )
            OR
            (
                paused = TRUE
                AND char_length(trim(pause_reason)) > 0
            )
        )
);

CREATE INDEX idx_orders_orderbook_bids
    ON orders (
        deployment_id,
        market_id,
        limit_price_quote_atoms_per_lot DESC,
        creation_block_number ASC,
        creation_transaction_index ASC,
        creation_log_index ASC
    )
    WHERE side = 'BUY'
      AND status IN ('OPEN', 'PARTIALLY_FILLED')
      AND remaining_lots > 0;


CREATE INDEX idx_orders_orderbook_asks
    ON orders (
        deployment_id,
        market_id,
        limit_price_quote_atoms_per_lot ASC,
        creation_block_number ASC,
        creation_transaction_index ASC,
        creation_log_index ASC
    )
    WHERE side = 'SELL'
      AND status IN ('OPEN', 'PARTIALLY_FILLED')
      AND remaining_lots > 0;


CREATE INDEX idx_orders_owner_chronology
    ON orders (
        deployment_id,
        owner_address,
        creation_block_number DESC,
        creation_transaction_index DESC,
        creation_log_index DESC
    );


CREATE INDEX idx_orders_market_status
    ON orders (
        deployment_id,
        market_id,
        status
    );


CREATE INDEX idx_trades_market_chronology
    ON trades (
        deployment_id,
        market_id,
        block_number DESC,
        transaction_index DESC,
        log_index DESC
    );


CREATE INDEX idx_processed_events_block_position
    ON processed_events (
        deployment_id,
        block_number ASC,
        transaction_index ASC,
        log_index ASC
    );


CREATE INDEX idx_execution_attempts_status
    ON execution_attempts (
        deployment_id,
        status,
        updated_at ASC
    );


CREATE INDEX idx_execution_attempts_buy_order
    ON execution_attempts (
        deployment_id,
        buy_order_id,
        status
    );


CREATE INDEX idx_execution_attempts_sell_order
    ON execution_attempts (
        deployment_id,
        sell_order_id,
        status
    );