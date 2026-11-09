package is.fivefivefive.CanDis.metric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import is.fivefivefive.CanDis.core.OrderedTreeEditDistance;
import is.fivefivefive.CanDis.metric.RepairView.Binding;
import is.fivefivefive.CanDis.metric.RepairView.BindingRole;
import is.fivefivefive.CanDis.metric.RepairView.Declaration;
import is.fivefivefive.CanDis.metric.RepairView.Node;
import is.fivefivefive.CanDis.metric.RepairView.Phase;
import is.fivefivefive.CanDis.metric.RepairView.TemporalNode;

/**
 * The established {@code CanonicalDistance} repair geometry evaluated over a
 * certificate-producing {@link RepairView}. Its decomposition and edit units
 * are metric semantics, while Layer 1 supplies admissible scope symmetries.
 * The public evaluator accepts only sealed certified projections and checks
 * in-process producer consistency. It does not grant independent replay
 * authority.
 */
public final class QuotientRepairDistance {
    public static final String VERSION = "certified-fast-rewrite-repair-distance-v25-retained-phase-representation";
    private static final long DEFAULT_MAX_QUANTIFIER_ALIGNMENTS = 1_000_000L;
    private static final long DEFAULT_MAX_SCOPE_ALIGNMENTS = 1_000_000L;
    private static final long DEFAULT_MAX_ALPHA_ALIGNMENTS = 10_000_000L;
    private static final ThreadLocal<MutableStats> DECISIONS=new ThreadLocal<>();

    private QuotientRepairDistance() {
    }

    private static final OrderedTreeEditDistance.Adapter<TemporalNode> TEMPORAL_ADAPTER =
            new OrderedTreeEditDistance.Adapter<>() {
                @Override
                public String label(TemporalNode node) {
                    return node.label();
                }

                @Override
                public List<? extends TemporalNode> children(TemporalNode node) {
                    return node.children();
                }
            };

    public static int distance(RepairView left, RepairView right) {
        return evaluate(left, right).distance();
    }

    /** Diagnostic only: native selected matrix edits, with no complete-plan authority. */
    public static org.json.JSONObject matrixReplay(RepairView left, RepairView right) {
        return captureDecisions(left,right).matrix;
    }
    private record Captured(org.json.JSONObject matrix,MutableStats decisions) { }
    private static Captured captureDecisions(RepairView left,RepairView right) {
        if(DECISIONS.get()!=null)throw new IllegalStateException("Reentrant native decision capture");
        MutableStats stats=new MutableStats();stats.capture=true;DECISIONS.set(stats);
        Result nativeResult;
        try { nativeResult=evaluate(left,right); } finally { DECISIONS.remove(); }
        int aligned = Math.min(left.phases().size(),right.phases().size());
        int phaseCount = Math.max(left.phases().size(),right.phases().size());
        int cost = 0;
        org.json.JSONArray phases = new org.json.JSONArray();
        for (int i = 0; i < phaseCount; i++) {
            if (i < aligned && stats.selectedMapping == null)
                throw new IllegalStateException("MATRIX_REPLAY_MISSING_CHOICE");
            int[] mapping = i < aligned
                    ? phaseMapping(stats.selectedLeft, stats.selectedRight, stats.selectedMapping, i) : new int[0];
            MatrixReplay<Node> replay=matrixReplayEngine(mapping,mapping,false,stats.temporalPayments,i);
            Node a = i < left.phases().size() ? left.phases().get(i).matrix() : null;
            Node b = i < right.phases().size() ? right.phases().get(i).matrix() : null;
            MatrixDecision<Node> chosen;
            if(i<aligned)chosen=stats.selectedPhases.get(i);
            else chosen=stats.unaligned.get(i);
            org.json.JSONObject receipt = replay.capture(chosen);
            cost = Math.addExact(cost, replay.verify(a, b, receipt));
            phases.put(new org.json.JSONObject().put("phase", i)
                    .put("alphaMapping", new org.json.JSONArray(mapping)).put("receipt", receipt));
        }
        if (cost != nativeResult.matrixDistance())
            throw new IllegalStateException("MATRIX_REPLAY_NATIVE_COST_MISMATCH");
        var result=new org.json.JSONObject().put("arm", "certified").put("distance", nativeResult.distance())
                .put("metricVersion",VERSION).put("exactForStoredOrbits",nativeResult.exactForStoredOrbits())
                .put("binderAlignments",nativeResult.binderAlignments()).put("kernelAuthority",nativeResult.kernelAuthority())
                .put("temporalDistance", nativeResult.temporalDistance())
                .put("quantifierDistance", nativeResult.quantifierDistance())
                .put("matrixDistance", nativeResult.matrixDistance()).put("replayedMatrixCost", cost)
                .put("matrixReplayChecked", true).put("complete", false).put("phases", phases)
                .put("selectedTemporalReferencePayments",stats.temporalPayments.json())
                .put("selectedGlobalMapping", new org.json.JSONArray(stats.selectedMapping == null ? new int[0] : stats.selectedMapping))
                .put("selectedQuantifierCorrespondence", stats.selectedCorrespondence == null
                        ? new org.json.JSONObject() : new org.json.JSONObject(stats.selectedCorrespondence))
                .put("selectedDeclarationPermutation",new org.json.JSONObject()
                        .put("left",new org.json.JSONArray(stats.selectedDeclarationLeft == null ? new int[0] : stats.selectedDeclarationLeft))
                        .put("right",new org.json.JSONArray(stats.selectedDeclarationRight == null ? new int[0] : stats.selectedDeclarationRight)));
        return new Captured(result,stats);
    }

    /** Fixed-choice diagnostic verifier; neither chooses a mapping nor grants admission. */
    public static int[] projectFixedMatrixMapping(RepairView left,RepairView right,int[] global) {
        if(left.phases().size()!=1 || right.phases().size()!=1)
            throw new IllegalArgumentException("Single-phase fixed replay required");
        var a=GlobalBindingIndex.create(left.phases(),1);
        var b=GlobalBindingIndex.create(right.phases(),1);
        if(global.length!=a.bindings.size())throw new IllegalArgumentException("Global map coverage");
        var used=new HashSet<Integer>();
        for(int j:global)if(j < -1 || j >= b.bindings.size() || j>=0 && !used.add(j))
            throw new IllegalArgumentException("Foreign or noninjective global mapping");
        return phaseMapping(a,b,global,0);
    }

    /** Fixed-choice diagnostic verifier; neither chooses a mapping nor grants admission. */
    public static int verifyFixedMatrixReplay(RepairView left,RepairView right,int[] labels,int[] completed,
            org.json.JSONObject receipt) {
        if(left.phases().size()!=1 || right.phases().size()!=1)
            throw new IllegalArgumentException("Single-phase fixed replay required");
        return matrixReplayEngine(labels,completed,false).verify(left.phases().get(0).matrix(),right.phases().get(0).matrix(),receipt);
    }

    private static MatrixReplay<Node> matrixReplayEngine(int[] labels,int[] costs,boolean search) {
        return matrixReplayEngine(labels,costs,search,SelectedTemporalPayments.NONE,-1);
    }
    private static MatrixReplay<Node> matrixReplayEngine(int[] labels,int[] costs,boolean search,
            SelectedTemporalPayments payments,int phase) {
        var context=new MatrixDistanceContext(costs,false,payments,phase);
        return new MatrixReplay<>(new MatrixReplay.NativeCost<Node>() {
            public String label(Node n) {
                return new org.json.JSONArray().put(n.operator()).put(n.semanticPayload()).put(n.bindingIndex())
                        .put(String.valueOf(n.lexicalVariable())).put(n.containerKind().toString())
                        .put(n.orderInsensitive()).put(Arrays.toString(labels)).toString();
            }
            public String evidence(Node n) {
                return new org.json.JSONObject().put("observationType",String.valueOf(n.observationType()))
                        .put("observationPhases",new org.json.JSONArray(n.observationPhases()))
                        .put("observationTemporalOperator",String.valueOf(n.observationTemporalOperator()))
                        .put("bindingIndex",n.bindingIndex()).put("lexicalVariable",String.valueOf(n.lexicalVariable()))
                        .put("payload",String.valueOf(n.payload())).put("semanticPayload",String.valueOf(n.semanticPayload())).toString();
            }
            public Object sourceObjectEvidence(Node n) { return payments.referenceEvidence(phase,n); }
            public List<Node> children(Node n) { return n.children(); }
            public List<Node> alternatives(Node n) { return n.certifiedAlternatives(); }
            public boolean unordered(Node a,Node b) { return a.operator().equals(b.operator()) && a.orderInsensitive() && b.orderInsensitive(); }
            public int size(Node n) { return n.size(); }
            public int update(Node a,Node b) { return context.updateCost(a,b); }
            public int distance(Node a,Node b) {
                if(!search) throw new IllegalStateException("Completion replay cannot search");
                return context.distance(a,b);
            }
        });
    }

    /** Keeps native matrix choices and backtraces compatible minimum declaration paths. */
    public static org.json.JSONObject replayWithSlices(RepairView left, RepairView right) {
        return evaluateWithTrace(left,right).json();
    }

    /** Executes the selected native metric representation, independently of cross-arm bridges. */
    public static NativeRepairTrace evaluateWithTrace(RepairView left,RepairView right) {
        var captured=captureDecisions(left,right);var out=captured.matrix;var stats=captured.decisions;
        try {
        var as=new ArrayList<List<DeclarationReplay.Atom>>();var bs=new ArrayList<List<DeclarationReplay.Atom>>();
        var declarations=new org.json.JSONArray();
        int aligned=Math.min(left.phases().size(),right.phases().size());
        int count=Math.max(left.phases().size(),right.phases().size());
        for(int index=0;index<stats.quantification.components.size();index++) {
            var component=stats.quantification.components.get(index);
            var a=component.left;var b=component.right;
            if(stats.selectedLeft!=null){
                a=permuteDeclarations(a,stats.selectedLeft,stats.selectedDeclarationLeft);
                b=permuteDeclarations(b,stats.selectedRight,stats.selectedDeclarationRight);
            }
            as.add(List.copyOf(declarationAtoms(a)));bs.add(List.copyOf(declarationAtoms(b)));
            var moves=stats.selectedDeclarationPaths==null
                    ?DeclarationReplay.selectedMoves(component.distance,(i,j)->QuantifierEditComponent.tupleUpdateCost(component.left.get(i),component.right.get(j)))
                    :stats.selectedDeclarationPaths.get(index);
            declarations.put(DeclarationReplay.captureSelected(as.get(index),bs.get(index),moves,component.cost()));
        }
        for(int phase=aligned;phase<count;phase++) {
            var a=phase<left.phases().size()?QuantificationAlignmentSpace.quantifiedDeclarations(left.phases().get(phase),phase):List.<IndexedDeclaration>of();
            var b=phase<right.phases().size()?QuantificationAlignmentSpace.quantifiedDeclarations(right.phases().get(phase),phase):List.<IndexedDeclaration>of();
            as.add(List.copyOf(declarationAtoms(a)));bs.add(List.copyOf(declarationAtoms(b)));
            var moves=new ArrayList<DeclarationReplay.Move>();
            for(int i=0;i<a.size();i++)moves.add(new DeclarationReplay.Move(i,-1));
            for(int j=0;j<b.size();j++)moves.add(new DeclarationReplay.Move(-1,j));
            declarations.put(DeclarationReplay.captureSelected(as.get(as.size()-1),bs.get(bs.size()-1),moves,Math.addExact(a.size(),b.size())));
        }
        for(int phase=0;phase<count;phase++) {
            int[] mapping=phase<aligned?phaseMapping(stats.selectedLeft,stats.selectedRight,stats.selectedMapping,phase):new int[0];
            var choice=phase<aligned?stats.selectedPhases.get(phase):stats.unaligned.get(phase);
            var payload=out.getJSONArray("phases").getJSONObject(phase);
            payload.put("program",matrixReplayEngine(mapping,mapping,false,stats.temporalPayments,phase).executable(choice.source(),choice.target(),payload.getJSONObject("receipt")));
        }
        out.put("nativeDeclarationPrograms",declarations).put("nativeTemporalProgram",TemporalReplay.capture(
                left.temporalRoot(),right.temporalRoot(),out.getInt("temporalDistance"),TemporalNode::label,TemporalNode::children,stats.temporal))
                .put("sourceProducerObservationDigest",left.producerObservationDigest())
                .put("targetProducerObservationDigest",right.producerObservationDigest())
                .put("executionRepresentation","Certified projected temporal tree, ordered declaration tuples and selected matrix representatives")
                .put("jointBindingCorrespondenceChecked",false)
                .put("sourceBindingContexts",traceBindingContexts(left)).put("targetBindingContexts",traceBindingContexts(right))
                .put("constructionLink","Retained sealed RepairViews and producer-observation digests; construction evidence is not an edit");
        PhaseReplay.attach(out,()->declarationReplay(left,right,out,stats),
                ()->new org.json.JSONObject(out.getJSONObject("nativeTemporalProgram").toString()));
        var phaseSteps=new org.json.JSONArray();
        var sourceOrder=left.phaseRepresentation("source");var targetOrder=right.phaseRepresentation("target");
        if(sourceOrder!=null)phaseSteps.put(sourceOrder);if(targetOrder!=null)phaseSteps.put(targetOrder);
        if(!phaseSteps.isEmpty())out.put("nativePhaseRepresentationSteps",phaseSteps);
        return NativeRepairTrace.checked(out,program->{
            if(!phaseSteps.isEmpty()&&!phaseSteps.similar(program.getJSONArray("nativePhaseRepresentationSteps")))
                throw new IllegalArgumentException("Changed certified original/projection phase correspondence");
            int temporal=TemporalReplay.verify(left.temporalRoot(),right.temporalRoot(),TemporalNode::label,TemporalNode::children,
                    program.getJSONObject("nativeTemporalProgram"));
            stats.temporalPayments.requireReceipt(program.getJSONObject("nativeTemporalProgram"),
                    program.getJSONObject("selectedTemporalReferencePayments"));
            if(program.getJSONArray("nativeDeclarationPrograms").length()!=as.size()||program.getJSONArray("phases").length()!=count)
                throw new IllegalArgumentException("Native trace phase coverage");
            int quantifiers=0,matrix=0;
            for(int index=0;index<as.size();index++)quantifiers=Math.addExact(quantifiers,DeclarationReplay.execute(
                    as.get(index),bs.get(index),program.getJSONArray("nativeDeclarationPrograms").getJSONObject(index)));
            for(int phase=0;phase<count;phase++) {
                int[] mapping=phase<aligned?phaseMapping(stats.selectedLeft,stats.selectedRight,stats.selectedMapping,phase):new int[0];
                var choice=phase<aligned?stats.selectedPhases.get(phase):stats.unaligned.get(phase);
                var payload=program.getJSONArray("phases").getJSONObject(phase);
                matrix=Math.addExact(matrix,matrixReplayEngine(mapping,mapping,false,stats.temporalPayments,phase).verifyExecutable(choice.source(),choice.target(),
                        payload.getJSONObject("receipt"),payload.getJSONObject("program")));
            }
            if(temporal!=program.getInt("temporalDistance")||quantifiers!=program.getInt("quantifierDistance")||matrix!=program.getInt("matrixDistance"))
                throw new IllegalArgumentException("Native trace component costs");
            return Math.addExact(temporal,Math.addExact(quantifiers,matrix));
        });
        } catch(RuntimeException failure) {
            throw NativeRepairTrace.captureFailure(out,failure);
        }
    }

    private static org.json.JSONArray traceBindingContexts(RepairView view) {
        var phases=new org.json.JSONArray();
        for(int phase=0;phase<view.phases().size();phase++) {
            var rows=new org.json.JSONArray();int index=0;
            for(var b:view.phases().get(phase).bindings()) {
                var d=b.declaration();rows.put(new org.json.JSONObject().put("vectorIndex",index++).put("coordinate",b.coordinate())
                        .put("role",b.role()).put("ordinal",b.ordinal()).put("ownerPhase",b.ownerPhase()).put("ownerContext",b.ownerContext())
                        .put("bindingPath",b.bindingPath()).put("certifiedOrbit",new org.json.JSONArray(b.certifiedOrbit()))
                        .put("quantifier",d.quantifier()).put("type",String.valueOf(d.observationType()))
                        .put("certifiedDomain",d.certifiedDomain()).put("dependencies",new org.json.JSONArray(d.dependencies()))
                        .put("cardinality",d.cardinality()).put("disjointnessClass",d.disjointnessClass()));
            }
            phases.put(new org.json.JSONObject().put("phase",phase).put("bindings",rows));
        }
        return phases;
    }

    private static org.json.JSONObject declarationReplay(RepairView left,RepairView right,
            org.json.JSONObject matrix,MutableStats decisions) {
        DeclarationReplay.requireBounded(QuantificationAlignmentSpace.parameterDeclarations(left.phases()).size(),
                QuantificationAlignmentSpace.parameterDeclarations(right.phases()).size());
        int aligned = Math.min(left.phases().size(),right.phases().size());
        for (int i = 0; i < Math.max(left.phases().size(),right.phases().size()); i++) DeclarationReplay.requireBounded(
                i < left.phases().size() ? left.phases().get(i).quantifiers().size() : 0,
                i < right.phases().size() ? right.phases().get(i).quantifiers().size() : 0);
        QuantificationAlignmentSpace space = decisions.quantification;
        GlobalBindingIndex a = GlobalBindingIndex.create(left.phases(),aligned);
        GlobalBindingIndex b = GlobalBindingIndex.create(right.phases(),aligned);
        org.json.JSONObject permutation = matrix.getJSONObject("selectedDeclarationPermutation");
        int[] leftPermutation = declarationPermutation(a,permutation.getJSONArray("left"));
        int[] rightPermutation = declarationPermutation(b,permutation.getJSONArray("right"));
        int[] effectiveLeft = leftPermutation.clone();
        int[] effectiveRight = rightPermutation.clone();
        Map<Integer,Integer> selected = new LinkedHashMap<>(), actual = new LinkedHashMap<>();
        org.json.JSONObject chosen = matrix.getJSONObject("selectedQuantifierCorrespondence");
        for (String key : chosen.keySet()) addInjectiveCorrespondence(selected,Integer.parseInt(key),
                DeclarationReplay.integer(chosen,key));
        Map<String,String> full = new LinkedHashMap<>();
        org.json.JSONArray global = matrix.getJSONArray("selectedGlobalMapping");
        DeclarationReplay.require(global.length() == a.bindings.size(),"Selected matrix mapping size");
        int[] mapping = new int[global.length()];
        for (int i = 0; i < mapping.length; i++) {
            int j = DeclarationReplay.integer(new org.json.JSONObject().put("index",global.get(i)),"index");
            DeclarationReplay.require(j >= -1 && j < b.bindings.size(),"Selected matrix coordinate");
            mapping[i] = j;
            if (j >= 0) full.put(Integer.toString(i),Integer.toString(j));
        }
        for (var e : selected.entrySet()) DeclarationReplay.require(
                Objects.equals(full.get(e.getKey().toString()),e.getValue().toString()),"Paid/matrix link conflict");
        for (int phase = 0; phase < aligned; phase++) DeclarationReplay.require(
                new org.json.JSONArray(phaseMapping(a,b,mapping,phase)).similar(matrix.getJSONArray("phases")
                        .getJSONObject(phase).getJSONArray("alphaMapping")),"Phase/global matrix mapping conflict");
        Set<String> observedLeft = a.used.stream().map(Object::toString).collect(java.util.stream.Collectors.toSet());
        Set<String> observedRight = b.used.stream().map(Object::toString).collect(java.util.stream.Collectors.toSet());
        boolean completeAbsent=closedPrefix(left) && closedPrefix(right);
        org.json.JSONArray components = new org.json.JSONArray();
        int cost = 0;
        for (int index = 0; index < space.components.size(); index++) {
            QuantifierEditComponent original = space.components.get(index);
            QuantifierEditComponent component = new QuantifierEditComponent(
                    permuteDeclarations(original.left,a,leftPermutation),
                    permuteDeclarations(original.right,b,rightPermutation),original.distance);
            org.json.JSONObject receipt, connecting=null;
            try {
                receipt=captureDeclarations(component,a,b,selected,full,observedLeft,observedRight,completeAbsent);
            } catch(DeclarationReplay.NoMatchingPath missing) {
                if(a.phases.size()!=1 || b.phases.size()!=1) throw missing;
                var source=component.left;
                var joint=declarationLink(component,a,b,full,observedLeft,observedRight,false);
                int[] order=joint.connectingPermutation(declarationAtoms(source),(i,j)->
                        CertifiedDeclarationOrbit.exchangeable(a.binding(a.indices.get(source.get(i).identity)).binding,
                                a.binding(a.indices.get(source.get(j).identity)).binding));
                var reordered=DeclarationMatrixLink.permute(source,order);
                for(int i=0;i<original.left.size();i++) effectiveLeft[a.indices.get(original.left.get(i).identity)]=
                        a.indices.get(reordered.get(i).identity);
                CertifiedDeclarationOrbit.requirePermutation(orbitBindings(a),effectiveLeft);
                component=new QuantifierEditComponent(reordered,component.right,component.distance);
                try {
                    receipt=captureDeclarations(component,a,b,selected,full,observedLeft,observedRight,completeAbsent);
                    connecting=new org.json.JSONObject().put("source",new org.json.JSONArray(order))
                            .put("target",new org.json.JSONArray(CertifiedDeclarationOrbit.identity(component.right.size())))
                            .put("admission","SOURCE_CERTIFIED_FULL_SYMMETRIC_ORBIT").put("cost",0);
                } catch(DeclarationReplay.NoMatchingPath stillMissing) {
                    var presentation=prefixPresentation(component,a,b,selected,full,observedLeft,observedRight,completeAbsent);
                    if(presentation==null) throw stillMissing;
                    component=presentation.component(); receipt=presentation.receipt(); connecting=presentation.evidence();
                    for(int i=0;i<original.left.size();i++) effectiveLeft[a.indices.get(original.left.get(i).identity)]=
                            a.indices.get(component.left.get(i).identity);
                    for(int i=0;i<original.right.size();i++) effectiveRight[b.indices.get(original.right.get(i).identity)]=
                            b.indices.get(component.right.get(i).identity);
                }
            }
            if(receipt.has("matrixCorrespondenceCompletion")) {
                var completion=receipt.getJSONObject("matrixCorrespondenceCompletion");
                var raw=completion.getJSONObject("completedMapping"); int[] completed=mapping.clone();
                for(String key:raw.keySet()) completed[Integer.parseInt(key)]=Integer.parseInt(raw.getString(key));
                var originalReceipt=matrix.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt");
                int replayed=matrixReplayEngine(phaseMapping(a,b,mapping,0),phaseMapping(a,b,completed,0),false,decisions.temporalPayments,0)
                        .verify(left.phases().get(0).matrix(),right.phases().get(0).matrix(),originalReceipt);
                DeclarationReplay.require(replayed==originalReceipt.getInt("cost"),"Completion matrix cost changed");
                completion.put("matrixActionsUnchanged",true).put("replayedMatrixCost",replayed);
            }
            cost = Math.addExact(cost,receipt.getInt("replayedCost"));
            org.json.JSONArray ops = receipt.getJSONArray("operations");
            for (int k = 0; k < ops.length(); k++) {
                org.json.JSONObject op = ops.getJSONObject(k);
                int i = op.getInt("leftIndex"), j = op.getInt("rightIndex");
                if (i >= 0 && j >= 0 && (op.getInt("cost") != 0 || component.left.get(i).identity.parameter))
                    addInjectiveCorrespondence(actual,Objects.requireNonNull(a.indices.get(component.left.get(i).identity)),
                            Objects.requireNonNull(b.indices.get(component.right.get(j).identity)));
            }
            var emitted=new org.json.JSONObject().put("phase",index-1)
                    .put("component",index == 0 ? "parameters" : "quantifiers").put("receipt",receipt);
            int[] sourceOrder=declarationOrder(original.left,component.left);
            int[] targetOrder=declarationOrder(original.right,component.right);
            if((connecting==null || !connecting.has("sourcePresentation"))
                    && (!Arrays.equals(sourceOrder,CertifiedDeclarationOrbit.identity(sourceOrder.length))
                    || !Arrays.equals(targetOrder,CertifiedDeclarationOrbit.identity(targetOrder.length))))
                connecting=new org.json.JSONObject().put("source",new org.json.JSONArray(sourceOrder))
                        .put("target",new org.json.JSONArray(targetOrder))
                        .put("admission","SOURCE_CERTIFIED_FULL_SYMMETRIC_ORBIT").put("cost",0);
            if(connecting!=null) emitted.put("connectingPermutation",connecting);
            components.put(emitted);
        }
        for (int phase = aligned; phase < Math.max(left.phases().size(),right.phases().size()); phase++) {
            List<IndexedDeclaration> ls = phase < left.phases().size()
                    ? QuantificationAlignmentSpace.quantifiedDeclarations(left.phases().get(phase),phase) : List.of();
            List<IndexedDeclaration> rs = phase < right.phases().size()
                    ? QuantificationAlignmentSpace.quantifiedDeclarations(right.phases().get(phase),phase) : List.of();
            QuantifierEditComponent component = QuantifierEditComponent.create(ls,rs,true);
            var as = declarationAtoms(component.left);
            var bs = declarationAtoms(component.right);
            org.json.JSONObject receipt = DeclarationReplay.capture(as,bs,component.distance,(i,j) -> true);
            cost = Math.addExact(cost,DeclarationReplay.verify(as,bs,receipt));
            components.put(new org.json.JSONObject().put("phase",phase).put("component","quantifiers")
                    .put("receipt",receipt));
        }
        DeclarationReplay.require(selected.equals(actual),"Selected paid/parameter correspondence mismatch");
        DeclarationReplay.require(cost == space.cost && cost == matrix.getInt("quantifierDistance"),"Native declaration cost");
        return new org.json.JSONObject().put("components",components).put("cost",cost).put("replayedCost",cost)
                .put("sliceReplayChecked",true).put("complete",false).put("selectedPaidCorrespondenceChecked",true)
                .put("selectedPaidCorrespondence",new org.json.JSONObject(actual))
                .put("selectedMatrixDeclarationLinksChecked",true)
                .put("jointLinkScope","Original declaration coordinates under selected map plus explicit absence completion; local binders excluded")
                .put("selectedDeclarationPermutation",new org.json.JSONObject()
                        .put("left",new org.json.JSONArray(effectiveLeft)).put("right",new org.json.JSONArray(effectiveRight)))
                .put("orderingAdmissionChecked",false).put("matrixChoiceAdmissionChecked",false);
    }

    private static DeclarationMatrixLink declarationLink(QuantifierEditComponent component,GlobalBindingIndex a,
            GlobalBindingIndex b,Map<String,String> full,Set<String> observedLeft,Set<String> observedRight,boolean completeAbsent) {
        return new DeclarationMatrixLink(
                component.left.stream().map(d -> Objects.requireNonNull(a.indices.get(d.identity)).toString()).toList(),
                component.right.stream().map(d -> Objects.requireNonNull(b.indices.get(d.identity)).toString()).toList(),
                full,observedLeft,observedRight,completeAbsent);
    }

    private record PresentationReplay(QuantifierEditComponent component,org.json.JSONObject receipt,
            org.json.JSONObject evidence) { }

    private static PresentationReplay prefixPresentation(QuantifierEditComponent original,GlobalBindingIndex a,
            GlobalBindingIndex b,Map<Integer,Integer> selected,Map<String,String> full,
            Set<String> observedLeft,Set<String> observedRight,boolean completeAbsent) {
        var ab=original.left.stream().map(d->a.binding(a.indices.get(d.identity)).binding).toList();
        var bb=original.right.stream().map(d->b.binding(b.indices.get(d.identity)).binding).toList();
        var ai=original.left.stream().map(d->a.indices.get(d.identity).toString()).toList();
        var bi=original.right.stream().map(d->b.indices.get(d.identity).toString()).toList();
        int[] ap=DeclarationPresentation.order(ab,ai,bi,full);
        int[] bp=DeclarationPresentation.order(bb,bi,ai,DeclarationPresentation.inverse(full));
        for(boolean sourceSide:new boolean[]{true,false}) {
            int[] p=sourceSide?ap:CertifiedDeclarationOrbit.identity(ai.size());
            int[] q=sourceSide?CertifiedDeclarationOrbit.identity(bi.size()):bp;
            var x=DeclarationMatrixLink.permute(original.left,p); var y=DeclarationMatrixLink.permute(original.right,q);
            var component=QuantifierEditComponent.create(x,y,false);
            if(component.cost()!=original.cost()) continue;
            try {
                var receipt=captureDeclarations(component,a,b,selected,full,observedLeft,observedRight,completeAbsent);
                var evidence=new org.json.JSONObject().put("source",new org.json.JSONArray(p)).put("target",new org.json.JSONArray(q))
                        .put("sourcePresentation",DeclarationPresentation.evidence(ab,ai,p))
                        .put("targetPresentation",DeclarationPresentation.evidence(bb,bi,q))
                        .put("admission","INDEPENDENT_TYPED_PREFIX_PRESENTATION").put("cost",0);
                return new PresentationReplay(component,receipt,evidence);
            } catch(DeclarationReplay.NoMatchingPath missing) {
                // The fixed selected map and paid path remain mandatory.
            }
        }
        return null;
    }

    private static int[] declarationOrder(List<IndexedDeclaration> original,List<IndexedDeclaration> ordered) {
        int[] result=new int[ordered.size()];
        for(int i=0;i<result.length;i++) {
            result[i]=-1;
            for(int j=0;j<original.size();j++) if(original.get(j).identity.equals(ordered.get(i).identity)) {
                result[i]=j; break;
            }
            DeclarationReplay.require(result[i]>=0,"Declaration escaped original component");
        }
        return result;
    }

    private static org.json.JSONObject captureDeclarations(QuantifierEditComponent component,GlobalBindingIndex a,
            GlobalBindingIndex b,Map<Integer,Integer> selected,Map<String,String> full,
            Set<String> observedLeft,Set<String> observedRight,boolean completeAbsent) {
        var as=declarationAtoms(component.left); var bs=declarationAtoms(component.right);
        var joint=declarationLink(component,a,b,full,observedLeft,observedRight,completeAbsent);
        var receipt=DeclarationReplay.capture(as,bs,component.distance,(i,j)->{
            if(!joint.allows(i,j,i>=0 && j>=0?QuantifierEditComponent.tupleUpdateCost(component.left.get(i),component.right.get(j)):-1)) return false;
            Integer ai=i<0?null:a.indices.get(component.left.get(i).identity);
            Integer bj=j<0?null:b.indices.get(component.right.get(j).identity);
            if(i<0) return !selected.containsValue(bj);
            if(j<0) return !selected.containsKey(ai);
            if(completeAbsent && !full.containsKey(ai.toString())) {
                var x=component.left.get(i).declaration; var y=component.right.get(j).declaration;
                if(!x.sameCertifiedPayload(y) || x.observationType()==null || x.observationDomain()==null
                        || !x.observationType().equals(y.observationType()) || !x.observationDomain().equals(y.observationDomain())) return false;
            }
            boolean carries=QuantifierEditComponent.tupleUpdateCost(component.left.get(i),component.right.get(j))!=0
                    || component.left.get(i).identity.parameter;
            return carries ? bj!=null && bj.equals(selected.get(ai))
                    : !selected.containsKey(ai) && !selected.containsValue(bj);
        });
        if(completeAbsent) {
            var completion=joint.completeAndVerify(as,bs,receipt);
            if(!completion.getJSONArray("absencePremises").isEmpty()) receipt.put("matrixCorrespondenceCompletion",completion);
        } else joint.verify(as,bs,receipt);
        return receipt;
    }

    private static boolean closedPrefix(RepairView view) {
        if(view.phases().size()!=1 || !view.temporalRoot().label().equals("NONE")
                || !view.temporalRoot().children().isEmpty()) return false;
        var phase=view.phases().get(0);
        if(phase.bindings().size()!=phase.quantifiers().size() || phase.bindings().stream().anyMatch(
                b->b.role()!=BindingRole.MATRIX || b.ownerPhase()!=0 || !b.ownerContext().isEmpty()
                        || b.declaration().observationType()==null || b.declaration().observationDomain()==null
                        || !b.declaration().dependencies().isEmpty())) return false;
        Set<Node> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        var pending=new java.util.ArrayDeque<Node>(); if(phase.matrix()!=null) pending.add(phase.matrix());
        while(!pending.isEmpty()) {
            var n=pending.removeFirst(); if(!seen.add(n)) continue;
            if(seen.size()>4096) throw new IllegalStateException("COMPLETION_OCCURRENCE_BUDGET");
            if(n.operator().equals("CALL") || n.operator().equals("REF") || n.isVariable() && n.bindingIndex()<0) return false;
            pending.addAll(n.children()); pending.addAll(n.certifiedAlternatives());
        }
        return true;
    }

    private static List<Binding> orbitBindings(GlobalBindingIndex index) {
        return index.bindings.stream().map(value -> value.binding).toList();
    }

    private static int[] declarationPermutation(GlobalBindingIndex index,org.json.JSONArray wire) {
        if(wire.length()==0) return CertifiedDeclarationOrbit.identity(index.bindings.size());
        int[] permutation=new int[wire.length()];
        for(int i=0;i<permutation.length;i++) permutation[i]=DeclarationReplay.integer(
                new org.json.JSONObject().put("coordinate",wire.get(i)),"coordinate");
        CertifiedDeclarationOrbit.requirePermutation(orbitBindings(index),permutation);
        if(index.phases.size()!=1)
            for(int i=0;i<permutation.length;i++) DeclarationReplay.require(permutation[i]==i,"Multiphase declaration orbit unsupported");
        return permutation;
    }

    private static List<IndexedDeclaration> permuteDeclarations(List<IndexedDeclaration> source,
            GlobalBindingIndex bindings,int[] permutation) {
        var result=new ArrayList<IndexedDeclaration>();
        for(var value:source) {
            Integer index=bindings.indices.get(value.identity);
            if(index==null || permutation[index]==index) { result.add(value); continue; }
            var target=bindings.binding(permutation[index]);
            DeclarationReplay.require(value.declaration.sameRepairTuple(target.binding.declaration()),"Changed orbit tuple");
            result.add(new IndexedDeclaration(target.binding.declaration(),target.identity,target.binding.ordinal()));
        }
        return result;
    }

    private static List<DeclarationReplay.Atom> declarationAtoms(List<IndexedDeclaration> declarations) {
        List<DeclarationReplay.Atom> result = new ArrayList<>();
        for (IndexedDeclaration indexed : declarations) {
            Declaration d = indexed.declaration;
            GlobalBindingIdentity id = indexed.identity;
            String occurrence = new org.json.JSONArray().put(id.parameter).put(id.ownerPhase)
                    .put(id.ownerContext).put(id.coordinate).put(indexed.ordinal).toString();
            String admission = new org.json.JSONArray().put(d.certifiedDomain()).put(d.exchangeClass())
                    .put(new org.json.JSONArray(d.dependencies())).put(String.valueOf(d.observationType()))
                    .put(String.valueOf(d.observationDomain())).toString();
            result.add(new DeclarationReplay.Atom(occurrence,List.of(d.quantifier(),d.type(),d.cardinality(),
                    Integer.toString(d.disjointnessClass())),admission));
        }
        return result;
    }

    public static Result evaluate(RepairView left, RepairView right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (!left.semanticProfile().equals(right.semanticProfile())) {
            throw new IllegalArgumentException(
                    "Repair views from different semantic profiles cannot be compared");
        }
        left.requireCertifiedProjection();
        right.requireCertifiedProjection();
        Result candidate = evaluateMetric(
                left,
                right,
                KernelAuthority.CERTIFIED_PROJECTION_PRODUCER_CONSISTENCY);
        boolean sameProducerObservation = left.hasSameProducerObservation(right);
        if (sameProducerObservation != (candidate.distance == 0)) {
            throw new IllegalStateException(sameProducerObservation
                    ? "Equal producer observations have nonzero repair distance: "
                            + "temporal=" + candidate.temporalDistance
                            + ", quantifiers=" + candidate.quantifierDistance
                            + ", matrix=" + candidate.matrixDistance
                    : "A zero repair distance lacks producer-observation equality");
        }
        return candidate;
    }

    static Result evaluateUncheckedForTesting(RepairView left, RepairView right) {
        return evaluateMetric(left, right, KernelAuthority.TEST_ONLY_UNCHECKED);
    }

    static Result evaluateClaimedProducerConsistencyForTesting(
            RepairView left,
            RepairView right) {
        requireComparableProfiles(left, right);
        Result candidate = evaluateMetric(
                left,
                right,
                KernelAuthority.TEST_ONLY_CLAIMED_PRODUCER_CONSISTENCY);
        boolean sameProducerObservation = left.hasSameClaimedProducerObservationForTesting(
                right);
        if (sameProducerObservation != (candidate.distance == 0)) {
            throw new IllegalStateException(sameProducerObservation
                    ? "Equal producer observations have nonzero repair distance"
                    : "A zero repair distance lacks producer-observation equality");
        }
        return candidate;
    }

    private static void requireComparableProfiles(RepairView left, RepairView right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (!left.semanticProfile().equals(right.semanticProfile())) {
            throw new IllegalArgumentException(
                    "Repair views from different semantic profiles cannot be compared");
        }
    }

    private static Result evaluateMetric(
            RepairView left,
            RepairView right,
            KernelAuthority authority) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        MutableStats stats = DECISIONS.get()==null?new MutableStats():DECISIONS.get();
        // Matrix reference discounts depend on this exact temporal choice, even
        // for scalar callers. Retain typed decisions, not serialized receipts.
        stats.temporal=OrderedTreeEditDistance.alignment(left.temporalRoot(),right.temporalRoot(),TEMPORAL_ADAPTER);
        stats.temporalPayments=SelectedTemporalPayments.from(left,right,stats.temporal);
        int temporal=stats.temporal.cost();
        QuantificationAlignmentSpace quantification =
                QuantificationAlignmentSpace.create(left.phases(), right.phases());
        if (stats.capture) {
            stats.quantification = quantification;
            stats.activeDeclarationPaths = new ArrayList<>(java.util.Collections.nCopies(quantification.components.size(),List.of()));
        }
        int quantifiers = quantification.cost;
        int matrix = matrixDistance(
                left.phases(), right.phases(), quantification, stats);
        return new Result(
                checkedTotal(temporal, quantifiers, matrix),
                temporal,
                quantifiers,
                matrix,
                true,
                authority,
                stats.alphaAlignments);
    }

    static int checkedTotal(int temporal, int quantifiers, int matrix) {
        if (temporal < 0 || quantifiers < 0 || matrix < 0) {
            throw new IllegalArgumentException(
                    "Repair-distance components must be non-negative");
        }
        return Math.addExact(temporal, Math.addExact(quantifiers, matrix));
    }

    public enum KernelAuthority {
        /** Both views were minted from matching frozen certified projections. */
        CERTIFIED_PROJECTION_PRODUCER_CONSISTENCY,
        /** Bounded tests only; no zero-kernel claim has been checked. */
        TEST_ONLY_UNCHECKED,
        /** Bounded refinement only; observation identity is fixture-supplied. */
        TEST_ONLY_CLAIMED_PRODUCER_CONSISTENCY
    }

    public static final class Result {
        private final int distance;
        private final int temporalDistance;
        private final int quantifierDistance;
        private final int matrixDistance;
        private final boolean exactForStoredOrbits;
        private final KernelAuthority kernelAuthority;
        private final long binderAlignments;

        private Result(
                int distance,
                int temporalDistance,
                int quantifierDistance,
                int matrixDistance,
                boolean exactForStoredOrbits,
                KernelAuthority kernelAuthority,
                long binderAlignments) {
            this.distance = distance;
            this.temporalDistance = temporalDistance;
            this.quantifierDistance = quantifierDistance;
            this.matrixDistance = matrixDistance;
            this.exactForStoredOrbits = exactForStoredOrbits;
            this.kernelAuthority = Objects.requireNonNull(
                    kernelAuthority, "kernelAuthority");
            this.binderAlignments = binderAlignments;
        }

        public int distance() {
            return distance;
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

        public boolean exactForStoredOrbits() {
            return exactForStoredOrbits;
        }

        public KernelAuthority kernelAuthority() {
            return kernelAuthority;
        }

        public long binderAlignments() {
            return binderAlignments;
        }
    }

    private static int temporalDistance(TemporalNode left, TemporalNode right) {
        return OrderedTreeEditDistance.distance(left, right, TEMPORAL_ADAPTER);
    }

    private static List<IndexedDeclaration> canonicalQuantifierOrder(
            List<IndexedDeclaration> source) {
        List<IndexedDeclaration> result = new ArrayList<>(source);
        for (int start = 0; start < result.size();) {
            String quantifier = result.get(start).declaration.quantifier();
            int end = start + 1;
            if ("ALL".equals(quantifier) || "SOME".equals(quantifier)) {
                while (end < result.size()
                        && quantifier.equals(
                                result.get(end).declaration.quantifier())) {
                    end++;
                }
                result.subList(start, end).sort((left, right) ->
                        compareTuple(left.declaration, right.declaration));
            }
            start = end;
        }
        return result;
    }

    private static int compareTuple(Declaration left, Declaration right) {
        int comparison = left.type().compareTo(right.type());
        if (comparison != 0) {
            return comparison;
        }
        comparison = left.cardinality().compareTo(right.cardinality());
        if (comparison != 0) {
            return comparison;
        }
        return Integer.compare(left.disjointnessClass(), right.disjointnessClass());
    }

    /**
     * Minimum declaration edits and their binding correspondences. Matrix
     * alignment may use a paid tuple modification or an explicit positional
     * parameter diagonal selected by the edit plan, but never a coincident
     * coordinate invented after that plan has been chosen.
     */
    private static final class QuantificationAlignmentSpace {
        private final int cost;
        private final List<QuantifierEditComponent> components;

        private QuantificationAlignmentSpace(
                int cost,
                List<QuantifierEditComponent> components) {
            this.cost = cost;
            this.components = List.copyOf(components);
        }

        private static QuantificationAlignmentSpace create(
                List<Phase> left,
                List<Phase> right) {
            List<QuantifierEditComponent> components = new ArrayList<>();
            int cost = 0;

            QuantifierEditComponent parameters = QuantifierEditComponent.create(
                    parameterDeclarations(left), parameterDeclarations(right), false);
            components.add(parameters);
            cost = Math.addExact(cost, parameters.cost());

            int aligned = Math.min(left.size(), right.size());
            for (int phase = 0; phase < aligned; phase++) {
                QuantifierEditComponent component = QuantifierEditComponent.create(
                        quantifiedDeclarations(left.get(phase), phase),
                        quantifiedDeclarations(right.get(phase), phase),
                        true);
                components.add(component);
                cost = Math.addExact(cost, component.cost());
            }
            for (int phase = aligned; phase < left.size(); phase++) {
                cost = Math.addExact(cost, left.get(phase).quantifiers().size());
            }
            for (int phase = aligned; phase < right.size(); phase++) {
                cost = Math.addExact(cost, right.get(phase).quantifiers().size());
            }
            return new QuantificationAlignmentSpace(cost, components);
        }

        private void forEachEditAlignment(
                GlobalBindingIndex left,
                GlobalBindingIndex right,
                Consumer<Map<Integer, Integer>> consumer,
                MutableStats stats) {
            enumerateComponents(
                    left,
                    right,
                    consumer,
                    stats,
                    0,
                    new LinkedHashMap<>());
        }

        private void enumerateComponents(
                GlobalBindingIndex left,
                GlobalBindingIndex right,
                Consumer<Map<Integer, Integer>> consumer,
                MutableStats stats,
                int componentIndex,
                Map<Integer, Integer> correspondence) {
            if (componentIndex == components.size()) {
                stats.consumeQuantifierAlignment();
                consumer.accept(Map.copyOf(correspondence));
                return;
            }
            components.get(componentIndex).forEachMinimumAlignment(
                    pairs -> {
                List<Integer> insertedLeft = new ArrayList<>();
                List<Integer> insertedRight = new ArrayList<>();
                for (EditCorrespondence pair : pairs) {
                    Integer leftIndex = left.indices.get(pair.left.identity);
                    Integer rightIndex = right.indices.get(pair.right.identity);
                    if (leftIndex == null || rightIndex == null) {
                        throw new IllegalStateException(
                                "A minimum quantifier correspondence lacks a projected binding");
                    }
                    boolean inserted = addInjectiveCorrespondence(
                            correspondence, leftIndex, rightIndex);
                    if (inserted) {
                        insertedLeft.add(leftIndex);
                        insertedRight.add(rightIndex);
                    }
                }
                enumerateComponents(
                        left,
                        right,
                        consumer,
                        stats,
                        componentIndex + 1,
                        correspondence);
                for (int index = insertedLeft.size() - 1; index >= 0; index--) {
                    Integer removed = correspondence.remove(insertedLeft.get(index));
                    if (removed == null
                            || removed.intValue()
                                    != insertedRight.get(index).intValue()) {
                        throw new IllegalStateException(
                                "Quantifier correspondence backtracking lost identity");
                    }
                }
            }, stats.capture ? path -> stats.activeDeclarationPaths.set(componentIndex,path) : null);
        }

        private static List<IndexedDeclaration> parameterDeclarations(
                List<Phase> phases) {
            if (phases.isEmpty()) {
                return List.of();
            }
            List<IndexedDeclaration> result = new ArrayList<>();
            for (Binding binding : phases.get(0).bindings()) {
                if (binding.role() == BindingRole.PARAMETER) {
                    result.add(new IndexedDeclaration(
                            binding.declaration(),
                            GlobalBindingIdentity.from(binding),
                            binding.ordinal()));
                }
            }
            result.sort((left, right) -> Integer.compare(left.ordinal, right.ordinal));
            return List.copyOf(result);
        }

        private static List<IndexedDeclaration> quantifiedDeclarations(
                Phase phase,
                int phaseIndex) {
            List<IndexedDeclaration> result = new ArrayList<>();
            for (int ordinal = 0; ordinal < phase.quantifiers().size(); ordinal++) {
                Binding binding = matrixBinding(phase, phaseIndex, ordinal);
                Declaration declaration = phase.quantifiers().get(ordinal);
                if (binding.declaration() != declaration
                        && !binding.declaration().sameCertifiedPayload(declaration)) {
                    throw new IllegalStateException(
                            "Quantifier and projected binding declarations disagree");
                }
                result.add(new IndexedDeclaration(
                        declaration,
                        GlobalBindingIdentity.from(binding),
                        ordinal));
            }
            return List.copyOf(result);
        }

        private static Binding matrixBinding(
                Phase phase,
                int phaseIndex,
                int ordinal) {
            for (Binding binding : phase.bindings()) {
                if (binding.role() == BindingRole.MATRIX
                        && binding.ownerPhase() == phaseIndex
                        && binding.ordinal() == ordinal) {
                    return binding;
                }
            }
            throw new IllegalStateException(
                    "Quantifier " + ordinal + " in phase " + phaseIndex
                            + " lacks its certified matrix binding");
        }
    }

    static boolean addInjectiveCorrespondence(
            Map<Integer, Integer> correspondence,
            int leftIndex,
            int rightIndex) {
        Objects.requireNonNull(correspondence, "correspondence");
        for (Map.Entry<Integer, Integer> existing : correspondence.entrySet()) {
            if (existing.getValue().intValue() == rightIndex
                    && existing.getKey().intValue() != leftIndex) {
                throw new IllegalStateException(
                        "Two left bindings share one quantifier correspondence");
            }
        }
        Integer priorRight = correspondence.putIfAbsent(leftIndex, rightIndex);
        if (priorRight != null && priorRight.intValue() != rightIndex) {
            throw new IllegalStateException(
                    "One left binding has two quantifier correspondences");
        }
        return priorRight == null;
    }

    private static final class QuantifierEditComponent {
        private final List<IndexedDeclaration> left;
        private final List<IndexedDeclaration> right;
        private final int[][] distance;

        private QuantifierEditComponent(
                List<IndexedDeclaration> left,
                List<IndexedDeclaration> right,
                int[][] distance) {
            this.left = left;
            this.right = right;
            this.distance = distance;
        }

        private static QuantifierEditComponent create(
                List<IndexedDeclaration> leftSource,
                List<IndexedDeclaration> rightSource,
                boolean canonicalizeExchangeRuns) {
            List<IndexedDeclaration> left = canonicalizeExchangeRuns
                    ? canonicalQuantifierOrder(leftSource)
                    : List.copyOf(leftSource);
            List<IndexedDeclaration> right = canonicalizeExchangeRuns
                    ? canonicalQuantifierOrder(rightSource)
                    : List.copyOf(rightSource);
            int[][] distance = new int[Math.addExact(left.size(), 1)]
                    [Math.addExact(right.size(), 1)];
            for (int row = 1; row <= left.size(); row++) {
                distance[row][0] = Math.addExact(distance[row - 1][0], 1);
            }
            for (int column = 1; column <= right.size(); column++) {
                distance[0][column] = Math.addExact(distance[0][column - 1], 1);
            }
            for (int row = 1; row <= left.size(); row++) {
                for (int column = 1; column <= right.size(); column++) {
                    int update = Math.addExact(
                            distance[row - 1][column - 1],
                            tupleUpdateCost(
                                    left.get(row - 1), right.get(column - 1)));
                    int delete = Math.addExact(distance[row - 1][column], 1);
                    int insert = Math.addExact(distance[row][column - 1], 1);
                    distance[row][column] = Math.min(
                            update, Math.min(delete, insert));
                }
            }
            return new QuantifierEditComponent(left, right, distance);
        }

        private int cost() {
            return distance[left.size()][right.size()];
        }

        private void forEachMinimumAlignment(
                Consumer<List<EditCorrespondence>> consumer, Consumer<List<DeclarationReplay.Move>> pathConsumer) {
            enumerateMinimumAlignments(
                    left.size(),
                    right.size(),
                    new ArrayList<>(),
                    consumer,pathConsumer,pathConsumer==null?null:new ArrayList<>());
        }

        private void enumerateMinimumAlignments(
                int row,
                int column,
                List<EditCorrespondence> reversed,
                Consumer<List<EditCorrespondence>> consumer,Consumer<List<DeclarationReplay.Move>> pathConsumer,
                List<DeclarationReplay.Move> path) {
            if (row == 0 && column == 0) {
                List<EditCorrespondence> result = new ArrayList<>(reversed);
                java.util.Collections.reverse(result);
                if(path!=null){var ordered=new ArrayList<>(path);java.util.Collections.reverse(ordered);pathConsumer.accept(List.copyOf(ordered));}
                consumer.accept(List.copyOf(result));
                return;
            }
            if (row > 0 && column > 0) {
                IndexedDeclaration leftValue = left.get(row - 1);
                IndexedDeclaration rightValue = right.get(column - 1);
                int updateCost = tupleUpdateCost(leftValue, rightValue);
                if (distance[row][column] == Math.addExact(
                        distance[row - 1][column - 1], updateCost)) {
                    boolean carriesBinding = updateCost != 0
                            || leftValue.identity.parameter;
                    if (carriesBinding) {
                        reversed.add(new EditCorrespondence(
                                leftValue, rightValue));
                    }
                    if(path!=null)path.add(new DeclarationReplay.Move(row-1,column-1));
                    enumerateMinimumAlignments(
                            row - 1, column - 1, reversed, consumer,pathConsumer,path);
                    if(path!=null)path.remove(path.size()-1);
                    if (carriesBinding) {
                        reversed.remove(reversed.size() - 1);
                    }
                }
            }
            if (row > 0
                    && distance[row][column]
                            == Math.addExact(distance[row - 1][column], 1)) {
                if(path!=null)path.add(new DeclarationReplay.Move(row-1,-1));
                enumerateMinimumAlignments(
                        row - 1, column, reversed, consumer,pathConsumer,path);
                if(path!=null)path.remove(path.size()-1);
            }
            if (column > 0
                    && distance[row][column]
                            == Math.addExact(distance[row][column - 1], 1)) {
                if(path!=null)path.add(new DeclarationReplay.Move(-1,column-1));
                enumerateMinimumAlignments(
                        row, column - 1, reversed, consumer,pathConsumer,path);
                if(path!=null)path.remove(path.size()-1);
            }
        }

        private static int tupleUpdateCost(
                IndexedDeclaration left,
                IndexedDeclaration right) {
            return left.declaration.sameRepairTuple(right.declaration) ? 0 : 1;
        }
    }

    private static final class IndexedDeclaration {
        private final Declaration declaration;
        private final GlobalBindingIdentity identity;
        private final int ordinal;

        private IndexedDeclaration(
                Declaration declaration,
                GlobalBindingIdentity identity,
                int ordinal) {
            this.declaration = Objects.requireNonNull(declaration, "declaration");
            this.identity = Objects.requireNonNull(identity, "binding identity");
            this.ordinal = ordinal;
        }
    }

    private static final class EditCorrespondence {
        private final IndexedDeclaration left;
        private final IndexedDeclaration right;

        private EditCorrespondence(
                IndexedDeclaration left,
                IndexedDeclaration right) {
            this.left = left;
            this.right = right;
        }
    }

    private static int matrixDistance(
            List<Phase> left,
            List<Phase> right,
            QuantificationAlignmentSpace quantification,
            MutableStats stats) {
        // One owner-coordinate mapping must govern its matrix and every temporal heir.
        int aligned = Math.min(left.size(), right.size());
        int result = 0;
        for (int index = aligned; index < left.size(); index++) {
            if(stats.capture){var context=new MatrixDistanceContext(new int[0],true);result=Math.addExact(result,context.distance(left.get(index).matrix(),null));stats.unaligned.put(index,context.decision(left.get(index).matrix(),null));}
            else result = Math.addExact(result, nodeSize(left.get(index).matrix()));
        }
        for (int index = aligned; index < right.size(); index++) {
            if(stats.capture){var context=new MatrixDistanceContext(new int[0],true);result=Math.addExact(result,context.distance(null,right.get(index).matrix()));stats.unaligned.put(index,context.decision(null,right.get(index).matrix()));}
            else result = Math.addExact(result, nodeSize(right.get(index).matrix()));
        }
        GlobalBindingIndex leftBindings = GlobalBindingIndex.create(left, aligned);
        GlobalBindingIndex rightBindings = GlobalBindingIndex.create(right, aligned);
        if (aligned == 0) {
            return result;
        }

        int fixedUnalignedCost = result;
        int[] best = {Integer.MAX_VALUE};
        boolean[] bestCanonical = {false};
        quantification.forEachEditAlignment(
                leftBindings, rightBindings, correspondence -> {
            Consumer<CertifiedDeclarationOrbit.Choice> evaluate = choice -> {
                int candidate = matrixDistanceForAlignment(
                        leftBindings, rightBindings, choice.correspondence(), stats);
                boolean canonical = CertifiedDeclarationOrbit.isIdentity(choice);
                // An orbit from an early DP path can tie a later canonical path.
                // Apply witness priority globally, without changing the minimum.
                if (CertifiedDeclarationOrbit.prefer(candidate,best[0],canonical,bestCanonical[0])) {
                    if (stats.capture) {
                        stats.selectedMapping = stats.candidateMapping.clone();
                        stats.selectedPhases=stats.candidatePhases;
                        stats.selectedLeft = leftBindings;
                        stats.selectedRight = rightBindings;
                        stats.selectedCorrespondence = new java.util.TreeMap<>(choice.correspondence());
                        stats.selectedDeclarationLeft = choice.left().clone();
                        stats.selectedDeclarationRight = choice.right().clone();
                        stats.selectedDeclarationPaths=List.copyOf(stats.activeDeclarationPaths);
                    }
                    best[0] = candidate;
                    bestCanonical[0] = canonical;
                }
            };
            if(left.size()==1 && right.size()==1)
                CertifiedDeclarationOrbit.forEach(orbitBindings(leftBindings),orbitBindings(rightBindings),correspondence,evaluate,
                        stats.declarationOrbitBudget);
            else evaluate.accept(new CertifiedDeclarationOrbit.Choice(correspondence,
                    CertifiedDeclarationOrbit.identity(leftBindings.bindings.size()),
                    CertifiedDeclarationOrbit.identity(rightBindings.bindings.size())));
        }, stats);
        if (best[0] == Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "No minimum-cost quantifier alignment was evaluated");
        }
        return Math.addExact(fixedUnalignedCost, best[0]);
    }

    private static int matrixDistanceForAlignment(
            GlobalBindingIndex leftBindings,
            GlobalBindingIndex rightBindings,
            Map<Integer, Integer> editCorrespondence,
            MutableStats stats) {
        int maximumMatches = -1;
        int best = Integer.MAX_VALUE;
        int[] mapping = new int[leftBindings.bindings.size()];
        Arrays.fill(mapping, -1);
        boolean[] usedRight = new boolean[rightBindings.bindings.size()];
        boolean[] orbitRelevant = new boolean[leftBindings.bindings.size()];
        for (Map.Entry<Integer, Integer> entry : editCorrespondence.entrySet()) {
            int leftIndex = entry.getKey();
            int rightIndex = entry.getValue();
            if (mapping[leftIndex] >= 0 || usedRight[rightIndex]) {
                throw new IllegalStateException(
                        "A quantifier edit correspondence is not one-to-one");
            }
            mapping[leftIndex] = rightIndex;
            usedRight[rightIndex] = true;
        }

        int[] maximum = {-1};
        int[] minimum = {Integer.MAX_VALUE};
        if (stats.capture) stats.candidateMapping = null;
        forEachGlobalScopeAlignment(leftBindings, rightBindings, scope -> {
            stats.consumeScopeAlignment();
            List<Integer> leftOrder = new ArrayList<>(leftBindings.used);
            leftOrder.removeIf(index -> mapping[index] >= 0);
            leftOrder.sort((first, second) -> Integer.compare(
                    globalCandidateCount(
                            leftBindings.binding(first), rightBindings, scope, usedRight),
                    globalCandidateCount(
                            leftBindings.binding(second), rightBindings, scope, usedRight)));
            int requiredMatches = maximumGlobalCompatibleMatches(
                    leftBindings, rightBindings, leftOrder, scope, usedRight);
            if (requiredMatches < maximum[0]) {
                return;
            }
            if (requiredMatches > maximum[0]) {
                maximum[0] = requiredMatches;
                minimum[0] = Integer.MAX_VALUE;
                if (stats.capture) stats.candidateMapping = null;
            }
            minimum[0] = searchGlobalMappings(
                    leftBindings,
                    rightBindings,
                    leftOrder,
                    scope,
                    mapping,
                    usedRight,
                    orbitRelevant,
                    0,
                    0,
                    requiredMatches,
                    minimum[0],
                    stats);
        });
        maximumMatches = maximum[0];
        best = minimum[0];
        if (maximumMatches < 0 || best == Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "No certified global alpha alignment was evaluated");
        }
        return best;
    }

    private static int searchGlobalMappings(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            List<Integer> leftOrder,
            ScopeAlignment scope,
            int[] mapping,
            boolean[] usedRight,
            boolean[] orbitRelevant,
            int index,
            int matched,
            int requiredMatches,
            int best,
            MutableStats stats) {
        if (index == leftOrder.size()) {
            if (matched != requiredMatches) {
                return best;
            }
            stats.consumeAlphaAlignment();
            int candidate = globallyMappedMatrixDistance(left, right, mapping,stats);
            if (stats.capture && candidate < best) {stats.candidateMapping = mapping.clone();stats.candidatePhases=stats.lastPhases;}
            return Math.min(best, candidate);
        }
        int leftIndex = leftOrder.get(index);
        GlobalBinding leftBinding = left.binding(leftIndex);
        boolean mapped = false;
        for (int rightIndex : right.used) {
            if (usedRight[rightIndex]
                    || !globalCompatible(
                            leftBinding, right.binding(rightIndex), scope)
                    || !preservesCertifiedOrbitRelations(
                            left,
                            right,
                            leftIndex,
                            rightIndex,
                            mapping,
                            orbitRelevant)) {
                continue;
            }
            mapped = true;
            mapping[leftIndex] = rightIndex;
            usedRight[rightIndex] = true;
            orbitRelevant[leftIndex] = true;
            best = searchGlobalMappings(
                    left,
                    right,
                    leftOrder,
                    scope,
                    mapping,
                    usedRight,
                    orbitRelevant,
                    index + 1,
                    matched + 1,
                    requiredMatches,
                    best,
                    stats);
            orbitRelevant[leftIndex] = false;
            usedRight[rightIndex] = false;
            mapping[leftIndex] = -1;
            if (best == 0) {
                return 0;
            }
        }
        if (!mapped || matched + leftOrder.size() - index - 1 >= requiredMatches) {
            best = searchGlobalMappings(
                    left,
                    right,
                    leftOrder,
                    scope,
                    mapping,
                    usedRight,
                    orbitRelevant,
                    index + 1,
                    matched,
                    requiredMatches,
                    best,
                    stats);
        }
        return best;
    }

    private static boolean preservesCertifiedOrbitRelations(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            int candidateLeft,
            int candidateRight,
            int[] mapping,
            boolean[] orbitRelevant) {
        for (int priorLeft = 0; priorLeft < mapping.length; priorLeft++) {
            if (!orbitRelevant[priorLeft]) {
                continue;
            }
            int priorRight = mapping[priorLeft];
            if (priorRight < 0
                    || sameCertifiedOrbit(
                            left.binding(candidateLeft), left.binding(priorLeft))
                            != sameCertifiedOrbit(
                                    right.binding(candidateRight),
                                    right.binding(priorRight))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameCertifiedOrbit(
            GlobalBinding first,
            GlobalBinding second) {
        return !first.identity.parameter
                && !second.identity.parameter
                && first.identity.ownerPhase == second.identity.ownerPhase
                && first.identity.ownerContext.equals(second.identity.ownerContext)
                && first.binding.certifiedOrbit().contains(
                        second.identity.coordinate)
                && second.binding.certifiedOrbit().contains(
                        first.identity.coordinate);
    }

    private static int globallyMappedMatrixDistance(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            int[] globalMapping,MutableStats stats) {
        int result = 0;
        var phases=stats.capture?new ArrayList<MatrixDecision<Node>>():null;
        for (int phaseIndex = 0; phaseIndex < left.phaseCount; phaseIndex++) {
            Phase leftPhase = left.phases.get(phaseIndex);
            Phase rightPhase = right.phases.get(phaseIndex);
            int[] localMapping = phaseMapping(left, right, globalMapping, phaseIndex);
            var context=new MatrixDistanceContext(localMapping,stats.capture,stats.temporalPayments,phaseIndex);
            result = Math.addExact(result,context.distance(leftPhase.matrix(),rightPhase.matrix()));
            if(stats.capture)phases.add(context.decision(leftPhase.matrix(),rightPhase.matrix()));
        }
        if(stats.capture)stats.lastPhases=List.copyOf(phases);
        return result;
    }

    private static int[] phaseMapping(GlobalBindingIndex left, GlobalBindingIndex right,
            int[] globalMapping, int phaseIndex) {
        int[] result = new int[left.phases.get(phaseIndex).bindings().size()];
        Arrays.fill(result, -1);
        int[] localToGlobal = left.localToGlobal.get(phaseIndex);
        for (int local = 0; local < localToGlobal.length; local++) {
            int rightGlobal = globalMapping[localToGlobal[local]];
            if (rightGlobal >= 0) {
                Integer rightLocal = right.usedLocalByGlobal.get(phaseIndex).get(rightGlobal);
                if (rightLocal != null) result[local] = rightLocal;
            }
        }
        return result;
    }

    private static int globalCandidateCount(
            GlobalBinding left,
            GlobalBindingIndex right,
            ScopeAlignment scope,
            boolean[] unavailableRight) {
        int result = 0;
        for (int rightIndex : right.used) {
            if (!unavailableRight[rightIndex]
                    && globalCompatible(left, right.binding(rightIndex), scope)) {
                result++;
            }
        }
        return result;
    }

    private static int maximumGlobalCompatibleMatches(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            List<Integer> leftOrder,
            ScopeAlignment scope,
            boolean[] unavailableRight) {
        int[] matchedLeftByRight = new int[right.bindings.size()];
        Arrays.fill(matchedLeftByRight, -1);
        int result = 0;
        for (int leftIndex : leftOrder) {
            if (augmentGlobalMatching(
                    leftIndex,
                    left,
                    right,
                    scope,
                    matchedLeftByRight,
                    new boolean[right.bindings.size()],
                    unavailableRight)) {
                result++;
            }
        }
        return result;
    }

    private static boolean augmentGlobalMatching(
            int leftIndex,
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            ScopeAlignment scope,
            int[] matchedLeftByRight,
            boolean[] visitedRight,
            boolean[] unavailableRight) {
        for (int rightIndex : right.used) {
            if (unavailableRight[rightIndex]
                    || visitedRight[rightIndex]
                    || !globalCompatible(
                            left.binding(leftIndex), right.binding(rightIndex), scope)) {
                continue;
            }
            visitedRight[rightIndex] = true;
            if (matchedLeftByRight[rightIndex] < 0
                    || augmentGlobalMatching(
                            matchedLeftByRight[rightIndex],
                            left,
                            right,
                            scope,
                            matchedLeftByRight,
                            visitedRight,
                            unavailableRight)) {
                matchedLeftByRight[rightIndex] = leftIndex;
                return true;
            }
        }
        return false;
    }

    private static boolean globalCompatible(
            GlobalBinding left,
            GlobalBinding right,
            ScopeAlignment scope) {
        if (!globalBaseCompatible(left, right)) {
            return false;
        }
        if (left.identity.parameter) {
            return left.identity.coordinate == right.identity.coordinate;
        }
        ScopeOwner owner = globalScopeOwner(left);
        ScopeCoordinate target = scope.mappings.get(new ScopeCoordinate(
                owner, left.binding.declaration().exchangeClass()));
        return target != null && target.equals(new ScopeCoordinate(
                globalScopeOwner(right), right.binding.declaration().exchangeClass()));
    }

    private static boolean globalBaseCompatible(
            GlobalBinding left,
            GlobalBinding right) {
        if (left.identity.parameter != right.identity.parameter
                || !left.binding.declaration().sameCertifiedPayload(
                        right.binding.declaration())) {
            return false;
        }
        if (left.identity.parameter) {
            return true;
        }
        if (left.identity.ownerPhase != right.identity.ownerPhase) {
            return false;
        }
        if (!left.identity.ownerContext.equals(right.identity.ownerContext)) {
            return false;
        }
        // A producer-certified prenex coordinate supersedes its presentation
        // path. Without that explicit authority, lexical scope remains part
        // of admissibility and must match exactly.
        return left.binding.prenexPathErasureCertified()
                        && right.binding.prenexPathErasureCertified()
                || left.binding.bindingPath().equals(right.binding.bindingPath());
    }

    private static void forEachGlobalScopeAlignment(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            Consumer<ScopeAlignment> consumer) {
        Map<ScopeOwner, Map<Integer, List<Integer>>> leftBlocks =
                globalScopeBlocks(left);
        Map<ScopeOwner, Map<Integer, List<Integer>>> rightBlocks =
                globalScopeBlocks(right);
        List<ScopeOwner> owners = new ArrayList<>(leftBlocks.keySet());
        owners.sort(ScopeOwner::compareTo);
        forEachGlobalScopeAlignment(
                left,
                right,
                leftBlocks,
                rightBlocks,
                owners,
                0,
                ScopeAlignment.EMPTY,
                consumer);
    }

    private static void forEachGlobalScopeAlignment(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            Map<ScopeOwner, Map<Integer, List<Integer>>> leftBlocks,
            Map<ScopeOwner, Map<Integer, List<Integer>>> rightBlocks,
            List<ScopeOwner> owners,
            int ownerIndex,
            ScopeAlignment prefix,
            Consumer<ScopeAlignment> consumer) {
        if (ownerIndex == owners.size()) {
            consumer.accept(prefix);
            return;
        }
        ScopeOwner owner = owners.get(ownerIndex);
        forEachGlobalBlockMapping(
                left,
                right,
                leftBlocks.get(owner),
                rightBlocks.getOrDefault(owner, java.util.Collections.emptyMap()),
                mapping -> forEachGlobalScopeAlignment(
                        left,
                        right,
                        leftBlocks,
                        rightBlocks,
                        owners,
                        ownerIndex + 1,
                        prefix.extend(owner, mapping),
                        consumer));
    }

    private static Map<ScopeOwner, Map<Integer, List<Integer>>> globalScopeBlocks(
            GlobalBindingIndex bindings) {
        Map<ScopeOwner, Map<Integer, List<Integer>>> result = new LinkedHashMap<>();
        for (int index : bindings.used) {
            GlobalBinding binding = bindings.binding(index);
            if (binding.identity.parameter) {
                continue;
            }
            result.computeIfAbsent(
                            globalScopeOwner(binding), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(
                            binding.binding.declaration().exchangeClass(),
                            ignored -> new ArrayList<>())
                    .add(index);
        }
        return result;
    }

    private static ScopeOwner globalScopeOwner(GlobalBinding binding) {
        BindingRole role = binding.binding.role() == BindingRole.LOCAL_INHERITED
                ? BindingRole.LOCAL_INHERITED : BindingRole.MATRIX;
        return new ScopeOwner(
                role,
                binding.identity.ownerPhase,
                binding.identity.ownerContext);
    }

    private static void forEachGlobalBlockMapping(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            Map<Integer, List<Integer>> leftBlocks,
            Map<Integer, List<Integer>> rightBlocks,
            Consumer<Map<Integer, Integer>> consumer) {
        List<Integer> leftClasses = new ArrayList<>(leftBlocks.keySet());
        List<Integer> rightClasses = new ArrayList<>(rightBlocks.keySet());
        leftClasses.sort(Integer::compareTo);
        rightClasses.sort(Integer::compareTo);
        enumerateGlobalBlockMappings(
                left,
                right,
                leftBlocks,
                rightBlocks,
                leftClasses,
                rightClasses,
                0,
                0,
                new LinkedHashMap<>(),
                consumer);
    }

    private static void enumerateGlobalBlockMappings(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            Map<Integer, List<Integer>> leftBlocks,
            Map<Integer, List<Integer>> rightBlocks,
            List<Integer> leftClasses,
            List<Integer> rightClasses,
            int leftPosition,
            int minimumRightPosition,
            Map<Integer, Integer> current,
            Consumer<Map<Integer, Integer>> consumer) {
        if (leftPosition == leftClasses.size()) {
            consumer.accept(Map.copyOf(current));
            return;
        }
        int leftClass = leftClasses.get(leftPosition);
        // Match-before-skip agrees with native alpha traversal on exact ties.
        // ScopeBlockTieOrder proves branch rotation preserves the admitted
        // candidate set and maximum-match/minimum-cost objective. No block
        // exchange is added; compatibility and monotone target order stay intact.
        for (int rightPosition = minimumRightPosition;
                rightPosition < rightClasses.size(); rightPosition++) {
            int rightClass = rightClasses.get(rightPosition);
            if (!globalBlocksCompatible(
                    left,
                    right,
                    leftBlocks.get(leftClass),
                    rightBlocks.get(rightClass))) {
                continue;
            }
            current.put(leftClass, rightClass);
            enumerateGlobalBlockMappings(
                    left,
                    right,
                    leftBlocks,
                    rightBlocks,
                    leftClasses,
                    rightClasses,
                    leftPosition + 1,
                    rightPosition + 1,
                    current,
                    consumer);
            current.remove(leftClass);
        }
        enumerateGlobalBlockMappings(
                left,
                right,
                leftBlocks,
                rightBlocks,
                leftClasses,
                rightClasses,
                leftPosition + 1,
                minimumRightPosition,
                current,
                consumer);
    }

    private static boolean globalBlocksCompatible(
            GlobalBindingIndex left,
            GlobalBindingIndex right,
            List<Integer> leftBlock,
            List<Integer> rightBlock) {
        for (int leftIndex : leftBlock) {
            for (int rightIndex : rightBlock) {
                if (globalBaseCompatible(
                        left.binding(leftIndex), right.binding(rightIndex))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<Integer> usedBindings(Node root) {
        Set<Integer> result = new HashSet<>();
        collectUsedBindings(root, result);
        return result;
    }

    private static void collectUsedBindings(Node node, Set<Integer> output) {
        if (node == null) {
            return;
        }
        if (node.isVariable() && node.bindingIndex() >= 0) {
            output.add(node.bindingIndex());
        }
        for (Node child : node.children()) {
            collectUsedBindings(child, output);
        }
        for (Node alternative : node.certifiedAlternatives()) {
            collectUsedBindings(alternative, output);
        }
    }

    private static int nodeSize(Node node) {
        return node == null ? 0 : node.minimumRepresentativeSize();
    }

    private static final class MatrixDistanceContext {
        private final int[] mapping;
        private final Map<NodePair, Integer> memo = new HashMap<>();
        private final Map<NodePair,MatrixDecision<Node>> decisions;
        private final SelectedTemporalPayments temporalPayments;
        private final int phase;
        private List<MatrixDecision.Edge<Node>> edges;

        private MatrixDistanceContext(int[] mapping) {
            this(mapping,false);
        }
        private MatrixDistanceContext(int[] mapping,boolean capture) {
            this(mapping,capture,SelectedTemporalPayments.NONE,-1);
        }
        private MatrixDistanceContext(int[] mapping,boolean capture,SelectedTemporalPayments payments,int phase) {
            this.mapping = mapping.clone();
            decisions=capture?new HashMap<>():null;
            this.temporalPayments=Objects.requireNonNull(payments);
            this.phase=phase;
        }
        private MatrixDecision<Node> decision(Node a,Node b) {return Objects.requireNonNull(decisions.get(new NodePair(a,b)),"Missing evaluated node decision");}
        private MatrixDecision.Edge<Node> edge(List<Node> a,List<Node> b,int i,int j) {
            Node l=i<0?null:a.get(i),r=j<0?null:b.get(j);
            return new MatrixDecision.Edge<>(i,j,i<0||j<0?MatrixDecision.whole(l,r,(l==null?r:l).size()):decision(l,r));
        }

        private int distance(Node left, Node right) {
            if (left == null || right == null) {
                Node present=left==null?right:left;int cost=nodeSize(present);
                if(decisions!=null){
                    MatrixDecision<Node> chosen=MatrixDecision.whole(left,right,cost);
                    if(present!=null&&!present.certifiedAlternatives().isEmpty()){
                        var alternatives=present.certifiedAlternatives();
                        for(int i=0;i<alternatives.size();i++)if(nodeSize(alternatives.get(i))==cost){
                            Node a=left==null?null:alternatives.get(i),b=right==null?null:alternatives.get(i);distance(a,b);
                            chosen=MatrixDecision.select(left,right,left==null?-1:i,right==null?-1:i,decision(a,b));break;
                        }
                    }
                    decisions.put(new NodePair(left,right),chosen);
                }
                return cost;
            }
            NodePair key = new NodePair(left, right);
            Integer remembered = memo.get(key);
            if (remembered != null) {
                return remembered;
            }
            if (!left.certifiedAlternatives().isEmpty()
                    || !right.certifiedAlternatives().isEmpty()) {
                List<Node> leftAlternatives = left.certifiedAlternatives().isEmpty()
                        ? List.of(left)
                        : left.certifiedAlternatives();
                List<Node> rightAlternatives = right.certifiedAlternatives().isEmpty()
                        ? List.of(right)
                        : right.certifiedAlternatives();
                int best = Integer.MAX_VALUE;
                for (int i=0;i<leftAlternatives.size();i++) {
                    Node leftAlternative=leftAlternatives.get(i);
                    for (int j=0;j<rightAlternatives.size();j++) {
                        Node rightAlternative=rightAlternatives.get(j);
                        int candidate=distance(leftAlternative,rightAlternative);
                        if(candidate<best && decisions!=null)decisions.put(key,MatrixDecision.select(left,right,
                                left.certifiedAlternatives().isEmpty()?-1:i,right.certifiedAlternatives().isEmpty()?-1:j,decision(leftAlternative,rightAlternative)));
                        best = Math.min(best,candidate);
                        if (best == 0) {
                            memo.put(key, 0);
                            return 0;
                        }
                    }
                }
                memo.put(key, best);
                return best;
            }
            int children = left.operator().equals(right.operator())
                    && left.orderInsensitive()
                    && right.orderInsensitive()
                    ? unorderedDistance(left.children(), right.children())
                    : sequenceDistance(left.children(), right.children());
            int head=updateCost(left,right),result=Math.addExact(head,children);
            if(decisions!=null)decisions.put(key,MatrixDecision.node(left,right,result,head,
                    left.operator().equals(right.operator())&&left.orderInsensitive()&&right.orderInsensitive(),edges));
            memo.put(key, result);
            return result;
        }

        private int updateCost(Node left, Node right) {
            if (!left.operator().equals(right.operator())) {
                return 1;
            }
            if (left.isVariable()) {
                boolean leftBound = left.bindingIndex() >= 0;
                boolean rightBound = right.bindingIndex() >= 0;
                if (leftBound != rightBound) {
                    return 1;
                }
                if (leftBound) {
                    int mapped = mapping[left.bindingIndex()];
                    return mapped >= 0 && mapped == right.bindingIndex() ? 0 : 1;
                }
                return Objects.equals(
                        left.lexicalVariable(), right.lexicalVariable()) ? 0 : 1;
            }
            // Full semantic identity remains in construction and replay payloads.
            // A differing derived operator needs the selected temporal program
            // and resolved original typed endpoints. This includes complete
            // skeleton reconstruction at the same matrix slot, not just the
            // temporal match/promotion pairing. Native replay executes that
            // whole program before checking any zero-cost reconstruction.
            if (left.isTemporalReference() && right.isTemporalReference()) {
                if(!Objects.equals(left.observationType(), right.observationType()))return 1;
                return (left.observationPhases().equals(right.observationPhases())
                        &&Objects.equals(left.semanticPayload(),right.semanticPayload()))
                        ||temporalPayments.pays(phase,left,right)?0:1;
            }
            return Objects.equals(
                    left.semanticPayload(), right.semanticPayload()) ? 0 : 1;
        }

        private int sequenceDistance(List<Node> left, List<Node> right) {
            if (left.size() == 1 && right.size() == 1) {
                int cost=distance(left.get(0),right.get(0));if(decisions!=null)edges=List.of(edge(left,right,0,0));return cost;
            }
            int[] previous = new int[Math.addExact(right.size(), 1)];
            int[] current = new int[Math.addExact(right.size(), 1)];
            if(decisions!=null)DECISIONS.get().resources.cells((long)left.size()+1,(long)right.size()+1);
            int[][] retained=decisions==null?null:new int[left.size()+1][right.size()+1];
            for (int j = 1; j <= right.size(); j++) {
                previous[j] = Math.addExact(
                        previous[j - 1], right.get(j - 1).size());
            }
            if(retained!=null)System.arraycopy(previous,0,retained[0],0,previous.length);
            for (int i = 1; i <= left.size(); i++) {
                current[0] = Math.addExact(
                        previous[0], left.get(i - 1).size());
                for (int j = 1; j <= right.size(); j++) {
                    int delete = Math.addExact(
                            previous[j], left.get(i - 1).size());
                    int insert = Math.addExact(
                            current[j - 1], right.get(j - 1).size());
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
            if(retained!=null){var chosen=new ArrayList<MatrixDecision.Edge<Node>>();int i=left.size(),j=right.size();
                while(i>0||j>0){
                    if(i>0&&j>0&&retained[i][j]==retained[i-1][j-1]+decision(left.get(i-1),right.get(j-1)).cost())chosen.add(edge(left,right,--i,--j));
                    else if(i>0&&retained[i][j]==retained[i-1][j]+left.get(i-1).size())chosen.add(edge(left,right,--i,-1));
                    else chosen.add(edge(left,right,-1,--j));
                }
                java.util.Collections.reverse(chosen);edges=List.copyOf(chosen);
            }
            return previous[right.size()];
        }

        private int unorderedDistance(List<Node> left, List<Node> right) {
            if (left.isEmpty() || right.isEmpty()) {
                return sequenceDistance(left, right);
            }
            int dimension = Math.addExact(left.size(), right.size());
            int[][] costs = new int[dimension][dimension];
            for (int i = 0; i < dimension; i++) {
                for (int j = 0; j < dimension; j++) {
                    if (i < left.size() && j < right.size()) {
                        costs[i][j] = distance(left.get(i), right.get(j));
                    } else if (i < left.size()) {
                        costs[i][j] = left.get(i).size();
                    } else if (j < right.size()) {
                        costs[i][j] = right.get(j).size();
                    }
                }
            }
            int[] columns=decisions==null?null:new int[dimension];int cost=solveAssignment(costs,columns);
            if(decisions!=null){var chosen=new ArrayList<MatrixDecision.Edge<Node>>();
                for(int i=0;i<dimension;i++){int j=columns[i];if(i<left.size()||j<right.size())chosen.add(edge(left,right,i<left.size()?i:-1,j<right.size()?j:-1));}
                edges=List.copyOf(chosen);}
            return cost;
        }
    }

    static int minimumAssignmentCost(int[][] costs) {
        return solveAssignment(costs,null);
    }

    /** Deterministic attaining columns from the same native Hungarian solve. */
    static int[] minimumAssignmentColumns(int[][] costs) {
        int[] columns = new int[costs.length];
        solveAssignment(costs,columns);
        return columns;
    }

    private static int solveAssignment(int[][] costs,int[] columns) {
        Objects.requireNonNull(costs, "costs");
        int size = costs.length;
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
        int[] columnMatch = new int[Math.addExact(size, 1)];
        int[] predecessor = new int[Math.addExact(size, 1)];
        for (int row = 1; row <= size; row++) {
            columnMatch[0] = row;
            int column = 0;
            long[] minimum = new long[size + 1];
            Arrays.fill(minimum, Long.MAX_VALUE);
            boolean[] used = new boolean[size + 1];
            do {
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
        if (columns != null) for (int column = 1; column <= size; column++)
            columns[columnMatch[column]-1] = column-1;
        return Math.toIntExact(Math.negateExact(columnPotential[0]));
    }

    private static final class ScopeOwner implements Comparable<ScopeOwner> {
        private final BindingRole role;
        private final int phase;
        private final String context;

        private ScopeOwner(BindingRole role, int phase) {
            this(role, phase, "");
        }

        private ScopeOwner(BindingRole role, int phase, String context) {
            this.role = role;
            this.phase = phase;
            this.context = context == null ? "" : context;
        }

        @Override
        public int compareTo(ScopeOwner other) {
            int comparison = role.compareTo(other.role);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(phase, other.phase);
            return comparison != 0 ? comparison : context.compareTo(other.context);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ScopeOwner
                    && role == ((ScopeOwner) other).role
                    && phase == ((ScopeOwner) other).phase
                    && context.equals(((ScopeOwner) other).context);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * role.hashCode() + phase) + context.hashCode();
        }
    }

    private static final class ScopeCoordinate {
        private final ScopeOwner owner;
        private final int exchangeClass;

        private ScopeCoordinate(ScopeOwner owner, int exchangeClass) {
            this.owner = owner;
            this.exchangeClass = exchangeClass;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ScopeCoordinate
                    && owner.equals(((ScopeCoordinate) other).owner)
                    && exchangeClass == ((ScopeCoordinate) other).exchangeClass;
        }

        @Override
        public int hashCode() {
            return 31 * owner.hashCode() + exchangeClass;
        }
    }

    private static final class ScopeAlignment {
        private static final ScopeAlignment EMPTY = new ScopeAlignment(
                java.util.Collections.emptyMap());

        private final Map<ScopeCoordinate, ScopeCoordinate> mappings;

        private ScopeAlignment(Map<ScopeCoordinate, ScopeCoordinate> mappings) {
            this.mappings = mappings;
        }

        private ScopeAlignment extend(
                ScopeOwner owner,
                Map<Integer, Integer> blockMapping) {
            Map<ScopeCoordinate, ScopeCoordinate> result = new LinkedHashMap<>(mappings);
            for (Map.Entry<Integer, Integer> entry : blockMapping.entrySet()) {
                result.put(
                        new ScopeCoordinate(owner, entry.getKey()),
                        new ScopeCoordinate(owner, entry.getValue()));
            }
            return new ScopeAlignment(result);
        }
    }

    private static final class GlobalBindingIdentity {
        private final boolean parameter;
        private final int ownerPhase;
        private final int coordinate;
        private final String ownerContext;

        private GlobalBindingIdentity(
                boolean parameter,
                int ownerPhase,
                int coordinate,
                String ownerContext) {
            this.parameter = parameter;
            this.ownerPhase = ownerPhase;
            this.coordinate = coordinate;
            this.ownerContext = ownerContext == null ? "" : ownerContext;
        }

        private static GlobalBindingIdentity from(Binding binding) {
            if (binding.role() == BindingRole.PARAMETER) {
                return new GlobalBindingIdentity(true, -1, binding.ordinal(), "");
            }
            return new GlobalBindingIdentity(
                    false,
                    binding.ownerPhase(),
                    binding.coordinate(),
                    binding.ownerContext());
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof GlobalBindingIdentity)) {
                return false;
            }
            GlobalBindingIdentity identity = (GlobalBindingIdentity) other;
            return parameter == identity.parameter
                    && ownerPhase == identity.ownerPhase
                    && coordinate == identity.coordinate
                    && ownerContext.equals(identity.ownerContext);
        }

        @Override
        public int hashCode() {
            int result = Boolean.hashCode(parameter);
            result = 31 * result + ownerPhase;
            result = 31 * result + coordinate;
            return 31 * result + ownerContext.hashCode();
        }
    }

    private static final class GlobalBinding {
        private final GlobalBindingIdentity identity;
        private final Binding binding;

        private GlobalBinding(GlobalBindingIdentity identity, Binding binding) {
            this.identity = identity;
            this.binding = binding;
        }
    }

    private static final class GlobalBindingIndex {
        private final List<Phase> phases;
        private final int phaseCount;
        private final List<GlobalBinding> bindings;
        private final Map<GlobalBindingIdentity, Integer> indices;
        private final List<int[]> localToGlobal;
        private final List<Map<Integer, Integer>> usedLocalByGlobal;
        private final Set<Integer> used;

        private GlobalBindingIndex(
                List<Phase> phases,
                int phaseCount,
                List<GlobalBinding> bindings,
                Map<GlobalBindingIdentity, Integer> indices,
                List<int[]> localToGlobal,
                List<Map<Integer, Integer>> usedLocalByGlobal,
                Set<Integer> used) {
            this.phases = phases;
            this.phaseCount = phaseCount;
            this.bindings = bindings;
            this.indices = indices;
            this.localToGlobal = localToGlobal;
            this.usedLocalByGlobal = usedLocalByGlobal;
            this.used = used;
        }

        private static GlobalBindingIndex create(
                List<Phase> phases,
                int phaseCount) {
            Map<GlobalBindingIdentity, Integer> indices = new LinkedHashMap<>();
            List<GlobalBinding> bindings = new ArrayList<>();
            List<int[]> localToGlobal = new ArrayList<>(phaseCount);
            for (int phaseIndex = 0; phaseIndex < phaseCount; phaseIndex++) {
                Phase phase = phases.get(phaseIndex);
                int[] local = new int[phase.bindings().size()];
                for (int bindingIndex = 0;
                        bindingIndex < phase.bindings().size(); bindingIndex++) {
                    Binding binding = phase.bindings().get(bindingIndex);
                    GlobalBindingIdentity identity = GlobalBindingIdentity.from(binding);
                    Integer global = indices.get(identity);
                    if (global == null) {
                        global = bindings.size();
                        indices.put(identity, global);
                        bindings.add(new GlobalBinding(identity, binding));
                    } else {
                        requireConsistentGlobalBinding(
                                bindings.get(global).binding, binding);
                    }
                    local[bindingIndex] = global;
                }
                localToGlobal.add(local);
            }
            requireCertifiedOrbitPartitions(bindings);

            Set<Integer> used = new java.util.TreeSet<>();
            List<Map<Integer, Integer>> usedLocalByGlobal =
                    new ArrayList<>(phaseCount);
            for (int phaseIndex = 0; phaseIndex < phaseCount; phaseIndex++) {
                Phase phase = phases.get(phaseIndex);
                Map<Integer, Integer> usedLocals = new HashMap<>();
                for (int local : usedBindings(phase.matrix())) {
                    int global = localToGlobal.get(phaseIndex)[local];
                    Integer previous = usedLocals.put(global, local);
                    if (previous != null && previous != local) {
                        throw new IllegalStateException(
                                "One certified binder coordinate has multiple matrix indices in a phase");
                    }
                    used.add(global);
                }
                usedLocalByGlobal.add(usedLocals);
            }
            return new GlobalBindingIndex(
                    phases,
                    phaseCount,
                    bindings,
                    indices,
                    localToGlobal,
                    usedLocalByGlobal,
                    used);
        }

        private GlobalBinding binding(int index) {
            return bindings.get(index);
        }

        private static void requireCertifiedOrbitPartitions(
                List<GlobalBinding> bindings) {
            for (GlobalBinding current : bindings) {
                if (current.identity.parameter) {
                    continue;
                }
                List<Integer> expected = new ArrayList<>();
                for (GlobalBinding candidate : bindings) {
                    if (!candidate.identity.parameter
                            && current.identity.ownerPhase
                                    == candidate.identity.ownerPhase
                            && current.identity.ownerContext.equals(
                                    candidate.identity.ownerContext)
                            && current.binding.declaration().exchangeClass()
                                    == candidate.binding.declaration().exchangeClass()
                            && current.binding.declaration().sameCertifiedPayload(
                                    candidate.binding.declaration())) {
                        expected.add(candidate.identity.coordinate);
                    }
                }
                expected.sort(Integer::compareTo);
                if (!expected.equals(current.binding.certifiedOrbit())) {
                    throw new IllegalStateException(
                            "A certified binder orbit does not equal its complete "
                                    + "owner-scoped exchange partition");
                }
            }
        }

        private static void requireConsistentGlobalBinding(
                Binding first,
                Binding repeated) {
            if (!first.declaration().sameCertifiedPayload(repeated.declaration())
                    || first.declaration().exchangeClass()
                            != repeated.declaration().exchangeClass()
                    || !first.certifiedOrbit().equals(repeated.certifiedOrbit())
                    || !first.ownerContext().equals(repeated.ownerContext())
                    || first.prenexPathErasureCertified()
                            != repeated.prenexPathErasureCertified()
                    || !first.prenexPathErasureCertified()
                            && !first.bindingPath().equals(repeated.bindingPath())) {
                throw new IllegalStateException(
                        "Inherited binder metadata disagrees with its owning coordinate");
            }
        }
    }

    private static final class MutableStats {
        private List<MatrixDecision<Node>> lastPhases,candidatePhases,selectedPhases;
        private final Map<Integer,MatrixDecision<Node>> unaligned=new HashMap<>();
        private QuantificationAlignmentSpace quantification;
        private final TraceResources resources=new TraceResources();
        private List<List<DeclarationReplay.Move>> activeDeclarationPaths,selectedDeclarationPaths;
        private OrderedTreeEditDistance.Alignment temporal;
        private SelectedTemporalPayments temporalPayments=SelectedTemporalPayments.NONE;
        private boolean capture;
        private int[] candidateMapping;
        private int[] selectedMapping;
        private GlobalBindingIndex selectedLeft;
        private GlobalBindingIndex selectedRight;
        private Map<Integer, Integer> selectedCorrespondence;
        private int[] selectedDeclarationLeft;
        private int[] selectedDeclarationRight;
        private final CertifiedDeclarationOrbit.Budget declarationOrbitBudget = new CertifiedDeclarationOrbit.Budget();
        private long alphaAlignments;
        private long quantifierAlignments;
        private long scopeAlignments;

        private void consumeAlphaAlignment() {
            alphaAlignments = boundedIncrement(
                    alphaAlignments,
                    "acgn.metric.maxAlphaAlignments",
                    DEFAULT_MAX_ALPHA_ALIGNMENTS,
                    "alpha alignments");
        }

        private void consumeQuantifierAlignment() {
            quantifierAlignments = boundedIncrement(
                    quantifierAlignments,
                    "acgn.metric.maxQuantifierAlignments",
                    DEFAULT_MAX_QUANTIFIER_ALIGNMENTS,
                    "minimum quantifier alignments");
        }

        private void consumeScopeAlignment() {
            scopeAlignments = boundedIncrement(
                    scopeAlignments,
                    "acgn.metric.maxScopeAlignments",
                    DEFAULT_MAX_SCOPE_ALIGNMENTS,
                    "scope alignments");
        }

        private static long boundedIncrement(
                long current,
                String property,
                long defaultMaximum,
                String label) {
            long maximum = Long.getLong(property, defaultMaximum);
            if (maximum <= 0) {
                throw new IllegalStateException(property + " must be positive");
            }
            long next = Math.incrementExact(current);
            if (next > maximum) {
                throw new ResourceLimitException(
                        label + " exceed configured exact-search bound " + maximum);
            }
            return next;
        }
    }

    /** Exact evaluation stopped before a minimum was certified. */
    public static final class ResourceLimitException extends RuntimeException {
        private ResourceLimitException(String message) {
            super(message);
        }
    }

    private static final class NodePair {
        private final Node left;
        private final Node right;

        private NodePair(Node left, Node right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NodePair
                    && left == ((NodePair) other).left
                    && right == ((NodePair) other).right;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(left) + System.identityHashCode(right);
        }
    }
}
