// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";

interface ITokenRegistry {
    function isApprovedToken(address token) external view returns (bool);
}

contract MarketRegistry is Ownable {
    struct Market {
        address baseToken;
        address quoteToken;
        uint256 baseLotAtoms;
        bool isRegistered;
    }

    ITokenRegistry public immutable tokenRegistry;

    uint256 public nextMarketId = 1;

    mapping(uint256 => Market) private markets;
    mapping(bytes32 => uint256) private marketIds;

    event MarketRegistered(
        uint256 indexed marketId,
        address indexed baseToken,
        address indexed quoteToken,
        uint256 baseLotAtoms
    );

    constructor(address tokenRegistryAddress) Ownable(msg.sender) {
        require(
            tokenRegistryAddress != address(0),
            "Invalid registry address"
        );
        require(
            tokenRegistryAddress.code.length > 0,
            "Registry is not a contract"
        );

        tokenRegistry = ITokenRegistry(tokenRegistryAddress);
    }

    function registerMarket(
        address base,
        address quote,
        uint256 baseLotAtoms
    ) external onlyOwner returns (uint256 marketId) {
        require(base != address(0), "Invalid base token");
        require(quote != address(0), "Invalid quote token");
        require(base != quote, "Tokens must differ");
        require(baseLotAtoms > 0, "Lot size must be positive");

        require(
            tokenRegistry.isApprovedToken(base),
            "Base token not approved"
        );
        require(
            tokenRegistry.isApprovedToken(quote),
            "Quote token not approved"
        );

        bytes32 key = keccak256(abi.encode(base, quote));
        require(marketIds[key] == 0, "Market already registered");

        marketId = nextMarketId++;

        markets[marketId] = Market({
            baseToken: base,
            quoteToken: quote,
            baseLotAtoms: baseLotAtoms,
            isRegistered: true
        });

        marketIds[key] = marketId;

        emit MarketRegistered(
            marketId,
            base,
            quote,
            baseLotAtoms
        );
    }

    function isMarketRegistered(
        uint256 marketId
    ) external view returns (bool) {
        return markets[marketId].isRegistered;
    }

    function getMarket(
        uint256 marketId
    )
        external
        view
        returns (
            address baseToken,
            address quoteToken,
            uint256 baseLotAtoms,
            bool isRegistered
        )
    {
        Market storage market = markets[marketId];

        require(market.isRegistered, "Market does not exist");

        return (
            market.baseToken,
            market.quoteToken,
            market.baseLotAtoms,
            market.isRegistered
        );
    }

    function getMarketId(
        address base,
        address quote
    ) external view returns (uint256) {
        return marketIds[keccak256(abi.encode(base, quote))];
    }

    function getMarketTokens(uint256 marketId)
        external
        view
        returns (
            address baseToken,
            address quoteToken,
            uint256 baseLotAtoms
        )
    {
        Market storage market = markets[marketId];
        require(market.isRegistered, "Market does not exist");

        return (
            market.baseToken,
            market.quoteToken,
            market.baseLotAtoms
        );
    }
}