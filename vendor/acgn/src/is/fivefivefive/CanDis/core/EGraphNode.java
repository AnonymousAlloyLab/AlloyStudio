package is.fivefivefive.CanDis.core;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import is.fivefivefive.CanDis.core.RenamedIdUnionFind.RenamedId;
import is.fivefivefive.CanDis.theory.ArityPolicy;
import is.fivefivefive.CanDis.theory.FlatLicense;
import is.fivefivefive.CanDis.theory.LeanVerifiedRewrite;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import is.fivefivefive.CanDis.theory.SiblingQuotient;
import is.fivefivefive.CanDis.theory.UnitLicense;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.alloy.ConstSymbol;
import is.fivefivefive.ACGN.alloy.SigSymbol;


/**
 * This class represents a node in the slotted e-graph of the matrix of the normal form.
 * The e-graph is a graph representation of the formula, where each node represents a subformula, and edges represent the structure of the formula.
 * For example, for a formula like "P(x) and Q(y)", the e-graph would have a node for "P(x)", a node for "Q(y)", and an edge between them representing the "and" operator. 
 * The e-graph shall be used to make the distance minimal, to capture the symmetry, associativity, commutativity, and other properties of the formula, 
 * which can help to make the distance calculation more accurate and efficient.
 * Operands are slotted e-class invocations rather than embedded subtrees. Each
 * invocation maps the slots exposed by the referenced e-class into the caller's
 * slots. The current implementation keeps canonical representatives for Fast Rewrite IR
 * consumers and retains rewrite alternatives in their shared e-class.
 */
public final class EGraphNode {
    private static final AtomicInteger NEXT_ECLASS_ID = new AtomicInteger();
    private static final AtomicLong NEXT_SOURCE_OCCURRENCE_LINEAGE =
            new AtomicLong(1L);
    private static final ThreadLocal<EGraphArena> CURRENT_ARENA = ThreadLocal.withInitial(EGraphArena::new);

    private int id;
    private Opcode opcode; // the semantic operator of this node with a corresponding opcode
    private List<EClassRef> childClasses;
    private final List<EGraphNode> childrenView;
    private EClass eClass;
    private final EGraphArena arena;
    private boolean isCommutative; // whether the operator of this node is commutative, which can help to capture the symmetry of the formula
    private int maxArity; // the maximum arity of this node, which is the maximum number of children this node can have, and it is determined by the operator of this node
    private boolean flexibleArity; // whether this node has flexible arity, which is determined by the operator of this node, e.g., "and" and "or" have flexible arity, while "implies" and "iff" have fixed arity of 2.
    private String sourceName;
    private String sourceType;
    private ExactAlloyType exactAlloyType;
    private String alphaName;
    private String semanticIdentity;
    private BuiltinConstantKind builtinConstantKind;
    private SigSymbol parserSignatureEvidence;
    /* Transfer-only identity: never participates in semantic keys or serialization. */
    private long sourceOccurrenceLineage;
    /*
     * Immutable checkpoint occurrence used to transfer pre-saturation evidence.
     * Unlike sourceOccurrenceLineage, adoption of an equivalent representative
     * must not replace this carrier identity.
     */
    private long certificationOccurrenceLineage;
    /* A bound relation value imported from an enclosing temporal phase. */
    private boolean temporalSnapshotBinding;
    /* Parser-owned CALL provenance; excluded from semantic keys and repair cost. */
    private long callOccurrenceId = -1L;
    private int declaredArity = -1;
    private String callArityAuthority;
    /* Internal derivation evidence; generic Bool metadata alone is not authority. */
    private Opcode derivedBooleanRewriteOpcode;
    /* Owner-issued temporal REF provenance; excluded from semantic keys. */
    private long temporalReferenceAuthorityId = -1L;
    private final SemanticProfile semanticProfile;
    private enum BuiltinConstantKind {
        NONE,
        UNIV,
        IDEN
    }
    public enum Metatype {
        ATOMIC, 
        SET, 
        BOOLEAN,
        CONTROL
    }
    public enum FlexibleArityKind {
        FIXED,
        SET,
        BAG,
        SEQUENCE
    }
    public enum Opcode {
        AND,
        OR,
        NOT,
        IMPLIES,
        IFF,
        PREDICATE,
        FUNCTION,
        VARIABLE,
        GLOBALBINDING,
        CONSTANT,
        TEMPORALROOT,
        ASSERTION,
        CHECK,
        RUN,
        FACT,
        LET,
        DUMMY,
        REF,
        SHADOW,
        END,

        // STRUCTURAL IR NODES
        ITE,
        CALL,
        LIST,
        DISJOINT,
        DISJOINT_LIST,
        TOTALORDER_LIST,
        COMPREHENSION,
        SUM,

        // TEMPORAL LEAVES
        RELEASES,
        SINCE,
        TRIGGERED,
        UNTIL,
        BEFORE,
        HISTORICALLY,
        ONCE,
        ALWAYS,
        EVENTUALLY,
        AFTER,

        // FORMULA OPERATORS
        EQUALS,
        NOT_EQUALS,
        GT,
        GTE,
        IN,
        LT,
        LTE,
        NOT_GT,
        NOT_GTE,
        NOT_IN,
        NOT_LT,
        NOT_LTE,
        SOME,
        NO,

        // EXPRESSION OPERATORS
        ARROW,
        ANY_ARROW_SOME,
        ANY_ARROW_ONE,
        ANY_ARROW_LONE,
        SOME_ARROW_ANY,
        SOME_ARROW_SOME,
        SOME_ARROW_ONE,
        SOME_ARROW_LONE,
        ONE_ARROW_ANY,
        ONE_ARROW_SOME,
        ONE_ARROW_ONE,
        ONE_ARROW_LONE,
        LONE_ARROW_ANY,
        LONE_ARROW_SOME,
        LONE_ARROW_ONE,
        LONE_ARROW_LONE,
        ISSEQ_ARROW_LONE,
        JOIN,
        DOMAIN,
        RANGE,
        INTERSECT,
        PLUSPLUS,
        PLUS,
        IPLUS,
        MINUS,
        IMINUS,
        MUL,
        DIV,
        REM,
        SHL,
        SHA,
        SHR,
        SETOF,
        EXACTLY,
        TRANSPOSE,
        RCLOSURE,
        CLOSURE,
        CARDINALITY,
        CAST2INT,
        CAST2SIGINT,
        PRIME,
        
        // QUANTIFIER IR NODES TO BE ELIMINATED
        FORALL,
        EXISTS,
        LONE,
        ONE,

        // DECLS IR NODES TO BE ELIMINATED
        GENERICRELDECL,
        DISJ,
        VAR,
        DISJVAR,
        
        // ... other operators can be added here
        MODULEDECL,
        OPEN,
        PARAMDECL,
        SIGDECL,
        FIELDDECL
    }
    private Metatype metatype; // the metatype of this node, which can be used to capture the type of the formula, e.g., atomic formula, set formula, boolean formula, etc.
    public EGraphNode(int id, Opcode opcode, List<EGraphNode> children, boolean isCommutative, int maxArity, boolean flexibleArity, Metatype metatype) {
        this(id, opcode, children, isCommutative, maxArity, flexibleArity, metatype,
                SemanticProfile.alloyOverflowForbidding(), true);
    }

    public EGraphNode(
            int id,
            Opcode opcode,
            List<EGraphNode> children,
            boolean isCommutative,
            int maxArity,
            boolean flexibleArity,
            Metatype metatype,
            SemanticProfile semanticProfile) {
        this(id, opcode, children, isCommutative, maxArity, flexibleArity, metatype,
                semanticProfile, true);
    }

    private EGraphNode(int id, Opcode opcode, List<EGraphNode> children, boolean isCommutative,
            int maxArity, boolean flexibleArity, Metatype metatype,
            SemanticProfile semanticProfile, boolean createEClass) {
        this(id, opcode, children, isCommutative, maxArity, flexibleArity,
                metatype, semanticProfile, createEClass, CURRENT_ARENA.get());
    }

    private EGraphNode(int id, Opcode opcode, List<EGraphNode> children, boolean isCommutative,
            int maxArity, boolean flexibleArity, Metatype metatype,
            SemanticProfile semanticProfile, boolean createEClass,
            EGraphArena arena) {
        this.id = id;
        this.opcode = opcode;
        this.arena = java.util.Objects.requireNonNull(arena, "e-graph arena");
        this.childClasses = new ArrayList<>();
        this.childrenView = new AbstractList<EGraphNode>() {
            @Override
            public EGraphNode get(int index) {
                requireLiveNode();
                return childClasses.get(index).getEClass().getRepresentative();
            }

            @Override
            public int size() {
                requireLiveNode();
                return childClasses.size();
            }
        };
        this.isCommutative = isCommutative;
        this.maxArity = maxArity;
        this.flexibleArity = flexibleArity;
        this.metatype = metatype;
        this.semanticProfile = java.util.Objects.requireNonNull(
                semanticProfile, "semanticProfile");
        this.sourceOccurrenceLineage = nextSourceOccurrenceLineage();
        this.certificationOccurrenceLineage = sourceOccurrenceLineage;
        if (children != null) {
            for (EGraphNode child : children) {
                appendChildReference(child);
            }
        }
        requireExtensibleChildCount();
        if (createEClass) {
            this.eClass = new EClass(NEXT_ECLASS_ID.getAndIncrement(), this);
        }
    }

    static EGraphNode inOwningArena(
            EGraphNode owner,
            int id,
            Opcode opcode,
            List<EGraphNode> children,
            boolean isCommutative,
            int maxArity,
            boolean flexibleArity,
            Metatype metatype,
            SemanticProfile semanticProfile) {
        EGraphNode checkedOwner = Objects.requireNonNull(owner, "arena owner");
        return checkedOwner.arena.createDerivedNode(
                checkedOwner,
                id,
                opcode,
                children,
                isCommutative,
                maxArity,
                flexibleArity,
                metatype,
                semanticProfile);
    }

    /** Constructs a reserved Alloy set constant without a mutable identity setter. */
    public static EGraphNode builtinSetConstant(
            int id,
            SigSymbol symbol,
            ExactAlloyType exactAlloyType,
            SemanticProfile semanticProfile) {
        SigSymbol checked = Objects.requireNonNull(symbol, "built-in set symbol");
        BuiltinConstantKind kind;
        switch (checked.getKind()) {
            case BUILTIN_NONE:
                kind = BuiltinConstantKind.NONE;
                break;
            case BUILTIN_UNIV:
                kind = BuiltinConstantKind.UNIV;
                break;
            default:
                throw new IllegalArgumentException(
                        "Only Alloy none or univ may use the built-in set factory");
        }
        EGraphNode result = new EGraphNode(
                id,
                Opcode.GLOBALBINDING,
                Collections.emptyList(),
                false,
                0,
                false,
                Metatype.SET,
                semanticProfile);
        result.sourceName = checked.getName();
        result.sourceType = checked.getType();
        result.exactAlloyType = exactAlloyType;
        result.semanticIdentity = checked.getSemanticIdentity();
        result.builtinConstantKind = kind;
        return result;
    }

    /** Constructs Alloy's reserved polymorphic identity relation. */
    public static EGraphNode builtinIdentityRelation(
            int id,
            ConstSymbol symbol,
            ExactAlloyType exactAlloyType,
            SemanticProfile semanticProfile) {
        ConstSymbol checked = Objects.requireNonNull(
                symbol, "built-in identity symbol");
        if (!checked.isBuiltinIdentityRelation()) {
            throw new IllegalArgumentException(
                    "Only Alloy iden may use the identity-relation factory");
        }
        if (exactAlloyType == null
                || exactAlloyType.kind() != ExactAlloyType.Kind.RELATION
                || exactAlloyType.relationArity() != 2) {
            throw new IllegalArgumentException(
                    "Alloy iden requires an exact binary relation type");
        }
        EGraphNode result = new EGraphNode(
                id,
                Opcode.CONSTANT,
                Collections.emptyList(),
                false,
                0,
                false,
                Metatype.ATOMIC,
                semanticProfile);
        result.sourceName = checked.getName();
        result.sourceType = checked.getType();
        result.exactAlloyType = exactAlloyType;
        result.semanticIdentity = ConstSymbol.BUILTIN_IDEN_IDENTITY;
        result.builtinConstantKind = BuiltinConstantKind.IDEN;
        return result;
    }

    /** Constructs a normalization-derived reserved set constant in its source arena. */
    static EGraphNode derivedSetConstant(
            EGraphNode owner,
            int id,
            String name,
            ExactAlloyType exactAlloyType) {
        EGraphNode checkedOwner = Objects.requireNonNull(owner, "set-constant owner");
        SigSymbol symbol;
        if ("none".equals(name)) {
            symbol = SigSymbol.builtinNone();
        } else if ("univ".equals(name)) {
            symbol = SigSymbol.builtinUniv();
        } else {
            throw new IllegalArgumentException(
                    "Only Alloy none or univ may be normalization-derived");
        }
        EGraphNode result = inOwningArena(
                checkedOwner,
                id,
                Opcode.GLOBALBINDING,
                Collections.emptyList(),
                false,
                0,
                false,
                Metatype.SET,
                checkedOwner.getSemanticProfile());
        result.arena.mutate(result, () -> {
            result.sourceName = symbol.getName();
            result.sourceType = symbol.getType();
            result.exactAlloyType = exactAlloyType;
            result.semanticIdentity = symbol.getSemanticIdentity();
            result.builtinConstantKind = "none".equals(name)
                    ? BuiltinConstantKind.NONE : BuiltinConstantKind.UNIV;
        });
        return result;
    }

    /** Constructs normalization-derived Alloy iden in its source arena. */
    static EGraphNode derivedIdentityRelation(
            EGraphNode owner,
            int id,
            ExactAlloyType exactAlloyType) {
        EGraphNode checkedOwner = Objects.requireNonNull(
                owner, "identity-relation owner");
        if (exactAlloyType == null
                || exactAlloyType.kind() != ExactAlloyType.Kind.RELATION
                || exactAlloyType.relationArity() != 2) {
            throw new IllegalArgumentException(
                    "A derived Alloy iden requires an exact binary relation type");
        }
        ConstSymbol symbol = ConstSymbol.builtinIden();
        EGraphNode result = inOwningArena(
                checkedOwner,
                id,
                Opcode.CONSTANT,
                Collections.emptyList(),
                false,
                0,
                false,
                Metatype.ATOMIC,
                checkedOwner.getSemanticProfile());
        result.arena.mutate(result, () -> {
            result.sourceName = symbol.getName();
            result.sourceType = symbol.getType();
            result.exactAlloyType = exactAlloyType;
            result.semanticIdentity = ConstSymbol.BUILTIN_IDEN_IDENTITY;
            result.builtinConstantKind = BuiltinConstantKind.IDEN;
        });
        return result;
    }

    /** Constructs a parser-certified signature leaf derived by normalization. */
    static EGraphNode derivedParserSignature(
            EGraphNode owner,
            int id,
            SigSymbol evidence) {
        EGraphNode checkedOwner = Objects.requireNonNull(
                owner, "signature owner");
        SigSymbol checkedEvidence = Objects.requireNonNull(
                evidence, "signature evidence");
        if (!checkedEvidence.hasParserSignatureAuthority()) {
            throw new IllegalArgumentException(
                    "A derived signature requires live parser authority");
        }
        EGraphNode result = inOwningArena(
                checkedOwner,
                id,
                Opcode.GLOBALBINDING,
                Collections.emptyList(),
                false,
                0,
                false,
                Metatype.ATOMIC,
                checkedOwner.getSemanticProfile());
        ExactAlloyType exact = checkedEvidence.parserExactType();
        result.arena.mutate(result, () -> {
            result.sourceName = checkedEvidence.getName();
            result.sourceType = checkedEvidence.getType();
            result.exactAlloyType = exact;
            result.semanticIdentity = checkedEvidence.getSemanticIdentity();
            result.parserSignatureEvidence = checkedEvidence;
        });
        return result;
    }

    private void requireLiveNode() {
        if (eClass != null) {
            eClass.requireLive();
        }
    }

    public int getId() {
        requireLiveNode();
        return id;
    }
    public Opcode getOpcode() {
        requireLiveNode();
        return opcode;
    }
    public List<EGraphNode> getChildren() {
        requireLiveNode();
        return childrenView;
    }
    public void setChildren(List<EGraphNode> children) {
        replaceChildren(children, false);
    }

    /** Rebuilds children inside the trusted NormalForm construction boundary. */
    void setNormalizedChildren(List<EGraphNode> children) {
        replaceChildren(children, true);
    }

    private void replaceChildren(List<EGraphNode> children, boolean preserveDerivedAuthority) {
        arena.mutate(this, () -> {
            List<EClassRef> replacement = new ArrayList<>();
            if (children != null) {
                for (EGraphNode child : children) {
                    if (child != null) {
                        replacement.add(childReference(child));
                    }
                }
            }
            requireExtensibleChildCount(replacement.size());
            Opcode authority = preserveDerivedAuthority
                    ? derivedBooleanRewriteOpcode : null;
            long temporalAuthority = preserveDerivedAuthority
                    ? temporalReferenceAuthorityId : -1L;
            BuiltinConstantKind setConstantAuthority =
                    preserveDerivedAuthority && replacement.isEmpty()
                            ? builtinConstantKind : null;
            SigSymbol signatureAuthority =
                    preserveDerivedAuthority && replacement.isEmpty()
                            ? parserSignatureEvidence : null;
            derivedBooleanRewriteOpcode = null;
            temporalReferenceAuthorityId = -1L;
            builtinConstantKind = null;
            parserSignatureEvidence = null;
            childClasses = replacement;
            refreshEClassSlots();
            restoreDerivedBooleanRewriteAuthority(authority);
            if (temporalAuthority > 0L && opcode == Opcode.REF
                    && childClasses.isEmpty()) {
                temporalReferenceAuthorityId = temporalAuthority;
            }
            if (setConstantAuthority != null && childClasses.isEmpty()) {
                builtinConstantKind = setConstantAuthority;
            }
            if (signatureAuthority != null && childClasses.isEmpty()) {
                parserSignatureEvidence = signatureAuthority;
            }
        });
    }
    public boolean isCommutative() {
        requireLiveNode();
        return getSiblingQuotient().commutative();
    }
    public int getMaxArity() {
        requireLiveNode();
        return maxArity;
    }
    public boolean isFlexibleArity() {
        requireLiveNode();
        return operatorPolicy().isVariadic();
    }
    public FlexibleArityKind getFlexibleArityKind() {
        if (!isFlexibleArity()) {
            return FlexibleArityKind.FIXED;
        }
        if (getSiblingQuotient() == SiblingQuotient.COMMUTATIVE_IDEMPOTENT_SET) {
            return FlexibleArityKind.SET;
        }
        return getSiblingQuotient() == SiblingQuotient.COMMUTATIVE_BAG
                ? FlexibleArityKind.BAG : FlexibleArityKind.SEQUENCE;
    }
    public boolean isSetFlexibleArity() {
        return getFlexibleArityKind() == FlexibleArityKind.SET;
    }
    public boolean isBagFlexibleArity() {
        return getFlexibleArityKind() == FlexibleArityKind.BAG;
    }
    public boolean isSequenceFlexibleArity() {
        return getFlexibleArityKind() == FlexibleArityKind.SEQUENCE;
    }
    public boolean isOrderInsensitive() {
        return getSiblingQuotient().commutative();
    }
    public ArityPolicy getArityPolicy() {
        requireLiveNode();
        return operatorPolicy().arityPolicy();
    }
    public SiblingQuotient getSiblingQuotient() {
        requireLiveNode();
        return operatorPolicy().siblingQuotient();
    }
    public FlatLicense getFlatLicense() {
        requireLiveNode();
        return operatorPolicy().flatLicense();
    }
    public UnitLicense getUnitLicense() {
        requireLiveNode();
        return operatorPolicy().unitLicense();
    }
    public SemanticProfile getSemanticProfile() {
        requireLiveNode();
        return semanticProfile;
    }
    public boolean hasFlatLicense() {
        return getFlatLicense().enabled();
    }
    private AlloyOperatorPolicy operatorPolicy() {
        return AlloyOperatorPolicy.forShape(
                opcode, maxArity, flexibleArity, semanticProfile);
    }
    public void addChild(EGraphNode child) {
        appendChild(child, false);
    }

    /** Adds a child while constructing a trusted normalized Boolean node. */
    void addNormalizedChild(EGraphNode child) {
        appendChild(child, true);
    }

    private void appendChild(EGraphNode child, boolean preserveDerivedAuthority) {
        arena.mutate(this, () -> {
            EClassRef childReference = child == null ? null : childReference(child);
            if (childReference != null) {
                requireExtensibleChildCount(Math.addExact(childClasses.size(), 1));
            }
            Opcode authority = preserveDerivedAuthority
                    ? derivedBooleanRewriteOpcode : null;
            derivedBooleanRewriteOpcode = null;
            temporalReferenceAuthorityId = -1L;
            builtinConstantKind = null;
            parserSignatureEvidence = null;
            if (childReference == null) {
                return;
            }
            childClasses.add(childReference);
            refreshEClassSlots();
            restoreDerivedBooleanRewriteAuthority(authority);
        });
    }

    /** Adds an explicitly renamed child occurrence without discarding its slot map. */
    public void addChildInvocation(EClassRef child) {
        arena.mutate(this, () -> {
            if (child == null) {
                derivedBooleanRewriteOpcode = null;
                temporalReferenceAuthorityId = -1L;
                builtinConstantKind = null;
                parserSignatureEvidence = null;
                return;
            }
            if (child.eClass.arena != arena) {
                throw new IllegalArgumentException(
                        "A child invocation must belong to the same e-graph arena");
            }
            if (!semanticProfile.equals(
                    child.eClass.getRepresentative().semanticProfile)) {
                throw new IllegalArgumentException(
                        "A child invocation must use the parent's semantic profile");
            }
            requireExtensibleChildCount(Math.addExact(childClasses.size(), 1));
            derivedBooleanRewriteOpcode = null;
            temporalReferenceAuthorityId = -1L;
            builtinConstantKind = null;
            parserSignatureEvidence = null;
            childClasses.add(child);
            refreshEClassSlots();
        });
    }

    private void appendChildReference(EGraphNode child) {
        if (child != null) {
            childClasses.add(childReference(child));
        }
    }

    private EClassRef childReference(EGraphNode child) {
        requireCompatibleChild(child);
        return new EClassRef(child.getEClass(), identitySlotMap(child.getEClass()));
    }

    private void requireCompatibleChild(EGraphNode child) {
        if (child == null) {
            return;
        }
        if (child.arena != arena) {
            throw new IllegalArgumentException(
                    "A child node must belong to the same e-graph arena");
        }
        if (!semanticProfile.equals(child.semanticProfile)) {
            throw new IllegalArgumentException(
                    "A child node must use the parent's semantic profile");
        }
    }

    private void requireExtensibleChildCount() {
        requireExtensibleChildCount(childClasses.size());
    }

    private void requireExtensibleChildCount(int childCount) {
        if (isSourceWrapperWithExternalGrammar(opcode)) {
            return;
        }
        ArityPolicy policy = getArityPolicy();
        if (!policy.canExtend(childCount)) {
            throw new IllegalArgumentException(
                    opcode + " has too many children for " + policy + ": "
                            + childCount);
        }
    }

    /** Validates a completed source/operator occurrence before semantic use. */
    public void requireAdmittedArity() {
        if (isSourceWrapperWithExternalGrammar(opcode)) {
            return;
        }
        getArityPolicy().requireAdmitted(childClasses.size(), opcode.toString());
        if (opcode == Opcode.CALL) {
            CallMetadata.require(this);
        }
    }

    /**
     * Validates every reachable source occurrence before a rewrite may discard
     * any branch. This is intentionally a separate traversal from saturation:
     * absorber and identity rules must not be able to hide malformed evidence.
     */
    public void requireAdmittedGraph() {
        arena.requireAdmittedGraph(this);
    }

    /** Visits exactly the admitted alternatives reachable from this root. */
    void forEachAdmittedReachableNode(java.util.function.Consumer<EGraphNode> visitor) {
        arena.forEachAdmittedReachableNode(this, visitor);
    }

    private static void requireAdmittedOccurrence(EGraphNode node) {
        if (node.opcode == Opcode.LET && node.childClasses.isEmpty()) {
            if (!ExactAlloyType.isAdmittedIdentity(node.sourceName)) {
                throw new IllegalStateException(
                        "LET reference lacks a valid source identity");
            }
        } else {
            node.requireAdmittedArity();
        }
    }

    private static void enqueueUnionComponent(
            EClass current,
            Set<EClass> visited,
            ArrayDeque<EClass> pending,
            Set<Integer> expandedComponents) {
        current.requireLive();
        if (!current.registered) {
            return;
        }
        int componentRoot = current.arena.unionFind.componentRootId(current.id);
        if (!expandedComponents.add(componentRoot)) {
            return;
        }
        for (int id : current.arena.unionFind.componentIds(current.id)) {
            EClass equivalent = current.arena.classes.get(id);
            if (equivalent != null && !visited.contains(equivalent)) {
                pending.addLast(equivalent);
            }
        }
    }

    private static void requireAdmittedReachableGraph(
            EGraphNode root,
            java.util.function.Consumer<EGraphNode> visitor) {
        Set<EClass> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Integer> expandedComponents = new HashSet<>();
        ArrayDeque<EClass> pending = new ArrayDeque<>();
        pending.add(root.eClass);
        while (!pending.isEmpty()) {
            EClass current = pending.removeFirst();
            current.requireLive();
            if (!visited.add(current)) {
                continue;
            }
            enqueueUnionComponent(current, visited, pending, expandedComponents);
            for (EGraphNode node : current.nodes) {
                requireAdmittedOccurrence(node);
                visitor.accept(node);
                for (EClassRef child : node.childClasses) {
                    pending.addLast(child.getEClass());
                }
            }
        }
    }

    private static boolean isSourceWrapperWithExternalGrammar(Opcode opcode) {
        switch (opcode) {
            case PREDICATE:
            case FUNCTION:
            case ASSERTION:
            case CHECK:
            case RUN:
            case FACT:
            case MODULEDECL:
            case OPEN:
            case PARAMDECL:
            case SIGDECL:
            case FIELDDECL:
                return true;
            default:
                return false;
        }
    }

    /** Same typed/profile-indexed operator instance required by a flat splice. */
    public boolean sameFlatOperatorInstance(EGraphNode other) {
        requireLiveNode();
        if (other != null) {
            other.requireLiveNode();
        }
        if (other == null || opcode != other.opcode
                || arena != other.arena
                || !hasFlatLicense() || !other.hasFlatLicense()
                || !getArityPolicy().equals(other.getArityPolicy())
                || getSiblingQuotient() != other.getSiblingQuotient()
                || !getFlatLicense().equals(other.getFlatLicense())
                || !getUnitLicense().equals(other.getUnitLicense())
                || !semanticProfile.equals(other.semanticProfile)
                || metatype != other.metatype) {
            return false;
        }
        if (opcode == Opcode.AND || opcode == Opcode.OR) {
            return hasBooleanRewriteAuthority(this)
                    && hasBooleanRewriteAuthority(other);
        }
        if (exactAlloyType != null || other.exactAlloyType != null) {
            return exactAlloyType != null
                    && (exactAlloyType.equals(other.exactAlloyType)
                            || isParserCertifiedUnionWidening(
                                    exactAlloyType, other.exactAlloyType, opcode));
        }
        String leftType = normalizedSourceType(sourceType);
        String rightType = normalizedSourceType(other.sourceType);
        return !leftType.isEmpty() && leftType.equals(rightType);
    }

    private static boolean isParserCertifiedUnionWidening(
            ExactAlloyType outer,
            ExactAlloyType nested,
            Opcode opcode) {
        return opcode == Opcode.PLUS
                && nested != null
                && outer.kind() == ExactAlloyType.Kind.RELATION
                && nested.kind() == ExactAlloyType.Kind.RELATION
                && outer.relationArity() == nested.relationArity()
                && nested.isParserCertifiedRelationSubfamilyOf(outer);
    }

    private static String normalizedSourceType(String type) {
        return type == null ? "" : type.replaceAll("\\s+", "").trim();
    }
    public List<EClassRef> getChildClasses() {
        requireLiveNode();
        return Collections.unmodifiableList(childClasses);
    }
    public Map<String, Integer> getChildClassCardinalities() {
        requireLiveNode();
        Map<String, Integer> cardinalities = new LinkedHashMap<>();
        for (EClassRef child : childClasses) {
            String key = child.getEClass().getId() + child.getSlotMap().toString();
            cardinalities.put(key, cardinalities.getOrDefault(key, 0) + 1);
        }
        return Collections.unmodifiableMap(cardinalities);
    }
    public EClass getEClass() {
        requireLiveNode();
        return eClass;
    }
    /** Rejects an unproved representative choice at the certification boundary. */
    public void requirePristineCertificationSource() {
        if (eClass == null
                || eClass.getNodes().size() != 1
                || eClass.getRepresentative() != this) {
            throw new IllegalStateException(
                    "A certification source occurrence belongs to a non-singleton e-class");
        }
        EClassRef original = getEClassRef();
        EClassRef canonical = original.canonical();
        if (canonical.getEClass() != original.getEClass()
                || !canonical.getSlotMap().equals(original.getSlotMap())) {
            throw new IllegalStateException(
                    "A certification source occurrence belongs to an uncertified e-class union");
        }
    }
    public EClassRef getEClassRef() {
        eClass.requireLive();
        return eClass.invoke(identitySlotMap(eClass));
    }
    public static EClassRef union(EClassRef left, EClassRef right) {
        left.eClass.requireLive();
        right.eClass.requireLive();
        if (left.eClass.arena != right.eClass.arena) {
            throw new IllegalArgumentException("Cannot union e-classes from different e-graphs");
        }
        SemanticProfile leftProfile = left.eClass.getRepresentative().semanticProfile;
        SemanticProfile rightProfile = right.eClass.getRepresentative().semanticProfile;
        left.eClass.getRepresentative().requireAdmittedArity();
        right.eClass.getRepresentative().requireAdmittedArity();
        if (!leftProfile.equals(rightProfile)) {
            throw new IllegalArgumentException(
                    "Cannot union e-classes from different semantic profiles");
        }
        EGraphArena arena = left.eClass.arena;
        return arena.mutate(left.eClass, right.eClass, () -> {
            EClassRef canonicalLeft = left.canonical();
            EClassRef canonicalRight = right.canonical();
            if (canonicalLeft.eClass.id == canonicalRight.eClass.id) {
                canonicalLeft.eClass.addInvocationEquivalence(
                        canonicalLeft.slotMap,
                        canonicalRight.slotMap);
                return canonicalLeft;
            }
            canonicalLeft.eClass.ensureRegistered();
            canonicalRight.eClass.ensureRegistered();
            RenamedId leader = arena.unionFind.union(
                    left.asRenamedId(), right.asRenamedId());
            EClass leaderClass = arena.classes.get(leader.getId());
            return new EClassRef(leaderClass, leader.getRenaming());
        });
    }
    public static void beginGraph() {
        CURRENT_ARENA.set(new EGraphArena());
    }
    public static void endGraph() {
        CURRENT_ARENA.remove();
    }
    /**
     * Drops registered e-classes outside the child/union closure of {@code roots}.
     *
     * @return the number of disconnected registered e-classes removed
     */
    public static int retainReachable(List<EGraphNode> roots) {
        if (roots == null || roots.isEmpty()) {
            return retainNothing(CURRENT_ARENA.get());
        }
        Map<EGraphArena, Set<Integer>> reachableByArena = new LinkedHashMap<>();
        Map<EGraphArena, ArrayDeque<EClass>> pendingByArena = new LinkedHashMap<>();
        for (EGraphNode root : roots) {
            if (root == null || root.eClass == null) {
                continue;
            }
            root.eClass.requireLive();
            pendingByArena.computeIfAbsent(root.arena, ignored -> new ArrayDeque<>()).add(root.eClass);
            reachableByArena.computeIfAbsent(root.arena, ignored -> new HashSet<>());
        }
        if (pendingByArena.isEmpty()) {
            return retainNothing(CURRENT_ARENA.get());
        }
        int[] removed = new int[1];
        for (Map.Entry<EGraphArena, ArrayDeque<EClass>> entry : pendingByArena.entrySet()) {
            EGraphArena arena = entry.getKey();
            arena.mutateGlobally(() -> {
                Set<Integer> reachable = reachableByArena.get(arena);
                Set<EClass> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                Set<Integer> expandedComponents = new HashSet<>();
                ArrayDeque<EClass> pending = entry.getValue();
                while (!pending.isEmpty()) {
                    EClass current = pending.removeFirst();
                    if (!visited.add(current)) {
                        continue;
                    }
                    reachable.add(current.id);
                    enqueueUnionComponent(
                            current, visited, pending, expandedComponents);
                    for (EGraphNode node : current.nodes) {
                        for (EClassRef child : node.childClasses) {
                            pending.addLast(child.eClass);
                        }
                    }
                }
                int before = arena.classes.size();
                arena.unionFind.retainRegisteredIds(reachable);
                for (EClass candidate : new ArrayList<>(arena.classes.values())) {
                    if (!reachable.contains(candidate.id)) {
                        candidate.retire();
                    }
                }
                arena.classes.keySet().retainAll(reachable);
                removed[0] = Math.addExact(
                        removed[0], before - arena.classes.size());
            });
        }
        return removed[0];
    }

    private static int retainNothing(EGraphArena arena) {
        int[] removed = new int[1];
        arena.mutateGlobally(() -> {
            removed[0] = arena.classes.size();
            arena.unionFind.retainRegisteredIds(Collections.emptySet());
            for (EClass candidate : arena.classes.values()) {
                candidate.retire();
            }
            arena.classes.clear();
        });
        return removed[0];
    }
    public static ReachabilityStats countReachable(List<EGraphNode> roots) {
        if (roots == null || roots.isEmpty()) {
            return new ReachabilityStats(0, 0);
        }
        Set<EClass> classes = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<EGraphNode> nodes = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<EClass> pending = new ArrayDeque<>();
        for (EGraphNode root : roots) {
            if (root != null && root.eClass != null) {
                root.eClass.requireLive();
                pending.addLast(root.eClass);
            }
        }
        while (!pending.isEmpty()) {
            EClass eClass = pending.removeFirst();
            if (!classes.add(eClass)) {
                continue;
            }
            for (EGraphNode node : eClass.nodes) {
                nodes.add(node);
                for (EClassRef child : node.childClasses) {
                    pending.addLast(child.eClass);
                }
            }
        }
        return new ReachabilityStats(classes.size(), nodes.size());
    }
    public Metatype getMetatype() {
        requireLiveNode();
        return metatype;
    }
    public String getSourceName() {
        requireLiveNode();
        return sourceName;
    }
    public void setSourceName(String sourceName) {
        arena.mutate(this, () -> {
            derivedBooleanRewriteOpcode = null;
            temporalReferenceAuthorityId = -1L;
            builtinConstantKind = null;
            parserSignatureEvidence = null;
            this.sourceName = sourceName;
            if (opcode == Opcode.VARIABLE) {
                refreshEClassSlots();
            }
        });
    }
    public String getSourceType() {
        requireLiveNode();
        return sourceType;
    }
    public void setSourceType(String sourceType) {
        arena.mutate(this, () -> {
            derivedBooleanRewriteOpcode = null;
            temporalReferenceAuthorityId = -1L;
            builtinConstantKind = null;
            parserSignatureEvidence = null;
            this.sourceType = sourceType;
        });
    }
    public ExactAlloyType getExactAlloyType() {
        requireLiveNode();
        return exactAlloyType;
    }
    public void setExactAlloyType(ExactAlloyType exactAlloyType) {
        arena.mutate(this, () -> {
            derivedBooleanRewriteOpcode = null;
            temporalReferenceAuthorityId = -1L;
            builtinConstantKind = null;
            parserSignatureEvidence = null;
            this.exactAlloyType = exactAlloyType;
        });
    }

    void markTemporalSnapshotBinding() {
        arena.mutate(this, () -> {
            if (opcode != Opcode.VARIABLE || !childClasses.isEmpty()) {
                throw new IllegalStateException(
                        "Temporal snapshot provenance applies only to a variable leaf");
            }
            temporalSnapshotBinding = true;
        });
    }

    void preserveTemporalSnapshotBindingFrom(EGraphNode source) {
        EGraphNode checked = Objects.requireNonNull(
                source, "temporal snapshot source");
        checked.requireLiveNode();
        if (checked.temporalSnapshotBinding) {
            markTemporalSnapshotBinding();
        }
    }

    /** Records that trusted normalization derived this exact Boolean operator. */
    void markDerivedBooleanRewriteAuthority() {
        arena.mutate(this, this::recordDerivedBooleanRewriteAuthority);
    }

    /** Transfers internal derivation evidence only across an exact-opcode clone. */
    void preserveDerivedBooleanRewriteAuthorityFrom(EGraphNode source) {
        Objects.requireNonNull(source, "Boolean authority source");
        if (source.derivedBooleanRewriteOpcode == source.opcode
                && source.opcode == opcode) {
            markDerivedBooleanRewriteAuthority();
        }
    }

    private void recordDerivedBooleanRewriteAuthority() {
        if (!isBooleanOperatorRequiringAuthority(opcode)
                || metatype != Metatype.BOOLEAN
                || exactAlloyType == null
                || exactAlloyType.kind() != ExactAlloyType.Kind.BOOL
                || !isBooleanSourceType(sourceType)) {
            throw new IllegalStateException(
                    "Derived Boolean authority requires an exact Boolean operator");
        }
        derivedBooleanRewriteOpcode = opcode;
    }

    private void restoreDerivedBooleanRewriteAuthority(Opcode authority) {
        if (authority == opcode && isBooleanOperatorRequiringAuthority(opcode)) {
            recordDerivedBooleanRewriteAuthority();
        }
    }

    void attachTemporalReferenceAuthority(long authorityId) {
        arena.mutate(this, () -> {
            if (authorityId <= 0L || opcode != Opcode.REF || !childClasses.isEmpty()) {
                throw new IllegalArgumentException(
                        "Temporal reference authority requires a positive id on a nullary REF");
            }
            temporalReferenceAuthorityId = authorityId;
        });
    }

    void preserveTemporalReferenceAuthorityFrom(EGraphNode source) {
        Objects.requireNonNull(source, "temporal reference source");
        if (opcode == Opcode.REF && source.opcode == Opcode.REF
                && source.temporalReferenceAuthorityId > 0L
                && source.sourceOccurrenceLineage == sourceOccurrenceLineage) {
            attachTemporalReferenceAuthority(source.temporalReferenceAuthorityId);
        }
    }

    long temporalReferenceAuthorityId() {
        return temporalReferenceAuthorityId;
    }
    public String getAlphaName() {
        requireLiveNode();
        return alphaName;
    }
    public void setAlphaName(String alphaName) {
        arena.mutate(this, () -> {
            derivedBooleanRewriteOpcode = null;
            temporalReferenceAuthorityId = -1L;
            builtinConstantKind = null;
            this.alphaName = alphaName;
            refreshEClassSlots();
        });
    }
    public String getSemanticIdentity() {
        requireLiveNode();
        return semanticIdentity;
    }

    /** Binds a nullary signature leaf to its live parser declaration. */
    public void attachParserSignatureEvidence(SigSymbol evidence) {
        SigSymbol checked = Objects.requireNonNull(
                evidence, "parser signature evidence");
        arena.mutate(this, () -> {
            if (opcode != Opcode.GLOBALBINDING
                    || metatype != Metatype.ATOMIC
                    || !childClasses.isEmpty()
                    || !checked.hasParserSignatureAuthority()
                    || !checked.authenticatesExactType(exactAlloyType)
                    || exactAlloyType.kind() != ExactAlloyType.Kind.RELATION
                    || exactAlloyType.relationArity() != 1
                    || !checked.getName().equals(sourceName)
                    || !"Signature".equals(sourceType)
                    || !checked.getSemanticIdentity().equals(semanticIdentity)) {
                throw new IllegalArgumentException(
                        "Parser signature evidence does not match this source leaf");
            }
            parserSignatureEvidence = checked;
        });
    }

    /** Transfers parser declaration evidence only across an exact source clone. */
    void preserveParserSignatureEvidenceFrom(EGraphNode source) {
        EGraphNode checked = Objects.requireNonNull(
                source, "parser signature source");
        checked.requireLiveNode();
        if (checked.parserSignatureEvidence != null
                && checked.opcode == opcode
                && checked.sourceOccurrenceLineage == sourceOccurrenceLineage) {
            attachParserSignatureEvidence(checked.parserSignatureEvidence);
        }
    }

    public void setSemanticIdentity(String semanticIdentity) {
        arena.mutate(this, () -> {
            if (SigSymbol.BUILTIN_NONE_IDENTITY.equals(semanticIdentity)
                    || SigSymbol.BUILTIN_UNIV_IDENTITY.equals(semanticIdentity)
                    || ConstSymbol.BUILTIN_IDEN_IDENTITY.equals(semanticIdentity)) {
                throw new IllegalArgumentException(
                        "Reserved Alloy constant identities require factory or derivation authority");
            }
            derivedBooleanRewriteOpcode = null;
            temporalReferenceAuthorityId = -1L;
            builtinConstantKind = null;
            parserSignatureEvidence = null;
            this.semanticIdentity = semanticIdentity;
        });
    }

    /** Transfers an existing semantic identity only inside trusted NormalForm cloning. */
    void preserveSemanticIdentityFrom(EGraphNode source) {
        EGraphNode checked = Objects.requireNonNull(source, "semantic identity source");
        arena.mutate(this, () -> {
            checked.requireLiveNode();
            if (checked.arena != arena
                    || !checked.semanticProfile.equals(semanticProfile)) {
                throw new IllegalArgumentException(
                        "A semantic identity clone must retain arena and profile ownership");
            }
            semanticIdentity = checked.semanticIdentity;
            builtinConstantKind = checked.builtinConstantKind;
        });
    }
    public long getSourceOccurrenceLineage() {
        requireLiveNode();
        return sourceOccurrenceLineage;
    }

    /** Exact pre-saturation occurrence carrier for certificate transfer only. */
    public long getCertificationOccurrenceLineage() {
        requireLiveNode();
        return certificationOccurrenceLineage;
    }

    /** Permanently closes semantic mutation of this source e-graph arena. */
    public void freezeForCertification() {
        arena.freezeForCertification(this);
    }

    final void admitAndFreezeForCertification(
            java.util.function.Consumer<EGraphNode> visitor) {
        arena.admitAndFreezeForCertification(
                this, java.util.Objects.requireNonNull(visitor, "admission visitor"));
    }

    public boolean isFrozenForCertification() {
        requireLiveNode();
        return arena.isFrozenForCertification(this);
    }

    /**
     * Deterministic content commitment for one binary JOIN/ARROW source tree.
     * Occurrence identity is supplied separately by the adapter's stable source
     * path; this value deliberately excludes process-local ids and lineages.
     */
    public String dependentChainSourceContentCommitment() {
        requireLiveNode();
        if (opcode != Opcode.JOIN && opcode != Opcode.ARROW) {
            throw new IllegalStateException(
                    "A dependent-chain source commitment requires JOIN or ARROW");
        }
        StringBuilder result = new StringBuilder();
        appendDependentChainSourceContent(
                this,
                opcode,
                result,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        return result.toString();
    }

    /**
     * Source-content key used only to transfer a dependent-chain proof from
     * the retained pre-ACI tree to its frozen repair tree. The chain itself
     * remains ordered and binary; only certified ACI containers below a chain
     * operand are compared through their admitted quotient.
     */
    public String dependentChainTransferContentCommitment() {
        requireLiveNode();
        if (opcode != Opcode.JOIN && opcode != Opcode.ARROW) {
            throw new IllegalStateException(
                    "A dependent-chain transfer commitment requires JOIN or ARROW");
        }
        StringBuilder result = new StringBuilder();
        appendDependentChainTransferContent(
                this,
                opcode,
                result,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        return result.toString();
    }

    private static void appendDependentChainSourceContent(
            EGraphNode node,
            Opcode chainOpcode,
            StringBuilder output,
            Set<EGraphNode> active) {
        if (node.opcode != chainOpcode) {
            appendLengthEncoded(output, "leaf");
            appendLengthEncoded(output, node.sortKey());
            return;
        }
        if (!active.add(node)) {
            throw new IllegalStateException(
                    "A dependent-chain source commitment encountered a cycle");
        }
        try {
            node.requireAdmittedArity();
            if (node.childClasses.size() != 2) {
                throw new IllegalStateException(
                        "A dependent-chain source commitment requires binary syntax");
            }
            appendLengthEncoded(output, "application");
            appendLengthEncoded(output, chainOpcode.name());
            appendLengthEncoded(output, node.semanticProfile.fingerprint());
            appendLengthEncoded(output, node.exactAlloyType == null
                    ? "" : node.exactAlloyType.stableString());
            for (EClassRef childRef : node.childClasses) {
                appendLengthEncoded(
                        output,
                        new java.util.TreeMap<>(childRef.getSlotMap()).toString());
                appendDependentChainSourceContent(
                        childRef.getEClass().getRepresentative(),
                        chainOpcode,
                        output,
                        active);
            }
        } finally {
            active.remove(node);
        }
    }

    private static void appendDependentChainTransferContent(
            EGraphNode node,
            Opcode chainOpcode,
            StringBuilder output,
            Set<EGraphNode> active) {
        if (node.opcode != chainOpcode) {
            appendLengthEncoded(output, "leaf");
            appendLengthEncoded(
                    output,
                    certifiedContainerInvocationKey(node.getEClassRef()));
            return;
        }
        if (!active.add(node)) {
            throw new IllegalStateException(
                    "A dependent-chain transfer commitment encountered a cycle");
        }
        try {
            node.requireAdmittedArity();
            if (node.childClasses.size() != 2) {
                throw new IllegalStateException(
                        "A dependent-chain transfer commitment requires binary syntax");
            }
            appendLengthEncoded(output, "application");
            appendLengthEncoded(output, chainOpcode.name());
            appendLengthEncoded(output, node.semanticProfile.fingerprint());
            appendLengthEncoded(output, node.exactAlloyType == null
                    ? "" : node.exactAlloyType.stableString());
            for (EClassRef childRef : node.childClasses) {
                appendLengthEncoded(
                        output,
                        new java.util.TreeMap<>(childRef.getSlotMap()).toString());
                appendDependentChainTransferContent(
                        childRef.getEClass().getRepresentative(),
                        chainOpcode,
                        output,
                        active);
            }
        } finally {
            active.remove(node);
        }
    }

    private static void appendLengthEncoded(StringBuilder output, String value) {
        output.append(value.length()).append(':').append(value);
    }

    void preserveSourceOccurrenceLineageFrom(EGraphNode source) {
        arena.mutate(this, () -> {
            if (source == null || source.sourceOccurrenceLineage <= 0L
                    || source.certificationOccurrenceLineage <= 0L) {
                throw new IllegalArgumentException(
                        "A source occurrence lineage must be positive");
            }
            sourceOccurrenceLineage = source.sourceOccurrenceLineage;
            certificationOccurrenceLineage =
                    source.certificationOccurrenceLineage;
        });
    }

    void reseedSourceOccurrenceLineages() {
        arena.mutate(this, () -> {
            Set<EGraphNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            ArrayDeque<EGraphNode> pending = new ArrayDeque<>();
            pending.add(this);
            while (!pending.isEmpty()) {
                EGraphNode node = pending.removeFirst();
                if (!visited.add(node)) {
                    continue;
                }
                long lineage = nextSourceOccurrenceLineage();
                node.sourceOccurrenceLineage = lineage;
                node.certificationOccurrenceLineage = lineage;
                pending.addAll(node.getChildren());
            }
        });
    }
    public int getDeclaredArity() {
        requireLiveNode();
        return declaredArity;
    }
    public long getCallOccurrenceId() {
        requireLiveNode();
        return callOccurrenceId;
    }
    public void setCallOccurrenceId(long callOccurrenceId) {
        arena.mutate(this, () -> {
            if (callOccurrenceId < -1L) {
                throw new IllegalArgumentException(
                        "CALL occurrence id must be -1 or nonnegative");
            }
            derivedBooleanRewriteOpcode = null;
            temporalReferenceAuthorityId = -1L;
            builtinConstantKind = null;
            this.callOccurrenceId = callOccurrenceId;
        });
    }
    public void setDeclaredArity(int declaredArity) {
        arena.mutate(this, () -> {
            if (declaredArity < -1) {
                throw new IllegalArgumentException("Declared arity must be -1 or nonnegative");
            }
            derivedBooleanRewriteOpcode = null;
            temporalReferenceAuthorityId = -1L;
            builtinConstantKind = null;
            this.declaredArity = declaredArity;
        });
    }
    public String getCallArityAuthority() {
        requireLiveNode();
        return callArityAuthority;
    }
    public void setCallArityAuthority(String callArityAuthority) {
        arena.mutate(this, () -> {
            derivedBooleanRewriteOpcode = null;
            temporalReferenceAuthorityId = -1L;
            builtinConstantKind = null;
            this.callArityAuthority = callArityAuthority;
        });
    }

    /**
     * Rewrite the e-graph with regard to rewriting rules; canonicalize the formula with equality saturation. 
     */
    public void saturate() {
        arena.mutate(this, () -> {
            // Reject before the first rewrite if any shared reachable source was
            // frozen through another parent.
            requireAdmittedReachableGraph(
                    this, node -> arena.requireMutable(node));
            Set<Integer> active = new HashSet<>();
            saturate(active);
        });
    }

    private void saturate(Set<Integer> active) {
        if (!active.add(eClass.getId())) {
            return;
        }
        arena.requireMutable(this);
        requireAdmittedOccurrence(this);
        for (EClassRef child : new ArrayList<>(childClasses)) {
            child.getEClass().getRepresentative().saturate(active);
        }
        boolean changed;
        int iterations = 0;
        do {
            changed = saturateOnce();
            iterations++;
        } while (changed && iterations < 32);
        active.remove(eClass.getId());
    }

    @LeanVerifiedRewrite({
            "R0-CORE-004", "R0-CORE-007", "R0-CORE-008", "R0-CORE-017",
            "R0-CORE-018", "R0-CORE-019", "R0-CORE-020", "R0-CORE-021",
            "R0-CORE-022", "R0-CORE-023", "R0-CORE-024", "R0-REL-034"
    })
    private boolean saturateOnce() {
        List<EClassRef> canonicalChildren = null;
        for (int i = 0; i < childClasses.size(); i++) {
            EClassRef child = childClasses.get(i);
            EClassRef canonical = child.canonical();
            if (!child.equals(canonical)) {
                if (canonicalChildren == null) {
                    canonicalChildren = new ArrayList<>(childClasses);
                }
                canonicalChildren.set(i, canonical);
            }
        }
        if (canonicalChildren != null) {
            childClasses = canonicalChildren;
            refreshEClassSlots();
        }

        if (isBooleanRewriteOperator(opcode)
                && (!hasBooleanRewriteAuthority(this) || !hasBooleanOperands(this))) {
            return false;
        }

        if (opcode == Opcode.NOT && childClasses.size() == 1) {
            EGraphNode child = childClasses.get(0).getEClass().getRepresentative();
            if (isBooleanConstant(child, true) || isBooleanConstant(child, false)) {
                eClass.preserveSnapshot(snapshot());
                collapseToBooleanConstant(isBooleanConstant(child, false));
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            if (child.getOpcode() == Opcode.NOT
                    && hasBooleanRewriteAuthority(child)
                    && hasBooleanOperands(child)
                    && child.childClasses.size() == 1) {
                eClass.preserveSnapshot(snapshot());
                adopt(child.childClasses.get(0).getEClass().getRepresentative());
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
        }

        if (opcode == Opcode.JOIN && childClasses.size() == 2) {
            EGraphNode contextualRestriction =
                    parserCertifiedJoinRestrictionNormalization(
                            this, childClasses.get(0), childClasses.get(1));
            if (contextualRestriction != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(contextualRestriction);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
        }

        if (saturateRelationalUnaryRule()) {
            return true;
        }

        List<EClassRef> rewrittenChildren = new ArrayList<>();
        for (EClassRef childRef : childClasses) {
            EGraphNode child = childRef.getEClass().getRepresentative();
            if (sameFlatOperatorInstance(child)) {
                for (EClassRef grandchild : child.childClasses) {
                    rewrittenChildren.add(composeInvocation(childRef, grandchild));
                }
            } else {
                rewrittenChildren.add(childRef);
            }
        }
        if (isOrderInsensitive()) {
            rewrittenChildren.sort(Comparator.comparing(ref -> ref.getEClass().getRepresentative().sortKey()));
        }

        if (isSetFlexibleArity()) {
            rewrittenChildren = removeDuplicateInvocations(rewrittenChildren);
        }

        if (opcode == Opcode.JOIN
                && isExactRelationNode(this)
                && rewrittenChildren.size() >= 2
                && rewrittenChildren.stream().allMatch(ref ->
                        isExactRelationNode(
                                ref.getEClass().getRepresentative()))) {
            List<EClassRef> withoutIdentity = new ArrayList<>();
            for (EClassRef child : rewrittenChildren) {
                if (!isIdentityRelation(
                        child.getEClass().getRepresentative())) {
                    withoutIdentity.add(child);
                }
            }
            if (withoutIdentity.size() != rewrittenChildren.size()) {
                eClass.preserveSnapshot(snapshot());
                if (withoutIdentity.isEmpty()) {
                    adopt(rewrittenChildren.get(0)
                            .getEClass().getRepresentative());
                } else if (withoutIdentity.size() == 1) {
                    adopt(withoutIdentity.get(0)
                            .getEClass().getRepresentative());
                } else {
                    childClasses = withoutIdentity;
                }
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
        }

        if (opcode == Opcode.PLUS && isSetFlexibleArity()) {
            EGraphNode partition = parserCertifiedDifferencePartitionRecombination(
                    this, rewrittenChildren);
            if (partition != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(partition);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode absorption = parserCertifiedStructuralLatticeAbsorption(
                    this, rewrittenChildren);
            if (absorption != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(absorption);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode restriction = parserCertifiedRestrictionLatticeFactoring(
                    this, rewrittenChildren);
            if (restriction != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(restriction);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode difference = parserCertifiedDifferenceFactoring(
                    this, rewrittenChildren);
            if (difference != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(difference);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode join = parserCertifiedJoinUnionFactoring(
                    this, rewrittenChildren);
            if (join != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(join);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode productCarrier =
                    parserCertifiedProductUnionCarrier(
                            this, rewrittenChildren);
            if (productCarrier != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(productCarrier);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode abstractCarrier = parserCertifiedAbstractUnionCarrier(
                    this, representativesOf(rewrittenChildren));
            if (abstractCarrier != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(abstractCarrier);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            rewrittenChildren = removeSubrelationsCoveredByFullCarriers(
                    rewrittenChildren);
        }

        if (opcode == Opcode.AND || opcode == Opcode.OR) {
            Boolean constantValue = booleanConstantIn(rewrittenChildren, opcode == Opcode.AND ? false : true);
            if (constantValue != null) {
                eClass.preserveSnapshot(snapshot());
                collapseToBooleanConstant(constantValue);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            List<EClassRef> withoutNeutral = removeBooleanConstant(
                    rewrittenChildren,
                    opcode == Opcode.AND);
            if (withoutNeutral.size() != rewrittenChildren.size()) {
                rewrittenChildren = withoutNeutral;
            }
            if (rewrittenChildren.isEmpty()) {
                eClass.preserveSnapshot(snapshot());
                collapseToBooleanConstant(opcode == Opcode.AND);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            if (containsComplement(rewrittenChildren)
                    || containsCertifiedCoveredDualBranch(
                            this, rewrittenChildren)) {
                eClass.preserveSnapshot(snapshot());
                collapseToBooleanConstant(opcode == Opcode.OR);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            if (rewrittenChildren.size() == 1) {
                eClass.preserveSnapshot(snapshot());
                adopt(rewrittenChildren.get(0).getEClass().getRepresentative());
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode lattice = parserCertifiedLatticeNormalForm(
                    this, rewrittenChildren);
            if (lattice != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(lattice);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
        }

        if (isSetFlexibleArityOperator(opcode) && rewrittenChildren.size() == 1 && childClasses.size() != 1) {
            eClass.preserveSnapshot(snapshot());
            adopt(rewrittenChildren.get(0).getEClass().getRepresentative());
            refreshEClassSlots();
            eClass.recordShape(this);
            return true;
        }

        if (!sameChildren(childClasses, rewrittenChildren)) {
            eClass.preserveSnapshot(snapshot());
            childClasses = rewrittenChildren;
            refreshEClassSlots();
            eClass.recordShape(this);
            return true;
        }

        if (opcode == Opcode.NOT && childClasses.size() == 1) {
            EGraphNode child = childClasses.get(0).getEClass().getRepresentative();
            if ((child.getOpcode() == Opcode.AND || child.getOpcode() == Opcode.OR)
                    && hasBooleanRewriteAuthority(child)
                    && hasBooleanOperands(child)) {
                eClass.preserveSnapshot(snapshot());
                opcode = child.getOpcode() == Opcode.AND ? Opcode.OR : Opcode.AND;
                isCommutative = true;
                flexibleArity = true;
                maxArity = -1;
                sourceName = null;
                sourceType = "Bool";
                recordDerivedBooleanRewriteAuthority();
                childClasses = new ArrayList<>();
                for (EClassRef grandchild : child.childClasses) {
                    EGraphNode negated = new EGraphNode(
                            -Math.abs(grandchild.getEClass().getId()) - 1,
                            Opcode.NOT,
                            new ArrayList<>(),
                            false,
                            1,
                            false,
                            Metatype.BOOLEAN,
                            semanticProfile,
                            true,
                            arena);
                    negated.setSourceType("Bool");
                    negated.setExactAlloyType(ExactAlloyType.boolType());
                    negated.markDerivedBooleanRewriteAuthority();
                    negated.addNormalizedChild(grandchild.getEClass().getRepresentative());
                    negated.saturate();
                    addNormalizedChild(negated);
                }
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
        }

        if ((opcode == Opcode.ARROW || opcode == Opcode.JOIN)
                && isExactRelationNode(this)
                && containsSetConstant(childClasses, "none")) {
            eClass.preserveSnapshot(snapshot());
            collapseToSetConstant("none");
            refreshEClassSlots();
            eClass.recordShape(this);
            return true;
        }

        Boolean reflexiveComparison = parserCertifiedReflexiveComparison(
                this, childClasses);
        if (reflexiveComparison != null) {
            eClass.preserveSnapshot(snapshot());
            collapseToBooleanConstant(reflexiveComparison);
            refreshEClassSlots();
            eClass.recordShape(this);
            return true;
        }

        Boolean structuralSubset = parserCertifiedStructuralSubsetComparison(
                this, childClasses);
        if (structuralSubset != null) {
            eClass.preserveSnapshot(snapshot());
            collapseToBooleanConstant(structuralSubset);
            refreshEClassSlots();
            eClass.recordShape(this);
            return true;
        }

        EGraphNode subsetExpansion = parserCertifiedSubsetLatticeExpansion(
                this, childClasses);
        if (subsetExpansion != null) {
            eClass.preserveSnapshot(snapshot());
            adopt(subsetExpansion);
            refreshEClassSlots();
            eClass.recordShape(this);
            return true;
        }

        EGraphNode emptyRightSubset = parserCertifiedEmptyRightSubsetExpansion(
                this, childClasses);
        if (emptyRightSubset != null) {
            eClass.preserveSnapshot(snapshot());
            adopt(emptyRightSubset);
            refreshEClassSlots();
            eClass.recordShape(this);
            return true;
        }

        if (opcode == Opcode.IN && childClasses.size() == 2) {
            EGraphNode lhs = childClasses.get(0).getEClass().getRepresentative();
            EGraphNode rhs = childClasses.get(1).getEClass().getRepresentative();
            Boolean subset = null;
            if (isNone(lhs)
                    && (isNone(rhs) || hasCompatibleRelationArity(lhs, rhs))) {
                subset = true;
            } else if (isUniv(rhs)) {
                subset = true;
            }
            if (subset != null) {
                eClass.preserveSnapshot(snapshot());
                collapseToBooleanConstant(subset);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
        }

        if (opcode == Opcode.NOT_IN && childClasses.size() == 2) {
            EGraphNode lhs = childClasses.get(0).getEClass().getRepresentative();
            EGraphNode rhs = childClasses.get(1).getEClass().getRepresentative();
            Boolean notSubset = null;
            if ((isNone(lhs)
                            && (isNone(rhs)
                                    || hasCompatibleRelationArity(lhs, rhs)))
                    || isUniv(rhs)) {
                notSubset = false;
            }
            if (notSubset != null) {
                eClass.preserveSnapshot(snapshot());
                collapseToBooleanConstant(notSubset);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
        }

        if (opcode == Opcode.MINUS && childClasses.size() == 2) {
            EGraphNode left = childClasses.get(0).getEClass().getRepresentative();
            EGraphNode right = childClasses.get(1).getEClass().getRepresentative();
            EGraphNode partition = parserCertifiedDifferencePartitionNormalization(
                    this, childClasses.get(0), childClasses.get(1));
            if (partition != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(partition);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            if (isNone(left) || isUniv(right)
                    || sameInvocation(childClasses.get(0), childClasses.get(1))) {
                eClass.preserveSnapshot(snapshot());
                collapseToSetConstant("none");
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            if (isNone(right)) {
                eClass.preserveSnapshot(snapshot());
                adopt(left);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode restriction = parserCertifiedRestrictionDifferenceFactoring(
                    this, childClasses.get(0), childClasses.get(1));
            if (restriction != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(restriction);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode productDifference =
                    parserCertifiedProductDifferenceFactoring(
                            this, childClasses.get(0), childClasses.get(1));
            if (productDifference != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(productDifference);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode rightNestedDifference =
                    parserCertifiedRightNestedDifference(
                            this, childClasses.get(0), childClasses.get(1));
            if (rightNestedDifference != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(rightNestedDifference);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode nestedDifference = parserCertifiedLeftNestedDifference(
                    this, childClasses.get(0), childClasses.get(1));
            if (nestedDifference != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(nestedDifference);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
        }

        if (opcode == Opcode.IMPLIES && childClasses.size() == 2) {
            EGraphNode left = childClasses.get(0).getEClass().getRepresentative();
            EGraphNode right = childClasses.get(1).getEClass().getRepresentative();
            if (!isBooleanConstant(left, false) && !isBooleanConstant(left, true)
                    && !isBooleanConstant(right, false) && !isBooleanConstant(right, true)) {
                eClass.preserveSnapshot(snapshot());
                EGraphNode negatedLeft = new EGraphNode(
                        -Math.abs(left.getId()) - 11,
                        Opcode.NOT,
                        new ArrayList<>(),
                        false,
                        1,
                        false,
                        Metatype.BOOLEAN,
                        semanticProfile,
                        true,
                        arena);
                negatedLeft.setSourceType("Bool");
                negatedLeft.setExactAlloyType(ExactAlloyType.boolType());
                negatedLeft.markDerivedBooleanRewriteAuthority();
                negatedLeft.addNormalizedChild(left);
                opcode = Opcode.OR;
                childClasses = new ArrayList<>();
                isCommutative = true;
                maxArity = -1;
                flexibleArity = true;
                sourceName = null;
                sourceType = "Bool";
                recordDerivedBooleanRewriteAuthority();
                addNormalizedChild(negatedLeft);
                addNormalizedChild(right);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            if (isBooleanConstant(left, false) || isBooleanConstant(right, true)) {
                eClass.preserveSnapshot(snapshot());
                collapseToBooleanConstant(true);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            if (isBooleanConstant(left, true)) {
                eClass.preserveSnapshot(snapshot());
                adopt(right);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            if (isBooleanConstant(right, false)) {
                eClass.preserveSnapshot(snapshot());
                opcode = Opcode.NOT;
                childClasses = new ArrayList<>();
                isCommutative = false;
                maxArity = 1;
                flexibleArity = false;
                sourceName = null;
                sourceType = "Bool";
                recordDerivedBooleanRewriteAuthority();
                addNormalizedChild(left);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
        }

        if ((opcode == Opcode.INTERSECT || opcode == Opcode.PLUS) && childClasses.size() >= 2) {
            if (opcode == Opcode.INTERSECT && containsSetConstant(childClasses, "none")) {
                eClass.preserveSnapshot(snapshot());
                collapseToSetConstant("none");
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            if (opcode == Opcode.INTERSECT) {
                EGraphNode disjoint = parserCertifiedDifferenceDisjointness(
                        this, childClasses);
                if (disjoint != null) {
                    eClass.preserveSnapshot(snapshot());
                    adopt(disjoint);
                    refreshEClassSlots();
                    eClass.recordShape(this);
                    return true;
                }
                EGraphNode absorption = parserCertifiedStructuralLatticeAbsorption(
                        this, childClasses);
                if (absorption != null) {
                    eClass.preserveSnapshot(snapshot());
                    adopt(absorption);
                    refreshEClassSlots();
                    eClass.recordShape(this);
                    return true;
                }
                List<EClassRef> withoutUniv = removeSetConstant(
                        childClasses, "univ");
                if (withoutUniv.size() != childClasses.size()) {
                    eClass.preserveSnapshot(snapshot());
                    if (withoutUniv.isEmpty()) {
                        collapseToSetConstant("univ");
                    } else if (withoutUniv.size() == 1) {
                        adopt(withoutUniv.get(0).getEClass().getRepresentative());
                    } else {
                        childClasses = withoutUniv;
                    }
                    refreshEClassSlots();
                    eClass.recordShape(this);
                    return true;
                }
                List<EClassRef> withoutContainingCarriers =
                        removeFullCarriersContainingSubrelations(childClasses);
                if (withoutContainingCarriers.size() != childClasses.size()) {
                    eClass.preserveSnapshot(snapshot());
                    if (withoutContainingCarriers.size() == 1) {
                        adopt(withoutContainingCarriers.get(0)
                                .getEClass().getRepresentative());
                    } else {
                        childClasses = withoutContainingCarriers;
                    }
                    refreshEClassSlots();
                    eClass.recordShape(this);
                    return true;
                }
                EGraphNode restriction =
                        parserCertifiedRestrictionLatticeFactoring(
                                this, childClasses);
                if (restriction != null) {
                    eClass.preserveSnapshot(snapshot());
                    adopt(restriction);
                    refreshEClassSlots();
                    eClass.recordShape(this);
                    return true;
                }
                EGraphNode productIntersection =
                        parserCertifiedProductIntersectionFactoring(
                                this, childClasses);
                if (productIntersection != null) {
                    eClass.preserveSnapshot(snapshot());
                    adopt(productIntersection);
                    refreshEClassSlots();
                    eClass.recordShape(this);
                    return true;
                }
                EGraphNode difference = parserCertifiedDifferenceFactoring(
                        this, childClasses);
                if (difference != null) {
                    eClass.preserveSnapshot(snapshot());
                    adopt(difference);
                    refreshEClassSlots();
                    eClass.recordShape(this);
                    return true;
                }
                EGraphNode extractedDifference =
                        parserCertifiedIntersectionDifferenceExtraction(
                                this, childClasses);
                if (extractedDifference != null) {
                    eClass.preserveSnapshot(snapshot());
                    adopt(extractedDifference);
                    refreshEClassSlots();
                    eClass.recordShape(this);
                    return true;
                }
            }
            if (opcode == Opcode.PLUS) {
                if (containsSetConstant(childClasses, "univ")) {
                    eClass.preserveSnapshot(snapshot());
                    collapseToSetConstant("univ");
                    refreshEClassSlots();
                    eClass.recordShape(this);
                    return true;
                }
                List<EClassRef> withoutNone = removeSetConstant(childClasses, "none");
                if (withoutNone.size() != childClasses.size()) {
                    if (withoutNone.isEmpty()) {
                        eClass.preserveSnapshot(snapshot());
                        collapseToSetConstant("none");
                    } else if (withoutNone.size() == 1) {
                        eClass.preserveSnapshot(snapshot());
                        adopt(withoutNone.get(0).getEClass().getRepresentative());
                    } else {
                        eClass.preserveSnapshot(snapshot());
                        childClasses = withoutNone;
                    }
                    refreshEClassSlots();
                    eClass.recordShape(this);
                    return true;
                }
            }
            EGraphNode lattice = parserCertifiedLatticeNormalForm(
                    this, childClasses);
            if (lattice != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(lattice);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
        }
        return false;
    }

    @LeanVerifiedRewrite({"R0-REL-007", "R0-REL-033"})
    private boolean saturateRelationalUnaryRule() {
        if ((opcode == Opcode.SOME || opcode == Opcode.NO)
                && childClasses.size() == 1) {
            EGraphNode difference = parserCertifiedDifferenceCardinalityExpansion(
                    this, childClasses.get(0));
            if (difference != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(difference);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode expanded = parserCertifiedUnionCardinalityExpansion(
                    this, childClasses.get(0));
            if (expanded != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(expanded);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
        }

        if ((opcode == Opcode.DOMAIN || opcode == Opcode.RANGE)
                && childClasses.size() == 2) {
            EGraphNode identity = parserCertifiedRestrictionIdentityOrZero(
                    this, getEClassRef());
            if (identity != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(identity);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode unary = parserCertifiedUnaryRestrictionOrientation(
                    this, getEClassRef());
            if (unary != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(unary);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode restriction = parserCertifiedNestedRestriction(
                    this, childClasses.get(0), childClasses.get(1));
            if (restriction != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(restriction);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
        }

        if (opcode == Opcode.TRANSPOSE
                && childClasses.size() == 1
                && hasExactRelationArity(this, 2)) {
            EClassRef childInvocation = childClasses.get(0);
            EGraphNode child = childInvocation.getEClass().getRepresentative();
            EGraphNode empty = parserCertifiedEmptyRelationalUnaryIdentity(
                    this, childInvocation);
            if (empty != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(empty);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            if (isIdentityRelation(child)
                    && sameExactRelationOccurrence(this, child)) {
                eClass.preserveSnapshot(snapshot());
                adopt(child);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            if (child.opcode == Opcode.TRANSPOSE
                    && child.childClasses.size() == 1
                    && isExactBinaryRelation(child)
                    && sameExactRelationOccurrence(
                            this,
                            child.childClasses.get(0)
                                    .getEClass().getRepresentative())) {
                eClass.preserveSnapshot(snapshot());
                adopt(child.childClasses.get(0)
                        .getEClass().getRepresentative());
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            if (child.opcode == Opcode.ARROW
                    && child.childClasses.size() == 2
                    && isExactBinaryRelation(child)) {
                EGraphNode reversed = inOwningArena(
                        this,
                        id,
                        Opcode.ARROW,
                        Collections.emptyList(),
                        false,
                        -1,
                        true,
                        Metatype.SET,
                        semanticProfile);
                reversed.setSourceName(child.sourceName);
                reversed.setSourceType(child.sourceType);
                reversed.setExactAlloyType(exactAlloyType);
                reversed.preserveSourceOccurrenceLineageFrom(child);
                reversed.addChildInvocation(composeInvocation(
                        childInvocation, child.childClasses.get(1)));
                reversed.addChildInvocation(composeInvocation(
                        childInvocation, child.childClasses.get(0)));
                eClass.preserveSnapshot(snapshot());
                adopt(reversed);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode reversedJoin =
                    parserCertifiedTransposeJoinReversal(
                            this, childInvocation);
            if (reversedJoin != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(reversedJoin);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode closure = parserCertifiedTransposeClosureCommutation(
                    this, childInvocation);
            if (closure != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(closure);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode restriction = parserCertifiedTransposeRestrictionSwap(
                    this, childInvocation);
            if (restriction != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(restriction);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            EGraphNode distributed = distributeTransposeThroughContainer(
                    childInvocation, child);
            if (distributed != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(distributed);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
        }

        if ((opcode == Opcode.CLOSURE || opcode == Opcode.RCLOSURE)
                && childClasses.size() == 1
                && hasExactRelationArity(this, 2)) {
            EClassRef childInvocation = childClasses.get(0);
            EGraphNode child = childInvocation.getEClass().getRepresentative();
            EGraphNode empty = parserCertifiedEmptyRelationalUnaryIdentity(
                    this, childInvocation);
            if (empty != null) {
                eClass.preserveSnapshot(snapshot());
                adopt(empty);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            if (isIdentityRelation(child)
                    && sameExactRelationOccurrence(this, child)) {
                eClass.preserveSnapshot(snapshot());
                adopt(child);
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
            if ((child.opcode == Opcode.CLOSURE
                            || child.opcode == Opcode.RCLOSURE)
                    && child.childClasses.size() == 1
                    && isExactBinaryRelation(child)) {
                Opcode resultOpcode = opcode == Opcode.RCLOSURE
                                || child.opcode == Opcode.RCLOSURE
                        ? Opcode.RCLOSURE : Opcode.CLOSURE;
                eClass.preserveSnapshot(snapshot());
                if (child.opcode == resultOpcode
                        && sameExactRelationOccurrence(this, child)) {
                    adopt(child);
                } else {
                    opcode = resultOpcode;
                    childClasses = new ArrayList<>(Collections.singletonList(
                            composeInvocation(
                                    childInvocation, child.childClasses.get(0))));
                    isCommutative = false;
                    maxArity = 1;
                    flexibleArity = false;
                    semanticIdentity = null;
                    builtinConstantKind = null;
                    parserSignatureEvidence = null;
                }
                refreshEClassSlots();
                eClass.recordShape(this);
                return true;
            }
        }
        return false;
    }

    @LeanVerifiedRewrite("R0-REL-001")
    private EGraphNode distributeTransposeThroughContainer(
            EClassRef containerInvocation,
            EGraphNode container) {
        EGraphNode distributed = buildTransposeContainerInvocation(
                containerInvocation, container, exactAlloyType);
        if (distributed != null) {
            distributed.preserveSourceOccurrenceLineageFrom(this);
        }
        return distributed;
    }

    private EGraphNode buildTransposeContainerInvocation(
            EClassRef containerInvocation,
            EGraphNode container,
            ExactAlloyType resultType) {
        Opcode containerOpcode = container.opcode;
        boolean aciContainer = containerOpcode == Opcode.PLUS
                || containerOpcode == Opcode.INTERSECT;
        if ((!aciContainer && containerOpcode != Opcode.MINUS)
                || !isExactBinaryRelation(container)
                || (aciContainer && (!container.isSetFlexibleArity()
                        || container.childClasses.size() < 2))
                || (containerOpcode == Opcode.MINUS
                        && container.childClasses.size() != 2)) {
            return null;
        }
        List<EGraphNode> transposedOperands = new ArrayList<>(
                container.childClasses.size());
        for (EClassRef operandInvocation : container.childClasses) {
            EGraphNode transposed = buildNormalizedTransposeInvocation(
                    composeInvocation(containerInvocation, operandInvocation));
            if (transposed == null) {
                return null;
            }
            transposedOperands.add(transposed);
        }
        EGraphNode distributed = inOwningArena(
                this,
                id,
                containerOpcode,
                transposedOperands,
                container.isCommutative,
                container.maxArity,
                container.flexibleArity,
                container.metatype,
                semanticProfile);
        distributed.setSourceName(container.sourceName);
        distributed.setSourceType(container.sourceType);
        distributed.setExactAlloyType(resultType);
        return distributed;
    }

    private EGraphNode buildNormalizedTransposeInvocation(
            EClassRef operandInvocation) {
        EGraphNode operand = operandInvocation.getEClass().getRepresentative();
        if (!isExactBinaryRelation(operand)) {
            return null;
        }
        ExactAlloyType transposeType;
        try {
            transposeType = ExactAlloyType.parserCertifiedTranspose(
                    operand.exactAlloyType);
        } catch (IllegalArgumentException rejectedProof) {
            return null;
        }
        if (!exactAlloyType.sharesParserModuleAuthorityWith(transposeType)) {
            return null;
        }
        if (operand.opcode == Opcode.TRANSPOSE
                && operand.childClasses.size() == 1) {
            EClassRef originalInvocation = composeInvocation(
                    operandInvocation, operand.childClasses.get(0));
            EGraphNode original = originalInvocation.canonical().eClass
                    .getRepresentative();
            if (isExactRelation(original.exactAlloyType)
                    && transposeType.sameOccurrenceEvidenceAs(
                            original.exactAlloyType)) {
                EGraphNode representative =
                        identityInvocationRepresentative(originalInvocation);
                if (representative != null) {
                    return representative;
                }
            }
        }
        if (operand.opcode == Opcode.ARROW
                && operand.childClasses.size() == 2) {
            EGraphNode reversed = inOwningArena(
                    this,
                    id,
                    Opcode.ARROW,
                    Collections.emptyList(),
                    operand.isCommutative,
                    operand.maxArity,
                    operand.flexibleArity,
                    operand.metatype,
                    semanticProfile);
            reversed.setSourceName(operand.sourceName);
            reversed.setSourceType(operand.sourceType);
            reversed.setExactAlloyType(transposeType);
            reversed.addChildInvocation(composeInvocation(
                    operandInvocation, operand.childClasses.get(1)));
            reversed.addChildInvocation(composeInvocation(
                    operandInvocation, operand.childClasses.get(0)));
            return reversed;
        }
        EGraphNode distributed = buildTransposeContainerInvocation(
                operandInvocation, operand, transposeType);
        if (distributed != null) {
            return distributed;
        }
        EGraphNode transposed = inOwningArena(
                this,
                id,
                Opcode.TRANSPOSE,
                Collections.emptyList(),
                false,
                1,
                false,
                metatype,
                semanticProfile);
        transposed.setSourceName(canonicalRelationalSourceName(
                Opcode.TRANSPOSE));
        transposed.setSourceType(operand.sourceType);
        transposed.setExactAlloyType(transposeType);
        transposed.addChildInvocation(operandInvocation);
        EGraphNode restricted = parserCertifiedTransposeRestrictionSwap(
                transposed, operandInvocation);
        if (restricted != null) {
            return restricted;
        }
        return transposed;
    }

    /**
     * Reverses a parser-authenticated chain of exact binary relations under
     * converse. JOIN remains an ordered sequence; only its traversal direction
     * and the converse of each binary operand change.
     */
    @LeanVerifiedRewrite("R0-REL-015")
    static EGraphNode parserCertifiedTransposeJoinReversal(
            EGraphNode owner,
            EClassRef joinInvocation) {
        if (owner == null || joinInvocation == null
                || owner.opcode != Opcode.TRANSPOSE
                || owner.childClasses.size() != 1
                || !isExactBinaryRelation(owner)) {
            return null;
        }
        EClassRef canonicalJoin = joinInvocation.canonical();
        EGraphNode join = canonicalJoin.eClass.getRepresentative();
        if (join.opcode != Opcode.JOIN
                || join.childClasses.size() < 2
                || !isExactBinaryRelation(join)) {
            return null;
        }
        List<EGraphNode> reversed = new ArrayList<>(join.childClasses.size());
        List<ExactAlloyType> reversedTypes = new ArrayList<>(
                join.childClasses.size());
        for (int index = join.childClasses.size() - 1; index >= 0; index--) {
            EClassRef operand = composeInvocation(
                    canonicalJoin, join.childClasses.get(index));
            EGraphNode transposed = owner.buildNormalizedTransposeInvocation(
                    operand);
            if (transposed == null) {
                return null;
            }
            reversed.add(transposed);
            reversedTypes.add(transposed.exactAlloyType);
        }
        try {
            ExactAlloyType derived = reversedTypes.get(0);
            for (int index = 1; index < reversedTypes.size(); index++) {
                derived = ExactAlloyType.parserCertifiedRelationalJoin(
                        derived, reversedTypes.get(index));
            }
            if (!owner.exactAlloyType.sameOccurrenceEvidenceAs(derived)) {
                return null;
            }
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
        EGraphNode result = inOwningArena(
                owner,
                owner.id,
                Opcode.JOIN,
                reversed,
                false,
                join.maxArity,
                join.flexibleArity,
                join.metatype,
                owner.semanticProfile);
        result.sourceName = canonicalRelationalSourceName(Opcode.JOIN);
        result.sourceType = join.sourceType;
        result.exactAlloyType = owner.exactAlloyType;
        result.preserveSourceOccurrenceLineageFrom(owner);
        if (result.childClasses.size() == 2) {
            EGraphNode normalized = parserCertifiedJoinRestrictionNormalization(
                    result,
                    result.childClasses.get(0),
                    result.childClasses.get(1));
            if (normalized != null) {
                return normalized;
            }
        }
        return result;
    }

    /** Moves converse through an exact transitive or reflexive closure. */
    @LeanVerifiedRewrite("R0-REL-016")
    static EGraphNode parserCertifiedTransposeClosureCommutation(
            EGraphNode owner,
            EClassRef closureInvocation) {
        if (owner == null || closureInvocation == null
                || owner.opcode != Opcode.TRANSPOSE
                || owner.childClasses.size() != 1
                || !isExactBinaryRelation(owner)) {
            return null;
        }
        EClassRef canonicalClosure = closureInvocation.canonical();
        EGraphNode closure = canonicalClosure.eClass.getRepresentative();
        if ((closure.opcode != Opcode.CLOSURE
                        && closure.opcode != Opcode.RCLOSURE)
                || closure.childClasses.size() != 1
                || !isExactBinaryRelation(closure)) {
            return null;
        }
        try {
            EClassRef relation = composeInvocation(
                    canonicalClosure, closure.childClasses.get(0));
            EGraphNode transposed = owner.buildNormalizedTransposeInvocation(
                    relation);
            ExactAlloyType transposedClosure =
                    ExactAlloyType.parserCertifiedTranspose(
                            closure.exactAlloyType);
            if (transposed == null
                    || !owner.exactAlloyType.sameOccurrenceEvidenceAs(
                            transposedClosure)) {
                return null;
            }
            EGraphNode result = inOwningArena(
                    owner,
                    owner.id,
                    closure.opcode,
                    Collections.emptyList(),
                    false,
                    1,
                    false,
                    closure.metatype,
                    owner.semanticProfile);
            result.sourceName = closure.sourceName;
            result.sourceType = closure.sourceType;
            result.exactAlloyType = owner.exactAlloyType;
            result.addChildInvocation(transposed.getEClassRef());
            result.preserveSourceOccurrenceLineageFrom(owner);
            return result;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
    }

    /** Converse swaps exact domain and range restriction. */
    @LeanVerifiedRewrite("R0-REL-017")
    static EGraphNode parserCertifiedTransposeRestrictionSwap(
            EGraphNode owner,
            EClassRef restrictionInvocation) {
        if (owner == null || restrictionInvocation == null
                || owner.opcode != Opcode.TRANSPOSE
                || owner.childClasses.size() != 1
                || !isExactBinaryRelation(owner)) {
            return null;
        }
        RestrictionCoordinates restriction =
                parserCertifiedRestrictionCoordinates(restrictionInvocation);
        if (restriction == null
                || !isExactBinaryRelation(restriction.template)) {
            return null;
        }
        try {
            EGraphNode transposed = owner.buildNormalizedTransposeInvocation(
                    restriction.relation);
            if (transposed == null) {
                return null;
            }
            Opcode swapped = restriction.opcode == Opcode.DOMAIN
                    ? Opcode.RANGE : Opcode.DOMAIN;
            EGraphNode result = buildDerivedRestriction(
                    owner,
                    restriction.template,
                    swapped,
                    restriction.restrictor,
                    transposed.getEClassRef(),
                    owner.exactAlloyType);
            result.preserveSourceOccurrenceLineageFrom(owner);
            return result;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
    }

    /** Applies the zero and full-carrier identities of a valid restriction. */
    @LeanVerifiedRewrite("R0-REL-018")
    static EGraphNode parserCertifiedRestrictionIdentityOrZero(
            EGraphNode owner,
            EClassRef restrictionInvocation) {
        RestrictionCoordinates restriction =
                parserCertifiedRestrictionCoordinates(restrictionInvocation);
        if (owner == null || restriction == null
                || !isExactRelationNode(owner)) {
            return null;
        }
        EGraphNode restrictor = restriction.restrictor.canonical()
                .eClass.getRepresentative();
        EGraphNode relation = restriction.relation.canonical()
                .eClass.getRepresentative();
        if (isNone(restrictor) || isNone(relation)) {
            EGraphNode empty = derivedSetConstant(
                    owner, owner.id, "none", owner.exactAlloyType);
            empty.preserveSourceOccurrenceLineageFrom(owner);
            return empty;
        }
        ExactAlloyType carrierType = isUniv(restrictor)
                ? restrictor.exactAlloyType
                : parserCertifiedPrimitiveCarrierTermType(
                        restrictor,
                        Collections.newSetFromMap(new IdentityHashMap<>()));
        if (carrierType == null
                || relation.exactAlloyType == null
                || !owner.exactAlloyType.sameOccurrenceEvidenceAs(
                        relation.exactAlloyType)) {
            return null;
        }
        return identityInvocationRepresentative(restriction.relation);
    }

    /**
     * Canonicalizes endpoint restrictions across one exact relational JOIN.
     * The orientation lifts outer endpoint guards and places a shared middle
     * guard on the right operand, so none of the three rules can cycle.
     */
    @LeanVerifiedRewrite("R0-REL-019")
    static EGraphNode parserCertifiedJoinRestrictionNormalization(
            EGraphNode owner,
            EClassRef leftInvocation,
            EClassRef rightInvocation) {
        if (owner == null || leftInvocation == null || rightInvocation == null
                || owner.opcode != Opcode.JOIN
                || owner.childClasses.size() != 2
                || !isExactRelationNode(owner)) {
            return null;
        }
        EClassRef left = leftInvocation.canonical();
        EClassRef right = rightInvocation.canonical();
        EGraphNode leftNode = left.eClass.getRepresentative();
        EGraphNode rightNode = right.eClass.getRepresentative();
        if (!isExactRelationNode(leftNode) || !isExactRelationNode(rightNode)) {
            return null;
        }
        try {
            ExactAlloyType sourceJoin = ExactAlloyType.parserCertifiedRelationalJoin(
                    leftNode.exactAlloyType, rightNode.exactAlloyType);
            if (!owner.exactAlloyType.sameOccurrenceEvidenceAs(sourceJoin)) {
                return null;
            }

            RestrictionCoordinates leftRestriction =
                    parserCertifiedRestrictionCoordinates(left);
            if (leftRestriction != null
                    && leftRestriction.opcode == Opcode.DOMAIN
                    && exactRelationArity(leftRestriction.relation) >= 2) {
                EGraphNode joined = buildDerivedJoin(
                        owner,
                        owner,
                        leftRestriction.relation,
                        right,
                        null);
                return buildDerivedRestriction(
                        owner,
                        owner,
                        Opcode.DOMAIN,
                        leftRestriction.restrictor,
                        joined.getEClassRef(),
                        owner.exactAlloyType);
            }

            RestrictionCoordinates rightRestriction =
                    parserCertifiedRestrictionCoordinates(right);
            if (rightRestriction != null
                    && rightRestriction.opcode == Opcode.RANGE
                    && exactRelationArity(rightRestriction.relation) >= 2) {
                EGraphNode joined = buildDerivedJoin(
                        owner,
                        owner,
                        left,
                        rightRestriction.relation,
                        null);
                return buildDerivedRestriction(
                        owner,
                        owner,
                        Opcode.RANGE,
                        rightRestriction.restrictor,
                        joined.getEClassRef(),
                        owner.exactAlloyType);
            }

            if (leftRestriction != null
                    && (leftRestriction.opcode == Opcode.RANGE
                            || (leftRestriction.opcode == Opcode.DOMAIN
                                    && exactRelationArity(
                                            leftRestriction.relation) == 1))) {
                EGraphNode restrictedRight = buildDerivedRestriction(
                        owner,
                        owner,
                        Opcode.DOMAIN,
                        leftRestriction.restrictor,
                        right,
                        null);
                return buildDerivedJoin(
                        owner,
                        owner,
                        leftRestriction.relation,
                        restrictedRight.getEClassRef(),
                        owner.exactAlloyType);
            }

            if (rightRestriction != null
                    && rightRestriction.opcode == Opcode.RANGE
                    && exactRelationArity(rightRestriction.relation) == 1) {
                EGraphNode restrictedRight = buildDerivedRestriction(
                        owner,
                        owner,
                        Opcode.DOMAIN,
                        rightRestriction.restrictor,
                        rightRestriction.relation,
                        rightRestriction.template.exactAlloyType);
                return buildDerivedJoin(
                        owner,
                        owner,
                        left,
                        restrictedRight.getEClassRef(),
                        owner.exactAlloyType);
            }
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
        return null;
    }

    /** Resolves reflexive relation comparison atoms under source authority. */
    @LeanVerifiedRewrite("R0-REL-020")
    static Boolean parserCertifiedReflexiveComparison(
            EGraphNode owner,
            List<EClassRef> operands) {
        if (owner == null || operands == null || operands.size() != 2
                || !hasBooleanFormulaType(owner)
                || !sameCertifiedContainerInvocation(
                        operands.get(0), operands.get(1))) {
            return null;
        }
        EGraphNode operand = operands.get(0).canonical()
                .eClass.getRepresentative();
        if (!isParserAuthenticatedSetFamily(operand.exactAlloyType)) {
            return null;
        }
        String expectedName;
        Boolean result;
        switch (owner.opcode) {
            case IN:
                expectedName = "BOP_IN";
                result = true;
                break;
            case EQUALS:
                expectedName = "BOP_EQ";
                result = true;
                break;
            case NOT_IN:
                expectedName = "BOP_NOT_IN";
                result = false;
                break;
            case NOT_EQUALS:
                expectedName = "BOP_NEQ";
                result = false;
                break;
            default:
                return null;
        }
        return expectedName.equals(owner.sourceName)
                        && ("MIDDLENODE_" + expectedName).equals(
                                owner.sourceType)
                ? result : null;
    }

    /** Proves supported structural subset facts and their explicit negations. */
    @LeanVerifiedRewrite("R0-REL-021")
    static Boolean parserCertifiedStructuralSubsetComparison(
            EGraphNode owner,
            List<EClassRef> operands) {
        Boolean negated = parserCertifiedSubsetComparisonNegated(owner);
        if (negated == null || operands == null || operands.size() != 2) {
            return null;
        }
        try {
            if (!parserCertifiedStructuralSubset(
                    operands.get(0), operands.get(1), new HashSet<>())) {
                return null;
            }
            return !negated;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
    }

    /**
     * Expands the exact lattice adjunctions
     * {@code union(xs) in R} and {@code L in intersect(ys)}. Explicit
     * NOT_IN uses the De Morgan dual. The two coordinates may both expand,
     * yielding their complete Cartesian family of subset obligations.
     */
    @LeanVerifiedRewrite("R0-REL-022")
    static EGraphNode parserCertifiedSubsetLatticeExpansion(
            EGraphNode owner,
            List<EClassRef> operands) {
        Boolean negated = parserCertifiedSubsetComparisonNegated(owner);
        if (negated == null || operands == null || operands.size() != 2) {
            return null;
        }
        try {
            EClassRef left = operands.get(0).canonical();
            EClassRef right = operands.get(1).canonical();
            if (!sameParserSetArity(left, right)) {
                return null;
            }
            List<EClassRef> leftTerms = subsetExpansionTerms(
                    left, Opcode.PLUS);
            List<EClassRef> rightTerms = subsetExpansionTerms(
                    right, Opcode.INTERSECT);
            if (leftTerms == null && rightTerms == null) {
                return null;
            }
            if (leftTerms == null) {
                leftTerms = List.of(left);
            }
            if (rightTerms == null) {
                rightTerms = List.of(right);
            }

            List<EClassRef> comparisons = new ArrayList<>();
            for (EClassRef leftTerm : leftTerms) {
                for (EClassRef rightTerm : rightTerms) {
                    if (parserCertifiedStructuralSubset(
                            leftTerm, rightTerm, new HashSet<>())) {
                        continue;
                    }
                    EGraphNode comparison = buildDerivedSubsetComparison(
                            owner, leftTerm, rightTerm);
                    comparisons.add(comparison.getEClassRef());
                }
            }
            if (comparisons.isEmpty()) {
                return derivedBooleanConstant(owner, !negated);
            }
            if (comparisons.size() == 1) {
                return comparisons.get(0).eClass.getRepresentative();
            }
            EGraphNode result = inOwningArena(
                    owner,
                    owner.id,
                    negated ? Opcode.OR : Opcode.AND,
                    Collections.emptyList(),
                    true,
                    -1,
                    true,
                    Metatype.BOOLEAN,
                    owner.semanticProfile);
            result.sourceType = "Bool";
            result.exactAlloyType = ExactAlloyType.boolType();
            for (EClassRef comparison : comparisons) {
                result.addChildInvocation(comparison);
            }
            result.recordDerivedBooleanRewriteAuthority();
            result.preserveSourceOccurrenceLineageFrom(owner);
            return result;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
    }

    /** Converts subset comparison with typed none into relation cardinality. */
    @LeanVerifiedRewrite("R0-REL-023")
    static EGraphNode parserCertifiedEmptyRightSubsetExpansion(
            EGraphNode owner,
            List<EClassRef> operands) {
        Boolean negated = parserCertifiedSubsetComparisonNegated(owner);
        if (negated == null || operands == null || operands.size() != 2) {
            return null;
        }
        try {
            EClassRef left = operands.get(0).canonical();
            EClassRef right = operands.get(1).canonical();
            if (!isNone(right.eClass.getRepresentative())
                    || !sameParserSetArity(left, right)) {
                return null;
            }
            return buildDerivedRelationCardinality(
                    owner, negated ? Opcode.SOME : Opcode.NO, left);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
    }

    /** Removes lattice operands already covered by a certified structural order. */
    @LeanVerifiedRewrite("R0-REL-024")
    static EGraphNode parserCertifiedStructuralLatticeAbsorption(
            EGraphNode owner,
            List<EClassRef> operands) {
        if (owner == null || operands == null || operands.size() < 2
                || (owner.opcode != Opcode.PLUS
                        && owner.opcode != Opcode.INTERSECT)
                || !isCertifiedLatticeOperator(owner)) {
            return null;
        }
        try {
            List<EClassRef> retained = new ArrayList<>(operands.size());
            for (EClassRef operand : operands) {
                retained.add(operand.canonical());
            }
            boolean changed = false;
            boolean reduced;
            do {
                reduced = false;
                outer:
                for (int left = 0; left < retained.size(); left++) {
                    for (int right = 0; right < retained.size(); right++) {
                        if (left == right
                                || sameCertifiedContainerInvocation(
                                        retained.get(left), retained.get(right))
                                || !parserCertifiedStructuralSubset(
                                retained.get(left), retained.get(right),
                                new HashSet<>())) {
                            continue;
                        }
                        int removed = owner.opcode == Opcode.PLUS ? left : right;
                        retained.remove(removed);
                        changed = true;
                        reduced = true;
                        break outer;
                    }
                }
            } while (reduced && retained.size() > 1);
            if (!changed || retained.isEmpty()) {
                return null;
            }
            if (retained.size() == 1) {
                EGraphNode result = retained.get(0).eClass.getRepresentative();
                return owner.exactAlloyType != null
                                && result.exactAlloyType != null
                                && owner.exactAlloyType.sameOccurrenceEvidenceAs(
                                        result.exactAlloyType)
                        ? result : null;
            }
            EClassRef result = buildDerivedRelationLattice(
                    owner, owner.opcode, retained);
            EGraphNode representative = result.eClass.getRepresentative();
            return owner.exactAlloyType != null
                            && owner.exactAlloyType.sameOccurrenceEvidenceAs(
                                    representative.exactAlloyType)
                    ? representative : null;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
    }

    /** Resolves an intersection containing a difference and one of its removals. */
    @LeanVerifiedRewrite("R0-REL-025")
    static EGraphNode parserCertifiedDifferenceDisjointness(
            EGraphNode owner,
            List<EClassRef> operands) {
        if (owner == null || operands == null || operands.size() < 2
                || owner.opcode != Opcode.INTERSECT
                || !isCertifiedLatticeOperator(owner)
                || owner.exactAlloyType == null) {
            return null;
        }
        try {
            for (int differenceIndex = 0;
                    differenceIndex < operands.size(); differenceIndex++) {
                DifferenceCoordinates difference =
                        parserCertifiedDifferenceCoordinates(
                                operands.get(differenceIndex));
                if (difference == null) {
                    continue;
                }
                for (int otherIndex = 0;
                        otherIndex < operands.size(); otherIndex++) {
                    if (otherIndex != differenceIndex
                            && parserCertifiedStructuralSubset(
                                    operands.get(otherIndex), difference.right,
                                    new HashSet<>())) {
                        EGraphNode empty = derivedSetConstant(
                                owner, owner.id, "none", owner.exactAlloyType);
                        empty.preserveSourceOccurrenceLineageFrom(owner);
                        return empty;
                    }
                }
            }
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
        return null;
    }

    /** Recombines the Boolean partition {@code (A-B) + (A&B) = A}. */
    @LeanVerifiedRewrite("R0-REL-026")
    static EGraphNode parserCertifiedDifferencePartitionRecombination(
            EGraphNode owner,
            List<EClassRef> operands) {
        if (owner == null || operands == null || operands.size() < 2
                || owner.opcode != Opcode.PLUS
                || !isCertifiedLatticeOperator(owner)) {
            return null;
        }
        try {
            for (int differenceIndex = 0;
                    differenceIndex < operands.size(); differenceIndex++) {
                DifferenceCoordinates difference =
                        parserCertifiedDifferenceCoordinates(
                                operands.get(differenceIndex));
                if (difference == null) {
                    continue;
                }
                for (int partnerIndex = 0;
                        partnerIndex < operands.size(); partnerIndex++) {
                    if (partnerIndex == differenceIndex
                            || !isCertifiedDifferencePartitionPartner(
                                    operands.get(partnerIndex), difference)) {
                        continue;
                    }
                    List<EClassRef> rewritten = new ArrayList<>(
                            operands.size() - 1);
                    for (int index = 0; index < operands.size(); index++) {
                        if (index != differenceIndex && index != partnerIndex) {
                            rewritten.add(operands.get(index).canonical());
                        }
                    }
                    rewritten.add(difference.left);
                    EGraphNode result;
                    if (rewritten.size() == 1) {
                        result = rewritten.get(0).eClass.getRepresentative();
                    } else {
                        result = buildDerivedRelationLattice(
                                owner, Opcode.PLUS, rewritten)
                                .eClass.getRepresentative();
                    }
                    return owner.exactAlloyType != null
                                    && result.exactAlloyType != null
                                    && owner.exactAlloyType.sameOccurrenceEvidenceAs(
                                            result.exactAlloyType)
                            ? result : null;
                }
            }
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
        return null;
    }

    private static boolean isCertifiedDifferencePartitionPartner(
            EClassRef partnerInvocation,
            DifferenceCoordinates difference) {
        if (sameCertifiedContainerInvocation(
                partnerInvocation, difference.right)
                && parserCertifiedStructuralSubset(
                        difference.right, difference.left, new HashSet<>())) {
            return true;
        }
        List<EClassRef> terms = subsetExpansionTerms(
                partnerInvocation, Opcode.INTERSECT);
        if (terms == null || terms.size() != 2) {
            return false;
        }
        return sameCertifiedContainerInvocation(terms.get(0), difference.left)
                        && sameCertifiedContainerInvocation(
                                terms.get(1), difference.right)
                || sameCertifiedContainerInvocation(terms.get(1), difference.left)
                        && sameCertifiedContainerInvocation(
                                terms.get(0), difference.right);
    }

    /** Normalizes the two exact Boolean partitions headed by relation difference. */
    @LeanVerifiedRewrite("R0-REL-027")
    static EGraphNode parserCertifiedDifferencePartitionNormalization(
            EGraphNode owner,
            EClassRef leftInvocation,
            EClassRef rightInvocation) {
        if (owner == null || leftInvocation == null || rightInvocation == null
                || owner.opcode != Opcode.MINUS
                || owner.childClasses.size() != 2
                || owner.exactAlloyType == null) {
            return null;
        }
        try {
            DifferenceCoordinates source = parserCertifiedDifferenceCoordinates(
                    owner.getEClassRef());
            EClassRef left = leftInvocation.canonical();
            EClassRef right = rightInvocation.canonical();
            if (source == null
                    || !sameCertifiedContainerInvocation(source.left, left)
                    || !sameCertifiedContainerInvocation(source.right, right)) {
                return null;
            }
            if (parserCertifiedStructuralSubset(
                    left, right, new HashSet<>())) {
                EGraphNode empty = derivedSetConstant(
                        owner, owner.id, "none", owner.exactAlloyType);
                empty.preserveSourceOccurrenceLineageFrom(owner);
                return empty;
            }

            List<EClassRef> intersection = subsetExpansionTerms(
                    right, Opcode.INTERSECT);
            if (intersection != null) {
                List<EClassRef> remainder = new ArrayList<>(intersection);
                int leftIndex = indexOfCertifiedInvocation(remainder, left);
                if (leftIndex >= 0) {
                    remainder.remove(leftIndex);
                    if (remainder.isEmpty()) {
                        EGraphNode empty = derivedSetConstant(
                                owner, owner.id, "none", owner.exactAlloyType);
                        empty.preserveSourceOccurrenceLineageFrom(owner);
                        return empty;
                    }
                    EClassRef removed = remainder.size() == 1
                            ? remainder.get(0)
                            : buildDerivedRelationLattice(
                                    owner, Opcode.INTERSECT, remainder);
                    ExactAlloyType exact =
                            ExactAlloyType.parserCertifiedRelationDifference(
                                    left.eClass.getRepresentative().exactAlloyType,
                                    removed.eClass.getRepresentative().exactAlloyType);
                    if (!owner.exactAlloyType.sameOccurrenceEvidenceAs(exact)) {
                        return null;
                    }
                    return buildDerivedBinaryRelation(
                            owner, owner, Opcode.MINUS, left, removed, exact);
                }
            }

            DifferenceCoordinates removedDifference =
                    parserCertifiedDifferenceCoordinates(right);
            if (removedDifference != null
                    && sameCertifiedContainerInvocation(
                            left, removedDifference.left)) {
                EClassRef intersectionResult = buildDerivedRelationLattice(
                        owner,
                        Opcode.INTERSECT,
                        List.of(left, removedDifference.right));
                EGraphNode result = intersectionResult.eClass.getRepresentative();
                return owner.exactAlloyType.sameOccurrenceEvidenceAs(
                                result.exactAlloyType)
                        ? result : null;
            }
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
        return null;
    }

    /** Converts emptiness/nonemptiness of a difference into exact subset atoms. */
    @LeanVerifiedRewrite("R0-REL-028")
    static EGraphNode parserCertifiedDifferenceCardinalityExpansion(
            EGraphNode owner,
            EClassRef relationInvocation) {
        if (owner == null || relationInvocation == null
                || (owner.opcode != Opcode.SOME && owner.opcode != Opcode.NO)
                || !hasBooleanFormulaType(owner)) {
            return null;
        }
        String expectedName = owner.opcode == Opcode.SOME
                ? "UNOPF_SOME" : "UNOPF_NO";
        if (!expectedName.equals(owner.sourceName)
                || !("MIDDLENODE_" + expectedName).equals(owner.sourceType)) {
            return null;
        }
        try {
            DifferenceCoordinates difference =
                    parserCertifiedDifferenceThroughRestrictions(
                            owner, relationInvocation);
            if (difference == null) {
                return null;
            }
            Opcode comparison = owner.opcode == Opcode.NO
                    ? Opcode.IN : Opcode.NOT_IN;
            return buildDerivedRelationComparison(
                    owner, comparison, difference.left, difference.right);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
    }

    private static DifferenceCoordinates parserCertifiedDifferenceThroughRestrictions(
            EGraphNode owner,
            EClassRef relationInvocation) {
        DifferenceCoordinates direct =
                parserCertifiedDifferenceCoordinates(relationInvocation);
        if (direct != null) {
            return direct;
        }
        RestrictionCoordinates restriction =
                parserCertifiedRestrictionCoordinates(relationInvocation);
        if (restriction == null) {
            return null;
        }
        DifferenceCoordinates nested = parserCertifiedDifferenceThroughRestrictions(
                owner, restriction.relation);
        if (nested == null) {
            return null;
        }
        EGraphNode left = buildDerivedRestriction(
                owner,
                restriction.template,
                restriction.opcode,
                restriction.restrictor,
                nested.left,
                null);
        EGraphNode right = buildDerivedRestriction(
                owner,
                restriction.template,
                restriction.opcode,
                restriction.restrictor,
                nested.right,
                null);
        ExactAlloyType differenceType =
                ExactAlloyType.parserCertifiedRelationDifference(
                        left.exactAlloyType, right.exactAlloyType);
        EGraphNode original = relationInvocation.canonical()
                .eClass.getRepresentative();
        return original.exactAlloyType != null
                        && original.exactAlloyType.sameOccurrenceEvidenceAs(
                                differenceType)
                ? new DifferenceCoordinates(
                        left.getEClassRef(), right.getEClassRef(), original)
                : null;
    }

    /**
     * Eliminates one quantified membership atom before prenexing. The caller
     * proves that the declaration has exactly one binder and that the binder
     * occurs only as the comparison's left operand.
     */
    @LeanVerifiedRewrite("R0-BIND-002")
    static EGraphNode parserCertifiedMembershipQuantifierElimination(
            EGraphNode owner,
            Opcode quantifier,
            QuantiVar.Cardinality cardinality,
            EClassRef binderInvocation,
            EClassRef domainInvocation,
            EClassRef memberSetInvocation,
            boolean positiveMembership) {
        if (owner == null || quantifier == null || cardinality == null
                || binderInvocation == null || domainInvocation == null
                || memberSetInvocation == null
                || owner.opcode != quantifier
                || (quantifier != Opcode.FORALL
                        && quantifier != Opcode.EXISTS
                        && quantifier != Opcode.NO)
                || cardinality == QuantiVar.Cardinality.EXACTLY
                || !hasBooleanFormulaType(owner)
                || !sameParserSetArity(binderInvocation, domainInvocation)
                || !sameParserSetArity(binderInvocation, memberSetInvocation)
                || !sameParserSetArity(domainInvocation, memberSetInvocation)
                || !hasParserQuantifierSourceAuthority(owner, quantifier)) {
            return null;
        }
        try {
            boolean admitsEmpty = cardinality == QuantiVar.Cardinality.SET
                    || cardinality == QuantiVar.Cardinality.LONE;
            if (positiveMembership) {
                if (quantifier == Opcode.FORALL) {
                    return buildDerivedRelationComparison(
                            owner, Opcode.IN,
                            domainInvocation, memberSetInvocation);
                }
                if (admitsEmpty) {
                    return derivedBooleanConstant(
                            owner, quantifier == Opcode.EXISTS);
                }
                EClassRef intersection = buildDerivedMembershipRelation(
                        owner, Opcode.INTERSECT,
                        domainInvocation, memberSetInvocation);
                return buildDerivedRelationCardinality(
                        owner,
                        quantifier == Opcode.EXISTS ? Opcode.SOME : Opcode.NO,
                        intersection);
            }

            if (quantifier == Opcode.NO) {
                return buildDerivedRelationComparison(
                        owner, Opcode.IN,
                        domainInvocation, memberSetInvocation);
            }
            if (quantifier == Opcode.FORALL) {
                if (admitsEmpty) {
                    return derivedBooleanConstant(owner, false);
                }
                EClassRef intersection = buildDerivedMembershipRelation(
                        owner, Opcode.INTERSECT,
                        domainInvocation, memberSetInvocation);
                return buildDerivedRelationCardinality(
                        owner, Opcode.NO, intersection);
            }
            EClassRef difference = buildDerivedMembershipRelation(
                    owner, Opcode.MINUS,
                    domainInvocation, memberSetInvocation);
            return buildDerivedRelationCardinality(
                    owner, Opcode.SOME, difference);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
    }

    private static boolean hasParserQuantifierSourceAuthority(
            EGraphNode owner,
            Opcode quantifier) {
        String prefix;
        switch (quantifier) {
            case FORALL:
                prefix = "QT_FORMULA_ALL";
                break;
            case EXISTS:
                prefix = "QT_FORMULA_SOME";
                break;
            case NO:
                prefix = "QT_FORMULA_NO";
                break;
            default:
                return false;
        }
        return owner.sourceName != null
                && owner.sourceName.startsWith(prefix)
                && ("MIDDLENODE_" + owner.sourceName).equals(owner.sourceType);
    }

    private static EClassRef buildDerivedMembershipRelation(
            EGraphNode owner,
            Opcode opcode,
            EClassRef left,
            EClassRef right) {
        if (opcode == Opcode.INTERSECT) {
            EGraphNode result = buildDerivedLatticeContainer(
                    owner,
                    left.canonical().eClass.getRepresentative(),
                    Opcode.INTERSECT,
                    List.of(left.canonical(), right.canonical()));
            result.sourceType = "MIDDLENODE_BOPEXPR_INTERSECT";
            result.preserveSourceOccurrenceLineageFrom(owner);
            return result.getEClassRef();
        }
        if (opcode == Opcode.MINUS) {
            EClassRef canonicalLeft = left.canonical();
            EClassRef canonicalRight = right.canonical();
            ExactAlloyType exact = ExactAlloyType.parserCertifiedRelationDifference(
                    canonicalLeft.eClass.getRepresentative().exactAlloyType,
                    canonicalRight.eClass.getRepresentative().exactAlloyType);
            EGraphNode result = buildDerivedBinaryRelation(
                    owner,
                    canonicalLeft.eClass.getRepresentative(),
                    Opcode.MINUS,
                    canonicalLeft,
                    canonicalRight,
                    exact);
            result.sourceType = "MIDDLENODE_BOPEXPR_MINUS";
            return result.getEClassRef();
        }
        throw new IllegalArgumentException(
                "A membership bridge requires intersection or difference");
    }

    private static EGraphNode buildDerivedRelationCardinality(
            EGraphNode owner,
            Opcode cardinality,
            EClassRef relation) {
        if (cardinality != Opcode.SOME && cardinality != Opcode.NO) {
            throw new IllegalArgumentException(
                    "A derived relation cardinality requires SOME or NO");
        }
        EGraphNode result = inOwningArena(
                owner,
                owner.id,
                cardinality,
                Collections.emptyList(),
                false,
                1,
                false,
                Metatype.BOOLEAN,
                owner.semanticProfile);
        result.sourceName = cardinality == Opcode.SOME
                ? "UNOPF_SOME" : "UNOPF_NO";
        result.sourceType = "MIDDLENODE_" + result.sourceName;
        result.exactAlloyType = ExactAlloyType.boolType();
        result.addChildInvocation(relation);
        result.preserveSourceOccurrenceLineageFrom(owner);
        return result;
    }

    private static Boolean parserCertifiedSubsetComparisonNegated(
            EGraphNode owner) {
        if (owner == null || !hasBooleanFormulaType(owner)) {
            return null;
        }
        String expectedName;
        boolean negated;
        if (owner.opcode == Opcode.IN) {
            expectedName = "BOP_IN";
            negated = false;
        } else if (owner.opcode == Opcode.NOT_IN) {
            expectedName = "BOP_NOT_IN";
            negated = true;
        } else {
            return null;
        }
        return expectedName.equals(owner.sourceName)
                        && ("MIDDLENODE_" + expectedName).equals(
                                owner.sourceType)
                ? negated : null;
    }

    private static boolean parserCertifiedStructuralSubset(
            EClassRef leftInvocation,
            EClassRef rightInvocation,
            Set<String> activePairs) {
        EClassRef left = leftInvocation.canonical();
        EClassRef right = rightInvocation.canonical();
        if (!sameParserSetArity(left, right)) {
            return false;
        }
        String leftKey = certifiedContainerInvocationKey(left);
        String rightKey = certifiedContainerInvocationKey(right);
        if (leftKey.equals(rightKey)) {
            return true;
        }
        EGraphNode leftNode = left.eClass.getRepresentative();
        EGraphNode rightNode = right.eClass.getRepresentative();
        if (isNone(leftNode) || isUniv(rightNode)) {
            return true;
        }
        String pairKey = leftKey.length() + ":" + leftKey + rightKey;
        if (!activePairs.add(pairKey)) {
            return false;
        }

        List<EClassRef> leftUnion = subsetExpansionTerms(left, Opcode.PLUS);
        if (leftUnion != null && allStructurallySubset(
                leftUnion, right, activePairs)) {
            return true;
        }
        List<EClassRef> rightIntersection = subsetExpansionTerms(
                right, Opcode.INTERSECT);
        if (rightIntersection != null && structurallySubsetOfAll(
                left, rightIntersection, activePairs)) {
            return true;
        }
        List<EClassRef> rightUnion = subsetExpansionTerms(right, Opcode.PLUS);
        if (rightUnion != null && structurallySubsetOfAny(
                left, rightUnion, activePairs)) {
            return true;
        }
        List<EClassRef> leftIntersection = subsetExpansionTerms(
                left, Opcode.INTERSECT);
        if (leftIntersection != null && anyStructurallySubset(
                leftIntersection, right, activePairs)) {
            return true;
        }

        EClassRef differenceBase = parserCertifiedDifferenceBase(left);
        if (differenceBase != null && parserCertifiedStructuralSubset(
                differenceBase, right, activePairs)) {
            return true;
        }
        RestrictionCoordinates restriction =
                parserCertifiedRestrictionCoordinates(left);
        return restriction != null && parserCertifiedStructuralSubset(
                restriction.relation, right, activePairs);
    }

    private static boolean allStructurallySubset(
            List<EClassRef> leftTerms,
            EClassRef right,
            Set<String> activePairs) {
        for (EClassRef term : leftTerms) {
            if (!parserCertifiedStructuralSubset(term, right, activePairs)) {
                return false;
            }
        }
        return true;
    }

    private static boolean structurallySubsetOfAll(
            EClassRef left,
            List<EClassRef> rightTerms,
            Set<String> activePairs) {
        for (EClassRef term : rightTerms) {
            if (!parserCertifiedStructuralSubset(left, term, activePairs)) {
                return false;
            }
        }
        return true;
    }

    private static boolean structurallySubsetOfAny(
            EClassRef left,
            List<EClassRef> rightTerms,
            Set<String> activePairs) {
        for (EClassRef term : rightTerms) {
            if (parserCertifiedStructuralSubset(left, term, activePairs)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyStructurallySubset(
            List<EClassRef> leftTerms,
            EClassRef right,
            Set<String> activePairs) {
        for (EClassRef term : leftTerms) {
            if (parserCertifiedStructuralSubset(term, right, activePairs)) {
                return true;
            }
        }
        return false;
    }

    private static List<EClassRef> subsetExpansionTerms(
            EClassRef invocation,
            Opcode opcode) {
        EClassRef canonical = invocation.canonical();
        EGraphNode node = canonical.eClass.getRepresentative();
        if (node.opcode != opcode || !isCertifiedLatticeOperator(node)) {
            return null;
        }
        List<EClassRef> terms = new ArrayList<>();
        collectFlatLatticeOperands(node, canonical, terms);
        terms = semanticDistinctInvocations(terms);
        if (terms.isEmpty()) {
            return null;
        }
        ExactAlloyType derived = derivedLatticeExactType(opcode, terms);
        return node.exactAlloyType.sameOccurrenceEvidenceAs(derived)
                ? terms : null;
    }

    private static EClassRef parserCertifiedDifferenceBase(
            EClassRef invocation) {
        DifferenceCoordinates coordinates =
                parserCertifiedDifferenceCoordinates(invocation);
        return coordinates == null ? null : coordinates.left;
    }

    private static DifferenceCoordinates parserCertifiedDifferenceCoordinates(
            EClassRef invocation) {
        EClassRef canonical = invocation.canonical();
        EGraphNode node = canonical.eClass.getRepresentative();
        if (node.opcode != Opcode.MINUS || node.childClasses.size() != 2
                || !"BOPEXPR_MINUS".equals(node.sourceName)) {
            return null;
        }
        EClassRef left = composeInvocation(canonical, node.childClasses.get(0));
        EClassRef right = composeInvocation(canonical, node.childClasses.get(1));
        ExactAlloyType derived = ExactAlloyType.parserCertifiedRelationDifference(
                left.eClass.getRepresentative().exactAlloyType,
                right.eClass.getRepresentative().exactAlloyType);
        return node.exactAlloyType != null
                        && node.exactAlloyType.sameOccurrenceEvidenceAs(derived)
                ? new DifferenceCoordinates(left, right, node) : null;
    }

    private static int indexOfCertifiedInvocation(
            List<EClassRef> invocations,
            EClassRef target) {
        for (int index = 0; index < invocations.size(); index++) {
            if (sameCertifiedContainerInvocation(invocations.get(index), target)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean sameParserSetArity(
            EClassRef left,
            EClassRef right) {
        ExactAlloyType leftType = left.eClass.getRepresentative().exactAlloyType;
        ExactAlloyType rightType = right.eClass.getRepresentative().exactAlloyType;
        int leftArity = parserAuthenticatedSetArity(leftType);
        return leftArity >= 0
                && leftArity == parserAuthenticatedSetArity(rightType);
    }

    private static int parserAuthenticatedSetArity(ExactAlloyType type) {
        if (!isParserAuthenticatedSetFamily(type)) {
            return -1;
        }
        return type.kind() == ExactAlloyType.Kind.INT
                ? 1 : type.relationArity();
    }

    private static EGraphNode buildDerivedSubsetComparison(
            EGraphNode owner,
            EClassRef left,
            EClassRef right) {
        return buildDerivedRelationComparison(owner, owner.opcode, left, right);
    }

    private static EGraphNode buildDerivedRelationComparison(
            EGraphNode owner,
            Opcode comparison,
            EClassRef left,
            EClassRef right) {
        if (comparison != Opcode.IN && comparison != Opcode.NOT_IN) {
            throw new IllegalArgumentException(
                    "A derived relation comparison requires IN or NOT_IN");
        }
        if (!sameParserSetArity(left, right)) {
            throw new IllegalArgumentException(
                    "A derived subset comparison requires equal relation arity");
        }
        EGraphNode result = inOwningArena(
                owner,
                owner.id,
                comparison,
                Collections.emptyList(),
                false,
                2,
                false,
                Metatype.BOOLEAN,
                owner.semanticProfile);
        result.sourceName = comparison == Opcode.IN ? "BOP_IN" : "BOP_NOT_IN";
        result.sourceType = "MIDDLENODE_" + result.sourceName;
        result.exactAlloyType = ExactAlloyType.boolType();
        result.addChildInvocation(left);
        result.addChildInvocation(right);
        result.preserveSourceOccurrenceLineageFrom(owner);
        return result;
    }

    private static EGraphNode derivedBooleanConstant(
            EGraphNode owner,
            boolean value) {
        EGraphNode result = inOwningArena(
                owner,
                owner.id,
                Opcode.CONSTANT,
                Collections.emptyList(),
                false,
                0,
                false,
                Metatype.BOOLEAN,
                owner.semanticProfile);
        result.sourceName = Boolean.toString(value);
        result.sourceType = "Bool";
        result.exactAlloyType = ExactAlloyType.boolType();
        result.preserveSourceOccurrenceLineageFrom(owner);
        return result;
    }

    /** Unary restriction has only one coordinate; orient RANGE to DOMAIN. */
    @LeanVerifiedRewrite("R0-REL-029")
    static EGraphNode parserCertifiedUnaryRestrictionOrientation(
            EGraphNode owner,
            EClassRef restrictionInvocation) {
        RestrictionCoordinates restriction =
                parserCertifiedRestrictionCoordinates(restrictionInvocation);
        if (owner == null || restriction == null
                || restriction.opcode != Opcode.RANGE
                || exactRelationArity(restriction.relation) != 1) {
            return null;
        }
        try {
            return buildDerivedRestriction(
                    owner,
                    restriction.template,
                    Opcode.DOMAIN,
                    restriction.restrictor,
                    restriction.relation,
                    owner.exactAlloyType);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
    }

    private static int exactRelationArity(EClassRef invocation) {
        ExactAlloyType exact = invocation.canonical().eClass
                .getRepresentative().exactAlloyType;
        if (!isExactRelation(exact)) {
            throw new IllegalArgumentException(
                    "A relational rewrite coordinate requires exact relation arity");
        }
        return exact.relationArity();
    }

    /** Normalizes converse/closure over an authenticated empty binary relation. */
    @LeanVerifiedRewrite("R0-REL-030")
    static EGraphNode parserCertifiedEmptyRelationalUnaryIdentity(
            EGraphNode owner,
            EClassRef childInvocation) {
        if (owner == null || childInvocation == null
                || (owner.opcode != Opcode.TRANSPOSE
                        && owner.opcode != Opcode.CLOSURE
                        && owner.opcode != Opcode.RCLOSURE)
                || !hasExactRelationArity(owner, 2)) {
            return null;
        }
        EGraphNode child = childInvocation.canonical()
                .eClass.getRepresentative();
        if (!isNone(child) || !hasExactRelationArity(child, 2)) {
            return null;
        }
        EGraphNode result = owner.opcode == Opcode.RCLOSURE
                ? derivedIdentityRelation(
                        owner, owner.id, owner.exactAlloyType)
                : derivedSetConstant(
                        owner, owner.id, "none", owner.exactAlloyType);
        result.preserveSourceOccurrenceLineageFrom(owner);
        return result;
    }

    private static EClassRef composeInvocation(
            EClassRef outer,
            EClassRef inner) {
        if (inner.slotMap.isEmpty()) {
            return inner;
        }
        Map<String, String> composed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : inner.slotMap.entrySet()) {
            String target = outer.slotMap.get(entry.getValue());
            if (target == null) {
                throw new IllegalStateException(
                        "A flat splice cannot compose its child invocation map");
            }
            composed.put(entry.getKey(), target);
        }
        return new EClassRef(inner.eClass, composed);
    }

    private void adopt(EGraphNode replacement) {
        if (!semanticProfile.equals(replacement.semanticProfile)) {
            throw new IllegalStateException("Cannot adopt an e-node from another semantic profile");
        }
        opcode = replacement.opcode;
        childClasses = new ArrayList<>(replacement.childClasses);
        isCommutative = replacement.isCommutative;
        maxArity = replacement.maxArity;
        flexibleArity = replacement.flexibleArity;
        sourceName = replacement.sourceName;
        sourceType = replacement.sourceType;
        exactAlloyType = replacement.exactAlloyType;
        alphaName = replacement.alphaName;
        semanticIdentity = replacement.semanticIdentity;
        builtinConstantKind = replacement.builtinConstantKind;
        parserSignatureEvidence = replacement.parserSignatureEvidence;
        sourceOccurrenceLineage = replacement.sourceOccurrenceLineage;
        callOccurrenceId = replacement.callOccurrenceId;
        declaredArity = replacement.declaredArity;
        callArityAuthority = replacement.callArityAuthority;
        derivedBooleanRewriteOpcode = replacement.derivedBooleanRewriteOpcode;
        temporalReferenceAuthorityId = replacement.temporalReferenceAuthorityId;
        temporalSnapshotBinding = replacement.temporalSnapshotBinding;
        metatype = replacement.metatype;
    }

    private void collapseToBooleanConstant(boolean value) {
        opcode = Opcode.CONSTANT;
        childClasses = new ArrayList<>();
        isCommutative = false;
        maxArity = 0;
        flexibleArity = false;
        sourceName = Boolean.toString(value);
        sourceType = "Bool";
        exactAlloyType = ExactAlloyType.boolType();
        alphaName = null;
        semanticIdentity = null;
        builtinConstantKind = null;
        parserSignatureEvidence = null;
        callOccurrenceId = -1L;
        declaredArity = -1;
        callArityAuthority = null;
        derivedBooleanRewriteOpcode = null;
        temporalReferenceAuthorityId = -1L;
        temporalSnapshotBinding = false;
        metatype = Metatype.BOOLEAN;
    }

    private void collapseToSetConstant(String name) {
        opcode = Opcode.GLOBALBINDING;
        childClasses = new ArrayList<>();
        isCommutative = false;
        maxArity = 0;
        flexibleArity = false;
        sourceName = name;
        sourceType = "Signature";
        alphaName = null;
        if ("none".equals(name)) {
            semanticIdentity = SigSymbol.BUILTIN_NONE_IDENTITY;
            builtinConstantKind = BuiltinConstantKind.NONE;
        } else if ("univ".equals(name)) {
            semanticIdentity = SigSymbol.BUILTIN_UNIV_IDENTITY;
            builtinConstantKind = BuiltinConstantKind.UNIV;
        } else {
            semanticIdentity = null;
            builtinConstantKind = null;
        }
        parserSignatureEvidence = null;
        declaredArity = -1;
        callArityAuthority = null;
        derivedBooleanRewriteOpcode = null;
        temporalReferenceAuthorityId = -1L;
        temporalSnapshotBinding = false;
        metatype = Metatype.SET;
    }

    private static Boolean booleanConstantIn(List<EClassRef> children, boolean value) {
        for (EClassRef child : children) {
            EGraphNode representative = child.getEClass().getRepresentative();
            if (isBooleanConstant(representative, value)) {
                return value;
            }
        }
        return null;
    }

    private static List<EClassRef> removeBooleanConstant(List<EClassRef> children, boolean value) {
        List<EClassRef> filtered = new ArrayList<>();
        for (EClassRef child : children) {
            EGraphNode representative = child.getEClass().getRepresentative();
            if (!isBooleanConstant(representative, value)) {
                filtered.add(child);
            }
        }
        return filtered;
    }

    static boolean hasBooleanFormulaType(EGraphNode node) {
        return node != null
                && node.getMetatype() == Metatype.BOOLEAN
                && node.getExactAlloyType() != null
                && node.getExactAlloyType().kind() == ExactAlloyType.Kind.BOOL
                && isBooleanFormulaSourceType(node.getSourceType());
    }

    static boolean hasBooleanRewriteAuthority(EGraphNode node) {
        if (!hasBooleanFormulaType(node)) {
            return false;
        }
        if (node.derivedBooleanRewriteOpcode == node.opcode
                && isBooleanOperatorRequiringAuthority(node.opcode)) {
            return true;
        }
        String sourceType = node.sourceType == null ? "" : node.sourceType.trim();
        if (!sourceType.startsWith("MIDDLENODE_") || node.sourceName == null
                || !sourceType.equals("MIDDLENODE_" + node.sourceName)) {
            return false;
        }
        switch (node.opcode) {
            case NOT:
                return "UNOPF_NOT".equals(node.sourceName);
            case AND:
                return "BOP_AND".equals(node.sourceName)
                        || "LIST_FORMULA_1".equals(node.sourceName);
            case OR:
                return "BOP_OR".equals(node.sourceName)
                        || "LIST_FORMULA_2".equals(node.sourceName);
            case IMPLIES:
                return "BOP_IMPLIES".equals(node.sourceName);
            case IFF:
                return "BOP_IFF".equals(node.sourceName);
            case ITE:
                return "ITE_FORMULA".equals(node.sourceName);
            default:
                return false;
        }
    }

    static boolean hasBooleanOperands(EGraphNode node) {
        if (node == null) {
            return false;
        }
        for (EClassRef child : node.childClasses) {
            if (!hasBooleanOperandType(child.getEClass().getRepresentative())) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasBooleanOperandType(EGraphNode node) {
        if (node == null) {
            return false;
        }
        if (node.opcode == Opcode.VARIABLE
                && node.metatype == Metatype.ATOMIC
                && node.exactAlloyType == null
                && (node.sourceType == null || node.sourceType.isBlank())) {
            // Untyped slots exist only in internal rewrite-pattern fixtures.
            return true;
        }
        if (!hasBooleanFormulaType(node)) {
            return false;
        }
        switch (node.opcode) {
            case CALL:
                CallMetadata.require(node);
                return "call/formula".equals(node.sourceType);
            case NOT:
            case AND:
            case OR:
            case IMPLIES:
            case IFF:
            case ITE:
                return hasBooleanRewriteAuthority(node);
            case CONSTANT:
                return isBooleanConstant(node, true)
                        || isBooleanConstant(node, false);
            case EQUALS:
            case NOT_EQUALS:
            case GT:
            case GTE:
            case IN:
            case LT:
            case LTE:
            case NOT_GT:
            case NOT_GTE:
            case NOT_IN:
            case NOT_LT:
            case NOT_LTE:
            case SOME:
            case NO:
            case LONE:
            case ONE:
            case FORALL:
            case EXISTS:
            case RELEASES:
            case SINCE:
            case TRIGGERED:
            case UNTIL:
            case BEFORE:
            case HISTORICALLY:
            case ONCE:
            case ALWAYS:
            case EVENTUALLY:
            case AFTER:
            case PREDICATE:
            case TEMPORALROOT:
            case REF:
            case LET:
            case DISJOINT:
            case DISJOINT_LIST:
                return true;
            default:
                return false;
        }
    }

    static boolean isBooleanConstant(EGraphNode node, boolean value) {
        return node != null
                && node.getOpcode() == Opcode.CONSTANT
                && hasBooleanFormulaType(node)
                && isBooleanSourceType(node.getSourceType())
                && Boolean.toString(value).equals(node.getSourceName());
    }

    private static boolean isBooleanSourceType(String sourceType) {
        if (sourceType == null) {
            return false;
        }
        String normalized = sourceType.trim();
        return "bool".equalsIgnoreCase(normalized)
                || "boolean".equalsIgnoreCase(normalized);
    }

    private static boolean isBooleanFormulaSourceType(String sourceType) {
        if (isBooleanSourceType(sourceType)) {
            return true;
        }
        return sourceType != null
                && (sourceType.trim().startsWith("MIDDLENODE_")
                        || "call/formula".equals(sourceType.trim())
                        || "predroot".equals(sourceType.trim()));
    }

    private static boolean isBooleanRewriteOperator(Opcode opcode) {
        return opcode == Opcode.NOT
                || opcode == Opcode.AND
                || opcode == Opcode.OR
                || opcode == Opcode.IMPLIES
                || opcode == Opcode.IFF;
    }

    private static boolean isBooleanOperatorRequiringAuthority(Opcode opcode) {
        return isBooleanRewriteOperator(opcode) || opcode == Opcode.ITE;
    }

    private static boolean containsSetConstant(List<EClassRef> children, String name) {
        for (EClassRef child : children) {
            if (isSetConstant(child.getEClass().getRepresentative(), name)) {
                return true;
            }
        }
        return false;
    }

    private static List<EClassRef> removeSetConstant(List<EClassRef> children, String name) {
        List<EClassRef> filtered = new ArrayList<>();
        for (EClassRef child : children) {
            if (!isSetConstant(child.getEClass().getRepresentative(), name)) {
                filtered.add(child);
            }
        }
        return filtered;
    }

    private static boolean isNone(EGraphNode node) {
        return isSetConstant(node, "none");
    }

    private static boolean isUniv(EGraphNode node) {
        return isSetConstant(node, "univ");
    }

    static boolean isSetConstant(EGraphNode node, String name) {
        if (node == null) {
            return false;
        }
        node.requireLiveNode();
        String expectedIdentity = "none".equals(name)
                ? SigSymbol.BUILTIN_NONE_IDENTITY
                : "univ".equals(name) ? SigSymbol.BUILTIN_UNIV_IDENTITY : null;
        BuiltinConstantKind expectedKind = "none".equals(name)
                ? BuiltinConstantKind.NONE
                : "univ".equals(name) ? BuiltinConstantKind.UNIV : null;
        return (node.getOpcode() == Opcode.GLOBALBINDING || node.getOpcode() == Opcode.CONSTANT)
                && expectedIdentity != null
                && expectedKind == node.builtinConstantKind
                && expectedIdentity.equals(node.getSemanticIdentity())
                && name.equals(node.getSourceName())
                && "Signature".equals(node.getSourceType())
                && node.getMetatype() == Metatype.SET
                && node.childClasses.isEmpty();
    }

    static boolean isIdentityRelation(EGraphNode node) {
        if (node == null) {
            return false;
        }
        node.requireLiveNode();
        ExactAlloyType exact = node.exactAlloyType;
        return node.opcode == Opcode.CONSTANT
                && node.builtinConstantKind == BuiltinConstantKind.IDEN
                && ConstSymbol.BUILTIN_IDEN_IDENTITY.equals(node.semanticIdentity)
                && "iden".equals(node.sourceName)
                && "iden".equals(node.sourceType)
                && node.metatype == Metatype.ATOMIC
                && node.childClasses.isEmpty()
                && exact != null
                && exact.kind() == ExactAlloyType.Kind.RELATION
                && exact.relationArity() == 2;
    }

    /**
     * True only when {@code carrier} is the full parser-owned user signature
     * named by the source leaf and {@code candidate}'s exact relation family is
     * contained in that signature through the same parser module's ancestry.
     */
    static boolean isParserCertifiedSubrelationOfFullSignature(
            EGraphNode candidate,
            EGraphNode carrier) {
        if (candidate == null || carrier == null
                || candidate.arena != carrier.arena
                || !candidate.semanticProfile.equals(carrier.semanticProfile)
                || !isParserAuthenticatedFullSignature(carrier)) {
            return false;
        }
        if (containsTemporalSnapshotBinding(candidate)
                && carrier.parserSignatureEvidence.isParserVariableSignature()) {
            return false;
        }
        if (candidate.parserSignatureEvidence != null) {
            return !candidate.parserSignatureEvidence.isSameParserSignatureAs(
                            carrier.parserSignatureEvidence)
                    && candidate.parserSignatureEvidence
                            .isParserCertifiedSubsignatureOf(
                                    carrier.parserSignatureEvidence);
        }

        // Exact primitive-column ancestry can prove containment under a
        // primitive carrier. It cannot prove containment under a subset
        // signature because Alloy erases that declaration boundary from Type.
        ExactAlloyType carrierType = carrier.exactAlloyType;
        if (!isParserAuthenticatedPrimitiveCarrier(carrier, carrierType)) {
            return false;
        }
        ExactAlloyType candidateType = candidate.exactAlloyType;
        return candidateType != null
                && candidateType.isParserCertifiedRelationSubfamilyOf(carrierType);
    }

    /**
     * Proves that {@code candidate} is contained by the denotation of a closed
     * full-carrier term. A subset-signature leaf uses its declaration DAG;
     * composite carrier proofs deliberately admit only primitive signature
     * leaves because Alloy's static Type erases subset-signature boundaries.
     */
    static boolean isParserCertifiedSubrelationOfFullCarrier(
            EGraphNode candidate,
            EGraphNode carrier) {
        if (candidate == null || carrier == null
                || candidate.arena != carrier.arena
                || !candidate.semanticProfile.equals(carrier.semanticProfile)) {
            return false;
        }
        if (containsTemporalSnapshotBinding(candidate)
                && containsParserVariableSignature(carrier)) {
            return false;
        }
        if (isParserAuthenticatedFullSignature(carrier)) {
            return isParserCertifiedSubrelationOfFullSignature(
                    candidate, carrier);
        }
        ExactAlloyType carrierType = parserCertifiedPrimitiveCarrierTermType(
                carrier,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        ExactAlloyType candidateType = candidate.exactAlloyType;
        return carrierType != null
                && candidateType != null
                && candidateType.isParserCertifiedSetSubfamilyOf(carrierType);
    }

    private static boolean containsTemporalSnapshotBinding(EGraphNode root) {
        return containsTemporalSnapshotBinding(
                root, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean containsTemporalSnapshotBinding(
            EGraphNode node,
            Set<EClass> active) {
        if (!active.add(node.eClass)) {
            return false;
        }
        try {
            for (EGraphNode alternative : node.eClass.nodes) {
                if (alternative.temporalSnapshotBinding) {
                    return true;
                }
                for (EClassRef child : alternative.childClasses) {
                    if (containsTemporalSnapshotBinding(
                            child.eClass.getRepresentative(), active)) {
                        return true;
                    }
                }
            }
            return false;
        } finally {
            active.remove(node.eClass);
        }
    }

    private static boolean containsParserVariableSignature(EGraphNode root) {
        return containsParserVariableSignature(
                root, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean containsParserVariableSignature(
            EGraphNode node,
            Set<EClass> active) {
        if (!active.add(node.eClass)) {
            return false;
        }
        try {
            for (EGraphNode alternative : node.eClass.nodes) {
                if (alternative.parserSignatureEvidence != null
                        && alternative.parserSignatureEvidence
                                .isParserVariableSignature()) {
                    return true;
                }
                for (EClassRef child : alternative.childClasses) {
                    if (containsParserVariableSignature(
                            child.eClass.getRepresentative(), active)) {
                        return true;
                    }
                }
            }
            return false;
        } finally {
            active.remove(node.eClass);
        }
    }

    private static ExactAlloyType parserCertifiedPrimitiveCarrierTermType(
            EGraphNode node,
            Set<EClass> active) {
        if (node == null || !node.getEClass().getSlots().isEmpty()) {
            return null;
        }
        if (isParserAuthenticatedPrimitiveCarrier(node, node.exactAlloyType)) {
            return node.exactAlloyType;
        }
        if (node.opcode != Opcode.ARROW && node.opcode != Opcode.PLUS) {
            return null;
        }
        if ((node.opcode == Opcode.ARROW && node.childClasses.size() != 2)
                || (node.opcode == Opcode.PLUS && !node.isSetFlexibleArity())
                || node.childClasses.size() < 2
                || !active.add(node.eClass)) {
            return null;
        }
        try {
            List<ExactAlloyType> factorTypes = new ArrayList<>(
                    node.childClasses.size());
            for (EClassRef child : node.childClasses) {
                EClassRef invocation = child.canonical();
                if (!invocation.slotMap.isEmpty()) {
                    return null;
                }
                ExactAlloyType factorType =
                        parserCertifiedPrimitiveCarrierTermType(
                                invocation.eClass.getRepresentative(), active);
                if (factorType == null) {
                    return null;
                }
                factorTypes.add(factorType);
            }
            try {
                return node.opcode == Opcode.ARROW
                        ? ExactAlloyType.parserCertifiedCartesianProduct(
                                factorTypes)
                        : ExactAlloyType.parserCertifiedRelationUnion(
                                factorTypes);
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                return null;
            }
        } finally {
            active.remove(node.eClass);
        }
    }

    /**
     * Derives an abstract carrier only when full parser-owned signature leaves
     * cover every direct extension branch and every union operand is certified
     * below that carrier.
     */
    @LeanVerifiedRewrite("R0-REL-031")
    static EGraphNode parserCertifiedAbstractUnionCarrier(
            EGraphNode owner,
            List<EGraphNode> operands) {
        if (owner == null || operands == null || operands.isEmpty()) {
            return null;
        }
        List<EGraphNode> terminals = new ArrayList<>();
        for (EGraphNode operand : operands) {
            collectSameProfilePlusTerminals(owner, operand, terminals);
        }
        List<SigSymbol> fullSignatures = new ArrayList<>();
        for (EGraphNode terminal : terminals) {
            if (isParserAuthenticatedFullSignature(terminal)) {
                fullSignatures.add(terminal.parserSignatureEvidence);
            }
        }
        SigSymbol carrier = SigSymbol.parserCertifiedAbstractCover(fullSignatures);
        if (carrier == null) {
            return null;
        }
        for (EGraphNode terminal : terminals) {
            if (!isParserCertifiedWithinSignature(terminal, carrier)) {
                return null;
            }
        }
        if (terminals.size() == 1
                && terminals.get(0).parserSignatureEvidence != null
                && terminals.get(0).parserSignatureEvidence
                        .isSameParserSignatureAs(carrier)) {
            return null;
        }
        return derivedParserSignature(owner, owner.getId(), carrier);
    }

    /**
     * Factors unions of plain Cartesian products one coordinate at a time.
     * A subgroup may vary in one slot-free coordinate while every other
     * coordinate is the same invocation; a simultaneous reduction requires
     * the complete Cartesian grid. Coordinate unions retain their operands,
     * while parser-certified subtype and abstract covers may choose a smaller
     * proven carrier. Diagonal and partial grids cannot synthesize cross terms.
     */
    @LeanVerifiedRewrite("R0-REL-002")
    static EGraphNode parserCertifiedProductUnionCarrier(
            EGraphNode owner,
            List<EClassRef> operands) {
        if (owner == null || operands == null || operands.size() < 2
                || owner.opcode != Opcode.PLUS
                || !owner.isSetFlexibleArity()
                || !isExactRelation(owner.exactAlloyType)) {
            return null;
        }
        List<EClassRef> terminals = new ArrayList<>();
        for (EClassRef operand : operands) {
            collectSameProfilePlusTerminals(owner, operand, terminals);
        }
        if (terminals.size() < 2) {
            return null;
        }

        EGraphNode template = terminals.get(0).getEClass().getRepresentative();
        List<List<EClassRef>> terminalFactors = new ArrayList<>(
                terminals.size());
        List<EClassRef> templateFactors = plainProductFactorInvocations(
                terminals.get(0));
        if (templateFactors == null) {
            return null;
        }
        terminalFactors.add(templateFactors);
        int factorCount = templateFactors.size();
        for (EClassRef terminalInvocation : terminals) {
            EGraphNode terminal = terminalInvocation.getEClass()
                    .getRepresentative();
            List<EClassRef> factors = terminalInvocation == terminals.get(0)
                    ? templateFactors
                    : plainProductFactorInvocations(terminalInvocation);
            if (factors == null
                    || factors.size() != factorCount
                    || terminal.metatype != template.metatype
                    || !terminal.getArityPolicy().equals(
                            template.getArityPolicy())
                    || terminal.getSiblingQuotient()
                            != template.getSiblingQuotient()
                    || !terminal.getFlatLicense().equals(
                            template.getFlatLicense())) {
                return null;
            }
            if (terminal != template) {
                terminalFactors.add(factors);
            }
        }

        EGraphNode subgroupReduction = parserCertifiedProductSubgroupReduction(
                owner, terminals, terminalFactors, template);
        if (subgroupReduction != null) {
            return subgroupReduction;
        }

        List<ProductCoordinateCover> coordinateCovers = new ArrayList<>(
                factorCount);
        boolean changesProduct = false;
        for (int coordinate = 0; coordinate < factorCount; coordinate++) {
            List<EClassRef> coordinateFactors = new ArrayList<>(
                    terminals.size());
            for (List<EClassRef> productFactors : terminalFactors) {
                coordinateFactors.add(productFactors.get(coordinate));
            }
            ProductCoordinateCover cover = parserCertifiedProductCoordinateCover(
                    owner, coordinateFactors);
            if (cover == null) {
                return null;
            }
            coordinateCovers.add(cover);
            changesProduct |= cover.changesCoordinate;
        }
        if (!changesProduct
                || !containsCompleteProductGrid(
                        terminalFactors, coordinateCovers)) {
            return null;
        }

        List<EClassRef> factors = new ArrayList<>(factorCount);
        List<ExactAlloyType> factorTypes = new ArrayList<>(factorCount);
        for (ProductCoordinateCover cover : coordinateCovers) {
            factors.add(cover.carrier);
            factorTypes.add(cover.carrier.getEClass().getRepresentative()
                    .exactAlloyType);
        }
        ExactAlloyType productType;
        try {
            productType = ExactAlloyType.parserCertifiedCartesianProduct(
                    factorTypes);
        } catch (IllegalArgumentException rejectedProof) {
            return null;
        }
        if (!owner.exactAlloyType
                .isParserCertifiedRelationSubfamilyOf(productType)) {
            throw new IllegalStateException(
                    "An abstract product cover failed to contain its source union");
        }
        EGraphNode product = buildDerivedPlainProduct(
                owner, template, factors, 0);
        if (!productType.equals(product.exactAlloyType)) {
            throw new IllegalStateException(
                    "A derived abstract product disagrees with its flat exact type proof");
        }
        product.preserveSourceOccurrenceLineageFrom(owner);
        return product;
    }

    /**
     * Factors an intersection subgroup of equal-length Cartesian products by
     * intersecting every coordinate independently. Unlike union, intersection
     * needs no complete Cartesian grid: tuple membership is conjunctive in
     * every product and every source branch.
     */
    @LeanVerifiedRewrite("R0-REL-003")
    static EGraphNode parserCertifiedProductIntersectionFactoring(
            EGraphNode owner,
            List<EClassRef> operands) {
        if (owner == null || operands == null || operands.size() < 2
                || owner.opcode != Opcode.INTERSECT
                || !isCertifiedLatticeOperator(owner)) {
            return null;
        }
        List<Integer> productIndices = new ArrayList<>();
        List<List<EClassRef>> productFactors = new ArrayList<>();
        EGraphNode template = null;
        int factorCount = -1;
        for (int index = 0; index < operands.size(); index++) {
            EClassRef invocation = operands.get(index).canonical();
            EGraphNode product = invocation.eClass.getRepresentative();
            List<EClassRef> factors = plainProductFactorInvocations(invocation);
            if (factors == null) {
                continue;
            }
            if (template == null) {
                template = product;
                factorCount = factors.size();
            } else if (factors.size() != factorCount
                    || !template.semanticProfile.equals(product.semanticProfile)
                    || !template.getArityPolicy().equals(
                            product.getArityPolicy())
                    || template.getSiblingQuotient()
                            != product.getSiblingQuotient()
                    || !template.getFlatLicense().equals(
                            product.getFlatLicense())) {
                continue;
            }
            productIndices.add(index);
            productFactors.add(factors);
        }
        if (productIndices.size() < 2) {
            return null;
        }
        try {
            List<EClassRef> factoredCoordinates = new ArrayList<>(factorCount);
            for (int coordinate = 0; coordinate < factorCount; coordinate++) {
                List<EClassRef> coordinateOperands = new ArrayList<>(
                        productFactors.size());
                for (List<EClassRef> factors : productFactors) {
                    coordinateOperands.add(factors.get(coordinate));
                }
                factoredCoordinates.add(buildDerivedRelationLattice(
                        owner, Opcode.INTERSECT, coordinateOperands));
            }
            EGraphNode product = buildDerivedPlainProduct(
                    owner,
                    Objects.requireNonNull(template, "product template"),
                    factoredCoordinates,
                    0);
            EGraphNode result = replaceFactoredGroup(
                    owner,
                    operands,
                    productIndices,
                    product.getEClassRef());
            if (!owner.exactAlloyType.sameOccurrenceEvidenceAs(
                    result.exactAlloyType)) {
                return null;
            }
            result.preserveSourceOccurrenceLineageFrom(owner);
            return result;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
    }

    /**
     * Factors parser-authenticated DOMAIN/RANGE applications through the
     * relational lattice. Union is factored only along one fixed coordinate;
     * repeated applications therefore factor a complete grid without ever
     * inventing diagonal cross terms. Intersection is coordinatewise.
     */
    @LeanVerifiedRewrite("R0-REL-012")
    static EGraphNode parserCertifiedRestrictionLatticeFactoring(
            EGraphNode owner,
            List<EClassRef> operands) {
        if (owner == null || operands == null || operands.size() < 2
                || (owner.opcode != Opcode.PLUS
                        && owner.opcode != Opcode.INTERSECT)
                || !isCertifiedLatticeOperator(owner)) {
            return null;
        }
        List<EClassRef> flattenedOperands = new ArrayList<>();
        for (EClassRef operand : operands) {
            collectFlatDerivedRelationLatticeOperands(
                    owner, operand, flattenedOperands);
        }
        operands = flattenedOperands;
        for (Opcode restrictionOpcode : List.of(Opcode.DOMAIN, Opcode.RANGE)) {
            List<Integer> indices = new ArrayList<>();
            List<RestrictionCoordinates> restrictions = new ArrayList<>();
            for (int index = 0; index < operands.size(); index++) {
                RestrictionCoordinates coordinates =
                        parserCertifiedRestrictionCoordinates(
                                operands.get(index));
                if (coordinates != null
                        && coordinates.opcode == restrictionOpcode) {
                    indices.add(index);
                    restrictions.add(coordinates);
                }
            }
            if (restrictions.size() < 2) {
                continue;
            }
            try {
                if (owner.opcode == Opcode.INTERSECT) {
                    List<EClassRef> restrictors = new ArrayList<>(
                            restrictions.size());
                    List<EClassRef> relations = new ArrayList<>(
                            restrictions.size());
                    for (RestrictionCoordinates restriction : restrictions) {
                        restrictors.add(restriction.restrictor);
                        relations.add(restriction.relation);
                    }
                    EClassRef restrictor = buildDerivedRelationLattice(
                            owner, Opcode.INTERSECT, restrictors);
                    EClassRef relation = buildDerivedRelationLattice(
                            owner, Opcode.INTERSECT, relations);
                    EGraphNode replacement = buildDerivedRestriction(
                            owner,
                            restrictions.get(0).template,
                            restrictionOpcode,
                            restrictor,
                            relation,
                            null);
                    EGraphNode result = replaceFactoredGroup(
                            owner,
                            operands,
                            indices,
                            replacement.getEClassRef());
                    if (!owner.exactAlloyType.sameOccurrenceEvidenceAs(
                            result.exactAlloyType)) {
                        return null;
                    }
                    result.preserveSourceOccurrenceLineageFrom(owner);
                    return result;
                }

                List<List<EClassRef>> grid = new ArrayList<>(
                        restrictions.size());
                for (RestrictionCoordinates restriction : restrictions) {
                    grid.add(List.of(
                            restriction.restrictor, restriction.relation));
                }
                List<ProductCoordinateCover> covers = new ArrayList<>(2);
                for (int coordinate = 0; coordinate < 2; coordinate++) {
                    List<EClassRef> coordinateOperands = new ArrayList<>(
                            restrictions.size());
                    for (RestrictionCoordinates restriction : restrictions) {
                        coordinateOperands.add(restriction.coordinate(coordinate));
                    }
                    ProductCoordinateCover cover =
                            parserCertifiedProductCoordinateCover(
                                    owner, coordinateOperands);
                    if (cover == null) {
                        covers.clear();
                        break;
                    }
                    covers.add(cover);
                }
                if (covers.size() == 2
                        && (covers.get(0).changesCoordinate
                                || covers.get(1).changesCoordinate)
                        && containsCompleteProductGrid(grid, covers)) {
                    EGraphNode replacement = buildDerivedRestriction(
                            owner,
                            restrictions.get(0).template,
                            restrictionOpcode,
                            covers.get(0).carrier,
                            covers.get(1).carrier,
                            null);
                    EGraphNode result = replaceFactoredGroup(
                            owner,
                            operands,
                            indices,
                            replacement.getEClassRef());
                    if (!owner.exactAlloyType.sameOccurrenceEvidenceAs(
                            result.exactAlloyType)) {
                        return null;
                    }
                    result.preserveSourceOccurrenceLineageFrom(owner);
                    return result;
                }

                // A union subgroup is sound when one coordinate is fixed.
                // Iterating this reduction recognizes every complete grid.
                for (int varying = 0; varying < 2; varying++) {
                    int fixed = 1 - varying;
                    for (int anchor = 0;
                            anchor < restrictions.size(); anchor++) {
                        List<Integer> subgroup = new ArrayList<>();
                        for (int candidate = 0;
                                candidate < restrictions.size(); candidate++) {
                            if (sameCertifiedContainerInvocation(
                                    restrictions.get(anchor).coordinate(fixed),
                                    restrictions.get(candidate).coordinate(fixed))) {
                                subgroup.add(candidate);
                            }
                        }
                        if (subgroup.size() < 2) {
                            continue;
                        }
                        List<EClassRef> varyingOperands = new ArrayList<>(
                                subgroup.size());
                        List<Integer> sourceIndices = new ArrayList<>(
                                subgroup.size());
                        for (int member : subgroup) {
                            varyingOperands.add(
                                    restrictions.get(member).coordinate(varying));
                            sourceIndices.add(indices.get(member));
                        }
                        EClassRef varyingUnion = buildDerivedRelationLattice(
                                owner, Opcode.PLUS, varyingOperands);
                        RestrictionCoordinates anchorRestriction =
                                restrictions.get(anchor);
                        EClassRef restrictor = varying == 0
                                ? varyingUnion : anchorRestriction.restrictor;
                        EClassRef relation = varying == 1
                                ? varyingUnion : anchorRestriction.relation;
                        EGraphNode replacement = buildDerivedRestriction(
                                owner,
                                anchorRestriction.template,
                                restrictionOpcode,
                                restrictor,
                                relation,
                                null);
                        EGraphNode result = replaceFactoredGroup(
                                owner,
                                operands,
                                sourceIndices,
                                replacement.getEClassRef());
                        if (!owner.exactAlloyType.sameOccurrenceEvidenceAs(
                                result.exactAlloyType)) {
                            return null;
                        }
                        result.preserveSourceOccurrenceLineageFrom(owner);
                        return result;
                    }
                }
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                // A missing parser capability or incompatible exact column is
                // a proof failure, not permission for a structural rewrite.
            }
        }
        return null;
    }

    /** Factors a restriction difference only when exactly one coordinate changes. */
    @LeanVerifiedRewrite("R0-REL-013")
    static EGraphNode parserCertifiedRestrictionDifferenceFactoring(
            EGraphNode owner,
            EClassRef leftInvocation,
            EClassRef rightInvocation) {
        if (owner == null || leftInvocation == null || rightInvocation == null
                || owner.opcode != Opcode.MINUS
                || owner.childClasses.size() != 2
                || !isExactRelationNode(owner)) {
            return null;
        }
        RestrictionCoordinates left = parserCertifiedRestrictionCoordinates(
                leftInvocation);
        RestrictionCoordinates right = parserCertifiedRestrictionCoordinates(
                rightInvocation);
        if (left == null || right == null || left.opcode != right.opcode) {
            return null;
        }
        boolean restrictorDiffers = !sameCertifiedContainerInvocation(
                left.restrictor, right.restrictor);
        boolean relationDiffers = !sameCertifiedContainerInvocation(
                left.relation, right.relation);
        if (restrictorDiffers == relationDiffers) {
            return null;
        }
        try {
            EClassRef changingLeft = restrictorDiffers
                    ? left.restrictor : left.relation;
            EClassRef changingRight = restrictorDiffers
                    ? right.restrictor : right.relation;
            ExactAlloyType changingType =
                    ExactAlloyType.parserCertifiedRelationDifference(
                            changingLeft.eClass.getRepresentative().exactAlloyType,
                            changingRight.eClass.getRepresentative().exactAlloyType);
            EGraphNode coordinateDifference = buildDerivedBinaryRelation(
                    owner,
                    changingLeft.eClass.getRepresentative(),
                    Opcode.MINUS,
                    changingLeft,
                    changingRight,
                    changingType);
            EClassRef restrictor = restrictorDiffers
                    ? coordinateDifference.getEClassRef() : left.restrictor;
            EClassRef relation = relationDiffers
                    ? coordinateDifference.getEClassRef() : left.relation;
            return buildDerivedRestriction(
                    owner,
                    left.template,
                    left.opcode,
                    restrictor,
                    relation,
                    owner.exactAlloyType);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
    }

    /**
     * Combines nested same-side restrictions and chooses RANGE(DOMAIN(R)) as
     * the unique orientation for commuting opposite-side restrictions.
     */
    @LeanVerifiedRewrite("R0-REL-014")
    static EGraphNode parserCertifiedNestedRestriction(
            EGraphNode owner,
            EClassRef firstChild,
            EClassRef secondChild) {
        if (owner == null || firstChild == null || secondChild == null
                || (owner.opcode != Opcode.DOMAIN
                        && owner.opcode != Opcode.RANGE)
                || owner.childClasses.size() != 2) {
            return null;
        }
        RestrictionCoordinates outer = parserCertifiedRestrictionCoordinates(
                owner.getEClassRef());
        if (outer == null) {
            return null;
        }
        RestrictionCoordinates inner = parserCertifiedRestrictionCoordinates(
                outer.relation);
        if (inner == null) {
            return null;
        }
        try {
            if (outer.opcode == inner.opcode) {
                EClassRef intersection = buildDerivedRelationLattice(
                        owner,
                        Opcode.INTERSECT,
                        List.of(outer.restrictor, inner.restrictor));
                return buildDerivedRestriction(
                        owner,
                        owner,
                        outer.opcode,
                        intersection,
                        inner.relation,
                        owner.exactAlloyType);
            }
            if (outer.opcode == Opcode.DOMAIN
                    && inner.opcode == Opcode.RANGE) {
                EGraphNode domain = buildDerivedRestriction(
                        owner,
                        owner,
                        Opcode.DOMAIN,
                        outer.restrictor,
                        inner.relation,
                        null);
                return buildDerivedRestriction(
                        owner,
                        owner,
                        Opcode.RANGE,
                        inner.restrictor,
                        domain.getEClassRef(),
                        owner.exactAlloyType);
            }
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
        return null;
    }

    private static RestrictionCoordinates parserCertifiedRestrictionCoordinates(
            EClassRef invocation) {
        EClassRef canonical = invocation == null ? null : invocation.canonical();
        EGraphNode node = canonical == null
                ? null : canonical.eClass.getRepresentative();
        if (node == null
                || (node.opcode != Opcode.DOMAIN
                        && node.opcode != Opcode.RANGE)
                || node.childClasses.size() != 2
                || node.exactAlloyType == null) {
            return null;
        }
        EClassRef restrictor = composeInvocation(
                canonical,
                node.childClasses.get(node.opcode == Opcode.DOMAIN ? 0 : 1));
        EClassRef relation = composeInvocation(
                canonical,
                node.childClasses.get(node.opcode == Opcode.DOMAIN ? 1 : 0));
        try {
            ExactAlloyType derived = deriveRestrictionType(
                    node.opcode, restrictor, relation);
            if (!node.exactAlloyType.sameOccurrenceEvidenceAs(derived)) {
                return null;
            }
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
        return new RestrictionCoordinates(
                node.opcode, restrictor, relation, node);
    }

    private static EGraphNode buildDerivedRestriction(
            EGraphNode owner,
            EGraphNode template,
            Opcode opcode,
            EClassRef restrictor,
            EClassRef relation,
            ExactAlloyType expectedType) {
        ExactAlloyType exact = deriveRestrictionType(
                opcode, restrictor, relation);
        if (expectedType != null
                && !expectedType.sameOccurrenceEvidenceAs(exact)) {
            throw new IllegalArgumentException(
                    "A derived restriction disagrees with its source occurrence type");
        }
        EGraphNode result = inOwningArena(
                owner,
                owner.id,
                opcode,
                Collections.emptyList(),
                false,
                2,
                false,
                Metatype.SET,
                owner.semanticProfile);
        result.sourceName = canonicalRelationalSourceName(opcode);
        result.sourceType = template.sourceType;
        result.exactAlloyType = exact;
        if (opcode == Opcode.DOMAIN) {
            result.addChildInvocation(restrictor);
            result.addChildInvocation(relation);
        } else if (opcode == Opcode.RANGE) {
            result.addChildInvocation(relation);
            result.addChildInvocation(restrictor);
        } else {
            throw new IllegalArgumentException(
                    "A derived restriction requires DOMAIN or RANGE");
        }
        result.preserveSourceOccurrenceLineageFrom(owner);
        return result;
    }

    private static ExactAlloyType deriveRestrictionType(
            Opcode opcode,
            EClassRef restrictor,
            EClassRef relation) {
        ExactAlloyType restrictorType = restrictor.canonical().eClass
                .getRepresentative().exactAlloyType;
        ExactAlloyType relationType = relation.canonical().eClass
                .getRepresentative().exactAlloyType;
        if (opcode == Opcode.DOMAIN) {
            return ExactAlloyType.parserCertifiedDomainRestriction(
                    restrictorType, relationType);
        }
        if (opcode == Opcode.RANGE) {
            return ExactAlloyType.parserCertifiedRangeRestriction(
                    relationType, restrictorType);
        }
        throw new IllegalArgumentException(
                "A restriction type requires DOMAIN or RANGE");
    }

    /**
     * Canonicalizes the two distributive lattices used by Alloy formulas and
     * relations. Absorption projects an identity invocation; expanded terms
     * are oriented toward a factored form, which strictly reduces duplicated
     * common operands and therefore cannot alternate with expansion.
     */
    @LeanVerifiedRewrite("R0-REL-004")
    static EGraphNode parserCertifiedLatticeNormalForm(
            EGraphNode owner,
            List<EClassRef> operands) {
        if (owner == null || operands == null || operands.size() < 2
                || !isCertifiedLatticeOperator(owner)) {
            return null;
        }
        Opcode innerOpcode = latticeDual(owner.opcode);
        if (innerOpcode == null) {
            return null;
        }

        for (int candidateIndex = 0;
                candidateIndex < operands.size();
                candidateIndex++) {
            EClassRef candidate = operands.get(candidateIndex).canonical();
            for (int containerIndex = 0;
                    containerIndex < operands.size();
                    containerIndex++) {
                if (candidateIndex == containerIndex) {
                    continue;
                }
                EClassRef containerInvocation = operands.get(containerIndex)
                        .canonical();
                EGraphNode container = containerInvocation.getEClass()
                        .getRepresentative();
                if (!isCertifiedLatticeOperator(container)
                        || container.opcode != innerOpcode) {
                    continue;
                }
                List<EClassRef> nested = new ArrayList<>();
                collectFlatLatticeOperands(
                        container, containerInvocation, nested);
                if (containsSemanticInvocation(nested, candidate)) {
                    EGraphNode identityRepresentative =
                            identityInvocationRepresentative(candidate);
                    if (identityRepresentative != null) {
                        // Absorption removes only the matched dual container.
                        List<EClassRef> retained = new ArrayList<>(
                                operands.size() - 1);
                        for (int operandIndex = 0;
                                operandIndex < operands.size();
                                operandIndex++) {
                            if (operandIndex != containerIndex) {
                                retained.add(operands.get(operandIndex).canonical());
                            }
                        }
                        if (retained.size() == 1) {
                            return identityRepresentative;
                        }
                        EGraphNode result = buildDerivedLatticeContainer(
                                owner,
                                owner,
                                owner.opcode,
                                retained);
                        result.preserveSourceOccurrenceLineageFrom(owner);
                        return result;
                    }
                }
            }
        }

        List<List<EClassRef>> branches = new ArrayList<>(operands.size());
        EGraphNode innerTemplate = null;
        for (EClassRef branchInvocation : operands) {
            EClassRef canonical = branchInvocation.canonical();
            EGraphNode branch = canonical.getEClass().getRepresentative();
            if (!isCertifiedLatticeOperator(branch)
                    || branch.opcode != innerOpcode) {
                return null;
            }
            if (innerTemplate == null) {
                innerTemplate = branch;
            }
            List<EClassRef> terms = new ArrayList<>();
            collectFlatLatticeOperands(branch, canonical, terms);
            if (terms.size() < 2) {
                return null;
            }
            branches.add(terms);
        }

        List<EClassRef> common = semanticDistinctInvocations(branches.get(0));
        common.removeIf(candidate -> branches.stream().skip(1)
                .anyMatch(branch -> !containsSemanticInvocation(
                        branch, candidate)));
        if (common.isEmpty()) {
            return null;
        }

        List<EClassRef> remainderBranches = new ArrayList<>(branches.size());
        for (List<EClassRef> branch : branches) {
            List<EClassRef> remainder = new ArrayList<>(branch);
            for (EClassRef commonTerm : common) {
                removeFirstSemanticInvocation(remainder, commonTerm);
            }
            if (remainder.isEmpty()) {
                return null;
            }
            if (remainder.size() == 1) {
                remainderBranches.add(remainder.get(0));
            } else {
                remainderBranches.add(buildDerivedLatticeContainer(
                        owner,
                        innerTemplate,
                        innerOpcode,
                        remainder).getEClassRef());
            }
        }

        EGraphNode outerRemainder = buildDerivedLatticeContainer(
                owner,
                owner,
                owner.opcode,
                remainderBranches);
        List<EClassRef> factored = new ArrayList<>(common);
        factored.add(outerRemainder.getEClassRef());
        EGraphNode result = buildDerivedLatticeContainer(
                owner,
                innerTemplate,
                innerOpcode,
                factored);
        result.preserveSourceOccurrenceLineageFrom(owner);
        return result;
    }

    /** Factors the four Boolean-algebra distributivity laws for set difference. */
    @LeanVerifiedRewrite("R0-REL-005")
    static EGraphNode parserCertifiedDifferenceFactoring(
            EGraphNode owner,
            List<EClassRef> operands) {
        if (owner == null || operands == null || operands.size() < 2
                || (owner.opcode != Opcode.PLUS
                        && owner.opcode != Opcode.INTERSECT)
                || !isCertifiedLatticeOperator(owner)) {
            return null;
        }
        List<List<EClassRef>> differences = new ArrayList<>(operands.size());
        for (EClassRef branchInvocation : operands) {
            EClassRef canonical = branchInvocation.canonical();
            EGraphNode branch = canonical.eClass.getRepresentative();
            if (branch.opcode != Opcode.MINUS
                    || branch.childClasses.size() != 2
                    || !isExactRelationNode(branch)) {
                differences.add(null);
                continue;
            }
            differences.add(List.of(
                    composeInvocation(canonical, branch.childClasses.get(0)),
                    composeInvocation(canonical, branch.childClasses.get(1))));
        }
        EGraphNode commonRight = factorDifferenceGroup(
                owner, operands, differences, 1);
        return commonRight != null
                ? commonRight
                : factorDifferenceGroup(owner, operands, differences, 0);
    }

    /**
     * Factors a difference of Cartesian products when exactly one coordinate
     * changes. Multiple changing coordinates are deliberately retained because
     * subtracting one product does not subtract the Cartesian product of all
     * coordinate differences.
     */
    @LeanVerifiedRewrite("R0-REL-011")
    static EGraphNode parserCertifiedProductDifferenceFactoring(
            EGraphNode owner,
            EClassRef leftInvocation,
            EClassRef rightInvocation) {
        if (owner == null || leftInvocation == null || rightInvocation == null
                || owner.opcode != Opcode.MINUS
                || owner.childClasses.size() != 2
                || !isExactRelationNode(owner)) {
            return null;
        }
        EClassRef canonicalLeft = leftInvocation.canonical();
        EClassRef canonicalRight = rightInvocation.canonical();
        EGraphNode leftProduct = canonicalLeft.eClass.getRepresentative();
        EGraphNode rightProduct = canonicalRight.eClass.getRepresentative();
        List<EClassRef> leftFactors = plainProductFactorInvocations(
                canonicalLeft);
        List<EClassRef> rightFactors = plainProductFactorInvocations(
                canonicalRight);
        if (leftFactors == null || rightFactors == null
                || leftFactors.size() != rightFactors.size()
                || !leftProduct.semanticProfile.equals(rightProduct.semanticProfile)
                || !leftProduct.getArityPolicy().equals(
                        rightProduct.getArityPolicy())
                || leftProduct.getSiblingQuotient()
                        != rightProduct.getSiblingQuotient()
                || !leftProduct.getFlatLicense().equals(
                        rightProduct.getFlatLicense())) {
            return null;
        }
        int differingCoordinate = -1;
        for (int coordinate = 0; coordinate < leftFactors.size(); coordinate++) {
            if (sameCertifiedContainerInvocation(
                    leftFactors.get(coordinate), rightFactors.get(coordinate))) {
                continue;
            }
            if (differingCoordinate >= 0) {
                return null;
            }
            differingCoordinate = coordinate;
        }
        if (differingCoordinate < 0) {
            return null;
        }
        EClassRef leftFactor = leftFactors.get(differingCoordinate);
        EClassRef rightFactor = rightFactors.get(differingCoordinate);
        EGraphNode leftFactorNode = leftFactor.eClass.getRepresentative();
        EGraphNode rightFactorNode = rightFactor.eClass.getRepresentative();
        if (!isParserAuthenticatedSetFamily(leftFactorNode.exactAlloyType)
                || !isParserAuthenticatedSetFamily(
                        rightFactorNode.exactAlloyType)) {
            return null;
        }
        try {
            ExactAlloyType factorType =
                    ExactAlloyType.parserCertifiedRelationDifference(
                            leftFactorNode.exactAlloyType,
                            rightFactorNode.exactAlloyType);
            EGraphNode factorDifference = buildDerivedBinaryRelation(
                    owner,
                    leftFactorNode,
                    Opcode.MINUS,
                    leftFactor,
                    rightFactor,
                    factorType);
            List<EClassRef> factoredCoordinates = new ArrayList<>(leftFactors);
            factoredCoordinates.set(
                    differingCoordinate, factorDifference.getEClassRef());
            EGraphNode factoredProduct = buildDerivedPlainProduct(
                    owner, leftProduct, factoredCoordinates, 0);
            if (!owner.exactAlloyType.sameOccurrenceEvidenceAs(
                    factoredProduct.exactAlloyType)) {
                return null;
            }
            factoredProduct.preserveSourceOccurrenceLineageFrom(owner);
            return factoredProduct;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
    }

    private static EGraphNode factorDifferenceGroup(
            EGraphNode owner,
            List<EClassRef> operands,
            List<List<EClassRef>> differences,
            int commonCoordinate) {
        for (int anchor = 0; anchor < differences.size(); anchor++) {
            List<EClassRef> anchorDifference = differences.get(anchor);
            if (anchorDifference == null) {
                continue;
            }
            List<Integer> group = new ArrayList<>();
            for (int candidate = 0; candidate < differences.size(); candidate++) {
                List<EClassRef> candidateDifference = differences.get(candidate);
                if (candidateDifference != null
                        && sameCertifiedContainerInvocation(
                                anchorDifference.get(commonCoordinate),
                                candidateDifference.get(commonCoordinate))) {
                    group.add(candidate);
                }
            }
            if (group.size() < 2) {
                continue;
            }
            int varyingCoordinate = 1 - commonCoordinate;
            List<EClassRef> varying = new ArrayList<>(group.size());
            List<EClassRef> originalBranches = new ArrayList<>(group.size());
            for (int member : group) {
                varying.add(differences.get(member).get(varyingCoordinate));
                originalBranches.add(operands.get(member));
            }
            Opcode innerOpcode = commonCoordinate == 1
                    ? owner.opcode : latticeDual(owner.opcode);
            try {
                EGraphNode inner = buildDerivedLatticeContainer(
                        owner, owner, innerOpcode, varying);
                ExactAlloyType resultType = derivedLatticeExactType(
                        owner.opcode, originalBranches);
                EClassRef common = anchorDifference.get(commonCoordinate);
                EGraphNode factored = commonCoordinate == 0
                        ? buildDerivedBinaryRelation(
                                owner,
                                operands.get(anchor).eClass.getRepresentative(),
                                Opcode.MINUS,
                                common,
                                inner.getEClassRef(),
                                resultType)
                        : buildDerivedBinaryRelation(
                                owner,
                                operands.get(anchor).eClass.getRepresentative(),
                                Opcode.MINUS,
                                inner.getEClassRef(),
                                common,
                                resultType);
                return replaceFactoredGroup(
                        owner, operands, group, factored.getEClassRef());
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                return null;
            }
        }
        return null;
    }

    /** Accumulates a left-nested difference into one certified removal union. */
    @LeanVerifiedRewrite("R0-REL-008")
    static EGraphNode parserCertifiedLeftNestedDifference(
            EGraphNode owner,
            EClassRef leftInvocation,
            EClassRef rightInvocation) {
        if (owner == null || leftInvocation == null || rightInvocation == null
                || owner.opcode != Opcode.MINUS
                || owner.childClasses.size() != 2
                || !isExactRelationNode(owner)) {
            return null;
        }
        EClassRef canonicalLeft = leftInvocation.canonical();
        EGraphNode left = canonicalLeft.eClass.getRepresentative();
        if (left.opcode != Opcode.MINUS
                || left.childClasses.size() != 2
                || !isExactRelationNode(left)
                || !sameExactRelationOccurrence(owner, left)) {
            return null;
        }
        EClassRef base = composeInvocation(
                canonicalLeft, left.childClasses.get(0));
        EClassRef priorRemoval = composeInvocation(
                canonicalLeft, left.childClasses.get(1));
        EClassRef nextRemoval = rightInvocation.canonical();
        if (!isExactRelationNode(base.eClass.getRepresentative())
                || !isExactRelationNode(
                        priorRemoval.eClass.getRepresentative())
                || !isExactRelationNode(nextRemoval.eClass.getRepresentative())) {
            return null;
        }
        try {
            EClassRef combinedRemoval = buildDerivedRelationUnion(
                    owner, List.of(priorRemoval, nextRemoval));
            EGraphNode result = buildDerivedBinaryRelation(
                    owner,
                    owner,
                    Opcode.MINUS,
                    base,
                    combinedRemoval,
                    owner.exactAlloyType);
            result.preserveSourceOccurrenceLineageFrom(owner);
            return result;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
    }

    /** Expands a right-nested difference by its pointwise Boolean identity. */
    @LeanVerifiedRewrite("R0-REL-009")
    static EGraphNode parserCertifiedRightNestedDifference(
            EGraphNode owner,
            EClassRef leftInvocation,
            EClassRef rightInvocation) {
        if (owner == null || leftInvocation == null || rightInvocation == null
                || owner.opcode != Opcode.MINUS
                || owner.childClasses.size() != 2
                || !isExactRelationNode(owner)) {
            return null;
        }
        EClassRef left = leftInvocation.canonical();
        EClassRef canonicalRight = rightInvocation.canonical();
        EGraphNode right = canonicalRight.eClass.getRepresentative();
        if (right.opcode != Opcode.MINUS
                || right.childClasses.size() != 2
                || !isExactRelationNode(right)
                || !isExactRelationNode(left.eClass.getRepresentative())) {
            return null;
        }
        EClassRef removed = composeInvocation(
                canonicalRight, right.childClasses.get(0));
        EClassRef restored = composeInvocation(
                canonicalRight, right.childClasses.get(1));
        if (!isExactRelationNode(removed.eClass.getRepresentative())
                || !isExactRelationNode(restored.eClass.getRepresentative())) {
            return null;
        }
        try {
            EGraphNode retainedDifference = buildDerivedBinaryRelation(
                    owner,
                    owner,
                    Opcode.MINUS,
                    left,
                    removed,
                    owner.exactAlloyType);
            EClassRef restoredIntersection = buildDerivedRelationLattice(
                    owner, Opcode.INTERSECT, List.of(left, restored));
            EClassRef expansion = buildDerivedRelationUnion(
                    owner,
                    List.of(
                            retainedDifference.getEClassRef(),
                            restoredIntersection));
            ExactAlloyType expansionType = expansion.eClass
                    .getRepresentative().exactAlloyType;
            if (!owner.exactAlloyType.sameOccurrenceEvidenceAs(expansionType)) {
                return null;
            }
            EGraphNode result = expansion.eClass.getRepresentative();
            result.preserveSourceOccurrenceLineageFrom(owner);
            return result;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
    }

    /** Extracts every difference branch from one certified intersection. */
    @LeanVerifiedRewrite("R0-REL-010")
    static EGraphNode parserCertifiedIntersectionDifferenceExtraction(
            EGraphNode owner,
            List<EClassRef> operands) {
        if (owner == null || operands == null || operands.size() < 2
                || owner.opcode != Opcode.INTERSECT
                || !isCertifiedLatticeOperator(owner)) {
            return null;
        }
        List<EClassRef> kept = new ArrayList<>(operands.size());
        List<EClassRef> removed = new ArrayList<>();
        for (EClassRef operandInvocation : operands) {
            EClassRef canonical = operandInvocation.canonical();
            EGraphNode operand = canonical.eClass.getRepresentative();
            if (operand.opcode == Opcode.MINUS
                    && operand.childClasses.size() == 2
                    && isExactRelationNode(operand)) {
                EClassRef keptOperand = composeInvocation(
                        canonical, operand.childClasses.get(0));
                EClassRef removedOperand = composeInvocation(
                        canonical, operand.childClasses.get(1));
                if (!isExactRelationNode(
                                keptOperand.eClass.getRepresentative())
                        || !isExactRelationNode(
                                removedOperand.eClass.getRepresentative())) {
                    return null;
                }
                kept.add(keptOperand);
                removed.add(removedOperand);
            } else {
                kept.add(canonical);
            }
        }
        if (removed.isEmpty()) {
            return null;
        }
        try {
            EClassRef keptIntersection = kept.size() == 1
                    ? kept.get(0)
                    : buildDerivedRelationLattice(
                            owner, Opcode.INTERSECT, kept);
            EClassRef removedUnion = removed.size() == 1
                    ? removed.get(0)
                    : buildDerivedRelationUnion(owner, removed);
            ExactAlloyType resultType = derivedLatticeExactType(
                    Opcode.INTERSECT, operands);
            if (!owner.exactAlloyType.sameOccurrenceEvidenceAs(resultType)) {
                return null;
            }
            EGraphNode result = buildDerivedBinaryRelation(
                    owner,
                    owner,
                    Opcode.MINUS,
                    keptIntersection,
                    removedUnion,
                    resultType);
            result.preserveSourceOccurrenceLineageFrom(owner);
            return result;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
    }

    private static EClassRef buildDerivedRelationUnion(
            EGraphNode owner,
            List<EClassRef> operands) {
        return buildDerivedRelationLattice(owner, Opcode.PLUS, operands);
    }

    private static EClassRef buildDerivedRelationLattice(
            EGraphNode owner,
            Opcode opcode,
            List<EClassRef> operands) {
        if (opcode != Opcode.PLUS && opcode != Opcode.INTERSECT) {
            throw new IllegalArgumentException(
                    "A derived relation lattice requires union or intersection");
        }
        List<EClassRef> canonicalOperands = new ArrayList<>(operands.size());
        for (EClassRef operand : operands) {
            canonicalOperands.add(operand.canonical());
        }
        EGraphNode provisional = newDerivedRelationLattice(
                owner, opcode, canonicalOperands);
        List<EClassRef> flattened = new ArrayList<>();
        for (EClassRef operand : provisional.childClasses) {
            collectFlatDerivedRelationLatticeOperands(
                    provisional, operand, flattened);
        }
        flattened = semanticDistinctInvocations(flattened);
        if (flattened.size() == 1) {
            return flattened.get(0);
        }
        return sameChildren(provisional.childClasses, flattened)
                ? provisional.getEClassRef()
                : newDerivedRelationLattice(
                        owner, opcode, flattened).getEClassRef();
    }

    /**
     * Flattens a parser-certified relation lattice before the pre-ACI source
     * snapshot is taken. Intersection may narrow the parser's exact static
     * family at each binary source node, so ordinary flat-instance equality is
     * intentionally too strict here. The live parser capability, module,
     * arity, operator policy, and profile still have to agree.
     */
    private static void collectFlatDerivedRelationLatticeOperands(
            EGraphNode operator,
            EClassRef invocation,
            List<EClassRef> operands) {
        EClassRef canonical = invocation.canonical();
        EGraphNode node = canonical.getEClass().getRepresentative();
        if (sameParserCertifiedRelationLatticeInstance(operator, node)) {
            for (EClassRef child : node.childClasses) {
                collectFlatDerivedRelationLatticeOperands(
                        operator,
                        composeInvocation(canonical, child),
                        operands);
            }
            return;
        }
        operands.add(canonical);
    }

    private static boolean sameParserCertifiedRelationLatticeInstance(
            EGraphNode left,
            EGraphNode right) {
        if (left == null || right == null
                || left.arena != right.arena
                || left.opcode != right.opcode
                || (left.opcode != Opcode.PLUS
                        && left.opcode != Opcode.INTERSECT)
                || !left.hasFlatLicense()
                || !right.hasFlatLicense()
                || !left.getArityPolicy().equals(right.getArityPolicy())
                || left.getSiblingQuotient() != right.getSiblingQuotient()
                || !left.getFlatLicense().equals(right.getFlatLicense())
                || !left.getUnitLicense().equals(right.getUnitLicense())
                || !left.semanticProfile.equals(right.semanticProfile)
                || left.metatype != right.metatype) {
            return false;
        }
        ExactAlloyType leftType = left.exactAlloyType;
        ExactAlloyType rightType = right.exactAlloyType;
        return leftType != null
                && rightType != null
                && leftType.kind() == ExactAlloyType.Kind.RELATION
                && rightType.kind() == ExactAlloyType.Kind.RELATION
                && leftType.relationArity() == rightType.relationArity()
                && leftType.sharesParserModuleAuthorityWith(rightType);
    }

    private static EGraphNode newDerivedRelationLattice(
            EGraphNode owner,
            Opcode opcode,
            List<EClassRef> operands) {
        ExactAlloyType exactType = derivedLatticeExactType(
                opcode, operands);
        EGraphNode lattice = inOwningArena(
                owner,
                owner.id,
                opcode,
                Collections.emptyList(),
                true,
                -1,
                true,
                Metatype.SET,
                owner.semanticProfile);
        lattice.sourceType = owner.sourceType;
        lattice.sourceName = canonicalRelationalSourceName(opcode);
        lattice.exactAlloyType = exactType;
        for (EClassRef operand : operands) {
            lattice.addChildInvocation(operand);
        }
        lattice.preserveSourceOccurrenceLineageFrom(owner);
        return lattice;
    }

    /** Factors relational composition over a same-side union. */
    @LeanVerifiedRewrite("R0-REL-006")
    static EGraphNode parserCertifiedJoinUnionFactoring(
            EGraphNode owner,
            List<EClassRef> operands) {
        if (owner == null || operands == null || operands.size() < 2
                || owner.opcode != Opcode.PLUS
                || !isCertifiedLatticeOperator(owner)) {
            return null;
        }
        List<EClassRef> flattened = new ArrayList<>();
        for (EClassRef operand : operands) {
            collectFlatLatticeOperands(owner, operand, flattened);
        }
        operands = flattened;
        List<List<EClassRef>> joins = new ArrayList<>(operands.size());
        for (EClassRef branchInvocation : operands) {
            EClassRef canonical = branchInvocation.canonical();
            EGraphNode branch = canonical.eClass.getRepresentative();
            if (branch.opcode != Opcode.JOIN
                    || branch.childClasses.size() != 2
                    || !isExactRelationNode(branch)) {
                joins.add(null);
                continue;
            }
            joins.add(List.of(
                    composeInvocation(canonical, branch.childClasses.get(0)),
                    composeInvocation(canonical, branch.childClasses.get(1))));
        }
        EGraphNode commonLeft = factorJoinGroup(owner, operands, joins, 0);
        return commonLeft != null
                ? commonLeft : factorJoinGroup(owner, operands, joins, 1);
    }

    private static EGraphNode factorJoinGroup(
            EGraphNode owner,
            List<EClassRef> operands,
            List<List<EClassRef>> joins,
            int commonCoordinate) {
        for (int anchor = 0; anchor < joins.size(); anchor++) {
            List<EClassRef> anchorJoin = joins.get(anchor);
            if (anchorJoin == null) {
                continue;
            }
            List<Integer> group = new ArrayList<>();
            for (int candidate = 0; candidate < joins.size(); candidate++) {
                List<EClassRef> candidateJoin = joins.get(candidate);
                if (candidateJoin != null
                        && sameCertifiedContainerInvocation(
                                anchorJoin.get(commonCoordinate),
                                candidateJoin.get(commonCoordinate))) {
                    group.add(candidate);
                }
            }
            if (group.size() < 2) {
                continue;
            }
            int varyingCoordinate = 1 - commonCoordinate;
            List<EClassRef> varying = new ArrayList<>(group.size());
            List<EClassRef> originalBranches = new ArrayList<>(group.size());
            for (int member : group) {
                varying.add(joins.get(member).get(varyingCoordinate));
                originalBranches.add(operands.get(member));
            }
            try {
                EGraphNode union = buildDerivedLatticeContainer(
                        owner, owner, Opcode.PLUS, varying);
                EClassRef left = commonCoordinate == 0
                        ? anchorJoin.get(0) : union.getEClassRef();
                EClassRef right = commonCoordinate == 0
                        ? union.getEClassRef() : anchorJoin.get(1);
                ExactAlloyType resultType = derivedLatticeExactType(
                        Opcode.PLUS, originalBranches);
                ExactAlloyType derivedJoin =
                        ExactAlloyType.parserCertifiedRelationalJoin(
                                left.eClass.getRepresentative().exactAlloyType,
                                right.eClass.getRepresentative().exactAlloyType);
                if (!resultType.sameOccurrenceEvidenceAs(derivedJoin)) {
                    continue;
                }
                EGraphNode factored = buildDerivedBinaryRelation(
                        owner,
                        operands.get(anchor).eClass.getRepresentative(),
                        Opcode.JOIN,
                        left,
                        right,
                        derivedJoin);
                return replaceFactoredGroup(
                        owner, operands, group, factored.getEClassRef());
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                return null;
            }
        }
        return null;
    }

    /** Distributes relational-union nonemptiness/emptiness into Boolean ACI form. */
    @LeanVerifiedRewrite("R0-REL-007")
    static EGraphNode parserCertifiedUnionCardinalityExpansion(
            EGraphNode owner,
            EClassRef relationInvocation) {
        if (owner == null || relationInvocation == null
                || (owner.opcode != Opcode.SOME && owner.opcode != Opcode.NO)
                || !hasBooleanFormulaType(owner)) {
            return null;
        }
        EClassRef canonical = relationInvocation.canonical();
        EGraphNode union = canonical.eClass.getRepresentative();
        if (union.opcode != Opcode.PLUS
                || !isCertifiedLatticeOperator(union)) {
            return null;
        }
        List<EClassRef> terms = new ArrayList<>();
        collectFlatLatticeOperands(union, canonical, terms);
        terms = semanticDistinctInvocations(terms);
        if (terms.isEmpty()) {
            return null;
        }
        try {
            ExactAlloyType derivedUnion = derivedLatticeExactType(
                    Opcode.PLUS, terms);
            if (!union.exactAlloyType.sameOccurrenceEvidenceAs(derivedUnion)) {
                return null;
            }
            List<EClassRef> cardinalities = new ArrayList<>(terms.size());
            for (EClassRef term : terms) {
                EGraphNode cardinality = inOwningArena(
                        owner,
                        owner.id,
                        owner.opcode,
                        Collections.emptyList(),
                        false,
                        1,
                        false,
                        Metatype.BOOLEAN,
                        owner.semanticProfile);
                cardinality.sourceName = owner.sourceName;
                cardinality.sourceType = owner.sourceType;
                cardinality.exactAlloyType = ExactAlloyType.boolType();
                cardinality.addChildInvocation(term);
                cardinality.preserveSourceOccurrenceLineageFrom(owner);
                cardinalities.add(cardinality.getEClassRef());
            }
            if (cardinalities.size() == 1) {
                return cardinalities.get(0).eClass.getRepresentative();
            }
            Opcode booleanOpcode = owner.opcode == Opcode.SOME
                    ? Opcode.OR : Opcode.AND;
            EGraphNode expanded = inOwningArena(
                    owner,
                    owner.id,
                    booleanOpcode,
                    Collections.emptyList(),
                    true,
                    -1,
                    true,
                    Metatype.BOOLEAN,
                    owner.semanticProfile);
            expanded.sourceType = "Bool";
            expanded.exactAlloyType = ExactAlloyType.boolType();
            for (EClassRef cardinality : cardinalities) {
                expanded.addChildInvocation(cardinality);
            }
            expanded.recordDerivedBooleanRewriteAuthority();
            expanded.preserveSourceOccurrenceLineageFrom(owner);
            EGraphNode normalized = parserCertifiedLatticeNormalForm(
                    expanded, expanded.childClasses);
            return normalized == null ? expanded : normalized;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return null;
        }
    }

    private static EGraphNode buildDerivedBinaryRelation(
            EGraphNode owner,
            EGraphNode template,
            Opcode opcode,
            EClassRef left,
            EClassRef right,
            ExactAlloyType exactType) {
        EGraphNode result = inOwningArena(
                owner,
                owner.id,
                opcode,
                Collections.emptyList(),
                false,
                2,
                false,
                Metatype.SET,
                owner.semanticProfile);
        result.sourceName = canonicalRelationalSourceName(opcode);
        result.sourceType = template.sourceType;
        result.exactAlloyType = exactType;
        result.addChildInvocation(left);
        result.addChildInvocation(right);
        result.preserveSourceOccurrenceLineageFrom(owner);
        return result;
    }

    private static EGraphNode buildDerivedJoin(
            EGraphNode owner,
            EGraphNode template,
            EClassRef left,
            EClassRef right,
            ExactAlloyType expectedType) {
        ExactAlloyType exact = ExactAlloyType.parserCertifiedRelationalJoin(
                left.canonical().eClass.getRepresentative().exactAlloyType,
                right.canonical().eClass.getRepresentative().exactAlloyType);
        if (expectedType != null
                && !expectedType.sameOccurrenceEvidenceAs(exact)) {
            throw new IllegalArgumentException(
                    "A derived JOIN disagrees with its source occurrence type");
        }
        return buildDerivedBinaryRelation(
                owner, template, Opcode.JOIN, left, right, exact);
    }

    private static EGraphNode replaceFactoredGroup(
            EGraphNode owner,
            List<EClassRef> operands,
            List<Integer> group,
            EClassRef replacement) {
        Set<Integer> removed = new LinkedHashSet<>(group);
        List<EClassRef> rewritten = new ArrayList<>(
                operands.size() - group.size() + 1);
        for (int index = 0; index < operands.size(); index++) {
            if (!removed.contains(index)) {
                rewritten.add(operands.get(index));
            }
        }
        rewritten.add(replacement);
        if (rewritten.size() == 1) {
            return replacement.eClass.getRepresentative();
        }
        EGraphNode result = buildDerivedLatticeContainer(
                owner, owner, owner.opcode, rewritten);
        result.preserveSourceOccurrenceLineageFrom(owner);
        return result;
    }

    private static Opcode latticeDual(Opcode opcode) {
        switch (opcode) {
            case AND:
                return Opcode.OR;
            case OR:
                return Opcode.AND;
            case PLUS:
                return Opcode.INTERSECT;
            case INTERSECT:
                return Opcode.PLUS;
            default:
                return null;
        }
    }

    private static boolean isCertifiedLatticeOperator(EGraphNode node) {
        if (node == null || !node.isSetFlexibleArity()
                || node.childClasses.size() < 2) {
            return false;
        }
        if (node.opcode == Opcode.AND || node.opcode == Opcode.OR) {
            return hasBooleanRewriteAuthority(node)
                    && hasBooleanOperands(node);
        }
        if (node.opcode != Opcode.PLUS
                && node.opcode != Opcode.INTERSECT) {
            return false;
        }
        ExactAlloyType exact = node.exactAlloyType;
        return exact != null
                && (isExactRelation(exact)
                        || (exact.kind() == ExactAlloyType.Kind.INT
                                && exact.hasParserAuthenticatedAncestry()));
    }

    private static void collectFlatLatticeOperands(
            EGraphNode operator,
            EClassRef invocation,
            List<EClassRef> operands) {
        EClassRef canonical = invocation.canonical();
        EGraphNode node = canonical.getEClass().getRepresentative();
        if (operator.sameFlatOperatorInstance(node)) {
            for (EClassRef child : node.childClasses) {
                collectFlatLatticeOperands(
                        operator,
                        composeInvocation(canonical, child),
                        operands);
            }
            return;
        }
        operands.add(canonical);
    }

    private static boolean containsSemanticInvocation(
            List<EClassRef> invocations,
            EClassRef target) {
        for (EClassRef invocation : invocations) {
            if (sameCertifiedContainerInvocation(invocation, target)) {
                return true;
            }
        }
        return false;
    }

    private static void removeFirstSemanticInvocation(
            List<EClassRef> invocations,
            EClassRef target) {
        for (int index = 0; index < invocations.size(); index++) {
            if (sameCertifiedContainerInvocation(
                    invocations.get(index), target)) {
                invocations.remove(index);
                return;
            }
        }
        throw new IllegalStateException(
                "A certified distributive factor disappeared from a branch");
    }

    private static EGraphNode identityInvocationRepresentative(
            EClassRef invocation) {
        EClassRef canonical = invocation.canonical();
        return canonical.slotMap.equals(identitySlotMap(canonical.eClass))
                ? canonical.eClass.getRepresentative() : null;
    }

    private static EGraphNode buildDerivedLatticeContainer(
            EGraphNode owner,
            EGraphNode template,
            Opcode opcode,
            List<EClassRef> operands) {
        ExactAlloyType exactType = derivedLatticeExactType(opcode, operands);
        EGraphNode result = inOwningArena(
                owner,
                owner.id,
                opcode,
                Collections.emptyList(),
                template.isCommutative,
                template.maxArity,
                template.flexibleArity,
                template.metatype,
                owner.semanticProfile);
        result.sourceType = opcode == Opcode.AND || opcode == Opcode.OR
                ? "Bool" : template.sourceType;
        if (opcode == Opcode.PLUS || opcode == Opcode.INTERSECT) {
            result.sourceName = canonicalRelationalSourceName(opcode);
        }
        result.exactAlloyType = exactType;
        for (EClassRef operand : operands) {
            result.addChildInvocation(operand);
        }
        if (opcode == Opcode.AND || opcode == Opcode.OR) {
            result.recordDerivedBooleanRewriteAuthority();
        }
        return result;
    }

    private static String canonicalRelationalSourceName(Opcode opcode) {
        switch (opcode) {
            case PLUS:
                return "BOPEXPR_PLUS";
            case INTERSECT:
                return "BOPEXPR_INTERSECT";
            case MINUS:
                return "BOPEXPR_MINUS";
            case JOIN:
                return "BOPEXPR_JOIN";
            case DOMAIN:
                return "BOPEXPR_DOMAIN";
            case RANGE:
                return "BOPEXPR_RANGE";
            case TRANSPOSE:
                return "UNOPE_TRANSPOSE";
            default:
                return null;
        }
    }

    private static ExactAlloyType derivedLatticeExactType(
            Opcode opcode,
            List<EClassRef> operands) {
        if (opcode == Opcode.AND || opcode == Opcode.OR) {
            return ExactAlloyType.boolType();
        }
        List<ExactAlloyType> operandTypes = new ArrayList<>(operands.size());
        for (EClassRef operand : operands) {
            ExactAlloyType type = operand.canonical().getEClass()
                    .getRepresentative().exactAlloyType;
            if (type == null) {
                throw new IllegalArgumentException(
                        "A derived relation lattice node requires exact operand types");
            }
            operandTypes.add(type);
        }
        if (opcode == Opcode.PLUS) {
            return ExactAlloyType.parserCertifiedRelationUnion(operandTypes);
        }
        if (opcode == Opcode.INTERSECT) {
            return ExactAlloyType.parserCertifiedRelationIntersection(
                    operandTypes);
        }
        throw new IllegalArgumentException(
                "Unsupported derived lattice opcode: " + opcode);
    }

    @LeanVerifiedRewrite("R0-REL-002")
    private static EGraphNode parserCertifiedProductSubgroupReduction(
            EGraphNode owner,
            List<EClassRef> products,
            List<List<EClassRef>> productFactors,
            EGraphNode template) {
        int factorCount = productFactors.get(0).size();
        for (int varying = 0; varying < factorCount; varying++) {
            for (int anchor = 0; anchor < products.size(); anchor++) {
                List<Integer> group = new ArrayList<>();
                for (int candidate = 0;
                        candidate < products.size();
                        candidate++) {
                    boolean fixedCoordinatesAgree = true;
                    for (int coordinate = 0;
                            coordinate < factorCount;
                            coordinate++) {
                        if (coordinate != varying
                                && !sameCertifiedContainerInvocation(
                                        productFactors.get(anchor).get(coordinate),
                                        productFactors.get(candidate).get(coordinate))) {
                            fixedCoordinatesAgree = false;
                            break;
                        }
                    }
                    if (fixedCoordinatesAgree) {
                        group.add(candidate);
                    }
                }
                if (group.size() < 2) {
                    continue;
                }
                List<EClassRef> varyingFactors = new ArrayList<>(group.size());
                for (int member : group) {
                    varyingFactors.add(productFactors.get(member).get(varying));
                }
                ProductCoordinateCover cover =
                        parserCertifiedProductCoordinateCover(
                                owner, varyingFactors);
                if (cover == null || !cover.changesCoordinate) {
                    continue;
                }
                List<EClassRef> replacementFactors = new ArrayList<>(
                        productFactors.get(anchor));
                replacementFactors.set(varying, cover.carrier);
                EGraphNode replacement = buildDerivedPlainProduct(
                        owner, template, replacementFactors, 0);

                if (group.size() == products.size()) {
                    replacement.preserveSourceOccurrenceLineageFrom(owner);
                    return replacement;
                }
                Set<Integer> replaced = new HashSet<>(group);
                List<EClassRef> retained = new ArrayList<>(
                        products.size() - group.size() + 1);
                for (int index = 0; index < products.size(); index++) {
                    if (!replaced.contains(index)) {
                        retained.add(products.get(index));
                    }
                }
                retained.add(replacement.getEClassRef());
                EGraphNode residual = inOwningArena(
                        owner,
                        owner.id,
                        Opcode.PLUS,
                        Collections.emptyList(),
                        owner.isCommutative,
                        owner.maxArity,
                        owner.flexibleArity,
                        owner.metatype,
                        owner.semanticProfile);
                for (EClassRef retainedInvocation : retained) {
                    residual.addChildInvocation(retainedInvocation);
                }
                residual.setSourceName(owner.sourceName);
                residual.setSourceType(owner.sourceType);
                residual.setExactAlloyType(owner.exactAlloyType);
                residual.preserveSourceOccurrenceLineageFrom(owner);
                EGraphNode furtherReduction =
                        parserCertifiedProductUnionCarrier(
                                residual, retained);
                return furtherReduction == null ? residual : furtherReduction;
            }
        }
        return null;
    }

    private static List<EClassRef> plainProductFactorInvocations(
            EClassRef invocation) {
        List<EClassRef> factors = new ArrayList<>();
        if (!collectPlainProductFactorInvocations(invocation, factors)
                || factors.size() < 2) {
            return null;
        }
        return factors;
    }

    private static boolean collectPlainProductFactorInvocations(
            EClassRef invocation,
            List<EClassRef> factors) {
        EClassRef canonical = invocation.canonical();
        EGraphNode node = canonical.getEClass().getRepresentative();
        if (node == null
                || node.opcode != Opcode.ARROW
                || node.childClasses.size() != 2
                || !isExactRelation(node.exactAlloyType)) {
            return false;
        }
        for (EClassRef child : node.childClasses) {
            EClassRef composed = composeInvocation(canonical, child);
            EGraphNode factor = composed.getEClass().getRepresentative();
            if (factor.opcode == Opcode.ARROW) {
                if (!node.semanticProfile.equals(factor.semanticProfile)
                        || !collectPlainProductFactorInvocations(
                                composed, factors)) {
                    return false;
                }
            } else {
                factors.add(composed);
            }
        }
        return true;
    }

    private static EGraphNode buildDerivedPlainProduct(
            EGraphNode owner,
            EGraphNode template,
            List<EClassRef> factors,
            int offset) {
        if (factors.size() - offset < 2) {
            throw new IllegalArgumentException(
                    "A derived plain product requires at least two remaining factors");
        }
        EClassRef right = factors.size() - offset == 2
                ? factors.get(offset + 1)
                : buildDerivedPlainProduct(
                        owner, template, factors, offset + 1).getEClassRef();
        EClassRef left = factors.get(offset);
        ExactAlloyType exact = ExactAlloyType.parserCertifiedCartesianProduct(
                java.util.Arrays.asList(
                        left.getEClass().getRepresentative().exactAlloyType,
                        right.getEClass().getRepresentative().exactAlloyType));
        EGraphNode product = inOwningArena(
                owner,
                owner.id,
                Opcode.ARROW,
                Collections.emptyList(),
                false,
                template.maxArity,
                template.flexibleArity,
                template.metatype,
                owner.semanticProfile);
        product.addChildInvocation(left);
        product.addChildInvocation(right);
        product.setSourceName(template.sourceName);
        product.setSourceType(template.sourceType);
        product.setExactAlloyType(exact);
        return product;
    }

    private static ProductCoordinateCover parserCertifiedProductCoordinateCover(
            EGraphNode owner,
            List<EClassRef> factors) {
        List<EClassRef> distinct = semanticDistinctInvocations(factors);
        if (distinct.isEmpty()) {
            return null;
        }
        if (distinct.size() == 1) {
            return new ProductCoordinateCover(
                    distinct.get(0), distinct, false);
        }

        EGraphNode dominant = parserCertifiedDominantSignature(
                representativesOf(distinct));
        if (dominant != null) {
            return new ProductCoordinateCover(
                    dominant.getEClassRef(),
                    Collections.singletonList(dominant.getEClassRef()),
                    true);
        }

        EGraphNode carrier = parserCertifiedAbstractUnionCarrier(
                owner, representativesOf(distinct));
        if (carrier != null && carrier.parserSignatureEvidence != null) {
            List<EGraphNode> core = maximalParserSignatureFactors(
                    representativesOf(distinct));
            List<SigSymbol> coreSignatures = new ArrayList<>(core.size());
            for (EGraphNode factor : core) {
                coreSignatures.add(factor.parserSignatureEvidence);
            }
            SigSymbol provedCarrier = SigSymbol.parserCertifiedAbstractCover(
                    coreSignatures);
            if (provedCarrier != null
                    && provedCarrier.isSameParserSignatureAs(
                            carrier.parserSignatureEvidence)) {
                List<EClassRef> coreInvocations = new ArrayList<>(core.size());
                for (EGraphNode factor : core) {
                    coreInvocations.add(factor.getEClassRef());
                }
                return new ProductCoordinateCover(
                        carrier.getEClassRef(), coreInvocations, true);
            }
        }

        List<ExactAlloyType> exactFactors = new ArrayList<>(distinct.size());
        for (EClassRef factor : distinct) {
            exactFactors.add(factor.getEClass().getRepresentative()
                    .exactAlloyType);
        }
        ExactAlloyType unionType;
        try {
            unionType = ExactAlloyType.parserCertifiedRelationUnion(
                    exactFactors);
        } catch (IllegalArgumentException | IllegalStateException rejectedProof) {
            return null;
        }
        EGraphNode union = inOwningArena(
                owner,
                owner.id,
                Opcode.PLUS,
                Collections.emptyList(),
                owner.isCommutative,
                owner.maxArity,
                owner.flexibleArity,
                owner.metatype,
                owner.semanticProfile);
        for (EClassRef factor : distinct) {
            union.addChildInvocation(factor);
        }
        union.setSourceName(owner.sourceName);
        union.setSourceType(owner.sourceType);
        union.setExactAlloyType(unionType);
        union.preserveSourceOccurrenceLineageFrom(owner);
        return new ProductCoordinateCover(
                union.getEClassRef(), distinct, true);
    }

    private static List<EClassRef> semanticDistinctInvocations(
            List<EClassRef> nodes) {
        List<EClassRef> distinct = new ArrayList<>();
        for (EClassRef candidate : nodes) {
            boolean seen = false;
            for (EClassRef existing : distinct) {
                if (sameCertifiedContainerInvocation(existing, candidate)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                distinct.add(candidate);
            }
        }
        return distinct;
    }

    private static List<EGraphNode> semanticDistinctNodes(
            List<EGraphNode> nodes) {
        List<EGraphNode> distinct = new ArrayList<>();
        for (EGraphNode candidate : nodes) {
            boolean seen = false;
            for (EGraphNode existing : distinct) {
                if (sameSemanticInvocation(existing, candidate)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                distinct.add(candidate);
            }
        }
        return distinct;
    }

    @LeanVerifiedRewrite("R0-REL-032")
    private static EGraphNode parserCertifiedDominantSignature(
            List<EGraphNode> factors) {
        EGraphNode best = null;
        for (EGraphNode candidate : factors) {
            if (!isParserAuthenticatedFullSignature(candidate)) {
                continue;
            }
            boolean containsAll = true;
            for (EGraphNode factor : factors) {
                if (!isParserCertifiedWithinSignature(
                        factor, candidate.parserSignatureEvidence)) {
                    containsAll = false;
                    break;
                }
            }
            if (!containsAll) {
                continue;
            }
            if (best == null
                    || candidate.parserSignatureEvidence
                            .isParserCertifiedSubsignatureOf(
                                    best.parserSignatureEvidence)) {
                best = candidate;
            }
        }
        return best;
    }

    private static List<EGraphNode> maximalParserSignatureFactors(
            List<EGraphNode> factors) {
        List<EGraphNode> signatures = new ArrayList<>();
        for (EGraphNode factor : factors) {
            if (isParserAuthenticatedFullSignature(factor)) {
                signatures.add(factor);
            }
        }
        List<EGraphNode> maximal = new ArrayList<>();
        for (EGraphNode candidate : signatures) {
            boolean subsumed = false;
            for (EGraphNode other : signatures) {
                if (candidate == other
                        || candidate.parserSignatureEvidence
                                .isSameParserSignatureAs(
                                        other.parserSignatureEvidence)) {
                    continue;
                }
                if (candidate.parserSignatureEvidence
                        .isParserCertifiedSubsignatureOf(
                                other.parserSignatureEvidence)) {
                    subsumed = true;
                    break;
                }
            }
            if (!subsumed) {
                maximal.add(candidate);
            }
        }
        return semanticDistinctNodes(maximal);
    }

    private static boolean containsCompleteProductGrid(
            List<List<EClassRef>> products,
            List<ProductCoordinateCover> covers) {
        long expected = 1L;
        for (ProductCoordinateCover cover : covers) {
            if (cover.coreFactors.isEmpty()
                    || expected > Long.MAX_VALUE / cover.coreFactors.size()) {
                return false;
            }
            expected *= cover.coreFactors.size();
        }
        if (expected > products.size()) {
            return false;
        }

        Set<String> present = new HashSet<>();
        for (List<EClassRef> product : products) {
            StringBuilder tuple = new StringBuilder();
            boolean coreProduct = true;
            for (int coordinate = 0; coordinate < covers.size(); coordinate++) {
                EClassRef factor = product.get(coordinate);
                List<EClassRef> options = covers.get(coordinate).coreFactors;
                int matched = -1;
                for (int option = 0; option < options.size(); option++) {
                    if (sameCertifiedContainerInvocation(
                            factor, options.get(option))) {
                        matched = option;
                        break;
                    }
                }
                if (matched < 0) {
                    coreProduct = false;
                    break;
                }
                tuple.append(matched).append('/');
            }
            if (coreProduct) {
                present.add(tuple.toString());
            }
        }
        return present.size() == expected;
    }

    private static final class ProductCoordinateCover {
        private final EClassRef carrier;
        private final List<EClassRef> coreFactors;
        private final boolean changesCoordinate;

        private ProductCoordinateCover(
                EClassRef carrier,
                List<EClassRef> coreFactors,
                boolean changesCoordinate) {
            this.carrier = Objects.requireNonNull(carrier, "carrier");
            this.coreFactors = Collections.unmodifiableList(
                    new ArrayList<>(coreFactors));
            this.changesCoordinate = changesCoordinate;
        }
    }

    private static final class RestrictionCoordinates {
        private final Opcode opcode;
        private final EClassRef restrictor;
        private final EClassRef relation;
        private final EGraphNode template;

        private RestrictionCoordinates(
                Opcode opcode,
                EClassRef restrictor,
                EClassRef relation,
                EGraphNode template) {
            this.opcode = Objects.requireNonNull(opcode, "restriction opcode");
            this.restrictor = Objects.requireNonNull(
                    restrictor, "restriction set");
            this.relation = Objects.requireNonNull(
                    relation, "restricted relation");
            this.template = Objects.requireNonNull(
                    template, "restriction template");
        }

        private EClassRef coordinate(int index) {
            if (index == 0) {
                return restrictor;
            }
            if (index == 1) {
                return relation;
            }
            throw new IndexOutOfBoundsException(
                    "A restriction has exactly two semantic coordinates");
        }
    }

    private static final class DifferenceCoordinates {
        private final EClassRef left;
        private final EClassRef right;
        private final EGraphNode template;

        private DifferenceCoordinates(
                EClassRef left,
                EClassRef right,
                EGraphNode template) {
            this.left = Objects.requireNonNull(left, "difference left operand");
            this.right = Objects.requireNonNull(right, "difference right operand");
            this.template = Objects.requireNonNull(
                    template, "difference source template");
        }
    }

    private static void collectSameProfilePlusTerminals(
            EGraphNode owner,
            EClassRef candidateInvocation,
            List<EClassRef> terminals) {
        EClassRef canonical = candidateInvocation == null
                ? null : candidateInvocation.canonical();
        EGraphNode candidate = canonical == null
                ? null : canonical.getEClass().getRepresentative();
        if (candidate != null
                && candidate.opcode == Opcode.PLUS
                && candidate.isSetFlexibleArity()
                && owner.semanticProfile.equals(candidate.semanticProfile)) {
            for (EClassRef child : candidate.childClasses) {
                collectSameProfilePlusTerminals(
                        owner, composeInvocation(canonical, child), terminals);
            }
            return;
        }
        if (canonical != null) {
            terminals.add(canonical);
        }
    }

    private static void collectSameProfilePlusTerminals(
            EGraphNode owner,
            EGraphNode candidate,
            List<EGraphNode> terminals) {
        if (candidate != null
                && candidate.opcode == Opcode.PLUS
                && candidate.isSetFlexibleArity()
                && owner.semanticProfile.equals(candidate.semanticProfile)) {
            for (EGraphNode child : candidate.getChildren()) {
                collectSameProfilePlusTerminals(owner, child, terminals);
            }
            return;
        }
        if (candidate != null) {
            terminals.add(candidate);
        }
    }

    private static boolean isParserCertifiedWithinSignature(
            EGraphNode candidate,
            SigSymbol carrier) {
        if (candidate == null || carrier == null
                || !carrier.hasParserSignatureAuthority()) {
            return false;
        }
        if (candidate.parserSignatureEvidence != null) {
            return candidate.parserSignatureEvidence
                            .isSameParserSignatureAs(carrier)
                    || candidate.parserSignatureEvidence
                            .isParserCertifiedSubsignatureOf(carrier);
        }
        ExactAlloyType carrierType = carrier.parserExactType();
        if (carrierType.kind() != ExactAlloyType.Kind.RELATION
                || carrierType.relationArity() != 1
                || carrierType.alternatives().size() != 1
                || carrierType.alternatives().get(0).size() != 1
                || !carrier.getName().equals(
                        carrierType.alternatives().get(0).get(0))) {
            return false;
        }
        ExactAlloyType candidateType = candidate.exactAlloyType;
        if (candidateType != null
                && candidateType.kind() == ExactAlloyType.Kind.EMPTY_RELATION
                && candidateType.relationArity() == 1
                && isSetConstant(candidate, "none")) {
            return true;
        }
        return candidateType != null
                && candidateType.isParserCertifiedRelationSubfamilyOf(carrierType);
    }

    private static List<EGraphNode> representativesOf(
            List<EClassRef> children) {
        List<EGraphNode> representatives = new ArrayList<>(children.size());
        for (EClassRef child : children) {
            representatives.add(child.getEClass().getRepresentative());
        }
        return representatives;
    }

    private static boolean isParserAuthenticatedFullSignature(EGraphNode node) {
        if (node == null
                || node.opcode != Opcode.GLOBALBINDING
                || node.metatype != Metatype.ATOMIC
                || !"Signature".equals(node.sourceType)
                || node.sourceName == null
                || node.semanticIdentity == null
                || node.builtinConstantKind != null
                || node.parserSignatureEvidence == null
                || !node.childClasses.isEmpty()
                || !node.semanticIdentity.equals(
                        "alloy/signature/" + node.sourceName)) {
            return false;
        }
        ExactAlloyType exact = node.exactAlloyType;
        return exact != null
                && exact.kind() == ExactAlloyType.Kind.RELATION
                && exact.relationArity() == 1
                && node.parserSignatureEvidence.authenticatesExactType(exact);
    }

    private static boolean isParserAuthenticatedPrimitiveCarrier(
            EGraphNode node,
            ExactAlloyType exact) {
        return isParserAuthenticatedFullSignature(node)
                && exact != null
                && exact.alternatives().size() == 1
                && exact.alternatives().get(0).size() == 1
                && node.sourceName.equals(exact.alternatives().get(0).get(0));
    }

    static boolean hasCompatibleRelationArity(
            EGraphNode left,
            EGraphNode right) {
        if (left == null || right == null) {
            return false;
        }
        ExactAlloyType leftType = left.exactAlloyType;
        ExactAlloyType rightType = right.exactAlloyType;
        return isExactRelation(leftType)
                && isExactRelation(rightType)
                && leftType.relationArity() == rightType.relationArity();
    }

    private static boolean isExactRelation(ExactAlloyType type) {
        return type != null
                && (type.kind() == ExactAlloyType.Kind.RELATION
                        || type.kind() == ExactAlloyType.Kind.EMPTY_RELATION);
    }

    private static boolean isParserAuthenticatedSetFamily(ExactAlloyType type) {
        return type != null
                && type.hasParserAuthenticatedAncestry()
                && (isExactRelation(type)
                        || type.kind() == ExactAlloyType.Kind.INT);
    }

    private static boolean isExactBinaryRelation(EGraphNode node) {
        ExactAlloyType exact = node == null ? null : node.exactAlloyType;
        return exact != null
                && exact.kind() == ExactAlloyType.Kind.RELATION
                && exact.relationArity() == 2;
    }

    private static boolean hasExactRelationArity(
            EGraphNode node,
            int arity) {
        ExactAlloyType exact = node == null ? null : node.exactAlloyType;
        return isExactRelation(exact) && exact.relationArity() == arity;
    }

    private static boolean sameExactRelationOccurrence(
            EGraphNode left,
            EGraphNode right) {
        ExactAlloyType leftType = left == null ? null : left.exactAlloyType;
        ExactAlloyType rightType = right == null ? null : right.exactAlloyType;
        return leftType != null
                && rightType != null
                && isExactRelation(leftType)
                && leftType.sameOccurrenceEvidenceAs(rightType);
    }

    static boolean isExactRelationNode(EGraphNode node) {
        return node != null && isExactRelation(node.exactAlloyType);
    }

    private static List<EClassRef> removeSubrelationsCoveredByFullCarriers(
            List<EClassRef> children) {
        List<EClassRef> retained = new ArrayList<>(children.size());
        for (int candidateIndex = 0;
                candidateIndex < children.size();
                candidateIndex++) {
            EGraphNode candidate = children.get(candidateIndex)
                    .getEClass().getRepresentative();
            boolean covered = false;
            for (int carrierIndex = 0;
                    carrierIndex < children.size();
                    carrierIndex++) {
                if (candidateIndex == carrierIndex) {
                    continue;
                }
                EGraphNode carrier = children.get(carrierIndex)
                        .getEClass().getRepresentative();
                if (isParserCertifiedSubrelationOfFullCarrier(
                                candidate, carrier)
                        && (!isParserCertifiedSubrelationOfFullCarrier(
                                        carrier, candidate)
                                || candidateIndex > carrierIndex)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                retained.add(children.get(candidateIndex));
            }
        }
        return retained;
    }

    private static List<EClassRef> removeFullCarriersContainingSubrelations(
            List<EClassRef> children) {
        List<EClassRef> retained = new ArrayList<>(children.size());
        for (int carrierIndex = 0;
                carrierIndex < children.size();
                carrierIndex++) {
            EGraphNode carrier = children.get(carrierIndex)
                    .getEClass().getRepresentative();
            boolean redundant = false;
            for (int candidateIndex = 0;
                    candidateIndex < children.size();
                    candidateIndex++) {
                if (candidateIndex == carrierIndex) {
                    continue;
                }
                EGraphNode candidate = children.get(candidateIndex)
                        .getEClass().getRepresentative();
                if (isParserCertifiedSubrelationOfFullCarrier(
                                candidate, carrier)
                        && (!isParserCertifiedSubrelationOfFullCarrier(
                                        carrier, candidate)
                                || carrierIndex > candidateIndex)) {
                    redundant = true;
                    break;
                }
            }
            if (!redundant) {
                retained.add(children.get(carrierIndex));
            }
        }
        return retained;
    }

    private static List<EClassRef> removeDuplicateInvocations(List<EClassRef> children) {
        List<EClassRef> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (EClassRef child : children) {
            String key = invocationKey(child);
            if (seen.add(key)) {
                unique.add(child);
            }
        }
        return unique;
    }

    private static boolean containsComplement(List<EClassRef> children) {
        for (int i = 0; i < children.size(); i++) {
            for (int j = i + 1; j < children.size(); j++) {
                if (areComplements(children.get(i), children.get(j))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Recognizes `xs \/ (and (not xs))` and its Boolean dual. */
    static boolean containsCertifiedCoveredDualBranch(
            EGraphNode owner,
            List<EClassRef> children) {
        if (owner == null || children == null
                || (owner.opcode != Opcode.AND && owner.opcode != Opcode.OR)
                || !hasBooleanRewriteAuthority(owner)
                || !hasBooleanOperands(owner)) {
            return false;
        }
        List<EClassRef> effectiveChildren = new ArrayList<>();
        for (EClassRef child : children) {
            EClassRef canonical = child.canonical();
            if (owner.sameFlatOperatorInstance(
                    canonical.eClass.getRepresentative())) {
                collectFlatLatticeOperands(
                        owner, canonical, effectiveChildren);
            } else {
                effectiveChildren.add(canonical);
            }
        }
        Opcode branchOpcode = owner.opcode == Opcode.OR
                ? Opcode.AND : Opcode.OR;
        for (int branchIndex = 0;
                branchIndex < effectiveChildren.size();
                branchIndex++) {
            EClassRef branchInvocation = effectiveChildren.get(branchIndex);
            EGraphNode branch = branchInvocation.eClass.getRepresentative();
            if (branch.opcode != branchOpcode
                    || !isCertifiedLatticeOperator(branch)) {
                continue;
            }
            List<EClassRef> branchTerms = new ArrayList<>();
            collectFlatLatticeOperands(
                    branch, branchInvocation, branchTerms);
            boolean covered = !branchTerms.isEmpty();
            for (EClassRef branchTerm : branchTerms) {
                boolean complementFound = false;
                for (int candidate = 0;
                        candidate < effectiveChildren.size();
                        candidate++) {
                    if (candidate != branchIndex
                            && areComplements(
                                    branchTerm,
                                    effectiveChildren.get(candidate))) {
                        complementFound = true;
                        break;
                    }
                }
                if (!complementFound) {
                    covered = false;
                    break;
                }
            }
            if (covered) {
                return true;
            }
        }
        return false;
    }

    static boolean areSemanticComplements(EGraphNode left, EGraphNode right) {
        return left != null && right != null
                && areComplements(left.getEClassRef(), right.getEClassRef());
    }

    private static boolean areComplements(EClassRef left, EClassRef right) {
        EGraphNode leftNode = left.getEClass().getRepresentative();
        EGraphNode rightNode = right.getEClass().getRepresentative();
        if (leftNode.opcode == Opcode.NOT && leftNode.childClasses.size() == 1) {
            return sameCertifiedContainerInvocation(
                    composeInvocation(left, leftNode.childClasses.get(0)), right);
        }
        if (rightNode.opcode == Opcode.NOT && rightNode.childClasses.size() == 1) {
            return sameCertifiedContainerInvocation(
                    left, composeInvocation(right, rightNode.childClasses.get(0)));
        }
        EClassRef leftSingleton = certifiedAciSingletonInvocation(left);
        if (leftSingleton != null) {
            return areComplements(leftSingleton, right);
        }
        EClassRef rightSingleton = certifiedAciSingletonInvocation(right);
        if (rightSingleton != null) {
            return areComplements(left, rightSingleton);
        }
        return dualOf(leftNode.opcode) == rightNode.opcode
                && sameChildInvocations(left, right);
    }

    private static boolean sameChildInvocations(EClassRef left, EClassRef right) {
        EGraphNode leftNode = left.eClass.getRepresentative();
        EGraphNode rightNode = right.eClass.getRepresentative();
        if (leftNode.childClasses.size() != rightNode.childClasses.size()) {
            return false;
        }
        List<String> leftChildren = new ArrayList<>(leftNode.childClasses.size());
        List<String> rightChildren = new ArrayList<>(rightNode.childClasses.size());
        for (int i = 0; i < leftNode.childClasses.size(); i++) {
            leftChildren.add(certifiedContainerInvocationKey(
                    composeInvocation(left, leftNode.childClasses.get(i))));
            rightChildren.add(certifiedContainerInvocationKey(
                    composeInvocation(right, rightNode.childClasses.get(i))));
        }
        if (leftNode.isOrderInsensitive() && rightNode.isOrderInsensitive()) {
            Collections.sort(leftChildren);
            Collections.sort(rightChildren);
        }
        return leftChildren.equals(rightChildren);
    }

    private static Opcode dualOf(Opcode opcode) {
        switch (opcode) {
            case EQUALS:
                return Opcode.NOT_EQUALS;
            case NOT_EQUALS:
                return Opcode.EQUALS;
            case GT:
                return Opcode.LTE;
            case LTE:
                return Opcode.GT;
            case GTE:
                return Opcode.LT;
            case LT:
                return Opcode.GTE;
            case IN:
                return Opcode.NOT_IN;
            case NOT_IN:
                return Opcode.IN;
            case SOME:
                return Opcode.NO;
            case NO:
                return Opcode.SOME;
            default:
                return null;
        }
    }

    private static boolean sameInvocation(EClassRef left, EClassRef right) {
        if (left.eClass.arena != right.eClass.arena) {
            return false;
        }
        if (left.equivalentTo(right)) {
            return true;
        }
        return invocationKey(left).equals(invocationKey(right));
    }

    public static boolean sameSemanticInvocation(EGraphNode left, EGraphNode right) {
        return left != null && right != null
                && sameCertifiedContainerInvocation(
                        left.getEClassRef(), right.getEClassRef());
    }

    /**
     * Compares source invocations in the certified ACI container quotient without
     * mutating either operand. This lets an enclosing source rule consume an
     * identity such as {@code S + S = S} before the certified/live snapshots
     * diverge.
     */
    private static boolean sameCertifiedContainerInvocation(
            EClassRef left,
            EClassRef right) {
        if (left.eClass.arena != right.eClass.arena) {
            return false;
        }
        return certifiedContainerInvocationKey(left).equals(
                certifiedContainerInvocationKey(right));
    }

    private static String certifiedContainerInvocationKey(EClassRef invocation) {
        return appendCertifiedContainerInvocationKey(
                invocation.canonical(),
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static String appendCertifiedContainerInvocationKey(
            EClassRef invocation,
            Set<EClass> active) {
        EClassRef canonical = invocation.canonical();
        EGraphNode node = canonical.eClass.getRepresentative();
        if (!active.add(canonical.eClass)) {
            throw new IllegalStateException(
                    "Certified container comparison encountered a cyclic source term");
        }
        try {
            if (hasCertifiedAciQuotient(node)) {
                Set<String> operands = new java.util.TreeSet<>();
                for (EClassRef operand : certifiedAciOperandInvocations(
                        node, canonical, active)) {
                    operands.add(appendCertifiedContainerInvocationKey(
                            operand, active));
                }
                if (operands.size() == 1) {
                    return operands.iterator().next();
                }
                StringBuilder result = new StringBuilder();
                appendSemanticHead(node, result);
                result.append('[');
                for (String operand : operands) {
                    result.append(operand).append(',');
                }
                return result.append(']').toString();
            }

            List<String> children = new ArrayList<>(node.childClasses.size());
            for (EClassRef child : node.childClasses) {
                children.add(appendCertifiedContainerInvocationKey(
                        composeInvocation(canonical, child), active));
            }
            if (node.isOrderInsensitive()) {
                Collections.sort(children);
            }
            StringBuilder result = new StringBuilder();
            appendSemanticHead(node, result);
            result.append('[');
            for (String child : children) {
                result.append(child).append(',');
            }
            result.append(']');
            if (node.childClasses.isEmpty()) {
                result.append('@').append(canonical.slotMap);
            }
            return result.toString();
        } finally {
            active.remove(canonical.eClass);
        }
    }

    private static EClassRef certifiedAciSingletonInvocation(
            EClassRef invocation) {
        EClassRef canonical = invocation.canonical();
        EGraphNode node = canonical.eClass.getRepresentative();
        if (!hasCertifiedAciQuotient(node)) {
            return null;
        }
        Set<EClass> active = Collections.newSetFromMap(new IdentityHashMap<>());
        active.add(canonical.eClass);
        List<EClassRef> operands = certifiedAciOperandInvocations(
                node, canonical, active);
        if (operands.isEmpty()) {
            return null;
        }
        EClassRef singleton = operands.get(0);
        String singletonKey = certifiedContainerInvocationKey(singleton);
        for (int index = 1; index < operands.size(); index++) {
            if (!singletonKey.equals(
                    certifiedContainerInvocationKey(operands.get(index)))) {
                return null;
            }
        }
        return singleton;
    }

    private static List<EClassRef> certifiedAciOperandInvocations(
            EGraphNode operator,
            EClassRef invocation,
            Set<EClass> active) {
        List<EClassRef> operands = new ArrayList<>();
        appendCertifiedAciOperandInvocations(
                operator, invocation, operands, active);
        return operands;
    }

    private static void appendCertifiedAciOperandInvocations(
            EGraphNode operator,
            EClassRef invocation,
            List<EClassRef> operands,
            Set<EClass> active) {
        EGraphNode node = invocation.eClass.getRepresentative();
        for (EClassRef child : node.childClasses) {
            EClassRef composed = composeInvocation(invocation, child);
            EGraphNode childNode = composed.eClass.getRepresentative();
            if (operator.sameFlatOperatorInstance(childNode)
                    || sameParserCertifiedRelationLatticeInstance(
                            operator, childNode)) {
                if (!active.add(composed.eClass)) {
                    throw new IllegalStateException(
                            "Certified ACI comparison encountered a cyclic source term");
                }
                try {
                    appendCertifiedAciOperandInvocations(
                            operator, composed, operands, active);
                } finally {
                    active.remove(composed.eClass);
                }
            } else {
                operands.add(composed);
            }
        }
    }

    private static boolean hasCertifiedAciQuotient(EGraphNode node) {
        if (!node.isSetFlexibleArity()) {
            return false;
        }
        return (node.opcode != Opcode.AND && node.opcode != Opcode.OR)
                || (hasBooleanRewriteAuthority(node) && hasBooleanOperands(node));
    }

    private static void appendSemanticHead(EGraphNode node, StringBuilder output) {
        output.append(node.opcode).append('{')
                .append(node.semanticProfile.fingerprint()).append(';')
                .append(node.exactAlloyType == null
                        ? "" : node.exactAlloyType.stableString()).append(';')
                .append(node.getArityPolicy()).append(';')
                .append(node.getSiblingQuotient()).append(';')
                .append(node.getFlatLicense()).append(';')
                .append(node.getUnitLicense()).append("}:");
        if (node.opcode == Opcode.CALL) {
            output.append(CallMetadata.semanticKey(node));
        } else if (node.opcode == Opcode.VARIABLE && node.alphaName != null) {
            output.append(node.alphaName);
        } else if (node.semanticIdentity != null) {
            output.append(node.semanticIdentity);
        } else if (node.alphaName != null) {
            output.append(node.alphaName);
        } else if (node.sourceName != null) {
            output.append(node.sourceName);
        } else if (node.childClasses.isEmpty()) {
            output.append(node.sourceType == null ? "" : node.sourceType);
        }
    }

    private static String invocationKey(EClassRef invocation) {
        EClassRef canonical = invocation.canonical();
        return canonical.getEClass().getRepresentative().sortKey() + canonical.getSlotMap();
    }

    private static boolean isAssociative(Opcode opcode) {
        return AlloyOperatorPolicy.isFlatSetOperator(opcode);
    }

    private static boolean isSetFlexibleArityOperator(Opcode opcode) {
        return AlloyOperatorPolicy.isFlatSetOperator(opcode);
    }

    private String sortKey() {
        StringBuilder sb = new StringBuilder();
        appendSortKey(this, sb);
        return sb.toString();
    }

    private static void appendSortKey(EGraphNode node, StringBuilder sb) {
        appendSemanticHead(node, sb);
        sb.append('[');
        if (node.childClasses != null) {
            if (node.isSetFlexibleArity()) {
                Set<String> members = new java.util.TreeSet<>();
                for (EClassRef childRef : node.childClasses) {
                    members.add(invocationSortKey(childRef));
                }
                for (String member : members) {
                    sb.append(member).append(',');
                }
            } else if (node.isBagFlexibleArity()) {
                Map<String, Integer> multiplicities = new java.util.TreeMap<>();
                for (EClassRef childRef : node.childClasses) {
                    String key = invocationSortKey(childRef);
                    multiplicities.put(key, multiplicities.getOrDefault(key, 0) + 1);
                }
                for (Map.Entry<String, Integer> entry : multiplicities.entrySet()) {
                    sb.append(entry.getKey());
                    if (entry.getValue() > 1) {
                        sb.append('^').append(entry.getValue());
                    }
                    sb.append(',');
                }
            } else {
                for (EClassRef childRef : node.childClasses) {
                    sb.append(invocationSortKey(childRef)).append(',');
                }
            }
        }
        sb.append(']');
    }

    private static String invocationSortKey(EClassRef childRef) {
        StringBuilder key = new StringBuilder();
        appendSortKey(childRef.getEClass().getRepresentative(), key);
        key.append('@').append(childRef.getSlotMap());
        return key.toString();
    }

    private EGraphNode snapshot() {
        EGraphNode copy = new EGraphNode(
                id, opcode, Collections.emptyList(), isCommutative, maxArity, flexibleArity,
                metatype, semanticProfile, false, arena);
        copy.sourceName = sourceName;
        copy.sourceType = sourceType;
        copy.exactAlloyType = exactAlloyType;
        copy.alphaName = alphaName;
        copy.semanticIdentity = semanticIdentity;
        copy.builtinConstantKind = builtinConstantKind;
        copy.parserSignatureEvidence = parserSignatureEvidence;
        copy.sourceOccurrenceLineage = sourceOccurrenceLineage;
        copy.certificationOccurrenceLineage = certificationOccurrenceLineage;
        copy.callOccurrenceId = callOccurrenceId;
        copy.declaredArity = declaredArity;
        copy.callArityAuthority = callArityAuthority;
        copy.derivedBooleanRewriteOpcode = derivedBooleanRewriteOpcode;
        copy.temporalReferenceAuthorityId = temporalReferenceAuthorityId;
        copy.temporalSnapshotBinding = temporalSnapshotBinding;
        copy.childClasses = new ArrayList<>(childClasses);
        copy.eClass = eClass;
        return copy;
    }

    private static long nextSourceOccurrenceLineage() {
        long lineage = NEXT_SOURCE_OCCURRENCE_LINEAGE.getAndIncrement();
        if (lineage <= 0L) {
            throw new IllegalStateException("Source occurrence lineage space exhausted");
        }
        return lineage;
    }

    private void retireSemanticPayload() {
        id = -1;
        opcode = Opcode.DUMMY;
        childClasses = new ArrayList<>();
        isCommutative = false;
        maxArity = 0;
        flexibleArity = false;
        sourceName = null;
        sourceType = null;
        exactAlloyType = null;
        alphaName = null;
        semanticIdentity = null;
        builtinConstantKind = null;
        parserSignatureEvidence = null;
        sourceOccurrenceLineage = 0L;
        certificationOccurrenceLineage = 0L;
        callOccurrenceId = -1L;
        declaredArity = -1;
        callArityAuthority = null;
        derivedBooleanRewriteOpcode = null;
        temporalReferenceAuthorityId = -1L;
        temporalSnapshotBinding = false;
        metatype = Metatype.CONTROL;
    }

    private String semanticLabel() {
        return opcode == Opcode.CALL
                ? CallMetadata.semanticKey(this)
                : semanticIdentity == null ? sourceName : semanticIdentity;
    }

    private void refreshEClassSlots() {
        if (eClass != null) {
            eClass.markSlotsDirty();
        }
    }

    private static Map<String, String> identitySlotMap(EClass eClass) {
        if (eClass.getSlots().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String slot : eClass.getSlots()) {
            mapping.put(slot, slot);
        }
        return mapping;
    }

    private static boolean sameChildren(List<EClassRef> left, List<EClassRef> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).equals(right.get(i))) {
                return false;
            }
        }
        return true;
    }

    private Set<String> slots() {
        Set<String> exposed = null;
        if (opcode == Opcode.VARIABLE) {
            String slot = alphaName != null ? alphaName : sourceName;
            if (slot != null) {
                exposed = new LinkedHashSet<>();
                exposed.add(slot);
            }
        }
        for (EClassRef child : childClasses) {
            if (!child.getSlotMap().isEmpty()) {
                if (exposed == null) {
                    exposed = new LinkedHashSet<>();
                }
                exposed.addAll(child.getSlotMap().values());
            }
        }
        return exposed == null ? Collections.emptySet() : exposed;
    }

    public static final class ReachabilityStats {
        public final long eclasses;
        public final long enodes;

        private ReachabilityStats(long eclasses, long enodes) {
            this.eclasses = eclasses;
            this.enodes = enodes;
        }
    }

    public static final class EClassRef {
        private final EClass eClass;
        private final Map<String, String> slotMap;

        private EClassRef(EClass eClass, Map<String, String> slotMap) {
            this.eClass = eClass;
            this.slotMap = slotMap.isEmpty()
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(slotMap));
        }

        public EClass getEClass() {
            return eClass;
        }

        public Map<String, String> getSlotMap() {
            return slotMap;
        }

        public EClassRef canonical() {
            eClass.requireLive();
            if (!eClass.registered) {
                Set<String> exposedSlots = eClass.getSlots();
                if (slotMap.keySet().equals(exposedSlots)) {
                    return this;
                }
                Map<String, String> restricted = new LinkedHashMap<>();
                for (String slot : exposedSlots) {
                    String callerSlot = slotMap.get(slot);
                    if (callerSlot != null) {
                        restricted.put(slot, callerSlot);
                    }
                }
                return new EClassRef(eClass, restricted);
            }
            RenamedId leader = eClass.arena.unionFind.find(asRenamedId());
            return new EClassRef(eClass.arena.classes.get(leader.getId()), leader.getRenaming());
        }

        public boolean equivalentTo(EClassRef other) {
            if (eClass.arena != other.eClass.arena) {
                return false;
            }
            EClassRef left = canonical();
            EClassRef right = other.canonical();
            return left.eClass.id == right.eClass.id
                    && left.eClass.equivalentInvocations(left.slotMap, right.slotMap);
        }

        private RenamedId asRenamedId() {
            return new RenamedId(eClass.getId(), slotMap);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof EClassRef)) {
                return false;
            }
            EClassRef ref = (EClassRef) other;
            return eClass.getId() == ref.eClass.getId() && slotMap.equals(ref.slotMap);
        }

        @Override
        public int hashCode() {
            return 31 * eClass.getId() + slotMap.hashCode();
        }
    }

    public static final class EClass {
        private final int id;
        private final EGraphArena arena;
        private final List<EGraphNode> nodes = new ArrayList<>();
        private Set<String> slots = Collections.emptySet();
        private Set<String> shapes;
        private SlotPermutationGroup symmetries;
        private boolean registered;
        private boolean retired;
        private boolean slotsDirty = true;

        private EClass(int id, EGraphNode head) {
            this.id = id;
            this.arena = head.arena;
            nodes.add(head);
        }

        public int getId() {
            return id;
        }

        public List<EGraphNode> getNodes() {
            requireLive();
            return Collections.unmodifiableList(nodes);
        }

        public EGraphNode getRepresentative() {
            requireLive();
            return nodes.get(0);
        }

        public Set<String> getSlots() {
            requireLive();
            ensureSlots();
            return Collections.unmodifiableSet(slots);
        }

        public EClassRef invoke(Map<String, String> slotMap) {
            requireLive();
            ensureSlots();
            if (!slotMap.keySet().equals(slots)) {
                throw new IllegalArgumentException("Invocation must map every exposed e-class slot");
            }
            return new EClassRef(this, slotMap);
        }

        public void addSlotSwap(String left, String right) {
            arena.mutate(this, () -> {
                ensureSlots();
                symmetryGroup().addSwap(left, right);
            });
        }

        public int symmetryCount() {
            requireLive();
            ensureSlots();
            return symmetries == null ? 1 : symmetries.size();
        }

        private void addEquivalentNode(EGraphNode node) {
            String shape = node.sortKey();
            if (shapes == null) {
                shapes = new HashSet<>();
                for (EGraphNode existing : nodes) {
                    shapes.add(existing.sortKey());
                }
            }
            if (!shapes.add(shape)) {
                return;
            }
            node.eClass = this;
            nodes.add(node);
            recomputeSlots();
        }

        private void preserveSnapshot(EGraphNode node) {
            node.eClass = this;
            nodes.add(node);
        }

        private void recordShape(EGraphNode node) {
            if (shapes != null) {
                shapes.add(node.sortKey());
            }
        }

        private void recomputeSlots() {
            if (nodes.isEmpty()) {
                slots = Collections.emptySet();
                slotsDirty = false;
                return;
            }
            Set<String> common;
            if (nodes.size() == 1) {
                common = nodes.get(0).slots();
            } else if (slots.isEmpty()) {
                slotsDirty = false;
                return;
            } else {
                Set<String> representativeSlots = nodes.get(0).slots();
                if (representativeSlots.containsAll(slots)) {
                    slotsDirty = false;
                    return;
                }
                common = new LinkedHashSet<>(slots);
                common.retainAll(representativeSlots);
            }
            if (slots.equals(common)) {
                slotsDirty = false;
                return;
            }
            slots = common;
            slotsDirty = false;
            if (symmetries != null) {
                symmetries.setSlots(slots);
            }
            if (registered) {
                arena.unionFind.updateSlots(id, slots);
            }
        }

        private SlotPermutationGroup symmetryGroup() {
            ensureSlots();
            if (symmetries == null) {
                symmetries = new SlotPermutationGroup(slots);
            }
            return symmetries;
        }

        private void addInvocationEquivalence(Map<String, String> left, Map<String, String> right) {
            if (left.equals(right)) {
                return;
            }
            symmetryGroup().addInvocationEquivalence(left, right);
        }

        private boolean equivalentInvocations(Map<String, String> left, Map<String, String> right) {
            ensureSlots();
            return symmetries == null ? left.equals(right) : symmetries.equivalentInvocations(left, right);
        }

        private void ensureRegistered() {
            requireLive();
            if (!registered) {
                ensureSlots();
                arena.classes.put(id, this);
                arena.unionFind.register(id, slots);
                registered = true;
            }
        }

        private void markSlotsDirty() {
            requireLive();
            slotsDirty = true;
        }

        private void retire() {
            retired = true;
            registered = false;
            for (EGraphNode node : nodes) {
                node.retireSemanticPayload();
            }
            nodes.clear();
            shapes = null;
            symmetries = null;
            slots = Collections.emptySet();
            slotsDirty = false;
        }

        private void requireLive() {
            if (retired) {
                throw new IllegalStateException(
                        "An e-class pruned from its arena cannot be reused");
            }
        }

        private void ensureSlots() {
            if (slotsDirty) {
                recomputeSlots();
            }
        }
    }

    private static final class EGraphArena {
        private final Map<Integer, EClass> classes = new LinkedHashMap<>();
        private final RenamedIdUnionFind unionFind = new RenamedIdUnionFind();
        private final Set<EGraphNode> frozenCertificationSources =
                Collections.newSetFromMap(new IdentityHashMap<>());

        private synchronized EGraphNode createDerivedNode(
                EGraphNode owner,
                int id,
                Opcode opcode,
                List<EGraphNode> children,
                boolean isCommutative,
                int maxArity,
                boolean flexibleArity,
                Metatype metatype,
                SemanticProfile semanticProfile) {
            requireMutable(owner);
            if (!owner.semanticProfile.equals(semanticProfile)) {
                throw new IllegalArgumentException(
                        "A derived e-node must preserve its owner's semantic profile");
            }
            return new EGraphNode(
                    id, opcode, children, isCommutative, maxArity, flexibleArity,
                    metatype, semanticProfile, true, this);
        }

        private synchronized void mutate(
                EGraphNode source,
                Runnable mutation) {
            requireMutable(source);
            mutation.run();
        }

        private synchronized <T> T mutate(
                EClass left,
                EClass right,
                java.util.function.Supplier<T> mutation) {
            requireMutable(left);
            requireMutable(right);
            return mutation.get();
        }

        private synchronized void mutate(EClass source, Runnable mutation) {
            requireMutable(source);
            mutation.run();
        }

        private synchronized void mutateGlobally(Runnable mutation) {
            if (!frozenCertificationSources.isEmpty()) {
                throw new IllegalStateException(
                        "A global Fast Rewrite e-graph mutation would affect a certified source");
            }
            mutation.run();
        }

        private synchronized void freezeForCertification(EGraphNode root) {
            Set<EClass> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Integer> expandedComponents = new HashSet<>();
            ArrayDeque<EClass> pending = new ArrayDeque<>();
            pending.add(Objects.requireNonNull(root, "certification root").eClass);
            while (!pending.isEmpty()) {
                EClass eClass = pending.removeFirst();
                if (!visited.add(eClass)) {
                    continue;
                }
                enqueueUnionComponent(
                        eClass, visited, pending, expandedComponents);
                for (EGraphNode node : eClass.nodes) {
                    frozenCertificationSources.add(node);
                    for (EClassRef child : node.childClasses) {
                        pending.addLast(child.eClass);
                    }
                }
            }
        }

        private synchronized void admitAndFreezeForCertification(
                EGraphNode root,
                java.util.function.Consumer<EGraphNode> visitor) {
            EGraphNode checked = Objects.requireNonNull(root, "certification root");
            requireAdmittedReachableGraph(checked, visitor);
            // The trusted path must not dispatch to an overridable method between
            // admission and freezing; the arena performs both operations directly.
            freezeForCertification(checked);
        }

        private synchronized void requireAdmittedGraph(EGraphNode root) {
            requireAdmittedReachableGraph(
                    Objects.requireNonNull(root, "admission root"), ignored -> { });
        }

        private synchronized void forEachAdmittedReachableNode(
                EGraphNode root,
                java.util.function.Consumer<EGraphNode> visitor) {
            requireAdmittedReachableGraph(
                    Objects.requireNonNull(root, "admission root"),
                    Objects.requireNonNull(visitor, "admission visitor"));
        }

        private synchronized boolean isFrozenForCertification(EGraphNode source) {
            return frozenCertificationSources.contains(source);
        }

        private void requireMutable(EGraphNode source) {
            source.eClass.requireLive();
            if (frozenCertificationSources.contains(source)) {
                throw new IllegalStateException(
                        "A certified Fast Rewrite source e-graph is immutable");
            }
        }

        private void requireMutable(EClass source) {
            source.requireLive();
            for (EGraphNode node : source.nodes) {
                requireMutable(node);
            }
        }
    }

}
