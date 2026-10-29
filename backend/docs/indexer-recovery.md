# Indexer recovery

The blockchain is authoritative. PostgreSQL contains a rebuildable projection,
but the backend never deletes or resets that projection automatically. Recovery
must be performed by a trusted operator after the cause of the failure is
understood.

## Failure behavior

The indexer processes at most one bounded block range per polling invocation.
Its behavior depends on the type of failure:

- A retryable gateway or operational failure records `last_error_at` and
  `last_error_message` when a checkpoint exists. The checkpoint is not advanced,
  and a later poll retries the same range.
- A chain-identity, checkpoint-hash, projection-consistency, or non-retryable
  gateway failure pauses the checkpoint. The worker also halts in memory so
  scheduled and manual invocations perform no more indexing work in that
  application process.
- A successful poll clears a previous transient error. A paused checkpoint is
  never cleared automatically.

Do not edit checkpoint block numbers or hashes to work around a safety failure.
Choose the appropriate recovery path below instead.

## Inspect the current state

Load the repository's ignored `.env` before running the commands in this
document:

```bash
set -a
source .env
set +a
```

Open PostgreSQL from the repository root:

```bash
docker compose exec \
  -e PGPASSWORD="$SPRING_DATASOURCE_PASSWORD" \
  postgres psql \
  --username "$SPRING_DATASOURCE_USERNAME" \
  --dbname "$DATABASE_NAME" \
  --set ON_ERROR_STOP=1 \
  --variable deployment_id="$DEX_DEPLOYMENT_ID"
```

At the `psql` prompt, inspect the configured deployment and checkpoint. The
`:'deployment_id'` form safely quotes the value supplied above as an SQL string:

```sql
SELECT
    d.deployment_id,
    d.adapter_mode,
    d.chain_id,
    d.dex_address,
    d.deployment_block_number,
    d.deployment_block_hash,
    c.last_processed_block_number,
    c.last_processed_block_hash,
    c.latest_observed_block_number,
    c.latest_observed_block_hash,
    c.last_successful_sync_at,
    c.last_error_at,
    c.last_error_message,
    c.paused,
    c.pause_reason
FROM chain_deployments AS d
LEFT JOIN indexer_checkpoints AS c
    ON c.deployment_id = d.deployment_id
WHERE d.deployment_id = :'deployment_id';
```

Use these deployment-scoped counts before any destructive recovery:

```sql
SELECT 'chain_deployments' AS table_name, COUNT(*) AS row_count
FROM chain_deployments WHERE deployment_id = :'deployment_id'
UNION ALL
SELECT 'markets', COUNT(*)
FROM markets WHERE deployment_id = :'deployment_id'
UNION ALL
SELECT 'orders', COUNT(*)
FROM orders WHERE deployment_id = :'deployment_id'
UNION ALL
SELECT 'trades', COUNT(*)
FROM trades WHERE deployment_id = :'deployment_id'
UNION ALL
SELECT 'execution_attempts', COUNT(*)
FROM execution_attempts WHERE deployment_id = :'deployment_id'
UNION ALL
SELECT 'processed_events', COUNT(*)
FROM processed_events WHERE deployment_id = :'deployment_id'
UNION ALL
SELECT 'indexer_checkpoints', COUNT(*)
FROM indexer_checkpoints WHERE deployment_id = :'deployment_id';
```

## Recovery path 1: transient failure

For a temporary RPC, network, or database failure:

1. Correct the external problem.
2. Leave the deployment and checkpoint rows unchanged.
3. Allow the next scheduled poll to retry the same range.
4. Confirm that `last_successful_sync_at` advances and the stored transient error
   is cleared.

Restarting is not normally required because retryable failures do not halt the
worker. If the application itself stopped, restart it normally.

## Recovery path 2: resume the same deployment

Use this path only when the chain deployment is unchanged and the underlying
software or configuration problem has been corrected. Stop the backend before
changing the checkpoint.

At the `psql` prompt opened above, run:

```sql
BEGIN;

SELECT
    deployment_id,
    last_processed_block_number,
    last_processed_block_hash,
    paused,
    pause_reason
FROM indexer_checkpoints
WHERE deployment_id = :'deployment_id'
FOR UPDATE;

UPDATE indexer_checkpoints
SET paused = FALSE,
    pause_reason = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE deployment_id = :'deployment_id'
  AND paused = TRUE;
```

Before closing the transaction, verify that the selected row is the intended
deployment and that the update reported `UPDATE 1`. If both checks pass, run:

```sql
COMMIT;
```

Otherwise run `ROLLBACK` and investigate. Do not commit an unexpected result.

This deliberately preserves the last successful block and the previous error.
Restart the backend after committing because the old application process keeps
an in-memory halted flag. On its next poll, the indexer validates the deployment
identity and stored checkpoint hash before processing. A successful poll clears
the old error; another mismatch pauses the checkpoint again.

## Recovery path 3: reset or redeployed chain

After restarting Anvil or redeploying the DEX contract, use a new stable
`DEX_DEPLOYMENT_ID`. This is the preferred recovery path because it preserves
the old projection for diagnosis and prevents data from two deployments from
sharing an identity.

1. Stop the backend.
2. Update `.env` with a new `DEX_DEPLOYMENT_ID` and the new Ethereum deployment
   metadata where applicable.
3. Keep the old deployment rows unchanged.
4. Restart the backend. It registers the new identity and begins indexing from
   that deployment's block.

Never reuse an identifier merely because the new chain has the same chain ID or
contract address. Deployment identity also includes the deployment block and
its hash.

## Exceptional full reset of an existing deployment ID

Only use this procedure when retaining the old rows under their existing
deployment ID is not acceptable. This permanently deletes the selected
off-chain projection. It does not alter blockchain state.

### 1. Stop and back up

Stop the backend completely so no poller, matcher, or executor can write during
the reset. Then create a full database backup outside the repository:

```bash
mkdir -p ../dex-project-backups
DEX_BACKUP_PATH="../dex-project-backups/dex-before-indexer-recovery.dump"
docker compose exec -T \
  -e PGPASSWORD="$SPRING_DATASOURCE_PASSWORD" \
  postgres pg_dump \
  --username "$SPRING_DATASOURCE_USERNAME" \
  --dbname "$DATABASE_NAME" \
  --format custom > "$DEX_BACKUP_PATH"
```

Confirm that the backup file exists and is nonempty. Then reopen `psql`, inspect
the deployment identity, and run the row-count query from the inspection section.

### 2. Delete only the selected deployment

Run the following statements interactively. Check the locked deployment row
before executing the `DELETE` statements. Use `ROLLBACK` immediately if it is
not the intended target.

```sql
BEGIN;

SELECT
    deployment_id,
    adapter_mode,
    chain_id,
    dex_address,
    deployment_block_number,
    deployment_block_hash
FROM chain_deployments
WHERE deployment_id = :'deployment_id'
FOR UPDATE;
```

Confirm that exactly one row was returned and that every identity field belongs
to the deployment being removed. If anything is unexpected, run `ROLLBACK` and
stop. Only after confirming the row should you run:

```sql

DELETE FROM execution_attempts
WHERE deployment_id = :'deployment_id';

DELETE FROM trades
WHERE deployment_id = :'deployment_id';

DELETE FROM processed_events
WHERE deployment_id = :'deployment_id';

DELETE FROM orders
WHERE deployment_id = :'deployment_id';

DELETE FROM markets
WHERE deployment_id = :'deployment_id';

DELETE FROM indexer_checkpoints
WHERE deployment_id = :'deployment_id';

DELETE FROM chain_deployments
WHERE deployment_id = :'deployment_id';
```

Review every reported delete count. If the counts agree with the earlier
preview, finish with `COMMIT`; otherwise use `ROLLBACK`:

```sql
COMMIT;
```

The order is intentional: execution attempts and trades reference orders and
markets, while every projection ultimately references the deployment. Do not
replace these statements with `TRUNCATE`, remove their `WHERE` clauses, or turn
them into an automatic startup action.

### 3. Rebuild

Update `.env` with the intended current deployment metadata and restart the
backend. The indexer registers the deployment identity again and rebuilds the
projection from the deployment block. Monitor the application logs and the
checkpoint inspection query until the indexed block catches up.
