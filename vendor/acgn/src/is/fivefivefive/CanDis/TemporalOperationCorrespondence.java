package is.fivefivefive.CanDis;

import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.metric.RepairView.*;
import is.fivefivefive.CanDis.theory.*;

/** Original-owner transport of selected native programs. Construction and repair
 * remain separate. No distance search is performed by this correspondence. */
final class TemporalOperationCorrespondence {
    static final class Missing extends RuntimeException {
        final JSONObject boundary;
        Missing(String stage,String path,String detail) {
            super("TEMPORAL_CORRESPONDENCE_MISSING");
            boundary=new JSONObject().put("stage",stage).put("path",path).put("detail",detail);
        }
    }
    private static void need(boolean ok,String stage,String path,String detail) {
        if(!ok)throw new Missing(stage,path,detail);
    }
    private static void require(boolean ok,String why){if(!ok)throw new IllegalArgumentException(why);}
    static boolean temporal(Canonical.Prepared p) {
        return p.normalizedForms().size()!=1||!p.normalizedForms().get(0).getTemporalChildren().isEmpty()
                ||p.normalizedForms().get(0).getTemporalOp()!=NormalForm.TemporalOp.NONE;
    }
    private record Pair(EGraphNode fast,Node certified,JSONObject token) { }
    static JSONObject localVariable(LocalOwnerCorrespondence owners,EGraphNode fast,Node cert) {
        var q=owners.originalBinding(fast);var coordinate=LocalOwnerCorrespondence.coordinate(q);
        require(cert.isVariable()&&cert.bindingIndex()==-1
                &&("@local["+coordinate+"]").equals(cert.lexicalVariable()),"Original temporal local lexical coordinate");
        require(fast.hasComparisonLocalCoordinate()?("@fast-local:"+coordinate).equals(fast.getAlphaName())
                :q.getName().equals(fast.getAlphaName()),"Original temporal local Fast coordinate");
        require(q.getExactAlloyType()!=null&&q.getExactAlloyType().equals(fast.getExactAlloyType())
                &&q.getExactAlloyType().sameOccurrenceEvidenceAs(fast.getExactAlloyType())
                &&AlloyTypeBridge.graphType(q.getExactAlloyType()).equals(cert.observationType()),"Original temporal local type authority");
        return new JSONObject().put("bindingPath",q.getBindingPath()).put("lexicalCoordinate",coordinate)
                .put("sourceOccurrence",fast.getSourceOccurrenceLineage()).put("type",q.getExactAlloyType().stableString())
                .put("kind",q.getQuantifier()).put("cardinality",q.getCardinality()).put("disjointness",q.getDisjointnessClass());
    }
    private static final class Endpoint {
        final Canonical.Prepared original;
        final List<NormalForm> forms;
        final RepairView view;
        final CanonicalAlloyPipeline.Prepared certifiedSource;
        final List<JSONObject> coordinates=new ArrayList<>();
        final List<Map<String,Pair>> nodes=new ArrayList<>();
        final List<LocalOwnerCorrespondence> localOwners=new ArrayList<>();
        final TraceResources resources=new TraceResources();
        Endpoint(Canonical.Prepared original,JSONObject arm,String side) {
            this.original=original;forms=CanonicalDistance.prepare(original.normalizedForms()).comparisonForms();
            certifiedSource=CanonicalAlloyPipeline.prepare(original);view=certifiedSource.repairView();
            require(view.producerObservationDigest().equals(arm.getString(side+"ProducerObservationDigest")),"Foreign temporal construction/source digest");
            var construction=arm.getJSONObject("constructionDerivations");
            need(construction.getBoolean("retained"),"construction extraction",side,"Native path is executable but its construction derivation was not retained");
            require(construction.getJSONObject(side).getString("projectionObservationDigest").equals(view.producerObservationDigest()),"Temporal construction/repair link");
            var indexed=new TreeMap<Integer,JSONObject>();
            for(Object item:arm.getJSONObject("selectedTemporalReferencePayments").getJSONArray(side))
                for(Object value:((JSONObject)item).getJSONArray("typedBranches")) {
                    var row=(JSONObject)value;var old=indexed.putIfAbsent(row.getInt("ordinal"),row);
                    require(old==null||old.similar(row),"Ambiguous original temporal phase locator");
                }
            need(indexed.size()==view.phases().size()&&forms.size()==view.phases().size(),"phase coverage",side,"Original and projected phase enumeration differ");
            for(int i=0;i<forms.size();i++) {
                var row=indexed.get(i);require(row!=null,"Missing typed phase coordinate");var source=forms.get(i).comparisonSource();
                require(original.normalizedForms().stream().anyMatch(f->f==source),"Foreign comparison phase source");
                require(row.getString("sourcePath").equals(source.getPhasePath())&&row.getString("kind").equals(source.getTemporalOp().name()),"Original typed phase/source correspondence");
                coordinates.add(new JSONObject().put("sourcePath",source.getPhasePath()).put("typedRole",source.getTemporalOp().name()));
            }
            for(int i=0;i<forms.size();i++) {
                var map=new LinkedHashMap<String,Pair>();nodes.add(map);
                localOwners.add(LocalOwnerCorrespondence.check(forms.get(i).comparisonSource(),forms.get(i).getMatrixEGraph()));
                node(forms.get(i).getMatrixEGraph(),view.phases().get(i).matrix(),i,"root",map);
            }
        }
        private void node(EGraphNode fast,Node cert,int phase,String path,Map<String,Pair> map) {
            resources.spend(192L+2L*path.length());
            need(fast!=null&&cert!=null&&fast.getOpcode().name().equals(cert.operator()),"matrix endpoint",phase+":"+path,"Unpaired concrete operator/representation");
            var form=forms.get(phase);var token=new JSONObject().put("operator",cert.operator()).put("phase",coordinates.get(phase));
            if(cert.isVariable()&&cert.bindingIndex()<0) {
                token.put("localBinding",localVariable(localOwners.get(phase),fast,cert));
            } else if(cert.isVariable()) {
                var bindings=view.phases().get(phase).bindings();
                need(cert.bindingIndex()>=0&&cert.bindingIndex()<bindings.size(),"variable occurrence",phase+":"+path,"Lexical binding transport needs its retained local owner");
                var binding=bindings.get(cert.bindingIndex());var originals=new ArrayList<QuantiVar>();
                for(var group:List.of(form.getParams(),form.getMatrixQuantiVars(),form.getInheritedQuantiVars(),form.getLocalQuantiVars()))originals.addAll(group);
                var matches=Collections.newSetFromMap(new IdentityHashMap<QuantiVar,Boolean>());
                originals.stream().filter(q->q.getBindingPath().equals(binding.bindingPath())
                        &&(q.getName().equals(fast.getAlphaName())||q.getName().equals(fast.getSourceName())||q.getOriginalNames().contains(fast.getSourceName()))).forEach(matches::add);
                need(matches.size()==1,"variable owner",phase+":"+path,"Original binding occurrence is absent or ambiguous");var q=matches.iterator().next();
                require(q.getExactAlloyType()!=null&&q.getExactAlloyType().equals(fast.getExactAlloyType()),"Temporal variable original exact type");
                require(AlloyTypeBridge.graphType(q.getExactAlloyType()).equals(cert.observationType()),"Temporal variable literal type");
                var carrier=q.getExactAlloyType().relationArity()>1?AlloyTypeBridge.graphType(q.getExactAlloyType()):
                        "int".equalsIgnoreCase(q.getTypeName())?GraphType.INT:GraphType.constructor("AlloyCarrier",List.of(GraphType.constructor(q.getTypeName())));
                require(carrier.equals(binding.declaration().observationType()),"Original binder carrier type");
                require(binding.declaration().quantifier().equals(q.getQuantifier().name())&&binding.declaration().cardinality().equals(q.getCardinality().name())
                        &&binding.declaration().disjointnessClass()==q.getDisjointnessClass(),"Temporal original binder tuple");
                token.put("bindingPath",q.getBindingPath()).put("type",q.getExactAlloyType().stableString())
                        .put("dependencies",binding.declaration().dependencies()).put("certifiedDomain",binding.declaration().certifiedDomain());
                if(binding.ownerPhase()>=0) {
                    require(binding.ownerPhase()<forms.size(),"Foreign original binding phase");
                    var owner=forms.get(binding.ownerPhase());
                    require(owner.getMatrixQuantiVars().stream().anyMatch(v->v==q)||owner.getLocalQuantiVars().stream().anyMatch(v->v==q)
                            ||owner.getParams().stream().anyMatch(v->v==q),"Binding owner must retain the actual original variable");
                    token.put("owner",coordinates.get(binding.ownerPhase()));
                }
            } else {
                GraphType type=fast.getOpcode()==EGraphNode.Opcode.GENERICRELDECL?null:
                        fast.getMetatype()==EGraphNode.Metatype.BOOLEAN?GraphType.BOOL:AlloyTypeBridge.graphType(Objects.requireNonNull(fast.getExactAlloyType()));
                require(Objects.equals(type,cert.observationType()),"Temporal original literal result type");
                token.put("type",String.valueOf(type)).put("semanticPayload",String.valueOf(cert.semanticPayload()));
                if(cert.isTemporalReference()) {
                    require(Objects.equals(fast.getSourceName(),cert.payload()),"Original temporal reference slot");
                    var matcher=java.util.regex.Pattern.compile("temporal\\[(\\d+):(\\d+)\\]").matcher(fast.getSourceName());
                    require(matcher.matches(),"Malformed retained temporal reference");int start=Integer.parseInt(matcher.group(1)),count=Integer.parseInt(matcher.group(2));
                    require(count==cert.observationPhases().size()&&start>=0&&start+count<=form.getTemporalChildren().size(),"Original temporal branch tuple");
                    var branches=new JSONArray();
                    for(int j=0;j<count;j++) {
                        int at=cert.observationPhases().get(j);require(at>=0&&at<forms.size(),"Foreign referenced phase");
                        var actual=form.getTemporalChildren().get(start+j);var mapped=forms.get(at).comparisonSource();
                        require(actual.comparisonSource()==mapped&&actual.getTemporalOp()==mapped.getTemporalOp(),"Original reference owner/typed branch identity");
                        branches.put(coordinates.get(at));
                    }
                    token.put("branches",branches).put("temporalOperator",cert.observationTemporalOperator());
                } else if(fast.getOpcode()==EGraphNode.Opcode.GLOBALBINDING||fast.getOpcode()==EGraphNode.Opcode.CONSTANT)
                    require(Objects.equals(fast.getSourceName(),cert.payload()),"Original temporal atom identity");
                else if(fast.getOpcode()==EGraphNode.Opcode.CALL) {
                    require(cert.semanticPayload().equals("identity="+CallMetadata.semanticKey(fast)+";type="+type),"Original call premise identity/type");
                    var metadata=CallMetadata.require(fast);
                    var calls=certifiedSource.semanticArtifact().callOccurrenceCertificates().stream()
                            .filter(p->p.occurrenceId()==metadata.occurrenceId()).toList();
                    require(calls.size()==1,"Original temporal call source occurrence");var premise=calls.get(0);
                    require(premise.qualifiedCallee().equals(metadata.identity())&&premise.sourceName().equals(metadata.sourceName())
                            &&premise.kind().equals(metadata.kind())&&premise.declaredArity()==metadata.arity()
                            &&premise.arityAuthority()==metadata.authority()&&premise.sourceEndpoint().operator().outputType().equals(type),"Original temporal call certificate premise");
                    token.put("callSourcePath",premise.sourcePath()).put("callIdentity",metadata.identity());
                }
            }
            var children=FastChainGeometry.children(fast);
            need(children.size()==cert.children().size(),"matrix children",phase+":"+path,"Unpaired selected child representation");
            need(cert.certifiedAlternatives().isEmpty(),"selected representative",phase+":"+path,"Retained alternative needs its selected original occurrence transport");
            map.put(path,new Pair(fast,cert,token));
            for(int i=0;i<children.size();i++)node(children.get(i),cert.children().get(i),phase,path+"/child/"+i,map);
        }
        JSONArray localOwnerEvidence() {
            var out=new JSONArray();
            for(int i=0;i<localOwners.size();i++)out.put(new JSONObject().put("phase",coordinates.get(i)).put("correspondence",localOwners.get(i).json()));
            return out;
        }
        JSONObject occurrence(int phase,String path,JSONObject payload,boolean certified) {
            need(phase>=0&&phase<nodes.size()&&nodes.get(phase).containsKey(path),"selected matrix address",phase+":"+path,"Selected representation address not linked to original endpoint");
            var pair=nodes.get(phase).get(path);var nativeEvidence=new JSONObject(payload.getString("nativeEvidence"));
            if(!certified)require(nativeEvidence.getLong("sourceOccurrenceLineage")==pair.fast.getSourceOccurrenceLineage()
                    &&nativeEvidence.getString("alphaName").equals(String.valueOf(pair.fast.getAlphaName()))
                    &&nativeEvidence.getString("exactType").equals(String.valueOf(pair.fast.getExactAlloyType()==null?null:pair.fast.getExactAlloyType().stableString())),"Foreign Fast original source occurrence/type");
            else require(nativeEvidence.getString("semanticPayload").equals(String.valueOf(pair.certified.semanticPayload()))
                    &&nativeEvidence.getInt("bindingIndex")==pair.certified.bindingIndex()
                    &&nativeEvidence.getString("observationType").equals(String.valueOf(pair.certified.observationType())),"Foreign projected selected endpoint");
            return new JSONObject(pair.token.toString()).put("path",path);
        }
        JSONObject atom(JSONObject atom,boolean certified) {
            int phase,ordinal;List<QuantiVar> qs;
            if(certified) {
                var c=new JSONArray(atom.getString("occurrence"));phase=c.getInt(1);ordinal=c.getInt(4);
                need(phase>=0&&phase<forms.size(),"declaration coordinate",atom.toString(),"Parameter coordinate requires retained parameter transport");
                qs=c.getBoolean(0)?forms.get(phase).getParams():forms.get(phase).getMatrixQuantiVars();
            } else {
                var parts=atom.getString("occurrence").split("/");require(parts.length==4&&parts[0].equals("phase"),"Original declaration address");
                phase=Integer.parseInt(parts[1]);ordinal=Integer.parseInt(parts[3]);qs=forms.get(phase).getMatrixQuantiVars();
            }
            require(ordinal>=0&&ordinal<qs.size(),"Original declaration ordinal");var q=qs.get(ordinal);
            var tuple=new JSONArray().put(q.getQuantifier().name()).put(q.getTypeName()).put(q.getCardinality().name()).put(Integer.toString(q.getDisjointnessClass()));
            require(tuple.similar(atom.getJSONArray("tuple")),"Original declaration type/quantifier tuple");
            return new JSONObject().put("phase",coordinates.get(phase)).put("bindingPath",q.getBindingPath()).put("tuple",tuple).put("type",q.getExactAlloyType().stableString());
        }
    }
    static JSONObject compare(Canonical.Prepared a,Canonical.Prepared b,NativeRepairTrace fast,NativeRepairTrace certified,JSONObject report) {
        // Replay actual immutable selections before any correspondence refusal.
        var fa=report.getJSONObject("fast");var ca=report.getJSONObject("certified");
        replay(fast,fa);replay(certified,ca);
        var left=new Endpoint(a,ca,"source");var right=new Endpoint(b,ca,"target");
        var x=blocks(fa,false,left,right);var y=blocks(ca,true,left,right);
        int first=0;while(first<Math.min(x.length(),y.length())) {
            var u=x.getJSONObject(first);var v=y.getJSONObject(first);
            if(!u.getString("component").equals(v.getString("component"))||!same(u.get("effect"),v.get("effect")))break;first++;
        }
        var out=new JSONObject().put("diagnostic",report).put("fastBlocks",x).put("certifiedBlocks",y)
                .put("endpointCorrespondenceChecked",true).put("sourceCertificatesChecked",true).put("fixedChoiceReplaysChecked",true)
                .put("allNativeEntriesRetained",true).put("literalOneToOneOperationLists",false)
                .put("constructionIsNotRepair",true).put("sourcePhases",left.coordinates).put("targetPhases",right.coordinates);
        out.put("sourceLocalOwners",left.localOwnerEvidence()).put("targetLocalOwners",right.localOwnerEvidence());
        boolean equal=first==x.length()&&first==y.length();out.put("orderedBlockEffectsEqual",equal);
        if(!equal)out.put("firstDifference",first).put("fastFirst",first<x.length()?x.get(first):JSONObject.NULL).put("certifiedFirst",first<y.length()?y.get(first):JSONObject.NULL);
        return out;
    }
    /** Bind a diagnostic arm to its immutable selection, independently of phase shape. */
    static void replay(NativeRepairTrace trace,JSONObject supplied) {
        require(trace!=null,"Missing immutable native selection");
        var nativeProgram=new JSONObject(supplied.toString());
        if(nativeProgram.has("diagnosticStatus"))require(nativeProgram.remove("diagnosticStatus").equals("MATRIX_REPLAY_CHECKED"),"Foreign native diagnostic marker");
        trace.replay(nativeProgram);
    }
    private static boolean same(Object a,Object b){return a instanceof JSONObject x?x.similar(b):a instanceof JSONArray x?x.similar(b):Objects.equals(a,b);}
    private static JSONObject at(JSONObject trace,String path) {
        Object value=trace;for(String key:path.substring(1).split("/"))value=value instanceof JSONObject x?x.get(key):((JSONArray)value).get(Integer.parseInt(key));return (JSONObject)value;
    }
    private static JSONArray blocks(JSONObject trace,boolean certified,Endpoint a,Endpoint b) {
        var result=new JSONArray();JSONObject block=null;int total=0;var seen=new HashSet<String>();
        for(Object value:trace.getJSONArray("orderedNativeOperations")) {
            var row=(JSONObject)value;String component=row.getString("component"),path=row.getString("payloadPath");var op=at(trace,path);
            require(seen.add(path)&&row.getInt("cost")==op.getInt("cost"),"Ordered payload/charge coverage");total=Math.addExact(total,row.getInt("cost"));
            if(block==null||!block.getString("component").equals(component)) {
                block=new JSONObject().put("component",component).put("entries",new JSONArray())
                        .put("effect",component.equals("declaration-representation")?new JSONObject().put("source",new JSONArray()).put("target",new JSONArray()):new JSONArray());result.put(block);
            }
            var entry=new JSONObject().put("nativeIndex",seen.size()-1).put("row",row).put("payload",op);block.getJSONArray("entries").put(entry);
            if(component.equals("declaration-representation")) {
                require(op.getInt("cost")==0,"Declaration representation charge");
                if(op.getString("kind").equals("metric-coordinate-order")) {
                    String side=op.getString("side");Endpoint endpoint=side.equals("source")?a:b;
                    for(Object atom:trace.getJSONArray("nativeDeclarationPrograms").getJSONObject(op.getInt("component")).getJSONArray(side))
                        block.getJSONObject("effect").getJSONArray(side).put(endpoint.atom((JSONObject)atom,certified));
                    entry.put("effectPremise","Selected original typed atom order; empty reads retained");
                } else {
                    var selection=op.getJSONObject("selection");
                    for(String side:List.of("left","right")) {
                        var permutation=selection.optJSONArray(side);
                        need(permutation!=null,"declaration representation",path,"Selected grouped exchange needs its original coordinate effect");
                        for(int i=0;i<permutation.length();i++)need(permutation.getInt(i)==i,"declaration representation",path,"Actual nonidentity exchange needs its source-linked effect correspondence");
                    }
                    entry.put("effectPremise","Independently replayed selected identity exchange; native operation retained");
                }
                continue;
            }
            Object effect=op;
            switch(component) {
                case "matrix" -> {
                    int phase=Integer.parseInt(path.split("/")[2]);
                    effect=new JSONObject().put("phase",phase).put("kind",op.isNull("source")?"insert":op.isNull("target")?"delete":op.getInt("cost")==0?"zero-transport":"update")
                            .put("cost",op.getInt("cost")).put("source",op.isNull("source")?JSONObject.NULL:a.occurrence(phase,op.getString("source"),op.getJSONObject("before"),certified))
                            .put("target",op.isNull("target")?JSONObject.NULL:b.occurrence(phase,op.getString("target"),op.getJSONObject("after"),certified));
                }
                case "declaration" -> {
                    var step=new JSONObject(op.toString());
                    if(!step.isNull("source"))step.put("source",a.atom(step.getJSONObject("source"),certified));
                    if(!step.isNull("target"))step.put("target",b.atom(step.getJSONObject("target"),certified));
                    if(step.getInt("cost")==0)step.put("payloadAction","checked-original-coordinate-transport");effect=step;
                }
                case "phase-representation" -> {
                    String side=op.getString("side");var endpoint=side.equals("source")?a:b;
                    require(op.getInt("cost")==0,"Phase representation charge");var rows=op.getJSONArray("phases");require(rows.length()==endpoint.forms.size(),"Phase reindex coverage");
                    for(int i=0;i<rows.length();i++) {
                        var r=rows.getJSONObject(i);int old=r.getInt("originalPhase");
                        require(old>=0&&old<endpoint.original.normalizedForms().size()&&endpoint.forms.get(i).comparisonSource()==endpoint.original.normalizedForms().get(old)
                                &&r.getInt("comparisonPhase")==i&&r.getString("sourcePath").equals(endpoint.coordinates.get(i).getString("sourcePath"))
                                &&r.getString("typedRole").equals(endpoint.coordinates.get(i).getString("typedRole")),"Original phase representation occurrence/role");
                    }
                    if(certified)require(trace.getJSONObject("constructionDerivations").getJSONObject(side).getJSONObject("projectionPhaseRepresentation").similar(op),"Projection construction/reindex link");
                }
                case "temporal", "temporal-read" -> { }
                default -> throw new Missing("ordered representation",path,"Concrete selected step has no checked cross-arm effect: "+component);
            }
            block.getJSONArray("effect").put(effect);
        }
        require(total==trace.getInt("distance"),"All ordered charges retained");return result;
    }
}
