# Member 2 Solidity Integration Checklist

This checklist coordinates the finalized Solidity handoff to the Java backend.
It should be completed against one contract source commit or tag so the ABI,
deployment metadata, and behavior describe the same build.

The backend's current interface is provisional. See the
[contract assumptions](contract-assumptions.md) for the exact assumptions and
open questions.

## Required artifacts

- [ ] Final DEX ABI
- [ ] ERC-20 ABI if it differs from standard ERC-20
- [ ] DEX contract address
- [ ] Token A and Token B addresses
- [ ] Chain ID
- [ ] Deployment block number
- [ ] Deployment block hash
- [ ] Deployment transaction hash
- [ ] Stable deployment identifier
- [ ] Contract source commit or tag matching the ABI

Before adding artifacts, agree on a shared location. The recommended locations
from the repository plan are the top-level `abi/` and `deployments/`
directories. This backend checkpoint does not create or populate those
teammate-owned files.

## Interface confirmation

- [ ] Market getter name, arguments, and return schema
- [ ] Order getter name, arguments, and return schema
- [ ] Match execution name, arguments, return value, and authorization
- [ ] Order-placement event schema and indexed fields
- [ ] Order-cancellation event schema and indexed fields
- [ ] Trade event schema and indexed fields
- [ ] Market-registration event schema and indexed fields
- [ ] Side and status numeric encoding
- [ ] Lot-size, quantity, and price unit convention
- [ ] Execution-price rule
- [ ] Cancellation authorization and remaining-quantity behavior
- [ ] Self-trade behavior
- [ ] Executor authorization policy
- [ ] Revert conditions and custom-error schemas

## Local environment handoff

Provide exact, repeatable commands for:

- [ ] Starting Anvil with the expected chain ID
- [ ] Deploying the token and DEX contracts
- [ ] Redeploying after a local-chain reset
- [ ] Funding the backend executor and user test accounts with test ETH
- [ ] Distributing Token A and Token B to the test users
- [ ] Approving the DEX contract as token spender
- [ ] Registering the initial market

Commit only safe addresses and deployment identifiers. Do not commit real or
sensitive private keys. If known local-development accounts are used, document
how the tooling derives or supplies them without adding secrets to repository
files.

## Joint acceptance scenario

Run this scenario together after the final artifacts are available:

- [ ] 1. Alice receives Token A.
- [ ] 2. Bob receives Token B.
- [ ] 3. Alice and Bob approve the DEX contract.
- [ ] 4. Alice places a 100-lot sell order.
- [ ] 5. Bob places a compatible 40-lot buy order.
- [ ] 6. The backend indexes both orders.
- [ ] 7. The backend submits `executeMatch`.
- [ ] 8. The contract settles 40 lots.
- [ ] 9. The backend indexes the confirmed trade.
- [ ] 10. Alice has 60 lots remaining.
- [ ] 11. Bob's order is filled.
- [ ] 12. Token balances reflect both sides of the exchange.
- [ ] 13. Reprocessing the same logs does not duplicate the trade.

Record the contract commit, deployment identifier, and backend commit used for
the successful run so the result can be reproduced.
