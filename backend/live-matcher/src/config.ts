import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { getAddress } from "ethers";

interface DeploymentFile {
  chainId: number;
  deploymentBlock?: number;
  contracts: {
    TokenRegistry: string;
    MarketRegistry: string;
    OrderBook: string;
  };
}

const here = dirname(fileURLToPath(import.meta.url));
const defaultDeploymentPath = resolve(here, "../../../smart-contracts/deployments/localhost.json");

function loadDeployment(): DeploymentFile {
  const path = process.env.DEPLOYMENT_FILE || defaultDeploymentPath;
  return JSON.parse(readFileSync(path, "utf8")) as DeploymentFile;
}

function positiveInteger(value: string | undefined, fallback: number, name: string): number {
  const parsed = Number(value ?? fallback);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) throw new Error(`${name} must be a positive integer`);
  return parsed;
}

export function loadConfig() {
  const deployment = loadDeployment();
  const privateKey = process.env.EXECUTOR_PRIVATE_KEY
    || "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

  if (!/^0x[0-9a-fA-F]{64}$/.test(privateKey)) {
    throw new Error("EXECUTOR_PRIVATE_KEY must be a 32-byte hex private key");
  }

  return {
    rpcUrl: process.env.RPC_URL || "http://127.0.0.1:8545",
    chainId: positiveInteger(process.env.CHAIN_ID, deployment.chainId, "CHAIN_ID"),
    port: positiveInteger(process.env.PORT, 8081, "PORT"),
    pollIntervalMs: positiveInteger(process.env.POLL_INTERVAL_MS, 2000, "POLL_INTERVAL_MS"),
    deploymentBlock: Number(process.env.DEPLOYMENT_BLOCK ?? deployment.deploymentBlock ?? 0),
    privateKey,
    tokenRegistryAddress: getAddress(process.env.TOKEN_REGISTRY_ADDRESS || deployment.contracts.TokenRegistry),
    marketRegistryAddress: getAddress(process.env.MARKET_REGISTRY_ADDRESS || deployment.contracts.MarketRegistry),
    orderBookAddress: getAddress(process.env.ORDER_BOOK_ADDRESS || deployment.contracts.OrderBook),
  };
}

export type MatcherConfig = ReturnType<typeof loadConfig>;
