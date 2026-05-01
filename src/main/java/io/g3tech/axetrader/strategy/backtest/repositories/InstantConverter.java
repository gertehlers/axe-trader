package io.g3tech.axetrader.strategy.backtest.repositories;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

@Converter(autoApply = true)
public class InstantConverter implements AttributeConverter<Instant, String> {

    @Override
    public String convertToDatabaseColumn(Instant attribute) {
        return ZonedDateTime.ofInstant(attribute, ZoneId.from(ZoneOffset.UTC)).toString();
    }

    @Override
    public Instant convertToEntityAttribute(String dbData) {
        return Instant.parse(dbData);
    }
}
