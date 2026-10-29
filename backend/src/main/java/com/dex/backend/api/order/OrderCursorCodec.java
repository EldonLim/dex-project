package com.dex.backend.api.order;

import com.dex.backend.api.error.InvalidCursorException;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

@Component
public class OrderCursorCodec {

    private static final String VERSION = "v1";
    private static final Pattern NON_NEGATIVE_INTEGER = Pattern.compile("[0-9]+");

    public String encode(Position position) {
        String value = String.join(
                ":",
                VERSION,
                position.blockNumber().toString(),
                position.transactionIndex().toString(),
                position.logIndex().toString(),
                position.orderId().toString()
        );

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public Position decode(String cursor) {
        try {
            if (cursor.isBlank() || cursor.indexOf('=') >= 0) {
                throw new IllegalArgumentException();
            }

            String value = new String(
                    Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8
            );
            String[] components = value.split(":", -1);

            if (components.length != 5 || !VERSION.equals(components[0])) {
                throw new IllegalArgumentException();
            }

            return new Position(
                    parseInteger(components[1]),
                    parseInteger(components[2]),
                    parseInteger(components[3]),
                    parseInteger(components[4])
            );
        } catch (IllegalArgumentException exception) {
            throw new InvalidCursorException();
        }
    }

    private BigInteger parseInteger(String value) {
        if (!NON_NEGATIVE_INTEGER.matcher(value).matches()) {
            throw new IllegalArgumentException();
        }
        return new BigInteger(value);
    }

    public record Position(
            BigInteger blockNumber,
            BigInteger transactionIndex,
            BigInteger logIndex,
            BigInteger orderId
    ) {
    }
}
