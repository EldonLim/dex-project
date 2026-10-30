
 // SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";

interface IMarketRegistry {
    function isMarketRegistered(
        uint256 marketId
    ) external view returns (bool);

    function getMarketTokens(
        uint256 marketId
    )
        external
        view
        returns (
            address baseToken,
            address quoteToken,
            uint256 baseLotAtoms
        );
}

contract OrderBook is Ownable, ReentrancyGuard {
    using SafeERC20 for IERC20;

    enum Side {
        BUY,
        SELL
    }

    enum Status {
        OPEN,
        FILLED,
        CANCELLED
    }

    struct Order {
        uint256 id;
        address owner;
        uint256 marketId;
        Side side;
        uint256 originalLots;
        uint256 remainingLots;
        uint256 limitPrice;
        Status status;
    }

    IMarketRegistry public immutable marketRegistry;

    uint256 public nextOrderId = 1;

    mapping(uint256 => Order) private orders;

    event OrderPlaced(
        uint256 indexed orderId,
        address indexed owner,
        uint256 indexed marketId,
        Side side,
        uint256 lots,
        uint256 limitPrice
    );

    event OrderCancelled(uint256 indexed orderId);

    event TradeExecuted(
        uint256 indexed buyOrderId,
        uint256 indexed sellOrderId,
        uint256 indexed marketId,
        address buyer,
        address seller,
        uint256 fillLots,
        uint256 executionPrice,
        uint256 baseAtoms,
        uint256 quoteAtoms
    );

    constructor(
        address marketRegistryAddress
    ) Ownable(msg.sender) {
        require(
            marketRegistryAddress != address(0),
            "Invalid registry address"
        );
        require(
            marketRegistryAddress.code.length > 0,
            "Registry is not a contract"
        );

        marketRegistry = IMarketRegistry(marketRegistryAddress);
    }

    function placeOrder(
        uint256 marketId,
        Side side,
        uint256 lots,
        uint256 limitPrice
    ) external nonReentrant returns (uint256 orderId) {
        require(
            marketRegistry.isMarketRegistered(marketId),
            "Market does not exist"
        );
        require(lots > 0, "Lots must be positive");
        require(limitPrice > 0, "Price must be positive");

        orderId = nextOrderId++;

        orders[orderId] = Order({
            id: orderId,
            owner: msg.sender,
            marketId: marketId,
            side: side,
            originalLots: lots,
            remainingLots: lots,
            limitPrice: limitPrice,
            status: Status.OPEN
        });

        emit OrderPlaced(
            orderId,
            msg.sender,
            marketId,
            side,
            lots,
            limitPrice
        );
    }

    function cancelOrder(
        uint256 orderId
    ) external nonReentrant {
        Order storage order = orders[orderId];

        require(order.id != 0, "Order does not exist");
        require(order.owner == msg.sender, "Not order owner");
        require(
            order.status == Status.OPEN,
            "Order cannot be cancelled"
        );

        order.status = Status.CANCELLED;

        emit OrderCancelled(orderId);
    }

    function executeTrade(
        uint256 buyOrderId,
        uint256 sellOrderId,
        uint256 fillLots,
        uint256 executionPrice
    ) external nonReentrant {
        Order storage buyOrder = orders[buyOrderId];
        Order storage sellOrder = orders[sellOrderId];

        // 1. Validate that both orders exist.
        require(
            buyOrder.id != 0 && sellOrder.id != 0,
            "Order does not exist"
        );

        // 2. Validate order status.
        require(
            buyOrder.status == Status.OPEN &&
                sellOrder.status == Status.OPEN,
            "Orders must be open"
        );

        // 3. Validate order sides.
        require(
            buyOrder.side == Side.BUY &&
                sellOrder.side == Side.SELL,
            "Invalid order sides"
        );

        // 4. Both orders must belong to the same market.
        require(
            buyOrder.marketId == sellOrder.marketId,
            "Markets must match"
        );

        // 5. Validate fill quantity and execution price.
        require(fillLots > 0, "Fill lots must be positive");
        require(
            fillLots <= buyOrder.remainingLots,
            "Fill exceeds buy quantity"
        );
        require(
            fillLots <= sellOrder.remainingLots,
            "Fill exceeds sell quantity"
        );
        require(
            executionPrice > 0,
            "Execution price must be positive"
        );

        // 6. Execution price must satisfy both limit prices.
        require(
            executionPrice <= buyOrder.limitPrice,
            "Price exceeds buy limit"
        );
        require(
            executionPrice >= sellOrder.limitPrice,
            "Price below sell limit"
        );

        // 7. Retrieve the market's token addresses and lot size.
        (
            address baseTokenAddress,
            address quoteTokenAddress,
            uint256 baseLotAtoms
        ) = marketRegistry.getMarketTokens(buyOrder.marketId);

        require(
            baseLotAtoms > 0,
            "Invalid base lot size"
        );

        // Solidity 0.8+ reverts automatically on multiplication overflow.
        uint256 baseAtoms = fillLots * baseLotAtoms;
        uint256 quoteAtoms = fillLots * executionPrice;

        address buyer = buyOrder.owner;
        address seller = sellOrder.owner;

        // 8. Update order state before external token calls.
        // If either transfer fails, the entire transaction reverts,
        // including these state changes.
        buyOrder.remainingLots -= fillLots;
        sellOrder.remainingLots -= fillLots;

        if (buyOrder.remainingLots == 0) {
            buyOrder.status = Status.FILLED;
        }

        if (sellOrder.remainingLots == 0) {
            sellOrder.status = Status.FILLED;
        }

        // 9. Settle both sides atomically.
        IERC20(baseTokenAddress).safeTransferFrom(
            seller,
            buyer,
            baseAtoms
        );

        IERC20(quoteTokenAddress).safeTransferFrom(
            buyer,
            seller,
            quoteAtoms
        );

        // 10. Emit the completed trade details.
        emit TradeExecuted(
            buyOrderId,
            sellOrderId,
            buyOrder.marketId,
            buyer,
            seller,
            fillLots,
            executionPrice,
            baseAtoms,
            quoteAtoms
        );
    }

    function getOrder(
        uint256 orderId
    ) external view returns (Order memory) {
        require(
            orders[orderId].id != 0,
            "Order does not exist"
        );

        return orders[orderId];
    }
}