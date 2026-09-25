package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import is.fivefivefive.ACGN.alloy.ExactAlloyType;

/** Exact relation column together with its validated direct-parent path. */
public final class DependentColumnEvidence {
    private final GraphType exactColumn;
    private final List<GraphType> ancestry;
    private final StructuralKey structuralKey;
    private final ExactAlloyType parserAuthoritySource;

    public static DependentColumnEvidence exact(GraphType column) {
        return new DependentColumnEvidence(column, List.of(column), null);
    }

    static DependentColumnEvidence fromExactAlloyType(
            ExactAlloyType type,
            int columnIndex) {
        if (Objects.requireNonNull(type, "type").alternatives().size() != 1) {
            throw new IllegalArgumentException(
                    "Parser-derived single-alternative evidence requires one relation alternative");
        }
        return fromExactAlloyType(type, 0, columnIndex);
    }

    static DependentColumnEvidence fromExactAlloyType(
            ExactAlloyType type,
            int alternativeIndex,
            int columnIndex) {
        Objects.requireNonNull(type, "type");
        if (type.kind() != ExactAlloyType.Kind.RELATION
                || type.alternatives().isEmpty()
                || type.alternatives().size()
                        != type.ancestryAlternatives().size()) {
            throw new IllegalArgumentException(
                    "Parser-derived dependent evidence requires exact relation alternatives");
        }
        if (alternativeIndex < 0
                || alternativeIndex >= type.alternatives().size()) {
            throw new IllegalArgumentException(
                    "Parser-derived dependent evidence names an invalid relation alternative");
        }
        List<String> columns = type.alternatives().get(alternativeIndex);
        List<List<String>> ancestries = type.ancestryAlternatives().get(
                alternativeIndex);
        if (columnIndex < 0 || columnIndex >= columns.size()
                || columns.size() != ancestries.size()) {
            throw new IllegalArgumentException(
                    "Parser-derived dependent evidence names an invalid relation column");
        }
        GraphType exact = alloyColumn(columns.get(columnIndex));
        List<GraphType> path = ancestries.get(columnIndex).stream()
                .map(DependentColumnEvidence::alloyColumn)
                .toList();
        return new DependentColumnEvidence(exact, path, type);
    }

    private DependentColumnEvidence(
            GraphType exactColumn,
            List<GraphType> ancestry,
            ExactAlloyType parserAuthoritySource) {
        this.exactColumn = requireAtomicColumn(exactColumn, "exact column");
        Objects.requireNonNull(ancestry, "ancestry");
        if (ancestry.isEmpty()) {
            throw new IllegalArgumentException(
                    "A dependent column ancestry must not be empty");
        }
        List<GraphType> copied = new ArrayList<>(ancestry.size());
        LinkedHashSet<GraphType> seen = new LinkedHashSet<>();
        for (GraphType ancestor : ancestry) {
            GraphType checked = requireAtomicColumn(ancestor, "ancestor column");
            if (!seen.add(checked)) {
                throw new IllegalArgumentException(
                        "A dependent column ancestry must be acyclic");
            }
            copied.add(checked);
        }
        if (!this.exactColumn.equals(copied.get(0))) {
            throw new IllegalArgumentException(
                    "A dependent column ancestry must start at its exact column");
        }
        if (copied.size() > 1 && copied.stream().anyMatch(
                column -> column.kind() != GraphType.Kind.INT
                        && (column.kind() != GraphType.Kind.CONSTRUCTOR
                                || !column.arguments().isEmpty()
                                || !column.symbol().startsWith("AlloySig:")))) {
            throw new IllegalArgumentException(
                    "A nontrivial dependent ancestry requires nominal Alloy signatures");
        }
        int univ = copied.indexOf(GraphType.constructor("AlloySig:univ"));
        if (univ >= 0 && univ + 1 != copied.size()) {
            throw new IllegalArgumentException(
                    "AlloySig:univ must terminate a dependent column ancestry");
        }
        this.ancestry = Collections.unmodifiableList(copied);
        if (copied.size() > 1
                && (parserAuthoritySource == null
                        || !parserAuthoritySource.hasParserAuthenticatedAncestry())) {
            throw new DependentChainTheory.UnsupportedFlattening(
                    "Nontrivial dependent ancestry requires live parser authority");
        }
        this.parserAuthoritySource = parserAuthoritySource;
        List<StructuralKey> path = new ArrayList<>(copied.size());
        copied.stream().map(TheoryKeys::type).forEach(path::add);
        this.structuralKey = StructuralKey.of(
                "dependent-column-evidence-v1",
                List.of(),
                List.of(
                        TheoryKeys.type(this.exactColumn),
                        StructuralKey.branch("direct-parent-path-v1", path)));
    }

    public GraphType exactColumn() {
        return exactColumn;
    }

    /** Starts at {@link #exactColumn()} and follows direct parents upward. */
    public List<GraphType> ancestry() {
        return ancestry;
    }

    public int ancestorIndex(GraphType candidate) {
        return ancestry.indexOf(Objects.requireNonNull(candidate, "candidate"));
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    boolean hasParserModuleAuthority() {
        return parserAuthoritySource != null
                && parserAuthoritySource.hasParserAuthenticatedAncestry();
    }

    boolean sharesParserModuleAuthorityWith(DependentColumnEvidence other) {
        return other != null
                && parserAuthoritySource != null
                && parserAuthoritySource.sharesParserModuleAuthorityWith(
                        other.parserAuthoritySource);
    }

    boolean sameOccurrenceEvidenceAs(DependentColumnEvidence other) {
        if (other == null || !equals(other)) {
            return false;
        }
        if (parserAuthoritySource == null || other.parserAuthoritySource == null) {
            return parserAuthoritySource == other.parserAuthoritySource;
        }
        return parserAuthoritySource.sharesParserModuleAuthorityWith(
                other.parserAuthoritySource);
    }

    private static GraphType requireAtomicColumn(GraphType type, String role) {
        GraphType checked = Objects.requireNonNull(type, role);
        if (!isAdmittedAtomicColumn(checked)) {
            throw new IllegalArgumentException(
                    "A dependent " + role + " is not an atomic Alloy carrier: "
                            + checked);
        }
        return checked;
    }

    static boolean isAdmittedAtomicColumn(GraphType type) {
        if (type.kind() == GraphType.Kind.INT) {
            return true;
        }
        if (type.kind() != GraphType.Kind.CONSTRUCTOR
                || !type.arguments().isEmpty()
                || !type.symbol().startsWith("AlloySig:")) {
            return false;
        }
        String identity = type.symbol().substring("AlloySig:".length());
        return AlloyTypeBridge.isAdmittedIdentity(identity);
    }

    private static GraphType alloyColumn(String name) {
        String checked = Objects.requireNonNull(name, "signature name");
        if (!AlloyTypeBridge.isAdmittedIdentity(checked)) {
            throw new IllegalArgumentException(
                    "A dependent signature name must be a well-formed visible identity");
        }
        return AlloyTypeBridge.alloyColumn(checked);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DependentColumnEvidence
                && exactColumn.equals(
                        ((DependentColumnEvidence) other).exactColumn)
                && ancestry.equals(((DependentColumnEvidence) other).ancestry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exactColumn, ancestry);
    }

    @Override
    public String toString() {
        return structuralKey.stableString();
    }
}
