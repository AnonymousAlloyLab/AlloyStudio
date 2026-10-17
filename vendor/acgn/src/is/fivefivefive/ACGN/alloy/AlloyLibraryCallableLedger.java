package is.fivefivefive.ACGN.alloy;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Independently fixed callable signatures for Alloy library modules accepted
 * by the source adapter. Unknown modules and members are intentionally absent.
 */
public final class AlloyLibraryCallableLedger {
    public static final String VERSION = "alloy-library-callables-v5";

    private static final Map<String, Signature> SIGNATURES = signatures();

    private AlloyLibraryCallableLedger() {
    }

    public static Signature require(
            String module,
            String member,
            CallSymbol.Kind expectedKind,
            int observedArity) {
        if (observedArity < 0) {
            throw new IllegalArgumentException(
                    "An imported call cannot have negative arity");
        }
        Signature signature = SIGNATURES.get(key(
                module, member, expectedKind, observedArity));
        if (signature == null) {
            String prefix = Objects.requireNonNull(module, "module") + "/"
                    + Objects.requireNonNull(member, "member") + "/"
                    + Objects.requireNonNull(expectedKind, "expectedKind") + "/";
            if (SIGNATURES.keySet().stream().anyMatch(key -> key.startsWith(prefix))) {
                throw new IllegalStateException(
                        "Call arity disagrees with imported declaration for "
                                + module + "/" + member + ": found "
                                + observedArity);
            }
            throw new IllegalStateException(
                    "Imported call lacks an independently pinned declaration: "
                            + module + "/" + member + "/"
                            + expectedKind + "/" + observedArity);
        }
        return signature;
    }

    private static Map<String, Signature> signatures() {
        Map<String, Signature> signatures = new HashMap<>();
        // Audited against the pinned alloy.jar models/util/*.als declarations.
        // Calls remain opaque and argument order is preserved. This ledger adds
        // no library-body, arithmetic or algebraic rewrite rules. util/time uses
        // parser-expanded macros and therefore has no CALL declarations here.

        // util/boolean (9 declarations).
        formula(signatures, "util/boolean", "isTrue", 1);
        formula(signatures, "util/boolean", "isFalse", 1);
        expression(signatures, "util/boolean", "Not", 1);
        expression(signatures, "util/boolean", "And", 2);
        expression(signatures, "util/boolean", "Or", 2);
        expression(signatures, "util/boolean", "Xor", 2);
        expression(signatures, "util/boolean", "Nand", 2);
        expression(signatures, "util/boolean", "Nor", 2);
        expression(signatures, "util/boolean", "subset_", 2);

        // util/graph (13 declarations).
        formula(signatures, "util/graph", "undirected", 1);
        formula(signatures, "util/graph", "noSelfLoops", 1);
        formula(signatures, "util/graph", "weaklyConnected", 1);
        formula(signatures, "util/graph", "stronglyConnected", 1);
        formula(signatures, "util/graph", "rootedAt", 2);
        formula(signatures, "util/graph", "ring", 1);
        formula(signatures, "util/graph", "dag", 1);
        formula(signatures, "util/graph", "forest", 1);
        formula(signatures, "util/graph", "tree", 1);
        formula(signatures, "util/graph", "treeRootedAt", 2);
        expression(signatures, "util/graph", "roots", 1);
        expression(signatures, "util/graph", "leaves", 1);
        expression(signatures, "util/graph", "innerNodes", 1);

        // util/integer (31 declarations).
        expression(signatures, "util/integer", "add", 2);
        expression(signatures, "util/integer", "plus", 2);
        expression(signatures, "util/integer", "sub", 2);
        expression(signatures, "util/integer", "minus", 2);
        expression(signatures, "util/integer", "mul", 2);
        expression(signatures, "util/integer", "div", 2);
        expression(signatures, "util/integer", "rem", 2);
        expression(signatures, "util/integer", "negate", 1);
        formula(signatures, "util/integer", "eq", 2);
        formula(signatures, "util/integer", "gt", 2);
        formula(signatures, "util/integer", "lt", 2);
        formula(signatures, "util/integer", "gte", 2);
        formula(signatures, "util/integer", "lte", 2);
        formula(signatures, "util/integer", "zero", 1);
        formula(signatures, "util/integer", "pos", 1);
        formula(signatures, "util/integer", "neg", 1);
        formula(signatures, "util/integer", "nonpos", 1);
        formula(signatures, "util/integer", "nonneg", 1);
        expression(signatures, "util/integer", "signum", 1);
        expression(signatures, "util/integer", "int2elem", 3);
        expression(signatures, "util/integer", "elem2int", 2);
        expression(signatures, "util/integer", "max", 0);
        expression(signatures, "util/integer", "min", 0);
        expression(signatures, "util/integer", "next", 0);
        expression(signatures, "util/integer", "prev", 0);
        expression(signatures, "util/integer", "max", 1);
        expression(signatures, "util/integer", "min", 1);
        expression(signatures, "util/integer", "prevs", 1);
        expression(signatures, "util/integer", "nexts", 1);
        expression(signatures, "util/integer", "larger", 2);
        expression(signatures, "util/integer", "smaller", 2);

        // util/natural (12 declarations).
        expression(signatures, "util/natural", "inc", 1);
        expression(signatures, "util/natural", "dec", 1);
        expression(signatures, "util/natural", "add", 2);
        expression(signatures, "util/natural", "sub", 2);
        expression(signatures, "util/natural", "mul", 2);
        expression(signatures, "util/natural", "div", 2);
        formula(signatures, "util/natural", "gt", 2);
        formula(signatures, "util/natural", "lt", 2);
        formula(signatures, "util/natural", "gte", 2);
        formula(signatures, "util/natural", "lte", 2);
        expression(signatures, "util/natural", "max", 1);
        expression(signatures, "util/natural", "min", 1);

        // util/ordering (14 declarations).
        expression(signatures, "util/ordering", "first", 0);
        expression(signatures, "util/ordering", "last", 0);
        expression(signatures, "util/ordering", "prev", 0);
        expression(signatures, "util/ordering", "next", 0);
        expression(signatures, "util/ordering", "prevs", 1);
        expression(signatures, "util/ordering", "nexts", 1);
        formula(signatures, "util/ordering", "lt", 2);
        formula(signatures, "util/ordering", "gt", 2);
        formula(signatures, "util/ordering", "lte", 2);
        formula(signatures, "util/ordering", "gte", 2);
        expression(signatures, "util/ordering", "larger", 2);
        expression(signatures, "util/ordering", "smaller", 2);
        expression(signatures, "util/ordering", "max", 1);
        expression(signatures, "util/ordering", "min", 1);

        // util/relation (20 declarations).
        expression(signatures, "util/relation", "dom", 1);
        expression(signatures, "util/relation", "ran", 1);
        formula(signatures, "util/relation", "total", 2);
        formula(signatures, "util/relation", "functional", 2);
        formula(signatures, "util/relation", "function", 2);
        formula(signatures, "util/relation", "surjective", 2);
        formula(signatures, "util/relation", "injective", 2);
        formula(signatures, "util/relation", "bijective", 2);
        formula(signatures, "util/relation", "bijection", 3);
        formula(signatures, "util/relation", "reflexive", 2);
        formula(signatures, "util/relation", "irreflexive", 1);
        formula(signatures, "util/relation", "symmetric", 1);
        formula(signatures, "util/relation", "antisymmetric", 1);
        formula(signatures, "util/relation", "transitive", 1);
        formula(signatures, "util/relation", "acyclic", 2);
        formula(signatures, "util/relation", "complete", 2);
        formula(signatures, "util/relation", "preorder", 2);
        formula(signatures, "util/relation", "equivalence", 2);
        formula(signatures, "util/relation", "partialOrder", 2);
        formula(signatures, "util/relation", "totalOrder", 2);

        // util/seqrel (22 declarations).
        formula(signatures, "util/seqrel", "isSeq", 1);
        expression(signatures, "util/seqrel", "elems", 1);
        expression(signatures, "util/seqrel", "first", 1);
        expression(signatures, "util/seqrel", "last", 1);
        expression(signatures, "util/seqrel", "rest", 1);
        expression(signatures, "util/seqrel", "butlast", 1);
        formula(signatures, "util/seqrel", "isEmpty", 1);
        formula(signatures, "util/seqrel", "hasDups", 1);
        expression(signatures, "util/seqrel", "inds", 1);
        expression(signatures, "util/seqrel", "lastIdx", 1);
        expression(signatures, "util/seqrel", "afterLastIdx", 1);
        expression(signatures, "util/seqrel", "idxOf", 2);
        expression(signatures, "util/seqrel", "lastIdxOf", 2);
        expression(signatures, "util/seqrel", "indsOf", 2);
        expression(signatures, "util/seqrel", "add", 2);
        expression(signatures, "util/seqrel", "setAt", 3);
        expression(signatures, "util/seqrel", "insert", 3);
        expression(signatures, "util/seqrel", "delete", 2);
        expression(signatures, "util/seqrel", "append", 2);
        expression(signatures, "util/seqrel", "subseq", 3);
        expression(signatures, "util/seqrel", "firstIdx", 0);
        expression(signatures, "util/seqrel", "finalIdx", 0);

        // util/sequence (25 declarations).
        // Pinned declarations only: this legacy resource has a pre-existing
        // Alloy parser type error. Admission still requires successful parsing.
        formula(signatures, "util/sequence", "noDuplicates", 0);
        formula(signatures, "util/sequence", "allExist", 0);
        formula(signatures, "util/sequence", "allExistNoDuplicates", 0);
        expression(signatures, "util/sequence", "at", 2);
        expression(signatures, "util/sequence", "elems", 1);
        expression(signatures, "util/sequence", "first", 1);
        expression(signatures, "util/sequence", "last", 1);
        formula(signatures, "util/sequence", "rest", 2);
        formula(signatures, "util/sequence", "isEmpty", 1);
        formula(signatures, "util/sequence", "hasDups", 1);
        expression(signatures, "util/sequence", "inds", 1);
        expression(signatures, "util/sequence", "lastIdx", 1);
        expression(signatures, "util/sequence", "afterLastIdx", 1);
        expression(signatures, "util/sequence", "idxOf", 2);
        expression(signatures, "util/sequence", "lastIdxOf", 2);
        expression(signatures, "util/sequence", "indsOf", 2);
        formula(signatures, "util/sequence", "startsWith", 2);
        formula(signatures, "util/sequence", "add", 3);
        formula(signatures, "util/sequence", "setAt", 4);
        formula(signatures, "util/sequence", "insert", 4);
        formula(signatures, "util/sequence", "copy", 3);
        formula(signatures, "util/sequence", "append", 3);
        formula(signatures, "util/sequence", "subseq", 4);
        expression(signatures, "util/sequence", "firstIdx", 0);
        expression(signatures, "util/sequence", "finalIdx", 0);

        // util/sequniv (20 declarations).
        formula(signatures, "util/sequniv", "isSeq", 1);
        expression(signatures, "util/sequniv", "elems", 1);
        expression(signatures, "util/sequniv", "first", 1);
        expression(signatures, "util/sequniv", "last", 1);
        expression(signatures, "util/sequniv", "rest", 1);
        expression(signatures, "util/sequniv", "butlast", 1);
        formula(signatures, "util/sequniv", "isEmpty", 1);
        formula(signatures, "util/sequniv", "hasDups", 1);
        expression(signatures, "util/sequniv", "inds", 1);
        expression(signatures, "util/sequniv", "lastIdx", 1);
        expression(signatures, "util/sequniv", "afterLastIdx", 1);
        expression(signatures, "util/sequniv", "idxOf", 2);
        expression(signatures, "util/sequniv", "lastIdxOf", 2);
        expression(signatures, "util/sequniv", "indsOf", 2);
        expression(signatures, "util/sequniv", "add", 2);
        expression(signatures, "util/sequniv", "setAt", 3);
        expression(signatures, "util/sequniv", "insert", 3);
        expression(signatures, "util/sequniv", "delete", 2);
        expression(signatures, "util/sequniv", "append", 2);
        expression(signatures, "util/sequniv", "subseq", 3);

        // util/ternary (9 declarations).
        expression(signatures, "util/ternary", "dom", 1);
        expression(signatures, "util/ternary", "ran", 1);
        expression(signatures, "util/ternary", "mid", 1);
        expression(signatures, "util/ternary", "select12", 1);
        expression(signatures, "util/ternary", "select13", 1);
        expression(signatures, "util/ternary", "select23", 1);
        expression(signatures, "util/ternary", "flip12", 1);
        expression(signatures, "util/ternary", "flip13", 1);
        expression(signatures, "util/ternary", "flip23", 1);
        return Collections.unmodifiableMap(signatures);
    }

    private static void expression(
            Map<String, Signature> signatures,
            String module,
            String member,
            int arity) {
        put(signatures, module, member, arity, CallSymbol.Kind.EXPRESSION);
    }

    private static void formula(
            Map<String, Signature> signatures,
            String module,
            String member,
            int arity) {
        put(signatures, module, member, arity, CallSymbol.Kind.FORMULA);
    }

    private static void put(
            Map<String, Signature> signatures,
            String module,
            String member,
            int arity,
        CallSymbol.Kind kind) {
        Signature previous = signatures.put(
                key(module, member, kind, arity), new Signature(arity, kind));
        if (previous != null) {
            throw new IllegalStateException(
                    "Duplicate imported callable signature: " + module + "/"
                            + member + "/" + kind + "/" + arity);
        }
    }

    private static String key(
            String module,
            String member,
            CallSymbol.Kind kind,
            int arity) {
        return Objects.requireNonNull(module, "module") + "/"
                + Objects.requireNonNull(member, "member") + "/"
                + Objects.requireNonNull(kind, "kind") + "/" + arity;
    }

    public static final class Signature {
        private final int arity;
        private final CallSymbol.Kind kind;

        private Signature(int arity, CallSymbol.Kind kind) {
            this.arity = arity;
            this.kind = kind;
        }

        public int arity() {
            return arity;
        }

        public CallSymbol.Kind kind() {
            return kind;
        }
    }
}
