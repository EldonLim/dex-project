ALTER TABLE execution_attempts
    ADD COLUMN signed_transaction_hex TEXT;

ALTER TABLE execution_attempts
    ADD CONSTRAINT ck_execution_attempts_signed_transaction
        CHECK (
            signed_transaction_hex IS NULL
            OR signed_transaction_hex ~ '^0x([0-9a-f]{2})+$'
        ),

    ADD CONSTRAINT ck_execution_attempts_transaction_identity
        CHECK (
            (
                status IN ('RESERVED', 'ABANDONED')
                AND executor_nonce IS NULL
                AND transaction_hash IS NULL
                AND signed_transaction_hex IS NULL
            )
            OR
            (
                status IN (
                    'SIGNED',
                    'SUBMITTED',
                    'CONFIRMED',
                    'REVERTED',
                    'UNCERTAIN'
                )
                AND executor_nonce IS NOT NULL
                AND transaction_hash IS NOT NULL
                AND signed_transaction_hex IS NOT NULL
            )
        ),

    ADD CONSTRAINT ck_execution_attempts_resolution_state
        CHECK (
            (
                status IN ('CONFIRMED', 'REVERTED', 'ABANDONED')
                AND resolved_at IS NOT NULL
            )
            OR
            (
                status IN ('RESERVED', 'SIGNED', 'SUBMITTED', 'UNCERTAIN')
                AND resolved_at IS NULL
            )
        );
