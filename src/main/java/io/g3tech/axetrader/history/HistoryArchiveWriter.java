package io.g3tech.axetrader.history;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

/**
 * Rebuilds the committed gzip snapshot from the active database.
 *
 * <p>Incremental updates deliberately leave the archive alone, so this runs on demand before the
 * snapshot is committed. It writes to a temporary sibling and moves it into place so an interrupted
 * run never leaves a truncated archive for {@code DatabaseBootstrap} to restore from.
 */
@Component
public class HistoryArchiveWriter {

    public Path rewrite(Path activeDatabase, Path archive) {
        Path active = Objects.requireNonNull(activeDatabase, "activeDatabase").toAbsolutePath().normalize();
        Path target = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        if (!Files.isRegularFile(active)) {
            throw new IllegalStateException("Active database does not exist: " + active);
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".new-", ".tmp");
            try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(temporary))) {
                Files.copy(active, output);
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new IllegalStateException("Could not rewrite the archive " + target, exception);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Nothing further to do; the temporary file is not referenced anywhere.
        }
    }
}
