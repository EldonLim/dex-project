package com.dex.backend.persistence.converter;

import com.dex.backend.blockchain.model.Hash256;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class Hash256Converter implements AttributeConverter<Hash256, String> {

    @Override
    public String convertToDatabaseColumn(Hash256 hash) {
        return hash == null ? null : hash.value();
    }

    @Override
    public Hash256 convertToEntityAttribute(String value) {
        return value == null ? null : new Hash256(value);
    }
}
