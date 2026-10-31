package com.dex.backend.persistence.execution;

import java.math.BigInteger;

public interface ReservedOrderPair {

    BigInteger getBuyOrderId();

    BigInteger getSellOrderId();
}
