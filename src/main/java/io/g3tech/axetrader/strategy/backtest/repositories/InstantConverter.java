package io.g3tech.axetrader.strategy.backtest.repositories;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Converter(autoApply = true)
public class InstantConverter implements AttributeConverter<Instant, String> {

    private static final DateTimeFormatter CANONICAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    @Override
    public String convertToDatabaseColumn(Instant attribute) {
        return CANONICAL.format(attribute);
    }

    @Override
    public Instant convertToEntityAttribute(String dbData) {
        return ZonedDateTime.parse(dbData).toInstant();
    }
}
