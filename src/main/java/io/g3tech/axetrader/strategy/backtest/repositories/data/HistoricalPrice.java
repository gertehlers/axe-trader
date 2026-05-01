package io.g3tech.axetrader.strategy.backtest.repositories.data;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Entity(name = "historical_price")
public class HistoricalPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String epic;
    private String resolution;
    private Instant snapshotTimeUtc;
    private double openBid;
    private double openAsk;
    private double highBid;
    private double highAsk;
    private double lowBid;
    private double lowAsk;
    private double closeBid;
    private double closeAsk;
    private int lastTradedVolume;
    private String source;
    private Instant ingestionTimeUtc;
}
