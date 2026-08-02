package io.g3tech.axetrader;

import java.io.IOException;
import java.util.Map;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class AxeTraderApplication {

	static void main(String[] args) throws IOException {
		SpringApplication application = new SpringApplication(AxeTraderApplication.class);
		application.addListeners(new HistoryImportEnvironmentListener());
		application.run(args);
	}

	private static final class HistoryImportEnvironmentListener
			implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

		@Override
		public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
			boolean importEnabled = event.getEnvironment().getProperty(
					"axe-trader.history-import.enabled", Boolean.class, false);
			if (importEnabled) {
				event.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
						"historyImportRuntimeIsolation",
						Map.of(
								"spring.datasource.url", "jdbc:sqlite::memory:",
								"spring.flyway.enabled", "false",
								"spring.devtools.restart.enabled", "false")));
				event.getSpringApplication().setWebApplicationType(WebApplicationType.NONE);
				return;
			}

			try {
				// Restore the SQLite history only for normal application startup, after all
				// Spring configuration sources have had a chance to opt into local import.
				DatabaseBootstrap.ensureDatabase(DatabaseBootstrap.DEFAULT_DATABASE,
						DatabaseBootstrap.DEFAULT_ARCHIVE);
			} catch (IOException exception) {
				throw new IllegalStateException("Could not bootstrap the local history database", exception);
			}
		}

		@Override
		public int getOrder() {
			return Ordered.LOWEST_PRECEDENCE;
		}
	}

}
