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
| `SIGNED` | Executor nonce and transaction hash are known | Yes | No |
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
- `SIGNED`, `SUBMITTED`, and `UNCERTAIN` require a non-negative executor nonce
  and a transaction hash.
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
invalid transition rolls back without losing an already recorded nonce or
transaction hash.

## Crash boundaries and deferred work

Checkpoint 6A defines and persists the lifecycle but does not sign, broadcast,
or inspect transactions. It also does not persist the signed transaction bytes.
The next executor checkpoint must close the sign-to-broadcast crash window by
deciding how the exact signed payload is durably recoverable before broadcast.

No process may generate a replacement settlement or release an uncertain
attempt merely because a timeout elapsed. Later receipt reconciliation must use
the known transaction hash and executor nonce to determine a safe outcome.
