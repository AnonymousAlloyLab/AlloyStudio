package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One complete finite derivation of {@code Rep_G(m*a,t;iota)} before a final
 * weakening. Every invocation leaf has been replaced by another complete tree.
 * This is a proof/equality observation, not the repair-metric tree; Layer 3
 * consumes a separate certified repair view of the repaired normal form.
 */
public final class FiniteUnfoldingTree {
    private final TypedInvocation rootInvocation;
    private final TypedEClassInterface shapeOwner;
    private final CanonicalShape selectedShape;
    private final ShapeWitness shapeWitness;
    private final TypedEqualityCertificate shapeCoherence;
    private final TypedENode restoredRoot;
    private final List<FiniteUnfoldingTree> invocationChildren;
    private final int height;
    private volatile FiniteUnfoldingIndexTrace indexTrace;
    private volatile StructuralKey structuralKey;
    private final StructuralKey enumerationKey;
    private final StructuralKey normalizedTermKey;

    private FiniteUnfoldingTree(
            TypedInvocation rootInvocation,
            TypedEClassRecord owner,
            CanonicalShape selectedShape,
            ShapeWitness shapeWitness,
            TypedEqualityCertificate shapeCoherence,
            List<? extends FiniteUnfoldingTree> invocationChildren) {
        this.rootInvocation = Objects.requireNonNull(rootInvocation, "rootInvocation");
        TypedEClassRecord checkedOwner = Objects.requireNonNull(owner, "owner");
        this.shapeOwner = checkedOwner.interfaceView();
        this.selectedShape = Objects.requireNonNull(selectedShape, "selectedShape");
        this.shapeWitness = Objects.requireNonNull(shapeWitness, "shapeWitness");
        Objects.requireNonNull(invocationChildren, "invocationChildren");

        if (!rootInvocation.eclass().equals(shapeOwner)) {
            throw new IllegalArgumentException(
                    "A finite unfolding must choose a shape stored by its root e-class");
        }
        ShapeWitness stored = checkedOwner.shapeWitnesses().get(selectedShape);
        if (!shapeWitness.equals(stored)) {
            throw new IllegalArgumentException(
                    "A finite unfolding must retain the exact stored shape witness");
        }

        this.restoredRoot = selectedShape.node().act(
                shapeWitness.instantiatingRenaming());
        TypedEqualityCertificate oriented =
                EffectiveShapeCollisionCertificate.orientShapeEquation(
                        selectedShape,
                        checkedOwner,
                        shapeWitness,
                        Objects.requireNonNull(shapeCoherence, "shapeCoherence"));
        CertificateVerifier.verify(oriented);
        this.shapeCoherence = oriented;

        List<TypedInvocation> expectedChildren = invocationLeaves(restoredRoot);
        if (expectedChildren.size() != invocationChildren.size()) {
            throw new IllegalArgumentException(
                    "A complete finite unfolding needs one child tree per invocation occurrence");
        }
        List<FiniteUnfoldingTree> copied = new ArrayList<>(invocationChildren.size());
        int computedHeight = 1;
        for (int index = 0; index < invocationChildren.size(); index++) {
            FiniteUnfoldingTree child = Objects.requireNonNull(
                    invocationChildren.get(index), "invocation child");
            if (!expectedChildren.get(index).equals(child.rootInvocation())) {
                throw new IllegalArgumentException(
                        "Finite-unfolding child does not match its invocation occurrence");
            }
            copied.add(child);
            computedHeight = Math.max(computedHeight, Math.addExact(1, child.height()));
        }
        this.invocationChildren = Collections.unmodifiableList(copied);
        this.height = computedHeight;
        List<StructuralKey> enumerationParts = new ArrayList<>(copied.size() + 3);
        enumerationParts.add(TheoryKeys.invocation(rootInvocation));
        enumerationParts.add(selectedShape.structuralKey());
        enumerationParts.add(shapeWitness.structuralKey());
        for (FiniteUnfoldingTree child : copied) {
            enumerationParts.add(child.enumerationKey());
        }
        this.enumerationKey = StructuralKey.branch(
                "finite-unfolding-enumeration", enumerationParts);
        this.normalizedTermKey = Normalizer.normalize(this);
    }

    private StructuralKey materializeStructuralKey() {
        List<StructuralKey> keyParts = new ArrayList<>(invocationChildren.size() + 6);
        keyParts.add(TheoryKeys.invocation(rootInvocation));
        keyParts.add(TheoryKeys.eclass(shapeOwner));
        keyParts.add(selectedShape.structuralKey());
        keyParts.add(shapeWitness.structuralKey());
        keyParts.add(shapeCoherence.structuralKey());
        keyParts.add(indexTrace().structuralKey());
        for (FiniteUnfoldingTree child : invocationChildren) {
            keyParts.add(child.structuralKey());
        }
        return StructuralKey.branch("finite-unfolding-tree", keyParts);
    }

    static FiniteUnfoldingTree create(
            TypedInvocation rootInvocation,
            TypedEClassRecord owner,
            CanonicalShape selectedShape,
            ShapeWitness shapeWitness,
            TypedEqualityCertificate shapeCoherence,
            List<? extends FiniteUnfoldingTree> invocationChildren) {
        return new FiniteUnfoldingTree(
                rootInvocation,
                owner,
                selectedShape,
                shapeWitness,
                shapeCoherence,
                invocationChildren);
    }

    static List<TypedInvocation> invocationLeaves(TypedENode node) {
        Objects.requireNonNull(node, "node");
        List<TypedInvocation> result = new ArrayList<>();
        for (PortValue port : node.ports()) {
            collectInvocations(port, result);
        }
        return Collections.unmodifiableList(result);
    }

    private static void collectInvocations(
            PortValue port,
            List<TypedInvocation> output) {
        if (port instanceof OnePort) {
            PortLeaf leaf = ((OnePort) port).leaf();
            if (leaf instanceof InvocationPortLeaf) {
                output.add(((InvocationPortLeaf) leaf).invocation());
            }
            return;
        }
        if (port instanceof SeqPort) {
            for (PortValue element : ((SeqPort) port).elements()) {
                collectInvocations(element, output);
            }
            return;
        }
        if (port instanceof BagPort) {
            for (PortValue element : ((BagPort) port).occurrences()) {
                collectInvocations(element, output);
            }
            return;
        }
        if (port instanceof SetPort) {
            for (PortValue element : ((SetPort) port).elements()) {
                collectInvocations(element, output);
            }
            return;
        }
        if (port instanceof BindPort) {
            collectInvocations(((BindPort) port).body(), output);
            return;
        }
        if (port instanceof BindBlockPort) {
            collectInvocations(((BindBlockPort) port).body(), output);
            return;
        }
        throw new IllegalStateException("Unhandled port value " + port.getClass().getName());
    }

    public TypedInvocation rootInvocation() {
        return rootInvocation;
    }

    public TypedEClassInterface shapeOwner() {
        return shapeOwner;
    }

    public CanonicalShape selectedShape() {
        return selectedShape;
    }

    public ShapeWitness shapeWitness() {
        return shapeWitness;
    }

    public TypedEqualityCertificate shapeCoherence() {
        return shapeCoherence;
    }

    /** The selected shape after restoring its exact ambient witness. */
    public TypedENode restoredRoot() {
        return restoredRoot;
    }

    /** Child trees in deterministic depth-first port occurrence order. */
    public List<FiniteUnfoldingTree> invocationChildren() {
        return invocationChildren;
    }

    public int height() {
        return height;
    }

    /** Explicit indexed weakening and fresh-extension witnesses for this tree. */
    public FiniteUnfoldingIndexTrace indexTrace() {
        FiniteUnfoldingIndexTrace result = indexTrace;
        if (result == null) {
            synchronized (this) {
                result = indexTrace;
                if (result == null) {
                    result = FiniteUnfoldingIndexTrace.materialize(this);
                    indexTrace = result;
                }
            }
        }
        return result;
    }

    /** Complete proof-trace identity, including the retained EC at every node. */
    public StructuralKey structuralKey() {
        StructuralKey result = structuralKey;
        if (result == null) {
            synchronized (this) {
                result = structuralKey;
                if (result == null) {
                    result = materializeStructuralKey();
                    structuralKey = result;
                }
            }
        }
        return result;
    }

    StructuralKey enumerationKey() {
        return enumerationKey;
    }

    /**
     * The realized finite term modulo typed alpha-equivalence, certified binder
     * automorphisms, and the declared Seq=A, Bag=AC, and Set=ACI container laws.
     * Input equations are deliberately not folded into this key; a validation
     * observer supplies those semantics.
     */
    public StructuralKey normalizedTermKey() {
        return normalizedTermKey;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FiniteUnfoldingTree
                && structuralKey().equals(((FiniteUnfoldingTree) other).structuralKey());
    }

    @Override
    public int hashCode() {
        return structuralKey().hashCode();
    }

    @Override
    public String toString() {
        return normalizedTermKey.stableString();
    }

    private static final class Normalizer {
        private final NavigableMap<GraphType, Integer> freshOrdinals = new TreeMap<>();

        static StructuralKey normalize(FiniteUnfoldingTree tree) {
            Normalizer normalizer = new Normalizer();
            Map<TypedSlot, StructuralKey> caller = new LinkedHashMap<>();
            for (TypedSlot slot : tree.rootInvocation().callerContext()) {
                caller.put(slot, StructuralKey.branch(
                        "finite-term/free-slot",
                        Collections.singletonList(TheoryKeys.slot(slot))));
            }
            return normalizer.normalizeTree(tree, caller, 0);
        }

        private StructuralKey normalizeTree(
                FiniteUnfoldingTree tree,
                Map<TypedSlot, StructuralKey> callerEnvironment,
                int binderDepth) {
            if (!callerEnvironment.keySet().equals(
                    tree.rootInvocation().callerContext().slots())) {
                throw new IllegalStateException(
                        "Finite-term environment does not cover the invocation caller context");
            }
            Map<TypedSlot, StructuralKey> ambient = new LinkedHashMap<>();
            TypedSlotContext exposed = tree.shapeWitness().exposedInterface();
            TypedSlotContext witnessAmbient = tree.shapeWitness().ambientSupport();
            for (TypedSlot slot : exposed) {
                TypedSlot callerSlot = tree.rootInvocation().embedding().apply(slot);
                StructuralKey value = callerEnvironment.get(callerSlot);
                if (value == null) {
                    throw new IllegalStateException(
                            "Invocation embedding targets an unbound finite-term coordinate");
                }
                ambient.put(slot, value);
            }
            for (TypedSlot slot : witnessAmbient.minus(exposed)) {
                ambient.put(slot, fresh(slot.type()));
            }
            if (!ambient.keySet().equals(witnessAmbient.slots())) {
                throw new IllegalStateException(
                        "Restored shape environment does not cover its exact ambient support");
            }

            ChildCursor cursor = new ChildCursor(tree.invocationChildren());
            List<StructuralKey> ports = new ArrayList<>();
            for (PortValue port : tree.restoredRoot().ports()) {
                ports.add(normalizePort(port, ambient, binderDepth, cursor));
            }
            cursor.requireExhausted();
            List<StructuralKey> nodeParts = new ArrayList<>(ports.size() + 1);
            nodeParts.add(tree.restoredRoot().operator().structuralKey());
            nodeParts.addAll(ports);
            return StructuralKey.branch("finite-term/node", nodeParts);
        }

        private StructuralKey normalizePort(
                PortValue port,
                Map<TypedSlot, StructuralKey> environment,
                int binderDepth,
                ChildCursor cursor) {
            if (port instanceof OnePort) {
                PortLeaf leaf = ((OnePort) port).leaf();
                StructuralKey value;
                if (leaf instanceof SlotPortLeaf) {
                    TypedSlot slot = ((SlotPortLeaf) leaf).slot();
                    value = environment.get(slot);
                    if (value == null) {
                        throw new IllegalStateException(
                                "Finite-term slot is absent from its scoped environment");
                    }
                } else {
                    FiniteUnfoldingTree child = cursor.next(
                            ((InvocationPortLeaf) leaf).invocation());
                    value = normalizeTree(child, environment, binderDepth);
                }
                return StructuralKey.of(
                        "finite-term/one",
                        Collections.emptyList(),
                        java.util.Arrays.asList(port.schema().structuralKey(), value));
            }
            if (port instanceof SeqPort) {
                List<StructuralKey> values = normalizePorts(
                        ((SeqPort) port).elements(), environment, binderDepth, cursor);
                return containerKey("finite-term/seq", port.schema(), values);
            }
            if (port instanceof BagPort) {
                List<StructuralKey> values = normalizePorts(
                        ((BagPort) port).occurrences(), environment, binderDepth, cursor);
                Collections.sort(values);
                return containerKey("finite-term/bag", port.schema(), values);
            }
            if (port instanceof SetPort) {
                List<StructuralKey> values = normalizePorts(
                        ((SetPort) port).elements(), environment, binderDepth, cursor);
                NavigableMap<StructuralKey, StructuralKey> unique = new TreeMap<>();
                for (StructuralKey value : values) {
                    unique.put(value, value);
                }
                return containerKey(
                        "finite-term/set",
                        port.schema(),
                        new ArrayList<>(unique.values()));
            }
            if (port instanceof BindPort) {
                BindPort binder = (BindPort) port;
                Map<TypedSlot, StructuralKey> bodyEnvironment =
                        new LinkedHashMap<>(environment);
                bodyEnvironment.put(
                        binder.boundSlot(),
                        StructuralKey.of(
                                "finite-term/bound-slot",
                                Collections.singletonList(Integer.toString(binderDepth)),
                                Collections.singletonList(
                                        TheoryKeys.type(binder.boundSlot().type()))));
                StructuralKey body = normalizePort(
                        binder.body(), bodyEnvironment, binderDepth + 1, cursor);
                return StructuralKey.of(
                        "finite-term/bind",
                        Collections.emptyList(),
                        java.util.Arrays.asList(binder.schema().structuralKey(), body));
            }
            if (port instanceof BindBlockPort) {
                BindBlockPort block = (BindBlockPort) port;
                BinderBlockDescriptor descriptor = block.schema().descriptor();
                Map<TypedSlot, StructuralKey> bodyEnvironment =
                        new LinkedHashMap<>(environment);
                for (Map.Entry<TypedSlot, TypedSlot> coordinate
                        : block.descriptorToOccurrence().mapping().entrySet()) {
                    bodyEnvironment.put(
                            coordinate.getValue(),
                            StructuralKey.of(
                                            "finite-term/bound-block-coordinate",
                                    Collections.singletonList(Integer.toString(binderDepth)),
                                    java.util.Arrays.asList(
                                            descriptor.structuralKey(),
                                            TheoryKeys.slot(coordinate.getKey()))));
                }
                StructuralKey sourceBody = normalizePort(
                        block.body(), bodyEnvironment, binderDepth + 1, cursor);
                LeastOption<StructuralKey> bodies = new LeastOption<>(
                        StructuralKey::compareTo);
                descriptor.automorphisms().forEachElement(permutation -> {
                    StructuralKey candidate = permuteBlockCoordinates(
                            sourceBody, descriptor, binderDepth, permutation);
                    bodies.consider(candidate);
                });
                StructuralKey body = bodies.orElseThrow(() ->
                        new IllegalStateException(
                                "A binder automorphism group must contain identity"));
                return StructuralKey.of(
                        "finite-term/bind-block",
                        Collections.emptyList(),
                        java.util.Arrays.asList(
                                block.schema().structuralKey(),
                                block.schema().descriptor().structuralKey(),
                                body));
            }
            throw new IllegalStateException(
                    "Unhandled port value " + port.getClass().getName());
        }

        private StructuralKey permuteBlockCoordinates(
                StructuralKey key,
                BinderBlockDescriptor descriptor,
                int binderDepth,
                TypedPermutation permutation) {
            if ("finite-term/bound-block-coordinate".equals(key.tag())
                    && key.scalars().equals(Collections.singletonList(
                            Integer.toString(binderDepth)))
                    && key.children().size() == 2
                    && key.children().get(0).equals(descriptor.structuralKey())) {
                StructuralKey sourceSlot = key.children().get(1);
                for (BinderCoordinateDescriptor coordinate : descriptor.coordinates()) {
                    if (sourceSlot.equals(TheoryKeys.slot(coordinate.canonicalSlot()))) {
                        return StructuralKey.of(
                                key.tag(),
                                key.scalars(),
                                java.util.Arrays.asList(
                                        descriptor.structuralKey(),
                                        TheoryKeys.slot(permutation.apply(
                                                coordinate.canonicalSlot()))));
                    }
                }
                throw new IllegalStateException(
                        "Finite-term block marker is outside its descriptor");
            }
            List<StructuralKey> children = new ArrayList<>(key.children().size());
            boolean changed = false;
            for (StructuralKey child : key.children()) {
                StructuralKey transformed = permuteBlockCoordinates(
                        child, descriptor, binderDepth, permutation);
                children.add(transformed);
                changed |= transformed != child;
            }
            if (!changed) {
                return key;
            }
            if ("finite-term/bag".equals(key.tag())) {
                List<StructuralKey> values = new ArrayList<>(children.subList(1, children.size()));
                Collections.sort(values);
                List<StructuralKey> normalized = new ArrayList<>(children.size());
                normalized.add(children.get(0));
                normalized.addAll(values);
                children = normalized;
            } else if ("finite-term/set".equals(key.tag())) {
                NavigableMap<StructuralKey, StructuralKey> unique = new TreeMap<>();
                for (int index = 1; index < children.size(); index++) {
                    StructuralKey child = children.get(index);
                    unique.put(child, child);
                }
                List<StructuralKey> normalized = new ArrayList<>(unique.size() + 1);
                normalized.add(children.get(0));
                normalized.addAll(unique.values());
                children = normalized;
            }
            return StructuralKey.of(key.tag(), key.scalars(), children);
        }

        private List<StructuralKey> normalizePorts(
                List<PortValue> ports,
                Map<TypedSlot, StructuralKey> environment,
                int binderDepth,
                ChildCursor cursor) {
            List<StructuralKey> result = new ArrayList<>(ports.size());
            for (PortValue port : ports) {
                result.add(normalizePort(port, environment, binderDepth, cursor));
            }
            return result;
        }

        private static StructuralKey containerKey(
                String tag,
                PortSchema schema,
                List<StructuralKey> values) {
            List<StructuralKey> children = new ArrayList<>(values.size() + 1);
            children.add(schema.structuralKey());
            children.addAll(values);
            return StructuralKey.branch(tag, children);
        }

        private StructuralKey fresh(GraphType type) {
            int ordinal = freshOrdinals.getOrDefault(type, 0);
            freshOrdinals.put(type, Math.addExact(ordinal, 1));
            return StructuralKey.of(
                    "finite-term/fresh-redundant-slot",
                    Collections.singletonList(Integer.toString(ordinal)),
                    Collections.singletonList(TheoryKeys.type(type)));
        }
    }

    private static final class ChildCursor {
        private final List<FiniteUnfoldingTree> children;
        private int index;

        private ChildCursor(List<FiniteUnfoldingTree> children) {
            this.children = children;
        }

        private FiniteUnfoldingTree next(TypedInvocation expected) {
            if (index >= children.size()) {
                throw new IllegalStateException("Finite-term child cursor is exhausted");
            }
            FiniteUnfoldingTree child = children.get(index++);
            if (!expected.equals(child.rootInvocation())) {
                throw new IllegalStateException(
                        "Finite-term child cursor does not match the invocation occurrence");
            }
            return child;
        }

        private void requireExhausted() {
            if (index != children.size()) {
                throw new IllegalStateException(
                        "Finite-term normalization did not consume every invocation child");
            }
        }
    }
}
