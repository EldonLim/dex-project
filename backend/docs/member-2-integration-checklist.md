# Member 2 Solidity Integration Handoff

This record coordinates the Solidity handoff to the Java backend. The checked
items were verified together against repository integration commit `c89b269`.
If a contract, ABI, or deployment script changes, treat that as a new handoff
and rerun every applicable compatibility and acceptance check.

The backend is aligned with the checked-in Solidity artifacts. See the
[contract assumptions](contract-assumptions.md) for the confirmed interface and
remaining policy decisions.

## Required artifacts

- [x] OrderBook and MarketRegistry ABIs
- [x] Standard ERC-20-compatible MockToken ABI
- [x] OrderBook contract address in generated metadata
- [x] Token A and Token B addresses in generated metadata
- [x] Chain ID in generated metadata
- [x] Deployment block number in generated metadata
- [x] Deployment block hash in generated metadata
- [x] Deployment transaction hash in generated metadata
- [x] Stable deployment identifier derived from deployment identity
- [x] Contract source and matching ABIs checked into the same repository

ABIs live under `smart-contracts/abi/`. Safe and private local deployment
metadata are generated under `smart-contracts/deployments/`; generated files
are ignored because they become invalid after a chain reset.

The generated `localhost.json` is the deployment-specific handoff: it records
the schema version, deployment ID, chain ID, deployment block number and hash,
contract addresses, relevant transaction hashes, and acceptance-fixture
identifiers. The companion `localhost.env` contains the ephemeral executor key
and must remain ignored with owner-only permissions.

## Interface confirmation

- [x] Market getter name, arguments, and return schema
- [x] Order getter name, arguments, and return schema
- [x] `executeTrade` arguments, no return value, and unrestricted caller policy
- [x] Order-placement event schema and indexed fields
- [x] Order-cancellation event schema and indexed fields
- [x] Trade event schema and indexed fields
- [x] Market-registration event schema and indexed fields
- [x] Side and status numeric encoding
- [x] Lot-size, quantity, and price unit convention
- [x] Execution-price compatibility and backend older-order rule
- [x] Cancellation authorization and unchanged remainder behavior
- [x] Solidity-permitted and Java-skipped self-trade behavior
- [x] Unrestricted Solidity executor policy
- [x] Current documented revert-string behavior

## Local environment handoff

Provide exact, repeatable commands for:

- [x] Starting the Hardhat node with chain ID `31337`
- [x] Deploying the token and DEX contracts
- [x] Redeploying after a local-chain reset
- [x] Funding a random local backend executor and test users
- [x] Distributing Token A and Token B to the test users
- [x] Approving the OrderBook as token spender
- [x] Registering the initial market after OrderBook deployment

Commit only safe addresses and deployment identifiers. Do not commit real or
sensitive private keys. If known local-development accounts are used, document
how the tooling derives or supplies them without adding secrets to repository
files.

## Joint acceptance scenario

Run this scenario together after the final artifacts are available:

- [x] 1. Alice receives Token A.
- [x] 2. Bob receives Token B.
- [x] 3. Alice and Bob approve the OrderBook.
- [x] 4. Alice places a 100-lot sell order.
- [x] 5. Bob places a compatible 40-lot buy order.
- [x] 6. The backend indexes both orders.
- [x] 7. The backend submits `executeTrade`.
- [x] 8. The contract settles 40 lots.
- [x] 9. The backend indexes the confirmed trade.
- [x] 10. Alice has 60 lots remaining.
- [x] 11. Bob's order is filled.
- [x] 12. Token balances reflect both sides of the exchange.
- [x] 13. Restarting and reprocessing does not duplicate the trade.

The automated acceptance command is documented in
[Local Ethereum acceptance](ethereum-local-acceptance.md). It passed on
2026-10-30 against integration checkpoint `c89b269`; each run records its unique
deployment identifier in ignored generated metadata.

## Remaining team decisions before a shared or public deployment

These do not block the verified local integration, but they must not be treated
as finalized production policy:

- [ ] Decide whether `executeTrade` remains callable by any address or gains an
  executor authorization mechanism.
- [ ] Agree on the contract-level self-trade policy. Solidity currently permits
  self-trades while the Java matcher skips them.
- [ ] Decide whether revert strings remain the stable error contract or are
  replaced by documented custom errors.
- [ ] Select the shared target network and provide its RPC access outside Git.
- [ ] Tag the exact contract source/ABI revision used for that deployment.
- [ ] Publish safe shared deployment metadata containing both DEX addresses,
  token addresses, chain ID, deployment block number/hash, and deployment
  transaction hashes.
- [ ] Rerun `npm run verify` and `npm run e2e:backend` after any ABI or behavior
  change, then update this handoff record.

Private keys, authenticated RPC URLs, and other credentials are never handoff
artifacts and must not be committed.
