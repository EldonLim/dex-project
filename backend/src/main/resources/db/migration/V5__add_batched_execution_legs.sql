ALTER TABLE execution_attempts
    ADD CONSTRAINT uq_execution_attempts_attempt_deployment
        UNIQUE (attempt_id, deployment_id);

CREATE TABLE execution_attempt_legs (
    attempt_id UUID NOT NULL,
    deployment_id VARCHAR(128) NOT NULL,
    leg_index INTEGER NOT NULL,
    market_id NUMERIC(78, 0) NOT NULL,
    buy_order_id NUMERIC(78, 0) NOT NULL,
    sell_order_id NUMERIC(78, 0) NOT NULL,
    fill_lots NUMERIC(78, 0) NOT NULL,
    execution_price_quote_atoms_per_lot NUMERIC(78, 0) NOT NULL,
    confirmed_event_log_index NUMERIC(78, 0),

    CONSTRAINT pk_execution_attempt_legs
        PRIMARY KEY (attempt_id, leg_index),
    CONSTRAINT fk_execution_attempt_legs_attempt
        FOREIGN KEY (attempt_id, deployment_id)
        REFERENCES execution_attempts (attempt_id, deployment_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_execution_attempt_legs_market
        FOREIGN KEY (deployment_id, market_id)
        REFERENCES markets (deployment_id, market_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_execution_attempt_legs_buy_order
        FOREIGN KEY (deployment_id, market_id, buy_order_id)
        REFERENCES orders (deployment_id, market_id, order_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_execution_attempt_legs_sell_order
        FOREIGN KEY (deployment_id, market_id, sell_order_id)
        REFERENCES orders (deployment_id, market_id, order_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_execution_attempt_legs_pair
        UNIQUE (attempt_id, buy_order_id, sell_order_id),
    CONSTRAINT uq_execution_attempt_legs_event
        UNIQUE (attempt_id, confirmed_event_log_index),
    CONSTRAINT ck_execution_attempt_legs_index
        CHECK (leg_index >= 0 AND leg_index < 10),
    CONSTRAINT ck_execution_attempt_legs_distinct_orders
        CHECK (buy_order_id <> sell_order_id),
    CONSTRAINT ck_execution_attempt_legs_fill_lots
        CHECK (fill_lots > 0),
    CONSTRAINT ck_execution_attempt_legs_execution_price
        CHECK (execution_price_quote_atoms_per_lot > 0),
    CONSTRAINT ck_execution_attempt_legs_confirmed_log_index
        CHECK (
            confirmed_event_log_index IS NULL
            OR confirmed_event_log_index >= 0
        )
);

INSERT INTO execution_attempt_legs (
    attempt_id,
    deployment_id,
    leg_index,
    market_id,
    buy_order_id,
    sell_order_id,
    fill_lots,
    execution_price_quote_atoms_per_lot
)
SELECT
    attempt_id,
    deployment_id,
    0,
    market_id,
    buy_order_id,
    sell_order_id,
    fill_lots,
    execution_price_quote_atoms_per_lot
FROM execution_attempts;

CREATE INDEX idx_execution_attempt_legs_buy_order
    ON execution_attempt_legs (deployment_id, buy_order_id, attempt_id);

CREATE INDEX idx_execution_attempt_legs_sell_order
    ON execution_attempt_legs (deployment_id, sell_order_id, attempt_id);

ALTER TABLE execution_attempts
    DROP CONSTRAINT fk_execution_attempts_market,
    DROP CONSTRAINT fk_execution_attempts_buy_order,
    DROP CONSTRAINT fk_execution_attempts_sell_order,
    DROP CONSTRAINT ck_execution_attempts_distinct_orders,
    DROP CONSTRAINT ck_execution_attempts_fill_lots,
    DROP CONSTRAINT ck_execution_attempts_execution_price,
    DROP COLUMN market_id,
    DROP COLUMN buy_order_id,
    DROP COLUMN sell_order_id,
    DROP COLUMN fill_lots,
    DROP COLUMN execution_price_quote_atoms_per_lot;
