package is.fivefivefive.CanDis.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import is.fivefivefive.CanDis.core.NormalForm.TemporalOp;
import is.fivefivefive.CanDis.theory.AlloyTypeBridge;
import is.fivefivefive.CanDis.theory.GraphType;
import is.fivefivefive.CanDis.metric.MatrixDecision;

/**
 * Specification and reference implementation of the CanDis repair metric over
 * repaired normal forms. Its decomposition, edit units, alpha minimization,
 * and unordered matching are metric semantics, not approximation heuristics.
 */
public final class CanonicalDistance {
    public static final String VERSION = "typed-fast-distance-v35-original-owner-phase-order";
    private static final ThreadLocal<MutableAllocationStats> ALLOCATION_TRACKER = new ThreadLocal<>();
    private static final ThreadLocal<DecisionCapture> DECISIONS = new ThreadLocal<>();
    private record PhaseDecision(Map<String,String> mapping,MatrixDecision<EGraphNode> matrix) {
        PhaseDecision { mapping=Map.copyOf(mapping); }
    }
    private static final class DecisionCapture {
        PhaseDecision lastPhase;
        List<PhaseDecision> phases;
        MatrixAlignment alignment;
        OrderedTreeEditDistance.Alignment temporal;
        final List<int[][]> declarations = new ArrayList<>();
        final List<List<is.fivefivefive.CanDis.metric.DeclarationReplay.Move>> declarationMoves = new ArrayList<>();
        final is.fivefivefive.CanDis.metric.TraceResources resources = new is.fivefivefive.CanDis.metric.TraceResources();
    }
    private static final OrderedTreeEditDistance.Adapter<TemporalTree> TEMPORAL_ADAPTER =
            new OrderedTreeEditDistance.Adapter<>() {
                @Override
                public String label(TemporalTree node) {
                    return node.label;
                }

                @Override
                public List<? extends TemporalTree> children(TemporalTree node) {
                    return node.children;
                }
            };

    private CanonicalDistance() {
    }

    public static void beginAllocationTracking() {
        ALLOCATION_TRACKER.set(new MutableAllocationStats());
    }

    public static AllocationStats endAllocationTracking() {
        MutableAllocationStats mutable = ALLOCATION_TRACKER.get();
        ALLOCATION_TRACKER.remove();
        return mutable == null ? new AllocationStats(0, 0, 0, 0)
                : new AllocationStats(mutable.bytes, mutable.peakBytes, mutable.arrays, mutable.matrices);
    }

    public static int distance(Prepared left, Prepared right) {
        return evaluate(left, right).distance();
    }

    /** Exposes the normative metric decomposition without changing its units. */
    public static DistanceBreakdown evaluate(Prepared left, Prepared right) {
        var capture=DECISIONS.get();
        int temporalDistance;
        if(capture==null)temporalDistance=treeDistance(left.temporalTree,right.temporalTree);
        else {capture.temporal=OrderedTreeEditDistance.alignment(left.temporalTree,right.temporalTree,TEMPORAL_ADAPTER);temporalDistance=capture.temporal.cost();}
        int quantificationDistance = quantificationDistance(left.normalForms, right.normalForms);
        int matrixDistance = matrixDistance(left.normalForms, right.normalForms, left.metadata, right.metadata);
        return new DistanceBreakdown(
                temporalDistance, quantificationDistance, matrixDistance);
    }

    /** Native matrix-only backtrace. Never a replacement for a complete public edit plan. */
    public static org.json.JSONObject matrixReplay(Prepared left, Prepared right) {
        return captureDecisions(left,right).matrix;
    }
    private record Captured(org.json.JSONObject matrix,DecisionCapture decisions) { }
    private static Captured captureDecisions(Prepared left,Prepared right) {
        if(DECISIONS.get()!=null)throw new IllegalStateException("Reentrant native decision capture");
        var decisions=new DecisionCapture();DECISIONS.set(decisions);
        DistanceBreakdown nativeResult;
        try { nativeResult=evaluate(left,right); } finally { DECISIONS.remove(); }
        int phaseCount = Math.max(left.normalForms.size(),right.normalForms.size());
        MatrixAlignment coherent=Objects.requireNonNull(decisions.alignment,"Missing selected matrix decision");
        org.json.JSONArray phases = new org.json.JSONArray();
        int cost = 0;
        for (int i = 0; i < phaseCount; i++) {
            NormalForm a = i < left.normalForms.size() ? left.normalForms.get(i) : null;
            NormalForm b = i < right.normalForms.size() ? right.normalForms.get(i) : null;
            PhaseDecision chosen=coherent.phases.get(i);
            Map<String, String> mapping=chosen.mapping();
            var replay=matrixReplayEngine(mapping,mapping,left,right,false);
            EGraphNode source = a == null ? null : a.getMatrixEGraph();
            EGraphNode target = b == null ? null : b.getMatrixEGraph();
            org.json.JSONObject receipt = replay.capture(chosen.matrix());
            int checked = replay.verify(source,target,receipt);
            cost = Math.addExact(cost, checked);
            phases.put(new org.json.JSONObject().put("phase", i)
                    .put("alphaMapping", new org.json.JSONObject(new java.util.TreeMap<>(mapping)))
                    .put("receipt", receipt));
        }
        if (cost != nativeResult.matrixDistance())
            throw new IllegalStateException("MATRIX_REPLAY_NATIVE_COST_MISMATCH");
        org.json.JSONObject result = new org.json.JSONObject().put("arm", "fast").put("metricVersion", VERSION)
                .put("chainGeometryVersion", FastChainGeometry.VERSION).put("distance", nativeResult.distance())
                .put("temporalDistance", nativeResult.temporalDistance())
                .put("quantifierDistance", nativeResult.quantifierDistance())
                .put("matrixDistance", nativeResult.matrixDistance()).put("replayedMatrixCost", cost)
                .put("matrixReplayChecked", true).put("complete", false).put("phases", phases);
        if (coherent.paidCorrespondence != null) result.put("selectedPaidDeclarationCorrespondence",
                new org.json.JSONObject(new java.util.TreeMap<>(coherent.paidCorrespondence)));
        if (coherent.sourceOrder != null) result.put("selectedGroupedDeclarationPermutation",
                new org.json.JSONObject().put("source",new org.json.JSONArray(coherent.sourceOrder))
                        .put("target",new org.json.JSONArray(coherent.targetOrder)));
        return new Captured(result,decisions);
    }

    private static is.fivefivefive.CanDis.metric.MatrixReplay<EGraphNode> matrixReplayEngine(
            Map<String,String> labels,Map<String,String> costs,Prepared left,Prepared right,boolean search) {
        var context=new EGraphDistanceContext(costs,left.metadata,right.metadata);
        return new is.fivefivefive.CanDis.metric.MatrixReplay<>(new is.fivefivefive.CanDis.metric.MatrixReplay.NativeCost<EGraphNode>() {
            public String label(EGraphNode n) {
                return new org.json.JSONArray().put(n.getOpcode().toString()).put(String.valueOf(resultType(n)))
                        .put(String.valueOf(n.getSourceName())).put(String.valueOf(atomIdentity(n)))
                        .put(n.getOpcode()==EGraphNode.Opcode.CALL?callIdentity(n):"")
                        .put(n.getFlexibleArityKind().toString()).put(n.isOrderInsensitive())
                        .put(new java.util.TreeMap<>(labels).toString()).toString();
            }
            public String evidence(EGraphNode n) {
                return new org.json.JSONObject().put("sourceOccurrenceLineage",n.getSourceOccurrenceLineage())
                        .put("certificationOccurrenceLineage",n.getCertificationOccurrenceLineage())
                        .put("sourceName",String.valueOf(n.getSourceName())).put("alphaName",String.valueOf(n.getAlphaName()))
                        .put("exactType",String.valueOf(n.getExactAlloyType())).put("sourceType",String.valueOf(n.getSourceType()))
                        .put("semanticIdentity",String.valueOf(n.getSemanticIdentity())).put("callOccurrence",n.getCallOccurrenceId())
                        .put("localCoordinate",n.hasComparisonLocalCoordinate()).toString();
            }
            public Object sourceObjectEvidence(EGraphNode n) {
                return n.temporalReferenceAuthorityId()>0?new org.json.JSONObject()
                        .put("temporalReferenceAuthority",n.temporalReferenceAuthorityId())
                        .put("sourceOccurrenceLineage",n.getSourceOccurrenceLineage()):org.json.JSONObject.NULL;
            }
            public List<EGraphNode> children(EGraphNode n) { return FastChainGeometry.children(n); }
            public List<EGraphNode> alternatives(EGraphNode n) { return List.of(); }
            public boolean unordered(EGraphNode a,EGraphNode b) { return a.getOpcode()==b.getOpcode() && a.isOrderInsensitive() && b.isOrderInsensitive(); }
            public int size(EGraphNode n) { return eGraphSize(n); }
            public int update(EGraphNode a,EGraphNode b) { return nodeUpdateCost(a,b,costs,left.metadata,right.metadata); }
            public int distance(EGraphNode a,EGraphNode b) {
                if(!search) throw new IllegalStateException("Completion replay cannot search");
                return context.distance(a,b);
            }
        });
    }

    /** Adds selected-map-linked declaration and native temporal replay; public linkage remains incomplete. */
    public static org.json.JSONObject replayWithSlices(Prepared left, Prepared right) {
        return evaluateWithTrace(left,right).json();
    }

    /** Numeric value and executable native metric representation from one evaluation.
     * Cross-arm correspondence is deliberately not a precondition of this API. */
    public static is.fivefivefive.CanDis.metric.NativeRepairTrace evaluateWithTrace(Prepared left,Prepared right) {
        var captured=captureDecisions(left,right);var out=captured.matrix;
        try {
        var phaseOrder=new org.json.JSONArray();
        appendPhaseOrder(left,"source",phaseOrder);appendPhaseOrder(right,"target",phaseOrder);
        if(!phaseOrder.isEmpty())out.put("nativePhaseRepresentationSteps",phaseOrder);
        var selected=captured.decisions.alignment;
        var as=new ArrayList<List<is.fivefivefive.CanDis.metric.DeclarationReplay.Atom>>();
        var bs=new ArrayList<List<is.fivefivefive.CanDis.metric.DeclarationReplay.Atom>>();
        var declarations=new org.json.JSONArray();
        int count=Math.max(left.normalForms.size(),right.normalForms.size());
        for(int phase=0;phase<count;phase++) {
            var source=phase<left.normalForms.size()?left.normalForms.get(phase).getMatrixQuantiVars():List.<QuantiVar>of();
            var target=phase<right.normalForms.size()?right.normalForms.get(phase).getMatrixQuantiVars():List.<QuantiVar>of();
            var a=canonicalQuantifierOrder(source);var b=canonicalQuantifierOrder(target);
            if(phase==0&&selected.sourceOrder!=null){
                a=is.fivefivefive.CanDis.metric.DeclarationMatrixLink.permute(a,selected.sourceOrder);
                b=is.fivefivefive.CanDis.metric.DeclarationMatrixLink.permute(b,selected.targetOrder);
            }
            as.add(List.copyOf(declarationAtoms(source,a,phase)));bs.add(List.copyOf(declarationAtoms(target,b,phase)));
            var moves=phase==0&&selected.declarationPath!=null?selected.declarationPath:captured.decisions.declarationMoves.get(phase);
            int[][] dp=captured.decisions.declarations.get(phase);
            declarations.put(is.fivefivefive.CanDis.metric.DeclarationReplay.captureSelected(as.get(phase),bs.get(phase),moves,dp[a.size()][b.size()]));
            var choice=selected.phases.get(phase);
            var matrix=out.getJSONArray("phases").getJSONObject(phase);
            matrix.put("program",matrixReplayEngine(choice.mapping,choice.mapping,left,right,false).executable(
                    choice.matrix.source(),choice.matrix.target(),matrix.getJSONObject("receipt")));
        }
        out.put("nativeDeclarationPrograms",declarations).put("nativeTemporalProgram",
                is.fivefivefive.CanDis.metric.TemporalReplay.capture(left.temporalTree,right.temporalTree,
                        out.getInt("temporalDistance"),n->n.label,n->n.children,captured.decisions.temporal))
                .put("executionRepresentation","Native temporal tree, ordered declaration tuples and selected mapped matrix occurrences")
                .put("jointBindingCorrespondenceChecked",false)
                .put("sourceBindingContexts",traceBindingContexts(left))
                .put("targetBindingContexts",traceBindingContexts(right))
                .put("constructionLink","Retained Prepared sourceForms and exact comparisonForms; not a source wire certificate");
        // Preserve the existing joint/cross-arm diagnostic separately from the
        // executable native choices. Its failure cannot discard native CRUD.
        is.fivefivefive.CanDis.metric.PhaseReplay.attach(out,()->declarationReplay(left,right,out,captured.decisions),
                ()->new org.json.JSONObject(out.getJSONObject("nativeTemporalProgram").toString()));
        return is.fivefivefive.CanDis.metric.NativeRepairTrace.checked(out,program->{
            if(!phaseOrder.isEmpty()&&!phaseOrder.similar(program.getJSONArray("nativePhaseRepresentationSteps")))
                throw new IllegalArgumentException("Original temporal phase permutation changed");
            int temporal=is.fivefivefive.CanDis.metric.TemporalReplay.verify(left.temporalTree,right.temporalTree,
                    n->n.label,n->n.children,program.getJSONObject("nativeTemporalProgram"));
            int quantifiers=0,matrix=0;
            if(program.getJSONArray("nativeDeclarationPrograms").length()!=count||program.getJSONArray("phases").length()!=count)
                throw new IllegalArgumentException("Native trace phase coverage");
            for(int phase=0;phase<count;phase++) {
                quantifiers=Math.addExact(quantifiers,is.fivefivefive.CanDis.metric.DeclarationReplay.execute(as.get(phase),bs.get(phase),
                        program.getJSONArray("nativeDeclarationPrograms").getJSONObject(phase)));
                var choice=selected.phases.get(phase);var payload=program.getJSONArray("phases").getJSONObject(phase);
                matrix=Math.addExact(matrix,matrixReplayEngine(choice.mapping,choice.mapping,left,right,false).verifyExecutable(
                        choice.matrix.source(),choice.matrix.target(),payload.getJSONObject("receipt"),payload.getJSONObject("program")));
            }
            if(temporal!=program.getInt("temporalDistance")||quantifiers!=program.getInt("quantifierDistance")||matrix!=program.getInt("matrixDistance"))
                throw new IllegalArgumentException("Native trace component costs");
            return Math.addExact(temporal,Math.addExact(quantifiers,matrix));
        });
        } catch(RuntimeException failure) {
            throw is.fivefivefive.CanDis.metric.NativeRepairTrace.captureFailure(out,failure);
        }
    }

    private static void appendPhaseOrder(Prepared prepared,String side,org.json.JSONArray out) {
        var rows=new org.json.JSONArray();boolean changed=false;
        for(int i=0;i<prepared.normalForms.size();i++) {
            NormalForm view=prepared.normalForms.get(i),original=view.comparisonSource();
            int old=-1;for(int j=0;j<prepared.sourceForms.size();j++)if(prepared.sourceForms.get(j)==original)old=j;
            if(old<0)throw new IllegalArgumentException("Comparison phase lost original occurrence");
            changed|=old!=i;
            rows.put(new org.json.JSONObject().put("comparisonPhase",i).put("originalPhase",old)
                    .put("sourcePath",original.getPhasePath()).put("typedRole",original.getTemporalOp().name()));
        }
        if(changed)out.put(new org.json.JSONObject().put("kind","source-linked-phase-reindex").put("cost",0)
                .put("side",side).put("phases",rows));
    }

    private static org.json.JSONArray traceBindingContexts(Prepared prepared) {
        var phases=new org.json.JSONArray();
        for(int phase=0;phase<prepared.normalForms.size();phase++) {
            var f=prepared.normalForms.get(phase);var rows=new org.json.JSONArray();
            var groups=List.of(f.getParams(),f.getMatrixQuantiVars(),f.getInheritedQuantiVars(),f.getLocalQuantiVars());
            var roles=List.of("parameter","matrix","inherited","local");
            for(int group=0;group<groups.size();group++)for(int i=0;i<groups.get(group).size();i++) {
                var q=groups.get(group).get(i);
                rows.put(new org.json.JSONObject().put("role",roles.get(group)).put("ordinal",i).put("alphaName",q.getName())
                        .put("sourceNames",new org.json.JSONArray(q.getOriginalNames())).put("owner",q.getBindingPath())
                        .put("slot",String.valueOf(q.getDeBruijnKey())).put("type",String.valueOf(q.getExactAlloyType()))
                        .put("quantifier",q.getQuantifier()).put("cardinality",q.getCardinality()).put("disjointnessClass",q.getDisjointnessClass()));
            }
            var imports=new org.json.JSONArray();
            for(var imported:f.getPhaseLocalBindingImports())imports.put(new org.json.JSONObject()
                    .put("alphaName",imported.variable().getName()).put("sourceBinderLineage",imported.sourceBinderLineage())
                    .put("ownerPhase",imported.ownerPhasePath()).put("targetPhase",imported.targetPhasePath()).put("context",imported.binderContext()));
            phases.put(new org.json.JSONObject().put("phase",phase).put("temporalOperator",f.getTemporalOp())
                    .put("bindings",rows).put("imports",imports));
        }
        return phases;
    }

    /** Fixed-choice diagnostic verifier; performs no distance search or admission. */
    public static int verifyFixedMatrixReplay(Prepared left,Prepared right,Map<String,String> labels,
            Map<String,String> completed,org.json.JSONObject receipt) {
        if(left.normalForms.size()!=1 || right.normalForms.size()!=1)
            throw new IllegalArgumentException("Single-phase fixed replay required");
        return matrixReplayEngine(labels,completed,left,right,false).verify(
                left.normalForms.get(0).getMatrixEGraph(),right.normalForms.get(0).getMatrixEGraph(),receipt);
    }

    /** Original-coordinate declaration snapshots, not permission to reorder them. */
    public static List<is.fivefivefive.CanDis.metric.DeclarationReplay.Atom> diagnosticDeclarationAtoms(NormalForm form) {
        return List.copyOf(declarationAtoms(form.getMatrixQuantiVars(),form.getMatrixQuantiVars(),0));
    }

    public static List<Integer> diagnosticDeclarationOrder(NormalForm form) {
        var source=form.getMatrixQuantiVars(); var indices=new IdentityHashMap<QuantiVar,Integer>();
        for(int i=0;i<source.size();i++) indices.put(source.get(i),i);
        return canonicalQuantifierOrder(source).stream().map(indices::get).toList();
    }

    private static org.json.JSONObject declarationReplay(Prepared left, Prepared right,org.json.JSONObject matrix,
            DecisionCapture decisions) {
        int expected = matrix.getInt("quantifierDistance");
        Map<String,String> paid = new HashMap<>();
        boolean linked = matrix.has("selectedPaidDeclarationCorrespondence");
        if (linked) {
            org.json.JSONObject selected = matrix.getJSONObject("selectedPaidDeclarationCorrespondence");
            for (String key : selected.keySet()) paid.put(key,selected.getString(key));
        }
        org.json.JSONArray components = new org.json.JSONArray();
        boolean completeAbsent=closedPrefix(left) && closedPrefix(right);
        int cost = 0;
        for (int phase = 0; phase < Math.max(left.normalForms.size(),right.normalForms.size()); phase++) {
            List<QuantiVar> aSource = phase < left.normalForms.size()
                    ? left.normalForms.get(phase).getMatrixQuantiVars() : List.of();
            List<QuantiVar> bSource = phase < right.normalForms.size()
                    ? right.normalForms.get(phase).getMatrixQuantiVars() : List.of();
            is.fivefivefive.CanDis.metric.DeclarationReplay.requireBounded(aSource.size(),bSource.size());
            List<QuantiVar> a = canonicalQuantifierOrder(aSource), b = canonicalQuantifierOrder(bSource);
            Map<String,String> selected = new java.util.LinkedHashMap<>();
            org.json.JSONObject alpha = matrix.getJSONArray("phases").getJSONObject(phase).getJSONObject("alphaMapping");
            for (String key : alpha.keySet()) selected.put(key,alpha.getString(key));
            Map<String,String> originalMatrixMapping=Map.copyOf(selected);
            for (var e : paid.entrySet()) {
                String previous = selected.putIfAbsent(e.getKey(),e.getValue());
                if (previous != null && !previous.equals(e.getValue()))
                    throw new IllegalStateException("DECLARATION_MATRIX_PAID_LINK_CONFLICT");
            }
            Set<String> usedA = phase < left.normalForms.size()
                    ? matrixVariableNames(left.normalForms.get(phase).getMatrixEGraph()) : Set.of();
            Set<String> usedB = phase < right.normalForms.size()
                    ? matrixVariableNames(right.normalForms.get(phase).getMatrixEGraph()) : Set.of();
            int[][] dp=decisions.declarations.get(phase);
            org.json.JSONObject receipt, connecting=null;
            var grouped=matrix.optJSONObject("selectedGroupedDeclarationPermutation");
            if(grouped!=null) {
                if(phase!=0 || left.normalForms.size()!=1 || right.normalForms.size()!=1)
                    throw new IllegalStateException("GROUPED_ORBIT_PHASE_BOUNDARY");
                int[] p=new int[grouped.getJSONArray("source").length()],q=new int[grouped.getJSONArray("target").length()];
                for(int i=0;i<p.length;i++)p[i]=grouped.getJSONArray("source").getInt(i);
                for(int i=0;i<q.length;i++)q[i]=grouped.getJSONArray("target").getInt(i);
                FastGroupedDeclarationOrbit.requireOrder(left.sourceForms.get(phase),left.normalForms.get(phase),a,p);
                FastGroupedDeclarationOrbit.requireOrder(right.sourceForms.get(phase),right.normalForms.get(phase),b,q);
                a=is.fivefivefive.CanDis.metric.DeclarationMatrixLink.permute(a,p);
                b=is.fivefivefive.CanDis.metric.DeclarationMatrixLink.permute(b,q);
                connecting=new org.json.JSONObject().put("source",new org.json.JSONArray(p)).put("target",new org.json.JSONArray(q))
                        .put("admission","ORIGINAL_SOURCE_CERTIFIED_PREFIX_TRANSPOSITION").put("cost",0);
            }
            try {
                receipt=captureDeclarations(aSource,bSource,a,b,phase,dp,selected,usedA,usedB,paid,linked,completeAbsent);
            } catch(is.fivefivefive.CanDis.metric.DeclarationReplay.NoMatchingPath missing) {
                if(grouped!=null) throw missing;
                if(phase>=left.normalForms.size()) throw missing;
                var joint=new is.fivefivefive.CanDis.metric.DeclarationMatrixLink(
                        a.stream().map(QuantiVar::getName).toList(),b.stream().map(QuantiVar::getName).toList(),selected,usedA,usedB);
                var admission=FastDeclarationPermutation.admission(left.normalForms.get(phase),a);
                int[] order=joint.connectingPermutation(declarationAtoms(aSource,a,phase),admission);
                var reordered=is.fivefivefive.CanDis.metric.DeclarationMatrixLink.permute(a,order);
                try {
                    receipt=captureDeclarations(aSource,bSource,reordered,b,phase,dp,selected,usedA,usedB,paid,linked,completeAbsent);
                    connecting=new org.json.JSONObject().put("source",new org.json.JSONArray(order))
                        .put("target",new org.json.JSONArray(java.util.stream.IntStream.range(0,b.size()).toArray()))
                        .put("admission","SOURCE_OBSERVED_GROUPED_ALL_SOME").put("cost",0);
                } catch(is.fivefivefive.CanDis.metric.DeclarationReplay.NoMatchingPath stillMissing) {
                    var completed=retainedPaidCompletion(left,right,a,b,selected,matrix,phase,linked,dp);
                    if(completed==null) completed=prefixPresentation(left,right,a,b,selected,usedA,usedB,
                            paid,linked,completeAbsent,phase,dp[a.size()][b.size()]);
                    if(completed==null) throw stillMissing;
                    receipt=completed.getJSONObject("receipt"); connecting=completed.getJSONObject("connectingPermutation");
                }
            }
            if(receipt.has("matrixCorrespondenceCompletion")) {
                var completion=receipt.getJSONObject("matrixCorrespondenceCompletion");
                var raw=completion.getJSONObject("completedMapping"); var completed=new HashMap<String,String>();
                for(String key:raw.keySet()) completed.put(key,raw.getString(key));
                var originalReceipt=matrix.getJSONArray("phases").getJSONObject(phase).getJSONObject("receipt");
                int replayed=matrixReplayEngine(originalMatrixMapping,completed,left,right,false).verify(
                        left.normalForms.get(phase).getMatrixEGraph(),right.normalForms.get(phase).getMatrixEGraph(),originalReceipt);
                if(replayed!=originalReceipt.getInt("cost")) throw new IllegalStateException("COMPLETION_MATRIX_COST_CHANGED");
                completion.put("matrixActionsUnchanged",true).put("replayedMatrixCost",replayed);
            }
            cost = Math.addExact(cost,receipt.getInt("replayedCost"));
            var component=new org.json.JSONObject().put("phase",phase).put("receipt",receipt);
            if(connecting!=null) component.put("connectingPermutation",connecting);
            components.put(component);
        }
        if (cost != expected) throw new IllegalStateException("DECLARATION_REPLAY_NATIVE_COST_MISMATCH");
        return new org.json.JSONObject().put("cost",cost).put("replayedCost",cost)
                .put("components",components).put("sliceReplayChecked",true).put("complete",false)
                .put("matrixChoiceAdmissionChecked",false).put("orderingAdmissionChecked",false)
                .put("selectedPaidCorrespondenceChecked",linked)
                .put("selectedMatrixDeclarationLinksChecked",true)
                .put("jointLinkScope","Original outer coordinates under selected map plus checked completion; local declarations never edited")
                .put("bindingRelationship","Every emitted keep/delete/insert checked against the selected or explicitly completed matrix map; scalar searches unchanged");
    }

    private static org.json.JSONObject retainedPaidCompletion(Prepared left,Prepared right,List<QuantiVar> a,
            List<QuantiVar> b,Map<String,String> selected,org.json.JSONObject matrix,int phase,boolean alreadyPaid,int[][] dp) {
        if(alreadyPaid || phase!=0 || left.normalForms.size()!=1 || right.normalForms.size()!=1
                || left.sourceForms.size()!=1 || right.sourceForms.size()!=1) return null;
        var af=left.normalForms.get(0); var bf=right.normalForms.get(0);
        for(var plan:FastRetainedDeclarationCompletion.plans(left.sourceForms.get(0),right.sourceForms.get(0),
                af,bf,a,b,selected,matrix.getInt("quantifierDistance"))) {
            var paid=new HashMap<String,String>(); int diagonal=0;
            for(int i=0;i<a.size();i++) {
                var x=plan.left().get(i); var y=plan.right().get(i);
                if(!y.getName().equals(plan.completed().get(x.getName())))
                    throw new IllegalStateException("RETAINED_COMPLETION_ORDER_LINK");
                int charge=quantifierUpdateCost(x,y); diagonal=Math.addExact(diagonal,charge);
                if(charge!=0) paid.put(x.getName(),y.getName());
            }
            if(diagonal!=1 || paid.size()!=1 || selected.containsKey(paid.keySet().iterator().next())) continue;
            for(int i=0;i<a.size();i++)if(quantifierUpdateCost(a.get(i),plan.left().get(i))!=0
                    ||quantifierUpdateCost(b.get(i),plan.right().get(i))!=0)
                throw new IllegalStateException("RETAINED_COMPLETION_CHANGED_NATIVE_TUPLE_ORDER");
            if(dp[a.size()][b.size()]!=matrix.getInt("quantifierDistance")) continue;
            var receipt=captureDeclarations(af.getMatrixQuantiVars(),bf.getMatrixQuantiVars(),plan.left(),plan.right(),0,
                    dp,plan.completed(),matrixVariableNames(af.getMatrixEGraph()),matrixVariableNames(bf.getMatrixEGraph()),
                    paid,true,false);
            var original=matrix.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt");
            int replayed=matrixReplayEngine(selected,plan.completed(),left,right,false)
                    .verify(af.getMatrixEGraph(),bf.getMatrixEGraph(),original);
            if(replayed!=matrix.getInt("matrixDistance")) throw new IllegalStateException("RETAINED_COMPLETION_COST_CHANGED");
            receipt.put("matrixCorrespondenceCompletion",FastRetainedDeclarationCompletion.evidence(plan,selected)
                    .put("paidCorrespondence",new org.json.JSONObject(new java.util.TreeMap<>(paid)))
                    .put("matrixActionsUnchanged",true).put("replayedMatrixCost",replayed));
            return new org.json.JSONObject().put("receipt",receipt).put("connectingPermutation",new org.json.JSONObject()
                    .put("source",new org.json.JSONArray(plan.sourceOrder())).put("target",new org.json.JSONArray(plan.targetOrder()))
                    .put("admission","ORIGINAL_TYPED_BINDER_AUTOMORPHISM_RETAINED_EXISTS").put("cost",0));
        }
        return null;
    }

    private static List<is.fivefivefive.CanDis.metric.RepairView.Binding> presentationBindings(
            Prepared prepared,int phase,List<QuantiVar> ordered) {
        var source=prepared.sourceForms.get(phase); var comparison=prepared.normalForms.get(phase);
        if(source.getCertificationMatrixEGraph()!=comparison.getCertificationMatrixEGraph()
                || source.getMatrixQuantiVars().size()!=comparison.getMatrixQuantiVars().size())
            throw new IllegalStateException("PREFIX_PRESENTATION_SOURCE_OWNER");
        for(int i=0;i<source.getMatrixQuantiVars().size();i++)
            if(source.getMatrixQuantiVars().get(i)!=comparison.getMatrixQuantiVars().get(i))
                throw new IllegalStateException("PREFIX_PRESENTATION_SOURCE_OCCURRENCE");
        var evidence=is.fivefivefive.CanDis.theory.TheoryAlloyAdapter.adapt(prepared.sourceForms,
                source.getCertificationMatrixEGraph().getSemanticProfile());
        evidence.requireRepairProjectionSources(prepared.sourceForms);
        var projected=is.fivefivefive.CanDis.metric.RepairProjection.project(evidence,prepared.sourceForms);
        var bindings=projected.phases().get(phase).bindings().stream()
                .filter(x->x.role()==is.fivefivefive.CanDis.metric.RepairView.BindingRole.MATRIX).toList();
        var owned=new java.util.IdentityHashMap<QuantiVar,is.fivefivefive.CanDis.metric.RepairView.Binding>();
        for(int i=0;i<source.getMatrixQuantiVars().size();i++) owned.put(source.getMatrixQuantiVars().get(i),bindings.get(i));
        return ordered.stream().map(q->java.util.Objects.requireNonNull(owned.get(q),"Foreign prefix occurrence")).toList();
    }

    private static org.json.JSONObject prefixPresentation(Prepared left,Prepared right,List<QuantiVar> a,
            List<QuantiVar> b,Map<String,String> selected,Set<String> usedA,Set<String> usedB,
            Map<String,String> paid,boolean linked,boolean completeAbsent,int phase,int expected) {
        if(phase>=left.normalForms.size() || phase>=right.normalForms.size()) return null;
        var ab=presentationBindings(left,phase,a); var bb=presentationBindings(right,phase,b);
        var ai=a.stream().map(QuantiVar::getName).toList(); var bi=b.stream().map(QuantiVar::getName).toList();
        int[] ap=is.fivefivefive.CanDis.metric.DeclarationPresentation.order(ab,ai,bi,selected);
        int[] bp=is.fivefivefive.CanDis.metric.DeclarationPresentation.order(bb,bi,ai,
                is.fivefivefive.CanDis.metric.DeclarationPresentation.inverse(selected));
        for(boolean sourceSide:new boolean[]{true,false}) {
            int[] p=sourceSide?ap:java.util.stream.IntStream.range(0,a.size()).toArray();
            int[] q=sourceSide?java.util.stream.IntStream.range(0,b.size()).toArray():bp;
            var x=is.fivefivefive.CanDis.metric.DeclarationMatrixLink.permute(a,p);
            var y=is.fivefivefive.CanDis.metric.DeclarationMatrixLink.permute(b,q);
            var as=left.normalForms.get(phase).getMatrixQuantiVars(); var bs=right.normalForms.get(phase).getMatrixQuantiVars();
            int[][] dp=is.fivefivefive.CanDis.metric.DeclarationPresentation.distances(
                    declarationAtoms(as,x,phase),declarationAtoms(bs,y,phase));
            if(dp[x.size()][y.size()]!=expected) continue;
            try {
                var receipt=captureDeclarations(as,bs,x,y,phase,dp,selected,usedA,usedB,paid,linked,completeAbsent);
                return new org.json.JSONObject().put("receipt",receipt).put("connectingPermutation",new org.json.JSONObject()
                        .put("source",new org.json.JSONArray(p)).put("target",new org.json.JSONArray(q))
                        .put("sourcePresentation",is.fivefivefive.CanDis.metric.DeclarationPresentation.evidence(ab,ai,p))
                        .put("targetPresentation",is.fivefivefive.CanDis.metric.DeclarationPresentation.evidence(bb,bi,q))
                        .put("admission","INDEPENDENT_TYPED_PREFIX_PRESENTATION").put("cost",0));
            } catch(is.fivefivefive.CanDis.metric.DeclarationReplay.NoMatchingPath missing) {
                // Try the opposite endpoint's admitted presentation, not a weaker link.
            }
        }
        return null;
    }

    private static org.json.JSONObject captureDeclarations(List<QuantiVar> originalA,List<QuantiVar> originalB,
            List<QuantiVar> a,List<QuantiVar> b,int phase,int[][] dp,Map<String,String> selected,
            Set<String> usedA,Set<String> usedB,Map<String,String> paid,boolean linked,boolean completeAbsent) {
        var as=declarationAtoms(originalA,a,phase); var bs=declarationAtoms(originalB,b,phase);
        var joint=new is.fivefivefive.CanDis.metric.DeclarationMatrixLink(a.stream().map(QuantiVar::getName).toList(),
                b.stream().map(QuantiVar::getName).toList(),selected,usedA,usedB,completeAbsent);
        var receipt=is.fivefivefive.CanDis.metric.DeclarationReplay.capture(as,bs,dp,(i,j)->{
            if(!joint.allows(i,j,i>=0 && j>=0?quantifierUpdateCost(a.get(i),b.get(j)):-1)) return false;
            if(completeAbsent && i>=0 && j>=0 && !selected.containsKey(a.get(i).getName())
                    && (a.get(i).getExactAlloyType()==null
                        || !a.get(i).getExactAlloyType().equals(b.get(j).getExactAlloyType()))) return false;
            if(!linked) return true;
            if(i<0) return !paid.containsValue(b.get(j).getName());
            if(j<0) return !paid.containsKey(a.get(i).getName());
            if(quantifierUpdateCost(a.get(i),b.get(j))!=0)
                return b.get(j).getName().equals(paid.get(a.get(i).getName()));
            return !paid.containsKey(a.get(i).getName()) && !paid.containsValue(b.get(j).getName());
        });
        if(completeAbsent) {
            var completion=joint.completeAndVerify(as,bs,receipt);
            if(!completion.getJSONArray("absencePremises").isEmpty()) receipt.put("matrixCorrespondenceCompletion",completion);
        } else joint.verify(as,bs,receipt);
        return receipt;
    }

    private static boolean closedPrefix(Prepared prepared) {
        if(prepared.normalForms.size()!=1) return false;
        var f=prepared.normalForms.get(0);
        if(f.getTemporalOp()!=TemporalOp.NONE || !f.getTemporalChildren().isEmpty() || !f.getParams().isEmpty()
                || !f.getInheritedQuantiVars().isEmpty() || !f.getLocalQuantiVars().isEmpty()
                || !f.getPhaseLocalBindingImports().isEmpty()) return false;
        var names=new HashSet<String>();
        for(var q:f.getMatrixQuantiVars()) if(!q.isFrozenForCertification() || !names.add(q.getName())
                || q.getBindingPath().isEmpty() || q.getExactAlloyType()==null
                || !q.getExactAlloyType().hasParserAuthenticatedAncestry()) return false;
        Set<EGraphNode> seen=java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        var pending=new java.util.ArrayDeque<EGraphNode>(); if(f.getMatrixEGraph()!=null) pending.add(f.getMatrixEGraph());
        while(!pending.isEmpty()) {
            var n=pending.removeFirst(); if(!seen.add(n)) continue;
            if(seen.size()>4096) throw new IllegalStateException("COMPLETION_OCCURRENCE_BUDGET");
            if(n.getOpcode()==EGraphNode.Opcode.CALL || n.getOpcode()==EGraphNode.Opcode.REF
                    || n.getOpcode()==EGraphNode.Opcode.VARIABLE && !names.contains(variableName(n))) return false;
            pending.addAll(n.getChildren());
        }
        return true;
    }

    private static List<is.fivefivefive.CanDis.metric.DeclarationReplay.Atom> declarationAtoms(
            List<QuantiVar> source,List<QuantiVar> ordered,int phase) {
        IdentityHashMap<QuantiVar,java.util.ArrayDeque<Integer>> indices = new IdentityHashMap<>();
        for (int i = 0; i < source.size(); i++) indices.computeIfAbsent(source.get(i),
                ignored -> new java.util.ArrayDeque<>()).addLast(i);
        List<is.fivefivefive.CanDis.metric.DeclarationReplay.Atom> result = new ArrayList<>();
        for (QuantiVar q : ordered) {
            int original = indices.get(q).removeFirst();
            String identity = "phase/"+phase+"/declaration/"+original;
            String admission = new org.json.JSONArray().put(q.getName()).put(q.getOriginalName())
                    .put(q.getBindingPath()).put(q.getCarrierTypeName()).put(String.valueOf(q.getExactAlloyType())).toString();
            result.add(new is.fivefivefive.CanDis.metric.DeclarationReplay.Atom(identity,
                    List.of(q.getQuantifier().name(),normalizeType(typeKey(q)),q.getCardinality().name(),
                            Integer.toString(q.getDisjointnessClass())),admission));
        }
        return result;
    }

    public static final class DistanceBreakdown {
        private final int temporalDistance;
        private final int quantifierDistance;
        private final int matrixDistance;

        private DistanceBreakdown(
                int temporalDistance,
                int quantifierDistance,
                int matrixDistance) {
            this.temporalDistance = temporalDistance;
            this.quantifierDistance = quantifierDistance;
            this.matrixDistance = matrixDistance;
        }

        public int temporalDistance() {
            return temporalDistance;
        }

        public int quantifierDistance() {
            return quantifierDistance;
        }

        public int matrixDistance() {
            return matrixDistance;
        }

        public int distance() {
            return temporalDistance + quantifierDistance + matrixDistance;
        }
    }

    public static List<String> edits(Prepared left, Prepared right) {
        // Human-readable projection only. The executable payload lives in the
        // same selected trace; this API no longer chooses a second mapping/DP.
        return evaluateWithTrace(left,right).paidDescriptions();
    }

    public static List<String> irTemporalFol(Prepared prepared) {
        return sourceIrTemporalFol(prepared.normalForms);
    }

    /** Render retained source forms without constructing the Fast metric projection. */
    public static List<String> sourceIrTemporalFol(List<NormalForm> normalForms) {
        List<String> formulas = new ArrayList<>();
        for (int i = 0; i < normalForms.size(); i++) {
            NormalForm normalForm = normalForms.get(i);
            formulas.add(normalFormPath(normalForm, i) + " := " + quantifierPrefix(normalForm.getMatrixQuantiVars())
                    + eGraphFormula(normalForm.getMatrixEGraph()));
        }
        return formulas;
    }

    public static int canonicalFormSize(Prepared prepared) {
        return prepared.size;
    }

    public static Prepared prepare(List<NormalForm> normalForms) {
        if (normalForms == null) {
            throw new IllegalArgumentException("Normal forms cannot be null");
        }
        List<NormalForm> snapshot = new ArrayList<>(normalForms);
        validateCalls(snapshot);
        for (NormalForm normalForm : snapshot) {
            if (normalForm != null) {
                normalForm.freezeForCertification();
            }
        }
        List<NormalForm> originals=List.copyOf(snapshot);
        snapshot = NormalForm.fastComparisonViews(snapshot);
        return new Prepared(originals,snapshot, temporalTree(snapshot), canonicalFormSize(snapshot));
    }

    private static void validateCalls(List<NormalForm> normalForms) {
        Set<EGraphNode> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<EGraphNode> pending = new java.util.ArrayDeque<>();
        for (NormalForm normalForm : normalForms) {
            if (normalForm != null && normalForm.getMatrixEGraph() != null) {
                pending.add(normalForm.getMatrixEGraph());
            }
        }
        while (!pending.isEmpty()) {
            is.fivefivefive.CanDis.WorkBudget.charge(1);
            EGraphNode node = pending.removeFirst();
            if (!seen.add(node)) {
                continue;
            }
            if (node.getOpcode() == EGraphNode.Opcode.CALL) {
                CallMetadata.require(node);
            }
            pending.addAll(node.getChildren());
        }
    }

    private static int canonicalFormSize(List<NormalForm> nfs) {
        int size = nfs.size();
        for (NormalForm nf : nfs) {
            size += eGraphSize(nf.getMatrixEGraph());
            size += quantificationSize(nf.getMatrixQuantiVars());
        }
        return size;
    }

    public static final class Prepared {
        private final List<NormalForm> sourceForms;
        private final List<NormalForm> normalForms;
        private final TemporalTree temporalTree;
        private final int size;
        private final EGraphMetadata metadata;
        private final EGraphNode.ReachabilityStats eGraphStats;

        /** Frozen metric views for bounded endpoint-linked diagnostic replay. */
        public List<NormalForm> comparisonForms() { return List.copyOf(normalForms); }

        private Prepared(List<NormalForm> sourceForms,List<NormalForm> normalForms, TemporalTree temporalTree, int size) {
            this.sourceForms=sourceForms;
            this.normalForms = normalForms;
            this.temporalTree = temporalTree;
            this.size = size;
            this.metadata = new EGraphMetadata(sourceForms,normalForms);
            List<EGraphNode> roots = new ArrayList<>(normalForms.size());
            for (NormalForm normalForm : normalForms) {
                if (normalForm.getMatrixEGraph() != null) {
                    roots.add(normalForm.getMatrixEGraph());
                }
            }
            this.eGraphStats = EGraphNode.countReachable(roots);
        }

        public long eclassCount() {
            return eGraphStats.eclasses;
        }

        public long enodeCount() {
            return eGraphStats.enodes;
        }

        public int canonicalSize() {
            return size;
        }

        public int normalFormCount() {
            return normalForms.size();
        }

        public int quantifierCount() {
            int count = 0;
            for (NormalForm normalForm : normalForms) {
                count += normalForm.getParams().size();
                count += normalForm.getMatrixQuantiVars().size();
                count += normalForm.getInheritedQuantiVars().size();
            }
            return count;
        }

        public int temporalNodeCount() {
            return temporalSize(temporalTree);
        }

        public int metadataEntryCount() {
            return metadata.sizes.size();
        }
    }

    private static int quantificationDistance(List<NormalForm> left, List<NormalForm> right) {
        int size = Math.max(left.size(), right.size());
        int distance = 0;
        for (int i = 0; i < size; i++) {
            if (DECISIONS.get() != null) {
                var a = canonicalQuantifierOrder(i < left.size() ? left.get(i).getMatrixQuantiVars() : List.of());
                var b = canonicalQuantifierOrder(i < right.size() ? right.get(i).getMatrixQuantiVars() : List.of());
                int[][] dp = quantificationDp(a,b);
                DECISIONS.get().declarations.add(dp);
                DECISIONS.get().declarationMoves.add(is.fivefivefive.CanDis.metric.DeclarationReplay.selectedMoves(
                        dp,(x,y)->quantifierUpdateCost(a.get(x),b.get(y))));
                distance = Math.addExact(distance, dp[a.size()][b.size()]);
                continue;
            }
            if (i >= left.size()) {
                distance += quantificationSize(right.get(i).getMatrixQuantiVars());
            } else if (i >= right.size()) {
                distance += quantificationSize(left.get(i).getMatrixQuantiVars());
            } else {
                distance += bindingListDistance(left.get(i).getMatrixQuantiVars(), right.get(i).getMatrixQuantiVars());
            }
        }
        return distance;
    }

    private static int bindingListDistance(List<QuantiVar> left, List<QuantiVar> right) {
        left = canonicalQuantifierOrder(left);
        right = canonicalQuantifierOrder(right);
        int[][] dp = quantificationDp(left,right);
        return dp[left.size()][right.size()];
    }

    private static int quantifierUpdateCost(QuantiVar left, QuantiVar right) {
        if (left.getQuantifier() == right.getQuantifier()
                && sameType(typeKey(left), typeKey(right))
                && left.getCardinality() == right.getCardinality()
                && left.getDisjointnessClass() == right.getDisjointnessClass()) {
            return 0;
        }
        return 1;
    }

    private static List<QuantiVar> canonicalQuantifierOrder(List<QuantiVar> variables) {
        is.fivefivefive.CanDis.WorkBudget.charge(1L + (long) variables.size() * variables.size());
        List<QuantiVar> ordered = new ArrayList<>(variables);
        for (int start = 0; start < ordered.size();) {
            QuantiVar.Quantifier quantifier = ordered.get(start).getQuantifier();
            int end = start + 1;
            if (quantifier == QuantiVar.Quantifier.ALL || quantifier == QuantiVar.Quantifier.SOME) {
                while (end < ordered.size() && ordered.get(end).getQuantifier() == quantifier) {
                    end++;
                }
                ordered.subList(start, end).sort(CanonicalDistance::compareQuantifierTuple);
            }
            start = end;
        }
        return ordered;
    }

    private static int compareQuantifierTuple(QuantiVar left, QuantiVar right) {
        int comparison = normalizeType(typeKey(left)).compareTo(normalizeType(typeKey(right)));
        if (comparison != 0) {
            return comparison;
        }
        comparison = left.getCardinality().compareTo(right.getCardinality());
        if (comparison != 0) {
            return comparison;
        }
        return Integer.compare(left.getDisjointnessClass(), right.getDisjointnessClass());
    }

    private static int quantificationSize(List<QuantiVar> vars) {
        return vars == null ? 0 : vars.size();
    }

    private static void quantificationEdits(List<NormalForm> left, List<NormalForm> right, List<String> edits) {
        int size = Math.max(left.size(), right.size());
        for (int i = 0; i < size; i++) {
            NormalForm pathSource = i < left.size() ? left.get(i) : right.get(i);
            String path = normalFormPath(pathSource, i) + ".quantifier";
            if (i >= left.size()) {
                collectInsertedQuantifiers(right.get(i).getMatrixQuantiVars(), path, edits);
            } else if (i >= right.size()) {
                collectDeletedQuantifiers(left.get(i).getMatrixQuantiVars(), path, edits);
            } else {
                quantificationEdits(left.get(i).getMatrixQuantiVars(), right.get(i).getMatrixQuantiVars(), path, edits);
            }
        }
    }

    private static void quantificationEdits(
            List<QuantiVar> left,
            List<QuantiVar> right,
            String path,
            List<String> edits) {
        left = canonicalQuantifierOrder(left);
        right = canonicalQuantifierOrder(right);
        int[][] dp = quantificationDp(left, right);
        int i = left.size();
        int j = right.size();
        List<String> reversed = new ArrayList<>();
        while (i > 0 || j > 0) {
            is.fivefivefive.CanDis.WorkBudget.charge(1);
            if (i > 0 && dp[i][j] == dp[i - 1][j] + 1) {
                reversed.add(path + "[" + (i - 1) + "]: delete " + quantifierFormula(left.get(i - 1)));
                i--;
            } else if (j > 0 && dp[i][j] == dp[i][j - 1] + 1) {
                reversed.add(path + "[" + j + "]: insert " + quantifierFormula(right.get(j - 1)));
                j--;
            } else {
                if (quantifierUpdateCost(left.get(i - 1), right.get(j - 1)) != 0) {
                    reversed.add(path + "[" + (i - 1) + "]: modify "
                            + quantifierFormula(left.get(i - 1)) + " -> " + quantifierFormula(right.get(j - 1)));
                }
                i--;
                j--;
            }
        }
        appendReverse(reversed, edits);
    }

    private static int[][] quantificationDp(List<QuantiVar> left, List<QuantiVar> right) {
        int[][] dp = intMatrix(left.size() + 1, right.size() + 1);
        for (int i = 1; i <= left.size(); i++) {
            dp[i][0] = dp[i - 1][0] + 1;
        }
        for (int j = 1; j <= right.size(); j++) {
            dp[0][j] = dp[0][j - 1] + 1;
        }
        for (int i = 1; i <= left.size(); i++) {
            for (int j = 1; j <= right.size(); j++) {
                int delete = dp[i - 1][j] + 1;
                int insert = dp[i][j - 1] + 1;
                int update = dp[i - 1][j - 1] + quantifierUpdateCost(left.get(i - 1), right.get(j - 1));
                dp[i][j] = Math.min(update, Math.min(delete, insert));
            }
        }
        return dp;
    }

    private static void collectInsertedQuantifiers(List<QuantiVar> vars, String path, List<String> edits) {
        for (int i = 0; i < vars.size(); i++) {
            edits.add(path + "[" + i + "]: insert " + quantifierFormula(vars.get(i)));
        }
    }

    private static void collectDeletedQuantifiers(List<QuantiVar> vars, String path, List<String> edits) {
        for (int i = 0; i < vars.size(); i++) {
            edits.add(path + "[" + i + "]: delete " + quantifierFormula(vars.get(i)));
        }
    }

    private static int matrixDistance(List<NormalForm> left, List<NormalForm> right) {
        return matrixDistance(left, right, null, null);
    }

    private static int matrixDistance(
            List<NormalForm> left,
            List<NormalForm> right,
            EGraphMetadata leftMetadata,
            EGraphMetadata rightMetadata) {
        var selected=bestCoherentMatrixAlignment(left,right,leftMetadata,rightMetadata);
        var capture=DECISIONS.get();if(capture!=null)capture.alignment=selected;
        return selected.distance;
    }

    private static int matrixDistanceWithFixedMapping(
            List<NormalForm> left,
            List<NormalForm> right,
            Map<String, String> fixedMapping,
            Set<String> lockedLeftNames,
            Set<String> lockedRightNames,
            EGraphMetadata leftMetadata,
            EGraphMetadata rightMetadata) {
        int size = Math.max(left.size(), right.size());
        int distance = 0;
        var capture=DECISIONS.get();var phases=capture==null?null:new ArrayList<PhaseDecision>();
        for (int i = 0; i < size; i++) {
            if (i >= left.size()) {
                distance += eGraphSize(right.get(i).getMatrixEGraph(), rightMetadata);
                if(capture!=null)capture.lastPhase=new PhaseDecision(Map.of(),MatrixDecision.whole(null,right.get(i).getMatrixEGraph(),eGraphSize(right.get(i).getMatrixEGraph(),rightMetadata)));
            } else if (i >= right.size()) {
                distance += eGraphSize(left.get(i).getMatrixEGraph(), leftMetadata);
                if(capture!=null)capture.lastPhase=new PhaseDecision(Map.of(),MatrixDecision.whole(left.get(i).getMatrixEGraph(),null,eGraphSize(left.get(i).getMatrixEGraph(),leftMetadata)));
            } else {
                distance += matrixDistance(
                        left.get(i),
                        right.get(i),
                        fixedMapping,
                        lockedLeftNames,
                        lockedRightNames,
                        leftMetadata,
                        rightMetadata);
            }
            if(capture!=null)phases.add(capture.lastPhase);
        }
        if(capture!=null)capture.phases=List.copyOf(phases);
        return distance;
    }

    private static MatrixAlignment bestCoherentMatrixAlignment(
            List<NormalForm> left,
            List<NormalForm> right,
            EGraphMetadata leftMetadata,
            EGraphMetadata rightMetadata) {
        if (FastPaidDeclarationAlignment.supportsPair(left,right))
            return paidDeclarationMatrixAlignment(left.get(0),right.get(0),leftMetadata,rightMetadata);
        // One source binder imported into several temporal phases must consume
        // one alpha mapping; phase-local binders remain independently aligned.
        CoherentVariableSpace leftSpace = coherentVariableSpace(left);
        CoherentVariableSpace rightSpace = coherentVariableSpace(right);
        Set<String> lockedLeftNames = leftSpace.coherentNames();
        Set<String> lockedRightNames = rightSpace.coherentNames();
        closeCoherentCandidateRegion(
                lockedLeftNames,
                lockedRightNames,
                leftSpace,
                rightSpace);
        List<String> leftNames = new ArrayList<>(lockedLeftNames);
        List<String> rightNames = new ArrayList<>(lockedRightNames);
        rightNames.sort(String::compareTo);
        leftNames.sort((a, b) -> {
            int comparison = Integer.compare(
                    coherentCandidateCount(
                            leftSpace.variables.get(a), rightNames, rightSpace),
                    coherentCandidateCount(
                            leftSpace.variables.get(b), rightNames, rightSpace));
            return comparison != 0 ? comparison : a.compareTo(b);
        });
        int requiredMatches = maximumCoherentMatches(
                leftNames, rightNames, leftSpace, rightSpace);
        BestCoherentMapping best = new BestCoherentMapping();
        searchBestCoherentMapping(
                left,
                right,
                leftNames,
                rightNames,
                leftSpace,
                rightSpace,
                new HashMap<>(),
                new HashSet<>(),
                0,
                0,
                requiredMatches,
                lockedLeftNames,
                lockedRightNames,
                leftMetadata,
                rightMetadata,
                best);
        if (best.distance == Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "No admissible coherent temporal variable alignment was evaluated");
        }
        MatrixAlignment existing = new MatrixAlignment(
                best.distance,
                best.mapping,
                lockedLeftNames,
                lockedRightNames);
        existing.phases=best.phases;
        if (FastPaidDeclarationAlignment.supportsSupplementalPair(left,right)) {
            // Both families attain the same minimum declaration cost. Retain
            // the old family, including cheaper exchange paths, and prefer an
            // actual canonical paid path on a body-cost tie. Scoring and replay
            // consume this same selected mapping and its paid correspondence.
            MatrixAlignment canonical = paidDeclarationMatrixAlignment(
                    left.get(0),right.get(0),leftMetadata,rightMetadata);
            if (canonical.distance <= existing.distance) return canonical;
        }
        return existing;
    }

    private static MatrixAlignment paidDeclarationMatrixAlignment(NormalForm left,NormalForm right,
            EGraphMetadata leftMetadata,EGraphMetadata rightMetadata) {
        List<QuantiVar> a = canonicalQuantifierOrder(left.getMatrixQuantiVars());
        List<QuantiVar> b = canonicalQuantifierOrder(right.getMatrixQuantiVars());
        if (a.size() > FastPaidDeclarationAlignment.MAX_DECLARATIONS
                || b.size() > FastPaidDeclarationAlignment.MAX_DECLARATIONS)
            throw new IllegalStateException("PAID_DECLARATION_SIZE_BUDGET");
        var sourceOrders=FastGroupedDeclarationOrbit.orders(leftMetadata.original(left),left,a);
        var targetOrders=FastGroupedDeclarationOrbit.orders(rightMetadata.original(right),right,b);
        var budget=new FastGroupedDeclarationOrbit.Budget();
        MatrixAlignment[] best = {null};
        int[][] dp=DECISIONS.get()==null?quantificationDp(a,b):DECISIONS.get().declarations.get(0);
        FastPaidDeclarationAlignment.enumerateSelected(a,b,dp,CanonicalDistance::quantifierUpdateCost,(paid,path) -> {
            for(var p:sourceOrders)for(var q:targetOrders) {
                budget.spend();
                var moved=FastGroupedDeclarationOrbit.transport(a,b,paid,p,q);
                Set<String> lockedLeft = moved.keySet(), lockedRight = new HashSet<>(moved.values());
                int distance = matrixDistance(left,right,moved,lockedLeft,lockedRight,leftMetadata,rightMetadata);
                boolean canonical=FastGroupedDeclarationOrbit.isIdentity(p)&&FastGroupedDeclarationOrbit.isIdentity(q);
                if (best[0] == null || distance < best[0].distance
                        || distance == best[0].distance && canonical && best[0].sourceOrder!=null) {
                    best[0] = new MatrixAlignment(distance,moved,lockedLeft,lockedRight);
                    var capture=DECISIONS.get();if(capture!=null)best[0].phases=List.of(capture.lastPhase);
                    best[0].paidCorrespondence = Map.copyOf(moved);
                    if(capture!=null)best[0].declarationPath=path;
                    if(!canonical){best[0].sourceOrder=p.clone();best[0].targetOrder=q.clone();}
                }
            }
        },DECISIONS.get()!=null);
        if (best[0] == null) throw new IllegalStateException("PAID_DECLARATION_NO_MINIMUM_PATH");
        return best[0];
    }

    private static void closeCoherentCandidateRegion(
            Set<String> leftNames,
            Set<String> rightNames,
            CoherentVariableSpace leftSpace,
            CoherentVariableSpace rightSpace) {
        boolean changed;
        do {
            is.fivefivefive.CanDis.WorkBudget.charge(1L + (long) rightSpace.variables.size() * (leftNames.size() + 1L)
                    + (long) leftSpace.variables.size() * (rightNames.size() + 1L));
            changed = false;
            for (Map.Entry<String, CoherentVariable> entry
                    : rightSpace.variables.entrySet()) {
                if (rightNames.contains(entry.getKey())) {
                    continue;
                }
                for (String leftName : leftNames) {
                    if (leftSpace.variables.get(leftName)
                            .compatibleWith(entry.getValue())) {
                        rightNames.add(entry.getKey());
                        changed = true;
                        break;
                    }
                }
            }
            for (Map.Entry<String, CoherentVariable> entry
                    : leftSpace.variables.entrySet()) {
                if (leftNames.contains(entry.getKey())) {
                    continue;
                }
                for (String rightName : rightNames) {
                    if (entry.getValue().compatibleWith(
                            rightSpace.variables.get(rightName))) {
                        leftNames.add(entry.getKey());
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
    }

    private static void searchBestCoherentMapping(
            List<NormalForm> left,
            List<NormalForm> right,
            List<String> leftNames,
            List<String> rightNames,
            CoherentVariableSpace leftSpace,
            CoherentVariableSpace rightSpace,
            Map<String, String> mapping,
            Set<String> usedRightNames,
            int index,
            int matched,
            int requiredMatches,
            Set<String> lockedLeftNames,
            Set<String> lockedRightNames,
            EGraphMetadata leftMetadata,
            EGraphMetadata rightMetadata,
            BestCoherentMapping best) {
        is.fivefivefive.CanDis.WorkBudget.charge(1L + rightNames.size());
        if (index == leftNames.size()) {
            if (matched != requiredMatches) {
                return;
            }
            int distance = matrixDistanceWithFixedMapping(
                    left,
                    right,
                    mapping,
                    lockedLeftNames,
                    lockedRightNames,
                    leftMetadata,
                    rightMetadata);
            if (distance < best.distance) {
                best.distance = distance;
                best.mapping = new HashMap<>(mapping);
                var capture=DECISIONS.get();if(capture!=null)best.phases=capture.phases;
            }
            return;
        }
        String leftName = leftNames.get(index);
        CoherentVariable leftVariable = leftSpace.variables.get(leftName);
        boolean mapped = false;
        for (String rightName : rightNames) {
            if (usedRightNames.contains(rightName)
                    || !leftVariable.compatibleWith(
                            rightSpace.variables.get(rightName))) {
                continue;
            }
            mapped = true;
            mapping.put(leftName, rightName);
            usedRightNames.add(rightName);
            searchBestCoherentMapping(
                    left,
                    right,
                    leftNames,
                    rightNames,
                    leftSpace,
                    rightSpace,
                    mapping,
                    usedRightNames,
                    index + 1,
                    matched + 1,
                    requiredMatches,
                    lockedLeftNames,
                    lockedRightNames,
                    leftMetadata,
                    rightMetadata,
                    best);
            usedRightNames.remove(rightName);
            mapping.remove(leftName);
            if (best.distance == 0) {
                return;
            }
        }
        if (!mapped || matched + leftNames.size() - index - 1 >= requiredMatches) {
            searchBestCoherentMapping(
                    left,
                    right,
                    leftNames,
                    rightNames,
                    leftSpace,
                    rightSpace,
                    mapping,
                    usedRightNames,
                    index + 1,
                    matched,
                    requiredMatches,
                    lockedLeftNames,
                    lockedRightNames,
                    leftMetadata,
                    rightMetadata,
                    best);
        }
    }

    private static int coherentCandidateCount(
            CoherentVariable left,
            List<String> rightNames,
            CoherentVariableSpace rightSpace) {
        is.fivefivefive.CanDis.WorkBudget.charge(1L + rightNames.size());
        int count = 0;
        for (String rightName : rightNames) {
            if (left.compatibleWith(rightSpace.variables.get(rightName))) {
                count++;
            }
        }
        return count;
    }

    private static int maximumCoherentMatches(
            List<String> leftNames,
            List<String> rightNames,
            CoherentVariableSpace leftSpace,
            CoherentVariableSpace rightSpace) {
        Map<String, String> matchedLeftByRight = new HashMap<>();
        int result = 0;
        for (String leftName : leftNames) {
            if (augmentCoherentMatching(
                    leftName,
                    rightNames,
                    leftSpace,
                    rightSpace,
                    matchedLeftByRight,
                    new HashSet<>())) {
                result++;
            }
        }
        return result;
    }

    private static boolean augmentCoherentMatching(
            String leftName,
            List<String> rightNames,
            CoherentVariableSpace leftSpace,
            CoherentVariableSpace rightSpace,
            Map<String, String> matchedLeftByRight,
            Set<String> visitedRight) {
        is.fivefivefive.CanDis.WorkBudget.charge(1L + rightNames.size());
        CoherentVariable left = leftSpace.variables.get(leftName);
        for (String rightName : rightNames) {
            if (!visitedRight.add(rightName)
                    || !left.compatibleWith(
                            rightSpace.variables.get(rightName))) {
                continue;
            }
            String previousLeft = matchedLeftByRight.get(rightName);
            if (previousLeft == null
                    || augmentCoherentMatching(
                            previousLeft,
                            rightNames,
                            leftSpace,
                            rightSpace,
                            matchedLeftByRight,
                            visitedRight)) {
                matchedLeftByRight.put(rightName, leftName);
                return true;
            }
        }
        return false;
    }

    private static CoherentVariableSpace coherentVariableSpace(
            List<NormalForm> normalForms) {
        is.fivefivefive.CanDis.WorkBudget.charge(1L + normalForms.size());
        Map<String, CoherentVariable> all = new HashMap<>();
        for (int phase = 0; phase < normalForms.size(); phase++) {
            NormalForm normalForm = normalForms.get(phase);
            Map<String, BindingDescriptor> bindings = variableBindings(normalForm);
            for (String name : matrixVariableNames(normalForm.getMatrixEGraph())) {
                BindingDescriptor descriptor = bindings.get(name);
                if (descriptor == null) {
                    continue;
                }
                CoherentVariable variable = all.computeIfAbsent(
                        name, ignored -> new CoherentVariable(descriptor));
                variable.observe(descriptor, phase);
            }
        }
        all.entrySet().removeIf(entry -> entry.getValue().ambiguous);
        // A declaration can be absent from its owner's matrix and used only
        // by descendants. Resolve ownership independently of first use.
        for (int phase = 0; phase < normalForms.size(); phase++) {
            for (var entry : variableBindings(normalForms.get(phase)).entrySet()) {
                var variable = all.get(entry.getKey());
                if (variable != null) variable.observeOwner(entry.getValue(), phase);
            }
        }
        all.entrySet().removeIf(entry -> entry.getValue().ambiguous);
        return new CoherentVariableSpace(all);
    }

    private static int matrixDistance(NormalForm left, NormalForm right) {
        return matrixDistance(left, right, null, null);
    }

    private static int matrixDistance(
            NormalForm left,
            NormalForm right,
            EGraphMetadata leftMetadata,
            EGraphMetadata rightMetadata) {
        return matrixDistance(
                left,
                right,
                java.util.Collections.emptyMap(),
                java.util.Collections.emptySet(),
                java.util.Collections.emptySet(),
                leftMetadata,
                rightMetadata);
    }

    private static int matrixDistance(
            NormalForm left,
            NormalForm right,
            Map<String, String> fixedMapping,
            Set<String> lockedLeftNames,
            Set<String> lockedRightNames,
            EGraphMetadata leftMetadata,
            EGraphMetadata rightMetadata) {
        Map<String, BindingDescriptor> leftBindings = variableBindings(left);
        Map<String, BindingDescriptor> rightBindings = variableBindings(right);
        leftBindings.keySet().retainAll(matrixVariableNames(left.getMatrixEGraph()));
        rightBindings.keySet().retainAll(matrixVariableNames(right.getMatrixEGraph()));
        leftBindings.keySet().removeAll(lockedLeftNames);
        rightBindings.keySet().removeAll(lockedRightNames);
        List<String> leftNames = new ArrayList<>(leftBindings.keySet());
        leftNames.sort((a, b) -> Integer.compare(
                candidateCount(a, leftBindings, rightBindings),
                candidateCount(b, leftBindings, rightBindings)));
        Map<String, String> mapping = new HashMap<>(fixedMapping);
        int requiredMatches = maximumCompatibleMatches(
                leftNames, leftBindings, rightBindings);
        var capture=DECISIONS.get();var selected=capture==null?null:new BestMapping();
        int result=bestMappedDistance(
                left.getMatrixEGraph(),
                right.getMatrixEGraph(),
                leftNames,
                leftBindings,
                rightBindings,
                mapping,
                new HashSet<>(lockedRightNames),
                0,
                0,
                requiredMatches,
                Integer.MAX_VALUE,
                leftMetadata,
                rightMetadata,selected);
        if(capture!=null)capture.lastPhase=new PhaseDecision(selected.mapping,selected.decision);
        return result;
    }

    private static void matrixEdits(List<NormalForm> left, List<NormalForm> right,
            EGraphMetadata leftMetadata,EGraphMetadata rightMetadata,List<String> edits) {
        MatrixAlignment alignment = bestCoherentMatrixAlignment(
                left, right, leftMetadata, rightMetadata);
        int size = Math.max(left.size(), right.size());
        for (int i = 0; i < size; i++) {
            NormalForm pathSource = i < left.size() ? left.get(i) : right.get(i);
            String path = normalFormPath(pathSource, i) + ".matrix";
            if (i >= left.size()) {
                collectInsertedEGraph(right.get(i).getMatrixEGraph(), path, edits);
            } else if (i >= right.size()) {
                collectDeletedEGraph(left.get(i).getMatrixEGraph(), path, edits);
            } else {
                matrixEdits(
                        left.get(i), right.get(i), path, edits, alignment);
            }
        }
    }

    private static void matrixEdits(NormalForm left, NormalForm right, String path, List<String> edits) {
        matrixEdits(left, right, path, edits, MatrixAlignment.empty());
    }

    private static void matrixEdits(
            NormalForm left,
            NormalForm right,
            String path,
            List<String> edits,
            MatrixAlignment alignment) {
        Map<String, String> mapping = bestVariableMapping(
                left,
                right,
                alignment.mapping,
                alignment.lockedLeftNames,
                alignment.lockedRightNames);
        eGraphEdits(left.getMatrixEGraph(), right.getMatrixEGraph(), mapping, path, edits);
    }

    private static Map<String, String> bestVariableMapping(NormalForm left, NormalForm right) {
        return bestVariableMapping(
                left,
                right,
                java.util.Collections.emptyMap(),
                java.util.Collections.emptySet(),
                java.util.Collections.emptySet());
    }

    private static Map<String, String> bestVariableMapping(
            NormalForm left,
            NormalForm right,
            Map<String, String> fixedMapping,
            Set<String> lockedLeftNames,
            Set<String> lockedRightNames) {
        Map<String, BindingDescriptor> leftBindings = variableBindings(left);
        Map<String, BindingDescriptor> rightBindings = variableBindings(right);
        leftBindings.keySet().retainAll(matrixVariableNames(left.getMatrixEGraph()));
        rightBindings.keySet().retainAll(matrixVariableNames(right.getMatrixEGraph()));
        leftBindings.keySet().removeAll(lockedLeftNames);
        rightBindings.keySet().removeAll(lockedRightNames);
        List<String> leftNames = new ArrayList<>(leftBindings.keySet());
        leftNames.sort((a, b) -> Integer.compare(
                candidateCount(a, leftBindings, rightBindings),
                candidateCount(b, leftBindings, rightBindings)));
        BestMapping best = new BestMapping();
        int requiredMatches = maximumCompatibleMatches(
                leftNames, leftBindings, rightBindings);
        searchBestMapping(
                left.getMatrixEGraph(),
                right.getMatrixEGraph(),
                leftNames,
                leftBindings,
                rightBindings,
                new HashMap<>(fixedMapping),
                new HashSet<>(lockedRightNames),
                0,
                0,
                requiredMatches,
                best);
        return best.mapping;
    }

    private static void searchBestMapping(
            EGraphNode left,
            EGraphNode right,
            List<String> leftNames,
            Map<String, BindingDescriptor> leftBindings,
            Map<String, BindingDescriptor> rightBindings,
            Map<String, String> mapping,
            Set<String> usedRightNames,
            int index,
            int matched,
            int requiredMatches,
            BestMapping best) {
        is.fivefivefive.CanDis.WorkBudget.charge(1L + rightBindings.size());
        if (index == leftNames.size()) {
            if (matched != requiredMatches) {
                return;
            }
            int distance = eGraphDistance(left, right, mapping);
            if (distance < best.distance) {
                best.distance = distance;
                best.mapping = new HashMap<>(mapping);
            }
            return;
        }
        String leftName = leftNames.get(index);
        BindingDescriptor leftBinding = leftBindings.get(leftName);
        boolean mapped = false;
        for (Map.Entry<String, BindingDescriptor> rightEntry : rightBindings.entrySet()) {
            if (usedRightNames.contains(rightEntry.getKey())
                    || !leftBinding.compatibleWith(rightEntry.getValue())) {
                continue;
            }
            mapped = true;
            mapping.put(leftName, rightEntry.getKey());
            usedRightNames.add(rightEntry.getKey());
            searchBestMapping(left, right, leftNames, leftBindings, rightBindings,
                    mapping, usedRightNames, index + 1,
                    matched + 1, requiredMatches, best);
            usedRightNames.remove(rightEntry.getKey());
            mapping.remove(leftName);
            if (best.distance == 0) {
                return;
            }
        }
        if (!mapped || matched + leftNames.size() - index - 1 >= requiredMatches) {
            searchBestMapping(left, right, leftNames, leftBindings, rightBindings,
                    mapping, usedRightNames, index + 1,
                    matched, requiredMatches, best);
        }
    }

    private static int candidateCount(
            String leftName,
            Map<String, BindingDescriptor> leftBindings,
            Map<String, BindingDescriptor> rightBindings) {
        is.fivefivefive.CanDis.WorkBudget.charge(1L + rightBindings.size());
        int count = 0;
        BindingDescriptor leftBinding = leftBindings.get(leftName);
        for (BindingDescriptor rightBinding : rightBindings.values()) {
            if (leftBinding.compatibleWith(rightBinding)) {
                count++;
            }
        }
        return count;
    }

    private static int bestMappedDistance(
            EGraphNode left,
            EGraphNode right,
            List<String> leftNames,
            Map<String, BindingDescriptor> leftBindings,
            Map<String, BindingDescriptor> rightBindings,
            Map<String, String> mapping,
            Set<String> usedRightNames,
            int index,
            int matched,
            int requiredMatches,
            int best,
            EGraphMetadata leftMetadata,
            EGraphMetadata rightMetadata,BestMapping selected) {
        is.fivefivefive.CanDis.WorkBudget.charge(1L + rightBindings.size());
        if (index == leftNames.size()) {
            if (matched != requiredMatches) {
                return best;
            }
            var context=new EGraphDistanceContext(mapping,leftMetadata,rightMetadata,selected!=null);
            int candidate=context.distance(left,right);
            if(selected!=null && candidate<best){selected.mapping=Map.copyOf(mapping);selected.decision=context.decision(left,right);}
            return Math.min(best,candidate);
        }
        String leftName = leftNames.get(index);
        BindingDescriptor leftBinding = leftBindings.get(leftName);
        boolean mapped = false;
        for (Map.Entry<String, BindingDescriptor> rightEntry : rightBindings.entrySet()) {
            if (usedRightNames.contains(rightEntry.getKey())
                    || !leftBinding.compatibleWith(rightEntry.getValue())) {
                continue;
            }
            mapped = true;
            mapping.put(leftName, rightEntry.getKey());
            usedRightNames.add(rightEntry.getKey());
            best = bestMappedDistance(
                    left,
                    right,
                    leftNames,
                    leftBindings,
                    rightBindings,
                    mapping,
                    usedRightNames,
                    index + 1,
                    matched + 1,
                    requiredMatches,
                    best,
                    leftMetadata,
                    rightMetadata,selected);
            usedRightNames.remove(rightEntry.getKey());
            mapping.remove(leftName);
            if (best == 0) {
                return 0;
            }
        }
        if (!mapped || matched + leftNames.size() - index - 1 >= requiredMatches) {
            best = bestMappedDistance(
                    left,
                    right,
                    leftNames,
                    leftBindings,
                    rightBindings,
                    mapping,
                    usedRightNames,
                    index + 1,
                    matched,
                    requiredMatches,
                    best,
                    leftMetadata,
                    rightMetadata,selected);
        }
        return best;
    }

    private static int maximumCompatibleMatches(
            List<String> leftNames,
            Map<String, BindingDescriptor> leftBindings,
            Map<String, BindingDescriptor> rightBindings) {
        Map<String, String> matchedLeftByRight = new HashMap<>();
        int result = 0;
        for (String leftName : leftNames) {
            if (augmentCompatibleMatching(
                    leftName,
                    leftBindings,
                    rightBindings,
                    matchedLeftByRight,
                    new HashSet<>())) {
                result++;
            }
        }
        return result;
    }

    private static boolean augmentCompatibleMatching(
            String leftName,
            Map<String, BindingDescriptor> leftBindings,
            Map<String, BindingDescriptor> rightBindings,
            Map<String, String> matchedLeftByRight,
            Set<String> visitedRight) {
        is.fivefivefive.CanDis.WorkBudget.charge(1L + rightBindings.size());
        BindingDescriptor left = leftBindings.get(leftName);
        for (Map.Entry<String, BindingDescriptor> right : rightBindings.entrySet()) {
            if (!visitedRight.add(right.getKey())
                    || !left.compatibleWith(right.getValue())) {
                continue;
            }
            String previousLeft = matchedLeftByRight.get(right.getKey());
            if (previousLeft == null
                    || augmentCompatibleMatching(
                            previousLeft,
                            leftBindings,
                            rightBindings,
                            matchedLeftByRight,
                            visitedRight)) {
                matchedLeftByRight.put(right.getKey(), leftName);
                return true;
            }
        }
        return false;
    }

    private static Map<String, BindingDescriptor> variableBindings(NormalForm nf) {
        is.fivefivefive.CanDis.WorkBudget.charge(1);
        // Equal candidate counts retain original declaration order, as in the
        // certified global index. Search coverage and the minimum are unchanged.
        Map<String, BindingDescriptor> bindings = new java.util.LinkedHashMap<>();
        addBindings(bindings, nf.getParams(), BindingRole.PARAMETER);
        addBindings(bindings, nf.getMatrixQuantiVars(), BindingRole.MATRIX);
        addBindings(bindings, nf.getInheritedQuantiVars(), BindingRole.INHERITED);
        addBindings(bindings, nf.getLocalQuantiVars(), BindingRole.LOCAL);
        return bindings;
    }

    private static void addBindings(
            Map<String, BindingDescriptor> bindings,
            List<QuantiVar> variables,
            BindingRole role) {
        is.fivefivefive.CanDis.WorkBudget.charge(variables.size());
        for (int i = 0; i < variables.size(); i++) {
            QuantiVar variable = variables.get(i);
            bindings.put(variable.getName(), new BindingDescriptor(variable, role, i));
        }
    }

    private static Set<String> matrixVariableNames(EGraphNode node) {
        Set<String> names = new HashSet<>();
        collectMatrixVariableNames(node, names);
        return names;
    }

    private static void collectMatrixVariableNames(EGraphNode node, Set<String> names) {
        if (node == null) {
            return;
        }
        is.fivefivefive.CanDis.WorkBudget.charge(1);
        if (node.getOpcode() == EGraphNode.Opcode.VARIABLE) {
            names.add(variableName(node));
        }
        for (EGraphNode child : node.getChildren()) {
            collectMatrixVariableNames(child, names);
        }
    }

    private static void eGraphEdits(
            EGraphNode left,
            EGraphNode right,
            Map<String, String> variableMapping,
            String path,
            List<String> edits) {
        is.fivefivefive.CanDis.WorkBudget.charge(1);
        if (left == null) {
            collectInsertedEGraph(right, path, edits);
            return;
        }
        if (right == null) {
            collectDeletedEGraph(left, path, edits);
            return;
        }
        if (left.getOpcode() == right.getOpcode()
                && left.isOrderInsensitive()
                && right.isOrderInsensitive()
                && eGraphDistance(left, right, variableMapping) == 0) {
            return;
        }
        if (left.getOpcode() != right.getOpcode()) {
            edits.add(path + ": replace " + nodeSummary(left) + " -> " + nodeSummary(right));
        } else if (left.getOpcode() == EGraphNode.Opcode.VARIABLE) {
            if (variableUpdateCost(left, right, variableMapping) != 0) {
                edits.add(path + ": replace variable " + nodeVariableDisplay(left) + " -> " + nodeVariableDisplay(right));
            }
        } else if ((left.getOpcode() == EGraphNode.Opcode.GLOBALBINDING
                || left.getOpcode() == EGraphNode.Opcode.CONSTANT
                || left.getOpcode() == EGraphNode.Opcode.REF)
                && !safeEquals(atomIdentity(left), atomIdentity(right))) {
            edits.add(path + ": replace binding " + atomDisplay(left)
                    + " -> " + atomDisplay(right));
        } else if (left.getOpcode() != EGraphNode.Opcode.VARIABLE
                && !Objects.equals(resultType(left), resultType(right))) {
            edits.add(path + ": replace result type " + resultType(left)
                    + " -> " + resultType(right));
        }
        List<EGraphNode> leftChildren = FastChainGeometry.children(left);
        List<EGraphNode> rightChildren = FastChainGeometry.children(right);
        if (left.getOpcode() == right.getOpcode() && left.isOrderInsensitive() && right.isOrderInsensitive()) {
            leftChildren = sortedForMapping(leftChildren, variableMapping);
            rightChildren = sortedForMapping(rightChildren, java.util.Collections.emptyMap());
        }
        eGraphChildEdits(leftChildren, rightChildren, variableMapping, path + ".child", edits);
    }

    private static void eGraphChildEdits(
            List<EGraphNode> left,
            List<EGraphNode> right,
            Map<String, String> variableMapping,
            String path,
            List<String> edits) {
        int[][] dp = eGraphChildDp(left, right, variableMapping);
        int i = left.size();
        int j = right.size();
        List<String> reversed = new ArrayList<>();
        while (i > 0 || j > 0) {
            is.fivefivefive.CanDis.WorkBudget.charge(1);
            if (i > 0 && dp[i][j] == dp[i - 1][j] + eGraphSize(left.get(i - 1))) {
                collectDeletedEGraph(left.get(i - 1), path + "[" + (i - 1) + "]", reversed);
                i--;
            } else if (j > 0 && dp[i][j] == dp[i][j - 1] + eGraphSize(right.get(j - 1))) {
                collectInsertedEGraph(right.get(j - 1), path + "[" + j + "]", reversed);
                j--;
            } else {
                eGraphEdits(left.get(i - 1), right.get(j - 1), variableMapping, path + "[" + (i - 1) + "]", reversed);
                i--;
                j--;
            }
        }
        appendReverse(reversed, edits);
    }

    private static int[][] eGraphChildDp(
            List<EGraphNode> left,
            List<EGraphNode> right,
            Map<String, String> variableMapping) {
        int[][] dp = intMatrix(left.size() + 1, right.size() + 1);
        for (int i = 1; i <= left.size(); i++) {
            dp[i][0] = dp[i - 1][0] + eGraphSize(left.get(i - 1));
        }
        for (int j = 1; j <= right.size(); j++) {
            dp[0][j] = dp[0][j - 1] + eGraphSize(right.get(j - 1));
        }
        for (int i = 1; i <= left.size(); i++) {
            for (int j = 1; j <= right.size(); j++) {
                int delete = dp[i - 1][j] + eGraphSize(left.get(i - 1));
                int insert = dp[i][j - 1] + eGraphSize(right.get(j - 1));
                int update = dp[i - 1][j - 1] + eGraphDistance(left.get(i - 1), right.get(j - 1), variableMapping);
                dp[i][j] = Math.min(update, Math.min(delete, insert));
            }
        }
        return dp;
    }

    private static void collectInsertedEGraph(EGraphNode node, String path, List<String> edits) {
        if (node == null) {
            return;
        }
        is.fivefivefive.CanDis.WorkBudget.charge(1);
        edits.add(path + ": insert " + nodeSummary(node));
        List<EGraphNode> children = FastChainGeometry.children(node);
        for (int i = 0; i < children.size(); i++) {
            collectInsertedEGraph(children.get(i), path + ".child[" + i + "]", edits);
        }
    }

    private static void collectDeletedEGraph(EGraphNode node, String path, List<String> edits) {
        if (node == null) {
            return;
        }
        is.fivefivefive.CanDis.WorkBudget.charge(1);
        edits.add(path + ": delete " + nodeSummary(node));
        List<EGraphNode> children = FastChainGeometry.children(node);
        for (int i = 0; i < children.size(); i++) {
            collectDeletedEGraph(children.get(i), path + ".child[" + i + "]", edits);
        }
    }

    private static int eGraphDistance(EGraphNode left, EGraphNode right, Map<String, String> variableMapping) {
        return eGraphDistance(left, right, variableMapping, null, null);
    }

    private static int eGraphDistance(
            EGraphNode left,
            EGraphNode right,
            Map<String, String> variableMapping,
            EGraphMetadata leftMetadata,
            EGraphMetadata rightMetadata) {
        return new EGraphDistanceContext(variableMapping, leftMetadata, rightMetadata).distance(left, right);
    }

    private static List<EGraphNode> sortedForMapping(
            List<EGraphNode> children,
            Map<String, String> variableMapping) {
        is.fivefivefive.CanDis.WorkBudget.charge(1L + (long) children.size() * children.size());
        List<EGraphNode> sorted = new ArrayList<>(children);
        Map<EGraphNode, String> keys = new IdentityHashMap<>();
        for (EGraphNode child : sorted) {
            keys.put(child, mappedSortKey(child, variableMapping));
        }
        sorted.sort((left, right) -> keys.get(left).compareTo(keys.get(right)));
        return sorted;
    }

    private static String mappedSortKey(EGraphNode node, Map<String, String> variableMapping) {
        is.fivefivefive.CanDis.WorkBudget.charge(1L + node.getChildren().size());
        StringBuilder key = new StringBuilder(node.getOpcode().toString())
                .append('{').append(node.getFlexibleArityKind()).append("}:");
        if (node.getOpcode() == EGraphNode.Opcode.VARIABLE) {
            String name = variableName(node);
            key.append(variableMapping.getOrDefault(name, name));
        } else if (node.getOpcode() == EGraphNode.Opcode.CALL) {
            key.append(callIdentity(node));
        } else if (node.getSourceName() != null) {
            key.append(atomIdentity(node));
        }
        key.append('[');
        List<EGraphNode> children = new ArrayList<>(FastChainGeometry.children(node));
        if (node.isOrderInsensitive()) {
            children = sortedForMapping(children, variableMapping);
        }
        if (node.isSetFlexibleArity()) {
            java.util.Set<String> members = new java.util.TreeSet<>();
            for (EGraphNode child : children) {
                members.add(mappedSortKey(child, variableMapping));
            }
            for (String member : members) {
                is.fivefivefive.CanDis.WorkBudget.charge(member.length());
                key.append(member).append(',');
            }
        } else if (node.isBagFlexibleArity()) {
            Map<String, Integer> multiplicities = new java.util.TreeMap<>();
            for (EGraphNode child : children) {
                String childKey = mappedSortKey(child, variableMapping);
                multiplicities.put(childKey, multiplicities.getOrDefault(childKey, 0) + 1);
            }
            for (Map.Entry<String, Integer> entry : multiplicities.entrySet()) {
                is.fivefivefive.CanDis.WorkBudget.charge(entry.getKey().length());
                key.append(entry.getKey());
                if (entry.getValue() > 1) {
                    key.append('^').append(entry.getValue());
                }
                key.append(',');
            }
        } else {
            for (EGraphNode child : children) {
                String childKey = mappedSortKey(child, variableMapping);
                is.fivefivefive.CanDis.WorkBudget.charge(childKey.length());
                key.append(childKey).append(',');
            }
        }
        return key.append(']').toString();
    }

    private static int nodeUpdateCost(EGraphNode left, EGraphNode right, Map<String, String> variableMapping,
            EGraphMetadata leftMetadata, EGraphMetadata rightMetadata) {
        if (left.getOpcode() != right.getOpcode()) {
            return 1;
        }
        if (left.getOpcode() == EGraphNode.Opcode.VARIABLE) {
            return variableUpdateCost(left, right, variableMapping);
        }
        GraphType leftType = leftMetadata == null ? resultType(left) : leftMetadata.type(left);
        GraphType rightType = rightMetadata == null ? resultType(right) : rightMetadata.type(right);
        if (!Objects.equals(leftType, rightType)) {
            return 1;
        }
        if (left.getOpcode() == EGraphNode.Opcode.GLOBALBINDING
                || left.getOpcode() == EGraphNode.Opcode.CONSTANT
                || left.getOpcode() == EGraphNode.Opcode.REF) {
            return safeEquals(atomIdentity(left), atomIdentity(right)) ? 0 : 1;
        }
        if (left.getOpcode() == EGraphNode.Opcode.CALL) {
            return safeEquals(callIdentity(left), callIdentity(right)) ? 0 : 1;
        }
        return 0;
    }

    static int variableUpdateCost(EGraphNode left, EGraphNode right, Map<String, String> variableMapping) {
        String leftName = variableName(left), rightName = variableName(right);
        // Alpha names are local binder coordinates, not cross-source identities.
        // Local structural coordinates keep their existing projection cost;
        // compatible/paid selected links govern external bound coordinates.
        if ((left.hasComparisonRegisteredBinding() || right.hasComparisonRegisteredBinding())
                && !(left.hasComparisonLocalCoordinate() && right.hasComparisonLocalCoordinate())
                && !variableMapping.containsKey(leftName)) return 1;
        return variableUpdateCost(leftName, rightName, variableMapping);
    }

    static int variableUpdateCost(String leftName, String rightName, Map<String, String> variableMapping) {
        String mappedName = variableMapping.get(leftName);
        if (mappedName != null) return mappedName.equals(rightName) ? 0 : 1;
        // Identity fallback must not create a second preimage of a target
        // already consumed by the selected source-linked binder alignment.
        return leftName.equals(rightName) && !variableMapping.containsValue(rightName) ? 0 : 1;
    }

    private static GraphType resultType(EGraphNode node) {
        switch (node.getOpcode()) {
            case VARIABLE: case GENERICRELDECL: case DISJ: case VAR: case DISJVAR:
                return null;
            default:
                break;
        }
        if (node.getMetatype() == EGraphNode.Metatype.BOOLEAN) {
            return GraphType.BOOL;
        }
        if (node.getOpcode() == EGraphNode.Opcode.CARDINALITY
                || node.getOpcode() == EGraphNode.Opcode.CAST2INT) {
            return GraphType.INT;
        }
        // Untyped historical core fixtures remain non-certifying inputs.
        return node.getExactAlloyType() == null
                ? null : AlloyTypeBridge.graphType(node.getExactAlloyType());
    }

    private static String callIdentity(EGraphNode node) {
        return CallMetadata.semanticKey(node);
    }

    private static String atomIdentity(EGraphNode node) {
        if (node.getOpcode() == EGraphNode.Opcode.REF && node.hasComparisonTemporalReferenceName())
            return node.comparisonTemporalReferenceName();
        if (node.getSemanticIdentity() != null) {
            return node.getSemanticIdentity();
        }
        if (node.getOpcode() == EGraphNode.Opcode.GLOBALBINDING
                && node.getSourceType() != null
                && node.getSourceType().startsWith("FieldRelation")
                && node.getExactAlloyType() != null) {
            String name = node.getSourceName() == null
                    ? "" : node.getSourceName();
            // Some parser field leaves predate semanticIdentity. Their exact
            // relation type retains the declaring owner erased from spelling.
            return "alloy/field/" + name.length() + ":" + name + "/"
                    + node.getExactAlloyType().stableString();
        }
        return node.getSourceName();
    }

    private static String atomDisplay(EGraphNode node) {
        if (node.getOpcode() == EGraphNode.Opcode.GLOBALBINDING
                && node.getSourceType() != null
                && node.getSourceType().startsWith("FieldRelation")
                && node.getExactAlloyType() != null) {
            return display(node.getSourceName()) + " : "
                    + node.getExactAlloyType().stableString();
        }
        return display(node.getSourceName());
    }

    private static final class EGraphMetadata {
        private final IdentityHashMap<NormalForm,NormalForm> originals=new IdentityHashMap<>();
        private final IdentityHashMap<EGraphNode, Integer> sizes = new IdentityHashMap<>();
        private final IdentityHashMap<EGraphNode, GraphType> resultTypes = new IdentityHashMap<>();
        private final IdentityHashMap<EGraphNode, List<EGraphNode>> metricChildren = new IdentityHashMap<>();

        private EGraphMetadata(List<NormalForm> sourceForms,List<NormalForm> normalForms) {
            // Closed temporal dedup can change phase-list length. This authority
            // is needed only by the existing single, non-temporal paid slice.
            if(sourceForms.size()==1 && normalForms.size()==1)
                originals.put(normalForms.get(0),sourceForms.get(0));
            for (int i=0;i<normalForms.size();i++) {
                NormalForm normalForm=normalForms.get(i);
                prepare(normalForm.getMatrixEGraph());
            }
        }

        private NormalForm original(NormalForm comparison) {
            return Objects.requireNonNull(originals.get(comparison),"Missing original metric source");
        }

        private void prepare(EGraphNode node) {
            if (node == null || sizes.containsKey(node)) {
                return;
            }
            is.fivefivefive.CanDis.WorkBudget.charge(1);
            List<EGraphNode> children = FastChainGeometry.children(node);
            metricChildren.put(node, children);
            int size = 1;
            for (int i = 0; i < children.size(); i++) {
                EGraphNode child = children.get(i);
                prepare(child);
                size += sizes.get(child);
            }
            sizes.put(node, size);
            resultTypes.put(node, resultType(node));
        }

        private int size(EGraphNode node) {
            if (node == null) {
                return 0;
            }
            Integer size = sizes.get(node);
            if (size == null) {
                prepare(node);
                size = sizes.get(node);
            }
            return size;
        }

        private GraphType type(EGraphNode node) {
            if (!resultTypes.containsKey(node)) {
                prepare(node);
            }
            return resultTypes.get(node);
        }

        private List<EGraphNode> children(EGraphNode node) {
            if (!metricChildren.containsKey(node)) prepare(node);
            return metricChildren.get(node);
        }

    }

    private static final class EGraphDistanceContext {
        private final Map<String, String> variableMapping;
        private final EGraphMetadata leftMetadata;
        private final EGraphMetadata rightMetadata;
        private final IdentityHashMap<EGraphNode,IdentityHashMap<EGraphNode,MatrixDecision<EGraphNode>>> decisions;
        private List<MatrixDecision.Edge<EGraphNode>> edges;

        private EGraphDistanceContext(
                Map<String, String> variableMapping,
                EGraphMetadata leftMetadata,
                EGraphMetadata rightMetadata) {
            this(variableMapping,leftMetadata,rightMetadata,false);
        }
        private EGraphDistanceContext(Map<String,String> variableMapping,EGraphMetadata leftMetadata,
                EGraphMetadata rightMetadata,boolean capture) {
            this.variableMapping = capture?Map.copyOf(variableMapping):variableMapping;
            this.leftMetadata = leftMetadata;
            this.rightMetadata = rightMetadata;
            decisions=capture?new IdentityHashMap<>():null;
        }
        private MatrixDecision<EGraphNode> decision(EGraphNode a,EGraphNode b) {
            if(a==null||b==null)return MatrixDecision.whole(a,b,nodeSize(a==null?b:a,a!=null));
            return Objects.requireNonNull(decisions.get(a).get(b),"Missing evaluated node decision");
        }
        private MatrixDecision.Edge<EGraphNode> edge(List<EGraphNode> a,List<EGraphNode> b,int i,int j) {
            return new MatrixDecision.Edge<>(i,j,decision(i<0?null:a.get(i),j<0?null:b.get(j)));
        }

        private int distance(EGraphNode left, EGraphNode right) {
            is.fivefivefive.CanDis.WorkBudget.charge(1);
            if (left == null) {
                return nodeSize(right, false);
            }
            if (right == null) {
                return nodeSize(left, true);
            }

            List<EGraphNode> leftChildren = leftMetadata == null
                    ? FastChainGeometry.children(left) : leftMetadata.children(left);
            List<EGraphNode> rightChildren = rightMetadata == null
                    ? FastChainGeometry.children(right) : rightMetadata.children(right);
            int childCost = left.getOpcode() == right.getOpcode()
                    && left.isOrderInsensitive()
                    && right.isOrderInsensitive()
                    ? unorderedChildDistance(leftChildren, rightChildren)
                    : childDistance(leftChildren, rightChildren);
            int head=nodeUpdateCost(left,right,variableMapping,leftMetadata,rightMetadata),total=head+childCost;
            if(decisions!=null)decisions.computeIfAbsent(left,k->new IdentityHashMap<>()).put(right,
                    MatrixDecision.node(left,right,total,head,left.getOpcode()==right.getOpcode()
                            &&left.isOrderInsensitive()&&right.isOrderInsensitive(),edges));
            return total;
        }

        private int unorderedChildDistance(List<EGraphNode> left, List<EGraphNode> right) {
            if (left.isEmpty() || right.isEmpty()) {
                return childDistance(left, right);
            }
            int leftSize = left.size();
            int rightSize = right.size();
            int dimension = Math.addExact(leftSize, rightSize);
            int[][] costs = intMatrix(dimension, dimension);
            for (int i = 0; i < dimension; i++) {
                for (int j = 0; j < dimension; j++) {
                    if (i < leftSize && j < rightSize) {
                        costs[i][j] = distance(left.get(i), right.get(j));
                    } else if (i < leftSize) {
                        costs[i][j] = nodeSize(left.get(i), true);
                    } else if (j < rightSize) {
                        costs[i][j] = nodeSize(right.get(j), false);
                    }
                }
            }
            int[] columns=decisions==null?null:new int[dimension];
            int total=minimumAssignmentCost(costs,columns);
            if(decisions!=null){var chosen=new ArrayList<MatrixDecision.Edge<EGraphNode>>();
                for(int i=0;i<dimension;i++){int j=columns[i];if(i<leftSize||j<rightSize)chosen.add(edge(left,right,i<leftSize?i:-1,j<rightSize?j:-1));}
                edges=List.copyOf(chosen);}
            return total;
        }

        private static int minimumAssignmentCost(int[][] costs) {
            return minimumAssignmentCost(costs,null);
        }
        private static int minimumAssignmentCost(int[][] costs,int[] columns) {
            int size = costs.length;
            is.fivefivefive.CanDis.WorkBudget.chargeCells(size, size);
            for (int row = 0; row < size; row++) {
                if (costs[row] == null || costs[row].length != size) {
                    throw new IllegalArgumentException(
                            "Assignment cost matrix must be square");
                }
                for (int cost : costs[row]) {
                    if (cost < 0) {
                        throw new IllegalArgumentException(
                                "Assignment costs must be non-negative");
                    }
                }
            }
            long[] rowPotential = new long[Math.addExact(size, 1)];
            long[] columnPotential = new long[Math.addExact(size, 1)];
            int[] columnMatch = intArray(Math.addExact(size, 1));
            int[] predecessor = intArray(Math.addExact(size, 1));
            for (int row = 1; row <= size; row++) {
                columnMatch[0] = row;
                int column = 0;
                long[] minimum = new long[Math.addExact(size, 1)];
                java.util.Arrays.fill(minimum, Long.MAX_VALUE);
                boolean[] used = booleanArray(Math.addExact(size, 1));
                do {
                    is.fivefivefive.CanDis.WorkBudget.charge(2L * (size + 1));
                    used[column] = true;
                    int matchedRow = columnMatch[column];
                    long delta = Long.MAX_VALUE;
                    int nextColumn = 0;
                    for (int candidate = 1; candidate <= size; candidate++) {
                        if (used[candidate]) {
                            continue;
                        }
                        long reduced = Math.subtractExact(
                                Math.subtractExact(
                                        (long) costs[matchedRow - 1][candidate - 1],
                                        rowPotential[matchedRow]),
                                columnPotential[candidate]);
                        if (reduced < minimum[candidate]) {
                            minimum[candidate] = reduced;
                            predecessor[candidate] = column;
                        }
                        if (minimum[candidate] < delta) {
                            delta = minimum[candidate];
                            nextColumn = candidate;
                        }
                    }
                    for (int candidate = 0; candidate <= size; candidate++) {
                        if (used[candidate]) {
                            rowPotential[columnMatch[candidate]] = Math.addExact(
                                    rowPotential[columnMatch[candidate]], delta);
                            columnPotential[candidate] = Math.subtractExact(
                                    columnPotential[candidate], delta);
                        } else if (minimum[candidate] != Long.MAX_VALUE) {
                            minimum[candidate] = Math.subtractExact(
                                    minimum[candidate], delta);
                        }
                    }
                    column = nextColumn;
                } while (columnMatch[column] != 0);
                do {
                    int previous = predecessor[column];
                    columnMatch[column] = columnMatch[previous];
                    column = previous;
                } while (column != 0);
            }
            if(columns!=null)for(int col=1;col<=size;col++)columns[columnMatch[col]-1]=col-1;
            return Math.toIntExact(Math.negateExact(columnPotential[0]));
        }

        private int childDistance(List<EGraphNode> left, List<EGraphNode> right) {
            if (left.isEmpty()) {
                int distance = 0;
                for (int i = 0; i < right.size(); i++) {
                    distance = Math.addExact(
                            distance, nodeSize(right.get(i), false));
                }
                if(decisions!=null){var chosen=new ArrayList<MatrixDecision.Edge<EGraphNode>>();for(int j=0;j<right.size();j++)chosen.add(edge(left,right,-1,j));edges=List.copyOf(chosen);}
                return distance;
            }
            if (right.isEmpty()) {
                int distance = 0;
                for (int i = 0; i < left.size(); i++) {
                    distance = Math.addExact(
                            distance, nodeSize(left.get(i), true));
                }
                if(decisions!=null){var chosen=new ArrayList<MatrixDecision.Edge<EGraphNode>>();for(int i=0;i<left.size();i++)chosen.add(edge(left,right,i,-1));edges=List.copyOf(chosen);}
                return distance;
            }
            if (left.size() == 1 && right.size() == 1) {
                int total=distance(left.get(0),right.get(0));
                if(decisions!=null)edges=List.of(edge(left,right,0,0));return total;
            }

            int[] previous = intArray(right.size() + 1);
            int[] current = intArray(right.size() + 1);
            if(decisions!=null)DECISIONS.get().resources.cells((long)left.size()+1,(long)right.size()+1);
            int[][] retained=decisions==null?null:intMatrix(left.size()+1,right.size()+1);
            for (int j = 1; j <= right.size(); j++) {
                previous[j] = Math.addExact(
                        previous[j - 1], nodeSize(right.get(j - 1), false));
            }
            if(retained!=null)System.arraycopy(previous,0,retained[0],0,previous.length);
            for (int i = 1; i <= left.size(); i++) {
                current[0] = Math.addExact(
                        previous[0], nodeSize(left.get(i - 1), true));
                for (int j = 1; j <= right.size(); j++) {
                    int delete = Math.addExact(
                            previous[j], nodeSize(left.get(i - 1), true));
                    int insert = Math.addExact(
                            current[j - 1], nodeSize(right.get(j - 1), false));
                    int update = Math.addExact(
                            previous[j - 1],
                            distance(left.get(i - 1), right.get(j - 1)));
                    current[j] = Math.min(update, Math.min(delete, insert));
                }
                if(retained!=null)System.arraycopy(current,0,retained[i],0,current.length);
                int[] swap = previous;
                previous = current;
                current = swap;
            }
            if(retained!=null){var chosen=new ArrayList<MatrixDecision.Edge<EGraphNode>>();int i=left.size(),j=right.size();
                while(i>0||j>0){
                    if(i>0&&j>0&&retained[i][j]==retained[i-1][j-1]+decision(left.get(i-1),right.get(j-1)).cost())chosen.add(edge(left,right,--i,--j));
                    else if(i>0&&retained[i][j]==retained[i-1][j]+nodeSize(left.get(i-1),true))chosen.add(edge(left,right,--i,-1));
                    else chosen.add(edge(left,right,-1,--j));
                }
                java.util.Collections.reverse(chosen);edges=List.copyOf(chosen);
            }
            return previous[right.size()];
        }

        private int nodeSize(EGraphNode node, boolean leftSide) {
            EGraphMetadata metadata = leftSide ? leftMetadata : rightMetadata;
            return metadata == null ? eGraphSize(node) : metadata.size(node);
        }

    }

    private static int eGraphSize(EGraphNode node) {
        if (node == null) {
            return 0;
        }
        is.fivefivefive.CanDis.WorkBudget.charge(1);
        int size = 1;
        List<EGraphNode> children = FastChainGeometry.children(node);
        for (int i = 0; i < children.size(); i++) {
            size += eGraphSize(children.get(i));
        }
        return size;
    }

    private static int eGraphSize(EGraphNode node, EGraphMetadata metadata) {
        return metadata == null ? eGraphSize(node) : metadata.size(node);
    }

    private static TemporalTree temporalTree(List<NormalForm> nfs) {
        TemporalTree root = new TemporalTree(naturalTemporalLabel(TemporalOp.NONE));
        if (nfs.isEmpty()) {
            return root;
        }
        appendTemporalChildren(root, nfs.get(0).comparisonTemporalChildren());
        return root;
    }

    private static TemporalTree temporalTree(NormalForm nf) {
        TemporalTree tree = new TemporalTree(naturalTemporalLabel(nf.getTemporalOp()));
        appendTemporalChildren(tree, nf.comparisonTemporalChildren());
        return tree;
    }

    private static void appendTemporalChildren(TemporalTree parent, List<NormalForm> children) {
        for (int i = 0; i < children.size(); i++) {
            NormalForm child = children.get(i);
            if (isLeftBinaryTemporal(child.getTemporalOp())
                    && i + 1 < children.size()
                    && matchingRightBinaryTemporal(child.getTemporalOp()) == children.get(i + 1).getTemporalOp()) {
                TemporalTree binary = new TemporalTree(naturalTemporalLabel(child.getTemporalOp()));
                appendTemporalChildren(binary, child.comparisonTemporalChildren());
                appendTemporalChildren(binary, children.get(i + 1).comparisonTemporalChildren());
                parent.children.add(binary);
                i++;
            } else {
                parent.children.add(temporalTree(child));
            }
        }
    }

    private static boolean isLeftBinaryTemporal(TemporalOp op) {
        return op == TemporalOp.UNTILL || op == TemporalOp.RELEASESL
                || op == TemporalOp.SINCEL || op == TemporalOp.TRIGGEREDL;
    }

    private static TemporalOp matchingRightBinaryTemporal(TemporalOp op) {
        switch (op) {
            case UNTILL:
                return TemporalOp.UNTILR;
            case RELEASESL:
                return TemporalOp.RELEASESR;
            case SINCEL:
                return TemporalOp.SINCER;
            case TRIGGEREDL:
                return TemporalOp.TRIGGEREDR;
            default:
                return op;
        }
    }

    private static String naturalTemporalLabel(TemporalOp op) {
        switch (op) {
            case UNTILL:
            case UNTILR:
                return "UNTIL";
            case RELEASESL:
            case RELEASESR:
                return "RELEASES";
            case SINCEL:
            case SINCER:
                return "SINCE";
            case TRIGGEREDL:
            case TRIGGEREDR:
                return "TRIGGERED";
            default:
                return op.toString();
        }
    }

    private static int treeDistance(TemporalTree left, TemporalTree right) {
        return OrderedTreeEditDistance.distance(left, right, TEMPORAL_ADAPTER);
    }

    private static int temporalSize(TemporalTree node) {
        is.fivefivefive.CanDis.WorkBudget.charge(1);
        int size = 1;
        for (TemporalTree child : node.children) {
            size += temporalSize(child);
        }
        return size;
    }

    private static void temporalEdits(TemporalTree left, TemporalTree right, String path, List<String> edits) {
        is.fivefivefive.CanDis.WorkBudget.charge(1);
        if (!safeEquals(left.label, right.label)) {
            edits.add(path + ": replace " + left.label + " -> " + right.label);
        }
        int[][] dp = temporalForestDp(left.children, right.children);
        int i = left.children.size();
        int j = right.children.size();
        List<String> reversed = new ArrayList<>();
        while (i > 0 || j > 0) {
            if (i > 0 && dp[i][j] == dp[i - 1][j] + temporalSize(left.children.get(i - 1))) {
                collectDeletedTemporal(left.children.get(i - 1), path + ".child[" + (i - 1) + "]", reversed);
                i--;
            } else if (j > 0 && dp[i][j] == dp[i][j - 1] + temporalSize(right.children.get(j - 1))) {
                collectInsertedTemporal(right.children.get(j - 1), path + ".child[" + j + "]", reversed);
                j--;
            } else {
                temporalEdits(left.children.get(i - 1), right.children.get(j - 1), path + ".child[" + (i - 1) + "]", reversed);
                i--;
                j--;
            }
        }
        appendReverse(reversed, edits);
    }

    private static int[][] temporalForestDp(List<TemporalTree> left, List<TemporalTree> right) {
        int[][] dp = intMatrix(left.size() + 1, right.size() + 1);
        for (int i = 1; i <= left.size(); i++) {
            dp[i][0] = dp[i - 1][0] + temporalSize(left.get(i - 1));
        }
        for (int j = 1; j <= right.size(); j++) {
            dp[0][j] = dp[0][j - 1] + temporalSize(right.get(j - 1));
        }
        for (int i = 1; i <= left.size(); i++) {
            for (int j = 1; j <= right.size(); j++) {
                int delete = dp[i - 1][j] + temporalSize(left.get(i - 1));
                int insert = dp[i][j - 1] + temporalSize(right.get(j - 1));
                int update = dp[i - 1][j - 1] + treeDistance(left.get(i - 1), right.get(j - 1));
                dp[i][j] = Math.min(update, Math.min(delete, insert));
            }
        }
        return dp;
    }

    private static void collectInsertedTemporal(TemporalTree node, String path, List<String> edits) {
        is.fivefivefive.CanDis.WorkBudget.charge(1);
        edits.add(path + ": insert temporal " + node.label);
        for (int i = 0; i < node.children.size(); i++) {
            collectInsertedTemporal(node.children.get(i), path + ".child[" + i + "]", edits);
        }
    }

    private static void collectDeletedTemporal(TemporalTree node, String path, List<String> edits) {
        is.fivefivefive.CanDis.WorkBudget.charge(1);
        edits.add(path + ": delete temporal " + node.label);
        for (int i = 0; i < node.children.size(); i++) {
            collectDeletedTemporal(node.children.get(i), path + ".child[" + i + "]", edits);
        }
    }

    private static String variableName(EGraphNode node) {
        return node.getAlphaName() == null ? node.getSourceName() : node.getAlphaName();
    }

    private static String nodeSummary(EGraphNode node) {
        if (node.getOpcode() == EGraphNode.Opcode.VARIABLE) {
            return node.getOpcode() + "(" + nodeVariableDisplay(node) + ")";
        }
        String opcode = node.isFlexibleArity()
                ? node.getOpcode() + "<" + node.getFlexibleArityKind().name().toLowerCase(java.util.Locale.ROOT) + ">"
                : node.getOpcode().toString();
        String name = firstNonEmpty(node.getAlphaName(), node.getSourceName());
        if (name == null) {
            return opcode;
        }
        return opcode + "(" + name + ")";
    }

    private static String nodeVariableDisplay(EGraphNode node) {
        return display(firstNonEmpty(node.getSourceName(), node.getAlphaName()));
    }

    private static String quantiVarName(QuantiVar qv) {
        return display(firstNonEmpty(qv.getOriginalName(), qv.getName()));
    }

    private static String normalFormPath(NormalForm nf, int index) {
        String label = normalFormLabel(nf);
        if (nf.getTemporalOp() == TemporalOp.NONE && index == 0) {
            return "root normal form";
        }
        return label + " normal form";
    }

    private static String normalFormLabel(NormalForm nf) {
        switch (nf.getTemporalOp()) {
            case UNTILL:
                return "left branch of UNTIL";
            case UNTILR:
                return "right branch of UNTIL";
            case RELEASESL:
                return "left branch of RELEASES";
            case RELEASESR:
                return "right branch of RELEASES";
            case SINCEL:
                return "left branch of SINCE";
            case SINCER:
                return "right branch of SINCE";
            case TRIGGEREDL:
                return "left branch of TRIGGERED";
            case TRIGGEREDR:
                return "right branch of TRIGGERED";
            default:
                return naturalTemporalLabel(nf.getTemporalOp());
        }
    }

    private static String quantifierPrefix(List<QuantiVar> bindings) {
        List<String> parts = new ArrayList<>();
        for (QuantiVar binding : bindings) {
            parts.add(quantifierFormula(binding));
        }
        if (parts.isEmpty()) {
            return "";
        }
        return String.join(" ", parts) + " . ";
    }

    private static String quantifierFormula(QuantiVar qv) {
        String disj = qv.isDisj() ? " disj#" + qv.getDisjointnessClass() : "";
        String cardinality = cardinalityPrefix(qv);
        return qv.getQuantifier() + disj + " " + quantiVarName(qv) + " : "
                + cardinality + display(qv.getTypeName());
    }

    private static String cardinalityPrefix(QuantiVar qv) {
        switch (qv.getCardinality()) {
            case SOME:
                return "some ";
            case ONE:
                return "one ";
            case LONE:
                return "lone ";
            case EXACTLY:
                return "exactly ";
            case SET:
            default:
                return "";
        }
    }

    private static String eGraphFormula(EGraphNode node) {
        if (node == null) {
            return "<empty>";
        }
        is.fivefivefive.CanDis.WorkBudget.charge(1);
        switch (node.getOpcode()) {
            case VARIABLE:
                return nodeVariableDisplay(node);
            case GLOBALBINDING:
            case CONSTANT:
                return display(node.getSourceName());
            case TEMPORALROOT:
                if (node.getChildren().size() == 1) {
                    return eGraphFormula(node.getChildren().get(0));
                }
                return operatorFormula(node);
            case NOT:
            case SOME:
            case NO:
            case LONE:
            case ONE:
            case SETOF:
            case EXACTLY:
            case TRANSPOSE:
            case RCLOSURE:
            case CLOSURE:
            case CARDINALITY:
            case CAST2INT:
            case CAST2SIGINT:
            case PRIME:
            case BEFORE:
            case HISTORICALLY:
            case ONCE:
            case ALWAYS:
            case EVENTUALLY:
            case AFTER:
                return unaryFormula(node);
            case AND:
                return infixFormula(node, "&&");
            case OR:
                return infixFormula(node, "||");
            case IMPLIES:
                return infixFormula(node, "=>");
            case IFF:
                return infixFormula(node, "<=>");
            case EQUALS:
                return infixFormula(node, "=");
            case NOT_EQUALS:
                return infixFormula(node, "!=");
            case IN:
                return infixFormula(node, "in");
            case NOT_IN:
                return infixFormula(node, "!in");
            case GT:
                return infixFormula(node, ">");
            case GTE:
                return infixFormula(node, ">=");
            case LT:
                return infixFormula(node, "<");
            case LTE:
                return infixFormula(node, "<=");
            case JOIN:
                return infixFormula(node, ".");
            case ARROW:
                return infixFormula(node, "->");
            case INTERSECT:
                return infixFormula(node, "&");
            case PLUS:
                return infixFormula(node, "+");
            case PLUSPLUS:
                return infixFormula(node, "++");
            case MINUS:
                return infixFormula(node, "-");
            case UNTIL:
                return infixFormula(node, "until");
            case RELEASES:
                return infixFormula(node, "releases");
            case SINCE:
                return infixFormula(node, "since");
            case TRIGGERED:
                return infixFormula(node, "triggered");
            case ITE:
                return iteFormula(node);
            case CALL:
                CallMetadata.require(node);
                return operatorFormula(node);
            default:
                return operatorFormula(node);
        }
    }

    private static String unaryFormula(EGraphNode node) {
        if (node.getChildren().isEmpty()) {
            return node.getOpcode().toString();
        }
        // ONE/LONE/NO also label residual quantified formulas. Their children
        // include declarations and a body, so unary rendering would hide the
        // body even though the distance and edit paths still traverse it.
        if (node.getChildren().size() != 1) {
            return operatorFormula(node);
        }
        return "(" + node.getOpcode() + " " + eGraphFormula(node.getChildren().get(0)) + ")";
    }

    private static String infixFormula(EGraphNode node, String op) {
        if (node.getChildren().isEmpty()) {
            return node.getOpcode().toString();
        }
        List<String> children = new ArrayList<>();
        for (EGraphNode child : node.getChildren()) {
            children.add(eGraphFormula(child));
        }
        return "(" + String.join(" " + op + " ", children) + ")";
    }

    private static String iteFormula(EGraphNode node) {
        if (node.getChildren().size() != 3) {
            return operatorFormula(node);
        }
        return "(if " + eGraphFormula(node.getChildren().get(0)) + " then "
                + eGraphFormula(node.getChildren().get(1)) + " else "
                + eGraphFormula(node.getChildren().get(2)) + ")";
    }

    private static String operatorFormula(EGraphNode node) {
        List<String> children = new ArrayList<>();
        for (EGraphNode child : node.getChildren()) {
            children.add(eGraphFormula(child));
        }
        String name = firstNonEmpty(node.getSourceName(), node.getOpcode().toString());
        if (children.isEmpty()) {
            return name;
        }
        return name + "(" + String.join(", ", children) + ")";
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static String display(String value) {
        return value == null || value.isEmpty() ? "<unknown>" : value;
    }

    private static void appendReverse(List<String> reversed, List<String> edits) {
        for (int i = reversed.size() - 1; i >= 0; i--) {
            edits.add(reversed.get(i));
        }
    }

    private static String typeKey(QuantiVar qv) {
        String type = qv.getTypeName();
        return type == null ? "" : type;
    }

    private static boolean sameType(String left, String right) {
        return safeEquals(normalizeType(left), normalizeType(right));
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return "";
        }
        return type.startsWith("VAR_") ? type.substring(4) : type;
    }

    private static boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static int[][] intMatrix(int rows, int columns) {
        is.fivefivefive.CanDis.WorkBudget.chargeCells(rows, columns);
        long bytes = alignedArrayBytes(rows, 8) + (long) rows * alignedArrayBytes(columns, 4);
        recordAllocation(bytes, rows + 1L, true);
        return new int[rows][columns];
    }

    private static int[] intArray(int length) {
        is.fivefivefive.CanDis.WorkBudget.charge(length);
        recordAllocation(alignedArrayBytes(length, 4), 1, false);
        return new int[length];
    }

    private static boolean[] booleanArray(int length) {
        is.fivefivefive.CanDis.WorkBudget.charge(length);
        recordAllocation(alignedArrayBytes(length, 1), 1, false);
        return new boolean[length];
    }

    private static long alignedArrayBytes(int length, int elementBytes) {
        long bytes = 16L + (long) Math.max(0, length) * elementBytes;
        return (bytes + 7L) & ~7L;
    }

    private static void recordAllocation(long bytes, long arrays, boolean matrix) {
        MutableAllocationStats tracker = ALLOCATION_TRACKER.get();
        if (tracker == null) {
            return;
        }
        tracker.bytes += bytes;
        tracker.peakBytes = Math.max(tracker.peakBytes, bytes);
        tracker.arrays += arrays;
        if (matrix) {
            tracker.matrices++;
        }
    }

    public static final class AllocationStats {
        private final long estimatedBytes;
        private final long largestBufferBytes;
        private final long arrayCount;
        private final long matrixCount;

        private AllocationStats(long estimatedBytes, long largestBufferBytes, long arrayCount, long matrixCount) {
            this.estimatedBytes = estimatedBytes;
            this.largestBufferBytes = largestBufferBytes;
            this.arrayCount = arrayCount;
            this.matrixCount = matrixCount;
        }

        public long estimatedBytes() {
            return estimatedBytes;
        }

        public long largestBufferBytes() {
            return largestBufferBytes;
        }

        public long arrayCount() {
            return arrayCount;
        }

        public long matrixCount() {
            return matrixCount;
        }
    }

    private static final class MutableAllocationStats {
        private long bytes;
        private long peakBytes;
        private long arrays;
        private long matrices;
    }

    private enum BindingRole {
        PARAMETER,
        MATRIX,
        INHERITED,
        LOCAL
    }

    private static final class BindingDescriptor {
        private final QuantiVar variable;
        private final BindingRole role;
        private final int ordinal;

        private BindingDescriptor(QuantiVar variable, BindingRole role, int ordinal) {
            this.variable = variable;
            this.role = role;
            this.ordinal = ordinal;
        }

        private boolean compatibleWith(BindingDescriptor other) {
            if (other == null || role != other.role
                    || variable.getQuantifier() != other.variable.getQuantifier()
                    || variable.getCardinality() != other.variable.getCardinality()
                    || variable.getDisjointnessClass() != other.variable.getDisjointnessClass()
                    || !sameType(typeKey(variable), typeKey(other.variable))) {
                return false;
            }
            if (role == BindingRole.PARAMETER) {
                return ordinal == other.ordinal;
            }
            if (variable.getQuantifier() == QuantiVar.Quantifier.COMPREHENSION
                    || variable.getQuantifier() == QuantiVar.Quantifier.SUM) {
                return safeEquals(variable.getDeBruijnKey(), other.variable.getDeBruijnKey());
            }
            if (variable.getQuantifier() == QuantiVar.Quantifier.ALL
                    || variable.getQuantifier() == QuantiVar.Quantifier.SOME) {
                return true;
            }
            return safeEquals(variable.getBindingPath(), other.variable.getBindingPath());
        }

        private int ownershipPriority() {
            switch (role) {
                case PARAMETER:
                    return 0;
                case MATRIX:
                    return 1;
                case LOCAL:
                    return 2;
                case INHERITED:
                default:
                    return 3;
            }
        }
    }

    private static final class CoherentVariable {
        private BindingDescriptor descriptor;
        private final Set<Integer> phases = new HashSet<>();
        private int ownerPhase = Integer.MAX_VALUE;
        private boolean ambiguous;

        private CoherentVariable(BindingDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        private void observe(BindingDescriptor observed, int phase) {
            observeOwner(observed, phase);
            phases.add(phase);
        }

        private void observeOwner(BindingDescriptor observed, int phase) {
            if (descriptor.variable != observed.variable) {
                ambiguous = true;
                return;
            }
            if (observed.ownershipPriority() < descriptor.ownershipPriority()) {
                descriptor = observed;
                ownerPhase = phase;
            } else if (observed.ownershipPriority()
                    == descriptor.ownershipPriority()) {
                ownerPhase = Math.min(ownerPhase, phase);
            }
        }

        private boolean compatibleWith(CoherentVariable other) {
            return other != null
                    && !ambiguous
                    && !other.ambiguous
                    && ownerPhase == other.ownerPhase
                    && descriptor.compatibleWith(other.descriptor);
        }

        private boolean isCoherent() {
            return phases.size() > 1;
        }
    }

    private static final class CoherentVariableSpace {
        private final Map<String, CoherentVariable> variables;

        private CoherentVariableSpace(Map<String, CoherentVariable> variables) {
            this.variables = variables;
        }

        private Set<String> coherentNames() {
            Set<String> names = new HashSet<>();
            for (Map.Entry<String, CoherentVariable> entry : variables.entrySet()) {
                if (entry.getValue().isCoherent()) {
                    names.add(entry.getKey());
                }
            }
            return names;
        }
    }

    private static final class BestCoherentMapping {
        private List<PhaseDecision> phases;
        private int distance = Integer.MAX_VALUE;
        private Map<String, String> mapping = new HashMap<>();
    }

    private static final class MatrixAlignment {
        private List<PhaseDecision> phases;
        private Map<String,String> paidCorrespondence;
        private int[] sourceOrder,targetOrder;
        private List<is.fivefivefive.CanDis.metric.DeclarationReplay.Move> declarationPath;
        private final int distance;
        private final Map<String, String> mapping;
        private final Set<String> lockedLeftNames;
        private final Set<String> lockedRightNames;

        private MatrixAlignment(
                int distance,
                Map<String, String> mapping,
                Set<String> lockedLeftNames,
                Set<String> lockedRightNames) {
            this.distance = distance;
            this.mapping = new HashMap<>(mapping);
            this.lockedLeftNames = new HashSet<>(lockedLeftNames);
            this.lockedRightNames = new HashSet<>(lockedRightNames);
        }

        private static MatrixAlignment empty() {
            return new MatrixAlignment(
                    0,
                    java.util.Collections.emptyMap(),
                    java.util.Collections.emptySet(),
                    java.util.Collections.emptySet());
        }
    }

    private static class BestMapping {
        private MatrixDecision<EGraphNode> decision;
        private int distance = Integer.MAX_VALUE;
        private Map<String, String> mapping = new HashMap<>();
    }

    private static class TemporalTree {
        private String label;
        private List<TemporalTree> children;

        private TemporalTree(String label) {
            this.label = label;
            this.children = new ArrayList<>();
        }
    }
}
