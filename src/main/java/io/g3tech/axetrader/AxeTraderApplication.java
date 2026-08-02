package io.g3tech.axetrader;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class AxeTraderApplication {
	private static final Set<String> HISTORY_IMPORT_RUNTIME_PROPERTIES = Set.of(
			"--spring.datasource.url=",
			"--spring.flyway.enabled=",
			"--spring.main.web-application-type=",
			"--spring.devtools.restart.enabled=");

	static void main(String[] args) throws IOException {
		if (historyImportEnabled(args)) {
			SpringApplication.run(AxeTraderApplication.class, historyImportRuntimeArguments(args));
			return;
		}

		// Restore the SQLite history from its committed .gz snapshot before the
		// datasource/Flyway initialize (raw .sqlite is gitignored, only .gz is tracked).
		DatabaseBootstrap.ensureDatabase(DatabaseBootstrap.DEFAULT_DATABASE, DatabaseBootstrap.DEFAULT_ARCHIVE);
		SpringApplication.run(AxeTraderApplication.class, args);
	}

	private static boolean historyImportEnabled(String[] args) {
		String commandLineValue = Arrays.stream(args)
				.filter(argument -> argument.startsWith("--axe-trader.history-import.enabled="))
				.map(argument -> argument.substring(argument.indexOf('=') + 1))
				.reduce((first, second) -> second)
				.orElse(null);
		if (commandLineValue != null) {
			return Boolean.parseBoolean(commandLineValue);
		}
		String systemValue = System.getProperty("axe-trader.history-import.enabled");
		if (systemValue != null) {
			return Boolean.parseBoolean(systemValue);
		}
		String environmentValue = System.getenv("AXE_TRADER_HISTORY_IMPORT_ENABLED");
		return environmentValue != null && Boolean.parseBoolean(environmentValue.toLowerCase(Locale.ROOT));
	}

	private static String[] historyImportRuntimeArguments(String[] args) {
		return Stream.concat(Arrays.stream(args).filter(AxeTraderApplication::isNotImportRuntimeProperty), Stream.of(
				"--spring.datasource.url=jdbc:sqlite::memory:",
				"--spring.flyway.enabled=false",
				"--spring.main.web-application-type=none",
				"--spring.devtools.restart.enabled=false"))
				.toArray(String[]::new);
	}

	private static boolean isNotImportRuntimeProperty(String argument) {
		return HISTORY_IMPORT_RUNTIME_PROPERTIES.stream().noneMatch(argument::startsWith);
	}

}
