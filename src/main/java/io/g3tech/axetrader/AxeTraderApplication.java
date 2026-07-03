package io.g3tech.axetrader;

import java.io.IOException;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class AxeTraderApplication {

	static void main(String[] args) throws IOException {
		// Restore the SQLite history from its committed .gz snapshot before the
		// datasource/Flyway initialize (raw .sqlite is gitignored, only .gz is tracked).
		DatabaseBootstrap.ensureDatabase(DatabaseBootstrap.DEFAULT_DATABASE, DatabaseBootstrap.DEFAULT_ARCHIVE);
		SpringApplication.run(AxeTraderApplication.class, args);
	}

}
