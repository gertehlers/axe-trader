package io.g3tech.axetrader.strategy.backtest.repositories;

import io.g3tech.axetrader.strategy.backtest.repositories.data.PriceExclusion;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface PriceExclusionRepository extends Repository<PriceExclusion, PriceExclusion.Id> {

    @Query(value = """
            select count(*) from sqlite_master
            where type = 'table' and name = 'price_exclusion'
            """, nativeQuery = true)
    int priceExclusionLedgerTableCount();

    @Query("""
            select distinct exclusion.id.snapshotTimeUtc
            from PriceExclusion exclusion
            where exclusion.id.epic = :epic
                and exclusion.id.resolution = :resolution
                and exclusion.id.snapshotTimeUtc >= :from
                and exclusion.id.snapshotTimeUtc < :to
            """)
    List<String> findDistinctSnapshotTimes(
            @Param("epic") String epic,
            @Param("resolution") String resolution,
            @Param("from") String from,
            @Param("to") String to);
}
