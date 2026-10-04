package is.fivefivefive.CanDis.core.egraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conventional hash-consed e-graph with union-find and congruence rebuilding. */
final class IntEGraph {
    private final List<Integer> parents = new ArrayList<>();
    private final List<Integer> ranks = new ArrayList<>();
    private final List<List<ENode>> classes = new ArrayList<>();
    private final Map<ENode, Integer> hashcons = new HashMap<>();
    private long unions;
    private long rebuilds;

    int add(AlloyTerm term) {
        int[] children = new int[term.children().size()];
        for (int i = 0; i < children.length; i++) {
            children[i] = add(term.children().get(i));
        }
        return addNode(new ENode(term.head(), term.atom(), children));
    }

    int addNode(ENode input) {
        ENode node = input.canonicalized(this);
        Integer existing = hashcons.get(node);
        if (existing != null) {
            return find(existing);
        }
        int id = parents.size();
        parents.add(id);
        ranks.add(0);
        List<ENode> nodes = new ArrayList<>(1);
        nodes.add(node);
        classes.add(nodes);
        hashcons.put(node, id);
        return id;
    }

    int find(int id) {
        int parent = parents.get(id);
        if (parent != id) {
            parent = find(parent);
            parents.set(id, parent);
        }
        return parent;
    }

    int union(int left, int right) {
        int leftRoot = find(left);
        int rightRoot = find(right);
        if (leftRoot == rightRoot) {
            return leftRoot;
        }
        if (ranks.get(leftRoot) < ranks.get(rightRoot)) {
            int swap = leftRoot;
            leftRoot = rightRoot;
            rightRoot = swap;
        }
        parents.set(rightRoot, leftRoot);
        classes.get(leftRoot).addAll(classes.get(rightRoot));
        classes.get(rightRoot).clear();
        if (ranks.get(leftRoot).equals(ranks.get(rightRoot))) {
            ranks.set(leftRoot, ranks.get(leftRoot) + 1);
        }
        unions++;
        return leftRoot;
    }

    void rebuild() {
        boolean merged;
        do {
            merged = false;
            rebuilds++;
            hashcons.clear();
            for (int id = 0; id < parents.size(); id++) {
                if (find(id) != id) {
                    continue;
                }
                for (ENode oldNode : new ArrayList<>(classes.get(id))) {
                    ENode node = oldNode.canonicalized(this);
                    Integer owner = hashcons.get(node);
                    if (owner == null) {
                        hashcons.put(node, find(id));
                    } else if (find(owner) != find(id)) {
                        union(owner, id);
                        merged = true;
                    }
                }
            }
        } while (merged);

        hashcons.clear();
        for (int id = 0; id < parents.size(); id++) {
            if (find(id) != id) {
                continue;
            }
            Set<ENode> unique = new HashSet<>();
            List<ENode> canonicalNodes = new ArrayList<>();
            for (ENode node : classes.get(id)) {
                ENode canonical = node.canonicalized(this);
                if (unique.add(canonical)) {
                    canonicalNodes.add(canonical);
                    hashcons.put(canonical, id);
                }
            }
            classes.set(id, canonicalNodes);
        }
    }

    EGraphStats stats(long rewriteApplications, long iterations) {
        long classCount = 0;
        long nodeCount = 0;
        long bytes = 0;
        for (int id = 0; id < parents.size(); id++) {
            if (find(id) != id) {
                continue;
            }
            classCount++;
            bytes += 48;
            for (ENode node : classes.get(id)) {
                nodeCount++;
                bytes += node.estimatedBytes();
            }
        }
        bytes += parents.size() * 8L + hashcons.size() * 40L;
        return new EGraphStats(classCount, nodeCount, unions, rebuilds,
                rewriteApplications, iterations, 0, 0, 0, bytes);
    }

    static final class ENode {
        private final String head;
        private final String atom;
        private final int[] children;
        private final int hashCode;

        ENode(String head, String atom, int[] children) {
            this.head = head;
            this.atom = atom;
            this.children = children.clone();
            int hash = 31 * head.hashCode() + atom.hashCode();
            this.hashCode = 31 * hash + Arrays.hashCode(this.children);
        }

        ENode canonicalized(IntEGraph graph) {
            int[] canonical = null;
            for (int i = 0; i < children.length; i++) {
                int child = graph.find(children[i]);
                if (child != children[i]) {
                    if (canonical == null) {
                        canonical = children.clone();
                    }
                    canonical[i] = child;
                }
            }
            return canonical == null ? this : new ENode(head, atom, canonical);
        }

        long estimatedBytes() {
            return 48L + 2L * (head.length() + atom.length()) + 4L * children.length;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ENode)) {
                return false;
            }
            ENode node = (ENode) other;
            return head.equals(node.head)
                    && atom.equals(node.atom)
                    && Arrays.equals(children, node.children);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
