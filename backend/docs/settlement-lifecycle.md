# Settlement execution-attempt lifecycle

An execution attempt is local operational state for one proposed settlement.
It is separate from the confirmed order and trade projections. The blockchain
remains authoritative, and only indexed contract events may change those
projections.

Each attempt durably preserves the matcher's execution price in quote-token
atoms per lot. This exact value is carried into `executeTrade`, survives
restart recovery with the signed payload, and must match the indexed trade
event before the attempt can be confirmed.

## State machine

```text
RESERVED ----> SIGNED ----> SUBMITTED ----> CONFIRMED
    |             |              |--------> REVERTED
    |             |              |
    |             +----------------------> CONFIRMED
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
- `SIGNED` may transition directly to `CONFIRMED` only when a matching indexed
  trade proves that broadcast succeeded before the `SUBMITTED` database update
  could be persisted.
- Reverted or abandoned attempts release their reservations but never mutate
  confirmed order quantities or statuses.
- Lifecycle timestamps cannot move backward. Diagnostic codes and messages are
  bounded, internal-only metadata and must never contain credentials, private
  keys, signed transaction bytes, or other secrets.

## Transaction boundaries

Ordinary executor lifecycle mutations run in their own short,
deployment-scoped database transactions. The service locates attempts using
both the configured deployment ID and attempt UUID. Blockchain calls must
happen before or after these methods; no RPC request belongs inside a lifecycle
transaction.

Trade confirmation is intentionally different: it joins the indexing batch
transaction. Order updates, trade insertion, attempt confirmation, the
processed-event marker, and checkpoint advancement therefore commit or roll
back together. Lifecycle and confirmation lookups lock the attempt row so the
executor and indexer cannot overwrite each other's state.

The application validates transitions before changing the managed entity. An
invalid transition rolls back without losing an already recorded nonce,
transaction hash, or signed payload.

## Signing, broadcasting, and crash recovery

When matching is enabled, the settlement executor polls at the configured
`DEX_EXECUTOR_POLL_INTERVAL`, processes at most one unresolved attempt, and
prevents overlapping invocations. Preparing and signing a `RESERVED` attempt
happens outside any database transaction. A short lifecycle transaction then
stores the exact signed bytes together with the nonce and transaction hash.

The Ethereum adapter prepares the exact checked-in four-argument
`executeTrade` call, reads the executor's pending nonce and current node gas
price, estimates that call, adds a bounded gas margin, and signs one legacy
transaction with EIP-155 chain replay protection. No database transaction is
held during those RPC or signing operations.

After a restart, a `SIGNED` attempt is reconstructed from those durable fields
and is not prepared or signed again. The executor submits the exact persisted
payload outside a database transaction, verifies that the gateway returns the
same transaction hash, and then records `SUBMITTED` in a short lifecycle
transaction.

If broadcasting succeeds but the database transition fails, the attempt remains
`SIGNED`. The next cycle safely rebroadcasts the identical transaction. A
broadcast exception instead records `UNCERTAIN` with sanitized internal
diagnostics. The executor looks up that known hash before further action: a
visible pending, successful, or reverted transaction becomes `SUBMITTED`, while
`NOT_FOUND` causes an idempotent rebroadcast of the same payload. It never signs
a replacement or selects a new nonce.

Because signed transaction bytes authorize a blockchain action, PostgreSQL
access and database backups are operationally sensitive. Signed bytes must
never appear in logs, status responses, exception messages, or routine
diagnostics. Raw gateway failure details must not be copied into logs, status
responses, or stored attempt diagnostics.

## Receipt reconciliation and terminal outcomes

A `SUBMITTED` attempt is looked up by its persisted transaction hash on each
executor cycle. `PENDING` continues waiting. `NOT_FOUND` records sanitized
uncertainty and returns to the identical-payload recovery path. Mined results
are accepted only when the reported block hash still matches the canonical
block; a missing or changed block is retried without releasing the reservation.

`SUCCEEDED` remains unresolved until its matching `TradeExecutedEvent` is
indexed. The indexer validates deployment, transaction hash, market, both order
IDs, fill quantity, execution price, participants, and token amounts before
marking the attempt `CONFIRMED`. Post-trade remainders are derived by applying
the event's fill quantity to the prior confirmed projection because the current
Solidity event does not emit them. An event without a local attempt is still
projected normally. A local mismatch rolls back the batch and pauses indexing.
If the checkpoint has already passed the successful receipt block without
confirming the attempt, settlement execution halts rather than silently
releasing or permanently ignoring the reservation.

`REVERTED` becomes terminal only after the indexer checkpoint has processed
through the receipt block. This reuses the configured confirmation-depth policy.
Before the durable reservation is released, both referenced orders are added
to the existing in-memory matcher cooldown to prevent an immediate retry loop.
The bounded contract revert reason remains internal, and confirmed order and
trade projections are not changed.

No process may generate a replacement settlement or release an uncertain
attempt merely because a timeout elapsed. Later receipt reconciliation must use
the known transaction hash and executor nonce to determine a safe outcome.
