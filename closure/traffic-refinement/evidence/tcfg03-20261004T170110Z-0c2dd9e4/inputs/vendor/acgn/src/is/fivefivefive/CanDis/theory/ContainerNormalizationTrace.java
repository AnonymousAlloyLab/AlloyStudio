package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Structural provenance for one declared Seq, Bag, or Set normalization. */
public final class ContainerNormalizationTrace {
    private final PortSchema.Kind kind;
    private final PortSchema schema;
    private final TypedSlotContext context;
    private final List<PortValue> inputOccurrences;
    private final List<PortValue> outputOccurrences;
    private final List<List<Integer>> outputFibers;
    private final StructuralKey structuralKey;

    private ContainerNormalizationTrace(
            PortSchema.Kind kind,
            PortSchema schema,
            TypedSlotContext context,
            List<? extends PortValue> inputOccurrences,
            List<? extends PortValue> outputOccurrences,
            List<? extends List<Integer>> outputFibers) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.context = Objects.requireNonNull(context, "context");
        this.inputOccurrences = immutablePorts(inputOccurrences, "input occurrence");
        this.outputOccurrences = immutablePorts(outputOccurrences, "output occurrence");
        this.outputFibers = immutableFibers(outputFibers);
        validate();
        this.structuralKey = buildStructuralKey();
    }

    static ContainerNormalizationTrace of(
            PortValue sourceContainer,
            List<? extends PortValue> normalizedInputs,
            PortValue normalizedContainer) {
        Objects.requireNonNull(sourceContainer, "sourceContainer");
        Objects.requireNonNull(normalizedContainer, "normalizedContainer");
        PortSchema.Kind kind = containerKind(sourceContainer);
        if (containerKind(normalizedContainer) != kind
                || !sourceContainer.schema().equals(normalizedContainer.schema())
                || !sourceContainer.context().equals(normalizedContainer.context())) {
            throw new IllegalArgumentException(
                    "Container normalization must preserve kind, schema, and caller context");
        }
        List<PortValue> inputs = immutablePorts(normalizedInputs, "normalized input");
        PortValue expected = rebuild(sourceContainer, inputs);
        if (!expected.equals(normalizedContainer)) {
            throw new IllegalArgumentException(
                    "Normalized container must be the declared normalization of its inputs");
        }
        List<PortValue> outputs = elements(normalizedContainer);
        return new ContainerNormalizationTrace(
                kind,
                sourceContainer.schema(),
                sourceContainer.context(),
                inputs,
                outputs,
                computeFibers(kind, inputs, outputs));
    }

    private static List<List<Integer>> computeFibers(
            PortSchema.Kind kind,
            List<PortValue> inputs,
            List<PortValue> outputs) {
        if (kind == PortSchema.Kind.SEQ) {
            List<List<Integer>> fibers = new ArrayList<>(outputs.size());
            for (int index = 0; index < outputs.size(); index++) {
                if (!outputs.get(index).equals(inputs.get(index))) {
                    throw new IllegalStateException("Sequence normalization changed element order");
                }
                fibers.add(Collections.singletonList(index));
            }
            return fibers;
        }

        Map<StructuralKey, List<Integer>> byKey = new TreeMap<>();
        Map<StructuralKey, PortValue> representatives = new TreeMap<>();
        for (int index = 0; index < inputs.size(); index++) {
            PortValue input = inputs.get(index);
            StructuralKey key = input.structuralKey();
            PortValue representative = representatives.putIfAbsent(key, input);
            if (representative != null && !representative.equals(input)) {
                throw new IllegalStateException(
                        "Structural key collision in container normalization input");
            }
            byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
        }

        List<List<Integer>> fibers = new ArrayList<>(outputs.size());
        if (kind == PortSchema.Kind.BAG) {
            Map<StructuralKey, Integer> next = new LinkedHashMap<>();
            for (PortValue output : outputs) {
                List<Integer> candidates = requireMatchingGroup(
                        byKey, representatives, output);
                int offset = next.getOrDefault(output.structuralKey(), 0);
                if (offset >= candidates.size()) {
                    throw new IllegalStateException("Bag output has no unmatched input occurrence");
                }
                fibers.add(Collections.singletonList(candidates.get(offset)));
                next.put(output.structuralKey(), offset + 1);
            }
            for (Map.Entry<StructuralKey, List<Integer>> entry : byKey.entrySet()) {
                if (next.getOrDefault(entry.getKey(), 0) != entry.getValue().size()) {
                    throw new IllegalStateException("Bag normalization dropped an occurrence");
                }
            }
            return fibers;
        }

        if (kind != PortSchema.Kind.SET) {
            throw new IllegalStateException("Not a container kind: " + kind);
        }
        for (PortValue output : outputs) {
            List<Integer> candidates = requireMatchingGroup(byKey, representatives, output);
            fibers.add(new ArrayList<>(candidates));
            byKey.remove(output.structuralKey());
            representatives.remove(output.structuralKey());
        }
        if (!byKey.isEmpty()) {
            throw new IllegalStateException("Set normalization dropped a distinct structural class");
        }
        return fibers;
    }

    private static List<Integer> requireMatchingGroup(
            Map<StructuralKey, List<Integer>> byKey,
            Map<StructuralKey, PortValue> representatives,
            PortValue output) {
        StructuralKey key = output.structuralKey();
        List<Integer> group = byKey.get(key);
        PortValue representative = representatives.get(key);
        if (group == null || representative == null || !representative.equals(output)) {
            throw new IllegalStateException(
                    "Container output does not match an input structural class");
        }
        return group;
    }

    private void validate() {
        if (schema.kind() != kind
                || (kind != PortSchema.Kind.SEQ
                        && kind != PortSchema.Kind.BAG
                        && kind != PortSchema.Kind.SET)) {
            throw new IllegalArgumentException("Normalization trace requires a container schema");
        }
        List<PortValue> expectedOutputs = elements(rebuildSchema(schema, context, inputOccurrences));
        if (!expectedOutputs.equals(outputOccurrences)) {
            throw new IllegalArgumentException(
                    "Container trace outputs are not the declared normalized result");
        }
        if (outputFibers.size() != outputOccurrences.size()) {
            throw new IllegalArgumentException("Each output occurrence requires one source fiber");
        }
        boolean[] covered = new boolean[inputOccurrences.size()];
        for (int outputIndex = 0; outputIndex < outputFibers.size(); outputIndex++) {
            List<Integer> fiber = outputFibers.get(outputIndex);
            if (fiber.isEmpty()) {
                throw new IllegalArgumentException("A normalization fiber must be nonempty");
            }
            if (kind != PortSchema.Kind.SET && fiber.size() != 1) {
                throw new IllegalArgumentException(
                        "Only Set normalization may merge occurrences");
            }
            for (Integer inputIndex : fiber) {
                if (inputIndex < 0 || inputIndex >= inputOccurrences.size()
                        || covered[inputIndex]) {
                    throw new IllegalArgumentException(
                            "Container normalization fibers must partition input occurrences");
                }
                covered[inputIndex] = true;
                if (!inputOccurrences.get(inputIndex).equals(
                        outputOccurrences.get(outputIndex))) {
                    throw new IllegalArgumentException(
                            "A normalization fiber may contain only equal occurrences");
                }
            }
        }
        for (boolean present : covered) {
            if (!present) {
                throw new IllegalArgumentException(
                        "Container normalization fibers must cover every input occurrence");
            }
        }
        if (kind == PortSchema.Kind.SEQ) {
            for (int index = 0; index < outputFibers.size(); index++) {
                if (!outputFibers.get(index).equals(Collections.singletonList(index))) {
                    throw new IllegalArgumentException("Sequence trace must be pointwise");
                }
            }
        }
    }

    private StructuralKey buildStructuralKey() {
        List<StructuralKey> children = new ArrayList<>();
        children.add(schema.structuralKey());
        children.add(TheoryKeys.context(context));
        for (PortValue input : inputOccurrences) {
            children.add(StructuralKey.branch(
                    "container-trace/input",
                    Collections.singletonList(input.structuralKey())));
        }
        for (int index = 0; index < outputOccurrences.size(); index++) {
            List<String> origins = new ArrayList<>();
            for (Integer origin : outputFibers.get(index)) {
                origins.add(Integer.toString(origin));
            }
            children.add(StructuralKey.of(
                    "container-trace/output",
                    origins,
                    Collections.singletonList(outputOccurrences.get(index).structuralKey())));
        }
        return StructuralKey.of(
                "leader-kernel/container-normalization",
                Collections.singletonList(kind.name()),
                children);
    }

    private static PortSchema.Kind containerKind(PortValue value) {
        if (value instanceof SeqPort) {
            return PortSchema.Kind.SEQ;
        }
        if (value instanceof BagPort) {
            return PortSchema.Kind.BAG;
        }
        if (value instanceof SetPort) {
            return PortSchema.Kind.SET;
        }
        throw new IllegalArgumentException("Value is not a Seq, Bag, or Set port");
    }

    private static PortValue rebuild(
            PortValue source,
            List<? extends PortValue> elements) {
        return rebuildSchema(source.schema(), source.context(), elements);
    }

    private static PortValue rebuildSchema(
            PortSchema schema,
            TypedSlotContext context,
            List<? extends PortValue> elements) {
        if (schema instanceof SeqPortSchema) {
            return new SeqPort((SeqPortSchema) schema, context, elements);
        }
        if (schema instanceof BagPortSchema) {
            return new BagPort((BagPortSchema) schema, context, elements);
        }
        if (schema instanceof SetPortSchema) {
            return new SetPort((SetPortSchema) schema, context, elements);
        }
        throw new IllegalArgumentException("Schema is not a container schema");
    }

    private static List<PortValue> elements(PortValue container) {
        if (container instanceof SeqPort) {
            return ((SeqPort) container).elements();
        }
        if (container instanceof BagPort) {
            return ((BagPort) container).occurrences();
        }
        if (container instanceof SetPort) {
            return ((SetPort) container).elements();
        }
        throw new IllegalArgumentException("Value is not a container port");
    }

    private static List<PortValue> immutablePorts(
            List<? extends PortValue> values,
            String label) {
        Objects.requireNonNull(values, label + "s");
        List<PortValue> result = new ArrayList<>(values.size());
        for (PortValue value : values) {
            result.add(Objects.requireNonNull(value, label));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<List<Integer>> immutableFibers(
            List<? extends List<Integer>> fibers) {
        Objects.requireNonNull(fibers, "outputFibers");
        List<List<Integer>> result = new ArrayList<>(fibers.size());
        for (List<Integer> fiber : fibers) {
            Objects.requireNonNull(fiber, "output fiber");
            List<Integer> copied = new ArrayList<>(fiber.size());
            for (Integer index : fiber) {
                copied.add(Objects.requireNonNull(index, "input occurrence index"));
            }
            result.add(Collections.unmodifiableList(copied));
        }
        return Collections.unmodifiableList(result);
    }

    public PortSchema.Kind kind() {
        return kind;
    }

    public PortSchema schema() {
        return schema;
    }

    public TypedSlotContext context() {
        return context;
    }

    /** Child results in source-occurrence order, before the container law acts. */
    public List<PortValue> inputOccurrences() {
        return inputOccurrences;
    }

    /** Declared normalized occurrences in sequence or structural-key order. */
    public List<PortValue> outputOccurrences() {
        return outputOccurrences;
    }

    /** For each output occurrence, the nonempty source-occurrence fiber it represents. */
    public List<List<Integer>> outputFibers() {
        return outputFibers;
    }

    public boolean reordered() {
        if (inputOccurrences.size() != outputOccurrences.size()) {
            return true;
        }
        for (int index = 0; index < outputFibers.size(); index++) {
            if (!outputFibers.get(index).equals(Collections.singletonList(index))) {
                return true;
            }
        }
        return false;
    }

    public boolean deduplicated() {
        return kind == PortSchema.Kind.SET
                && outputOccurrences.size() < inputOccurrences.size();
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ContainerNormalizationTrace)) {
            return false;
        }
        ContainerNormalizationTrace trace = (ContainerNormalizationTrace) other;
        return kind == trace.kind
                && schema.equals(trace.schema)
                && context.equals(trace.context)
                && inputOccurrences.equals(trace.inputOccurrences)
                && outputOccurrences.equals(trace.outputOccurrences)
                && outputFibers.equals(trace.outputFibers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                kind, schema, context, inputOccurrences, outputOccurrences, outputFibers);
    }

    @Override
    public String toString() {
        return kind + " " + outputFibers;
    }
}
