package io.g3tech.axetrader.backtest.discovery;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Resolves the commit a discovery run was produced from, and refuses to let a run claim a commit
 * whose source no longer matches the working tree.
 */
public final class SourceProvenance {

    private static final String RENAME_ARROW = " -> ";

    private SourceProvenance() {
    }

    public static String currentCommit() {
        List<String> output = git("rev-parse", "HEAD");
        if (output.isEmpty()) {
            throw new IllegalStateException("Could not resolve the current commit with git rev-parse");
        }
        return output.getFirst().trim();
    }

    /** Paths whose modification would make {@code sourceCommit} a false claim about a run. */
    public static List<String> blockingChanges() {
        return blockingChanges(git("status", "--porcelain"));
    }

    /**
     * Chart output under {@code output/} is excluded deliberately: {@code ./mvnw test} rewrites
     * tracked files there, so a blanket check would block discovery on a checkout that is clean in
     * every way that affects what a run computes.
     */
    static List<String> blockingChanges(List<String> porcelainLines) {
        Objects.requireNonNull(porcelainLines, "porcelainLines");
        List<String> blocking = new ArrayList<>();
        for (String line : porcelainLines) {
            if (line.length() <= 3) {
                continue;
            }
            String path = line.substring(3).trim();
            int arrow = path.indexOf(RENAME_ARROW);
            if (arrow >= 0) {
                path = path.substring(arrow + RENAME_ARROW.length()).trim();
            }
            if (affectsWhatARunComputes(path)) {
                blocking.add(path);
            }
        }
        return List.copyOf(blocking);
    }

    private static boolean affectsWhatARunComputes(String path) {
        return path.startsWith("src/") || path.endsWith("application.yaml");
    }

    private static List<String> git(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            List<String> output = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                }
            }
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git " + String.join(" ", arguments) + " failed: "
                        + String.join("\n", output));
            }
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not run git " + String.join(" ", arguments), exception);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running git", interrupted);
        }
    }
}
