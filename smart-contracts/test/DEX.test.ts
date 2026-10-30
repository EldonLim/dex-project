
import { expect } from "chai";
import { network } from "hardhat";

const { ethers } = await network.create();

describe("DEX Smart Contracts", function () {
  let owner: any;
  let alice: any;
  let bob: any;

  let tokenA: any;
  let tokenB: any;
  let unregisteredToken: any;
  let tokenRegistry: any;
  let marketRegistry: any;
  let orderBook: any;

  const INITIAL_SUPPLY = ethers.parseUnits("1000000", 18);
  const LOT_SIZE = ethers.parseUnits("1", 18);
  const MARKET_ID = 1n;

  // Solidity enum values
  const BUY = 0;
  const SELL = 1;

  const OPEN = 0;
  const FILLED = 1;
  const CANCELLED = 2;

  // Order test values
  const LOTS = 10n;
  const PRICE = ethers.parseUnits("2", 18);

  beforeEach(async function () {
    [owner, alice, bob] = await ethers.getSigners();

    tokenA = await ethers.deployContract("MockToken", [
      "Token A",
      "TKA",
      INITIAL_SUPPLY,
    ]);

    tokenB = await ethers.deployContract("MockToken", [
      "Token B",
      "TKB",
      INITIAL_SUPPLY,
    ]);

    unregisteredToken = await ethers.deployContract("MockToken", [
      "Unregistered Token",
      "UNT",
      INITIAL_SUPPLY,
    ]);

    tokenRegistry = await ethers.deployContract("TokenRegistry");

    await tokenRegistry.registerToken(await tokenA.getAddress());
    await tokenRegistry.registerToken(await tokenB.getAddress());

    marketRegistry = await ethers.deployContract("MarketRegistry", [
      await tokenRegistry.getAddress(),
    ]);

    await marketRegistry.registerMarket(
      await tokenA.getAddress(),
      await tokenB.getAddress(),
      LOT_SIZE
    );

    orderBook = await ethers.deployContract("OrderBook", [
      await marketRegistry.getAddress(),
    ]);
  });

  // =========================================================
  // 1. MOCK TOKEN TESTS
  // =========================================================

  describe("MockToken", function () {
    it("Should assign the initial supply to the deployer", async function () {
      expect(await tokenA.totalSupply()).to.equal(INITIAL_SUPPLY);
      expect(await tokenA.balanceOf(owner.address)).to.equal(
        INITIAL_SUPPLY
      );
    });

    it("Should store the correct token name and symbol", async function () {
      expect(await tokenA.name()).to.equal("Token A");
      expect(await tokenA.symbol()).to.equal("TKA");
    });
  });

  // =========================================================
  // 2. TOKEN REGISTRY TESTS
  // =========================================================

  describe("TokenRegistry", function () {
    it("Should allow the owner to register a token", async function () {
      const tx = await tokenRegistry.registerToken(
        await unregisteredToken.getAddress()
      );

      await expect(tx)
        .to.emit(tokenRegistry, "TokenRegistered")
        .withArgs(await unregisteredToken.getAddress());

      expect(
        await tokenRegistry.isApprovedToken(
          await unregisteredToken.getAddress()
        )
      ).to.equal(true);
    });

    it("Should reject a zero address", async function () {
      await expect(
        tokenRegistry.registerToken(ethers.ZeroAddress)
      ).to.be.revertedWith("Invalid token address");
    });

    it("Should reject an address without contract code", async function () {
      await expect(
        tokenRegistry.registerToken(alice.address)
      ).to.be.revertedWith("Address is not a contract");
    });

    it("Should reject duplicate token registration", async function () {
      await expect(
        tokenRegistry.registerToken(await tokenA.getAddress())
      ).to.be.revertedWith("Token already registered");
    });

    it("Should prevent non-owners from registering tokens", async function () {
      await expect(
        tokenRegistry
          .connect(alice)
          .registerToken(await unregisteredToken.getAddress())
      ).to.be.revertedWithCustomError(
        tokenRegistry,
        "OwnableUnauthorizedAccount"
      );
    });

    it("Should expose all registered tokens", async function () {
      const registeredTokens =
        await tokenRegistry.getRegisteredTokens();

      expect(registeredTokens).to.have.lengthOf(2);
      expect(registeredTokens).to.include(
        await tokenA.getAddress()
      );
      expect(registeredTokens).to.include(
        await tokenB.getAddress()
      );
    });

    it("Should return the correct registered token count", async function () {
      expect(await tokenRegistry.getTokenCount()).to.equal(2n);
    });

    it("Should report an unregistered token as unapproved", async function () {
      expect(
        await tokenRegistry.isApprovedToken(
          await unregisteredToken.getAddress()
        )
      ).to.equal(false);
    });
  });

  // =========================================================
  // 3. MARKET REGISTRY TESTS
  // =========================================================

  describe("MarketRegistry", function () {
    it("Should register an approved trading pair", async function () {
      expect(
        await marketRegistry.isMarketRegistered(MARKET_ID)
      ).to.equal(true);
    });

    it("Should reject an unapproved base token", async function () {
      await expect(
        marketRegistry.registerMarket(
          await unregisteredToken.getAddress(),
          await tokenB.getAddress(),
          LOT_SIZE
        )
      ).to.be.revertedWith("Base token not approved");
    });

    it("Should reject an unapproved quote token", async function () {
      await expect(
        marketRegistry.registerMarket(
          await tokenA.getAddress(),
          await unregisteredToken.getAddress(),
          LOT_SIZE
        )
      ).to.be.revertedWith("Quote token not approved");
    });

    it("Should reject a zero base address", async function () {
      await expect(
        marketRegistry.registerMarket(
          ethers.ZeroAddress,
          await tokenB.getAddress(),
          LOT_SIZE
        )
      ).to.be.revertedWith("Invalid base token");
    });

    it("Should reject a zero quote address", async function () {
      await expect(
        marketRegistry.registerMarket(
          await tokenA.getAddress(),
          ethers.ZeroAddress,
          LOT_SIZE
        )
      ).to.be.revertedWith("Invalid quote token");
    });

    it("Should reject identical base and quote tokens", async function () {
      await expect(
        marketRegistry.registerMarket(
          await tokenA.getAddress(),
          await tokenA.getAddress(),
          LOT_SIZE
        )
      ).to.be.revertedWith("Tokens must differ");
    });

    it("Should reject a zero lot size", async function () {
      await expect(
        marketRegistry.registerMarket(
          await tokenA.getAddress(),
          await tokenB.getAddress(),
          0
        )
      ).to.be.revertedWith("Lot size must be positive");
    });

    it("Should reject duplicate trading pairs", async function () {
      await expect(
        marketRegistry.registerMarket(
          await tokenA.getAddress(),
          await tokenB.getAddress(),
          LOT_SIZE
        )
      ).to.be.revertedWith("Market already registered");
    });

    it("Should assign unique market IDs", async function () {
      await marketRegistry.registerMarket(
        await tokenB.getAddress(),
        await tokenA.getAddress(),
        LOT_SIZE
      );

      expect(
        await marketRegistry.getMarketId(
          await tokenA.getAddress(),
          await tokenB.getAddress()
        )
      ).to.equal(1n);

      expect(
        await marketRegistry.getMarketId(
          await tokenB.getAddress(),
          await tokenA.getAddress()
        )
      ).to.equal(2n);

      expect(await marketRegistry.nextMarketId()).to.equal(3n);
    });

    it("Should return the correct market details", async function () {
      const market = await marketRegistry.getMarket(MARKET_ID);

      expect(market.baseToken).to.equal(
        await tokenA.getAddress()
      );
      expect(market.quoteToken).to.equal(
        await tokenB.getAddress()
      );
      expect(market.baseLotAtoms).to.equal(LOT_SIZE);
      expect(market.isRegistered).to.equal(true);
    });

    it("Should reject requests for nonexistent markets", async function () {
      await expect(
        marketRegistry.getMarket(999)
      ).to.be.revertedWith("Market does not exist");
    });

    it("Should prevent non-owners from registering markets", async function () {
      await expect(
        marketRegistry
          .connect(alice)
          .registerMarket(
            await tokenB.getAddress(),
            await tokenA.getAddress(),
            LOT_SIZE
          )
      ).to.be.revertedWithCustomError(
        marketRegistry,
        "OwnableUnauthorizedAccount"
      );
    });
  });

  // =========================================================
  // 4. ORDER BOOK TESTS
  // =========================================================

  describe("OrderBook", function () {
    it("Should place a buy order with the correct details", async function () {
      await orderBook.connect(alice).placeOrder(
        MARKET_ID,
        BUY,
        LOTS,
        PRICE
      );

      const order = await orderBook.getOrder(1);

      expect(order.id).to.equal(1n);
      expect(order.owner).to.equal(alice.address);
      expect(order.marketId).to.equal(MARKET_ID);
      expect(order.side).to.equal(BUY);
      expect(order.originalLots).to.equal(LOTS);
      expect(order.remainingLots).to.equal(LOTS);
      expect(order.limitPrice).to.equal(PRICE);
      expect(order.status).to.equal(OPEN);
    });

    it("Should place a sell order with the correct side", async function () {
      await orderBook.connect(bob).placeOrder(
        MARKET_ID,
        SELL,
        LOTS,
        PRICE
      );

      const order = await orderBook.getOrder(1);

      expect(order.owner).to.equal(bob.address);
      expect(order.side).to.equal(SELL);
      expect(order.status).to.equal(OPEN);
    });

    it("Should emit OrderPlaced with the correct details", async function () {
      await expect(
        orderBook.connect(alice).placeOrder(
          MARKET_ID,
          BUY,
          LOTS,
          PRICE
        )
      )
        .to.emit(orderBook, "OrderPlaced")
        .withArgs(
          1n,
          alice.address,
          MARKET_ID,
          BUY,
          LOTS,
          PRICE
        );
    });

    it("Should assign unique sequential order IDs", async function () {
      await orderBook.connect(alice).placeOrder(
        MARKET_ID,
        BUY,
        LOTS,
        PRICE
      );

      await orderBook.connect(bob).placeOrder(
        MARKET_ID,
        SELL,
        LOTS,
        PRICE
      );

      expect(await orderBook.nextOrderId()).to.equal(3n);
      expect((await orderBook.getOrder(1)).id).to.equal(1n);
      expect((await orderBook.getOrder(2)).id).to.equal(2n);
    });

    it("Should reject orders for unregistered markets", async function () {
      await expect(
        orderBook.connect(alice).placeOrder(
          999,
          BUY,
          LOTS,
          PRICE
        )
      ).to.be.revertedWith("Market does not exist");
    });

    it("Should reject zero lots", async function () {
      await expect(
        orderBook.connect(alice).placeOrder(
          MARKET_ID,
          BUY,
          0,
          PRICE
        )
      ).to.be.revertedWith("Lots must be positive");
    });

    it("Should reject a zero limit price", async function () {
      await expect(
        orderBook.connect(alice).placeOrder(
          MARKET_ID,
          BUY,
          LOTS,
          0
        )
      ).to.be.revertedWith("Price must be positive");
    });

    it("Should reject an invalid side value", async function () {
    await expect(
        orderBook.connect(alice).placeOrder(
        MARKET_ID,
        2,
        LOTS,
        PRICE
        )
    ).to.be.revert(ethers);
    });

    it("Should reject retrieval of a nonexistent order", async function () {
      await expect(
        orderBook.getOrder(999)
      ).to.be.revertedWith("Order does not exist");
    });

    it("Should allow the owner to cancel an open order", async function () {
      await orderBook.connect(alice).placeOrder(
        MARKET_ID,
        BUY,
        LOTS,
        PRICE
      );

      await expect(
        orderBook.connect(alice).cancelOrder(1)
      )
        .to.emit(orderBook, "OrderCancelled")
        .withArgs(1n);

      const order = await orderBook.getOrder(1);

      expect(order.status).to.equal(CANCELLED);
      expect(order.originalLots).to.equal(LOTS);
      expect(order.remainingLots).to.equal(LOTS);
    });

    it("Should prevent another user from cancelling an order", async function () {
      await orderBook.connect(alice).placeOrder(
        MARKET_ID,
        BUY,
        LOTS,
        PRICE
      );

      await expect(
        orderBook.connect(bob).cancelOrder(1)
      ).to.be.revertedWith("Not order owner");
    });

    it("Should reject cancellation of a nonexistent order", async function () {
      await expect(
        orderBook.connect(alice).cancelOrder(999)
      ).to.be.revertedWith("Order does not exist");
    });

    it("Should reject cancellation of an already cancelled order", async function () {
      await orderBook.connect(alice).placeOrder(
        MARKET_ID,
        BUY,
        LOTS,
        PRICE
      );

      await orderBook.connect(alice).cancelOrder(1);

      await expect(
        orderBook.connect(alice).cancelOrder(1)
      ).to.be.revertedWith("Order cannot be cancelled");
    });

    it("Should allow cancellation of a different user's order only by its owner", async function () {
      await orderBook.connect(alice).placeOrder(
        MARKET_ID,
        BUY,
        LOTS,
        PRICE
      );

      await expect(
        orderBook.connect(bob).cancelOrder(1)
      ).to.be.revertedWith("Not order owner");
    });

    it("Should keep a cancelled order's status and quantity unchanged", async function () {
      await orderBook.connect(alice).placeOrder(
        MARKET_ID,
        SELL,
        LOTS,
        PRICE
      );

      await orderBook.connect(alice).cancelOrder(1);

      const order = await orderBook.getOrder(1);

      expect(order.status).to.equal(CANCELLED);
      expect(order.originalLots).to.equal(LOTS);
      expect(order.remainingLots).to.equal(LOTS);
    });

    async function prepareTrade(
      buyLots: bigint,
      sellLots: bigint
    ) {
      // Give Alice quote tokens and Bob base tokens.
      await tokenB.transfer(
        alice.address,
        ethers.parseUnits("100", 18)
      );

      await tokenA.transfer(
        bob.address,
        ethers.parseUnits("100", 18)
      );

      // Alice approves the OrderBook to spend quote tokens.
      await tokenB.connect(alice).approve(
        await orderBook.getAddress(),
        ethers.MaxUint256
      );

      // Bob approves the OrderBook to spend base tokens.
      await tokenA.connect(bob).approve(
        await orderBook.getAddress(),
        ethers.MaxUint256
      );

      // Alice places a buy order with ID 1.
      await orderBook.connect(alice).placeOrder(
        MARKET_ID,
        BUY,
        buyLots,
        PRICE
      );

      // Bob places a sell order with ID 2.
      await orderBook.connect(bob).placeOrder(
        MARKET_ID,
        SELL,
        sellLots,
        PRICE
      );
    }

    it("Should execute a partial fill correctly", async function () {
      await prepareTrade(10n, 10n);

      await expect(
        orderBook.executeTrade(1, 2, 4, PRICE)
      )
        .to.emit(orderBook, "TradeExecuted")
        .withArgs(
          1n,
          2n,
          MARKET_ID,
          alice.address,
          bob.address,
          4n,
          PRICE,
          ethers.parseUnits("4", 18),
          ethers.parseUnits("8", 18)
        );

      const buyOrder = await orderBook.getOrder(1);
      const sellOrder = await orderBook.getOrder(2);

      expect(buyOrder.originalLots).to.equal(10n);
      expect(buyOrder.remainingLots).to.equal(6n);
      expect(buyOrder.status).to.equal(OPEN);

      expect(sellOrder.originalLots).to.equal(10n);
      expect(sellOrder.remainingLots).to.equal(6n);
      expect(sellOrder.status).to.equal(OPEN);

      // Alice receives 4 base tokens and pays 8 quote tokens.
      expect(
        await tokenA.balanceOf(alice.address)
      ).to.equal(ethers.parseUnits("4", 18));

      expect(
        await tokenB.balanceOf(alice.address)
      ).to.equal(ethers.parseUnits("92", 18));

      // Bob receives 8 quote tokens and sells 4 base tokens.
      expect(
        await tokenB.balanceOf(bob.address)
      ).to.equal(ethers.parseUnits("8", 18));

      expect(
        await tokenA.balanceOf(bob.address)
      ).to.equal(ethers.parseUnits("96", 18));
    });

    it("Should mark both orders FILLED after a full fill", async function () {
      await prepareTrade(10n, 10n);

      await orderBook.executeTrade(1, 2, 10, PRICE);

      const buyOrder = await orderBook.getOrder(1);
      const sellOrder = await orderBook.getOrder(2);

      expect(buyOrder.remainingLots).to.equal(0n);
      expect(buyOrder.status).to.equal(FILLED);

      expect(sellOrder.remainingLots).to.equal(0n);
      expect(sellOrder.status).to.equal(FILLED);
    });

    it("Should reject a fill larger than the buy order", async function () {
      await prepareTrade(3n, 10n);

      await expect(
        orderBook.executeTrade(1, 2, 4, PRICE)
      ).to.be.revertedWith("Fill exceeds buy quantity");
    });

    it("Should reject a fill larger than the sell order", async function () {
      await prepareTrade(10n, 3n);

      await expect(
        orderBook.executeTrade(1, 2, 4, PRICE)
      ).to.be.revertedWith("Fill exceeds sell quantity");
    });

    it("Should reject a zero fill", async function () {
      await prepareTrade(10n, 10n);

      await expect(
        orderBook.executeTrade(1, 2, 0, PRICE)
      ).to.be.revertedWith("Fill lots must be positive");
    });

    it("Should reject an execution price above the buy limit", async function () {
      await prepareTrade(10n, 10n);

      const higherPrice = ethers.parseUnits("3", 18);

      await expect(
        orderBook.executeTrade(1, 2, 1, higherPrice)
      ).to.be.revertedWith("Price exceeds buy limit");
    });

    it("Should reject an execution price below the sell limit", async function () {
      await prepareTrade(10n, 10n);

      const lowerPrice = ethers.parseUnits("1", 18);

      await expect(
        orderBook.executeTrade(1, 2, 1, lowerPrice)
      ).to.be.revertedWith("Price below sell limit");
    });

    it("Should reject orders from different markets", async function () {
      await prepareTrade(10n, 10n);

      // Register the reverse pair as a second market.
      await marketRegistry.registerMarket(
        await tokenB.getAddress(),
        await tokenA.getAddress(),
        LOT_SIZE
      );

      await orderBook.connect(bob).placeOrder(
        2,
        SELL,
        10,
        PRICE
      );

      await expect(
        orderBook.executeTrade(1, 3, 1, PRICE)
      ).to.be.revertedWith("Markets must match");
    });

    it("Should revert settlement if the seller has not approved base tokens", async function () {
      // Fund the accounts but do not approve the OrderBook.
      await tokenB.transfer(
        alice.address,
        ethers.parseUnits("100", 18)
      );

      await tokenA.transfer(
        bob.address,
        ethers.parseUnits("100", 18)
      );

      await orderBook.connect(alice).placeOrder(
        MARKET_ID, BUY, 10, PRICE
      );

      await orderBook.connect(bob).placeOrder(
        MARKET_ID, SELL, 10, PRICE
      );

      await tokenB.connect(alice).approve(
        await orderBook.getAddress(),
        ethers.MaxUint256
      );

      await expect(
        orderBook.executeTrade(1, 2, 1, PRICE)
      ).to.be.revert(ethers);

      // The failed transaction must not change order quantities.
      expect(
        (await orderBook.getOrder(1)).remainingLots
      ).to.equal(10n);

      expect(
        (await orderBook.getOrder(2)).remainingLots
      ).to.equal(10n);
    });
  });
});