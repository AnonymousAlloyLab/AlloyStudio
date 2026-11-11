package is.fivefivefive.ACGN.repair;

import java.util.Objects;

/** Source occurrence edit. Admission requires membership in the current candidate's menu. */
public record RepairAction(String taskId, String candidateHash, int revision,
                           Kind kind, String path, int start, int end,
                           String expected, String replacement, String operation) {
    public enum Kind { REPLACE_OPERATOR, WRAP_NOT, UNWRAP_NOT, MODIFY_QUANTIFIER, STOP }

    public RepairAction(String taskId, String candidateHash, int revision, Kind kind, String path,
                        int start, int end, String expected, String replacement) {
        this(taskId, candidateHash, revision, kind, path, start, end, expected, replacement, kind.name());
    }

    public RepairAction {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(candidateHash, "candidateHash");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(operation, "operation");
        if (revision < 0 || start < 0 || end < start) {
            throw new IllegalArgumentException("Invalid action coordinate");
        }
    }
}
