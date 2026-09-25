package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable realization of {@code M(a)=(tau_a,S_a,B_a,G_a)}. */
public final class TypedEClassRecord {
    private final TypedEClassInterface interfaceView;
    private final NavigableMap<CanonicalShape, ShapeWitness> shapeWitnesses;
    private final TypedSymmetryGroup symmetryGroup;
    private final StructuralKey structuralKey;

    private TypedEClassRecord(
            TypedEClassInterface interfaceView,
            Map<CanonicalShape, ShapeWitness> shapeWitnesses,
            TypedSymmetryGroup symmetryGroup) {
        this.interfaceView = Objects.requireNonNull(interfaceView, "interfaceView");
        Objects.requireNonNull(shapeWitnesses, "shapeWitnesses");
        this.symmetryGroup = Objects.requireNonNull(symmetryGroup, "symmetryGroup");
        if (!interfaceView.exposedSlots().equals(symmetryGroup.context())) {
            throw new IllegalArgumentException(
                    "An e-class symmetry group must act on its exposed interface");
        }

        NavigableMap<CanonicalShape, ShapeWitness> copied = new TreeMap<>();
        for (Map.Entry<CanonicalShape, ShapeWitness> entry : shapeWitnesses.entrySet()) {
            CanonicalShape shape = Objects.requireNonNull(entry.getKey(), "shape");
            ShapeWitness witness = Objects.requireNonNull(entry.getValue(), "shape witness");
            validateShape(shape, witness);
            putShape(copied, shape, witness);
        }
        this.shapeWitnesses = Collections.unmodifiableNavigableMap(copied);

        ArrayList<StructuralKey> children = new ArrayList<>();
        children.add(TheoryKeys.eclass(interfaceView));
        children.add(symmetryGroup.structuralKey());
        for (Map.Entry<CanonicalShape, ShapeWitness> entry : copied.entrySet()) {
            children.add(StructuralKey.branch(
                    "stored-shape",
                    java.util.Arrays.asList(
                            entry.getKey().structuralKey(),
                            entry.getValue().structuralKey())));
        }
        this.structuralKey = StructuralKey.branch("eclass-record", children);
    }

    public static TypedEClassRecord empty(TypedEClassInterface interfaceView) {
        Objects.requireNonNull(interfaceView, "interfaceView");
        return new TypedEClassRecord(
                interfaceView,
                Collections.emptyMap(),
                TypedSymmetryGroup.identity(interfaceView.exposedSlots()));
    }

    public static TypedEClassRecord of(
            TypedEClassInterface interfaceView,
            Map<CanonicalShape, ShapeWitness> shapeWitnesses,
            TypedSymmetryGroup symmetryGroup) {
        return new TypedEClassRecord(interfaceView, shapeWitnesses, symmetryGroup);
    }

    private void validateShape(CanonicalShape shape, ShapeWitness witness) {
        if (!interfaceView.outputType().equals(shape.outputType())) {
            throw new IllegalArgumentException(
                    "Every stored shape must have the e-class output type");
        }
        if (!shape.exactSlots().equals(witness.exactSlots())) {
            throw new IllegalArgumentException(
                    "Stored witness exact slots must equal the canonical shape slots");
        }
        if (!interfaceView.exposedSlots().equals(witness.exposedInterface())) {
            throw new IllegalArgumentException(
                    "Stored witness must retain the owning e-class exposed interface");
        }
    }

    TypedEClassRecord withStoredShape(CanonicalShape shape, ShapeWitness witness) {
        NavigableMap<CanonicalShape, ShapeWitness> updated = new TreeMap<>(shapeWitnesses);
        CanonicalShape checkedShape = Objects.requireNonNull(shape, "shape");
        ShapeWitness checkedWitness = Objects.requireNonNull(witness, "witness");
        validateShape(checkedShape, checkedWitness);
        putShape(updated, checkedShape, checkedWitness);
        return new TypedEClassRecord(interfaceView, updated, symmetryGroup);
    }

    TypedEClassRecord withoutStoredShape(CanonicalShape shape) {
        Objects.requireNonNull(shape, "shape");
        if (!shapeWitnesses.containsKey(shape)) {
            return this;
        }
        NavigableMap<CanonicalShape, ShapeWitness> updated =
                new TreeMap<>(shapeWitnesses);
        updated.remove(shape);
        return new TypedEClassRecord(interfaceView, updated, symmetryGroup);
    }

    TypedEClassRecord withoutStoredShapes() {
        return shapeWitnesses.isEmpty()
                ? this
                : new TypedEClassRecord(
                        interfaceView, Collections.emptyMap(), symmetryGroup);
    }

    TypedEClassRecord withInterfaceAndState(
            TypedEClassInterface replacement,
            Map<CanonicalShape, ShapeWitness> witnesses,
            TypedSymmetryGroup group) {
        if (!id().equals(Objects.requireNonNull(replacement, "replacement").id())
                || !outputType().equals(replacement.outputType())) {
            throw new IllegalArgumentException(
                    "An interface transition must preserve e-class id and output type");
        }
        return new TypedEClassRecord(replacement, witnesses, group);
    }

    private static void putShape(
            NavigableMap<CanonicalShape, ShapeWitness> target,
            CanonicalShape shape,
            ShapeWitness witness) {
        Map.Entry<CanonicalShape, ShapeWitness> floor = target.floorEntry(shape);
        if (floor != null && floor.getKey().compareTo(shape) == 0) {
            if (!floor.getKey().equals(shape)) {
                throw new IllegalStateException(
                        "Structural key collision between unequal canonical shapes");
            }
            if (!floor.getValue().equals(witness)) {
                throw new IllegalArgumentException(
                        "One canonical shape cannot have two different witnesses in one e-class");
            }
            return;
        }
        target.put(shape, witness);
    }

    TypedEClassRecord withSymmetryGroup(TypedSymmetryGroup group) {
        return new TypedEClassRecord(interfaceView, shapeWitnesses, group);
    }

    public TypedEClassInterface interfaceView() {
        return interfaceView;
    }

    public EClassId id() {
        return interfaceView.id();
    }

    public GraphType outputType() {
        return interfaceView.outputType();
    }

    public TypedSlotContext exposedSlots() {
        return interfaceView.exposedSlots();
    }

    public NavigableMap<CanonicalShape, ShapeWitness> shapeWitnesses() {
        return shapeWitnesses;
    }

    public TypedSymmetryGroup symmetryGroup() {
        return symmetryGroup;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TypedEClassRecord)) {
            return false;
        }
        TypedEClassRecord record = (TypedEClassRecord) other;
        return interfaceView.equals(record.interfaceView)
                && shapeWitnesses.equals(record.shapeWitnesses)
                && symmetryGroup.equals(record.symmetryGroup);
    }

    @Override
    public int hashCode() {
        return Objects.hash(interfaceView, shapeWitnesses, symmetryGroup);
    }

    @Override
    public String toString() {
        return interfaceView + " B=" + shapeWitnesses.size() + " " + symmetryGroup;
    }
}
