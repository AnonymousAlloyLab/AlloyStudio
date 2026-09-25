package is.fivefivefive.CanDis.core.egraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Raw slotted e-graph following the paper's (union-find, eclass map, shape
 * hashcons) construction. Children are renamed eclass invocations, shapes use
 * canonical local slots, and each eclass records a finite slot-permutation
 * group. No temporal partitioning, prenexing, or project normal forms are used.
 */
public final class SlottedEGraph implements AblationEngine {
    private static final int MAX_TERM_SIZE = 50_000;

    /** Exposes the slot-shape alpha representative for audits and regression tests. */
    public static AlloyTerm alphaRepresentative(AlloyTerm term) {
        return SlotCanonicalizer.canonicalize(term);
    }

    /** Returns the alpha-canonical rewrite fixed point used by this engine. */
    public static AlloyTerm normalForm(AlloyTerm term) {
        AlloyTerm current = SlotCanonicalizer.canonicalize(term);
        for (int iteration = 0; iteration < AlloyRewriteSystem.MAX_ITERATIONS; iteration++) {
            AlloyRewriteSystem.Pass pass = AlloyRewriteSystem.rewriteOnce(
                    current, AlloyRewriteSystem.ArityMode.VARIADIC);
            if (pass.applications == 0 || pass.term.size() > MAX_TERM_SIZE) {
                return current;
            }
            current = SlotCanonicalizer.canonicalize(pass.term);
        }
        return current;
    }

    @Override
    public Result compare(AlloyTerm left, AlloyTerm right) {
        Core graph = new Core();
        AlloyTerm leftFrontier = SlotCanonicalizer.canonicalize(left);
        AlloyTerm rightFrontier = SlotCanonicalizer.canonicalize(right);
        Invocation leftRoot = graph.add(leftFrontier);
        Invocation rightRoot = graph.add(rightFrontier);
        Set<AlloyTerm> leftRoots = new LinkedHashSet<>();
        Set<AlloyTerm> rightRoots = new LinkedHashSet<>();
        leftRoots.add(leftFrontier);
        rightRoots.add(rightFrontier);
        long applications = 0;
        long iterations = 0;

        for (int iteration = 0; iteration < AlloyRewriteSystem.MAX_ITERATIONS; iteration++) {
            AlloyRewriteSystem.Pass leftPass = AlloyRewriteSystem.rewriteOnce(
                    leftFrontier, AlloyRewriteSystem.ArityMode.VARIADIC);
            AlloyRewriteSystem.Pass rightPass = AlloyRewriteSystem.rewriteOnce(
                    rightFrontier, AlloyRewriteSystem.ArityMode.VARIADIC);
            int roundApplications = leftPass.applications + rightPass.applications;
            if (roundApplications == 0) {
                break;
            }
            iterations++;
            applications += roundApplications;
            if (leftPass.applications > 0 && leftPass.term.size() <= MAX_TERM_SIZE) {
                leftFrontier = SlotCanonicalizer.canonicalize(leftPass.term);
                graph.union(leftRoot, graph.add(leftFrontier));
                leftRoots.add(leftFrontier);
            }
            if (rightPass.applications > 0 && rightPass.term.size() <= MAX_TERM_SIZE) {
                rightFrontier = SlotCanonicalizer.canonicalize(rightPass.term);
                graph.union(rightRoot, graph.add(rightFrontier));
                rightRoots.add(rightFrontier);
            }
            graph.rebuild();
        }

        boolean sameClass = graph.equivalent(leftRoot, rightRoot);
        int distance = sameClass ? 0 : EGraphEditDistance.minimum(leftRoots, rightRoots);
        return new Result(distance, graph.stats(applications, iterations));
    }

    private static final class Core {
        private static final int MAX_GROUP_SIZE = 720;

        private final List<EClass> classes = new ArrayList<>();
        private final List<Integer> parents = new ArrayList<>();
        private final List<Integer> ranks = new ArrayList<>();
        private final List<int[]> childToParent = new ArrayList<>();
        private final List<ShapeRecord> records = new ArrayList<>();
        private final Map<String, Integer> externalSlots = new HashMap<>();
        private Map<Shape, Invocation> hashcons = new HashMap<>();
        private long unions;
        private long rebuilds;
        private long redundantSlots;

        private Invocation add(AlloyTerm term) {
            if (term.isVariable()) {
                int external = externalSlots.computeIfAbsent(term.atom(), ignored -> externalSlots.size());
                return intern(
                        new Shape("SLOT", "", 1, Collections.emptyList()),
                        new int[] { external },
                        new int[] { 0 });
            }

            List<Invocation> childInvocations = new ArrayList<>(term.children().size());
            LinkedHashMap<Integer, Integer> externalToLocal = new LinkedHashMap<>();
            for (AlloyTerm child : term.children()) {
                Invocation invocation = find(add(child));
                childInvocations.add(invocation);
                for (int external : invocation.toExternal) {
                    if (external >= 0 && !externalToLocal.containsKey(external)) {
                        externalToLocal.put(external, externalToLocal.size());
                    }
                }
            }

            int[] localToExternal = new int[externalToLocal.size()];
            for (Map.Entry<Integer, Integer> entry : externalToLocal.entrySet()) {
                localToExternal[entry.getValue()] = entry.getKey();
            }
            List<ChildInvocation> children = new ArrayList<>(childInvocations.size());
            for (Invocation invocation : childInvocations) {
                int[] renaming = new int[invocation.toExternal.length];
                for (int i = 0; i < renaming.length; i++) {
                    renaming[i] = invocation.toExternal[i] < 0
                            ? -1
                            : externalToLocal.get(invocation.toExternal[i]);
                }
                children.add(canonicalChild(new ChildInvocation(invocation.eclass, renaming)));
            }
            Shape shape = new Shape(term.head(), term.atom(), localToExternal.length, children);
            Set<Integer> hiddenExternal = boundExternalSlots(term);
            int exposedCount = 0;
            for (int external : localToExternal) {
                if (!hiddenExternal.contains(external)) {
                    exposedCount++;
                }
            }
            int[] exposedShapeSlots = new int[exposedCount];
            int next = 0;
            for (int local = 0; local < localToExternal.length; local++) {
                if (!hiddenExternal.contains(localToExternal[local])) {
                    exposedShapeSlots[next++] = local;
                }
            }
            return intern(shape, localToExternal, exposedShapeSlots);
        }

        private Set<Integer> boundExternalSlots(AlloyTerm term) {
            if (!("PREDICATE".equals(term.head())
                    || term.head().startsWith("QF/")
                    || term.head().startsWith("QE/")
                    || "LetExpr".equals(term.head()))) {
                return Collections.emptySet();
            }
            Set<Integer> hidden = new HashSet<>();
            if ("LetExpr".equals(term.head()) && !term.children().isEmpty()) {
                AlloyTerm variable = term.children().get(0);
                if (variable.isVariable()) {
                    Integer slot = externalSlots.get(variable.atom());
                    if (slot != null) {
                        hidden.add(slot);
                    }
                }
                return hidden;
            }
            for (AlloyTerm child : term.children()) {
                if (!child.head().startsWith("DECL/")) {
                    continue;
                }
                for (AlloyTerm declarationChild : child.children()) {
                    if (!declarationChild.isVariable()) {
                        continue;
                    }
                    Integer slot = externalSlots.get(declarationChild.atom());
                    if (slot != null) {
                        hidden.add(slot);
                    }
                }
            }
            return hidden;
        }

        private Invocation intern(Shape input, int[] shapeSlotToExternal, int[] exposedShapeSlots) {
            Shape shape = canonicalShape(input);
            Invocation owner = hashcons.get(shape);
            if (owner == null) {
                int id = classes.size();
                EClass eclass = new EClass(id, exposedShapeSlots.length);
                classes.add(eclass);
                parents.add(id);
                ranks.add(0);
                childToParent.add(identity(exposedShapeSlots.length));
                Invocation ownerInvocation = new Invocation(id, exposedShapeSlots);
                ShapeRecord record = new ShapeRecord(shape, ownerInvocation);
                records.add(record);
                hashcons.put(shape, ownerInvocation);
                int[] rootToExternal = new int[exposedShapeSlots.length];
                for (int i = 0; i < rootToExternal.length; i++) {
                    rootToExternal[i] = shapeSlotToExternal[exposedShapeSlots[i]];
                }
                return new Invocation(id, rootToExternal);
            }

            Invocation canonicalOwner = find(owner);
            int[] rootToExternal = new int[canonicalOwner.toExternal.length];
            for (int i = 0; i < rootToExternal.length; i++) {
                int shapeSlot = canonicalOwner.toExternal[i];
                rootToExternal[i] = shapeSlot < 0 || shapeSlot >= shapeSlotToExternal.length
                        ? -1
                        : shapeSlotToExternal[shapeSlot];
            }
            return new Invocation(canonicalOwner.eclass, rootToExternal);
        }

        private Invocation find(Invocation invocation) {
            RootPath path = findPath(invocation.eclass);
            int rootSlots = classes.get(path.root).slotCount;
            int[] rootToExternal = new int[rootSlots];
            Arrays.fill(rootToExternal, -1);
            int length = Math.min(path.localToRoot.length, invocation.toExternal.length);
            for (int local = 0; local < length; local++) {
                int rootSlot = path.localToRoot[local];
                if (rootSlot >= 0 && rootSlot < rootToExternal.length) {
                    rootToExternal[rootSlot] = invocation.toExternal[local];
                }
            }
            return new Invocation(path.root, rootToExternal);
        }

        private RootPath findPath(int id) {
            int parent = parents.get(id);
            if (parent == id) {
                return new RootPath(id, identity(classes.get(id).slotCount));
            }
            RootPath parentPath = findPath(parent);
            int[] direct = childToParent.get(id);
            int[] composed = new int[direct.length];
            for (int i = 0; i < direct.length; i++) {
                int middle = direct[i];
                composed[i] = middle < 0 || middle >= parentPath.localToRoot.length
                        ? -1
                        : parentPath.localToRoot[middle];
            }
            parents.set(id, parentPath.root);
            childToParent.set(id, composed);
            return new RootPath(parentPath.root, composed);
        }

        private Invocation union(Invocation leftInput, Invocation rightInput) {
            Invocation left = find(leftInput);
            Invocation right = find(rightInput);
            if (left.eclass == right.eclass) {
                recordSymmetry(left, right);
                return left;
            }

            int leftSlots = left.toExternal.length;
            int rightSlots = right.toExternal.length;
            boolean leftWins;
            if (leftSlots != rightSlots) {
                leftWins = leftSlots < rightSlots;
            } else {
                leftWins = ranks.get(left.eclass) >= ranks.get(right.eclass);
            }
            Invocation winner = leftWins ? left : right;
            Invocation loser = leftWins ? right : left;
            int[] loserToWinner = correspondence(loser.toExternal, winner.toExternal);
            int mapped = 0;
            for (int slot : loserToWinner) {
                if (slot >= 0) {
                    mapped++;
                }
            }
            if (winner.toExternal.length == loser.toExternal.length && mapped < loserToWinner.length) {
                boolean[] occupied = new boolean[winner.toExternal.length];
                for (int slot : loserToWinner) {
                    if (slot >= 0) {
                        occupied[slot] = true;
                    }
                }
                int next = 0;
                for (int i = 0; i < loserToWinner.length; i++) {
                    if (loserToWinner[i] >= 0) {
                        continue;
                    }
                    while (next < occupied.length && occupied[next]) {
                        next++;
                    }
                    if (next < occupied.length) {
                        loserToWinner[i] = next;
                        occupied[next] = true;
                        mapped++;
                    }
                }
            }

            parents.set(loser.eclass, winner.eclass);
            childToParent.set(loser.eclass, loserToWinner);
            if (leftSlots == rightSlots && ranks.get(left.eclass).equals(ranks.get(right.eclass))) {
                ranks.set(winner.eclass, ranks.get(winner.eclass) + 1);
            }
            redundantSlots += Math.max(0, loserToWinner.length - mapped);
            unions++;
            return find(winner);
        }

        private void recordSymmetry(Invocation left, Invocation right) {
            if (left.toExternal.length != right.toExternal.length || left.toExternal.length == 0) {
                return;
            }
            int[] permutation = correspondence(left.toExternal, right.toExternal);
            for (int slot : permutation) {
                if (slot < 0) {
                    return;
                }
            }
            EClass eclass = classes.get(left.eclass);
            addPermutationClosure(eclass, permutation);
        }

        private void addPermutationClosure(EClass eclass, int[] generator) {
            if (generator.length != eclass.slotCount) {
                return;
            }
            Queue<IntArrayKey> queue = new ArrayDeque<>();
            IntArrayKey initial = new IntArrayKey(generator);
            if (eclass.symmetries.add(initial)) {
                queue.add(initial);
            }
            while (!queue.isEmpty() && eclass.symmetries.size() < MAX_GROUP_SIZE) {
                IntArrayKey next = queue.remove();
                List<IntArrayKey> known = new ArrayList<>(eclass.symmetries);
                for (IntArrayKey existing : known) {
                    IntArrayKey composed = new IntArrayKey(composePermutation(next.values, existing.values));
                    if (eclass.symmetries.add(composed)) {
                        queue.add(composed);
                    }
                    composed = new IntArrayKey(composePermutation(existing.values, next.values));
                    if (eclass.symmetries.add(composed)) {
                        queue.add(composed);
                    }
                    if (eclass.symmetries.size() >= MAX_GROUP_SIZE) {
                        break;
                    }
                }
            }
        }

        private ChildInvocation canonicalChild(ChildInvocation input) {
            Invocation canonical = find(new Invocation(input.eclass, input.renaming));
            int[] best = canonical.toExternal;
            EClass eclass = classes.get(canonical.eclass);
            for (IntArrayKey symmetry : eclass.symmetries) {
                if (symmetry.values.length != best.length) {
                    continue;
                }
                int[] candidate = new int[best.length];
                for (int i = 0; i < candidate.length; i++) {
                    candidate[i] = best[symmetry.values[i]];
                }
                if (compareArrays(candidate, best) < 0) {
                    best = candidate;
                }
            }
            return new ChildInvocation(canonical.eclass, best);
        }

        private Shape canonicalShape(Shape shape) {
            if (shape.children.isEmpty()) {
                return shape;
            }
            List<ChildInvocation> children = new ArrayList<>(shape.children.size());
            boolean changed = false;
            for (ChildInvocation child : shape.children) {
                ChildInvocation canonical = canonicalChild(child);
                children.add(canonical);
                changed |= !canonical.equals(child);
            }
            return changed ? new Shape(shape.head, shape.atom, shape.slotCount, children) : shape;
        }

        private void rebuild() {
            boolean merged;
            int rounds = 0;
            do {
                rebuilds++;
                long unionsBefore = unions;
                Map<Shape, Invocation> rebuilt = new HashMap<>();
                for (ShapeRecord record : records) {
                    Invocation owner = find(record.owner);
                    Shape shape = canonicalShape(record.shape);
                    CompactedShape compacted = compact(shape, owner);
                    shape = compacted.shape;
                    owner = compacted.owner;
                    record.shape = shape;
                    record.owner = owner;
                    Invocation existing = rebuilt.get(shape);
                    if (existing == null) {
                        rebuilt.put(shape, owner);
                    } else {
                        union(existing, owner);
                    }
                }
                hashcons = rebuilt;
                merged = unions != unionsBefore;
                rounds++;
            } while (merged && rounds < 16);
        }

        private CompactedShape compact(Shape shape, Invocation owner) {
            if ("SLOT".equals(shape.head) || shape.slotCount == 0) {
                return new CompactedShape(shape, owner);
            }
            boolean[] used = new boolean[shape.slotCount];
            for (ChildInvocation child : shape.children) {
                for (int slot : child.renaming) {
                    if (slot >= 0 && slot < used.length) {
                        used[slot] = true;
                    }
                }
            }
            int count = 0;
            int[] oldToNew = new int[used.length];
            Arrays.fill(oldToNew, -1);
            for (int i = 0; i < used.length; i++) {
                if (used[i]) {
                    oldToNew[i] = count++;
                }
            }
            if (count == shape.slotCount) {
                return new CompactedShape(shape, owner);
            }
            List<ChildInvocation> children = new ArrayList<>(shape.children.size());
            for (ChildInvocation child : shape.children) {
                int[] renaming = child.renaming.clone();
                for (int i = 0; i < renaming.length; i++) {
                    renaming[i] = renaming[i] < 0 ? -1 : oldToNew[renaming[i]];
                }
                children.add(new ChildInvocation(child.eclass, renaming));
            }
            int[] rootToShape = owner.toExternal.clone();
            for (int i = 0; i < rootToShape.length; i++) {
                int old = rootToShape[i];
                rootToShape[i] = old < 0 || old >= oldToNew.length ? -1 : oldToNew[old];
            }
            redundantSlots += shape.slotCount - count;
            return new CompactedShape(
                    new Shape(shape.head, shape.atom, count, children),
                    new Invocation(owner.eclass, rootToShape));
        }

        private boolean equivalent(Invocation left, Invocation right) {
            return find(left).eclass == find(right).eclass;
        }

        private EGraphStats stats(long applications, long iterations) {
            long eclasses = 0;
            long slots = 0;
            long mappings = 0;
            long bytes = 0;
            for (int id = 0; id < classes.size(); id++) {
                if (findPath(id).root != id) {
                    continue;
                }
                EClass eclass = classes.get(id);
                eclasses++;
                slots += eclass.slotCount;
                mappings += (long) eclass.symmetries.size() * eclass.slotCount;
                bytes += 64L + 4L * eclass.slotCount;
                for (IntArrayKey symmetry : eclass.symmetries) {
                    bytes += 24L + 4L * symmetry.values.length;
                }
            }
            for (ShapeRecord record : records) {
                bytes += record.shape.estimatedBytes();
                for (ChildInvocation child : record.shape.children) {
                    mappings += child.renaming.length;
                }
            }
            bytes += classes.size() * 12L + hashcons.size() * 48L;
            return new EGraphStats(eclasses, records.size(), unions, rebuilds,
                    applications, iterations, slots, mappings, redundantSlots, bytes);
        }

        private static int[] correspondence(int[] sourceToContext, int[] targetToContext) {
            Map<Integer, Integer> contextToTarget = new HashMap<>();
            for (int i = 0; i < targetToContext.length; i++) {
                if (targetToContext[i] >= 0) {
                    contextToTarget.put(targetToContext[i], i);
                }
            }
            int[] mapping = new int[sourceToContext.length];
            Arrays.fill(mapping, -1);
            for (int i = 0; i < sourceToContext.length; i++) {
                Integer target = contextToTarget.get(sourceToContext[i]);
                if (target != null) {
                    mapping[i] = target;
                }
            }
            return mapping;
        }

        private static int[] identity(int size) {
            int[] identity = new int[size];
            for (int i = 0; i < size; i++) {
                identity[i] = i;
            }
            return identity;
        }

        private static int[] composePermutation(int[] left, int[] right) {
            int[] result = new int[left.length];
            for (int i = 0; i < result.length; i++) {
                result[i] = right[left[i]];
            }
            return result;
        }

        private static int compareArrays(int[] left, int[] right) {
            for (int i = 0; i < Math.min(left.length, right.length); i++) {
                int comparison = Integer.compare(left[i], right[i]);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return Integer.compare(left.length, right.length);
        }
    }

    private static final class Invocation {
        private final int eclass;
        private final int[] toExternal;

        private Invocation(int eclass, int[] toExternal) {
            this.eclass = eclass;
            this.toExternal = toExternal.clone();
        }
    }

    private static final class RootPath {
        private final int root;
        private final int[] localToRoot;

        private RootPath(int root, int[] localToRoot) {
            this.root = root;
            this.localToRoot = localToRoot;
        }
    }

    private static final class EClass {
        private final int id;
        private final int slotCount;
        private final Set<IntArrayKey> symmetries = new HashSet<>();

        private EClass(int id, int slotCount) {
            this.id = id;
            this.slotCount = slotCount;
            symmetries.add(new IntArrayKey(Core.identity(slotCount)));
        }
    }

    private static final class ShapeRecord {
        private Shape shape;
        private Invocation owner;

        private ShapeRecord(Shape shape, Invocation owner) {
            this.shape = shape;
            this.owner = owner;
        }
    }

    private static final class Shape {
        private final String head;
        private final String atom;
        private final int slotCount;
        private final List<ChildInvocation> children;
        private final int hashCode;

        private Shape(String head, String atom, int slotCount, List<ChildInvocation> children) {
            this.head = head;
            this.atom = atom;
            this.slotCount = slotCount;
            this.children = Collections.unmodifiableList(new ArrayList<>(children));
            this.hashCode = 31 * (31 * (31 * head.hashCode() + atom.hashCode()) + slotCount)
                    + this.children.hashCode();
        }

        private long estimatedBytes() {
            long bytes = 56L + 2L * (head.length() + atom.length());
            for (ChildInvocation child : children) {
                bytes += 32L + 4L * child.renaming.length;
            }
            return bytes;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Shape)) {
                return false;
            }
            Shape shape = (Shape) other;
            return slotCount == shape.slotCount
                    && head.equals(shape.head)
                    && atom.equals(shape.atom)
                    && children.equals(shape.children);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private static final class ChildInvocation {
        private final int eclass;
        private final int[] renaming;
        private final int hashCode;

        private ChildInvocation(int eclass, int[] renaming) {
            this.eclass = eclass;
            this.renaming = renaming.clone();
            this.hashCode = 31 * eclass + Arrays.hashCode(this.renaming);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ChildInvocation)) {
                return false;
            }
            ChildInvocation invocation = (ChildInvocation) other;
            return eclass == invocation.eclass && Arrays.equals(renaming, invocation.renaming);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private static final class IntArrayKey {
        private final int[] values;
        private final int hashCode;

        private IntArrayKey(int[] values) {
            this.values = values.clone();
            this.hashCode = Arrays.hashCode(this.values);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IntArrayKey && Arrays.equals(values, ((IntArrayKey) other).values);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private static final class CompactedShape {
        private final Shape shape;
        private final Invocation owner;

        private CompactedShape(Shape shape, Invocation owner) {
            this.shape = shape;
            this.owner = owner;
        }
    }
}
