package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Closed family of ordered, heterogeneously typed associative Alloy chains. */
public enum DependentChainKind {
    JOIN,
    ARROW;

    public String operatorIdentity() {
        return "ALLOY/DEPENDENT-CHAIN/" + name();
    }

    public GraphType combine(GraphType left, GraphType right) {
        return DependentTypeDag.combine(
                this,
                DependentTypeDag.fromRelationFamilyType(
                        Objects.requireNonNull(left, "left")),
                DependentTypeDag.fromRelationFamilyType(
                        Objects.requireNonNull(right, "right")))
                .result().relationType();
    }

    public GraphType fold(List<GraphType> operands) {
        Objects.requireNonNull(operands, "operands");
        if (operands.size() < 2) {
            throw new IllegalArgumentException(
                    "A dependent chain requires at least two operands");
        }
        DependentChainTheory.requireSoundFlattening(this, operands);
        DependentTypeDag result = DependentTypeDag.fromRelationFamilyType(
                Objects.requireNonNull(operands.get(0), "operand type"));
        for (int index = 1; index < operands.size(); index++) {
            result = DependentTypeDag.combine(
                    this,
                    result,
                    DependentTypeDag.fromRelationFamilyType(
                            Objects.requireNonNull(
                                    operands.get(index), "operand type")))
                    .result();
        }
        return result.relationType();
    }

    List<DependentColumnEvidence> combineColumns(
            List<DependentColumnEvidence> leftColumns,
            List<DependentColumnEvidence> rightColumns) {
        requireColumns(leftColumns, "left");
        requireColumns(rightColumns, "right");
        requireCommonParserAuthority(leftColumns, rightColumns);
        DependentChainTheory.requireConsistentHierarchy(
                List.of(leftColumns, rightColumns));
        DependentTypeDag.ChainCombination combination = DependentTypeDag.combine(
                this,
                DependentTypeDag.exactAlternative(
                        typeOf(leftColumns), leftColumns),
                DependentTypeDag.exactAlternative(
                        typeOf(rightColumns), rightColumns));
        if (combination.result().alternatives().size() != 1) {
            throw new DependentChainTheory.UnsupportedFlattening(
                    "A scalar column view cannot represent a relation-family result");
        }
        return combination.result().alternatives().get(0);
    }

    List<DependentColumnEvidence> foldColumns(
            List<? extends List<DependentColumnEvidence>> operands) {
        Objects.requireNonNull(operands, "operands");
        if (operands.size() < 2) {
            throw new IllegalArgumentException(
                    "A dependent chain requires at least two operands");
        }
        List<GraphType> operandTypes = new ArrayList<>(operands.size());
        for (List<DependentColumnEvidence> operand : operands) {
            requireColumns(operand, "operand");
            operandTypes.add(typeOf(operand));
        }
        DependentChainTheory.requireSoundFlattening(this, operandTypes);
        requireCommonParserAuthority(operands);
        DependentChainTheory.requireConsistentHierarchy(operands);
        List<DependentColumnEvidence> result = List.copyOf(
                Objects.requireNonNull(operands.get(0), "operand columns"));
        requireColumns(result, "first");
        for (int index = 1; index < operands.size(); index++) {
            result = combineColumns(
                    result,
                    Objects.requireNonNull(
                            operands.get(index), "operand columns"));
        }
        return result;
    }

    static GraphType typeOf(List<DependentColumnEvidence> columns) {
        return GraphType.relation(columns.stream()
                .map(DependentColumnEvidence::exactColumn)
                .toList());
    }

    private static void requireColumns(
            List<DependentColumnEvidence> columns,
            String role) {
        Objects.requireNonNull(columns, role + " columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException(
                    "Dependent " + role + " operand has no relation columns");
        }
        for (DependentColumnEvidence column : columns) {
            Objects.requireNonNull(column, role + " column");
        }
    }

    private static void requireCommonParserAuthority(
            List<DependentColumnEvidence> leftColumns,
            List<DependentColumnEvidence> rightColumns) {
        requireCommonParserAuthority(List.of(leftColumns, rightColumns));
    }

    private static void requireCommonParserAuthority(
            List<? extends List<DependentColumnEvidence>> operands) {
        List<DependentColumnEvidence> all = new ArrayList<>();
        for (List<DependentColumnEvidence> operand : operands) {
            all.addAll(Objects.requireNonNull(operand, "operand columns"));
        }
        DependentColumnEvidence authority = all.stream()
                .filter(DependentColumnEvidence::hasParserModuleAuthority)
                .findFirst()
                .orElse(null);
        if (authority == null) {
            return;
        }
        for (DependentColumnEvidence column : all) {
            if (column.hasParserModuleAuthority()
                    && !authority.sharesParserModuleAuthorityWith(column)) {
                throw new IllegalArgumentException(
                        "Dependent chain columns carry conflicting live parser authorities");
            }
        }
    }
}
