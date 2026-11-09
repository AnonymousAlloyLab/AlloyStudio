package is.fivefivefive.CanDis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.metric.RepairView.*;
import is.fivefivefive.CanDis.theory.*;

/** Bounded comparison of independently replayed native actions. The starting
 * authority is frozen normalized IR, not a public proof of parser normalization.
 * Unsupported or ambiguous correspondence is never evidence of a mismatch. */
public final class NativeOperationComparison {
    public static final String SCHEMA="acgn-native-operation-comparison-v1";
    public enum Status { MATCHED, MISMATCH, UNCHECKABLE, INVALID_REPLAY }
    public static final class Result {
        private final Status status;
        private final String reason;
        private final JSONObject evidence;
        private Result(Status status,String reason,JSONObject evidence) {
            this.status=status; this.reason=reason;
            boundedJson(evidence,new long[]{200_000,6_000_000},0);
            this.evidence=new JSONObject(evidence.toString()).put("schema",SCHEMA).put("status",status.name()).put("reason",reason)
                    .put("certificationStatus","BLOCKED").put("complete",false).put("publicationAllowed",false)
                    .put("publicSequenceLinked",false).put("sourceBoundary","FROZEN_NORMALIZED_IR")
                    .put("javaLeanCorrespondenceChecked",false).put("sourceCertificateWireReplayChecked",false);
        }
        public Status status() { return status; }
        public String reason() { return reason; }
        public JSONObject evidence() { return new JSONObject(evidence.toString()); }
        Result bindScanCosts(int[] fast,int[] certified) {
            var bound=evidence().put("scanFast",new JSONArray(fast)).put("scanCertified",new JSONArray(certified));
            if(status==Status.MATCHED || status==Status.MISMATCH) {
                var report=bound.getJSONObject("diagnostic");
                if(!sameComponents(report.getJSONObject("fast"),fast) || !sameComponents(report.getJSONObject("certified"),certified))
                    return new Result(Status.INVALID_REPLAY,"RECAPTURED_COMPONENT_DRIFT",bound.put("scanCostBindingChecked",false));
                bound.put("scanCostBindingChecked",true);
            }
            return new Result(status,reason,bound);
        }
        private static boolean sameComponents(JSONObject arm,int[] values) {
            var keys=List.of("distance","temporalDistance","quantifierDistance","matrixDistance");
            if(values.length!=keys.size())return false;
            for(int i=0;i<keys.size();i++)if(!(arm.opt(keys.get(i)) instanceof Integer value) || value!=values[i])return false;
            return true;
        }
    }
    private static final int MAX_NODES=512,MAX_DEPTH=48,MAX_BINDINGS=6;
    private static final class Unsupported extends RuntimeException {
        JSONObject boundary;
        Unsupported(String why) { super(why); }
    }
    private static void supported(boolean condition,String reason) { if(!condition) throw new Unsupported(reason); }
    private static void require(boolean condition,String reason) { if(!condition) throw new IllegalArgumentException(reason); }
    static Result invalid(String reason) { return new Result(Status.INVALID_REPLAY,reason,new JSONObject()); }

    static Result compare(Canonical.Prepared a,Canonical.Prepared b,CanonicalAlloyPipeline.Prepared ca,CanonicalAlloyPipeline.Prepared cb) {
        if(TemporalOperationCorrespondence.temporal(a)||TemporalOperationCorrespondence.temporal(b)) {
            try {return compare(a,b,CanonicalRepairTraces.fast(a,b),CanonicalRepairTraces.certified(ca,cb));}
            catch(RuntimeException e){return failure(e,null);}
        }
        return compareSelected(a,b,()->DistanceReplayDiagnostics.capture(a,b,ca,cb));
    }

    static Result compare(Canonical.Prepared a,Canonical.Prepared b,NativeRepairTrace fast,NativeRepairTrace certified) {
        if(TemporalOperationCorrespondence.temporal(a)||TemporalOperationCorrespondence.temporal(b)) {
            JSONObject report=null;
            try {
                report=DistanceReplayDiagnostics.captureTraces(fast,certified);
                return compareCaptured(a,b,fast,certified,report);
            } catch(RuntimeException e){return failure(e,report);}
        }
        return compareSelected(a,b,()->DistanceReplayDiagnostics.captureTraces(fast,certified));
    }

    /** Recheck a diagnostic using its actual immutable numeric selections. */
    static Result compareCaptured(Canonical.Prepared a,Canonical.Prepared b,NativeRepairTrace fast,NativeRepairTrace certified,JSONObject report) {
        try {
            if(!TemporalOperationCorrespondence.temporal(a)&&!TemporalOperationCorrespondence.temporal(b)) {
                TemporalOperationCorrespondence.replay(fast,report.getJSONObject("fast"));
                TemporalOperationCorrespondence.replay(certified,report.getJSONObject("certified"));
                return compareCaptured(a,b,report);
            }
            var evidence=TemporalOperationCorrespondence.compare(a,b,fast,certified,report);
            boolean same=evidence.getBoolean("orderedBlockEffectsEqual");
            return new Result(same?Status.MATCHED:Status.MISMATCH,same?"CHECKED_NATIVE_ACTIONS_EQUAL":"CHECKED_NATIVE_ACTIONS_DIFFER",evidence);
        } catch(TemporalOperationCorrespondence.Missing missing) {
            return new Result(Status.UNCHECKABLE,missing.getMessage(),retained(report).put("correspondenceBoundary",missing.boundary));
        } catch(RuntimeException e){return failure(e,report);}
    }

    private static Result compareSelected(Canonical.Prepared a,Canonical.Prepared b,java.util.function.Supplier<JSONObject> capture) {
        JSONObject report=null;
        try {
            // Retain the actual selected paths even if the later correspondence
            // checker cannot yet map this successfully constructed endpoint.
            report=capture.get();
            Endpoint left=new Endpoint(a),right=new Endpoint(b);
            // A bridge refusal cannot erase the already captured native paths.
            // It also cannot be promoted to a demonstrated action mismatch.
            selectRepresentatives(left,right,report);
            var ap=bridge(left); var bp=bridge(right);
            report.put("nativeOperationEndpoints",new JSONArray().put(left.commitment).put(right.commitment));
            return verify(left,right,ap,bp,report);
        } catch(Unsupported e) { return refused(e,report); }
        catch(RuntimeException e) { return failure(e,report); }
    }

    /** Replays a captured diagnostic against fresh authority from these exact
     * frozen endpoint objects. No JSON success flag is accepted as authority. */
    static Result compareCaptured(Canonical.Prepared a,Canonical.Prepared b,JSONObject report) {
        try {
            boundedJson(report,new long[]{120_000,4_000_000},0);
            Endpoint left=new Endpoint(a),right=new Endpoint(b);
            selectRepresentatives(left,right,report);
            return verify(left,right,bridge(left),bridge(right),report);
        } catch(Unsupported e) { return refused(e,report); }
        catch(RuntimeException e) { return failure(e,report); }
    }

    private static Result failure(RuntimeException error,JSONObject report) {
        String text=String.valueOf(error.getMessage());
        if(text.contains("BUDGET") || text.contains("budget") || text.contains("group bound"))
            return new Result(Status.UNCHECKABLE,"REPLAY_BUDGET",new JSONObject());
        var evidence=new JSONObject().put("errorType",error.getClass().getName()).put("error",text.substring(0,Math.min(500,text.length())));
        if(report!=null) {
            try { boundedJson(report,new long[]{120_000,4_000_000},0); evidence.put("diagnostic",report); }
            catch(RuntimeException tooLarge) { evidence.put("receiptOmitted","BUDGET"); }
        }
        return new Result(Status.INVALID_REPLAY,"INVALID_NATIVE_RECEIPT",evidence);
    }

    private static JSONObject retained(JSONObject report) {
        var out=new JSONObject();if(report==null)return out;
        try { boundedJson(report,new long[]{120_000,4_000_000},0);out.put("diagnostic",report); }
        catch(Unsupported resource) {out.put("receiptRetentionBlocker","REPLAY_BUDGET");}
        return out;
    }
    private static Result refused(Unsupported error,JSONObject report) {
        var evidence=retained(report);if(error.boundary!=null)evidence.put("correspondenceBoundary",error.boundary);
        return new Result(Status.UNCHECKABLE,error.getMessage(),evidence);
    }

    private static Result verify(Endpoint a,Endpoint b,Map<String,String> ap,Map<String,String> bp,JSONObject report) {
        boundedJson(report,new long[]{120_000,4_000_000},0);
        require(new JSONArray().put(a.commitment).put(b.commitment).similar(report.getJSONArray("nativeOperationEndpoints")),"Foreign original endpoint commitment");
        JSONObject fast=report.getJSONObject("fast"),cert=report.getJSONObject("certified");
        for(var arm:List.of(fast,cert)) {
            if(!arm.has("phases")) {
                String error=arm.optString("error");
                if(error.contains("BUDGET") || error.contains("budget")) throw new Unsupported("REPLAY_BUDGET");
                if(error.isEmpty()) throw new Unsupported("MISSING_MATRIX_RECEIPT");
                throw new IllegalArgumentException("Native matrix capture failed: "+error);
            }
            for(String part:List.of("declarationSlice","temporalSlice")) {
                JSONObject slice=arm.optJSONObject(part);
                if(slice==null || !slice.has(part.equals("declarationSlice")?"components":"operations")) {
                    if(slice!=null && slice.optString("error").contains("DECLARATION_REPLAY_INVALID")
                            && !slice.optString("error").contains("No minimum path"))
                        throw new IllegalArgumentException("Invalid emitted "+part+": "+slice.optString("error"));
                    throw new Unsupported("MISSING_"+part.toUpperCase(Locale.ROOT));
                }
            }
        }
        var fa=arm(a,b,fast,false,ap,bp); var ca=arm(a,b,cert,true,identity(ap),identity(bp));
        for(String cost:List.of("distance","temporalDistance","quantifierDistance","matrixDistance"))
            require(fast.getInt(cost)==cert.getInt(cost),"Native component mismatch: "+cost);
        int first=0; while(first<Math.min(fa.length(),ca.length()) && fa.getJSONObject(first).similar(ca.getJSONObject(first))) first++;
        var evidence=new JSONObject().put("endpointCorrespondenceChecked",true).put("sourceCertificatesChecked",true)
                .put("fixedChoiceReplaysChecked",true).put("fastActionCount",fa.length()).put("certifiedActionCount",ca.length())
                .put("diagnostic",report).put("fastActions",fa).put("certifiedActions",ca);
        // Construction proofs do not become repair edits. These two catalogs
        // link the action paths to their actual typed endpoints and premises.
        evidence.put("constructionEndpoints",new JSONArray().put(a.trace(ap)).put(b.trace(bp)));
        evidence.put("certifiedRepresentationSelections",new JSONObject()
                .put("source",a.selections).put("target",b.selections));
        if(first==fa.length() && first==ca.length()) return new Result(Status.MATCHED,"CHECKED_NATIVE_ACTIONS_EQUAL",evidence);
        evidence.put("firstDifference",first).put("fastFirst",first<fa.length()?fa.get(first):JSONObject.NULL)
                .put("certifiedFirst",first<ca.length()?ca.get(first):JSONObject.NULL);
        return new Result(Status.MISMATCH,"CHECKED_NATIVE_ACTIONS_DIFFER",evidence);
    }

    private static JSONArray arm(Endpoint a,Endpoint b,JSONObject arm,boolean certified,Map<String,String> ap,Map<String,String> bp) {
        require(arm.getJSONArray("phases").length()==1,"Phase coverage");
        var phase=arm.getJSONArray("phases").getJSONObject(0); require(phase.getInt("phase")==0,"Foreign matrix phase");
        var components=arm.getJSONObject("declarationSlice").getJSONArray("components");
        require(components.length()==(certified?2:1),"Declaration component coverage");
        if(certified) require(DeclarationReplay.verify(List.of(),List.of(),components.getJSONObject(0).getJSONObject("receipt"))==0,"Parameter actions");
        var component=components.getJSONObject(certified?1:0); require(component.getInt("phase")==0,"Foreign declaration phase");
        var receipt=component.getJSONObject("receipt");
        var as=a.atoms(receipt.getJSONArray("source"),certified); var bs=b.atoms(receipt.getJSONArray("target"),certified);
        int declarations=DeclarationReplay.verify(as,bs,receipt);
        Map<String,String> selected=certified?certMapping(a,b,arm.getJSONArray("selectedGlobalMapping")):stringMapping(phase.getJSONObject("alphaMapping"));
        validateMapping(a,b,selected);
        // A paid declaration link can target a binder absent from the matrix.
        // Keep that global link, but check the producer's exact phase projection.
        if(certified) require(phase.getJSONArray("alphaMapping").similar(new JSONArray(
                QuotientRepairDistance.projectFixedMatrixMapping(a.view,b.view,indices(a,b,selected)))),"Global/phase map conflict");
        var completed=new TreeMap<>(selected);
        JSONObject completion=receipt.optJSONObject("matrixCorrespondenceCompletion");
        if(completion!=null) {
            var chosen=stringMapping(completion.getJSONObject("selectedMapping"));
            var filled=stringMapping(completion.getJSONObject("completedMapping"));
            if(certified) { chosen=coordinateMap(a,b,chosen); filled=coordinateMap(a,b,filled); }
            require(selected.equals(chosen),"Completion source map conflict");
            validateMapping(a,b,filled);
            for(var entry:selected.entrySet()) require(entry.getValue().equals(filled.get(entry.getKey())),"Completion changes selected link");
            completed.clear(); completed.putAll(filled);
        }
        var an=as.stream().map(x->a.name(x,certified)).toList(); var bn=bs.stream().map(x->b.name(x,certified)).toList();
        var link=new DeclarationMatrixLink(an,bn,completed,Set.copyOf(an),Set.copyOf(bn));
        require(link.verify(as,bs,receipt)==declarations,"Joint original-coordinate declaration link");
        // An added correspondence must be an actual emitted diagonal, not a
        // free alpha permission silently smuggled into a matrix completion.
        var diagonals=new HashMap<String,String>();var actualPaid=new HashMap<String,String>();
        for(Object item:receipt.getJSONArray("operations")) {
            var op=(JSONObject)item; int i=op.getInt("leftIndex"),j=op.getInt("rightIndex");
            if(i>=0 && j>=0) {
                diagonals.put(an.get(i),bn.get(j));
                if(op.getInt("cost")!=0) actualPaid.put(an.get(i),bn.get(j));
            }
        }
        for(var entry:completed.entrySet()) if(!selected.containsKey(entry.getKey()))
            require(entry.getValue().equals(diagonals.get(entry.getKey())),"Completion lacks declaration action");
        JSONObject paid=certified?arm.getJSONObject("selectedQuantifierCorrespondence"):
                arm.optJSONObject("selectedPaidDeclarationCorrespondence");
        if(paid==null && completion!=null)paid=completion.optJSONObject("paidCorrespondence");
        if(paid!=null) {
            var links=stringValues(paid); if(certified) links=coordinateMap(a,b,links);
            require(links.equals(actualPaid),"Paid declaration action coverage");
            for(var entry:links.entrySet()) require(entry.getValue().equals(completed.get(entry.getKey()))
                    && entry.getValue().equals(diagonals.get(entry.getKey())),"Paid matrix/declaration conflict");
        }
        var matrix=phase.getJSONObject("receipt");
        int matrixCost=certified?QuotientRepairDistance.verifyFixedMatrixReplay(a.view,b.view,
                QuotientRepairDistance.projectFixedMatrixMapping(a.view,b.view,indices(a,b,selected)),
                QuotientRepairDistance.projectFixedMatrixMapping(a.view,b.view,indices(a,b,completed)),matrix):CanonicalDistance.verifyFixedMatrixReplay(a.fast,b.fast,selected,completed,matrix);
        require(matrixCost==arm.getInt("matrixDistance") && declarations==arm.getInt("quantifierDistance"),"Native numeric endpoint costs");
        JSONObject temporal=arm.getJSONObject("temporalSlice");
        TemporalReplay.verify(a.view.temporalRoot(),b.view.temporalRoot(),RepairView.TemporalNode::label,RepairView.TemporalNode::children,temporal);
        require(arm.getInt("temporalDistance")==0 && temporal.getInt("cost")==0 && temporal.getJSONArray("operations").isEmpty(),"Temporal identity slice");
        require(arm.getInt("distance")==matrixCost+declarations,"Total cost linkage");
        PhaseReplay.verifyDiagnosticOrder(temporal,arm.getJSONObject("declarationSlice"),arm.getJSONArray("phases"),arm.getJSONArray("orderedDiagnosticActions"));
        var actions=new JSONArray();
        var pa=a.permutation(as,certified); var pb=b.permutation(bs,certified);
        JSONObject permutation=component.optJSONObject("connectingPermutation");
        if(permutation==null) require(identity(pa) && identity(pb),"Unrecorded declaration exchange");
        else {
            require(permutation.getInt("cost")==0 && new JSONArray(pa).similar(permutation.getJSONArray("source"))
                    && new JSONArray(pb).similar(permutation.getJSONArray("target")),"Permutation/action coordinate conflict");
            actions.put(new JSONObject().put("stage","declarationPermutation").put("cost",0)
                    .put("source",new JSONArray(pa)).put("target",new JSONArray(pb)));
        }
        if(permutation!=null && permutation.has("sourcePresentation")) {
            require(permutation.getString("admission").equals("INDEPENDENT_TYPED_PREFIX_PRESENTATION"),"Presentation law");
            a.checkPresentation(pa,certified,permutation.getJSONObject("sourcePresentation"));
            b.checkPresentation(pb,certified,permutation.getJSONObject("targetPresentation"));
        } else {
            a.checkGlobalPermutation(new JSONArray(a.globalPermutation(pa)));
            b.checkGlobalPermutation(new JSONArray(b.globalPermutation(pb)));
        }
        if(!certified && arm.has("selectedGroupedDeclarationPermutation")) {
            var selectedOrder=arm.getJSONObject("selectedGroupedDeclarationPermutation");
            require(permutation!=null && selectedOrder.getJSONArray("source").similar(permutation.getJSONArray("source"))
                    && selectedOrder.getJSONArray("target").similar(permutation.getJSONArray("target")),
                    "Selected grouped permutation/action linkage");
        }
        if(certified) {
            a.checkGlobalPermutation(arm.getJSONObject("selectedDeclarationPermutation").getJSONArray("left"));
            b.checkGlobalPermutation(arm.getJSONObject("selectedDeclarationPermutation").getJSONArray("right"));
            var effective=arm.getJSONObject("declarationSlice").getJSONObject("selectedDeclarationPermutation");
            require(Arrays.equals(a.globalPermutation(pa),ints(effective.getJSONArray("left")))
                    && Arrays.equals(b.globalPermutation(pb),ints(effective.getJSONArray("right"))),"Effective declaration permutation linkage");
        }
        for(Object item:receipt.getJSONArray("operations")) {
            var op=(JSONObject)item;int i=op.getInt("leftIndex"),j=op.getInt("rightIndex");
            actions.put(new JSONObject().put("stage","declaration").put("kind",op.getString("kind")).put("cost",op.getInt("cost"))
                    .put("source",i<0?JSONObject.NULL:a.ordinal(an.get(i))).put("target",j<0?JSONObject.NULL:b.ordinal(bn.get(j)))
                    .put("sourceTuple",i<0?JSONObject.NULL:new JSONArray(as.get(i).tuple()))
                    .put("targetTuple",j<0?JSONObject.NULL:new JSONArray(bs.get(j).tuple())));
        }
        flatten(matrix.getJSONObject("derivation"),"$","$",ap,bp,actions,new int[]{2048},0);
        return actions;
    }

    private static final class Endpoint {
        final Canonical.Prepared normalized;
        final NormalForm source,comparison;
        final CanonicalDistance.Prepared fast;
        final RepairView view;
        final Phase phase;
        final TheoryAlloyAdapter.Result evidence;
        final List<QuantiVar> qs;
        final List<Integer> base;
        final List<DeclarationReplay.Atom> fastAtoms,certAtoms;
        final Map<String,Integer> ordinals=new HashMap<>();
        final Map<Long,EGraphNode> originalVariables=new HashMap<>();
        final Map<Long,EGraphNode> originalCalls=new HashMap<>();
        final Map<Long,EGraphNode> originalConstants=new HashMap<>();
        final ClosedCallEvidence closedCalls;
        final BoundCallEvidence boundCalls;
        final LocalOwnerCorrespondence localOwners;
        final Set<EGraphNode> scopedCalls=Collections.newSetFromMap(new IdentityHashMap<>());
        final List<TypedEqualityCertificate> constructionRoots=new ArrayList<>();
        final Map<String,Node> selectedNodes=new HashMap<>();
        final JSONArray selections=new JSONArray();
        final String commitment;
        Endpoint(Canonical.Prepared normalized) {
            this.normalized=normalized;
            supported(normalized.normalizedForms().size()==1,"MULTIPHASE_UNSUPPORTED");
            source=normalized.normalizedForms().get(0);
            supported(source.getTemporalOp()==NormalForm.TemporalOp.NONE && source.getTemporalChildren().isEmpty(),"TEMPORAL_SCOPE_UNSUPPORTED");
            supported(source.getParams().isEmpty() && source.getInheritedQuantiVars().isEmpty()
                    && source.getPhaseLocalBindingImports().isEmpty(),"PARAMETER_OR_IMPORTED_SCOPE_UNSUPPORTED");
            qs=source.getMatrixQuantiVars(); supported(qs.size()<=MAX_BINDINGS,"BINDER_BUDGET");
            supported(source.getLocalQuantiVars().size()<=MAX_NODES,"LOCAL_METADATA_BUDGET");
            // The two native declaration sorters use enum versus lexical
            // multiplicity order. That representation bridge is not supplied
            // by this slice, so never call its ordering difference invalid.
            for(int start=0;start<qs.size();) {
                int end=start+1;var kind=qs.get(start).getQuantifier();
                if(kind==QuantiVar.Quantifier.ALL || kind==QuantiVar.Quantifier.SOME)
                    while(end<qs.size() && qs.get(end).getQuantifier()==kind) {
                        supported(qs.get(end).getCardinality()==qs.get(start).getCardinality(),"MIXED_MULTIPLICITY_ORDER_UNSUPPORTED");end++;
                    }
                start=end;
            }
            closedCalls=ClosedCallEvidence.from(source);
            boundCalls=BoundCallEvidence.from(source);
            fast=CanonicalDistance.prepare(normalized.normalizedForms());comparison=fast.comparisonForms().get(0);
            scopedCalls.addAll(FastLocalPaidScope.checkedCalls(source));
            scopedCalls.addAll(FastLocalPaidScope.checkedCalls(comparison));
            census(source.getCertificationMatrixEGraph(),new int[]{MAX_NODES},0,true);
            census(source.getMatrixEGraph(),new int[]{MAX_NODES},0,false);
            for(int i=0;i<qs.size();i++) {
                var q=qs.get(i);require(q.isFrozenForCertification(),"Mutable original binder");
                supported(q.getExactAlloyType()!=null && q.getExactAlloyType().hasParserAuthenticatedAncestry(),"BINDER_TYPE_AUTHORITY_UNSUPPORTED");
                require(ordinals.put(q.getName(),i)==null,"Repeated original binder name");
            }
            evidence=TheoryAlloyAdapter.adapt(normalized.normalizedForms(),normalized.semanticProfile());
            evidence.requireRepairProjectionSources(normalized.normalizedForms());
            view=RepairProjection.project(evidence,normalized.normalizedForms()); phase=view.phases().get(0);
            var artifact=evidence.semanticArtifact();
            for(var call:originalCalls.values()) {
                var metadata=CallMetadata.require(call);
                var premises=artifact.callOccurrenceCertificates().stream()
                        .filter(c->c.occurrenceId()==metadata.occurrenceId()).toList();
                require(premises.size()==1,"Missing original call premise");
                var premise=premises.get(0);
                require(premise.qualifiedCallee().equals(metadata.identity())
                        && premise.sourceName().equals(metadata.sourceName()) && premise.kind().equals(metadata.kind())
                        && premise.declaredArity()==metadata.arity() && premise.arityAuthority()==metadata.authority()
                        && premise.sourceEndpoint().operator().outputType().equals(AlloyTypeBridge.graphType(call.getExactAlloyType())),
                        "Original call/type/premise correspondence");
            }
            List<TypedEqualityCertificate> roots=new ArrayList<>();roots.addAll(artifact.flatConstructions());roots.addAll(artifact.containerConstructions());
            roots.addAll(artifact.dependentChainConstructions());roots.addAll(artifact.guardedJoinConstructions());roots.addAll(artifact.binderOccurrenceCertificates());
            supported(roots.size()<=2048,"CERTIFICATE_BUDGET");for(var proof:roots)CertificateVerifier.verify(proof);
            constructionRoots.addAll(roots);
            for(var proof:evidence.phaseBinderDescriptors().get(0).automorphisms().generatorCertificates())CertificateVerifier.verify(proof);
            supported(phase.bindings().size()==qs.size(),"NONPREFIX_BINDING_UNSUPPORTED");
            certAtoms=new ArrayList<>(Collections.nCopies(qs.size(),null));
            var canonicalSlots=new HashSet<Integer>();
            for(int i=0;i<phase.bindings().size();i++) {
                var binding=phase.bindings().get(i);var d=binding.declaration();
                supported(binding.role()==BindingRole.MATRIX && binding.ownerPhase()==0 && binding.ownerContext().isEmpty()
                        && d.dependencies().isEmpty(),"BINDING_LAYOUT_UNSUPPORTED");
                int ordinal=binding.ordinal();require(ordinal>=0 && ordinal<qs.size() && certAtoms.get(ordinal)==null,"Binder ordinal coverage");
                var q=qs.get(ordinal);
                var coordinate=evidence.phaseBinderDescriptors().get(0).coordinates().get(evidence.phaseSourceCoordinates().get(0).get(ordinal));
                require(binding.coordinate()==evidence.phaseSourceCoordinates().get(0).get(ordinal)
                        && canonicalSlots.add(binding.coordinate()),"Original ordinal/canonical-slot correspondence");
                // Unary binder slots use AlloyCarrier(T), while literal matrix
                // nodes use relation types. Compare each to its own authority.
                require(d.quantifier().equals(q.getQuantifier().name()) && d.cardinality().equals(q.getCardinality().name())
                        && coordinate.quantifier().equals(q.getQuantifier().name()) && coordinate.multiplicity().equals(q.getCardinality().name())
                        && Objects.equals(d.observationType(),coordinate.type()) && Objects.equals(d.observationDomain(),coordinate.domain()),
                        "Source/binder/type correspondence");
                String occurrence=new JSONArray().put(false).put(0).put(binding.ownerContext()).put(binding.coordinate()).put(ordinal).toString();
                String admission=new JSONArray().put(d.certifiedDomain()).put(d.exchangeClass()).put(new JSONArray(d.dependencies()))
                        .put(String.valueOf(d.observationType())).put(String.valueOf(d.observationDomain())).toString();
                certAtoms.set(ordinal,new DeclarationReplay.Atom(occurrence,List.of(d.quantifier(),d.type(),d.cardinality(),Integer.toString(d.disjointnessClass())),admission));
            }
            fastAtoms=CanonicalDistance.diagnosticDeclarationAtoms(source);base=CanonicalDistance.diagnosticDeclarationOrder(source);
            projectedCensus(phase.matrix(),new int[]{MAX_NODES},0);
            census(comparison.getMatrixEGraph(),new int[]{MAX_NODES},0,false);
            localOwners=LocalOwnerCorrespondence.check(source,comparison.getMatrixEGraph());
            var ids=new JSONArray();for(var q:qs)ids.put(new JSONArray().put(q.getId()).put(q.getName()).put(q.getBindingPath()).put(q.getExactAlloyType().stableString()));
            commitment=hash(new JSONArray().put(source.getCertificationMatrixEGraph().getSourceOccurrenceLineage()).put(ids)
                    .put(new TreeSet<>(originalVariables.keySet())).toString());
        }
        private void census(EGraphNode n,int[] budget,int depth,boolean original) {
            supported(n!=null && depth<=MAX_DEPTH && --budget[0]>=0,"ENDPOINT_BUDGET");
            supported(n.getChildren().size()<=64,"CHILD_BUDGET");
            supported(n.getOpcode()!=EGraphNode.Opcode.REF,"REFERENCE_OR_CALL_UNSUPPORTED");
            if(n.getOpcode()==EGraphNode.Opcode.CALL) {
                supported(scopedCalls.contains(n) || source.getLocalQuantiVars().isEmpty()
                        && ((original?ClosedCallEvidence.closed(n):closedCalls.admits(n))
                            || boundCalls.admits(n)),"CLOSED_CALL_SOURCE_UNSUPPORTED");
                if(original) {
                    originalCalls.put(n.getSourceOccurrenceLineage(),n);
                    supported(originalCalls.size()<=64,"CLOSED_CALL_COUNT_BUDGET");
                }
            }
            if(original && n.getOpcode()==EGraphNode.Opcode.VARIABLE) {
                var prior=originalVariables.putIfAbsent(n.getSourceOccurrenceLineage(),n);
                supported(prior==null || Objects.equals(prior.getAlphaName(),n.getAlphaName()),"AMBIGUOUS_SOURCE_LINEAGE");
            }
            if(original && n.getOpcode()==EGraphNode.Opcode.CONSTANT)originalConstants.put(n.getSourceOccurrenceLineage(),n);
            for(var child:n.getChildren())census(child,budget,depth+1,original);
        }

        JSONObject trace(Map<String,String> paths) {
            var keys=new TraceKeys();var proofs=new JSONArray();var pending=new ArrayDeque<TypedEqualityCertificate>(constructionRoots);
            var ids=new IdentityHashMap<TypedEqualityCertificate,Integer>();
            for(var root:constructionRoots)if(!ids.containsKey(root))ids.put(root,ids.size());
            var seen=Collections.newSetFromMap(new IdentityHashMap<TypedEqualityCertificate,Boolean>());
            while(!pending.isEmpty()) {
                var proof=pending.remove();if(!seen.add(proof))continue;
                supported(seen.size()<=2048,"CERTIFICATE_BUDGET");var premises=new JSONArray();
                for(var premise:proof.premises()){if(!ids.containsKey(premise))ids.put(premise,ids.size());premises.put(ids.get(premise));pending.add(premise);}
                proofs.put(new JSONObject().put("id",ids.get(proof)).put("category",proof.category()).put("premises",premises)
                        .put("sourceEndpoint",keys.id(proof.leftEndpoint().structuralKey(),0))
                        .put("constructedEndpoint",keys.id(proof.rightEndpoint().structuralKey(),0))
                        .put("proof",keys.id(proof.structuralKey(),0)));
            }
            var calls=new JSONArray();for(var c:evidence.semanticArtifact().callOccurrenceCertificates())calls.put(new JSONObject()
                    .put("occurrence",c.occurrenceId()).put("sourcePath",c.sourcePath()).put("callee",c.qualifiedCallee())
                    .put("authority",c.arityAuthority()).put("typedEndpoint",keys.id(c.sourceEndpoint().structuralKey(),0)));
            var bindings=new JSONArray();int vectorIndex=0;for(var b:phase.bindings())bindings.put(new JSONObject().put("bindingVectorIndex",vectorIndex++).put("canonicalSlotCoordinate",b.coordinate())
                    .put("ordinal",b.ordinal()).put("phase",b.ownerPhase()).put("context",b.ownerContext()).put("path",b.bindingPath())
                    .put("role",b.role()).put("dependencies",b.declaration().dependencies())
                    .put("type",String.valueOf(b.declaration().observationType())).put("domain",String.valueOf(b.declaration().observationDomain())));
            var nodes=new JSONArray();traceNodes(comparison.getMatrixEGraph(),"$",paths,nodes,new int[]{MAX_NODES},0);
            return new JSONObject().put("originalEndpointCommitment",commitment).put("phase",0).put("bindings",bindings)
                    .put("localOwnerCorrespondence",localOwners.json())
                    .put("constructionProofs",proofs).put("callPremises",calls).put("structuralKeys",keys.rows).put("matrixEndpoints",nodes)
                    .put("proofsCheckedInProcess",true).put("constructionIsNotRepair",true).put("wireReplayChecked",false);
        }
        private void traceNodes(EGraphNode n,String path,Map<String,String> paths,JSONArray rows,int[] budget,int depth) {
            supported(--budget[0]>=0&&depth<=MAX_DEPTH,"ENDPOINT_BUDGET");
            var row=new JSONObject().put("fastPath",path).put("certifiedPath",Objects.requireNonNull(paths.get(path)))
                    .put("sourceOccurrenceLineage",n.getSourceOccurrenceLineage()).put("typedCoordinateHead",fastHead(n))
                    .put("sourceName",String.valueOf(n.getSourceName())).put("alphaName",String.valueOf(n.getAlphaName()));
            if(n.getOpcode()==EGraphNode.Opcode.VARIABLE){var v=originalVariables.get(n.getSourceOccurrenceLineage());
                var bindings=new ArrayList<>(qs);bindings.addAll(source.getLocalQuantiVars());
                var owners=bindings.stream().filter(q->q.getName().equals(v.getAlphaName())).toList();require(owners.size()==1,"Original trace owner");var q=owners.get(0);
                row.put("originalAlpha",v.getAlphaName()).put("originalType",v.getExactAlloyType().stableString())
                        .put("originalOwner",q.getBindingPath()).put("originalSlot",q.getDeBruijnKey());}
            rows.put(row);var cs=FastChainGeometry.children(n);for(int i=0;i<cs.size();i++)traceNodes(cs.get(i),path+"/"+i,paths,rows,budget,depth+1);
        }
        int ordinal(String name) { Integer i=ordinals.get(name); require(i!=null,"Foreign outer variable"); return i; }
        int coordinate(String name) {
            int ordinal=ordinal(name);
            // Node.bindingIndex and selected mappings address the binding
            // vector, not the descriptor's independently canonicalized slot.
            for(int i=0;i<phase.bindings().size();i++)if(phase.bindings().get(i).ordinal()==ordinal)return i;
            throw new IllegalArgumentException("Missing original binding vector index");
        }
        String name(int coordinate) { require(coordinate>=0 && coordinate<phase.bindings().size(),"Foreign binding coordinate");return qs.get(phase.bindings().get(coordinate).ordinal()).getName(); }
        String name(DeclarationReplay.Atom atom,boolean certified) {
            var originals=certified?certAtoms:fastAtoms;int ordinal=originals.indexOf(atom);require(ordinal>=0,"Foreign declaration atom");return qs.get(ordinal).getName();
        }
        List<DeclarationReplay.Atom> atoms(JSONArray rows,boolean certified) {
            var originals=certified?certAtoms:fastAtoms;require(rows.length()==originals.size(),"Original declaration coverage");
            var result=new ArrayList<DeclarationReplay.Atom>();var seen=new HashSet<Integer>();
            for(Object item:rows) {
                var row=(JSONObject)item;int found=-1;
                for(int i=0;i<originals.size();i++) if(originals.get(i).json().similar(row)) { require(found<0,"Ambiguous declaration atom");found=i; }
                require(found>=0 && seen.add(found),"Substituted source/type/owner declaration");result.add(originals.get(found));
            }
            return result;
        }
        int[] permutation(List<DeclarationReplay.Atom> rows,boolean certified) {
            int[] result=new int[rows.size()];for(int i=0;i<result.length;i++)result[i]=base.indexOf(ordinal(name(rows.get(i),certified)));
            return result;
        }
        void checkPresentation(int[] order,boolean certified,JSONObject wire) {
            var bindings=new ArrayList<Binding>();var identities=new ArrayList<String>();
            for(int original:base) {
                String name=qs.get(original).getName();int index=coordinate(name);
                bindings.add(phase.bindings().get(index));
                identities.add(certified?Integer.toString(index):name);
            }
            var expected=DeclarationPresentation.evidence(bindings,identities,order);
            require(expected.similar(wire),"Substituted typed presentation/source evidence");
        }
        int[] globalPermutation(int[] order) {
            int[] result=new int[qs.size()];for(int i=0;i<result.length;i++)result[coordinate(qs.get(base.get(i)).getName())]=coordinate(qs.get(base.get(order[i])).getName());return result;
        }
        void checkGlobalPermutation(JSONArray raw) {
            int[] p=ints(raw);require(p.length==qs.size(),"Permutation coverage");
            var descriptor=evidence.phaseBinderDescriptors().get(0);var coordinates=evidence.phaseSourceCoordinates().get(0);
            var map=new LinkedHashMap<TypedSlot,TypedSlot>();for(var slot:descriptor.boundContext())map.put(slot,slot);
            var seen=new HashSet<Integer>();
            for(int i=0;i<p.length;i++) {
                require(p[i]>=0 && p[i]<p.length && seen.add(p[i]),"Nonbijective declaration permutation");
                var from=descriptor.coordinates().get(coordinates.get(ordinal(name(i)))).canonicalSlot();
                var to=descriptor.coordinates().get(coordinates.get(ordinal(name(p[i])))).canonicalSlot();map.put(from,to);
            }
            var permutation=TypedPermutation.of(descriptor.boundContext(),map);
            require(descriptor.automorphisms().contains(permutation),"Unadmitted source permutation");
            CertificateVerifier.verify(descriptor.automorphisms().derivationFor(descriptor,permutation));
        }
        String fastHead(EGraphNode n) {
            if(n.getOpcode()==EGraphNode.Opcode.VARIABLE) {
                var original=originalVariables.get(n.getSourceOccurrenceLineage());require(original!=null,"Missing variable source occurrence");
                require(original.getExactAlloyType()!=null && original.getExactAlloyType().sameOccurrenceEvidenceAs(n.getExactAlloyType()),"Foreign variable type occurrence");
                String name=original.getAlphaName();
                if(ordinals.containsKey(name)) {
                    require(qs.get(ordinal(name)).getExactAlloyType().equals(original.getExactAlloyType()),"Original bound occurrence type");
                    require(!n.hasComparisonLocalCoordinate() && name.equals(n.getAlphaName()),"Outer/local capture");return "VARIABLE/outer:"+coordinate(name);
                }
                var locals=source.getLocalQuantiVars().stream().filter(q->q.getName().equals(name)).toList();
                require(locals.size()==1 && locals.get(0).getExactAlloyType().equals(original.getExactAlloyType()),"Missing local owner/type");String key=locals.get(0).getDeBruijnKey();
                var matcher=java.util.regex.Pattern.compile("local#([0-9]+)#([0-9]+):.*").matcher(key);
                require(matcher.matches(),"Local scope coordinate");String lexical=matcher.group(1)+":"+matcher.group(2);
                require(n.hasComparisonLocalCoordinate() && ("@fast-local:"+lexical).equals(n.getAlphaName()),"Local owner coordinate capture");
                return "VARIABLE/local:"+lexical;
            }
            String identity=switch(n.getOpcode()) {
                case CALL -> {require(scopedCalls.contains(n)||closedCalls.admits(n)||boundCalls.admits(n),"Foreign original call");yield CallMetadata.semanticKey(n);}
                case GLOBALBINDING -> n.getSemanticIdentity()==null?n.getSourceName():n.getSemanticIdentity();
                case CONSTANT -> {
                    // Only a nonidentity label translation needs this original
                    // atom premise. Constructed Boolean constants already use
                    // the same label in both paths and keep their proof route.
                    if(n.getSemanticIdentity()!=null && !n.getSemanticIdentity().equals(n.getSourceName())) {
                      var original=originalConstants.get(n.getSourceOccurrenceLineage());
                      require(original!=null && Objects.equals(original.getSourceName(),n.getSourceName())
                            && Objects.equals(original.getSemanticIdentity(),n.getSemanticIdentity())
                            && original.getMetatype()==n.getMetatype()
                            && original.getExactAlloyType()!=null && original.getExactAlloyType().sameOccurrenceEvidenceAs(n.getExactAlloyType()),
                            "Foreign constant source/type/identity");
                    }
                    // RepairProjection's constant payload uses the source atom,
                    // while Fast's cost label retains its semantic identity.
                    yield n.getSourceName()==null?"":n.getSourceName();
                }
                default -> "";
            };
            supported(n.getOpcode()==EGraphNode.Opcode.GENERICRELDECL || n.getMetatype()==EGraphNode.Metatype.BOOLEAN
                    || n.getExactAlloyType()!=null,"LITERAL_TYPE_AUTHORITY_UNSUPPORTED");
            GraphType type=n.getOpcode()==EGraphNode.Opcode.GENERICRELDECL?null:
                    n.getMetatype()==EGraphNode.Metatype.BOOLEAN?GraphType.BOOL:AlloyTypeBridge.graphType(n.getExactAlloyType());
            String payload="identity="+identity+(n.getOpcode()==EGraphNode.Opcode.GENERICRELDECL?";structure=certified-local-declaration":";type="+type);
            supported(payload.length()<=16384,"HEAD_BUDGET");return n.getOpcode()+"/"+payload;
        }
    }

    /** DAG encoding avoids exponentially expanding repeated certificate keys. */
    private static final class TraceKeys {
        private record Shape(String tag,List<String> scalars,List<Integer> children) { }
        final IdentityHashMap<StructuralKey,Integer> ids=new IdentityHashMap<>();
        final Map<Shape,Integer> intern=new HashMap<>();
        final JSONArray rows=new JSONArray();long bytes,units,visits;
        int id(StructuralKey key,int depth) {
            var old=ids.get(key);if(old!=null)return old;
            supported(depth<=128&&++visits<=120_000,"RECEIPT_BUDGET");
            var children=new ArrayList<Integer>();for(var c:key.children())children.add(id(c,depth+1));
            var shape=new Shape(key.tag(),key.scalars(),List.copyOf(children));
            old=intern.get(shape);if(old!=null){ids.put(key,old);return old;}
            units+=1L+key.children().size()+key.scalars().size();supported(units<=120_000,"RECEIPT_BUDGET");
            bytes+=key.tag().length();for(var s:key.scalars())bytes+=s.length();
            supported(bytes<=4_000_000,"RECEIPT_BUDGET");
            int id=intern.size();intern.put(shape,id);ids.put(key,id);
            rows.put(new JSONObject().put("id",id).put("tag",key.tag()).put("scalars",key.scalars()).put("children",children));return id;
        }
    }

    private static String certHead(Node n) {
        if(n.isVariable()) {
            if(n.bindingIndex()>=0)return "VARIABLE/outer:"+n.bindingIndex();
            String local=n.lexicalVariable();supported(local!=null && local.matches("@local\\[[0-9]+:[0-9]+\\]"),"LOCAL_LAYOUT_UNSUPPORTED");
            return "VARIABLE/local:"+local.substring(7,local.length()-1);
        }
        return n.operator()+"/"+n.semanticPayload();
    }
    private static void projectedCensus(Node n,int[] budget,int depth) {
        supported(n!=null && --budget[0]>=0 && depth<=MAX_DEPTH && n.children().size()<=64,"PROJECTED_ENDPOINT_BUDGET");
        // Variable identity lives in bindingIndex/lexicalVariable, not payload.
        supported(n.semanticPayload()==null || n.semanticPayload().length()<=16384,"HEAD_BUDGET");
        for(var c:n.children())projectedCensus(c,budget,depth+1);
        for(var c:n.certifiedAlternatives())projectedCensus(c,budget,depth+1);
    }
    private static void selectRepresentatives(Endpoint a,Endpoint b,JSONObject report) {
        var cert=report.getJSONObject("certified");
        if(!cert.has("phases"))return; // verify() reports the missing capture.
        var phases=cert.getJSONArray("phases");require(phases.length()==1,"Phase coverage");
        var edit=phases.getJSONObject(0).getJSONObject("receipt").getJSONObject("derivation");
        select(a,a.phase.matrix(),edit,true,"$",new int[]{MAX_NODES},0);
        select(b,b.phase.matrix(),edit,false,"$",new int[]{MAX_NODES},0);
    }
    private static void select(Endpoint e,Node n,JSONObject d,boolean left,String path,int[] budget,int depth) {
        supported(--budget[0]>=0&&depth<=MAX_DEPTH,"SELECTION_BUDGET");
        if(d.getString("kind").equals("select")) {
            int index=d.getInt(left?"leftAlternative":"rightAlternative");var alternatives=n.certifiedAlternatives();
            require(alternatives.isEmpty()?index==-1:index>=0&&index<alternatives.size(),"Foreign selected representative");
            Node chosen=index<0?n:alternatives.get(index);
            if(index>=0){e.selectedNodes.put(path,chosen);e.selections.put(new JSONObject().put("path",path)
                    .put("alternative",index).put("constructionCost",0).put("remainingEditCost",d.getInt("cost"))
                    .put("beforeHead",certHead(n)).put("afterHead",certHead(chosen)));}
            select(e,chosen,d.getJSONObject("selected"),left,path,budget,depth+1);return;
        }
        if(!d.getString("kind").equals("node"))return;
        var seen=new HashSet<Integer>();
        for(Object value:d.getJSONArray("children")) {
            var edge=(JSONObject)value;int i=edge.getInt(left?"leftIndex":"rightIndex");if(i<0)continue;
            require(i<n.children().size()&&seen.add(i),"Selected representative child coverage");
            select(e,n.children().get(i),edge.getJSONObject("edit"),left,path+"/"+i,budget,depth+1);
        }
        require(seen.size()==n.children().size(),"Selected representative missing child");
    }
    private static Map<String,String> bridge(Endpoint e) {
        var paths=new LinkedHashMap<String,String>();var fk=new IdentityHashMap<EGraphNode,String>();var ck=new HashMap<String,String>();
        fastKey(e.comparison.getMatrixEGraph(),e,fk);certKey(e.phase.matrix(),"$",e,ck);
        correspond(e.comparison.getMatrixEGraph(),e.phase.matrix(),e,"$","$",paths,fk,ck);return paths;
    }
    private static String fastKey(EGraphNode n,Endpoint e,Map<EGraphNode,String> keys) {
        if(keys.containsKey(n))return keys.get(n);var children=new ArrayList<String>();for(var c:FastChainGeometry.children(n))children.add(fastKey(c,e,keys));
        if(n.isOrderInsensitive())Collections.sort(children);String key=hash(new JSONArray().put(e.fastHead(n)).put(children).toString());keys.put(n,key);return key;
    }
    private static String certKey(Node raw,String path,Endpoint e,Map<String,String> keys) {
        Node n=e.selectedNodes.getOrDefault(path,raw);var children=new ArrayList<String>();
        for(int i=0;i<n.children().size();i++)children.add(certKey(n.children().get(i),path+"/"+i,e,keys));
        if(n.orderInsensitive())Collections.sort(children);String key=hash(new JSONArray().put(certHead(n)).put(children).toString());keys.put(path,key);return key;
    }
    private static void correspond(EGraphNode a,Node raw,Endpoint e,String ap,String bp,Map<String,String> paths,Map<EGraphNode,String> fk,Map<String,String> ck) {
        Node b=e.selectedNodes.getOrDefault(bp,raw);
        var ac=FastChainGeometry.children(a);var bc=b.children();
        var boundary=new JSONObject().put("fastPath",ap).put("certifiedPath",bp).put("fastHead",e.fastHead(a))
                .put("certifiedHead",certHead(b)).put("fastChildren",ac.size()).put("certifiedChildren",bc.size());
        try {
        supported(e.fastHead(a).equals(certHead(b)) && a.isOrderInsensitive()==b.orderInsensitive(),"ENDPOINT_REPRESENTATION_UNSUPPORTED");
        paths.put(ap,bp);supported(ac.size()==bc.size(),"ENDPOINT_REPRESENTATION_UNSUPPORTED");
        var used=new HashSet<Integer>();
        for(int i=0;i<ac.size();i++) {
            int j=i;
            if(a.isOrderInsensitive()) {
                var candidates=new ArrayList<String>();for(int k=0;k<bc.size();k++)candidates.add(ck.get(bp+"/"+k));
                boundary.put("unmappedFastChild",i).put("fastChildHead",e.fastHead(ac.get(i)))
                        .put("certifiedChildHeads",new JSONArray(bc.stream().map(NativeOperationComparison::certHead).toList()));
                j=uniqueOccurrence(candidates,fk.get(ac.get(i)),used);
            }
            require(used.add(j),"Noninjective endpoint bridge");correspond(ac.get(i),bc.get(j),e,ap+"/"+i,bp+"/"+j,paths,fk,ck);
        }
        }catch(Unsupported gap){if(gap.boundary==null)gap.boundary=boundary;throw gap;}
    }
    static int uniqueOccurrence(List<String> candidates,String key,Set<Integer> used) {
        supported(candidates.size()<=64,"CHILD_BUDGET");int found=-1,count=0;
        for(int i=0;i<candidates.size();i++)if(!used.contains(i) && key.equals(candidates.get(i))){found=i;count++;}
        supported(count==1,count>1?"AMBIGUOUS_OCCURRENCE_BIJECTION":"ENDPOINT_REPRESENTATION_UNSUPPORTED");return found;
    }
    private static void flatten(JSONObject d,String a,String b,Map<String,String> ap,Map<String,String> bp,JSONArray out,int[] budget,int depth) {
        require(--budget[0]>=0 && depth<=MAX_DEPTH,"ACTION_BUDGET");String kind=d.getString("kind");
        // The independently checked selection is retained separately as a
        // construction link. It does not delete or reorder a repair action.
        if(kind.equals("select")){flatten(d.getJSONObject("selected"),a,b,ap,bp,out,budget,depth+1);return;}
        supported(List.of("node","insert","delete").contains(kind),"ACTION_KIND_UNSUPPORTED");
        out.put(new JSONObject().put("stage","matrix").put("kind",kind).put("cost",d.getInt(kind.equals("node")?"headCost":"cost"))
                .put("source",a==null?JSONObject.NULL:Objects.requireNonNull(ap.get(a))).put("target",b==null?JSONObject.NULL:Objects.requireNonNull(bp.get(b))));
        if(kind.equals("node"))for(Object item:d.getJSONArray("children")) {
            var c=(JSONObject)item;int i=c.getInt("leftIndex"),j=c.getInt("rightIndex");
            flatten(c.getJSONObject("edit"),i<0?null:a+"/"+i,j<0?null:b+"/"+j,ap,bp,out,budget,depth+1);
        }
    }
    private static Map<String,String> identity(Map<String,String> paths) { var out=new HashMap<String,String>();for(var p:paths.values())out.put(p,p);return out; }
    private static boolean identity(int[] p) { for(int i=0;i<p.length;i++)if(p[i]!=i)return false;return true; }
    private static int[] ints(JSONArray a) { int[] result=new int[a.length()];for(int i=0;i<result.length;i++){require(a.get(i) instanceof Integer,"Noninteger coordinate");result[i]=a.getInt(i);}return result; }
    private static Map<String,String> stringMapping(JSONObject raw) { var m=new TreeMap<String,String>();for(var key:raw.keySet())m.put(key,raw.getString(key));return m; }
    private static Map<String,String> stringValues(JSONObject raw) { var m=new TreeMap<String,String>();for(var key:raw.keySet())m.put(key,String.valueOf(raw.get(key)));return m; }
    private static Map<String,String> coordinateMap(Endpoint a,Endpoint b,Map<String,String> raw) {var m=new TreeMap<String,String>();for(var e:raw.entrySet())m.put(a.name(Integer.parseInt(e.getKey())),b.name(Integer.parseInt(e.getValue())));return m;}
    private static Map<String,String> certMapping(Endpoint a,Endpoint b,JSONArray raw) {require(raw.length()==a.qs.size(),"Matrix map coverage");var m=new TreeMap<String,String>();int[] values=ints(raw);for(int i=0;i<values.length;i++){require(values[i]>=-1,"Matrix map index");if(values[i]>=0)m.put(a.name(i),b.name(values[i]));}return m;}
    private static void validateMapping(Endpoint a,Endpoint b,Map<String,String> map) {require(a.ordinals.keySet().containsAll(map.keySet()) && b.ordinals.keySet().containsAll(map.values()) && new HashSet<>(map.values()).size()==map.size(),"Foreign or noninjective binding map");}
    private static int[] indices(Endpoint a,Endpoint b,Map<String,String> map) {int[] out=new int[a.qs.size()];Arrays.fill(out,-1);for(var e:map.entrySet())out[a.coordinate(e.getKey())]=b.coordinate(e.getValue());return out;}
    private static String hash(String text) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void boundedJson(Object value,long[] budget,int depth) {
        supported(depth<=128 && --budget[0]>=0,"RECEIPT_BUDGET");
        if(value instanceof JSONObject object)for(String key:object.keySet()){budget[1]-=key.length();boundedJson(object.get(key),budget,depth+1);}
        else if(value instanceof JSONArray array)for(Object item:array)boundedJson(item,budget,depth+1);
        else if(value instanceof String text)budget[1]-=text.length();
        supported(budget[1]>=0,"RECEIPT_BUDGET");
    }
}
