package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Graph-owned renamed union-find with parent embeddings in Definition 5 direction. */
final class TypedRenamedUnionFind {
    private final Map<EClassId, TypedEClassInterface> interfaces = new TreeMap<>();
    private final Map<EClassId, ParentAssignment> assignments = new TreeMap<>();

    synchronized void register(TypedEClassInterface eclass) {
        Objects.requireNonNull(eclass, "eclass");
        TypedEClassInterface prior = interfaces.get(eclass.id());
        if (prior != null) {
            if (!prior.equals(eclass)) {
                throw new IllegalArgumentException(
                        "An e-class id cannot be reused with different type or interface metadata");
            }
            return;
        }
        interfaces.put(eclass.id(), eclass);
        assignments.put(eclass.id(), ParentAssignment.root(eclass));
    }

    synchronized void linkRoots(ParentStep step) {
        Objects.requireNonNull(step, "step");
        requireRegistered(step.child());
        requireRegistered(step.parent());
        if (!assignment(step.child().id()).isRoot()) {
            throw new IllegalArgumentException("Only a current leader can receive a parent edge");
        }
        if (!assignment(step.parent().id()).isRoot()) {
            throw new IllegalArgumentException("A new parent must be a current leader");
        }
        assignments.put(step.child().id(), ParentAssignment.direct(step));
    }

    /** Atomically narrows one leader and replays every incident parent path. */
    synchronized void restrictLeader(
            InterfaceRestrictionCertificate restriction) {
        Objects.requireNonNull(restriction, "restriction");
        CertificateVerifier.verifyInterfaceRestriction(restriction);
        TypedEClassInterface original = restriction.originalInterface();
        TypedEClassInterface replacement = restriction.restrictedInterface();
        requireRegistered(original);
        if (!isLeader(original.id())) {
            throw new IllegalArgumentException(
                    "Only a current leader interface may be restricted");
        }

        Map<EClassId, TypedFindResult> affected = new TreeMap<>();
        for (TypedEClassInterface candidate : interfaces.values()) {
            TypedFindResult result = findWithoutCompression(
                    TypedInvocation.identity(candidate));
            if (result.leaderInvocation().eclass().equals(original)) {
                affected.put(candidate.id(), result);
            }
        }

        Map<EClassId, ParentAssignment> replacements = new TreeMap<>();
        replacements.put(original.id(), ParentAssignment.root(replacement));
        for (Map.Entry<EClassId, TypedFindResult> entry : affected.entrySet()) {
            if (entry.getKey().equals(original.id())) {
                continue;
            }
            TypedFindResult result = entry.getValue();
            TypedEClassInterface child = result.originalInvocation().eclass();
            TypedEmbedding oldLeaderInChild = result.leaderInvocation().embedding();
            TypedEmbedding newLeaderInChild = restriction.inclusion()
                    .andThen(oldLeaderInChild);
            TypedEqualityCertificate path = result.parentCertificate();
            TypedEqualityCertificate factorization = EqualityCertificates.rename(
                    restriction.factorization(), oldLeaderInChild);
            TypedEqualityCertificate derivation = EqualityCertificates.transitive(
                    path, factorization);
            ParentEdgeCertificate edge = new ParentEdgeCertificate(
                    child,
                    new TypedInvocation(replacement, newLeaderInChild),
                    derivation);
            replacements.put(child.id(), ParentAssignment.direct(
                    ParentStep.certified(edge)));
        }

        interfaces.put(original.id(), replacement);
        assignments.putAll(replacements);
        checkInvariants();
    }

    synchronized TypedFindResult findWithProvenance(TypedInvocation invocation) {
        return find(invocation, true);
    }

    synchronized TypedFindResult findWithoutCompression(TypedInvocation invocation) {
        return find(invocation, false);
    }

    private TypedFindResult find(TypedInvocation invocation, boolean compress) {
        Objects.requireNonNull(invocation, "invocation");
        requireRegistered(invocation.eclass());
        ParentPath path = pathToRoot(
                invocation.eclass().id(), new HashSet<>(), compress);
        TypedInvocation leader = new TypedInvocation(
                path.end(), path.compositeEmbedding().andThen(invocation.embedding()));
        return new TypedFindResult(invocation, leader, path);
    }

    private ParentPath pathToRoot(
            EClassId id,
            Set<EClassId> visiting,
            boolean compress) {
        if (!visiting.add(id)) {
            throw new IllegalStateException("Union-find parent assignments contain a cycle");
        }
        ParentAssignment current = assignment(id);
        if (current.isRoot()) {
            visiting.remove(id);
            return ParentPath.identity(current.child());
        }
        ParentPath tail = pathToRoot(
                current.parentInvocation().eclass().id(), visiting, compress);
        ParentPath full = current.provenancePath().andThen(tail);
        if (compress) {
            assignments.put(id, ParentAssignment.compressed(full));
        }
        visiting.remove(id);
        return full;
    }

    synchronized ParentAssignment assignment(EClassId id) {
        ParentAssignment assignment = assignments.get(Objects.requireNonNull(id, "id"));
        if (assignment == null) {
            throw new IllegalArgumentException("Unknown e-class id: " + id);
        }
        return assignment;
    }

    synchronized boolean isLeader(EClassId id) {
        return assignment(id).isRoot();
    }

    synchronized Map<EClassId, ParentAssignment> assignments() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(assignments));
    }

    synchronized Map<EClassId, TypedEClassInterface> interfaces() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(interfaces));
    }

    synchronized void checkInvariants() {
        if (!interfaces.keySet().equals(assignments.keySet())) {
            throw new IllegalStateException(
                    "Union-find interface and total parent-assignment domains differ");
        }
        for (Map.Entry<EClassId, TypedEClassInterface> entry : interfaces.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id())) {
                throw new IllegalStateException("Union-find interface is stored under the wrong id");
            }
            ParentAssignment parent = assignments.get(entry.getKey());
            if (!entry.getValue().equals(parent.child())) {
                throw new IllegalStateException(
                        "Parent assignment child metadata differs from the registered interface");
            }
            requireRegisteredState(parent.parentInvocation().eclass());
            validateHistoricalPath(parent.provenancePath());
        }
        for (EClassId id : interfaces.keySet()) {
            Set<EClassId> seen = new HashSet<>();
            EClassId current = id;
            while (true) {
                if (!seen.add(current)) {
                    throw new IllegalStateException(
                            "Union-find parent assignments contain a cycle");
                }
                ParentAssignment parent = assignments.get(current);
                if (parent.isRoot()) {
                    break;
                }
                current = parent.parentInvocation().eclass().id();
            }
            pathToRoot(id, new HashSet<>(), false);
        }
    }

    private void validateHistoricalPath(ParentPath path) {
        requireRegisteredState(path.start());
        requireRegisteredState(path.end());
        for (ParentStep step : path.steps()) {
            requireRegisteredState(step.child());
            requireRegisteredState(step.parent());
        }
    }

    private void requireRegistered(TypedEClassInterface eclass) {
        TypedEClassInterface registered = interfaces.get(eclass.id());
        if (registered == null) {
            throw new IllegalArgumentException("Unknown e-class id: " + eclass.id());
        }
        if (!registered.equals(eclass)) {
            throw new IllegalArgumentException(
                    "Invocation e-class metadata differs from the graph-owned interface");
        }
    }

    private void requireRegisteredState(TypedEClassInterface eclass) {
        TypedEClassInterface registered = interfaces.get(eclass.id());
        if (!eclass.equals(registered)) {
            throw new IllegalStateException(
                    "Parent path references missing or stale e-class metadata");
        }
    }
}
