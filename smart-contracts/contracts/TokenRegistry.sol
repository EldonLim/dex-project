
 // SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";

contract TokenRegistry is Ownable {
    struct TokenInfo {
        bool isRegistered;
        uint256 registeredAt;
    }

    mapping(address => TokenInfo) public tokens;
    address[] private registeredTokens;

    event TokenRegistered(address indexed token);

    constructor() Ownable(msg.sender) {}

    function registerToken(address token) external onlyOwner {
        require(token != address(0), "Invalid token address");
        require(token.code.length > 0, "Address is not a contract");
        require(!tokens[token].isRegistered, "Token already registered");

        // Check that the contract supports the ERC-20 totalSupply function.
        try IERC20(token).totalSupply() returns (uint256) {
            tokens[token] = TokenInfo({
                isRegistered: true,
                registeredAt: block.timestamp
            });

            registeredTokens.push(token);
            emit TokenRegistered(token);
        } catch {
            revert("Not a valid ERC-20 token");
        }
    }

    function isApprovedToken(address token)
        external
        view
        returns (bool)
    {
        return tokens[token].isRegistered;
    }

    function getRegisteredTokens()
        external
        view
        returns (address[] memory)
    {
        return registeredTokens;
    }

    function getTokenCount() external view returns (uint256) {
        return registeredTokens.length;
    }
}