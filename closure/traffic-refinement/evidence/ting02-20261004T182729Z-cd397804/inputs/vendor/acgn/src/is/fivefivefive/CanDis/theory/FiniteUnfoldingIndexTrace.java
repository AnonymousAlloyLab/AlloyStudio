package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Concrete indexed weakening/extension trace for one complete unfolding tree.
 * Fresh free coordinates are threaded through binder scopes without escaping a
 * bound coordinate into the outer context.
 */
public final class FiniteUnfoldingIndexTrace {
    private final TypedInvocation rootInvocation;
    private final TypedSlotContext finalContext;
    private final TypedEmbedding finalWeakening;
    private final List<FiniteUnfoldingStepIndex> steps;
    private final StructuralKey structuralKey;

    private FiniteUnfoldingIndexTrace(
            TypedInvocation rootInvocation,
            TypedSlotContext finalContext,
            TypedEmbedding finalWeakening,
            List<? extends FiniteUnfoldingStepIndex> steps) {
        this.rootInvocation = Objects.requireNonNull(rootInvocation, "rootInvocation");
        this.finalContext = Objects.requireNonNull(finalContext, "finalContext");
        this.finalWeakening = Objects.requireNonNull(finalWeakening, "finalWeakening");
        Objects.requireNonNull(steps, "steps");
        if (!rootInvocation.callerContext().equals(finalWeakening.source())
                || !finalContext.equals(finalWeakening.codomain())) {
            throw new IllegalArgumentException(
                    "Finite-unfolding final weakening has incorrect root endpoints");
        }
        List<FiniteUnfoldingStepIndex> copied = new ArrayList<>(steps.size());
        for (int index = 0; index < steps.size(); index++) {
            FiniteUnfoldingStepIndex step = Objects.requireNonNull(
                    steps.get(index), "finite-unfolding step");
            if (step.ordinal() != index) {
                throw new IllegalArgumentException(
                        "Finite-unfolding steps must use contiguous preorder ordinals");
            }
            copied.add(step);
        }
        if (copied.isEmpty()
                || !copied.get(0).invocationAtStep().equals(rootInvocation)) {
            throw new IllegalArgumentException(
                    "Finite-unfolding index trace must begin at its root invocation");
        }
        this.steps = Collections.unmodifiableList(copied);

        List<StructuralKey> parts = new ArrayList<>(copied.size() + 3);
        parts.add(TheoryKeys.invocation(rootInvocation));
        parts.add(TheoryKeys.context(finalContext));
        parts.add(TheoryKeys.embedding(finalWeakening));
        for (FiniteUnfoldingStepIndex step : copied) {
            parts.add(step.structuralKey());
        }
        this.structuralKey = StructuralKey.branch(
                "finite-unfolding-index-trace", parts);
    }

    static FiniteUnfoldingIndexTrace materialize(FiniteUnfoldingTree tree) {
        Builder builder = new Builder(Objects.requireNonNull(tree, "tree"));
        builder.visit(tree, tree.rootInvocation(), TypedSlotContext.empty());
        return builder.finish();
    }

    public TypedInvocation rootInvocation() {
        return rootInvocation;
    }

    /** Final common free context for the complete tree. */
    public TypedSlotContext finalContext() {
        return finalContext;
    }

    /** Indexed {@code iota} for the complete root representation. */
    public TypedEmbedding finalWeakening() {
        return finalWeakening;
    }

    public List<FiniteUnfoldingStepIndex> steps() {
        return steps;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    private static final class Builder {
        private final FiniteUnfoldingTree root;
        private final TypedSlotContext initialFreeContext;
        private TypedSlotContext currentFreeContext;
        private final List<Draft> drafts = new ArrayList<>();

        private Builder(FiniteUnfoldingTree root) {
            this.root = root;
            this.initialFreeContext = root.rootInvocation().callerContext();
            this.currentFreeContext = initialFreeContext;
        }

        private void visit(
                FiniteUnfoldingTree tree,
                TypedInvocation actualInvocation,
                TypedSlotContext scopedBound) {
            TypedSlotContext expectedCaller = currentFreeContext.union(scopedBound);
            if (!actualInvocation.callerContext().equals(expectedCaller)) {
                throw new IllegalStateException(
                        "Materialized invocation does not use the current free and bound scope");
            }

            TypedSlotContext exposed = tree.shapeWitness().exposedInterface();
            TypedSlotContext witnessAmbient = tree.shapeWitness().ambientSupport();
            TypedSlotContext redundant = witnessAmbient.minus(exposed);
            TypedSlotContext newFree = currentFreeContext;
            Map<TypedSlot, TypedSlot> freshTargets = new LinkedHashMap<>();
            TypedSlotContext occupied = expectedCaller;
            for (TypedSlot coordinate : redundant) {
                TypedSlot fresh = CanonicalSlotAlphabet.fresh(
                        coordinate.type(), SlotAlphabet.CANONICAL_FREE, occupied);
                freshTargets.put(coordinate, fresh);
                newFree = newFree.plus(fresh);
                occupied = occupied.plus(fresh);
            }

            TypedSlotContext targetCaller = newFree.union(scopedBound);
            TypedEmbedding weakening = TypedEmbedding.inclusion(
                    expectedCaller, targetCaller);
            Map<TypedSlot, TypedSlot> extensionMap = new LinkedHashMap<>();
            for (TypedSlot slot : exposed) {
                extensionMap.put(
                        slot,
                        weakening.apply(actualInvocation.embedding().apply(slot)));
            }
            extensionMap.putAll(freshTargets);
            TypedEmbedding extension = TypedEmbedding.of(
                    witnessAmbient, targetCaller, extensionMap);
            Draft draft = new Draft(
                    drafts.size(),
                    actualInvocation,
                    tree.selectedShape(),
                    tree.shapeWitness(),
                    weakening,
                    extension,
                    TypedSlotContext.of(freshTargets.values()),
                    scopedBound);
            drafts.add(draft);
            currentFreeContext = newFree;

            TypedENode actualRestored = tree.restoredRoot().act(extension);
            List<TypedInvocation> actualChildren =
                    FiniteUnfoldingTree.invocationLeaves(actualRestored);
            if (actualChildren.size() != tree.invocationChildren().size()) {
                throw new IllegalStateException(
                        "Materialized node changed its invocation occurrence count");
            }
            TypedSlotContext localFreeAtRoot = newFree;
            for (int index = 0; index < actualChildren.size(); index++) {
                TypedInvocation baseChild = actualChildren.get(index);
                TypedSlotContext childBound = baseChild.callerContext().minus(
                        localFreeAtRoot);
                TypedSlotContext widenedCaller = currentFreeContext.union(childBound);
                if (!baseChild.callerContext().isSubcontextOf(widenedCaller)) {
                    throw new IllegalStateException(
                            "A child invocation escaped its lexical binder scope");
                }
                TypedInvocation widenedChild = baseChild.act(TypedEmbedding.inclusion(
                        baseChild.callerContext(), widenedCaller));
                visit(tree.invocationChildren().get(index), widenedChild, childBound);
            }
        }

        private FiniteUnfoldingIndexTrace finish() {
            List<FiniteUnfoldingStepIndex> completed = new ArrayList<>(drafts.size());
            for (Draft draft : drafts) {
                TypedSlotContext finalScoped = currentFreeContext.union(draft.scopedBound);
                completed.add(new FiniteUnfoldingStepIndex(
                        draft.ordinal,
                        draft.invocation,
                        draft.shape,
                        draft.witness,
                        draft.weakening,
                        draft.extension,
                        draft.fresh,
                        TypedEmbedding.inclusion(
                                draft.weakening.codomain(), finalScoped)));
            }
            return new FiniteUnfoldingIndexTrace(
                    root.rootInvocation(),
                    currentFreeContext,
                    TypedEmbedding.inclusion(initialFreeContext, currentFreeContext),
                    completed);
        }
    }

    private static final class Draft {
        private final int ordinal;
        private final TypedInvocation invocation;
        private final CanonicalShape shape;
        private final ShapeWitness witness;
        private final TypedEmbedding weakening;
        private final TypedEmbedding extension;
        private final TypedSlotContext fresh;
        private final TypedSlotContext scopedBound;

        private Draft(
                int ordinal,
                TypedInvocation invocation,
                CanonicalShape shape,
                ShapeWitness witness,
                TypedEmbedding weakening,
                TypedEmbedding extension,
                TypedSlotContext fresh,
                TypedSlotContext scopedBound) {
            this.ordinal = ordinal;
            this.invocation = invocation;
            this.shape = shape;
            this.witness = witness;
            this.weakening = weakening;
            this.extension = extension;
            this.fresh = fresh;
            this.scopedBound = scopedBound;
        }
    }
}
