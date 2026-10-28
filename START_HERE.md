# Team run package

Start with [README.md](README.md), then follow the complete commands in [frontend/README.md](frontend/README.md).

## Included

- `smart-contracts/`: Solidity contracts, deployment script, exported ABIs, tests and pinned npm dependencies.
- `frontend/`: React/TypeScript application, tests, lockfile and safe environment example.
- `backend/`: Spring Boot scaffold, database migrations, Maven wrapper and tests.
- `.env.example` and `compose.yaml`: safe backend/PostgreSQL configuration examples.
- `.github/`: existing backend CI workflow.

## First run

Open this folder in Command Prompt, then use three terminals:

```cmd
cd smart-contracts
npm ci
npx hardhat node
```

```cmd
cd smart-contracts
npx hardhat run scripts/deploy.ts --network localhost
```

```cmd
cd frontend
npm ci
if not exist .env copy .env.example .env
npm run dev
```

Before starting Vite, update `frontend/.env` if the deployed TokenRegistry, MarketRegistry or OrderBook addresses differ from its example values.

## Package notes

- Local `.env` files, dependency folders, build outputs and caches are intentionally excluded. Each teammate installs dependencies locally.
- The supplied backend is a scaffold and does not yet implement automatic matching. The frontend will show `Matcher offline` until that service is implemented and integrated.
- Never commit or share real wallet keys. Hardhat's printed development keys are for the local chain only.
