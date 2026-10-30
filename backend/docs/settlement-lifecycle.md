# Settlement execution-attempt lifecycle

An execution attempt is local operational state for one proposed settlement.
It is separate from the confirmed order and trade projections. The blockchain
remains authoritative, and only indexed contract events may change those
projections.

## State machine

```text
RESERVED ----> SIGNED ----> SUBMITTED ----> CONFIRMED
    |             |              |--------> REVERTED
    |             |              |
    |             +--------------+--------> UNCERTAIN
    |                                           |
    +----> ABANDONED                            +--> SUBMITTED
                                                +--> CONFIRMED
                                                +--> REVERTED
```

| State | Meaning | Reserves both orders | Terminal |
|---|---|---:|---:|
| `RESERVED` | A proposal was durably selected, but no transaction identity exists | Yes | No |
| `SIGNED` | Executor nonce, transaction hash, and exact signed payload are durably stored | Yes | No |
| `SUBMITTED` | Broadcast was accepted or the transaction is visible to the node | Yes | No |
| `UNCERTAIN` | Transaction identity exists, but broadcast or observation outcome is unclear | Yes | No |
| `CONFIRMED` | The indexed trade event has already updated projections | No | Yes |
| `REVERTED` | A confirmed receipt reports contract execution failure | No | Yes |
| `ABANDONED` | An unsigned reservation was deliberately released | No | Yes |

`CONFIRMED`, `REVERTED`, and `ABANDONED` cannot transition again.

## Safety rules

- `RESERVED` is the only state that may be abandoned automatically by a
  future executor workflow. Once a transaction is signed, it may later appear
  on-chain and must remain reserved until reconciled.
- `SIGNED`, `SUBMITTED`, and `UNCERTAIN` require a non-negative executor nonce,
  transaction hash, and normalized lowercase signed transaction payload.
- A retryable failure before signing leaves the attempt `RESERVED`.
- An ambiguous result after signing becomes `UNCERTAIN`; it must not release
  either order.
- A successful receipt does not directly make an attempt `CONFIRMED`. The
  attempt remains unresolved until the indexer applies the corresponding trade
  event and marks it confirmed in the event-processing path.
- Reverted or abandoned attempts release their reservations but never mutate
  confirmed order quantities or statuses.
- Lifecycle timestamps cannot move backward. Diagnostic codes and messages are
  bounded, internal-only metadata and must never contain credentials, private
  keys, signed transaction bytes, or other secrets.

## Transaction boundaries

Each lifecycle mutation runs in its own short, deployment-scoped database
transaction. The service locates attempts using both the configured deployment
ID and attempt UUID. Blockchain calls must happen before or after these methods;
no RPC request belongs inside a lifecycle transaction.

The application validates transitions before changing the managed entity. An
invalid transition rolls back without losing an already recorded nonce,
transaction hash, or signed payload.

## Signing schedule and crash recovery

When matching is enabled, the settlement executor polls at the configured
`DEX_EXECUTOR_POLL_INTERVAL`, processes at most one unresolved attempt, and
prevents overlapping invocations. Preparing and signing a `RESERVED` attempt
happens outside any database transaction. A short lifecycle transaction then
stores the exact signed bytes together with the nonce and transaction hash.

After a restart, a `SIGNED` attempt is reconstructed from those durable fields
and is not prepared or signed again. `SUBMITTED` and `UNCERTAIN` attempts are
also left untouched for later reconciliation. Because signed transaction bytes
authorize a blockchain action, PostgreSQL access and database backups are
operationally sensitive. Signed bytes must never appear in logs, status
responses, exception messages, or routine diagnostics.

Checkpoint 6B deliberately stops at `SIGNED` and never broadcasts. Checkpoint
6C will broadcast the already-persisted payload and handle ambiguous submission
outcomes without generating a replacement transaction.

No process may generate a replacement settlement or release an uncertain
attempt merely because a timeout elapsed. Later receipt reconciliation must use
the known transaction hash and executor nonce to determine a safe outcome.
