package com.dex.backend.persistence.converter;

import com.dex.backend.blockchain.model.EthereumAddress;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class EthereumAddressConverter implements AttributeConverter<EthereumAddress, String> {

    @Override
    public String convertToDatabaseColumn(EthereumAddress address) {
        return address == null ? null : address.value();
    }

    @Override
    public EthereumAddress convertToEntityAttribute(String value) {
        return value == null ? null : new EthereumAddress(value);
    }
}
