import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  BrowserProvider,
  Contract,
  type ContractTransactionResponse,
  EventLog,
  getAddress,
  JsonRpcProvider,
} from "ethers";
import { config, expectedChainHex } from "../config";
import { erc20Abi, marketRegistryAbi, orderBookAbi, tokenRegistryAbi } from "../contracts/abis";
import { friendlyError } from "../lib/errors";
import type { BackendHealth, Market, Order, OrderStatus, Side, TokenMeta, Trade, TxNotice } from "../types";

interface EthereumProvider {
  request(args: { method: string; params?: unknown[] | object }): Promise<unknown>;
  on?(event: string, listener: (...args: unknown[]) => void): void;
  removeListener?(event: string, listener: (...args: unknown[]) => void): void;
}

declare global {
  interface Window {
    ethereum?: EthereumProvider;
  }
}

interface RawOrder {
  id: bigint;
  owner: string;
  marketId: bigint;
  side: bigint;
  originalLots: bigint;
  remainingLots: bigint;
  limitPrice: bigint;
  status: bigint;
}

const statusNames: OrderStatus[] = ["OPEN", "FILLED", "CANCELLED"];
const MAX_CHAIN_ORDERS = 500n;

function safeNumber(value: bigint, label: string): number {
  if (value > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error(`${label} is too large for this interface.`);
  return Number(value);
}

export function useDex() {
  const readProvider = useMemo(() => new JsonRpcProvider(config.rpcUrl, config.chainId, { staticNetwork: true }), []);
  const tokenCache = useRef(new Map<string, TokenMeta>());
  const [account, setAccount] = useState<string | null>(null);
  const [chainId, setChainId] = useState<number | null>(null);
  const [connecting, setConnecting] = useState(false);
  const [markets, setMarkets] = useState<Market[]>([]);
  const [selectedMarketId, setSelectedMarketId] = useState<bigint | null>(null);
  const [orders, setOrders] = useState<Order[]>([]);
  const [trades, setTrades] = useState<Trade[]>([]);
  const [balances, setBalances] = useState({ base: 0n, quote: 0n, baseAllowance: 0n, quoteAllowance: 0n });
  const [owners, setOwners] = useState({ tokenRegistry: "", marketRegistry: "" });
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  const [txNotices, setTxNotices] = useState<TxNotice[]>([]);
  const [backend, setBackend] = useState<BackendHealth>({ online: false, stale: false, checkedAt: Date.now() });

  const selectedMarket = useMemo(
    () => markets.find((market) => market.id === selectedMarketId) ?? null,
    [markets, selectedMarketId],
  );
  const wrongNetwork = account !== null && chainId !== config.chainId;
  const isTokenAdmin = !!account && owners.tokenRegistry.toLowerCase() === account.toLowerCase();
  const isMarketAdmin = !!account && owners.marketRegistry.toLowerCase() === account.toLowerCase();

  const readToken = useCallback(async (address: string): Promise<TokenMeta> => {
    const checksum = getAddress(address);
    const cached = tokenCache.current.get(checksum);
    if (cached) return cached;
    const contract = new Contract(checksum, erc20Abi, readProvider);
    const [name, symbol, decimals] = await Promise.all([contract.name(), contract.symbol(), contract.decimals()]);
    const token = { address: checksum, name: String(name), symbol: String(symbol), decimals: Number(decimals) };
    tokenCache.current.set(checksum, token);
    return token;
  }, [readProvider]);

  const loadBackendHealth = useCallback(async () => {
    if (!config.backendUrl) {
      setBackend({ online: false, stale: false, checkedAt: Date.now() });
      return;
    }
    try {
      const response = await fetch(`${config.backendUrl}/health`, { signal: AbortSignal.timeout(3500) });
      if (!response.ok) throw new Error(String(response.status));
      const raw = await response.json() as Record<string, unknown>;
      const indexedBlock = typeof raw.indexedBlock === "number" ? raw.indexedBlock : undefined;
      const responseChainId = typeof raw.chainId === "number" ? raw.chainId : undefined;
      const stale = raw.stale === true || (responseChainId !== undefined && responseChainId !== config.chainId);
      setBackend({ online: true, indexedBlock, chainId: responseChainId, stale, checkedAt: Date.now() });
    } catch {
      setBackend({ online: false, stale: false, checkedAt: Date.now() });
    }
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const marketRegistry = new Contract(config.marketRegistryAddress, marketRegistryAbi, readProvider);
      const orderBook = new Contract(config.orderBookAddress, orderBookAbi, readProvider);
      const tokenRegistry = new Contract(config.tokenRegistryAddress, tokenRegistryAbi, readProvider);
      const [nextMarketRaw, nextOrderRaw, tokenOwner, marketOwner] = await Promise.all([
        marketRegistry.nextMarketId(),
        orderBook.nextOrderId(),
        tokenRegistry.owner(),
        marketRegistry.owner(),
      ]);
      const nextMarketIdValue = nextMarketRaw as bigint;
      const nextOrderIdValue = nextOrderRaw as bigint;
      setOwners({ tokenRegistry: getAddress(String(tokenOwner)), marketRegistry: getAddress(String(marketOwner)) });

      const marketIds = Array.from({ length: safeNumber(nextMarketIdValue - 1n, "Market count") }, (_, index) => BigInt(index + 1));
      const loadedMarkets = await Promise.all(marketIds.map(async (id): Promise<Market> => {
        const raw = await marketRegistry.getMarket(id) as [string, string, bigint, boolean];
        const [baseToken, quoteToken] = await Promise.all([readToken(raw[0]), readToken(raw[1])]);
        return { id, baseToken, quoteToken, baseLotAtoms: raw[2] };
      }));
      setMarkets(loadedMarkets);
      const activeMarketId = selectedMarketId && loadedMarkets.some((market) => market.id === selectedMarketId)
        ? selectedMarketId
        : loadedMarkets[0]?.id ?? null;
      setSelectedMarketId(activeMarketId);

      const firstOrder = nextOrderIdValue > MAX_CHAIN_ORDERS ? nextOrderIdValue - MAX_CHAIN_ORDERS : 1n;
      const orderIds = Array.from(
        { length: safeNumber(nextOrderIdValue - firstOrder, "Order count") },
        (_, index) => firstOrder + BigInt(index),
      );
      const loadedOrders = await Promise.all(orderIds.map(async (id): Promise<Order> => {
        const raw = await orderBook.getOrder(id) as RawOrder;
        return {
          id: raw.id,
          owner: getAddress(raw.owner),
          marketId: raw.marketId,
          side: Number(raw.side) === 0 ? "BUY" : "SELL",
          originalLots: raw.originalLots,
          remainingLots: raw.remainingLots,
          limitPrice: raw.limitPrice,
          status: statusNames[Number(raw.status)] ?? "OPEN",
        };
      }));
      setOrders(loadedOrders);

      if (activeMarketId) {
        const eventLogs = await orderBook.queryFilter(orderBook.filters.TradeExecuted(null, null, activeMarketId), config.deploymentBlock);
        const loadedTrades = eventLogs.filter((log): log is EventLog => log instanceof EventLog).map((log): Trade => ({
          transactionHash: log.transactionHash,
          blockNumber: log.blockNumber,
          buyOrderId: log.args.buyOrderId as bigint,
          sellOrderId: log.args.sellOrderId as bigint,
          marketId: log.args.marketId as bigint,
          buyer: getAddress(log.args.buyer as string),
          seller: getAddress(log.args.seller as string),
          fillLots: log.args.fillLots as bigint,
          executionPrice: log.args.executionPrice as bigint,
          baseAtoms: log.args.baseAtoms as bigint,
          quoteAtoms: log.args.quoteAtoms as bigint,
        })).sort((a, b) => b.blockNumber - a.blockNumber);
        setTrades(loadedTrades);
      } else {
        setTrades([]);
      }

      if (account && activeMarketId) {
        const market = loadedMarkets.find((candidate) => candidate.id === activeMarketId);
        if (market) {
          const base = new Contract(market.baseToken.address, erc20Abi, readProvider);
          const quote = new Contract(market.quoteToken.address, erc20Abi, readProvider);
          const [baseBalance, quoteBalance, baseAllowance, quoteAllowance] = await Promise.all([
            base.balanceOf(account),
            quote.balanceOf(account),
            base.allowance(account, config.orderBookAddress),
            quote.allowance(account, config.orderBookAddress),
          ]);
          setBalances({
            base: baseBalance as bigint,
            quote: quoteBalance as bigint,
            baseAllowance: baseAllowance as bigint,
            quoteAllowance: quoteAllowance as bigint,
          });
        }
      } else {
        setBalances({ base: 0n, quote: 0n, baseAllowance: 0n, quoteAllowance: 0n });
      }
      setLastUpdated(Date.now());
    } catch (error) {
      setLoadError(`Unable to read the local deployment. ${friendlyError(error).message}`);
    } finally {
      setLoading(false);
    }
    void loadBackendHealth();
  }, [account, loadBackendHealth, readProvider, readToken, selectedMarketId]);

  useEffect(() => { void refresh(); }, [refresh]);

  useEffect(() => {
    const interval = window.setInterval(() => void refresh(), 12_000);
    return () => window.clearInterval(interval);
  }, [refresh]);

  useEffect(() => {
    const ethereum = window.ethereum;
    if (!ethereum?.on) return;
    const handleAccounts = (...args: unknown[]) => {
      const accounts = args[0] as string[];
      setAccount(accounts?.[0] ? getAddress(accounts[0]) : null);
    };
    const handleChain = (...args: unknown[]) => setChainId(Number.parseInt(String(args[0]), 16));
    ethereum.on("accountsChanged", handleAccounts);
    ethereum.on("chainChanged", handleChain);
    return () => {
      ethereum.removeListener?.("accountsChanged", handleAccounts);
      ethereum.removeListener?.("chainChanged", handleChain);
    };
  }, []);

  const connect = useCallback(async () => {
    if (!window.ethereum) {
      setLoadError("No injected wallet found. Install MetaMask or another EIP-1193 wallet.");
      return;
    }
    setConnecting(true);
    try {
      const provider = new BrowserProvider(window.ethereum);
      const accounts = await provider.send("eth_requestAccounts", []) as string[];
      const network = await provider.getNetwork();
      setAccount(accounts[0] ? getAddress(accounts[0]) : null);
      setChainId(Number(network.chainId));
      setLoadError("");
    } catch (error) {
      setLoadError(friendlyError(error).message);
    } finally {
      setConnecting(false);
    }
  }, []);

  const disconnect = useCallback(() => {
    setAccount(null);
    setChainId(null);
    setBalances({ base: 0n, quote: 0n, baseAllowance: 0n, quoteAllowance: 0n });
  }, []);

  const switchNetwork = useCallback(async () => {
    if (!window.ethereum) return;
    try {
      await window.ethereum.request({ method: "wallet_switchEthereumChain", params: [{ chainId: expectedChainHex }] });
    } catch (error) {
      const candidate = error as { code?: number };
      if (candidate.code !== 4902) {
        setLoadError(friendlyError(error).message);
        return;
      }
      await window.ethereum.request({
        method: "wallet_addEthereumChain",
        params: [{ chainId: expectedChainHex, chainName: config.chainName, rpcUrls: [config.rpcUrl], nativeCurrency: { name: "Test Ether", symbol: "ETH", decimals: 18 } }],
      });
    }
  }, []);

  const signer = useCallback(async () => {
    if (!window.ethereum || !account) throw new Error("Connect your wallet first.");
    if (wrongNetwork) throw new Error(`Switch to ${config.chainName} before continuing.`);
    return new BrowserProvider(window.ethereum).getSigner();
  }, [account, wrongNetwork]);

  const runTransaction = useCallback(async (
    label: string,
    submit: () => Promise<ContractTransactionResponse>,
  ): Promise<boolean> => {
    const id = `${Date.now()}-${Math.random()}`;
    const update = (patch: Partial<TxNotice>) => setTxNotices((items) => items.map((item) => item.id === id ? { ...item, ...patch } : item));
    const initialNotice: TxNotice = { id, label, phase: "awaiting-signature", message: "Confirm this request in your wallet.", createdAt: Date.now() };
    setTxNotices((items) => [initialNotice, ...items].slice(0, 8));
    try {
      const transaction = await submit();
      update({ phase: "submitted", hash: transaction.hash, message: "Submitted. Waiting for a confirmed receipt…" });
      const receipt = await transaction.wait();
      if (!receipt || receipt.status !== 1) throw new Error("The transaction was mined but did not succeed.");
      update({ phase: "confirmed", message: `Confirmed in block ${receipt.blockNumber}.` });
      await refresh();
      return true;
    } catch (error) {
      const parsed = friendlyError(error);
      update({ phase: parsed.rejected ? "rejected" : "failed", message: parsed.message });
      return false;
    }
  }, [refresh]);

  const approve = useCallback(async (side: Side, requiredAtoms: bigint) => {
    if (!selectedMarket) return false;
    const token = side === "SELL" ? selectedMarket.baseToken : selectedMarket.quoteToken;
    return runTransaction(`Approve ${token.symbol}`, async () => {
      const contract = new Contract(token.address, erc20Abi, await signer());
      return contract.approve(config.orderBookAddress, requiredAtoms) as Promise<ContractTransactionResponse>;
    });
  }, [runTransaction, selectedMarket, signer]);

  const placeOrder = useCallback(async (side: Side, lots: bigint, limitPrice: bigint) => {
    if (!selectedMarket) return false;
    return runTransaction(`Place ${side.toLowerCase()} order`, async () => {
      const contract = new Contract(config.orderBookAddress, orderBookAbi, await signer());
      return contract.placeOrder(selectedMarket.id, side === "BUY" ? 0 : 1, lots, limitPrice) as Promise<ContractTransactionResponse>;
    });
  }, [runTransaction, selectedMarket, signer]);

  const cancelOrder = useCallback(async (orderId: bigint) => runTransaction(`Cancel order #${orderId}`, async () => {
    const contract = new Contract(config.orderBookAddress, orderBookAbi, await signer());
    return contract.cancelOrder(orderId) as Promise<ContractTransactionResponse>;
  }), [runTransaction, signer]);

  const registerToken = useCallback(async (tokenAddress: string) => runTransaction("Register token", async () => {
    const contract = new Contract(config.tokenRegistryAddress, tokenRegistryAbi, await signer());
    return contract.registerToken(getAddress(tokenAddress)) as Promise<ContractTransactionResponse>;
  }), [runTransaction, signer]);

  const registerMarket = useCallback(async (base: string, quote: string, baseLotAtoms: bigint) => runTransaction("Register market", async () => {
    const contract = new Contract(config.marketRegistryAddress, marketRegistryAbi, await signer());
    return contract.registerMarket(getAddress(base), getAddress(quote), baseLotAtoms) as Promise<ContractTransactionResponse>;
  }), [runTransaction, signer]);

  const clearTxNotices = useCallback(() => setTxNotices([]), []);

  return {
    account, chainId, connecting, wrongNetwork, markets, selectedMarket, selectedMarketId, setSelectedMarketId,
    orders, trades, balances, loading, loadError, lastUpdated, txNotices, clearTxNotices, backend, isTokenAdmin, isMarketAdmin,
    walletAvailable: !!window.ethereum, connect, disconnect, switchNetwork, refresh, approve, placeOrder, cancelOrder,
    registerToken, registerMarket,
  };
}
