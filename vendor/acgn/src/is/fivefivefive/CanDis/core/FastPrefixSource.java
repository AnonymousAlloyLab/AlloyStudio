package is.fivefivefive.CanDis.core;

import java.util.*;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;

/** Source authority for outer-prefix exchange; does not certify matrix rewrites. */
final class FastPrefixSource {
    private FastPrefixSource() { }

    static TheoryAlloyAdapter.PrefixBinderPlan checkedPlan(NormalForm source, NormalForm comparison) {
        require(FastPaidDeclarationAlignment.supports(List.of(source))
                && FastPaidDeclarationAlignment.supports(List.of(comparison)), "Unadmitted prefix scope");
        require(source.getCertificationMatrixEGraph() == comparison.getCertificationMatrixEGraph(),
                "Foreign prefix source");
        var bindings = new HashMap<String,QuantiVar>();
        for (var q : source.getMatrixQuantiVars()) {
            require(bindings.put(q.getName(), q) == null, "Duplicate prefix slot");
        }
        for (var q : source.getLocalQuantiVars()) {
            require(bindings.put(q.getName(), q) == null, "Capturing local slot");
        }
        var occurrences = new HashMap<Long,QuantiVar>();
        var locals = Collections.newSetFromMap(new IdentityHashMap<QuantiVar,Boolean>());
        locals.addAll(source.getLocalQuantiVars());
        boolean checkedLocals = !locals.isEmpty() && FastLocalPaidScope.supports(source)
                && FastLocalPaidScope.supports(comparison);
        var paths = new IdentityHashMap<EGraphNode,String>();
        var active = Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
        var profile = source.getCertificationMatrixEGraph().getSemanticProfile();
        walk(source.getCertificationMatrixEGraph(), "matrix", bindings, occurrences,
                paths, active, locals, checkedLocals, profile, true);
        paths.clear();
        walk(comparison.getMatrixEGraph(), "matrix", bindings, occurrences,
                paths, active, locals, checkedLocals, profile, false);
        return TheoryAlloyAdapter.prefixBinderPlan(source);
    }

    private static void walk(EGraphNode node, String path, Map<String,QuantiVar> bindings,
            Map<Long,QuantiVar> occurrences, IdentityHashMap<EGraphNode,String> paths,
            Set<EGraphNode> active, Set<QuantiVar> locals, boolean checkedLocals,
            is.fivefivefive.CanDis.theory.SemanticProfile profile,
            boolean source) {
        require(node != null && node.isFrozenForCertification(), "Unfrozen prefix occurrence");
        require(active.add(node), "Recursive prefix occurrence");
        try {
            String priorPath = paths.putIfAbsent(node, path);
            if (source) {
                require(priorPath == null || priorPath.equals(path), "Ambiguous prefix source path");
                node.requirePristineCertificationSource();
            } else if (priorPath != null) {
                return;
            }
            require(profile.equals(node.getSemanticProfile()), "Cross-profile prefix occurrence");
            node.requireAdmittedArity();
            // Only the checked local-owner traversal licenses a declaration
            // container without an expression result type, never its opcode.
            if (node.getOpcode() == EGraphNode.Opcode.GENERICRELDECL) {
                require(checkedLocals, "Unowned prefix declaration");
            } else {
                TheoryAlloyAdapter.requireOccurrenceOutputType(node);
            }
            if (node.getOpcode() == EGraphNode.Opcode.VARIABLE) {
                // FastLocalPaidScope has already checked the concrete local
                // owner and its renamed declaration/body occurrences.
                var q = source ? bindings.get(node.getAlphaName())
                        : occurrences.get(node.getSourceOccurrenceLineage());
                require(q != null && node.getChildren().isEmpty(), "Unbound prefix occurrence");
                require(!source && locals.contains(q) || bindings.get(node.getAlphaName()) == q,
                        "Changed outer prefix slot");
                var type = q.getExactAlloyType();
                require(type != null && type.hasParserAuthenticatedAncestry()
                        && type.sameOccurrenceEvidenceAs(node.getExactAlloyType())
                        && q.getOriginalNames().contains(node.getSourceName())
                        && q.getOriginalNames().contains(node.getSemanticIdentity()),
                        "Foreign prefix occurrence type or alias");
                if (source) {
                    var previous = occurrences.putIfAbsent(node.getSourceOccurrenceLineage(), q);
                    require(previous == null || previous == q, "Conflicting prefix occurrence owner");
                } else {
                    require(occurrences.get(node.getSourceOccurrenceLineage()) == q,
                            "Changed prefix occurrence owner");
                }
            }
            var children = node.getChildren();
            for (int i=0; i<children.size(); i++) {
                walk(children.get(i), path + "/" + i, bindings, occurrences, paths, active, locals, checkedLocals, profile, source);
            }
        } finally {
            active.remove(node);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
