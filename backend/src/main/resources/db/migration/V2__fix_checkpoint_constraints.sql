ALTER TABLE indexer_checkpoints
    DROP CONSTRAINT ck_indexer_checkpoints_error_details,
    DROP CONSTRAINT ck_indexer_checkpoints_pause_details,
    DROP CONSTRAINT ck_indexer_checkpoints_block_order;


ALTER TABLE indexer_checkpoints
    ADD CONSTRAINT ck_indexer_checkpoints_error_details
        CHECK (
            (
                last_error_at IS NULL
                AND last_error_message IS NULL
            )
            OR
            (
                last_error_at IS NOT NULL
                AND last_error_message IS NOT NULL
                AND char_length(trim(last_error_message)) > 0
            )
        ),

    ADD CONSTRAINT ck_indexer_checkpoints_pause_details
        CHECK (
            (
                paused = FALSE
                AND pause_reason IS NULL
            )
            OR
            (
                paused = TRUE
                AND pause_reason IS NOT NULL
                AND char_length(trim(pause_reason)) > 0
            )
        ),

    ADD CONSTRAINT ck_indexer_checkpoints_block_order
        CHECK (
            latest_observed_block_number
                >= last_processed_block_number
            OR paused = TRUE
        );
