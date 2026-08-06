package io.g3tech.axetrader.history;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

@Configuration
@ConditionalOnProperty(prefix = "axe-trader.history-import", name = "enabled", havingValue = "true")
public class HistoryUpdateConfiguration {

    @Bean
    public HistoryCursorReader historyCursorReader(HistoryImportProperties properties) {
        return new HistoryCursorReader(properties.activeDatabase());
    }

    @Bean
    public HistoryUpdateService historyUpdateService(HistoryCursorReader cursorReader,
                                                     HistoryImportService importService,
                                                     HistoryDeltaMerger merger,
                                                     HistoryImportProperties properties) {
        return new HistoryUpdateService(cursorReader, importService, merger,
                properties.stagingDirectory(), Instant::now);
    }
}
