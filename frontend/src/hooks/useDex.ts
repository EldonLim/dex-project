import { useCallback, useEffect, useMemo, useState } from "react";
import {
  BrowserProvider,
  Contract,
  type ContractTransactionResponse,
  getAddress,
  JsonRpcProvider,
} from "ethers";
import { BackendApiClient, deploymentIdentityError } from "../api/backend";
import { config, expectedChainHex } from "../config";
import { erc20Abi, marketRegistryAbi, orderBookAbi, tokenRegistryAbi } from "../contracts/abis";
import { friendlyError } from "../lib/errors";
import type { BackendHealth, Market, Order, OrderBookOrder, Side, Trade, TxNotice } from "../types";

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

const emptyBalances = { base: 0n, quote: 0n, baseAllowance: 0n, quoteAllowance: 0n };
const sleep = (milliseconds: number) => new Promise((resolve) => window.setTimeout(resolve, milliseconds));

export function useDex() {
  const api = useMemo(() => new BackendApiClient(config.backendUrl), []);
  const readProvider = useMemo(
    () => new JsonRpcProvider(config.rpcUrl, config.chainId, { staticNetwork: true }),
    [],
  );
  const [account, setAccount] = useState<string | null>(null);
  const [chainId, setChainId] = useState<number | null>(null);
  const [connecting, setConnecting] = useState(false);
  const [markets, setMarkets] = useState<Market[]>([]);
  const [selectedMarketId, setSelectedMarketId] = useState<bigint | null>(null);
  const [orderBook, setOrderBook] = useState<OrderBookOrder[]>([]);
  const [orders, setOrders] = useState<Order[]>([]);
  const [ordersCursor, setOrdersCursor] = useState<string | null>(null);
  const [trades, setTrades] = useState<Trade[]>([]);
  const [tradesCursor, setTradesCursor] = useState<string | null>(null);
  const [balances, setBalances] = useState(emptyBalances);
  const [owners, setOwners] = useState({ tokenRegistry: "", marketRegistry: "" });
  const [loading, setLoading] = useState(true);
  const [loadingMoreOrders, setLoadingMoreOrders] = useState(false);
  const [loadingMoreTrades, setLoadingMoreTrades] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  const [txNotices, setTxNotices] = useState<TxNotice[]>([]);
  const [backend, setBackend] = useState<BackendHealth>({ online: false, checkedAt: Date.now() });

  const selectedMarket = useMemo(
    () => markets.find((market) => market.id === selectedMarketId) ?? null,
    [markets, selectedMarketId],
  );
  const wrongNetwork = account !== null && chainId !== config.chainId;
  const isTokenAdmin = !!account && owners.tokenRegistry.toLowerCase() === account.toLowerCase();
  const isMarketAdmin = !!account && owners.marketRegistry.toLowerCase() === account.toLowerCase();

  const applyBackendStatus = useCallback((status: BackendHealth): BackendHealth => {
    const identityError = deploymentIdentityError(status, {
      deploymentId: config.deploymentId,
      chainId: config.chainId,
      dexAddress: config.orderBookAddress,
      marketRegistryAddress: config.marketRegistryAddress,
    });
    const checked = { ...status, identityError };
    setBackend(checked);
    return checked;
  }, []);

  const loadBackendStatus = useCallback(async (): Promise<BackendHealth> => {
    try {
      return applyBackendStatus(await api.status());
    } catch {
      const offline: BackendHealth = { online: false, checkedAt: Date.now() };
      setBackend(offline);
      return offline;
    }
  }, [api, applyBackendStatus]);

  const refresh = useCallback(async () => {
    setLoading(true);
    setLoadError("");
    try {
      const tokenRegistry = new Contract(config.tokenRegistryAddress, tokenRegistryAbi, readProvider);
      const marketRegistry = new Contract(config.marketRegistryAddress, marketRegistryAbi, readProvider);
      const [status, loadedMarkets, tokenOwner, marketOwner] = await Promise.all([
        api.status(),
        api.markets(),
        tokenRegistry.owner(),
        marketRegistry.owner(),
      ]);
      applyBackendStatus(status);
      setOwners({ tokenRegistry: getAddress(String(tokenOwner)), marketRegistry: getAddress(String(marketOwner)) });
      setMarkets(loadedMarkets);

      const activeMarketId = selectedMarketId && loadedMarkets.some((market) => market.id === selectedMarketId)
        ? selectedMarketId
        : loadedMarkets[0]?.id ?? null;
      const market = loadedMarkets.find((candidate) => candidate.id === activeMarketId);
      setSelectedMarketId(activeMarketId);

      if (!market) {
        setOrderBook([]);
        setOrders([]);
        setOrdersCursor(null);
        setTrades([]);
        setTradesCursor(null);
        setBalances(emptyBalances);
      } else {
        const [book, tradePage, orderPage] = await Promise.all([
          api.orderBook(market.id),
          api.trades(market),
          account ? api.walletOrders(account, market.id) : Promise.resolve({ items: [], nextCursor: null }),
        ]);
        setOrderBook([...book.asks, ...book.bids]);
        setTrades(tradePage.items);
        setTradesCursor(tradePage.nextCursor);
        setOrders(orderPage.items);
        setOrdersCursor(orderPage.nextCursor);

        if (account) {
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
        } else {
          setBalances(emptyBalances);
        }
      }
      setLastUpdated(Date.now());
    } catch (error) {
      setLoadError(`Unable to load the backend projection. ${friendlyError(error).message}`);
      await loadBackendStatus();
    } finally {
      setLoading(false);
    }
  }, [account, api, applyBackendStatus, loadBackendStatus, readProvider, selectedMarketId]);

  const loadMoreOrders = useCallback(async () => {
    if (!account || !selectedMarket || !ordersCursor || loadingMoreOrders) return;
    setLoadingMoreOrders(true);
    try {
      const page = await api.walletOrders(account, selectedMarket.id, ordersCursor);
      setOrders((current) => [...current, ...page.items]);
      setOrdersCursor(page.nextCursor);
    } catch (error) {
      setLoadError(friendlyError(error).message);
    } finally {
      setLoadingMoreOrders(false);
    }
  }, [account, api, loadingMoreOrders, ordersCursor, selectedMarket]);

  const loadMoreTrades = useCallback(async () => {
    if (!selectedMarket || !tradesCursor || loadingMoreTrades) return;
    setLoadingMoreTrades(true);
    try {
      const page = await api.trades(selectedMarket, tradesCursor);
      setTrades((current) => [...current, ...page.items]);
      setTradesCursor(page.nextCursor);
    } catch (error) {
      setLoadError(friendlyError(error).message);
    } finally {
      setLoadingMoreTrades(false);
    }
  }, [api, loadingMoreTrades, selectedMarket, tradesCursor]);

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
    setBalances(emptyBalances);
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
        params: [{
          chainId: expectedChainHex,
          chainName: config.chainName,
          rpcUrls: [config.rpcUrl],
          nativeCurrency: { name: "Test Ether", symbol: "ETH", decimals: 18 },
        }],
      });
    }
  }, []);

  const signer = useCallback(async () => {
    if (!window.ethereum || !account) throw new Error("Connect your wallet first.");
    if (wrongNetwork) throw new Error(`Switch to ${config.chainName} before continuing.`);
    if (!backend.online) throw new Error("The Java backend must be online before submitting a transaction.");
    if (backend.identityError) throw new Error(backend.identityError);
    return new BrowserProvider(window.ethereum).getSigner();
  }, [account, backend.identityError, backend.online, wrongNetwork]);

  const waitForIndexedBlock = useCallback(async (blockNumber: bigint): Promise<boolean> => {
    const deadline = Date.now() + 30_000;
    while (Date.now() < deadline) {
      const status = await loadBackendStatus();
      if (status.indexedBlock !== undefined && status.indexedBlock >= blockNumber) return true;
      await sleep(750);
    }
    return false;
  }, [loadBackendStatus]);

  const runTransaction = useCallback(async (
    label: string,
    submit: () => Promise<ContractTransactionResponse>,
  ): Promise<boolean> => {
    const id = `${Date.now()}-${Math.random()}`;
    const update = (patch: Partial<TxNotice>) => setTxNotices(
      (items) => items.map((item) => item.id === id ? { ...item, ...patch } : item),
    );
    const initialNotice: TxNotice = {
      id,
      label,
      phase: "awaiting-signature",
      message: "Confirm this request in your wallet.",
      createdAt: Date.now(),
    };
    setTxNotices((items) => [initialNotice, ...items].slice(0, 8));

    try {
      const transaction = await submit();
      update({ phase: "submitted", hash: transaction.hash, message: "Submitted. Waiting for an on-chain receipt…" });
      const receipt = await transaction.wait();
      if (!receipt || receipt.status !== 1) throw new Error("The transaction was mined but did not succeed.");
      update({ phase: "indexing", message: `Confirmed in block ${receipt.blockNumber}. Waiting for backend indexing…` });
      const indexed = await waitForIndexedBlock(BigInt(receipt.blockNumber));
      await refresh();
      update({
        phase: "confirmed",
        message: indexed
          ? `Confirmed and indexed from block ${receipt.blockNumber}.`
          : `Confirmed in block ${receipt.blockNumber}; the backend is still catching up.`,
      });
      return true;
    } catch (error) {
      const parsed = friendlyError(error);
      update({ phase: parsed.rejected ? "rejected" : "failed", message: parsed.message });
      return false;
    }
  }, [refresh, waitForIndexedBlock]);

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
    if (!selectedMarket.active) {
      setLoadError("This market is inactive and cannot accept new orders.");
      return false;
    }
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
    orderBook, orders, ordersCursor, trades, tradesCursor, balances, loading, loadingMoreOrders, loadingMoreTrades,
    loadError, lastUpdated, txNotices, clearTxNotices, backend, isTokenAdmin, isMarketAdmin,
    walletAvailable: !!window.ethereum, connect, disconnect, switchNetwork, refresh, loadMoreOrders, loadMoreTrades,
    approve, placeOrder, cancelOrder, registerToken, registerMarket,
  };
}
