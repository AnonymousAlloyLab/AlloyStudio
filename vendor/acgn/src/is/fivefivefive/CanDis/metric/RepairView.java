package is.fivefivefive.CanDis.metric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import is.fivefivefive.CanDis.canonical.CanonicalObservation;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import is.fivefivefive.CanDis.theory.StructuralKey;
import is.fivefivefive.CanDis.theory.GraphType;

/**
 * Immutable repair-domain observation. Production values are sealed by
 * {@link RepairProjection} from a matching frozen certified adapter result;
 * package-local constructors create unsealed bounded-test fixtures only. This
 * value is not independent replay authority: replay happens outside the
 * producer process.
 * Temporal topology, phase quantifiers, and matrices remain separate because
 * that decomposition is part of the established {@code CanonicalDistance}
 * semantics. Certificate-derived scope data is metadata, never an edit node.
 */
public final class RepairView {
    public enum ContainerKind {
        ONE,
        SEQUENCE,
        BAG,
        SET
    }

    public enum BindingRole {
        PARAMETER,
        MATRIX,
        INHERITED,
        LOCAL_INHERITED
    }

    public static final class TemporalNode {
        private final String label;
        private final List<TemporalNode> children;
        private final int size;
        private final int ownerPhase;
        private final List<Integer> branchPhases;
        private final NormalForm sourceOwner;
        private final List<NormalForm> sourceBranches;

        TemporalNode(String label, List<? extends TemporalNode> children) {
            this(label, children, -1, List.of(), null, List.of());
        }

        TemporalNode(String label, List<? extends TemporalNode> children,
                int ownerPhase, List<Integer> branchPhases,
                NormalForm sourceOwner, List<NormalForm> sourceBranches) {
            this.label = requireText(label, "temporal label");
            this.children = immutable(children, "temporal child");
            this.ownerPhase = ownerPhase;
            this.branchPhases = immutableIntegers(branchPhases);
            this.sourceOwner = sourceOwner;
            this.sourceBranches = immutable(sourceBranches, "source temporal phase");
            if (this.branchPhases.size() != this.sourceBranches.size())
                throw new IllegalArgumentException("Temporal locators lack typed source phases");
            for (NormalForm phase : this.sourceBranches)
                if (!phase.isFrozenForCertification())
                    throw new IllegalArgumentException("Temporal source phase is not frozen");
            if (sourceOwner != null) {
                if (!sourceOwner.isFrozenForCertification() || ownerPhase < 0 || this.sourceBranches.isEmpty())
                    throw new IllegalArgumentException("Temporal source owner is not frozen");
                int first = -1;
                var actual = sourceOwner.getTemporalChildren();
                for (int i = 0; i < actual.size(); i++)
                    if (actual.get(i) == this.sourceBranches.get(0)) first = i;
                if (first < 0 || first + this.sourceBranches.size() > actual.size())
                    throw new IllegalArgumentException("Temporal branches lack original owner");
                for (int i = 0; i < this.sourceBranches.size(); i++)
                    if (actual.get(first + i) != this.sourceBranches.get(i))
                        throw new IllegalArgumentException("Temporal branch occurrence/order changed");
                var kind = this.sourceBranches.get(0).getTemporalOp();
                boolean binary = RepairProjection.isBinaryLeft(kind);
                if (this.sourceBranches.size() != (binary ? 2 : 1)
                        || !label.equals(RepairProjection.naturalTemporalLabel(kind))
                        || (binary && this.sourceBranches.get(1).getTemporalOp()
                                != RepairProjection.matchingRight(kind)))
                    throw new IllegalArgumentException("Temporal typed operator/branch roles changed");
            } else if (!this.sourceBranches.isEmpty()
                    && (ownerPhase != -1 || this.sourceBranches.size() != 1
                        || this.sourceBranches.get(0).getTemporalOp() != NormalForm.TemporalOp.NONE
                        || !"NONE".equals(label))) {
                throw new IllegalArgumentException("Temporal root source role changed");
            }
            int computed = 1;
            for (TemporalNode child : this.children) {
                computed = Math.addExact(computed, child.size);
            }
            this.size = computed;
        }

        public String label() {
            return label;
        }

        public List<TemporalNode> children() {
            return children;
        }

        public int size() {
            return size;
        }

        public int ownerPhase() { return ownerPhase; }
        public List<Integer> branchPhases() { return branchPhases; }
        NormalForm sourceOwner() { return sourceOwner; }
        List<NormalForm> sourceBranches() { return sourceBranches; }
    }

    /** One source-level quantifier repair tuple plus non-editable legality data. */
    public static final class Declaration {
        private final String quantifier;
        private final String type;
        private final String cardinality;
        private final int disjointnessClass;
        private final String certifiedDomain;
        private final int exchangeClass;
        private final List<Integer> dependencies;
        private final GraphType observationType;
        private final StructuralKey observationDomain;

        Declaration(
                String quantifier,
                String type,
                String cardinality,
                int disjointnessClass,
                String certifiedDomain,
                int exchangeClass,
                List<Integer> dependencies) {
            this(quantifier, type, cardinality, disjointnessClass, certifiedDomain,
                    exchangeClass, dependencies, null, null);
        }

        private Declaration(String quantifier, String type, String cardinality,
                int disjointnessClass, String certifiedDomain, int exchangeClass,
                List<Integer> dependencies, GraphType observationType, StructuralKey observationDomain) {
            this.quantifier = requireText(quantifier, "quantifier");
            this.type = requireText(type, "type");
            this.cardinality = requireText(cardinality, "cardinality");
            this.disjointnessClass = disjointnessClass;
            this.certifiedDomain = requireText(certifiedDomain, "certified domain");
            this.exchangeClass = exchangeClass;
            this.dependencies = immutableIntegers(dependencies);
            this.observationType = observationType;
            this.observationDomain = observationDomain;
        }

        Declaration withObservation(GraphType type, StructuralKey domain) {
            return new Declaration(quantifier, this.type, cardinality, disjointnessClass,
                    certifiedDomain, exchangeClass, dependencies, type, domain);
        }

        /** Structured observation metadata; never an additional metric charge. */
        public GraphType observationType() { return observationType; }
        public StructuralKey observationDomain() { return observationDomain; }

        public String quantifier() {
            return quantifier;
        }

        public String type() {
            return type;
        }

        public String cardinality() {
            return cardinality;
        }

        public int disjointnessClass() {
            return disjointnessClass;
        }

        public String certifiedDomain() {
            return certifiedDomain;
        }

        public int exchangeClass() {
            return exchangeClass;
        }

        public List<Integer> dependencies() {
            return dependencies;
        }

        /** The Fast Rewrite conceptual edit unit: any tuple modification costs one. */
        public boolean sameRepairTuple(Declaration other) {
            return other != null
                    && quantifier.equals(other.quantifier)
                    && type.equals(other.type)
                    && cardinality.equals(other.cardinality)
                    && disjointnessClass == other.disjointnessClass;
        }

        /** Fields used to establish a certificate-backed zero-cost alpha edge. */
        public boolean sameCertifiedPayload(Declaration other) {
            return sameRepairTuple(other)
                    && certifiedDomain.equals(other.certifiedDomain)
                    && dependencies.equals(other.dependencies);
        }
    }

    public static final class Binding {
        private final BindingRole role;
        private final int ordinal;
        private final int ownerPhase;
        private final int coordinate;
        private final Declaration declaration;
        private final String bindingPath;
        private final String ownerContext;
        private final List<Integer> certifiedOrbit;
        private final boolean prenexPathErasureCertified;

        Binding(
                BindingRole role,
                int ordinal,
                int ownerPhase,
                int coordinate,
                Declaration declaration,
                String bindingPath,
                List<Integer> certifiedOrbit,
                boolean prenexPathErasureCertified) {
            this(
                    role,
                    ordinal,
                    ownerPhase,
                    coordinate,
                    declaration,
                    bindingPath,
                    "",
                    certifiedOrbit,
                    prenexPathErasureCertified);
        }

        Binding(
                BindingRole role,
                int ordinal,
                int ownerPhase,
                int coordinate,
                Declaration declaration,
                String bindingPath,
                String ownerContext,
                List<Integer> certifiedOrbit,
                boolean prenexPathErasureCertified) {
            this.role = Objects.requireNonNull(role, "role");
            if (ordinal < 0) {
                throw new IllegalArgumentException("Binding ordinal must be non-negative");
            }
            this.ordinal = ordinal;
            this.ownerPhase = ownerPhase;
            this.coordinate = coordinate;
            this.declaration = Objects.requireNonNull(declaration, "declaration");
            this.bindingPath = bindingPath == null ? "" : bindingPath;
            this.ownerContext = ownerContext == null ? "" : ownerContext;
            this.certifiedOrbit = immutableIntegers(certifiedOrbit);
            this.prenexPathErasureCertified = prenexPathErasureCertified;
            for (int index = 0; index < this.certifiedOrbit.size(); index++) {
                int value = this.certifiedOrbit.get(index);
                if (value < 0 || index > 0
                        && this.certifiedOrbit.get(index - 1) >= value) {
                    throw new IllegalArgumentException(
                            "A certified binder orbit must be strictly increasing");
                }
            }
            if (role == BindingRole.PARAMETER && (ownerPhase != -1 || coordinate != -1)) {
                throw new IllegalArgumentException(
                        "Parameter bindings cannot claim a binder coordinate");
            }
            if (role == BindingRole.PARAMETER && prenexPathErasureCertified) {
                throw new IllegalArgumentException(
                        "Parameter bindings cannot claim prenex path-erasure authority");
            }
            if (role == BindingRole.PARAMETER && !this.certifiedOrbit.isEmpty()) {
                throw new IllegalArgumentException(
                        "Parameter bindings cannot claim a binder orbit");
            }
            if (role == BindingRole.LOCAL_INHERITED && this.ownerContext.isBlank()) {
                throw new IllegalArgumentException(
                        "A phase-local inherited binding requires its owner context");
            }
            if (role != BindingRole.LOCAL_INHERITED && !this.ownerContext.isEmpty()) {
                throw new IllegalArgumentException(
                        "Only phase-local inherited bindings may carry a local owner context");
            }
            if (role != BindingRole.PARAMETER
                    && (ownerPhase < 0 || coordinate < 0
                            || !this.certifiedOrbit.contains(coordinate))) {
                throw new IllegalArgumentException(
                        "Quantified bindings require a certified owner coordinate and orbit");
            }
            if (role != BindingRole.PARAMETER
                    && !prenexPathErasureCertified
                    && this.bindingPath.isBlank()) {
                throw new IllegalArgumentException(
                        "An uncertified quantified binding must retain its lexical path");
            }
        }

        public BindingRole role() {
            return role;
        }

        public int ordinal() {
            return ordinal;
        }

        public int ownerPhase() {
            return ownerPhase;
        }

        public int coordinate() {
            return coordinate;
        }

        public Declaration declaration() {
            return declaration;
        }

        public String bindingPath() {
            return bindingPath;
        }

        public String ownerContext() {
            return ownerContext;
        }

        public List<Integer> certifiedOrbit() {
            return certifiedOrbit;
        }

        public boolean prenexPathErasureCertified() {
            return prenexPathErasureCertified;
        }
    }

    /** One Fast Rewrite matrix e-node with certified flexible-container semantics. */
    public static final class Node {
        private final String operator;
        private final String payload;
        private final String semanticPayload;
        private final String lexicalVariable;
        private final int bindingIndex;
        private final ContainerKind containerKind;
        private final boolean orderInsensitive;
        private final List<Node> children;
        private final List<Node> certifiedAlternatives;
        private final int size;
        private final GraphType observationType;
        private final List<Integer> observationPhases;
        private final String observationTemporalOperator;
        private final EGraphNode sourceOccurrence;
        private final NormalForm.TemporalReferenceOrigin referenceOrigin;

        Node(
                String operator,
                String payload,
                String lexicalVariable,
                int bindingIndex,
                ContainerKind containerKind,
                boolean orderInsensitive,
                List<? extends Node> children) {
            this(
                    operator,
                    payload,
                    payload,
                    lexicalVariable,
                    bindingIndex,
                    containerKind,
                    orderInsensitive,
                    children,
                    Collections.emptyList());
        }

        Node(
                String operator,
                String payload,
                String lexicalVariable,
                int bindingIndex,
                ContainerKind containerKind,
                boolean orderInsensitive,
                List<? extends Node> children,
                List<? extends Node> certifiedAlternatives) {
            this(
                    operator,
                    payload,
                    payload,
                    lexicalVariable,
                    bindingIndex,
                    containerKind,
                    orderInsensitive,
                    children,
                    certifiedAlternatives);
        }

        Node(
                String operator,
                String payload,
                String semanticPayload,
                String lexicalVariable,
                int bindingIndex,
                ContainerKind containerKind,
                boolean orderInsensitive,
                List<? extends Node> children) {
            this(
                    operator,
                    payload,
                    semanticPayload,
                    lexicalVariable,
                    bindingIndex,
                    containerKind,
                    orderInsensitive,
                    children,
                    Collections.emptyList());
        }

        Node(
                String operator,
                String payload,
                String semanticPayload,
                String lexicalVariable,
                int bindingIndex,
                ContainerKind containerKind,
                boolean orderInsensitive,
                List<? extends Node> children,
                List<? extends Node> certifiedAlternatives) {
            this(operator, payload, semanticPayload, lexicalVariable, bindingIndex, containerKind,
                    orderInsensitive, children, certifiedAlternatives, null, List.of(), null, null, null);
        }

        private Node(String operator, String payload, String semanticPayload, String lexicalVariable,
                int bindingIndex, ContainerKind containerKind, boolean orderInsensitive,
                List<? extends Node> children, List<? extends Node> certifiedAlternatives,
                GraphType observationType, List<Integer> observationPhases, String observationTemporalOperator,
                EGraphNode sourceOccurrence, NormalForm.TemporalReferenceOrigin referenceOrigin) {
            this.operator = requireText(operator, "operator");
            this.payload = payload;
            this.semanticPayload = semanticPayload;
            this.lexicalVariable = lexicalVariable;
            this.bindingIndex = bindingIndex;
            this.containerKind = Objects.requireNonNull(containerKind, "containerKind");
            this.orderInsensitive = orderInsensitive;
            this.children = immutable(children, "matrix child");
            this.certifiedAlternatives = immutable(
                    certifiedAlternatives, "matrix certified alternative");
            if ("VARIABLE".equals(operator)) {
                if (lexicalVariable == null) {
                    throw new IllegalArgumentException(
                            "A variable must retain its readable lexical identity");
                }
                if (bindingIndex < -1) {
                    throw new IllegalArgumentException(
                            "A variable binding index must be -1 or non-negative");
                }
            } else if (bindingIndex >= 0 || lexicalVariable != null) {
                throw new IllegalArgumentException(
                        "Only VARIABLE nodes may carry a variable identity");
            }
            int computed = 1;
            for (Node child : this.children) {
                computed = Math.addExact(computed, child.size);
            }
            this.size = computed;
            this.observationType = observationType;
            this.observationPhases = immutableIntegers(observationPhases);
            this.observationTemporalOperator = observationTemporalOperator;
            this.sourceOccurrence = sourceOccurrence;
            this.referenceOrigin = referenceOrigin;
        }

        Node withObservation(GraphType type, List<Integer> phases, String temporalOperator) {
            return new Node(operator, payload, semanticPayload, lexicalVariable, bindingIndex, containerKind,
                    orderInsensitive, children, certifiedAlternatives, type, phases, temporalOperator,
                    sourceOccurrence, referenceOrigin);
        }

        Node withObservationFrom(Node source) {
            return new Node(operator, payload, semanticPayload, lexicalVariable, bindingIndex, containerKind,
                    orderInsensitive, children, certifiedAlternatives, source.observationType,
                    source.observationPhases, source.observationTemporalOperator,
                    source.sourceOccurrence, source.referenceOrigin);
        }

        Node withSourceOccurrence(EGraphNode source) {
            return new Node(operator, payload, semanticPayload, lexicalVariable, bindingIndex, containerKind,
                    orderInsensitive, children, certifiedAlternatives, observationType,
                    observationPhases, observationTemporalOperator, source, referenceOrigin);
        }

        Node withReferenceOrigin(NormalForm.TemporalReferenceOrigin origin) {
            return new Node(operator, payload, semanticPayload, lexicalVariable, bindingIndex, containerKind,
                    orderInsensitive, children, certifiedAlternatives, observationType,
                    observationPhases, observationTemporalOperator, origin.reference(), origin);
        }

        EGraphNode sourceOccurrence() { return sourceOccurrence; }
        NormalForm.TemporalReferenceOrigin referenceOrigin() { return referenceOrigin; }

        /** Null means a structural control node or an unannotated test fixture. */
        public GraphType observationType() { return observationType; }
        public List<Integer> observationPhases() { return observationPhases; }
        public String observationTemporalOperator() { return observationTemporalOperator; }

        /** Set only when projection resolves an actual source temporal edge. */
        public boolean isTemporalReference() {
            return "REF".equals(operator) && observationTemporalOperator != null
                    && !observationPhases.isEmpty();
        }

        public String operator() {
            return operator;
        }

        public String payload() {
            return payload;
        }

        /** Hidden certified identity used for edit equality; payload stays readable. */
        public String semanticPayload() {
            return semanticPayload;
        }

        public boolean isVariable() {
            return "VARIABLE".equals(operator);
        }

        public String lexicalVariable() {
            return lexicalVariable;
        }

        public int bindingIndex() {
            return bindingIndex;
        }

        public ContainerKind containerKind() {
            return containerKind;
        }

        public boolean orderInsensitive() {
            return orderInsensitive;
        }

        public List<Node> children() {
            return children;
        }

        /** Certified quotient representatives, excluding this wrapper. */
        public List<Node> certifiedAlternatives() {
            return certifiedAlternatives;
        }

        public int minimumRepresentativeSize() {
            if (certifiedAlternatives.isEmpty()) {
                return size;
            }
            int minimum = Integer.MAX_VALUE;
            for (Node alternative : certifiedAlternatives) {
                minimum = Math.min(
                        minimum, alternative.minimumRepresentativeSize());
            }
            return minimum;
        }

        public int size() {
            return size;
        }
    }

    public static final class Phase {
        private final List<Declaration> quantifiers;
        private final List<Binding> bindings;
        private final Node matrix;

        Phase(
                List<? extends Declaration> quantifiers,
                List<? extends Binding> bindings,
                Node matrix) {
            this.quantifiers = immutable(quantifiers, "quantifier");
            this.bindings = immutable(bindings, "binding");
            this.matrix = matrix;
            requireBindingIndices(matrix, this.bindings.size());
        }

        private static void requireBindingIndices(Node node, int bindingCount) {
            if (node == null) {
                return;
            }
            if (node.isVariable() && node.bindingIndex >= bindingCount) {
                throw new IllegalArgumentException(
                        "A matrix variable references an absent phase binding");
            }
            for (Node child : node.children) {
                requireBindingIndices(child, bindingCount);
            }
            for (Node alternative : node.certifiedAlternatives) {
                requireBindingIndices(alternative, bindingCount);
            }
        }

        public List<Declaration> quantifiers() {
            return quantifiers;
        }

        public List<Binding> bindings() {
            return bindings;
        }

        public Node matrix() {
            return matrix;
        }
    }

    private final TemporalNode temporalRoot;
    private final List<Phase> phases;
    private final SemanticProfile semanticProfile;
    private final StructuralKey producerObservationKey;
    private final String producerObservationDigest;
    private final boolean certifiedProjection;
    private final int semanticSize;
    record PhaseCoordinate(int originalPhase, NormalForm source) {
        PhaseCoordinate {
            if(originalPhase<0 || source==null || !source.isFrozenForCertification())
                throw new IllegalArgumentException("Phase projection lost original frozen occurrence");
        }
    }
    private final List<PhaseCoordinate> phaseCoordinates;

    RepairView(
            TemporalNode temporalRoot,
            List<? extends Phase> phases,
            SemanticProfile semanticProfile,
            StructuralKey producerObservationKey) {
        this(
                temporalRoot,
                phases,
                semanticProfile,
                producerObservationKey,
                false, List.of());
    }

    private RepairView(
            TemporalNode temporalRoot,
            List<? extends Phase> phases,
            SemanticProfile semanticProfile,
            StructuralKey producerObservationKey,
            boolean certifiedProjection, List<PhaseCoordinate> phaseCoordinates) {
        this.temporalRoot = Objects.requireNonNull(temporalRoot, "temporalRoot");
        this.phases = immutable(phases, "phase");
        this.semanticProfile = Objects.requireNonNull(
                semanticProfile, "semanticProfile");
        this.producerObservationKey = Objects.requireNonNull(
                producerObservationKey, "producerObservationKey");
        this.producerObservationDigest = new CanonicalObservation(
                this.producerObservationKey).digest();
        this.certifiedProjection = certifiedProjection;
        this.phaseCoordinates = List.copyOf(phaseCoordinates);
        if(certifiedProjection && this.phaseCoordinates.size()!=this.phases.size())
            throw new IllegalArgumentException("Projected phase coordinate coverage");
        int computed = this.phases.size();
        for (Phase phase : this.phases) {
            computed = Math.addExact(computed, phase.quantifiers.size());
            if (phase.matrix != null) {
                computed = Math.addExact(computed, phase.matrix.size());
            }
        }
        this.semanticSize = computed;
    }

    static RepairView fromCertifiedProjection(RepairProjection.Projection projection) {
        Objects.requireNonNull(projection, "projection");
        return new RepairView(
                projection.temporalRoot,
                projection.phases,
                projection.semanticProfile,
                projection.producerObservationKey,
                true, projection.phaseCoordinates);
    }

    /** Retained projection decision, not a new permutation search or equality proof. */
    public org.json.JSONObject phaseRepresentation(String side) {
        if(!side.equals("source")&&!side.equals("target"))throw new IllegalArgumentException("Phase endpoint side");
        var rows=new org.json.JSONArray();boolean changed=false;
        for(int i=0;i<phaseCoordinates.size();i++) {
            var coordinate=phaseCoordinates.get(i);var original=coordinate.source();
            changed|=coordinate.originalPhase()!=i;
            rows.put(new org.json.JSONObject().put("comparisonPhase",i).put("originalPhase",coordinate.originalPhase())
                    .put("sourcePath",original.getPhasePath()).put("typedRole",original.getTemporalOp().name()));
        }
        if(!changed)return null;
        var step=new org.json.JSONObject().put("kind","source-linked-phase-reindex").put("cost",0)
                .put("side",side).put("phases",rows);
        TraceResources.requireJson(step);return step;
    }

    public TemporalNode temporalRoot() {
        return temporalRoot;
    }

    public List<Phase> phases() {
        return phases;
    }

    public SemanticProfile semanticProfile() {
        return semanticProfile;
    }

    public String producerObservationDigest() {
        return producerObservationDigest;
    }

    boolean hasSameProducerObservation(RepairView other) {
        return other != null
                && certifiedProjection
                && other.certifiedProjection
                && semanticProfile.equals(other.semanticProfile)
                && producerObservationKey.equals(other.producerObservationKey);
    }

    boolean hasSameClaimedProducerObservationForTesting(RepairView other) {
        return other != null
                && semanticProfile.equals(other.semanticProfile)
                && producerObservationKey.equals(other.producerObservationKey);
    }

    void requireCertifiedProjection() {
        if (!certifiedProjection) {
            throw new IllegalStateException(
                    "Kernel-checked repair evaluation requires certified projections");
        }
    }

    /** Exactly the established normal-form representation-size denominator. */
    public int semanticSize() {
        return semanticSize;
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static List<Integer> immutableIntegers(List<Integer> source) {
        Objects.requireNonNull(source, "integer list");
        List<Integer> result = new ArrayList<>(source.size());
        for (Integer value : source) {
            result.add(Objects.requireNonNull(value, "integer"));
        }
        return Collections.unmodifiableList(result);
    }

    private static <T> List<T> immutable(List<? extends T> source, String label) {
        Objects.requireNonNull(source, label + "s");
        List<T> result = new ArrayList<>(source.size());
        for (T value : source) {
            result.add(Objects.requireNonNull(value, label));
        }
        return Collections.unmodifiableList(result);
    }
}
