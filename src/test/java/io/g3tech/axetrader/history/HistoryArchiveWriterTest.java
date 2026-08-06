package io.g3tech.axetrader.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class HistoryArchiveWriterTest {

    @TempDir
    Path directory;

    private final HistoryArchiveWriter writer = new HistoryArchiveWriter();

    @Test
    void rewritesTheArchiveFromTheActiveDatabase() throws Exception {
        Path active = directory.resolve("active.sqlite");
        Path archive = directory.resolve("active.sqlite.gz");
        Files.writeString(active, "database-contents");
        Files.writeString(archive, "stale");

        writer.rewrite(active, archive);

        try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(archive))) {
            ByteArrayOutputStream restored = new ByteArrayOutputStream();
            input.transferTo(restored);
            assertThat(restored.toString()).isEqualTo("database-contents");
        }
        try (var entries = Files.list(directory)) {
            assertThat(entries).hasSize(2);
        }
    }

    @Test
    void failsClosedWhenTheActiveDatabaseIsMissing() {
        assertThatIllegalStateException().isThrownBy(() ->
                writer.rewrite(directory.resolve("absent.sqlite"), directory.resolve("absent.sqlite.gz")));
    }
}
