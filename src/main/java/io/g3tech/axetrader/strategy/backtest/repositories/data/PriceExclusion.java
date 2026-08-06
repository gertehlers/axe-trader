package io.g3tech.axetrader.strategy.backtest.repositories.data;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

@Data
@Entity
@Table(name = "price_exclusion")
public class PriceExclusion {

    @EmbeddedId
    private Id id;

    private Instant detectedAtUtc;

    @Data
    @Embeddable
    public static class Id implements Serializable {
        private String importRunId;
        private String source;
        private String epic;
        private String resolution;
        private String snapshotTimeUtc;
        private String reason;
    }
}
