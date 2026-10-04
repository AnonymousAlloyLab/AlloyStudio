package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Exact raw-occurrence permutation/quotient trace for one container construction. */
public final class ContainerApplicationTrace {
    private final PortSchema schema;
    private final TypedSlotContext context;
    private final List<PortValue> inputOccurrences;
    private final List<PortValue> outputOccurrences;
    private final List<List<Integer>> outputFibers;
    private final StructuralKey structuralKey;

    private ContainerApplicationTrace(
            PortSchema schema,
            TypedSlotContext context,
            List<? extends PortValue> inputOccurrences,
            PortValue normalizedContainer) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.context = Objects.requireNonNull(context, "context");
        this.inputOccurrences = immutablePorts(inputOccurrences, "input occurrence");
        validateInputs();
        PortValue expected = rebuild(schema, context, this.inputOccurrences);
        if (!expected.equals(Objects.requireNonNull(
                normalizedContainer, "normalizedContainer"))) {
            throw new IllegalArgumentException(
                    "Container target is not the declared normalization of its raw inputs");
        }
        this.outputOccurrences = immutablePorts(
                occurrences(normalizedContainer), "output occurrence");
        this.outputFibers = immutableFibers(computeFibers());
        validateFibers();
        this.structuralKey = buildStructuralKey();
    }

    public static ContainerApplicationTrace of(
            PortSchema schema,
            TypedSlotContext context,
            List<? extends PortValue> inputOccurrences,
            PortValue normalizedContainer) {
        return new ContainerApplicationTrace(
                schema, context, inputOccurrences, normalizedContainer);
    }

    private void validateInputs() {
        ArityPolicy arity = ContainerLawDeclaration.arityPolicy(schema);
        if (arity == null) {
            throw new IllegalArgumentException(
                    "A container application trace requires Seq, Bag, or Set");
        }
        arity.requireAdmitted(inputOccurrences.size(), "raw container application");
        PortSchema element = OperatorDeclaration.elementSchema(schema);
        for (PortValue input : inputOccurrences) {
            if (!element.equals(input.schema()) || !context.equals(input.context())) {
                throw new IllegalArgumentException(
                        "Raw container input has the wrong schema or caller context");
            }
        }
    }

    private List<List<Integer>> computeFibers() {
        if (schema instanceof SeqPortSchema) {
            List<List<Integer>> fibers = new ArrayList<>(outputOccurrences.size());
            for (int index = 0; index < outputOccurrences.size(); index++) {
                if (!outputOccurrences.get(index).equals(inputOccurrences.get(index))) {
                    throw new IllegalStateException("Sequence construction changed source order");
                }
                fibers.add(Collections.singletonList(index));
            }
            return fibers;
        }

        Map<StructuralKey, List<Integer>> byKey = new TreeMap<>();
        Map<StructuralKey, PortValue> representatives = new TreeMap<>();
        for (int index = 0; index < inputOccurrences.size(); index++) {
            PortValue input = inputOccurrences.get(index);
            PortValue prior = representatives.putIfAbsent(input.structuralKey(), input);
            if (prior != null && !prior.equals(input)) {
                throw new IllegalStateException(
                        "Structural key collision in raw container inputs");
            }
            byKey.computeIfAbsent(
                    input.structuralKey(), ignored -> new ArrayList<>()).add(index);
        }

        List<List<Integer>> fibers = new ArrayList<>(outputOccurrences.size());
        if (schema instanceof BagPortSchema) {
            Map<StructuralKey, Integer> next = new LinkedHashMap<>();
            for (PortValue output : outputOccurrences) {
                List<Integer> group = matchingGroup(byKey, representatives, output);
                int offset = next.getOrDefault(output.structuralKey(), 0);
                if (offset >= group.size()) {
                    throw new IllegalStateException("Bag output has no unmatched input");
                }
                fibers.add(Collections.singletonList(group.get(offset)));
                next.put(output.structuralKey(), offset + 1);
            }
            for (Map.Entry<StructuralKey, List<Integer>> entry : byKey.entrySet()) {
                if (next.getOrDefault(entry.getKey(), 0) != entry.getValue().size()) {
                    throw new IllegalStateException("Bag construction dropped multiplicity");
                }
            }
            return fibers;
        }

        if (!(schema instanceof SetPortSchema)) {
            throw new IllegalStateException("Unsupported container schema " + schema);
        }
        for (PortValue output : outputOccurrences) {
            List<Integer> group = matchingGroup(byKey, representatives, output);
            fibers.add(new ArrayList<>(group));
            byKey.remove(output.structuralKey());
            representatives.remove(output.structuralKey());
        }
        if (!byKey.isEmpty()) {
            throw new IllegalStateException("Set construction dropped a distinct input");
        }
        return fibers;
    }

    private static List<Integer> matchingGroup(
            Map<StructuralKey, List<Integer>> byKey,
            Map<StructuralKey, PortValue> representatives,
            PortValue output) {
        List<Integer> group = byKey.get(output.structuralKey());
        PortValue representative = representatives.get(output.structuralKey());
        if (group == null || representative == null || !representative.equals(output)) {
            throw new IllegalStateException(
                    "Container output does not match a raw structural class");
        }
        return group;
    }

    private void validateFibers() {
        if (outputFibers.size() != outputOccurrences.size()) {
            throw new IllegalStateException("Each container output needs one source fiber");
        }
        boolean[] covered = new boolean[inputOccurrences.size()];
        for (int output = 0; output < outputFibers.size(); output++) {
            List<Integer> fiber = outputFibers.get(output);
            if (fiber.isEmpty()
                    || (!(schema instanceof SetPortSchema) && fiber.size() != 1)) {
                throw new IllegalStateException("Malformed container quotient fiber");
            }
            for (Integer input : fiber) {
                if (input < 0 || input >= covered.length || covered[input]
                        || !inputOccurrences.get(input).equals(outputOccurrences.get(output))) {
                    throw new IllegalStateException("Container fibers are not an exact partition");
                }
                covered[input] = true;
            }
        }
        for (boolean present : covered) {
            if (!present) {
                throw new IllegalStateException("Container trace omits a raw occurrence");
            }
        }
    }

    private StructuralKey buildStructuralKey() {
        List<StructuralKey> children = new ArrayList<>();
        children.add(schema.structuralKey());
        children.add(TheoryKeys.context(context));
        for (PortValue input : inputOccurrences) {
            children.add(StructuralKey.branch(
                    "container-application/input",
                    Collections.singletonList(input.structuralKey())));
        }
        for (int output = 0; output < outputOccurrences.size(); output++) {
            List<String> origins = new ArrayList<>();
            for (Integer input : outputFibers.get(output)) {
                origins.add(Integer.toString(input));
            }
            children.add(StructuralKey.of(
                    "container-application/output",
                    origins,
                    Collections.singletonList(outputOccurrences.get(output).structuralKey())));
        }
        return StructuralKey.branch("container-application-trace-v1", children);
    }

    private static PortValue rebuild(
            PortSchema schema,
            TypedSlotContext context,
            List<? extends PortValue> inputs) {
        if (schema instanceof SeqPortSchema) {
            return new SeqPort((SeqPortSchema) schema, context, inputs);
        }
        if (schema instanceof BagPortSchema) {
            return new BagPort((BagPortSchema) schema, context, inputs);
        }
        if (schema instanceof SetPortSchema) {
            return new SetPort((SetPortSchema) schema, context, inputs);
        }
        throw new IllegalArgumentException("Not a container schema: " + schema);
    }

    private static List<PortValue> occurrences(PortValue container) {
        if (container instanceof SeqPort) {
            return ((SeqPort) container).elements();
        }
        if (container instanceof BagPort) {
            return ((BagPort) container).occurrences();
        }
        if (container instanceof SetPort) {
            return ((SetPort) container).elements();
        }
        throw new IllegalArgumentException("Not a container value: " + container);
    }

    private static List<PortValue> immutablePorts(
            List<? extends PortValue> source,
            String label) {
        Objects.requireNonNull(source, label + "s");
        List<PortValue> copy = new ArrayList<>(source.size());
        for (PortValue value : source) {
            copy.add(Objects.requireNonNull(value, label));
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<List<Integer>> immutableFibers(
            List<? extends List<Integer>> source) {
        List<List<Integer>> copy = new ArrayList<>(source.size());
        for (List<Integer> fiber : source) {
            copy.add(Collections.unmodifiableList(new ArrayList<>(fiber)));
        }
        return Collections.unmodifiableList(copy);
    }

    public PortSchema schema() { return schema; }
    public TypedSlotContext context() { return context; }
    public List<PortValue> inputOccurrences() { return inputOccurrences; }
    public List<PortValue> outputOccurrences() { return outputOccurrences; }
    public List<List<Integer>> outputFibers() { return outputFibers; }

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
        return schema instanceof SetPortSchema
                && outputOccurrences.size() < inputOccurrences.size();
    }

    public StructuralKey structuralKey() { return structuralKey; }
}
