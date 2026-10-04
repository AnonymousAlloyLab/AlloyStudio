package is.fivefivefive.CanDis.core;

import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;

/** Single fail-closed validator for every normalized CALL consumer. */
public final class CallMetadata {
    private CallMetadata() {
    }

    public static Validated require(EGraphNode node) {
        if (node == null || node.getOpcode() != Opcode.CALL) {
            throw new IllegalArgumentException("Call metadata requires a CALL node");
        }
        String sourceName = requireText(node.getSourceName(), "source spelling");
        String identity = requireText(node.getSemanticIdentity(), "qualified semantic identity");
        int separator = identity.lastIndexOf('/');
        if (separator <= 0 || separator + 1 >= identity.length()) {
            throw new IllegalStateException(
                    "CALL semantic identity is not qualified: " + identity);
        }
        String kind = requireText(node.getSourceType(), "formula/expression kind");
        if (!"call/formula".equals(kind) && !"call/expression".equals(kind)) {
            throw new IllegalStateException(
                    "CALL has invalid formula/expression kind: " + kind);
        }
        int arity = node.getDeclaredArity();
        if (arity < 0) {
            throw new IllegalStateException("CALL lacks declared arity: " + identity);
        }
        if (node.getChildren().size() != arity) {
            throw new IllegalStateException(
                    "CALL child count disagrees with declared arity: " + identity);
        }
        if (node.isFlexibleArity() || node.getMaxArity() != arity) {
            throw new IllegalStateException(
                    "CALL must use its declared ordered fixed arity: " + identity);
        }
        long occurrenceId = node.getCallOccurrenceId();
        if (occurrenceId < 0L) {
            throw new IllegalStateException(
                    "CALL lacks parser occurrence identity: " + identity);
        }
        String authorityName = requireText(node.getCallArityAuthority(), "arity authority");
        CallSymbol.ArityAuthority authority;
        try {
            authority = CallSymbol.ArityAuthority.valueOf(authorityName);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "CALL has invalid arity authority: " + authorityName);
        }
        return new Validated(
                sourceName, identity, kind, arity, occurrenceId, authority);
    }

    public static String semanticKey(EGraphNode node) {
        Validated call = require(node);
        return call.identity + "/" + call.arity + "/" + call.kind
                + "/" + call.authority.name();
    }

    private static String requireText(String value, String field) {
        if (!ExactAlloyType.isAdmittedIdentity(value)) {
            throw new IllegalStateException("CALL lacks valid " + field);
        }
        return value;
    }

    public static final class Validated {
        private final String sourceName;
        private final String identity;
        private final String kind;
        private final int arity;
        private final long occurrenceId;
        private final CallSymbol.ArityAuthority authority;

        private Validated(
                String sourceName,
                String identity,
                String kind,
                int arity,
                long occurrenceId,
                CallSymbol.ArityAuthority authority) {
            this.sourceName = sourceName;
            this.identity = identity;
            this.kind = kind;
            this.arity = arity;
            this.occurrenceId = occurrenceId;
            this.authority = authority;
        }

        public String sourceName() {
            return sourceName;
        }

        public String identity() {
            return identity;
        }

        public String kind() {
            return kind;
        }

        public int arity() {
            return arity;
        }

        public long occurrenceId() {
            return occurrenceId;
        }

        public CallSymbol.ArityAuthority authority() {
            return authority;
        }
    }
}
