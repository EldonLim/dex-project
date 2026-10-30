import { getAddress } from "ethers";

function envAddress(value: string | undefined, fallback: string): string {
  return getAddress(value || fallback);
}

export const config = {
  chainId: Number(import.meta.env.VITE_CHAIN_ID || 31337),
  chainName: import.meta.env.VITE_CHAIN_NAME || "Hardhat Local",
  rpcUrl: import.meta.env.VITE_RPC_URL || "http://127.0.0.1:8545",
  explorerUrl: (import.meta.env.VITE_BLOCK_EXPLORER_URL || "").replace(/\/$/, ""),
  deploymentBlock: Number(import.meta.env.VITE_DEPLOYMENT_BLOCK || 0),
  tokenRegistryAddress: envAddress(
    import.meta.env.VITE_TOKEN_REGISTRY_ADDRESS,
    "0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0",
  ),
  marketRegistryAddress: envAddress(
    import.meta.env.VITE_MARKET_REGISTRY_ADDRESS,
    "0x5FC8d32690cc91D4c39d9d3abcBD16989F875707",
  ),
  orderBookAddress: envAddress(
    import.meta.env.VITE_ORDER_BOOK_ADDRESS,
    "0xa513E6E4b8f2a923D98304ec87F64353C4D5C853",
  ),
  backendUrl: (import.meta.env.VITE_BACKEND_URL || "").replace(/\/$/, ""),
} as const;

export const expectedChainHex = `0x${config.chainId.toString(16)}`;
