package is.fivefivefive.CanDis.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Union-find over slotted e-class invocations. A renaming maps slots exposed by
 * an e-class to slots in its caller context.
 */
public final class RenamedIdUnionFind {
    private final Map<Integer, Integer> parents = new HashMap<>();
    private final Map<Integer, Map<String, String>> childToParent = new HashMap<>();
    private final Map<Integer, Integer> ranks = new HashMap<>();
    private final Map<Integer, Set<String>> slots = new HashMap<>();
    private final Map<Integer, Set<Integer>> members = new HashMap<>();

    public synchronized void register(int id, Set<String> exposedSlots) {
        if (!parents.containsKey(id)) {
            parents.put(id, id);
            ranks.put(id, 0);
            members.put(id, new LinkedHashSet<>(Collections.singleton(id)));
        }
        updateSlots(id, exposedSlots);
    }

    public synchronized void updateSlots(int id, Set<String> exposedSlots) {
        slots.put(id, new LinkedHashSet<>(exposedSlots));
        if (parents.getOrDefault(id, id) == id) {
            childToParent.put(id, identity(exposedSlots));
        }
    }

    public synchronized RenamedId find(RenamedId invocation) {
        ensureRegistered(invocation.getId(), invocation.getRenaming().keySet());
        RootPath path = findRoot(invocation.getId());
        Map<String, String> rootToCaller = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : path.childToRoot.entrySet()) {
            String callerSlot = invocation.getRenaming().get(entry.getKey());
            if (callerSlot != null) {
                rootToCaller.put(entry.getValue(), callerSlot);
            }
        }
        return new RenamedId(path.root, rootToCaller);
    }

    public synchronized RenamedId union(RenamedId left, RenamedId right) {
        RenamedId leftRoot = find(left);
        RenamedId rightRoot = find(right);
        if (leftRoot.id == rightRoot.id) {
            return leftRoot;
        }

        Map<String, String> rightToLeft = correspondence(
                rightRoot.renaming,
                leftRoot.renaming);
        int leftRank = ranks.getOrDefault(leftRoot.id, 0);
        int rightRank = ranks.getOrDefault(rightRoot.id, 0);
        if (leftRank < rightRank) {
            parents.put(leftRoot.id, rightRoot.id);
            childToParent.put(leftRoot.id, invert(rightToLeft));
            mergeMembers(rightRoot.id, leftRoot.id);
            return find(left);
        }

        parents.put(rightRoot.id, leftRoot.id);
        childToParent.put(rightRoot.id, rightToLeft);
        mergeMembers(leftRoot.id, rightRoot.id);
        if (leftRank == rightRank) {
            ranks.put(leftRoot.id, leftRank + 1);
        }
        return find(left);
    }

    public synchronized boolean equivalent(RenamedId left, RenamedId right) {
        RenamedId leftRoot = find(left);
        RenamedId rightRoot = find(right);
        return leftRoot.id == rightRoot.id && leftRoot.renaming.equals(rightRoot.renaming);
    }

    /** Every registered e-class ID in the same union component as {@code id}. */
    public synchronized Set<Integer> componentIds(int id) {
        int root = componentRootId(id);
        Set<Integer> result = new LinkedHashSet<>(
                members.getOrDefault(root, Collections.singleton(root)));
        return Collections.unmodifiableSet(result);
    }

    public synchronized int componentRootId(int id) {
        if (!parents.containsKey(id)) {
            throw new IllegalArgumentException(
                    "Union component requires a registered e-class: " + id);
        }
        return findRoot(id).root;
    }

    /** Retains exactly a union-component-closed set of registered IDs. */
    synchronized void retainRegisteredIds(Set<Integer> retainedIds) {
        Set<Integer> retained = new LinkedHashSet<>(retainedIds);
        for (Map.Entry<Integer, Set<Integer>> entry : members.entrySet()) {
            boolean retainsAny = false;
            boolean retainsAll = true;
            for (int member : entry.getValue()) {
                retainsAny |= retained.contains(member);
                retainsAll &= retained.contains(member);
            }
            if (retainsAny && !retainsAll) {
                throw new IllegalArgumentException(
                        "Retained e-class IDs must be closed under union components");
            }
        }
        parents.keySet().retainAll(retained);
        childToParent.keySet().retainAll(retained);
        ranks.keySet().retainAll(retained);
        slots.keySet().retainAll(retained);
        members.entrySet().removeIf(entry -> !retained.contains(entry.getKey()));
    }

    private void mergeMembers(int targetRoot, int absorbedRoot) {
        Set<Integer> target = members.computeIfAbsent(
                targetRoot, ignored -> new LinkedHashSet<>(Collections.singleton(targetRoot)));
        target.addAll(members.getOrDefault(
                absorbedRoot, Collections.singleton(absorbedRoot)));
        members.remove(absorbedRoot);
    }

    private RootPath findRoot(int id) {
        int parent = parents.get(id);
        if (parent == id) {
            return new RootPath(id, identity(slots.getOrDefault(id, Collections.emptySet())));
        }
        RootPath parentPath = findRoot(parent);
        Map<String, String> direct = childToParent.getOrDefault(id, Collections.emptyMap());
        Map<String, String> compressed = compose(direct, parentPath.childToRoot);
        parents.put(id, parentPath.root);
        childToParent.put(id, compressed);
        return new RootPath(parentPath.root, compressed);
    }

    private void ensureRegistered(int id, Set<String> exposedSlots) {
        if (!parents.containsKey(id)) {
            register(id, exposedSlots);
        }
    }

    private static Map<String, String> correspondence(
            Map<String, String> sourceToContext,
            Map<String, String> targetToContext) {
        Map<String, String> contextToTarget = invert(targetToContext);
        Map<String, String> sourceToTarget = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sourceToContext.entrySet()) {
            String targetSlot = contextToTarget.get(entry.getValue());
            if (targetSlot != null) {
                sourceToTarget.put(entry.getKey(), targetSlot);
            }
        }
        return sourceToTarget;
    }

    private static Map<String, String> compose(
            Map<String, String> childToMiddle,
            Map<String, String> middleToRoot) {
        Map<String, String> childToRoot = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : childToMiddle.entrySet()) {
            String rootSlot = middleToRoot.get(entry.getValue());
            if (rootSlot != null) {
                childToRoot.put(entry.getKey(), rootSlot);
            }
        }
        return childToRoot;
    }

    private static Map<String, String> identity(Set<String> values) {
        Map<String, String> identity = new LinkedHashMap<>();
        for (String value : values) {
            identity.put(value, value);
        }
        return identity;
    }

    private static Map<String, String> invert(Map<String, String> mapping) {
        Map<String, String> inverse = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String previous = inverse.put(entry.getValue(), entry.getKey());
            if (previous != null) {
                throw new IllegalArgumentException("Slot renaming must be injective");
            }
        }
        return inverse;
    }

    public static final class RenamedId {
        private final int id;
        private final Map<String, String> renaming;

        public RenamedId(int id, Map<String, String> renaming) {
            this.id = id;
            this.renaming = Collections.unmodifiableMap(new LinkedHashMap<>(renaming));
        }

        public int getId() {
            return id;
        }

        public Map<String, String> getRenaming() {
            return renaming;
        }
    }

    private static final class RootPath {
        private final int root;
        private final Map<String, String> childToRoot;

        private RootPath(int root, Map<String, String> childToRoot) {
            this.root = root;
            this.childToRoot = childToRoot;
        }
    }
}
