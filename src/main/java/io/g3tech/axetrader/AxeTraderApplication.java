package io.g3tech.axetrader;

import io.g3tech.historyimport.HistoryReingestionApplication;

import java.io.IOException;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class AxeTraderApplication {

	public static void main(String[] args) throws IOException {
		if (historyImportEnabled(args)) {
			SpringApplication.run(HistoryReingestionApplication.class, args);
			return;
		}
		// Restore the SQLite history from its committed .gz snapshot before the
		// datasource/Flyway initialize (raw .sqlite is gitignored, only .gz is tracked).
		DatabaseBootstrap.ensureDatabase(DatabaseBootstrap.DEFAULT_DATABASE, DatabaseBootstrap.DEFAULT_ARCHIVE);
		SpringApplication.run(AxeTraderApplication.class, args);
	}

	static boolean historyImportEnabled(String[] args) {
		for (String argument : args) {
			if ("--axe-trader.history-import.enabled=true".equals(argument)) {
				return true;
			}
		}
		return Boolean.parseBoolean(System.getProperty("axe-trader.history-import.enabled"))
				|| Boolean.parseBoolean(System.getenv("AXE_TRADER_HISTORY_IMPORT_ENABLED"));
	}

}
