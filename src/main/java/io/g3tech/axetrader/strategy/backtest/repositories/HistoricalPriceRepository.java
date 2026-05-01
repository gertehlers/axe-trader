package io.g3tech.axetrader.strategy.backtest.repositories;

import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface HistoricalPriceRepository extends CrudRepository<HistoricalPrice, UUID> {
}
