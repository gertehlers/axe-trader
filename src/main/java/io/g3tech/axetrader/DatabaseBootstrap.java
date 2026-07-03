package io.g3tech.axetrader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Restores the SQLite database from its committed gzip snapshot on startup.
 *
 * <p>The raw {@code data/axe-trader.sqlite} is gitignored (it exceeds the 100&nbsp;MB
 * host push limit); only the compressed {@code .gz} is tracked in the repo. This lets
 * a cloud backtest read local history without calling out to the Capital.com API.
 *
 * <p>If the database already exists on disk it is left untouched — a locally grown
 * database (e.g. from MONITOR mode) always wins over the committed snapshot.
 */
final class DatabaseBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseBootstrap.class);

    static final Path DEFAULT_DATABASE = Path.of("data", "axe-trader.sqlite");
    static final Path DEFAULT_ARCHIVE = Path.of("data", "axe-trader.sqlite.gz");

    private DatabaseBootstrap() {
    }

    static void ensureDatabase(Path database, Path gzArchive) throws IOException {
        if (Files.exists(database)) {
            return;
        }
        if (!Files.exists(gzArchive)) {
            logger.warn("No database ({}) and no snapshot ({}) found; a fresh database will be created",
                    database, gzArchive);
            return;
        }

        Path parent = database.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tempDir = parent != null ? parent : Path.of(".");
        Path temp = Files.createTempFile(tempDir, "axe-trader-", ".sqlite.tmp");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(gzArchive));
             OutputStream out = Files.newOutputStream(temp)) {
            in.transferTo(out);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }

        try {
            Files.move(temp, database, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, database, StandardCopyOption.REPLACE_EXISTING);
        }
        logger.info("Restored database {} from snapshot {}", database, gzArchive);
    }
}
