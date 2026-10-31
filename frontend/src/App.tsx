import { useMemo, useState, type FormEvent } from "react";
import {
  Activity, AlertTriangle, ArrowDownUp, Check, ChevronDown, CircleOff, Clock3, ExternalLink,
  Landmark, LoaderCircle, LogOut, RefreshCw, ShieldCheck, Wallet, X,
} from "lucide-react";
import { getAddress, parseUnits } from "ethers";
import { config } from "./config";
import { useDex } from "./hooks/useDex";
import { formatAmount, formatOrderBase, formatPrice, parseOrderInput, shortAddress } from "./lib/units";
import type { BackendHealth, Market, Order, OrderBookOrder, Side, Trade, TxNotice } from "./types";

function Brand() {
  return (
    <a className="brand" href="#top" aria-label="Canopy DEX home">
      <span className="brand-mark"><ArrowDownUp size={18} /></span>
      <span><strong>Canopy<span className="brand-dot">.</span></strong><small>Decentralized exchange</small></span>
    </a>
  );
}

function Header(props: {
  account: string | null; chainId: number | null; connecting: boolean; wrongNetwork: boolean;
  walletAvailable: boolean; connect: () => void; disconnect: () => void; switchNetwork: () => void;
}) {
  return (
    <header className="topbar" id="top">
      <Brand />
      <nav className="top-nav" aria-label="Dashboard sections">
        <a href="#trade">Trade</a><a href="#my-orders">My orders</a><a href="#recent-trades">Activity</a><a href="#listing">Assets</a>
      </nav>
      <div className="wallet-area">
        {props.account && (
          <span className={`network-pill ${props.wrongNetwork ? "danger" : ""}`}>
            <i /> {props.wrongNetwork ? `Wrong network (${props.chainId ?? "?"})` : config.chainName}
          </span>
        )}
        {props.account ? (
          <div className="wallet-connected">
            <span className="identicon" aria-hidden="true" />
            <span>{shortAddress(props.account)}</span>
            <button className="icon-button" onClick={props.disconnect} aria-label="Disconnect wallet locally" title="Disconnect"><LogOut size={16} /></button>
          </div>
        ) : (
          <button className="button primary" onClick={props.connect} disabled={props.connecting || !props.walletAvailable}>
            {props.connecting ? <LoaderCircle className="spin" size={17} /> : <Wallet size={17} />}
            {props.walletAvailable ? "Connect wallet" : "Wallet unavailable"}
          </button>
        )}
      </div>
      {props.wrongNetwork && (
        <div className="network-banner">
          <AlertTriangle size={16} /> This app is configured for {config.chainName} (chain {config.chainId}).
          <button onClick={props.switchNetwork}>Switch network</button>
        </div>
      )}
    </header>
  );
}

function MarketHeader(props: { markets: Market[]; market: Market | null; onChange: (id: bigint) => void }) {
  return (
    <section className="market-header">
      <div>
        <p className="eyebrow">MARKET</p>
        <div className="market-select-wrap">
          <select
            className="market-select"
            value={props.market?.id.toString() ?? ""}
            onChange={(event) => props.onChange(BigInt(event.target.value))}
            aria-label="Select market"
          >
            {props.markets.length === 0 && <option value="">No markets registered</option>}
            {props.markets.map((market) => <option key={market.id} value={market.id.toString()}>{market.baseToken.symbol} / {market.quoteToken.symbol}</option>)}
          </select>
          <ChevronDown size={18} />
        </div>
        {props.market && <p className="market-addresses" title={`${props.market.baseToken.address} / ${props.market.quoteToken.address}`}>
          {shortAddress(props.market.baseToken.address)} · {shortAddress(props.market.quoteToken.address)}
        </p>}
      </div>
      {props.market && (
        <div className="market-facts">
          <div><span>LOT SIZE</span><strong>{formatAmount(props.market.baseLotAtoms, props.market.baseToken.decimals)} {props.market.baseToken.symbol}</strong></div>
          <div><span>SETTLEMENT</span><strong>Wallet to wallet</strong></div>
          <div><span>FEES</span><strong>0.00%</strong></div>
        </div>
      )}
    </section>
  );
}

function OrderForm(props: {
  market: Market; account: string | null; wrongNetwork: boolean; writesBlocked: boolean;
  balances: { base: bigint; quote: bigint; baseAllowance: bigint; quoteAllowance: bigint };
  approve: (side: Side, amount: bigint) => Promise<boolean>;
  placeOrder: (side: Side, lots: bigint, limitPrice: bigint) => Promise<boolean>;
}) {
  const [side, setSide] = useState<Side>("BUY");
  const [amount, setAmount] = useState("");
  const [price, setPrice] = useState("");
  const [busy, setBusy] = useState<"approve" | "order" | null>(null);
  const parsed = useMemo(() => {
    if (!amount || !price) return { value: null, error: "" };
    try { return { value: parseOrderInput(amount, price, props.market), error: "" }; }
    catch (error) { return { value: null, error: error instanceof Error ? error.message : "Invalid amount or price." }; }
  }, [amount, price, props.market]);

  const spendToken = side === "BUY" ? props.market.quoteToken : props.market.baseToken;
  const balance = side === "BUY" ? props.balances.quote : props.balances.base;
  const allowance = side === "BUY" ? props.balances.quoteAllowance : props.balances.baseAllowance;
  const required = parsed.value ? (side === "BUY" ? parsed.value.quoteAtoms : parsed.value.baseAtoms) : 0n;
  const approved = required > 0n && allowance >= required;
  const funded = required > 0n && balance >= required;
  const blocked = !props.account || props.wrongNetwork || props.writesBlocked || !props.market.active || !parsed.value;

  const doApprove = async () => {
    if (!parsed.value) return;
    setBusy("approve");
    await props.approve(side, required);
    setBusy(null);
  };
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!parsed.value) return;
    setBusy("order");
    const success = await props.placeOrder(side, parsed.value.lots, parsed.value.limitPrice);
    if (success) { setAmount(""); setPrice(""); }
    setBusy(null);
  };

  return (
    <section className="panel order-entry" id="trade">
      <div className="order-title"><span>Place an order</span><span className="order-type">Limit</span></div>
      <div className="side-tabs" role="tablist" aria-label="Order side">
        <button className={side === "BUY" ? "active buy" : ""} onClick={() => setSide("BUY")}>Buy</button>
        <button className={side === "SELL" ? "active sell" : ""} onClick={() => setSide("SELL")}>Sell</button>
      </div>
      <form onSubmit={submit}>
        <label>
          <span>Amount</span><small>Balance {formatAmount(side === "BUY" ? props.balances.base : props.balances.base, props.market.baseToken.decimals)} {props.market.baseToken.symbol}</small>
          <div className="input-shell"><input value={amount} onChange={(event) => setAmount(event.target.value)} inputMode="decimal" placeholder="0.00" /><b>{props.market.baseToken.symbol}</b></div>
        </label>
        <label>
          <span>Limit price</span><small>per 1 {props.market.baseToken.symbol}</small>
          <div className="input-shell"><input value={price} onChange={(event) => setPrice(event.target.value)} inputMode="decimal" placeholder="0.00" /><b>{props.market.quoteToken.symbol}</b></div>
        </label>
        <div className="estimate-row"><span>Estimated total</span><strong>{parsed.value ? formatAmount(parsed.value.quoteAtoms, props.market.quoteToken.decimals) : "—"} {props.market.quoteToken.symbol}</strong></div>
        <div className="estimate-row"><span>Spending balance</span><strong className={!funded && required > 0n ? "negative" : ""}>{formatAmount(balance, spendToken.decimals)} {spendToken.symbol}</strong></div>
        {parsed.error && <p className="form-error"><AlertTriangle size={14} />{parsed.error}</p>}
        {parsed.value && !funded && <p className="form-error"><AlertTriangle size={14} />Insufficient {spendToken.symbol} balance for settlement.</p>}
        <div className="order-actions">
          <button type="button" className="button secondary" disabled={blocked || approved || busy !== null} onClick={doApprove}>
            {busy === "approve" ? <LoaderCircle className="spin" size={16} /> : approved ? <Check size={16} /> : <ShieldCheck size={16} />}
            {approved ? `${spendToken.symbol} approved` : `1. Approve ${spendToken.symbol}`}
          </button>
          <button type="submit" className={`button ${side === "BUY" ? "buy-button" : "sell-button"}`} disabled={blocked || !approved || !funded || busy !== null}>
            {busy === "order" ? <LoaderCircle className="spin" size={16} /> : <ArrowDownUp size={16} />} 2. Place {side.toLowerCase()}
          </button>
        </div>
        {!props.account && <p className="form-hint">Connect a wallet to approve tokens and place orders.</p>}
        {props.writesBlocked && <p className="form-error"><AlertTriangle size={14} />Trading is blocked until the Java backend is online and its deployment identity matches.</p>}
        {!props.market.active && <p className="form-error"><AlertTriangle size={14} />This market is inactive.</p>}
        <p className="custody-note"><ShieldCheck size={15} /> Orders do not reserve funds. Tokens stay in your wallet until a confirmed match settles.</p>
      </form>
    </section>
  );
}

function EmptyRow({ text, columns }: { text: string; columns: number }) {
  return <tr><td colSpan={columns} className="empty-row">{text}</td></tr>;
}

function OrderBookTable(props: { orders: OrderBookOrder[]; market: Market }) {
  return <section className="panel table-panel" id="order-book">
    <div className="panel-heading"><div><p className="eyebrow">CONFIRMED PROJECTION</p><h2>Order book</h2></div><span className="count-badge">{props.orders.length}</span></div>
    <div className="table-scroll"><table>
      <thead><tr><th>ID</th><th>Side</th><th>Price</th><th>Remaining</th></tr></thead>
      <tbody>
        {props.orders.length === 0 && <EmptyRow text="No eligible orders to show yet." columns={4} />}
        {props.orders.map((order) => <tr key={order.id.toString()}>
          <td className="mono">#{order.id.toString()}</td>
          <td><span className={`side-label ${order.side.toLowerCase()}`}>{order.side}</span></td>
          <td className={`price-${order.side.toLowerCase()}`}>{formatPrice(order.limitPrice, props.market)} <small>{props.market.quoteToken.symbol}</small></td>
          <td><span className="quantity-value">{formatOrderBase(order.remainingLots, props.market)}</span> <small>{props.market.baseToken.symbol}</small></td>
        </tr>)}
      </tbody>
    </table></div>
  </section>;
}

function OrdersTable(props: {
  orders: Order[]; market: Market; account: string | null; writesBlocked: boolean;
  hasMore: boolean; loadingMore: boolean; loadMore: () => void; onCancel: (id: bigint) => void;
}) {
  return (
    <section className="panel table-panel" id="my-orders">
      <div className="panel-heading"><div><p className="eyebrow">CONFIRMED PROJECTION</p><h2>My orders</h2></div><span className="count-badge">{props.orders.length}</span></div>
      <div className="table-scroll">
        <table>
          <thead><tr><th>ID</th><th>Side</th><th>Price</th><th>Remaining / Original</th><th>Status</th><th /></tr></thead>
          <tbody>
            {props.orders.length === 0 && <EmptyRow text="No wallet orders to show yet." columns={6} />}
            {props.orders.map((order) => {
              const displayStatus = order.status === "PARTIALLY_FILLED" ? "PARTIAL" : order.status;
              const canCancel = !props.writesBlocked
                && props.account?.toLowerCase() === order.owner.toLowerCase()
                && (order.status === "OPEN" || order.status === "PARTIALLY_FILLED");
              return <tr key={order.id.toString()}>
                <td className="mono">#{order.id.toString()}</td>
                <td><span className={`side-label ${order.side.toLowerCase()}`}>{order.side}</span></td>
                <td className={`price-${order.side.toLowerCase()}`}>{formatPrice(order.limitPrice, props.market)} <small>{props.market.quoteToken.symbol}</small></td>
                <td><span className="quantity-value">{formatOrderBase(order.remainingLots, props.market)}</span> <span className="muted">/ {formatOrderBase(order.originalLots, props.market)} {props.market.baseToken.symbol}</span></td>
                <td><span className={`status status-${displayStatus.toLowerCase()}`}>{displayStatus}</span></td>
                <td>{canCancel && <button className="text-button danger-text" onClick={() => props.onCancel(order.id)}>Cancel</button>}</td>
              </tr>;
            })}
          </tbody>
        </table>
      </div>
      {props.hasMore && <button className="button table-more" onClick={props.loadMore} disabled={props.loadingMore}>{props.loadingMore ? "Loading…" : "Load more orders"}</button>}
    </section>
  );
}

function TradesTable(props: { trades: Trade[]; market: Market; hasMore: boolean; loadingMore: boolean; loadMore: () => void }) {
  const { trades, market } = props;
  return (
    <section className="panel table-panel trades-panel" id="recent-trades">
      <div className="panel-heading"><div><p className="eyebrow">SETTLED ON-CHAIN</p><h2>Recent trades</h2></div><span className="count-badge">{trades.length}</span></div>
      <div className="table-scroll"><table><thead><tr><th>Block</th><th>Orders</th><th>Price</th><th>Amount</th><th>Total</th><th>Transaction</th></tr></thead><tbody>
        {trades.length === 0 && <EmptyRow text="No confirmed trades for this market." columns={6} />}
        {trades.map((trade) => <tr key={`${trade.transactionHash}-${trade.logIndex}`}>
          <td className="mono">{trade.blockNumber.toString()}</td><td>#{trade.buyOrderId.toString()} ↔ #{trade.sellOrderId.toString()}</td>
          <td className="trade-price">{formatPrice(trade.executionPrice, market)} <small>{market.quoteToken.symbol}</small></td>
          <td><span className="quantity-value">{formatAmount(trade.baseAtoms, market.baseToken.decimals)}</span> <small>{market.baseToken.symbol}</small></td>
          <td>{formatAmount(trade.quoteAtoms, market.quoteToken.decimals)} <small>{market.quoteToken.symbol}</small></td>
          <td><a className="tx-link" href={config.explorerUrl ? `${config.explorerUrl}/tx/${trade.transactionHash}` : `#tx-${trade.transactionHash}`} title={trade.transactionHash} onClick={!config.explorerUrl ? (event) => event.preventDefault() : undefined}>{shortAddress(trade.transactionHash, 8)}{config.explorerUrl && <ExternalLink size={12} />}</a></td>
        </tr>)}
      </tbody></table></div>
      {props.hasMore && <button className="button table-more" onClick={props.loadMore} disabled={props.loadingMore}>{props.loadingMore ? "Loading…" : "Load more trades"}</button>}
    </section>
  );
}

function ServiceStrip(props: { backend: BackendHealth; lastUpdated: number | null; loading: boolean; refresh: () => void }) {
  const warning = !!props.backend.identityError || props.backend.operationalStatus === "DEGRADED";
  const state = props.backend.online ? (warning ? "warning" : "online") : "offline";
  return <div className="service-strip">
    <div className={`service-state ${state}`}>
      {props.backend.online ? <Activity size={16} /> : <CircleOff size={16} />}
      <span><strong>Java backend {props.backend.online ? (props.backend.operationalStatus ?? "online") : "offline"}</strong><small>{props.backend.identityError ?? (props.backend.online
        ? `Indexer ${props.backend.indexerState ?? "?"} · Matcher ${props.backend.matcherState ?? "?"} · Executor ${props.backend.executorState ?? "?"} · Block ${props.backend.indexedBlock?.toString() ?? "—"}`
        : "Automatic matching is unavailable; wallet assets remain safe.")}</small></span>
    </div>
    <div className="refresh-state"><Clock3 size={15} /> {props.lastUpdated ? `Projection refreshed ${new Date(props.lastUpdated).toLocaleTimeString()}` : "Not refreshed"}<button className="icon-button" onClick={props.refresh} disabled={props.loading} aria-label="Refresh backend data"><RefreshCw size={15} className={props.loading ? "spin" : ""} /></button></div>
  </div>;
}

function TxCenter({ notices, onClose }: { notices: TxNotice[]; onClose: () => void }) {
  if (notices.length === 0) return null;
  const icon = (notice: TxNotice) => {
    if (notice.phase === "confirmed") return <Check size={16} />;
    if (notice.phase === "failed" || notice.phase === "rejected") return <X size={16} />;
    return <LoaderCircle className="spin" size={16} />;
  };
  return <aside className="tx-center" aria-live="polite"><div className="tx-center-header"><h2>Transaction activity</h2><button type="button" className="icon-button tx-close" onClick={onClose} aria-label="Close transaction activity" title="Close"><X size={18} /></button></div>{notices.map((notice) => <div key={notice.id} className={`tx-notice ${notice.phase}`} id={notice.hash ? `tx-${notice.hash}` : undefined}>
    <span className="tx-icon">{icon(notice)}</span><div><strong>{notice.label}</strong><p>{notice.message}</p>{notice.hash && <code>{shortAddress(notice.hash, 10)}</code>}</div>
  </div>)}</aside>;
}

function ListingPanel(props: {
  isTokenAdmin: boolean; isMarketAdmin: boolean; account: string | null; writesBlocked: boolean;
  registerToken: (address: string) => Promise<boolean>;
  registerMarket: (base: string, quote: string, lot: bigint) => Promise<boolean>;
}) {
  const [open, setOpen] = useState(false);
  const [token, setToken] = useState("");
  const [base, setBase] = useState("");
  const [quote, setQuote] = useState("");
  const [lot, setLot] = useState("");
  const [decimals, setDecimals] = useState("18");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const registerToken = async (event: FormEvent) => {
    event.preventDefault(); setError("");
    try { getAddress(token); setBusy(true); if (await props.registerToken(token)) setToken(""); }
    catch { setError("Enter a valid ERC-20 contract address."); } finally { setBusy(false); }
  };
  const registerMarket = async (event: FormEvent) => {
    event.preventDefault(); setError("");
    try {
      getAddress(base); getAddress(quote);
      const atoms = parseUnits(lot, Number(decimals));
      if (atoms <= 0n) throw new Error();
      setBusy(true); if (await props.registerMarket(base, quote, atoms)) { setBase(""); setQuote(""); setLot(""); }
    } catch { setError("Use valid token addresses, decimals, and a positive lot size."); } finally { setBusy(false); }
  };
  return <section className="panel listing-panel" id="listing"><button className="listing-toggle" onClick={() => setOpen(!open)} aria-expanded={open}><span><Landmark size={18} /><span><strong>Asset & market listing</strong><small>Owner-only registry tools</small></span></span><ChevronDown size={18} className={open ? "rotate" : ""} /></button>
    {open && <div className="listing-content">
      {!props.account && <p className="permission-note">Connect the deployment-owner wallet to use listing tools.</p>}
      {props.account && !props.isTokenAdmin && !props.isMarketAdmin && <p className="permission-note">The connected account is not a registry owner. Listing controls are read-only.</p>}
      <form onSubmit={registerToken}><h3>1. Approve an ERC-20</h3><div className="inline-form"><input aria-label="Token contract address" placeholder="Token contract address (0x…)" value={token} onChange={(e) => setToken(e.target.value)} /><button className="button secondary" disabled={!props.isTokenAdmin || props.writesBlocked || busy}>Register token</button></div></form>
      <form onSubmit={registerMarket}><h3>2. Create a market</h3><div className="listing-grid"><input aria-label="Base token address" placeholder="Base token address" value={base} onChange={(e) => setBase(e.target.value)} /><input aria-label="Quote token address" placeholder="Quote token address" value={quote} onChange={(e) => setQuote(e.target.value)} /><input aria-label="Base lot size" placeholder="Base lot size (e.g. 0.01)" value={lot} onChange={(e) => setLot(e.target.value)} /><input aria-label="Base token decimals" placeholder="Base decimals" value={decimals} onChange={(e) => setDecimals(e.target.value)} /></div><button className="button secondary" disabled={!props.isMarketAdmin || props.writesBlocked || busy}>Register market</button></form>
      {error && <p className="form-error"><AlertTriangle size={14} />{error}</p>}
      <p className="form-hint">Markets appear automatically after confirmation; no frontend source change is required.</p>
    </div>}
  </section>;
}

export default function App() {
  const dex = useDex();
  const writesBlocked = !dex.backend.online || !!dex.backend.identityError;

  return <>
    <Header account={dex.account} chainId={dex.chainId} connecting={dex.connecting} wrongNetwork={dex.wrongNetwork} walletAvailable={dex.walletAvailable} connect={() => void dex.connect()} disconnect={dex.disconnect} switchNetwork={() => void dex.switchNetwork()} />
    <main>
      {dex.loadError && <div className="error-banner"><AlertTriangle size={17} /><span>{dex.loadError}</span></div>}
      <MarketHeader markets={dex.markets} market={dex.selectedMarket} onChange={dex.setSelectedMarketId} />
      <ServiceStrip backend={dex.backend} lastUpdated={dex.lastUpdated} loading={dex.loading} refresh={() => void dex.refresh()} />
      {dex.loading && dex.markets.length === 0 ? <div className="loading-card"><LoaderCircle className="spin" />Reading the confirmed backend projection…</div> : dex.selectedMarket ? <>
        <div className="trading-grid">
          <OrderForm market={dex.selectedMarket} account={dex.account} wrongNetwork={dex.wrongNetwork} writesBlocked={writesBlocked} balances={dex.balances} approve={dex.approve} placeOrder={dex.placeOrder} />
          <OrderBookTable orders={dex.orderBook} market={dex.selectedMarket} />
        </div>
        <OrdersTable orders={dex.orders} market={dex.selectedMarket} account={dex.account} writesBlocked={writesBlocked} hasMore={!!dex.ordersCursor} loadingMore={dex.loadingMoreOrders} loadMore={() => void dex.loadMoreOrders()} onCancel={(id) => void dex.cancelOrder(id)} />
        <TradesTable trades={dex.trades} market={dex.selectedMarket} hasMore={!!dex.tradesCursor} loadingMore={dex.loadingMoreTrades} loadMore={() => void dex.loadMoreTrades()} />
      </> : <div className="loading-card"><CircleOff />No registered market was found at the configured deployment.</div>}
      <ListingPanel isTokenAdmin={dex.isTokenAdmin} isMarketAdmin={dex.isMarketAdmin} account={dex.account} writesBlocked={writesBlocked} registerToken={dex.registerToken} registerMarket={dex.registerMarket} />
      <footer><Brand /><p>SC4053 Ethereum DEX · Educational local deployment · Never use real assets.</p></footer>
    </main>
    <TxCenter notices={dex.txNotices} onClose={dex.clearTxNotices} />
  </>;
}
