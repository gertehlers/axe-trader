package io.g3tech.axetrader.history;

import java.util.Objects;

/** Raised when a staged history import is not eligible for promotion. */
public final class HistoryAuditFailedException extends IllegalStateException {

    private final HistoryImportAudit audit;

    public HistoryAuditFailedException(HistoryImportAudit audit) {
        super("history import audit is not promotable: " + Objects.requireNonNull(audit, "audit"));
        this.audit = audit;
    }

    public HistoryImportAudit audit() {
        return audit;
    }
}
