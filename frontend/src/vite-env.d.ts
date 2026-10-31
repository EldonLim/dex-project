/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_DEPLOYMENT_ID?: string;
  readonly VITE_CHAIN_ID?: string;
  readonly VITE_CHAIN_NAME?: string;
  readonly VITE_RPC_URL?: string;
  readonly VITE_BLOCK_EXPLORER_URL?: string;
  readonly VITE_DEPLOYMENT_BLOCK?: string;
  readonly VITE_TOKEN_REGISTRY_ADDRESS?: string;
  readonly VITE_MARKET_REGISTRY_ADDRESS?: string;
  readonly VITE_ORDER_BOOK_ADDRESS?: string;
  readonly VITE_BACKEND_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
