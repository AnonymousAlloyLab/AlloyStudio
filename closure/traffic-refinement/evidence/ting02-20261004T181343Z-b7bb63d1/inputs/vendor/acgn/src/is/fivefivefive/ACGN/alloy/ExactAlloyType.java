package is.fivefivefive.ACGN.alloy;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import edu.mit.csail.sdg.ast.Sig.PrimSig;
import edu.mit.csail.sdg.ast.Sig;
import edu.mit.csail.sdg.ast.Type;
import edu.mit.csail.sdg.alloy4.Pos;
import edu.mit.csail.sdg.parser.CompModule;

/** Serializable occurrence-level image of Alloy's exact expression type. */
public final class ExactAlloyType implements Serializable {
    private static final long serialVersionUID = 3L;

    public enum Kind {
        BOOL,
        INT,
        RELATION,
        EMPTY_RELATION,
        UNKNOWN
    }

    private final Kind kind;
    private final List<List<String>> alternatives;
    private final List<List<List<String>>> ancestryAlternatives;
    private final int relationArity;
    private final String stableString;
    private final transient CompModule parserModuleAuthority;

    private ExactAlloyType(
            Kind kind,
            List<? extends List<String>> alternatives,
            int relationArity) {
        this(kind, alternatives, singletonAncestries(alternatives), relationArity, null);
    }

    private ExactAlloyType(
            Kind kind,
            List<? extends List<String>> alternatives,
            List<? extends List<? extends List<String>>> ancestryAlternatives,
            int relationArity) {
        this(kind, alternatives, ancestryAlternatives, relationArity, null);
    }

    private ExactAlloyType(
            Kind kind,
            List<? extends List<String>> alternatives,
            List<? extends List<? extends List<String>>> ancestryAlternatives,
            int relationArity,
            CompModule parserModuleAuthority) {
        this.kind = Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(alternatives, "alternatives");
        Objects.requireNonNull(ancestryAlternatives, "ancestryAlternatives");
        if (alternatives.size() != ancestryAlternatives.size()) {
            throw new IllegalArgumentException(
                    "Alloy type alternatives and ancestry proofs differ in arity");
        }
        List<AlternativeShape> shapes = new ArrayList<>();
        for (int alternativeIndex = 0;
                alternativeIndex < alternatives.size();
                alternativeIndex++) {
            List<String> alternative = alternatives.get(alternativeIndex);
            List<String> columns = new ArrayList<>();
            for (String column : alternative) {
                columns.add(normalizeColumn(column));
            }
            List<? extends List<String>> ancestry = ancestryAlternatives.get(
                    alternativeIndex);
            if (ancestry.size() != columns.size()) {
                throw new IllegalArgumentException(
                        "Every Alloy relation column requires one ancestry proof");
            }
            List<List<String>> copiedAncestry = new ArrayList<>();
            for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                copiedAncestry.add(copyAncestry(
                        columns.get(columnIndex), ancestry.get(columnIndex)));
            }
            shapes.add(new AlternativeShape(
                    Collections.unmodifiableList(columns),
                    Collections.unmodifiableList(copiedAncestry)));
        }
        shapes.sort(Comparator.comparing(shape -> tupleKey(shape.columns)));
        validateAncestryLedger(shapes);
        List<List<String>> copied = new ArrayList<>(shapes.size());
        List<List<List<String>>> copiedAncestries = new ArrayList<>(shapes.size());
        for (AlternativeShape shape : shapes) {
            copied.add(shape.columns);
            copiedAncestries.add(shape.ancestries);
        }
        this.alternatives = Collections.unmodifiableList(copied);
        this.ancestryAlternatives = Collections.unmodifiableList(copiedAncestries);
        this.relationArity = relationArity;
        this.parserModuleAuthority = parserModuleAuthority;
        validateShape();
        this.stableString = buildStableString();
    }

    public static ExactAlloyType from(Type type) {
        return from(type, Collections.emptySet(), null);
    }

    /** Convert a type while binding nontrivial ancestry to one parsed module. */
    public static ExactAlloyType fromParser(Type type, CompModule sourceModule) {
        CompModule checkedModule = Objects.requireNonNull(
                sourceModule, "sourceModule");
        return from(type, parserSignatures(checkedModule), checkedModule);
    }

    private static ExactAlloyType from(
            Type type,
            java.util.Set<PrimSig> parserSignatures,
            CompModule sourceModule) {
        if (type == null) {
            return unknownType();
        }
        if (type.is_bool) {
            return boolType();
        }
        if (isExactlyUnaryInt(type)) {
            return sourceModule == null
                    ? intType() : parserIntType(sourceModule);
        }
        Map<String, AlternativeShape> tuples = new LinkedHashMap<>();
        java.util.Set<Integer> emptyArities = new java.util.TreeSet<>();
        boolean parserAuthenticatedAncestry = !parserSignatures.isEmpty();
        for (Type.ProductType product : type) {
            if (product.isEmpty()) {
                if (product.arity() > 0) {
                    emptyArities.add(product.arity());
                }
                continue;
            }
            List<String> columns = new ArrayList<>(product.arity());
            List<List<String>> ancestries = new ArrayList<>(product.arity());
            for (int index = 0; index < product.arity(); index++) {
                PrimSig column = product.get(index);
                columns.add(normalizeColumn(column.label));
                ancestries.add(ancestryOf(column));
                parserAuthenticatedAncestry &= hasParserAuthenticatedParents(
                        column, parserSignatures);
            }
            AlternativeShape shape = new AlternativeShape(
                    Collections.unmodifiableList(columns),
                    Collections.unmodifiableList(ancestries));
            AlternativeShape previous = tuples.putIfAbsent(tupleKey(columns), shape);
            if (previous != null && !previous.equals(shape)) {
                throw new IllegalStateException(
                        "One Alloy type tuple has conflicting primitive-signature ancestry");
            }
        }
        if (tuples.isEmpty()) {
            return type.hasNoTuple() && emptyArities.size() == 1
                    ? (sourceModule == null
                            ? emptyRelation(emptyArities.iterator().next())
                            : parserEmptyRelation(
                                    emptyArities.iterator().next(), sourceModule))
                    : unknownType();
        }
        int arity = tuples.values().iterator().next().columns.size();
        for (AlternativeShape tuple : tuples.values()) {
            if (tuple.columns.size() != arity) {
                return unknownType();
            }
        }
        List<List<String>> alternatives = new ArrayList<>();
        List<List<List<String>>> ancestry = new ArrayList<>();
        for (AlternativeShape tuple : tuples.values()) {
            alternatives.add(tuple.columns);
            ancestry.add(tuple.ancestries);
        }
        return new ExactAlloyType(
                Kind.RELATION,
                alternatives,
                ancestry,
                arity,
                parserAuthenticatedAncestry ? sourceModule : null);
    }

    private static boolean isExactlyUnaryInt(Type type) {
        if (!type.is_int() || type.is_bool) {
            return false;
        }
        boolean sawInt = false;
        for (Type.ProductType product : type) {
            if (product.isEmpty()) {
                continue;
            }
            if (product.arity() != 1 || product.get(0) != Sig.SIGINT || sawInt) {
                return false;
            }
            sawInt = true;
        }
        return sawInt;
    }

    public static ExactAlloyType boolType() {
        return new ExactAlloyType(Kind.BOOL, Collections.emptyList(), -1);
    }

    public static ExactAlloyType intType() {
        return new ExactAlloyType(Kind.INT, Collections.emptyList(), -1);
    }

    private static ExactAlloyType parserIntType(CompModule sourceModule) {
        return new ExactAlloyType(
                Kind.INT,
                Collections.emptyList(),
                Collections.emptyList(),
                -1,
                Objects.requireNonNull(sourceModule, "sourceModule"));
    }

    public static ExactAlloyType unaryRelation(String column) {
        return relation(Collections.singletonList(column));
    }

    public static ExactAlloyType relation(List<String> columns) {
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException(
                    "An exact Alloy relation type requires at least one column");
        }
        return new ExactAlloyType(
                Kind.RELATION,
                Collections.singletonList(columns),
                columns.size());
    }

    /**
     * Derives the exact Cartesian-product type of parser-authenticated relation
     * factors without inventing a column or ancestry edge.
     */
    public static ExactAlloyType parserCertifiedCartesianProduct(
            List<ExactAlloyType> factors) {
        Objects.requireNonNull(factors, "factors");
        if (factors.size() < 2) {
            throw new IllegalArgumentException(
                    "A Cartesian product requires at least two relation factors");
        }
        CompModule module = null;
        List<AlternativeShape> products = new ArrayList<>();
        products.add(new AlternativeShape(
                Collections.emptyList(), Collections.emptyList()));
        int arity = 0;
        boolean sawEmpty = false;
        for (ExactAlloyType factor : factors) {
            if (factor == null
                    || (factor.kind != Kind.RELATION
                            && factor.kind != Kind.INT
                            && factor.kind != Kind.EMPTY_RELATION)
                    || factor.parserModuleAuthority == null) {
                throw new IllegalArgumentException(
                        "Every Cartesian factor requires parser-authenticated set evidence");
            }
            if (module == null) {
                module = factor.parserModuleAuthority;
            } else if (module != factor.parserModuleAuthority) {
                throw new IllegalArgumentException(
                        "Cartesian factors must come from one parser module");
            }
            int factorArity = factor.kind == Kind.INT
                    ? 1 : factor.relationArity;
            arity += factorArity;
            if (factor.kind == Kind.EMPTY_RELATION) {
                sawEmpty = true;
                products = Collections.emptyList();
                continue;
            }
            List<AlternativeShape> factorShapes = new ArrayList<>();
            if (factor.kind == Kind.INT) {
                factorShapes.add(new AlternativeShape(
                        Collections.singletonList(
                                normalizeColumn(Sig.SIGINT.label)),
                        Collections.singletonList(ancestryOf(Sig.SIGINT))));
            } else {
                for (int alternative = 0;
                        alternative < factor.alternatives.size();
                        alternative++) {
                    factorShapes.add(new AlternativeShape(
                            factor.alternatives.get(alternative),
                            factor.ancestryAlternatives.get(alternative)));
                }
            }
            List<AlternativeShape> expanded = new ArrayList<>();
            for (AlternativeShape prefix : products) {
                for (AlternativeShape factorShape : factorShapes) {
                    List<String> columns = new ArrayList<>(prefix.columns);
                    columns.addAll(factorShape.columns);
                    List<List<String>> ancestries =
                            new ArrayList<>(prefix.ancestries);
                    ancestries.addAll(factorShape.ancestries);
                    expanded.add(new AlternativeShape(
                            Collections.unmodifiableList(columns),
                            Collections.unmodifiableList(ancestries)));
                }
            }
            products = expanded;
        }
        if (sawEmpty) {
            return parserEmptyRelation(
                    arity, Objects.requireNonNull(module, "Cartesian module"));
        }
        Map<String, AlternativeShape> unique = new LinkedHashMap<>();
        for (AlternativeShape product : products) {
            AlternativeShape previous = unique.putIfAbsent(
                    tupleKey(product.columns), product);
            if (previous != null && !previous.equals(product)) {
                throw new IllegalStateException(
                        "A Cartesian product derived conflicting ancestry evidence");
            }
        }
        List<List<String>> alternatives = new ArrayList<>();
        List<List<List<String>>> ancestries = new ArrayList<>();
        for (AlternativeShape product : unique.values()) {
            alternatives.add(product.columns);
            ancestries.add(product.ancestries);
        }
        return new ExactAlloyType(
                Kind.RELATION, alternatives, ancestries, arity, module);
    }

    /**
     * Derives the exact same-arity relation family of a parser-authenticated
     * relational union. The operands themselves remain in the term; this
     * method supplies only their correlated static type proof.
     */
    public static ExactAlloyType parserCertifiedRelationUnion(
            List<ExactAlloyType> operands) {
        Objects.requireNonNull(operands, "operands");
        if (operands.size() < 2) {
            throw new IllegalArgumentException(
                    "A derived relation union requires at least two operands");
        }
        CompModule module = null;
        Integer arity = null;
        boolean allInt = true;
        boolean sawNonempty = false;
        Map<String, AlternativeShape> unique = new LinkedHashMap<>();
        for (ExactAlloyType operand : operands) {
            if (operand == null
                    || (operand.kind != Kind.RELATION
                            && operand.kind != Kind.INT
                            && operand.kind != Kind.EMPTY_RELATION)
                    || operand.parserModuleAuthority == null) {
                throw new IllegalArgumentException(
                        "Every relation-union operand requires parser-authenticated set evidence");
            }
            if (module == null) {
                module = operand.parserModuleAuthority;
            } else if (module != operand.parserModuleAuthority) {
                throw new IllegalArgumentException(
                        "Relation-union operands must come from one parser module");
            }
            int operandArity = operand.kind == Kind.INT
                    ? 1 : operand.relationArity;
            if (arity == null) {
                arity = operandArity;
            } else if (arity.intValue() != operandArity) {
                throw new IllegalArgumentException(
                        "Relation-union operands must have one exact arity");
            }
            if (operand.kind == Kind.INT) {
                sawNonempty = true;
                AlternativeShape integer = new AlternativeShape(
                        Collections.singletonList(
                                normalizeColumn(Sig.SIGINT.label)),
                        Collections.singletonList(ancestryOf(Sig.SIGINT)));
                unique.putIfAbsent(tupleKey(integer.columns), integer);
                continue;
            }
            if (operand.kind == Kind.EMPTY_RELATION) {
                continue;
            }
            sawNonempty = true;
            allInt = false;
            for (int alternative = 0;
                    alternative < operand.alternatives.size();
                    alternative++) {
                AlternativeShape shape = new AlternativeShape(
                        operand.alternatives.get(alternative),
                        operand.ancestryAlternatives.get(alternative));
                AlternativeShape previous = unique.putIfAbsent(
                        tupleKey(shape.columns), shape);
                if (previous != null && !previous.equals(shape)) {
                    throw new IllegalStateException(
                            "A relation union derived conflicting ancestry evidence");
                }
            }
        }
        if (!sawNonempty) {
            return parserEmptyRelation(
                    Objects.requireNonNull(arity, "relation-union arity"),
                    module);
        }
        if (allInt) {
            return parserIntType(module);
        }
        List<List<String>> alternatives = new ArrayList<>(unique.size());
        List<List<List<String>>> ancestries = new ArrayList<>(unique.size());
        for (AlternativeShape shape : unique.values()) {
            alternatives.add(shape.columns);
            ancestries.add(shape.ancestries);
        }
        return new ExactAlloyType(
                Kind.RELATION,
                alternatives,
                ancestries,
                Objects.requireNonNull(arity, "relation-union arity"),
                module);
    }

    /**
     * Derives the exact same-arity overlap of parser-authenticated relation
     * families. Each retained product alternative is justified column-wise by
     * one operand's concrete signature being on the other's recorded ancestry
     * path; unrelated products contribute no tuples.
     */
    public static ExactAlloyType parserCertifiedRelationIntersection(
            List<ExactAlloyType> operands) {
        Objects.requireNonNull(operands, "operands");
        if (operands.size() < 2) {
            throw new IllegalArgumentException(
                    "A derived relation intersection requires at least two operands");
        }
        CompModule module = null;
        Integer arity = null;
        List<AlternativeShape> overlap = null;
        for (ExactAlloyType operand : operands) {
            if (operand == null
                    || (operand.kind != Kind.RELATION
                            && operand.kind != Kind.INT
                            && operand.kind != Kind.EMPTY_RELATION)
                    || operand.parserModuleAuthority == null) {
                throw new IllegalArgumentException(
                        "Every relation-intersection operand requires parser-authenticated set evidence");
            }
            if (module == null) {
                module = operand.parserModuleAuthority;
            } else if (module != operand.parserModuleAuthority) {
                throw new IllegalArgumentException(
                        "Relation-intersection operands must come from one parser module");
            }
            int operandArity = operand.kind == Kind.INT
                    ? 1 : operand.relationArity;
            if (arity == null) {
                arity = operandArity;
            } else if (arity.intValue() != operandArity) {
                throw new IllegalArgumentException(
                        "Relation-intersection operands must have one exact arity");
            }
            List<AlternativeShape> shapes = relationShapes(operand);
            if (overlap == null) {
                overlap = shapes;
            } else {
                overlap = intersectShapes(overlap, shapes);
            }
        }
        if (overlap == null || overlap.isEmpty()) {
            return parserEmptyRelation(
                    Objects.requireNonNull(
                            arity, "relation-intersection arity"),
                    module);
        }
        if (overlap.size() == 1 && isIntegerShape(overlap.get(0))) {
            return parserIntType(module);
        }
        List<List<String>> alternatives = new ArrayList<>(overlap.size());
        List<List<List<String>>> ancestries = new ArrayList<>(overlap.size());
        for (AlternativeShape shape : overlap) {
            alternatives.add(shape.columns);
            ancestries.add(shape.ancestries);
        }
        return new ExactAlloyType(
                Kind.RELATION,
                alternatives,
                ancestries,
                Objects.requireNonNull(arity, "relation-intersection arity"),
                module);
    }

    /**
     * Derives the static family of a parser-authenticated relational
     * difference. Alloy difference retains the left operand's static family;
     * the right operand contributes only the same-arity compatibility proof.
     */
    public static ExactAlloyType parserCertifiedRelationDifference(
            ExactAlloyType left,
            ExactAlloyType right) {
        ExactAlloyType checkedLeft = Objects.requireNonNull(
                left, "left difference type");
        ExactAlloyType checkedRight = Objects.requireNonNull(
                right, "right difference type");
        if (!isSetFamily(checkedLeft.kind)
                || !isSetFamily(checkedRight.kind)
                || !checkedLeft.sharesParserModuleAuthorityWith(checkedRight)) {
            throw new IllegalArgumentException(
                    "Relational difference requires parser-authenticated operands from one module");
        }
        if (setFamilyArity(checkedLeft) != setFamilyArity(checkedRight)) {
            throw new IllegalArgumentException(
                    "Relational-difference operands must have one exact arity");
        }
        return checkedLeft;
    }

    /** Exact parser-backed type of Alloy domain restriction {@code S <: R}. */
    public static ExactAlloyType parserCertifiedDomainRestriction(
            ExactAlloyType restrictor,
            ExactAlloyType relation) {
        return parserCertifiedRestriction(restrictor, relation, true);
    }

    /** Exact parser-backed type of Alloy range restriction {@code R :> S}. */
    public static ExactAlloyType parserCertifiedRangeRestriction(
            ExactAlloyType relation,
            ExactAlloyType restrictor) {
        return parserCertifiedRestriction(restrictor, relation, false);
    }

    private static ExactAlloyType parserCertifiedRestriction(
            ExactAlloyType restrictor,
            ExactAlloyType relation,
            boolean firstColumn) {
        ExactAlloyType checkedRestrictor = Objects.requireNonNull(
                restrictor, "restriction set type");
        ExactAlloyType checkedRelation = Objects.requireNonNull(
                relation, "restricted relation type");
        if (!isSetFamily(checkedRestrictor.kind)
                || !isSetFamily(checkedRelation.kind)
                || !checkedRestrictor.sharesParserModuleAuthorityWith(
                        checkedRelation)) {
            throw new IllegalArgumentException(
                    "Relational restriction requires parser-authenticated operands from one module");
        }
        if (setFamilyArity(checkedRestrictor) != 1) {
            throw new IllegalArgumentException(
                    "An Alloy restriction set must be unary");
        }
        int relationArity = setFamilyArity(checkedRelation);
        int restrictedColumn = firstColumn ? 0 : relationArity - 1;
        List<AlternativeShape> restrictorShapes = relationShapes(
                checkedRestrictor);
        List<AlternativeShape> relationAlternatives = relationShapes(
                checkedRelation);
        if (restrictorShapes.isEmpty() || relationAlternatives.isEmpty()) {
            return parserEmptyRelation(
                    relationArity, checkedRelation.parserModuleAuthority);
        }
        Map<String, AlternativeShape> unique = new LinkedHashMap<>();
        for (AlternativeShape relationShape : relationAlternatives) {
            for (AlternativeShape restrictorShape : restrictorShapes) {
                AlternativeShape columnOverlap = intersectShape(
                        new AlternativeShape(
                                Collections.singletonList(
                                        relationShape.columns.get(restrictedColumn)),
                                Collections.singletonList(
                                        relationShape.ancestries.get(restrictedColumn))),
                        restrictorShape);
                if (columnOverlap == null) {
                    continue;
                }
                List<String> columns = new ArrayList<>(relationShape.columns);
                List<List<String>> ancestries = new ArrayList<>(
                        relationShape.ancestries);
                columns.set(restrictedColumn, columnOverlap.columns.get(0));
                ancestries.set(
                        restrictedColumn, columnOverlap.ancestries.get(0));
                AlternativeShape restricted = new AlternativeShape(
                        Collections.unmodifiableList(columns),
                        Collections.unmodifiableList(ancestries));
                AlternativeShape previous = unique.putIfAbsent(
                        tupleKey(restricted.columns), restricted);
                if (previous != null && !previous.equals(restricted)) {
                    throw new IllegalStateException(
                            "A relational restriction derived conflicting ancestry evidence");
                }
            }
        }
        if (unique.isEmpty()) {
            return parserEmptyRelation(
                    relationArity, checkedRelation.parserModuleAuthority);
        }
        if (relationArity == 1 && unique.size() == 1
                && isIntegerShape(unique.values().iterator().next())) {
            return parserIntType(checkedRelation.parserModuleAuthority);
        }
        List<List<String>> alternatives = new ArrayList<>(unique.size());
        List<List<List<String>>> ancestries = new ArrayList<>(unique.size());
        for (AlternativeShape shape : unique.values()) {
            alternatives.add(shape.columns);
            ancestries.add(shape.ancestries);
        }
        return new ExactAlloyType(
                Kind.RELATION,
                alternatives,
                ancestries,
                relationArity,
                checkedRelation.parserModuleAuthority);
    }

    /**
     * Derives the correlated result family of one parser-authenticated Alloy
     * relational join. Every admitted boundary pair must overlap through a
     * recorded primitive-signature ancestry path; disjoint alternatives simply
     * contribute no result tuples.
     */
    public static ExactAlloyType parserCertifiedRelationalJoin(
            ExactAlloyType left,
            ExactAlloyType right) {
        ExactAlloyType checkedLeft = Objects.requireNonNull(left, "left join type");
        ExactAlloyType checkedRight = Objects.requireNonNull(right, "right join type");
        if (!isSetFamily(checkedLeft.kind)
                || !isSetFamily(checkedRight.kind)
                || !checkedLeft.sharesParserModuleAuthorityWith(checkedRight)) {
            throw new IllegalArgumentException(
                    "Relational join requires parser-authenticated operands from one module");
        }
        int resultArity = setFamilyArity(checkedLeft)
                + setFamilyArity(checkedRight) - 2;
        if (resultArity <= 0) {
            throw new IllegalArgumentException(
                    "This exact relation representation requires positive join arity");
        }
        Map<String, AlternativeShape> unique = new LinkedHashMap<>();
        for (AlternativeShape leftShape : relationShapes(checkedLeft)) {
            for (AlternativeShape rightShape : relationShapes(checkedRight)) {
                int leftBoundary = leftShape.columns.size() - 1;
                String leftColumn = leftShape.columns.get(leftBoundary);
                String rightColumn = rightShape.columns.get(0);
                if (!leftShape.ancestries.get(leftBoundary).contains(rightColumn)
                        && !rightShape.ancestries.get(0).contains(leftColumn)) {
                    continue;
                }
                List<String> columns = new ArrayList<>(resultArity);
                List<List<String>> ancestries = new ArrayList<>(resultArity);
                columns.addAll(leftShape.columns.subList(0, leftBoundary));
                ancestries.addAll(leftShape.ancestries.subList(0, leftBoundary));
                columns.addAll(rightShape.columns.subList(
                        1, rightShape.columns.size()));
                ancestries.addAll(rightShape.ancestries.subList(
                        1, rightShape.ancestries.size()));
                AlternativeShape joined = new AlternativeShape(
                        Collections.unmodifiableList(columns),
                        Collections.unmodifiableList(ancestries));
                AlternativeShape previous = unique.putIfAbsent(
                        tupleKey(joined.columns), joined);
                if (previous != null && !previous.equals(joined)) {
                    throw new IllegalStateException(
                            "A relational join derived conflicting ancestry evidence");
                }
            }
        }
        CompModule module = checkedLeft.parserModuleAuthority;
        if (unique.isEmpty()) {
            return parserEmptyRelation(resultArity, module);
        }
        List<List<String>> alternatives = new ArrayList<>(unique.size());
        List<List<List<String>>> ancestries = new ArrayList<>(unique.size());
        for (AlternativeShape shape : unique.values()) {
            alternatives.add(shape.columns);
            ancestries.add(shape.ancestries);
        }
        if (resultArity == 1 && unique.size() == 1
                && isIntegerShape(unique.values().iterator().next())) {
            return parserIntType(module);
        }
        return new ExactAlloyType(
                Kind.RELATION,
                alternatives,
                ancestries,
                resultArity,
                module);
    }

    private static List<AlternativeShape> relationShapes(
            ExactAlloyType operand) {
        if (operand.kind == Kind.EMPTY_RELATION) {
            return Collections.emptyList();
        }
        if (operand.kind == Kind.INT) {
            return Collections.singletonList(new AlternativeShape(
                    Collections.singletonList(
                            normalizeColumn(Sig.SIGINT.label)),
                    Collections.singletonList(ancestryOf(Sig.SIGINT))));
        }
        List<AlternativeShape> shapes = new ArrayList<>(
                operand.alternatives.size());
        for (int alternative = 0;
                alternative < operand.alternatives.size();
                alternative++) {
            shapes.add(new AlternativeShape(
                    operand.alternatives.get(alternative),
                    operand.ancestryAlternatives.get(alternative)));
        }
        return shapes;
    }

    private static List<AlternativeShape> intersectShapes(
            List<AlternativeShape> left,
            List<AlternativeShape> right) {
        Map<String, AlternativeShape> unique = new LinkedHashMap<>();
        for (AlternativeShape leftShape : left) {
            for (AlternativeShape rightShape : right) {
                AlternativeShape intersection = intersectShape(
                        leftShape, rightShape);
                if (intersection == null) {
                    continue;
                }
                AlternativeShape previous = unique.putIfAbsent(
                        tupleKey(intersection.columns), intersection);
                if (previous != null && !previous.equals(intersection)) {
                    throw new IllegalStateException(
                            "A relation intersection derived conflicting ancestry evidence");
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static AlternativeShape intersectShape(
            AlternativeShape left,
            AlternativeShape right) {
        if (left.columns.size() != right.columns.size()) {
            throw new IllegalArgumentException(
                    "Relation-intersection alternatives must have one exact arity");
        }
        List<String> columns = new ArrayList<>(left.columns.size());
        List<List<String>> ancestries = new ArrayList<>(left.columns.size());
        for (int column = 0; column < left.columns.size(); column++) {
            String leftColumn = left.columns.get(column);
            String rightColumn = right.columns.get(column);
            List<String> leftAncestry = left.ancestries.get(column);
            List<String> rightAncestry = right.ancestries.get(column);
            if (leftAncestry.contains(rightColumn)) {
                columns.add(leftColumn);
                ancestries.add(leftAncestry);
            } else if (rightAncestry.contains(leftColumn)) {
                columns.add(rightColumn);
                ancestries.add(rightAncestry);
            } else {
                return null;
            }
        }
        return new AlternativeShape(
                Collections.unmodifiableList(columns),
                Collections.unmodifiableList(ancestries));
    }

    private static boolean isIntegerShape(AlternativeShape shape) {
        return shape.columns.equals(Collections.singletonList(
                normalizeColumn(Sig.SIGINT.label)));
    }

    /** Exact parser-authenticated type proof for relational converse. */
    public static ExactAlloyType parserCertifiedTranspose(
            ExactAlloyType operand) {
        ExactAlloyType checked = Objects.requireNonNull(operand, "operand");
        if (checked.kind != Kind.RELATION
                || checked.relationArity != 2
                || checked.parserModuleAuthority == null) {
            throw new IllegalArgumentException(
                    "Relational converse requires one parser-authenticated binary relation");
        }
        List<List<String>> alternatives = new ArrayList<>(
                checked.alternatives.size());
        List<List<List<String>>> ancestries = new ArrayList<>(
                checked.ancestryAlternatives.size());
        for (int alternative = 0;
                alternative < checked.alternatives.size();
                alternative++) {
            List<String> columns = checked.alternatives.get(alternative);
            List<List<String>> paths = checked.ancestryAlternatives.get(
                    alternative);
            alternatives.add(List.of(columns.get(1), columns.get(0)));
            ancestries.add(List.of(paths.get(1), paths.get(0)));
        }
        return new ExactAlloyType(
                Kind.RELATION,
                alternatives,
                ancestries,
                2,
                checked.parserModuleAuthority);
    }

    public static ExactAlloyType emptyRelation(int arity) {
        if (arity <= 0) {
            throw new IllegalArgumentException(
                    "An empty Alloy relation requires positive arity");
        }
        return new ExactAlloyType(
                Kind.EMPTY_RELATION, Collections.emptyList(), arity);
    }

    private static ExactAlloyType parserEmptyRelation(
            int arity,
            CompModule sourceModule) {
        if (arity <= 0) {
            throw new IllegalArgumentException(
                    "An empty Alloy relation requires positive arity");
        }
        return new ExactAlloyType(
                Kind.EMPTY_RELATION,
                Collections.emptyList(),
                Collections.emptyList(),
                arity,
                Objects.requireNonNull(sourceModule, "sourceModule"));
    }

    public Kind kind() {
        return kind;
    }

    public List<List<String>> alternatives() {
        return alternatives;
    }

    /** Per-alternative, per-column paths from the exact PrimSig to its ancestors. */
    public List<List<List<String>>> ancestryAlternatives() {
        return ancestryAlternatives;
    }

    /** True only for module-owned, parser-positioned parent evidence; never survives serialization. */
    public boolean hasParserAuthenticatedAncestry() {
        return parserModuleAuthority != null;
    }

    /**
     * Runtime-only proof that two exact types came from the identical parser
     * module object. This capability is deliberately absent from value
     * equality, stable keys, and serialized state.
     */
    public boolean sharesParserModuleAuthorityWith(ExactAlloyType other) {
        return other != null
                && parserModuleAuthority != null
                && parserModuleAuthority == other.parserModuleAuthority;
    }

    /** Runtime-only check used to bind occurrence evidence to its parser module. */
    public boolean isParserAuthenticatedBy(CompModule sourceModule) {
        return sourceModule != null && parserModuleAuthority == sourceModule;
    }

    /**
     * Runtime proof that every correlated relation product in this occurrence
     * is included column-wise in one product of {@code carrier}. The proof is
     * available only when both occurrences came from the identical parser
     * module and each subtype edge remains in the parser-authenticated ancestry
     * ledger.
     */
    public boolean isParserCertifiedRelationSubfamilyOf(
            ExactAlloyType carrier) {
        if (carrier == null
                || kind != Kind.RELATION
                || carrier.kind != Kind.RELATION
                || relationArity != carrier.relationArity
                || !sharesParserModuleAuthorityWith(carrier)) {
            return false;
        }
        for (int candidateIndex = 0;
                candidateIndex < alternatives.size();
                candidateIndex++) {
            List<List<String>> candidateAncestry = ancestryAlternatives.get(
                    candidateIndex);
            boolean covered = false;
            for (List<String> carrierAlternative : carrier.alternatives) {
                boolean columnsCovered = true;
                for (int columnIndex = 0;
                        columnIndex < relationArity;
                        columnIndex++) {
                    if (!candidateAncestry.get(columnIndex).contains(
                            carrierAlternative.get(columnIndex))) {
                        columnsCovered = false;
                        break;
                    }
                }
                if (columnsCovered) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return false;
            }
        }
        return true;
    }

    /**
     * Runtime proof that this parser-authenticated set-valued occurrence is a
     * subfamily of {@code carrier}. Unlike the older relation-only query, this
     * admits the parser's exact Int and empty-relation images as set families.
     * It still requires one live parser module and correlated, column-wise
     * ancestry for every nonempty alternative.
     */
    public boolean isParserCertifiedSetSubfamilyOf(
            ExactAlloyType carrier) {
        if (carrier == null
                || !isSetFamily(kind)
                || !isSetFamily(carrier.kind)
                || setFamilyArity(this) != setFamilyArity(carrier)
                || !sharesParserModuleAuthorityWith(carrier)) {
            return false;
        }
        List<AlternativeShape> candidateShapes = relationShapes(this);
        List<AlternativeShape> carrierShapes = relationShapes(carrier);
        for (AlternativeShape candidate : candidateShapes) {
            boolean covered = false;
            for (AlternativeShape carrierShape : carrierShapes) {
                if (shapeIsSubfamilyOf(candidate, carrierShape)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSetFamily(Kind candidateKind) {
        return candidateKind == Kind.RELATION
                || candidateKind == Kind.EMPTY_RELATION
                || candidateKind == Kind.INT;
    }

    private static int setFamilyArity(ExactAlloyType type) {
        return type.kind == Kind.INT ? 1 : type.relationArity;
    }

    private static boolean shapeIsSubfamilyOf(
            AlternativeShape candidate,
            AlternativeShape carrier) {
        if (candidate.columns.size() != carrier.columns.size()) {
            return false;
        }
        for (int column = 0; column < candidate.columns.size(); column++) {
            if (!candidate.ancestries.get(column).contains(
                    carrier.columns.get(column))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Equality for one source occurrence. Value equality deliberately omits
     * runtime parser authority, but an occurrence store must not merge two
     * equal-looking types whose ancestry came from different parser modules.
     */
    public boolean sameOccurrenceEvidenceAs(ExactAlloyType other) {
        return other != null
                && equals(other)
                && ancestryAlternatives.equals(other.ancestryAlternatives)
                && parserModuleAuthority == other.parserModuleAuthority;
    }

    public int relationArity() {
        if (kind != Kind.RELATION && kind != Kind.EMPTY_RELATION) {
            throw new IllegalStateException(
                    "A non-relation Alloy type has no relation arity");
        }
        return relationArity;
    }

    public String stableString() {
        return stableString;
    }

    private String buildStableString() {
        StringBuilder result = new StringBuilder(kind.name());
        if (kind == Kind.EMPTY_RELATION) {
            result.append("[arity=").append(relationArity).append(']');
        }
        for (List<String> tuple : alternatives) {
            result.append('[');
            for (String column : tuple) {
                result.append(column.length()).append(':').append(column);
            }
            result.append(']');
        }
        return result.toString();
    }

    private void validateShape() {
        if (kind == Kind.RELATION) {
            if (relationArity <= 0 || alternatives.isEmpty()) {
                throw new IllegalArgumentException(
                        "An exact relation requires alternatives and positive arity");
            }
            for (List<String> alternative : alternatives) {
                if (alternative.size() != relationArity) {
                    throw new IllegalArgumentException(
                            "Relation alternatives must have one exact arity");
                }
            }
            if (ancestryAlternatives.size() != alternatives.size()) {
                throw new IllegalArgumentException(
                        "Relation alternatives and ancestry proofs differ in arity");
            }
            for (int alternativeIndex = 0;
                    alternativeIndex < alternatives.size();
                    alternativeIndex++) {
                List<String> alternative = alternatives.get(alternativeIndex);
                List<List<String>> ancestry = ancestryAlternatives.get(alternativeIndex);
                if (ancestry.size() != alternative.size()) {
                    throw new IllegalArgumentException(
                            "Every relation column requires one ancestry proof");
                }
                for (int columnIndex = 0;
                        columnIndex < alternative.size();
                        columnIndex++) {
                    copyAncestry(
                            alternative.get(columnIndex), ancestry.get(columnIndex));
                }
            }
            return;
        }
        if (kind == Kind.EMPTY_RELATION) {
            if (relationArity <= 0 || !alternatives.isEmpty()) {
                throw new IllegalArgumentException(
                        "An empty relation requires only a positive arity");
            }
            return;
        }
        if (relationArity != -1 || !alternatives.isEmpty()
                || !ancestryAlternatives.isEmpty()) {
            throw new IllegalArgumentException(
                    kind + " must not carry relation shape");
        }
    }

    private static ExactAlloyType unknownType() {
        return new ExactAlloyType(Kind.UNKNOWN, Collections.emptyList(), -1);
    }

    private static String tupleKey(List<String> tuple) {
        StringBuilder result = new StringBuilder();
        for (String column : tuple) {
            result.append(column.length()).append(':').append(column);
        }
        return result.toString();
    }

    static String normalizeColumn(String value) {
        String checked = requireAdmittedIdentity(value, "column");
        String normalized = checked.startsWith("this/")
                ? checked.substring(5) : checked;
        if (normalized.isEmpty() || normalized.startsWith("this/")) {
            throw new IllegalArgumentException(
                    "An Alloy type column must have one canonical identity after its module prefix");
        }
        return normalized;
    }

    private static List<String> ancestryOf(PrimSig column) {
        List<String> result = new ArrayList<>();
        java.util.Set<PrimSig> seen = Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        PrimSig current = Objects.requireNonNull(column, "column");
        while (current != null) {
            if (!seen.add(current)) {
                throw new IllegalStateException(
                        "Alloy primitive-signature ancestry contains a cycle");
            }
            result.add(normalizeColumn(current.label));
            current = current.parent;
        }
        return Collections.unmodifiableList(result);
    }

    private static java.util.Set<PrimSig> parserSignatures(CompModule sourceModule) {
        java.util.Set<PrimSig> result = Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (Sig signature : sourceModule.getAllReachableSigs()) {
            if (!(signature instanceof PrimSig)) {
                continue;
            }
            PrimSig current = (PrimSig) signature;
            while (current != null && result.add(current)) {
                current = current.parent;
            }
        }
        result.add(Sig.UNIV);
        result.add(Sig.SIGINT);
        result.add(Sig.SEQIDX);
        result.add(Sig.STRING);
        result.add(Sig.NONE);
        return result;
    }

    private static boolean hasParserAuthenticatedParents(
            PrimSig column,
            java.util.Set<PrimSig> parserSignatures) {
        PrimSig current = Objects.requireNonNull(column, "column");
        if (!parserSignatures.contains(current)) {
            return false;
        }
        java.util.Set<PrimSig> seen = Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        while (current.parent != null && current.parent != Sig.UNIV) {
            if (!seen.add(current)) {
                throw new IllegalStateException(
                        "Alloy primitive-signature ancestry contains a cycle");
            }
            Pos declaration = current.isSubsig;
            if (!parserSignatures.contains(current.parent)
                    || (!isTrustedBuiltinParentEdge(current, current.parent)
                            && (declaration == null
                                    || Pos.UNKNOWN.equals(declaration)
                                    || declaration.filename == null
                                    || declaration.filename.isBlank()))) {
                return false;
            }
            current = current.parent;
        }
        return true;
    }

    private static boolean isTrustedBuiltinParentEdge(
            PrimSig child,
            PrimSig parent) {
        return child == Sig.SEQIDX && parent == Sig.SIGINT;
    }

    private static List<String> copyAncestry(
            String exactColumn,
            List<String> ancestry) {
        Objects.requireNonNull(ancestry, "ancestry");
        if (ancestry.isEmpty()) {
            throw new IllegalArgumentException(
                    "An Alloy relation column ancestry must not be empty");
        }
        List<String> copied = new ArrayList<>(ancestry.size());
        java.util.Set<String> seen = new LinkedHashSet<>();
        for (String ancestor : ancestry) {
            String checked = normalizeColumn(ancestor);
            if (!seen.add(checked)) {
                throw new IllegalArgumentException(
                        "An Alloy relation ancestry must be nonblank and acyclic");
            }
            copied.add(checked);
        }
        if (!exactColumn.equals(copied.get(0))) {
            throw new IllegalArgumentException(
                    "An Alloy relation ancestry must start at its exact column");
        }
        int univ = copied.indexOf("univ");
        if (univ >= 0 && univ + 1 != copied.size()) {
            throw new IllegalArgumentException(
                    "Alloy univ must terminate a relation ancestry");
        }
        return Collections.unmodifiableList(copied);
    }

    static String requireAdmittedIdentity(
            String value,
            String role) {
        String checked = Objects.requireNonNull(value, role);
        if (!isAdmittedIdentity(checked)) {
            throw new IllegalArgumentException(
                    "An Alloy type " + role + " must be a well-formed visible identity");
        }
        return checked;
    }

    public static boolean isAdmittedIdentity(String value) {
        return value != null
                && !value.isEmpty()
                && value.codePoints().noneMatch(
                        ExactAlloyType::isForbiddenIdentityCodePoint);
    }

    private static boolean isForbiddenIdentityCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || Character.isISOControl(codePoint)
                || type == Character.FORMAT
                || type == Character.SURROGATE
                || type == Character.PRIVATE_USE
                || type == Character.UNASSIGNED;
    }

    private static void validateAncestryLedger(List<AlternativeShape> shapes) {
        Map<String, String> directParents = new LinkedHashMap<>();
        for (AlternativeShape shape : shapes) {
            for (List<String> path : shape.ancestries) {
                for (int index = 1; index < path.size(); index++) {
                    String child = path.get(index - 1);
                    String parent = path.get(index);
                    String previous = directParents.putIfAbsent(child, parent);
                    if (previous != null && !previous.equals(parent)) {
                        throw new IllegalArgumentException(
                                "One Alloy signature has conflicting direct parents");
                    }
                }
            }
        }
        for (String start : directParents.keySet()) {
            java.util.Set<String> seen = new LinkedHashSet<>();
            String current = start;
            while (current != null) {
                if (!seen.add(current)) {
                    throw new IllegalArgumentException(
                            "Alloy relation ancestry contains a cross-path cycle");
                }
                current = directParents.get(current);
            }
        }
    }

    private static List<List<List<String>>> singletonAncestries(
            List<? extends List<String>> alternatives) {
        Objects.requireNonNull(alternatives, "alternatives");
        List<List<List<String>>> result = new ArrayList<>();
        for (List<String> alternative : alternatives) {
            List<List<String>> tuple = new ArrayList<>();
            for (String column : alternative) {
                tuple.add(Collections.singletonList(column));
            }
            result.add(tuple);
        }
        return result;
    }

    private void readObject(ObjectInputStream input)
            throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        try {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(alternatives, "alternatives");
            Objects.requireNonNull(ancestryAlternatives, "ancestryAlternatives");
            for (List<String> alternative : alternatives) {
                Objects.requireNonNull(alternative, "alternative");
                for (String column : alternative) {
                    requireAdmittedIdentity(column, "column");
                }
            }
            validateShape();
            if (!Objects.equals(stableString, buildStableString())) {
                throw new IllegalArgumentException(
                        "Serialized Alloy type stable key is inconsistent");
            }
        } catch (NullPointerException | IllegalArgumentException exception) {
            InvalidObjectException invalid = new InvalidObjectException(
                    "Invalid serialized exact Alloy type: " + exception.getMessage());
            invalid.initCause(exception);
            throw invalid;
        }
    }

    private Object readResolve() throws ObjectStreamException {
        try {
            return new ExactAlloyType(
                    kind, alternatives, ancestryAlternatives, relationArity, null);
        } catch (NullPointerException | IllegalArgumentException exception) {
            InvalidObjectException invalid = new InvalidObjectException(
                    "Invalid serialized exact Alloy type: " + exception.getMessage());
            invalid.initCause(exception);
            throw invalid;
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExactAlloyType
                && kind == ((ExactAlloyType) other).kind
                && relationArity == ((ExactAlloyType) other).relationArity
                && alternatives.equals(((ExactAlloyType) other).alternatives);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, alternatives, relationArity);
    }

    @Override
    public String toString() {
        return stableString;
    }

    private static final class AlternativeShape {
        private final List<String> columns;
        private final List<List<String>> ancestries;

        private AlternativeShape(
                List<String> columns,
                List<List<String>> ancestries) {
            this.columns = columns;
            this.ancestries = ancestries;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof AlternativeShape
                    && columns.equals(((AlternativeShape) other).columns)
                    && ancestries.equals(((AlternativeShape) other).ancestries);
        }

        @Override
        public int hashCode() {
            return Objects.hash(columns, ancestries);
        }
    }
}
