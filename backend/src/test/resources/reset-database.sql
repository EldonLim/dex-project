TRUNCATE TABLE
    execution_attempt_legs,
    execution_attempts,
    trades,
    processed_events,
    orders,
    indexer_checkpoints,
    markets,
    chain_deployments
RESTART IDENTITY CASCADE;
