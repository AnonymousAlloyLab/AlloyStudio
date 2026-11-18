package is.fivefivefive.CanDis.core;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.ToIntBiFunction;

/** Paid edit links, never free alpha equivalences or certified source rewrites. */
final class FastPaidDeclarationAlignment {
    static final int MAX_DECLARATIONS = 64;
    static final long MAX_VISITS = 100_000;

    private FastPaidDeclarationAlignment() { }

    static boolean supportsPair(List<NormalForm> left,List<NormalForm> right) {
        if(FastNestedPaidScope.supportsPair(left,right)) return true;
        if(!supportsDirectEndpoint(left) || !supportsDirectEndpoint(right)) return false;
        boolean multipleOwners=(!left.get(0).getLocalQuantiVars().isEmpty()
                && FastLocalPaidScope.checkedOwnerCount(left.get(0))>1)
                || (!right.get(0).getLocalQuantiVars().isEmpty()
                && FastLocalPaidScope.checkedOwnerCount(right.get(0))>1);
        // This new scalar slice enumerates canonical declaration paths, not
        // source/target exchanges. Keep symmetric retained-owner prefixes on
        // their existing route (including replay-only certified completion).
        if(multipleOwners && (!rigidPrefix(left.get(0)) || !rigidPrefix(right.get(0)))) return false;
        // No new cross-owner local/local alignment is admitted by this slice.
        return left.get(0).getLocalQuantiVars().isEmpty() || right.get(0).getLocalQuantiVars().isEmpty()
                || localsAbsentFromSnapshots(left.get(0)) || localsAbsentFromSnapshots(right.get(0));
    }

    /** Canonical paid paths are additional candidates for symmetric retained
     * prefixes, never a replacement for their existing alpha/exchange route. */
    static boolean supportsSupplementalPair(List<NormalForm> left,List<NormalForm> right) {
        if(!supports(left) || !supports(right)) return false;
        if(!supportsDirectEndpoint(left) || !supportsDirectEndpoint(right)) return true;
        var a=left.get(0); var b=right.get(0);
        boolean localA=!a.getLocalQuantiVars().isEmpty(), localB=!b.getLocalQuantiVars().isEmpty();
        if(localA && localB) return true;
        if(localA==localB) return false;
        return FastLocalPaidScope.checkedOwnerCount(localA?a:b)>1
                && (!rigidPrefix(a) || !rigidPrefix(b));
    }

    private static boolean rigidPrefix(NormalForm form) {
        var qs=form.getMatrixQuantiVars();
        for(int start=0;start<qs.size();) {
            int end=start+1;var kind=qs.get(start).getQuantifier();
            if(kind==QuantiVar.Quantifier.ALL || kind==QuantiVar.Quantifier.SOME) {
                var tuples=new HashSet<List<Object>>();
                while(end<qs.size() && qs.get(end).getQuantifier()==kind) end++;
                for(int i=start;i<end;i++) {
                    var q=qs.get(i);
                    if(q.getTypeName()==null || !tuples.add(List.of(q.getTypeName(),q.getCardinality(),q.getDisjointnessClass()))) return false;
                }
            }
            start=end;
        }
        return true;
    }

    static boolean supports(List<NormalForm> forms) {
        return supports(forms, true);
    }

    // Broader rigid-group admission adds candidates; it must not select the
    // exclusive direct route in place of an existing coherent candidate.
    static boolean supportsDirectEndpoint(List<NormalForm> forms) {
        return supports(forms, false);
    }

    private static boolean supports(List<NormalForm> forms, boolean rigidGroups) {
        if (forms.size() != 1) return false;
        NormalForm f = forms.get(0);
        if (!f.isFrozenForCertification() || f.getMatrixEGraph() == null
                || f.getTemporalOp() != NormalForm.TemporalOp.NONE || !f.getTemporalChildren().isEmpty()
                || !f.getParams().isEmpty() || !f.getInheritedQuantiVars().isEmpty()
                || !f.getPhaseLocalBindingImports().isEmpty()) return false;
        List<QuantiVar> qs = f.getMatrixQuantiVars();
        if (qs.isEmpty()) return false;
        // A changed owner must not evade grouped validation by splitting one
        // original ONE tuple into apparently independent singleton owners.
        if (!f.originalGroupedOneOriginsIntact()) return false;
        if (f.hasIssuedGroupedDeclarations() && !f.matrixDeclarationsMatchSource()) return false;
        Set<String> names = new HashSet<>();
        Map<String,List<QuantiVar>> declarations = new LinkedHashMap<>();
        var firstType = qs.get(0).getExactAlloyType();
        for (QuantiVar q : qs) {
            var type = q.getExactAlloyType();
            // Paid diagonals are edits, not exchanges across quantifier blocks.
            // CanonicalDistance retains each contiguous ALL/SOME run in place.
            if (!q.isFrozenForCertification()
                    || (q.getQuantifier() != QuantiVar.Quantifier.ALL
                        && q.getQuantifier() != QuantiVar.Quantifier.SOME
                        && q.getQuantifier() != QuantiVar.Quantifier.LONE
                        && q.getQuantifier() != QuantiVar.Quantifier.ONE)
                    || q.getCardinality() != QuantiVar.Cardinality.ONE || q.getDisjointnessClass() < 0
                    || (!rigidGroups && q.getDisjointnessClass() > 0
                        && q.getQuantifier() != QuantiVar.Quantifier.ALL
                        && q.getQuantifier() != QuantiVar.Quantifier.SOME)
                    || q.getBindingPath().isEmpty()
                    // A substituted owner must not split a retained grouped
                    // prefix into unchecked singletons while keeping its slot key.
                    || (!f.getLocalQuantiVars().isEmpty() && (q.getDeBruijnKey()==null
                        || !q.getDeBruijnKey().startsWith(q.getBindingPath()+"#")))
                    || !names.add(q.getName()) || type == null || !type.hasParserAuthenticatedAncestry()
                    || !type.sharesParserModuleAuthorityWith(firstType)) return false;
            declarations.computeIfAbsent(q.getBindingPath(), ignored -> new ArrayList<>()).add(q);
        }
        if (!groupedSlotsMatchSource(f, declarations, rigidGroups)) return false;
        // ONE/LONE remain ordered prefix barriers. Admission beneath a retained
        // local binder requires its actual source kind, owner, domain and types;
        // the paid path changes neither that scope nor prefix ordering.
        if(!f.getLocalQuantiVars().isEmpty() && !localsAbsentFromSnapshots(f)) return FastLocalPaidScope.supports(f);
        Set<EGraphNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<EGraphNode> pending = new ArrayDeque<>();
        ClosedCallEvidence calls = null;
        BoundCallEvidence boundCalls = null;
        pending.add(f.getMatrixEGraph());
        while (!pending.isEmpty()) {
            EGraphNode node = pending.removeFirst();
            if (!seen.add(node)) continue;
            if (seen.size() > 4096) throw new is.fivefivefive.CanDis.metric.TraceResources.Exhausted("PAID_DECLARATION_GRAPH_BUDGET");
            if (node.getOpcode() == EGraphNode.Opcode.REF) return false;
            if (node.getOpcode() == EGraphNode.Opcode.CALL) {
                if(calls == null) calls = ClosedCallEvidence.from(f);
                if(!calls.admits(node)) {
                    if(boundCalls == null) boundCalls = BoundCallEvidence.from(f);
                    if(!boundCalls.admits(node)) return false;
                }
            }
            if (node.getOpcode() == EGraphNode.Opcode.VARIABLE) {
                String name = node.getAlphaName() == null ? node.getSourceName() : node.getAlphaName();
                if (!names.contains(name)) return false;
            }
            pending.addAll(node.getChildren());
        }
        return true;
    }

    /** Retained metadata is not a live scope. Check both authoritative graph
     * snapshots; this admits no local rewrite and does not erase the metadata. */
    static boolean localsAbsentFromSnapshots(NormalForm form) {
        if(!form.isFrozenForCertification() || form.getCertificationMatrixEGraph()==null
                || form.getMatrixEGraph()==null) return false;
        var outer=new HashMap<String,QuantiVar>();
        for(var q:form.getMatrixQuantiVars()) if(outer.put(q.getName(),q)!=null) return false;
        for(var q:form.getLocalQuantiVars()) if(outer.containsKey(q.getName())) return false;
        var sourceOccurrences=new HashMap<Long,QuantiVar>();
        for(int side=0;side<2;side++) {
            var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
            var pending=new ArrayDeque<EGraphNode>();
            pending.add(side==0?form.getCertificationMatrixEGraph():form.getMatrixEGraph());
            while(!pending.isEmpty()) {
                var node=pending.remove();if(!seen.add(node))continue;
                if(seen.size()>4096)throw new is.fivefivefive.CanDis.metric.TraceResources.Exhausted("PAID_DECLARATION_GRAPH_BUDGET");
                switch(node.getOpcode()) {
                    case GENERICRELDECL,DISJ,VAR,DISJVAR,EXISTS,FORALL -> { return false; }
                    case VARIABLE -> {
                        var q=outer.get(node.getAlphaName());
                        if(q==null || !node.getChildren().isEmpty() || q.getExactAlloyType()==null
                                || !q.getExactAlloyType().hasParserAuthenticatedAncestry()
                                || !q.getExactAlloyType().sameOccurrenceEvidenceAs(node.getExactAlloyType())
                                || !q.getOriginalNames().contains(node.getSourceName())
                                || !q.getOriginalNames().contains(node.getSemanticIdentity())) return false;
                        if(side==0) {
                            var previous=sourceOccurrences.putIfAbsent(node.getSourceOccurrenceLineage(),q);
                            if(previous!=null && previous!=q) return false;
                        } else if(sourceOccurrences.get(node.getSourceOccurrenceLineage())!=q) return false;
                    }
                    default -> { }
                }
                pending.addAll(node.getChildren());
            }
        }
        return true;
    }

    // A shared declaration path is an owner, not a slot. Disjoint groups keep
    // their original payload and slot order; charged links are not exchanges.
    private static boolean groupedSlotsMatchSource(NormalForm form, Map<String,List<QuantiVar>> declarations) {
        return groupedSlotsMatchSource(form, declarations, true);
    }

    private static boolean groupedSlotsMatchSource(NormalForm form, Map<String,List<QuantiVar>> declarations,
            boolean rigidGroups) {
        // Presentation paths may coalesce separate declarations during prefix
        // normalization. Only issued occurrences identify an original tuple.
        if(form.matrixDeclarationsMatchSource()) {
            declarations=new LinkedHashMap<>();
            for(var q:form.getMatrixQuantiVars())for(var frame:q.getDeclarationFrames()) {
                var group=declarations.computeIfAbsent(Long.toString(frame.declarationOccurrence()),
                        ignored -> new ArrayList<>());
                if(!group.contains(q))group.add(q);
            }
        }
        Map<String,QuantiVar> grouped = new HashMap<>();
        Set<String> aliases = new HashSet<>();
        Map<Integer,String> disjointOwners = new HashMap<>();
        Map<QuantiVar,Integer> positions = new IdentityHashMap<>();
        boolean checkedRigidGroup = false;
        for (int i=0;i<form.getMatrixQuantiVars().size();i++) positions.put(form.getMatrixQuantiVars().get(i),i);
        for (var group : declarations.values()) {
            if (group.size() < 2) {
                if (group.get(0).getDisjointnessClass() != 0) return false;
                continue;
            }
            // Grouped outer slots remain distinct beneath a retained owner.
            // supports() also requires FastLocalPaidScope's original/current
            // occurrence checks; this does not admit local or disjoint exchange.
            var first = group.get(0);
            boolean groupedOne = first.getQuantifier() == QuantiVar.Quantifier.ONE
                    && form.getLocalQuantiVars().isEmpty() && form.hasOriginalGroupedOne(group);
            boolean rigid = first.getQuantifier() == QuantiVar.Quantifier.ONE
                    || first.getQuantifier() == QuantiVar.Quantifier.LONE;
            checkedRigidGroup |= rigidGroups && rigid;
            if (first.getQuantifier() != QuantiVar.Quantifier.ALL
                    && first.getQuantifier() != QuantiVar.Quantifier.SOME && !groupedOne
                    && !(rigidGroups && rigid && issuedTupleMatches(form, group))) return false;
            int disjoint = first.getDisjointnessClass();
            if (disjoint > 0 && disjointOwners.putIfAbsent(disjoint,first.getBindingPath()) != null) return false;
            for (int slot=0;slot<group.size();slot++) {
                var q = group.get(slot);
                String type = q.getTypeName();
                if (type == null) return false;
                if (type.startsWith("VAR_")) type = type.substring(4);
                String expectedKey = q.getBindingPath()+"#"+slot+":"+q.getCardinality()+":"+type;
                if (!q.getDeBruijnKey().equals(expectedKey)
                        || q.getQuantifier() != first.getQuantifier()
                        || q.getDisjointnessClass() != disjoint
                        || ((disjoint > 0 || rigid) && positions.get(q) != positions.get(first)+slot)
                        || !q.getCarrierTypeName().equals(first.getCarrierTypeName())
                        || !q.getExactAlloyType().sameOccurrenceEvidenceAs(first.getExactAlloyType())) return false;
                // Shadowed spellings in separate declarations are not identities.
                // Only the complete issued-frame check may discharge a collision.
                for (String alias : q.getOriginalNames())
                    if (!aliases.add(alias) && !form.matrixDeclarationsMatchSource()) return false;
                grouped.put(q.getName(),q);
            }
        }
        if (grouped.isEmpty()) return true;
        EGraphNode source = form.getCertificationMatrixEGraph();
        if (source == null) return false;
        Set<String> observed = new HashSet<>();
        Set<EGraphNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<EGraphNode> pending = new ArrayDeque<>();
        pending.add(source);
        while (!pending.isEmpty()) {
            EGraphNode node = pending.removeFirst();
            if (!seen.add(node)) continue;
            if (seen.size() > 4096) throw new is.fivefivefive.CanDis.metric.TraceResources.Exhausted("PAID_DECLARATION_GRAPH_BUDGET");
            QuantiVar q = node.getOpcode() == EGraphNode.Opcode.VARIABLE ? grouped.get(node.getAlphaName()) : null;
            if (q != null) {
                if (node.getSemanticIdentity() == null || !q.getOriginalNames().contains(node.getSemanticIdentity())
                        || !q.getOriginalNames().contains(node.getSourceName())
                        || node.getExactAlloyType() == null
                        || !q.getExactAlloyType().sameOccurrenceEvidenceAs(node.getExactAlloyType())) return false;
                observed.add(q.getName());
            }
            pending.addAll(node.getChildren());
        }
        if (observed.equals(grouped.keySet()) && !checkedRigidGroup) return true;
        return form.matrixDeclarationsMatchSource() && declaredReadsMatchSource(form);
    }

    private static boolean issuedTupleMatches(NormalForm form, List<QuantiVar> group) {
        if (!form.matrixDeclarationsMatchSource()) return false;
        Map<Long,Set<Integer>> declarations = new HashMap<>();
        for (int slot = 0; slot < group.size(); slot++) {
            for (var frame : group.get(slot).getDeclarationFrames()) {
                if (!form.ownsDeclarationFrame(frame) || frame.tupleSlot() != slot
                        || !declarations.computeIfAbsent(frame.declarationOccurrence(), ignored -> new HashSet<>()).add(slot))
                    return false;
            }
        }
        return !declarations.isEmpty() && declarations.values().stream().allMatch(slots -> slots.size() == group.size());
    }

    private static boolean declaredReadsMatchSource(NormalForm form) {
        var declarations = new HashMap<String,QuantiVar>();
        var semanticAliases = new HashMap<String,QuantiVar>();
        for (var q : form.getMatrixQuantiVars()) {
            declarations.put(q.getName(), q);
            for (String alias : q.getOriginalNames()) semanticAliases.put(alias, q);
        }
        var sourceOccurrences = new HashMap<Long,QuantiVar>();
        Set<EGraphNode> sourceReads = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean checkingSource = true;
        for (var root : List.of(form.getCertificationMatrixEGraph(), form.getMatrixEGraph())) {
            Set<EGraphNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            var pending = new ArrayDeque<EGraphNode>();
            pending.add(root);
            while (!pending.isEmpty()) {
                var node = pending.removeFirst();
                if (!seen.add(node)) continue;
                if (seen.size() > 4096) throw new is.fivefivefive.CanDis.metric.TraceResources.Exhausted("PAID_DECLARATION_GRAPH_BUDGET");
                if (node.getOpcode() == EGraphNode.Opcode.VARIABLE) {
                    if (checkingSource) sourceReads.add(node);
                    var q = declarations.get(node.getAlphaName());
                    if (q == null) {
                        if (semanticAliases.containsKey(node.getSemanticIdentity())
                                || form.getLocalQuantiVars().isEmpty()) return false;
                    } else if (!node.getChildren().isEmpty() || node.getSemanticIdentity() == null
                            || !q.getOriginalNames().contains(node.getSemanticIdentity())
                            || !q.getOriginalNames().contains(node.getSourceName())
                            || !q.getExactAlloyType().sameOccurrenceEvidenceAs(node.getExactAlloyType())) return false;
                    if (q != null) {
                        long occurrence = node.getSourceOccurrenceLineage();
                        if (checkingSource) {
                            var prior = sourceOccurrences.putIfAbsent(occurrence, q);
                            if (prior != null && prior != q) return false;
                        } else if (sourceOccurrences.get(occurrence) != q) return false;
                    }
                }
                pending.addAll(node.getChildren());
            }
            if (checkingSource && !form.sourceReadInventoryMatches(sourceReads)) return false;
            checkingSource = false;
        }
        return true;
    }

    static void enumerate(List<QuantiVar> left, List<QuantiVar> right, int[][] dp,
            ToIntBiFunction<QuantiVar,QuantiVar> update, Consumer<Map<String,String>> accept) {
        enumerateSelected(left,right,dp,update,(paid,path)->accept.accept(paid),false);
    }

    static void enumerateSelected(List<QuantiVar> left,List<QuantiVar> right,int[][] dp,
            ToIntBiFunction<QuantiVar,QuantiVar> update,
            java.util.function.BiConsumer<Map<String,String>,List<is.fivefivefive.CanDis.metric.DeclarationReplay.Move>> accept,
            boolean retain) {
        if (left.size() > MAX_DECLARATIONS || right.size() > MAX_DECLARATIONS)
            throw new is.fivefivefive.CanDis.metric.TraceResources.Exhausted("PAID_DECLARATION_SIZE_BUDGET");
        walk(left,right,dp,update,accept,left.size(),right.size(),new LinkedHashMap<>(),new long[1],retain?new ArrayList<>():null);
    }

    private static void walk(List<QuantiVar> a,List<QuantiVar> b,int[][] dp,
            ToIntBiFunction<QuantiVar,QuantiVar> update,
            java.util.function.BiConsumer<Map<String,String>,List<is.fivefivefive.CanDis.metric.DeclarationReplay.Move>> accept,
            int i,int j,Map<String,String> paid,long[] visits,List<is.fivefivefive.CanDis.metric.DeclarationReplay.Move> path) {
        is.fivefivefive.CanDis.WorkBudget.charge(1);
        if (visits[0] >= MAX_VISITS) throw new is.fivefivefive.CanDis.metric.TraceResources.Exhausted("PAID_DECLARATION_PATH_BUDGET");
        visits[0]++;
        if (i == 0 && j == 0) {
            var selected=path==null?List.<is.fivefivefive.CanDis.metric.DeclarationReplay.Move>of():new ArrayList<>(path);
            if(path!=null)Collections.reverse(selected);
            accept.accept(Map.copyOf(paid),List.copyOf(selected)); return;
        }
        if (i > 0 && j > 0) {
            int cost = update.applyAsInt(a.get(i-1),b.get(j-1));
            if (dp[i][j] == dp[i-1][j-1] + cost) {
                String x = a.get(i-1).getName(), y = b.get(j-1).getName();
                if (cost != 0) {
                    if (paid.containsKey(x) || paid.containsValue(y))
                        throw new IllegalStateException("PAID_DECLARATION_NONINJECTIVE");
                    paid.put(x,y);
                }
                if(path!=null)path.add(new is.fivefivefive.CanDis.metric.DeclarationReplay.Move(i-1,j-1));
                walk(a,b,dp,update,accept,i-1,j-1,paid,visits,path);
                if(path!=null)path.remove(path.size()-1);
                if (cost != 0) paid.remove(x);
            }
        }
        if (i > 0 && dp[i][j] == dp[i-1][j] + 1) {
            if(path!=null)path.add(new is.fivefivefive.CanDis.metric.DeclarationReplay.Move(i-1,-1));
            walk(a,b,dp,update,accept,i-1,j,paid,visits,path);
            if(path!=null)path.remove(path.size()-1);
        }
        if (j > 0 && dp[i][j] == dp[i][j-1] + 1) {
            if(path!=null)path.add(new is.fivefivefive.CanDis.metric.DeclarationReplay.Move(-1,j-1));
            walk(a,b,dp,update,accept,i,j-1,paid,visits,path);
            if(path!=null)path.remove(path.size()-1);
        }
    }
}
