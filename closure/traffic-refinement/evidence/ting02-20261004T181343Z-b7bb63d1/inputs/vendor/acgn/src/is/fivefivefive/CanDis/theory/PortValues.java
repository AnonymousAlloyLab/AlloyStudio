package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Shared validation and normalization for immutable port containers. */
final class PortValues {
    private static final Comparator<PortValue> STRUCTURAL_ORDER =
            Comparator.comparing(PortValue::structuralKey);

    private PortValues() {
    }

    static void requireAdmissibleCardinality(
            ArityPolicy policy,
            List<? extends PortValue> values) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(values, "values");
        if (!policy.admits(values.size())) {
            throw new IllegalArgumentException(
                    "Container cardinality " + values.size()
                            + " is not admitted by " + policy);
        }
    }

    static List<PortValue> validatedElements(
            PortSchema elementSchema,
            TypedSlotContext context,
            List<? extends PortValue> values) {
        Objects.requireNonNull(elementSchema, "elementSchema");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(values, "values");
        List<PortValue> result = new ArrayList<>(values.size());
        for (PortValue value : values) {
            PortValue element = Objects.requireNonNull(value, "container element");
            if (!elementSchema.equals(element.schema())) {
                throw new IllegalArgumentException(
                        "Container element schema mismatch: expected " + elementSchema
                                + " but got " + element.schema());
            }
            if (!context.equals(element.context())) {
                throw new IllegalArgumentException("All container elements require one caller context");
            }
            result.add(element);
        }
        return result;
    }

    static List<PortValue> immutableSequence(
            PortSchema elementSchema,
            TypedSlotContext context,
            List<? extends PortValue> values) {
        return Collections.unmodifiableList(validatedElements(elementSchema, context, values));
    }

    static List<PortValue> immutableBag(
            PortSchema elementSchema,
            TypedSlotContext context,
            List<? extends PortValue> values) {
        List<PortValue> result = validatedElements(elementSchema, context, values);
        result.sort(STRUCTURAL_ORDER);
        rejectKeyCollision(result);
        return Collections.unmodifiableList(result);
    }

    static List<PortValue> immutableSet(
            PortSchema elementSchema,
            TypedSlotContext context,
            List<? extends PortValue> values) {
        List<PortValue> validated = validatedElements(elementSchema, context, values);
        Map<StructuralKey, PortValue> unique = new TreeMap<>();
        for (PortValue value : validated) {
            PortValue existing = unique.putIfAbsent(value.structuralKey(), value);
            if (existing != null && !existing.equals(value)) {
                throw new IllegalStateException(
                        "Structural key collision between unequal set elements");
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }

    private static void rejectKeyCollision(List<PortValue> sorted) {
        for (int i = 1; i < sorted.size(); i++) {
            PortValue left = sorted.get(i - 1);
            PortValue right = sorted.get(i);
            if (left.structuralKey().equals(right.structuralKey()) && !left.equals(right)) {
                throw new IllegalStateException(
                        "Structural key collision between unequal bag elements");
            }
        }
    }

    static TypedSlotContext support(List<? extends PortValue> values) {
        TypedSlotContext result = TypedSlotContext.empty();
        for (PortValue value : values) {
            result = result.union(value.support());
        }
        return result;
    }

    static List<PortValue> act(
            List<? extends PortValue> values,
            TypedEmbedding embedding) {
        List<PortValue> result = new ArrayList<>(values.size());
        for (PortValue value : values) {
            result.add(value.act(embedding));
        }
        return result;
    }

    static StructuralKey key(
            String tag,
            PortSchema schema,
            TypedSlotContext context,
            List<? extends PortValue> values) {
        List<StructuralKey> children = new ArrayList<>(values.size() + 2);
        children.add(schema.structuralKey());
        children.add(TheoryKeys.context(context));
        for (PortValue value : values) {
            children.add(value.structuralKey());
        }
        return StructuralKey.branch(tag, children);
    }
}
