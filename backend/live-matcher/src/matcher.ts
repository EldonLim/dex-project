import { Contract, JsonRpcProvider, NonceManager, Wallet, type ContractTransactionReceipt } from "ethers";
import { erc20Abi, marketRegistryAbi, orderBookAbi } from "./abis.js";
import { proposeMatch } from "./matching.js";
import type { MatcherConfig } from "./config.js";
import type { ChainOrder, MatcherSnapshot } from "./types.js";

function toOrder(raw: any): ChainOrder {
  return {
    id: BigInt(raw.id),
    owner: String(raw.owner),
    marketId: BigInt(raw.marketId),
    side: Number(raw.side) as 0 | 1,
    originalLots: BigInt(raw.originalLots),
    remainingLots: BigInt(raw.remainingLots),
    limitPrice: BigInt(raw.limitPrice),
    status: Number(raw.status) as 0 | 1 | 2,
  };
}

export class LiveMatcher {
  readonly provider: JsonRpcProvider;
  readonly orderBook: Contract;
  readonly marketRegistry: Contract;
  private readonly signer: NonceManager;
  private timer?: NodeJS.Timeout;
  private cyclePromise: Promise<number> | null = null;
  private readonly state: MatcherSnapshot = { running: false, matching: false, matchesExecuted: 0 };

  constructor(readonly config: MatcherConfig) {
    this.provider = new JsonRpcProvider(config.rpcUrl, config.chainId, { staticNetwork: true });
    this.signer = new NonceManager(new Wallet(config.privateKey, this.provider));
    this.orderBook = new Contract(config.orderBookAddress, orderBookAbi, this.signer);
    this.marketRegistry = new Contract(config.marketRegistryAddress, marketRegistryAbi, this.provider);
  }

  snapshot(): MatcherSnapshot {
    return { ...this.state };
  }

  async readOrders(): Promise<ChainOrder[]> {
    const nextOrderId = BigInt(await this.orderBook.nextOrderId());
    const orders: ChainOrder[] = [];
    for (let id = 1n; id < nextOrderId; id += 1n) {
      orders.push(toOrder(await this.orderBook.getOrder(id)));
    }
    return orders;
  }

  private async preflight(proposal: ReturnType<typeof proposeMatch>): Promise<void> {
    if (!proposal) throw new Error("Cannot preflight an empty match");
    const market = await this.marketRegistry.getMarket(proposal.buy.marketId);
    const baseToken = new Contract(String(market.baseToken), erc20Abi, this.provider);
    const quoteToken = new Contract(String(market.quoteToken), erc20Abi, this.provider);
    const baseAtoms = proposal.fillLots * BigInt(market.baseLotAtoms);
    const quoteAtoms = proposal.fillLots * proposal.executionPrice;
    const [sellerBase, sellerAllowance, buyerQuote, buyerAllowance] = await Promise.all([
      baseToken.balanceOf(proposal.sell.owner),
      baseToken.allowance(proposal.sell.owner, this.config.orderBookAddress),
      quoteToken.balanceOf(proposal.buy.owner),
      quoteToken.allowance(proposal.buy.owner, this.config.orderBookAddress),
    ]);

    if (BigInt(sellerBase) < baseAtoms) throw new Error(`Seller ${proposal.sell.owner} has insufficient base balance`);
    if (BigInt(sellerAllowance) < baseAtoms) throw new Error(`Seller ${proposal.sell.owner} has insufficient base allowance`);
    if (BigInt(buyerQuote) < quoteAtoms) throw new Error(`Buyer ${proposal.buy.owner} has insufficient quote balance`);
    if (BigInt(buyerAllowance) < quoteAtoms) throw new Error(`Buyer ${proposal.buy.owner} has insufficient quote allowance`);
  }

  async runCycle(maxMatches = 25): Promise<number> {
    if (this.cyclePromise) return this.cyclePromise;
    this.cyclePromise = this.runCycleInternal(maxMatches).finally(() => { this.cyclePromise = null; });
    return this.cyclePromise;
  }

  private async runCycleInternal(maxMatches: number): Promise<number> {
    this.state.matching = true;
    this.state.lastCycleAt = new Date().toISOString();
    this.state.lastError = undefined;
    let executed = 0;

    try {
      const network = await this.provider.getNetwork();
      if (Number(network.chainId) !== this.config.chainId) {
        throw new Error(`Connected chain ${network.chainId} does not match configured chain ${this.config.chainId}`);
      }

      for (; executed < maxMatches; executed += 1) {
        const proposal = proposeMatch(await this.readOrders());
        if (!proposal) break;
        await this.preflight(proposal);
        const tx = await this.orderBook.executeTrade(
          proposal.buy.id,
          proposal.sell.id,
          proposal.fillLots,
          proposal.executionPrice,
        );
        const receipt = await tx.wait() as ContractTransactionReceipt | null;
        if (!receipt || receipt.status !== 1) throw new Error(`Settlement ${tx.hash} was not confirmed successfully`);
        this.state.matchesExecuted += 1;
        this.state.lastMatchAt = new Date().toISOString();
        this.state.lastTransactionHash = tx.hash;
      }

      const block = await this.provider.getBlockNumber();
      this.state.chainId = this.config.chainId;
      this.state.indexedBlock = block;
      return executed;
    } catch (error) {
      this.state.lastError = error instanceof Error ? error.message : String(error);
      throw error;
    } finally {
      this.state.matching = false;
    }
  }

  start(): void {
    if (this.state.running) return;
    this.state.running = true;
    const poll = async () => {
      try { await this.runCycle(); } catch (error) { console.error(`[matcher] ${error instanceof Error ? error.message : error}`); }
    };
    void poll();
    this.timer = setInterval(() => void poll(), this.config.pollIntervalMs);
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
    this.state.running = false;
  }
}
