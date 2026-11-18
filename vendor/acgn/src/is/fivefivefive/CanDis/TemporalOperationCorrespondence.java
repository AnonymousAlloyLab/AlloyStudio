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
    private static void require(boolean ok,String why){if(!ok)throw new ReplayValidationException(why);}
    static boolean temporal(Canonical.Prepared p) {
        return p.normalizedForms().size()!=1||!p.normalizedForms().get(0).getTemporalChildren().isEmpty()
                ||p.normalizedForms().get(0).getTemporalOp()!=NormalForm.TemporalOp.NONE;
    }
    private record Pair(EGraphNode fast,Node certified,JSONObject token) { }
    /** Only identity presentations qualify; a real automorphism needs its own transport. */
    static boolean samePrimaryBody(Node a,Node b,TraceResources resources) {
        resources.spend(192);
        if(!a.operator().equals(b.operator())||!Objects.equals(a.payload(),b.payload())
                ||!Objects.equals(a.semanticPayload(),b.semanticPayload())||a.bindingIndex()!=b.bindingIndex()
                ||!Objects.equals(a.lexicalVariable(),b.lexicalVariable())||a.containerKind()!=b.containerKind()
                ||a.orderInsensitive()!=b.orderInsensitive()||!Objects.equals(a.observationType(),b.observationType())
                ||!a.observationPhases().equals(b.observationPhases())
                ||!Objects.equals(a.observationTemporalOperator(),b.observationTemporalOperator())
                ||a.originalSourceOccurrence()!=b.originalSourceOccurrence()
                ||a.originalReferenceOrigin()!=b.originalReferenceOrigin()||a.children().size()!=b.children().size())return false;
        for(int i=0;i<a.children().size();i++)if(!samePrimaryBody(a.children().get(i),b.children().get(i),resources))return false;
        return true;
    }
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
        final is.fivefivefive.CanDis.theory.CertifiedSemanticArtifact certifiedSource;
        final PhaseIdentityJoin phaseJoin;
        final List<JSONObject> coordinates=new ArrayList<>();
        final List<Map<String,Pair>> nodes=new ArrayList<>();
        final List<Map<String,String>> aliases=new ArrayList<>();
        final List<LocalOwnerCorrespondence> localOwners=new ArrayList<>();
        final TraceResources resources=new TraceResources();
        Endpoint(Canonical.Prepared original,NativeRepairTrace fast,NativeRepairTrace certified,JSONObject arm,String side) {
            this.original=original;boolean sourceSide=side.equals("source");
            forms=fast.comparisonForms(sourceSide);view=certified.projectedView(sourceSide);
            certifiedSource=certified.constructionArtifact(sourceSide);
            var projectedSources=view.originalPhaseSources(original.normalizedForms());
            need(forms.size()==projectedSources.size(),
                    "phase coverage",side,"Fast and certified live phase counts differ");
            var fastSources=forms.stream().map(NormalForm::comparisonSource).toList();
            var fastIdentities=Collections.newSetFromMap(new IdentityHashMap<NormalForm,Boolean>());
            var projectedIdentities=Collections.newSetFromMap(new IdentityHashMap<NormalForm,Boolean>());
            fastIdentities.addAll(fastSources);projectedIdentities.addAll(projectedSources);
            need(fastIdentities.equals(projectedIdentities),
                    "phase coverage",side,"Fast and certified live phase identities differ");
            phaseJoin=PhaseIdentityJoin.live(original.normalizedForms(),fastSources,projectedSources);
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
                int projected=phaseJoin.certified(i);
                var row=indexed.get(projected);require(row!=null,"Missing typed phase coordinate");var source=forms.get(i).comparisonSource();
                require(projectedSources.get(projected)==source,"Foreign comparison phase source");
                require(row.getString("sourcePath").equals(source.getPhasePath())&&row.getString("kind").equals(source.getTemporalOp().name()),"Original typed phase/source correspondence");
                coordinates.add(new JSONObject().put("sourcePath",source.getPhasePath()).put("typedRole",source.getTemporalOp().name()));
            }
            for(int i=0;i<forms.size();i++) {
                var map=new LinkedHashMap<String,Pair>();nodes.add(map);
                aliases.add(new HashMap<>());
                localOwners.add(LocalOwnerCorrespondence.check(forms.get(i).comparisonSource(),forms.get(i).getMatrixEGraph()));
                node(forms.get(i).getMatrixEGraph(),view.phases().get(phaseJoin.certified(i)).matrix(),i,"root",map);
            }
        }
        private void node(EGraphNode fast,Node cert,int phase,String path,Map<String,Pair> map) {
            resources.spend(192L+2L*path.length());
            need(fast!=null&&cert!=null&&fast.getOpcode().name().equals(cert.operator()),"matrix endpoint",phase+":"+path,"Unpaired concrete operator/representation");
            var form=forms.get(phase);var token=new JSONObject().put("operator",cert.operator()).put("phase",coordinates.get(phase));
            if(cert.isVariable()&&cert.bindingIndex()<0) {
                token.put("localBinding",localVariable(localOwners.get(phase),fast,cert));
            } else if(cert.isVariable()) {
                var bindings=view.phases().get(phaseJoin.certified(phase)).bindings();
                need(cert.bindingIndex()>=0&&cert.bindingIndex()<bindings.size(),"variable occurrence",phase+":"+path,"Lexical binding transport needs its retained local owner");
                var binding=bindings.get(cert.bindingIndex());var originals=new ArrayList<QuantiVar>();
                for(var group:List.of(form.getParams(),form.getMatrixQuantiVars(),form.getInheritedQuantiVars(),form.getLocalQuantiVars()))originals.addAll(group);
                var imports=form.checkedPhaseLocalBindingImports();
                for(var imported:imports)originals.add(imported.variable());
                var matches=Collections.newSetFromMap(new IdentityHashMap<QuantiVar,Boolean>());
                originals.stream().filter(q->q.getBindingPath().equals(binding.bindingPath())
                        &&(q.getName().equals(fast.getAlphaName())||q.getName().equals(fast.getSourceName())||q.getOriginalNames().contains(fast.getSourceName()))).forEach(matches::add);
                need(matches.size()==1,"variable owner",phase+":"+path,"Original binding occurrence is absent or ambiguous");var q=matches.iterator().next();
                require(binding.originalBinding()==q,"Temporal original projected binding authority");
                if(binding.role()==RepairView.BindingRole.LOCAL_INHERITED) {
                    var imported=imports.stream().filter(value->value.variable()==q).toList();
                    require(imported.size()==1,"Missing or ambiguous retained local import");
                    var origin=imported.get(0);
                    require(binding.ownerPhase()>=0&&binding.ownerPhase()<forms.size()
                            &&forms.get(phaseJoin.fast(binding.ownerPhase())).comparisonSource()==origin.ownerPhase()
                            &&binding.ownerContext().equals(origin.ownerPhasePath()+"|"+origin.binderContext())
                            &&origin.targetPhasePath().equals(form.comparisonSource().getPhasePath()),
                            "Temporal imported binding owner, context and target authority");
                    token.put("importOwnerContext",binding.ownerContext());
                }
                require(q.getExactAlloyType()!=null&&q.getExactAlloyType().equals(fast.getExactAlloyType())
                        &&q.getExactAlloyType().sameOccurrenceEvidenceAs(fast.getExactAlloyType()),"Temporal variable original exact type");
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
                    int ownerPhase=phaseJoin.fast(binding.ownerPhase());
                    var owner=forms.get(ownerPhase).comparisonSource();
                    require(owner.getMatrixQuantiVars().stream().anyMatch(v->v==q)||owner.getLocalQuantiVars().stream().anyMatch(v->v==q)
                            ||owner.getParams().stream().anyMatch(v->v==q),"Binding owner must retain the actual original variable");
                    token.put("owner",coordinates.get(ownerPhase));
                }
            } else {
                GraphType type=fast.getOpcode()==EGraphNode.Opcode.GENERICRELDECL?null:
                        fast.getMetatype()==EGraphNode.Metatype.BOOLEAN?GraphType.BOOL:AlloyTypeBridge.graphType(Objects.requireNonNull(fast.getExactAlloyType()));
                require(Objects.equals(type,cert.observationType()),"Temporal original literal result type");
                token.put("type",String.valueOf(type)).put("semanticPayload",String.valueOf(cert.semanticPayload()));
                if(cert.isTemporalReference()) {
                    require(Objects.equals(fast.getSourceName(),cert.payload()),"Original temporal reference slot");
                    var origin=Objects.requireNonNull(cert.originalReferenceOrigin(),"Missing original REF authority");
                    var fastOrigin=form.comparisonSource().temporalReferenceOrigin(fast);
                    require(origin.owner()==form.comparisonSource()&&fastOrigin.owner()==origin.owner()
                            &&fastOrigin.authorityId()==origin.authorityId()
                            &&fastOrigin.parserOccurrenceId()==origin.parserOccurrenceId()
                            &&fastOrigin.sourceOccurrenceLineage()==origin.sourceOccurrenceLineage(),"Original REF occurrence/owner authority");
                    var matcher=java.util.regex.Pattern.compile("temporal\\[(\\d+):(\\d+)\\]").matcher(fast.getSourceName());
                    require(matcher.matches(),"Malformed retained temporal reference");int start=Integer.parseInt(matcher.group(1)),count=Integer.parseInt(matcher.group(2));
                    require(count==cert.observationPhases().size()&&start>=0&&start+count<=form.getTemporalChildren().size(),"Original temporal branch tuple");
                    var branches=new JSONArray();
                    for(int j=0;j<count;j++) {
                        int at=cert.observationPhases().get(j);require(at>=0&&at<forms.size(),"Foreign referenced phase");
                        at=phaseJoin.fast(at);
                        var actual=form.getTemporalChildren().get(start+j);var mapped=forms.get(at).comparisonSource();
                        require(actual.comparisonSource()==mapped&&origin.branches().get(j)==mapped
                                &&actual.getTemporalOp()==mapped.getTemporalOp(),"Original reference owner/typed branch identity");
                        branches.put(coordinates.get(at));
                    }
                    String referencePayload="temporal-phase["+String.join(",",cert.observationPhases().stream().map(String::valueOf).toList())+"]/"+cert.observationTemporalOperator();
                    require(referencePayload.equals(cert.semanticPayload()),"Original REF embedded projected coordinates");
                    token.put("semanticPayload",new JSONObject().put("originalBranches",branches).put("operator",cert.observationTemporalOperator()));
                    token.put("branches",branches).put("temporalOperator",cert.observationTemporalOperator());
                } else if(fast.getOpcode()==EGraphNode.Opcode.GLOBALBINDING||fast.getOpcode()==EGraphNode.Opcode.CONSTANT)
                    require(Objects.equals(fast.getSourceName(),cert.payload()),"Original temporal atom identity");
                else if(fast.getOpcode()==EGraphNode.Opcode.CALL) {
                    require(cert.semanticPayload().equals("identity="+CallMetadata.semanticKey(fast)+";type="+type),"Original call premise identity/type");
                    var metadata=CallMetadata.require(fast);
                    var calls=certifiedSource.callOccurrenceCertificates().stream()
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
            // Selected identity addresses are installed only after native replay.
            map.put(path,new Pair(fast,cert,token));
            for(int i=0;i<children.size();i++)node(children.get(i),cert.children().get(i),phase,path+"/child/"+i,map);
        }
        JSONArray localOwnerEvidence() {
            var out=new JSONArray();
            for(int i=0;i<localOwners.size();i++)out.put(new JSONObject().put("phase",coordinates.get(i)).put("correspondence",localOwners.get(i).json()));
            return out;
        }
        private void alias(int phase,String selectedPath,String primaryPath,Node selected) {
            resources.spend(192L+2L*(selectedPath.length()+primaryPath.length()));
            var primary=nodes.get(phase).get(primaryPath);
            require(primary!=null,"Missing primary identity coordinate");
            require(nodes.get(phase).putIfAbsent(selectedPath,new Pair(primary.fast,selected,primary.token))==null,
                    "Duplicate selected identity address");
            aliases.get(phase).put(selectedPath,aliases.get(phase).getOrDefault(primaryPath,primaryPath));
            for(int i=0;i<selected.children().size();i++)alias(phase,selectedPath+"/child/"+i,primaryPath+"/child/"+i,selected.children().get(i));
        }
        JSONObject selectIdentity(int projectedPhase,String path,int index) {
            require(index>=-1,"Invalid selected identity index");
            if(index<0)return new JSONObject().put("unchanged",true);
            int phase=phaseJoin.fast(projectedPhase);var pair=nodes.get(phase).get(path);
            need(pair!=null,"selected representation",path,"Selected parent has no original coordinate");
            require(index<pair.certified.certifiedAlternatives().size(),"Foreign selected identity index");
            var selected=pair.certified.certifiedAlternatives().get(index);
            need(samePrimaryBody(pair.certified,selected,resources),"selected representation",path,
                    "Nonidentity selected body needs its typed original-owner transport");
            String selectedPath=path+"/alternative/"+index;
            alias(phase,selectedPath,path,selected);
            return new JSONObject().put("selectedPath",selectedPath)
                    .put("primaryPath",aliases.get(phase).getOrDefault(path,path)).put("phase",coordinates.get(phase))
                    .put("typedSourceTreeIdentityChecked",true);
        }
        JSONObject occurrence(int phase,String path,JSONObject payload,boolean certified) {
            if(certified)phase=phaseJoin.fast(phase);
            need(phase>=0&&phase<nodes.size()&&nodes.get(phase).containsKey(path),"selected matrix address",phase+":"+path,"Selected representation address not linked to original endpoint");
            var pair=nodes.get(phase).get(path);var nativeEvidence=new JSONObject(payload.getString("nativeEvidence"));
            if(!certified)require(nativeEvidence.getLong("sourceOccurrenceLineage")==pair.fast.getSourceOccurrenceLineage()
                    &&nativeEvidence.getString("alphaName").equals(String.valueOf(pair.fast.getAlphaName()))
                    &&nativeEvidence.getString("exactType").equals(String.valueOf(pair.fast.getExactAlloyType()==null?null:pair.fast.getExactAlloyType().stableString())),"Foreign Fast original source occurrence/type");
            else require(nativeEvidence.getString("semanticPayload").equals(String.valueOf(pair.certified.semanticPayload()))
                    &&nativeEvidence.getInt("bindingIndex")==pair.certified.bindingIndex()
                    &&nativeEvidence.getString("observationType").equals(String.valueOf(pair.certified.observationType())),"Foreign projected selected endpoint");
            return new JSONObject(pair.token.toString()).put("path",certified?aliases.get(phase).getOrDefault(path,path):path);
        }
        JSONObject atom(JSONObject atom,boolean certified) {
            int phase,ordinal;List<QuantiVar> qs;
            if(certified) {
                var c=new JSONArray(atom.getString("occurrence"));phase=c.getInt(1);ordinal=c.getInt(4);
                need(phase>=0&&phase<forms.size(),"declaration coordinate",atom.toString(),"Parameter coordinate requires retained parameter transport");
                phase=phaseJoin.fast(phase);
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
        var left=new Endpoint(a,fast,certified,ca,"source");var right=new Endpoint(b,fast,certified,ca,"target");
        var fastIdentities=new JSONArray();var certifiedIdentities=new JSONArray();
        var x=blocks(fa,false,left,right,fastIdentities);var y=blocks(ca,true,left,right,certifiedIdentities);
        int first=0;while(first<Math.min(x.length(),y.length())) {
            var u=x.getJSONObject(first);var v=y.getJSONObject(first);
            if(!u.getString("component").equals(v.getString("component"))||!same(u.get("effect"),v.get("effect")))break;first++;
        }
        var out=new JSONObject().put("diagnostic",report).put("fastBlocks",x).put("certifiedBlocks",y)
                .put("endpointCorrespondenceChecked",true).put("sourceCertificatesChecked",true).put("fixedChoiceReplaysChecked",true)
                .put("allNativeEntriesRetained",true).put("literalOneToOneOperationLists",false)
                .put("constructionIsNotRepair",true).put("sourcePhases",left.coordinates).put("targetPhases",right.coordinates);
        out.put("sourceLocalOwners",left.localOwnerEvidence()).put("targetLocalOwners",right.localOwnerEvidence());
        out.put("fastIdentityRepresentationSteps",fastIdentities).put("certifiedIdentityRepresentationSteps",certifiedIdentities);
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
    private static JSONArray blocks(JSONObject trace,boolean certified,Endpoint a,Endpoint b,JSONArray identities) {
        var result=new JSONArray();JSONObject block=null;int total=0;var seen=new HashSet<String>();
        for(Object value:trace.getJSONArray("orderedNativeOperations")) {
            var row=(JSONObject)value;String component=row.getString("component"),path=row.getString("payloadPath");var op=at(trace,path);
            require(seen.add(path)&&row.getInt("cost")==op.getInt("cost"),"Ordered payload/charge coverage");total=Math.addExact(total,row.getInt("cost"));
            if(component.equals("representation")) {
                need(certified,"selected representation",path,"Fast selection needs its own source-linked adapter");
                require(op.getInt("cost")==0,"Identity representation charge");
                int phase=Integer.parseInt(path.split("/")[2]);
                var source=a.selectIdentity(phase,op.optString("source",null),op.getInt("leftAlternative"));
                var target=b.selectIdentity(phase,op.optString("target",null),op.getInt("rightAlternative"));
                identities.put(new JSONObject().put("nativeIndex",seen.size()-1).put("row",row).put("payload",op)
                        .put("source",source).put("target",target).put("effect","checked-identity-presentation"));
                continue;
            }
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
                    effect=new JSONObject().put("kind",op.isNull("source")?"insert":op.isNull("target")?"delete":op.getInt("cost")==0?"zero-transport":"update")
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
                        int fastPhase=certified?endpoint.phaseJoin.fast(i):i;
                        require(old==endpoint.phaseJoin.original(i,certified)
                                &&r.getInt("comparisonPhase")==i&&r.getString("sourcePath").equals(endpoint.coordinates.get(fastPhase).getString("sourcePath"))
                                &&r.getString("typedRole").equals(endpoint.coordinates.get(fastPhase).getString("typedRole")),"Original phase representation occurrence/role");
                    }
                    if(certified)require(trace.getJSONObject("constructionDerivations").getJSONObject(side).getJSONObject("projectionPhaseRepresentation").similar(op),"Projection construction/reindex link");
                    effect=new JSONObject().put("kind",op.getString("kind")).put("cost",0).put("side",side)
                            .put("originals",new JSONArray(endpoint.original.normalizedForms().stream().map(f->
                                    new JSONObject().put("sourcePath",f.getPhasePath()).put("typedRole",f.getTemporalOp().name())).toList()));
                }
                case "temporal", "temporal-read" -> { }
                default -> throw new Missing("ordered representation",path,"Concrete selected step has no checked cross-arm effect: "+component);
            }
            block.getJSONArray("effect").put(effect);
        }
        require(total==trace.getInt("distance"),"All ordered charges retained");return result;
    }
}
