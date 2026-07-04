package io.g3tech.axetrader.strategy.backtest.repositories;

import io.g3tech.axetrader.strategy.backtest.repositories.data.HistoricalPrice;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface HistoricalPriceRepository extends CrudRepository<HistoricalPrice, UUID> {
    List<HistoricalPrice> findByEpic(String epic, Sort sort, Limit limit);

    List<HistoricalPrice> findByEpicAndSnapshotTimeUtcBetweenOrderBySnapshotTimeUtcAsc(
            String epic, Instant from, Instant to);
}
