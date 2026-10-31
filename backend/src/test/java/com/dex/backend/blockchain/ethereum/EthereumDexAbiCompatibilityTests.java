package com.dex.backend.blockchain.ethereum;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EthereumDexAbiCompatibilityTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void javaEventDefinitionsMatchCheckedInContractArtifacts()
            throws IOException {
        JsonNode marketRegistry = artifact("MarketRegistry.json");
        JsonNode orderBook = artifact("OrderBook.json");

        assertThat(EventEncoder.encode(EthereumDexAbi.MARKET_REGISTERED))
                .isEqualTo(eventTopic(marketRegistry, "MarketRegistered"));
        assertThat(EventEncoder.encode(EthereumDexAbi.ORDER_PLACED))
                .isEqualTo(eventTopic(orderBook, "OrderPlaced"));
        assertThat(EventEncoder.encode(EthereumDexAbi.ORDER_CANCELLED))
                .isEqualTo(eventTopic(orderBook, "OrderCancelled"));
        assertThat(EventEncoder.encode(EthereumDexAbi.TRADE_EXECUTED))
                .isEqualTo(eventTopic(orderBook, "TradeExecuted"));

        assertThat(inputTypes(orderBook, "event", "OrderPlaced"))
                .containsExactly(
                        "uint256", "address", "uint256",
                        "uint8", "uint256", "uint256"
                );
        assertThat(inputTypes(orderBook, "event", "TradeExecuted"))
                .containsExactly(
                        "uint256", "uint256", "uint256",
                        "address", "address", "uint256",
                        "uint256", "uint256", "uint256"
                );
    }

    @Test
    void javaReadDefinitionsMatchCheckedInContractArtifacts()
            throws IOException {
        JsonNode marketRegistry = artifact("MarketRegistry.json");
        JsonNode orderBook = artifact("OrderBook.json");

        assertThat(inputTypes(marketRegistry, "function", "getMarket"))
                .containsExactly("uint256");
        assertThat(outputTypes(marketRegistry, "getMarket"))
                .containsExactly("address", "address", "uint256", "bool");
        assertThat(inputTypes(orderBook, "function", "getOrder"))
                .containsExactly("uint256");
        assertThat(tupleOutputTypes(orderBook, "getOrder"))
                .containsExactly(
                        "uint256", "address", "uint256", "uint8",
                        "uint256", "uint256", "uint256", "uint8"
                );

        assertThat(FunctionEncoder.encode(EthereumDexAbi.getMarket(BigInteger.ONE)))
                .startsWith(selector(signature(marketRegistry, "function", "getMarket")));
        assertThat(FunctionEncoder.encode(EthereumDexAbi.getOrder(BigInteger.ONE)))
                .startsWith(selector(signature(orderBook, "function", "getOrder")));
    }

    @Test
    void settlementArtifactRequiresThePersistedExecutionPrice()
            throws IOException {
        JsonNode orderBook = artifact("OrderBook.json");

        assertThat(inputTypes(orderBook, "function", "executeTrade"))
                .containsExactly("uint256", "uint256", "uint256", "uint256");
        assertThat(signature(orderBook, "function", "executeTrade"))
                .isEqualTo("executeTrade(uint256,uint256,uint256,uint256)");
    }

    private JsonNode artifact(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/contract-abi/" + name
        )) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing checked-in contract ABI " + name
                );
            }
            return objectMapper.readTree(input);
        }
    }

    private String eventTopic(JsonNode artifact, String name) {
        return org.web3j.crypto.Hash.sha3String(
                signature(artifact, "event", name)
        );
    }

    private String selector(String signature) {
        return org.web3j.crypto.Hash.sha3String(signature).substring(0, 10);
    }

    private String signature(JsonNode artifact, String type, String name) {
        return name + "(" + String.join(",", inputTypes(artifact, type, name)) + ")";
    }

    private List<String> inputTypes(
            JsonNode artifact,
            String type,
            String name
    ) {
        return entry(artifact, type, name).path("inputs").valueStream()
                .map(node -> node.path("type").asText())
                .toList();
    }

    private List<String> outputTypes(JsonNode artifact, String name) {
        return entry(artifact, "function", name).path("outputs").valueStream()
                .map(node -> node.path("type").asText())
                .toList();
    }

    private List<String> tupleOutputTypes(JsonNode artifact, String name) {
        return entry(artifact, "function", name)
                .path("outputs")
                .get(0)
                .path("components")
                .valueStream()
                .map(node -> node.path("type").asText())
                .toList();
    }

    private JsonNode entry(JsonNode artifact, String type, String name) {
        return artifact.valueStream()
                .filter(node -> type.equals(node.path("type").asText()))
                .filter(node -> name.equals(node.path("name").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing " + type + " " + name
                ));
    }
}
