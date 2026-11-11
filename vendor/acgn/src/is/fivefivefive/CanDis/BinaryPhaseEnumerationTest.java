package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.metric.RepairView.*;
import is.fivefivefive.CanDis.theory.*;

/** Finite original-source phase-order and selected-path investigation. */
public final class BinaryPhaseEnumerationTest {
    private static int checks;
    static int checkCount() { return checks; }
    private static void check(boolean value,String message) {
        checks++;if(!value)throw new AssertionError(message);
    }
    private static void reject(Runnable action,String message) {
        checks++;try {action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}
        throw new AssertionError("Accepted "+message);
    }
    private static void write(Path file,JSONObject data)throws Exception {
        Files.writeString(file,data.toString(),StandardOpenOption.CREATE_NEW);
    }
    private static String hash(String text)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static JSONObject fixture(Path out,String id,String text)throws Exception {
        var path=out.resolve(id+".als");Files.writeString(path,text,StandardOpenOption.CREATE_NEW);
        return new JSONObject().put("id",id).put("source",path.toString()).put("sha256",hash(text)).put("left","a").put("right","b");
    }
    static List<JSONObject> phases(JSONObject payment,String side) {
        var result=new TreeMap<Integer,JSONObject>();
        for(Object value:payment.getJSONArray(side))for(Object p:((JSONObject)value).getJSONArray("typedBranches")) {
            var phase=(JSONObject)p;var prior=result.putIfAbsent(phase.getInt("ordinal"),phase);
            check(prior==null||prior.similar(phase),"one original typed phase per locator");
        }
        for(int i=0;i<result.size();i++)check(result.containsKey(i),"contiguous retained source enumeration");
        return List.copyOf(result.values());
    }
    private static JSONObject sourceLinks(Canonical.Prepared source,Canonical.Prepared foreign,RepairView view,
            List<JSONObject> phases) {
        var evidence=TheoryAlloyAdapter.adapt(source.normalizedForms(),source.semanticProfile());
        var checked=RepairProjection.project(evidence,source.normalizedForms());
        check(checked.producerObservationDigest().equals(view.producerObservationDigest()),"same actual certified construction");
        reject(()->RepairProjection.project(evidence,foreign.normalizedForms()),"foreign original source artifact");
        var originals=source.normalizedForms();
        if(originals.size()>2) {
            var reordered=new ArrayList<>(originals);Collections.swap(reordered,1,2);
            reject(()->RepairProjection.project(evidence,reordered),"source phase permutation without source authority");
            var repeated=new ArrayList<>(originals);repeated.set(2,repeated.get(1));
            reject(()->RepairProjection.project(evidence,repeated),"repeated phase source identity");
        }
        var retainedPaths=new HashSet<String>();for(var p:phases)check(retainedPaths.add(p.getString("sourcePath")),"unique original phase path");
        var retained=originals.stream().filter(f->retainedPaths.contains(f.getPhasePath())).toList();
        check(retained.size()==phases.size()&&phases.size()==view.phases().size(),"reachability/subsequence coverage");
        var links=new JSONArray();
        for(int i=0;i<phases.size();i++) {
            var p=phases.get(i);var actual=retained.get(i);
            check(p.getString("sourcePath").equals(actual.getPhasePath())&&p.getString("kind").equals(actual.getTemporalOp().name()),"original typed enumeration slot "+i);
            var bindings=new JSONArray();
            for(var binding:view.phases().get(i).bindings()) {
                if(binding.ownerPhase()>=0) {
                    var owner=retained.get(binding.ownerPhase());
                    if(binding.role()==BindingRole.MATRIX||binding.role()==BindingRole.INHERITED)
                        check(owner.getMatrixQuantiVars().stream().anyMatch(v->v.getBindingPath().equals(binding.bindingPath())),"original prefix/import owner after reindex");
                    if(binding.role()==BindingRole.MATRIX)check(binding.ownerPhase()==i,"matrix binder remains in its own source phase");
                    if(binding.role()==BindingRole.LOCAL_INHERITED)
                        check(actual.getPhaseLocalBindingImports().stream().anyMatch(imported->
                                imported.variable().getBindingPath().equals(binding.bindingPath())
                                &&imported.ownerPhase()==owner
                                &&imported.ownerPhasePath().equals(owner.getPhasePath())
                                &&imported.targetPhasePath().equals(actual.getPhasePath())
                                &&imported.sourceBinderLineage()>0),"original local binder occurrence/owner survives phase reindex");
                    bindings.put(new JSONObject().put("role",binding.role()).put("bindingPath",binding.bindingPath())
                            .put("ownerSourcePath",owner.getPhasePath()).put("ownerKind",owner.getTemporalOp()));
                }
            }
            links.put(new JSONObject().put("ordinal",i).put("originalOrdinal",originals.indexOf(actual))
                    .put("sourcePath",actual.getPhasePath()).put("kind",actual.getTemporalOp()).put("bindings",bindings));
        }
        return new JSONObject().put("phaseLinks",links).put("artifactProjectionDigest",view.producerObservationDigest())
                .put("inProcessSourcePremisesChecked",true).put("wireCorrespondenceChecked",false);
    }
    private static String operator(NormalForm.TemporalOp role) {
        return switch(role) {
            case UNTILL,UNTILR -> "UNTIL";
            case RELEASESL,RELEASESR -> "RELEASES";
            case SINCEL,SINCER -> "SINCE";
            case TRIGGEREDL,TRIGGEREDR -> "TRIGGERED";
            default -> role.name();
        };
    }
    static void endpoint(EGraphNode fast,Node certified,NormalForm form,List<Binding> bindings,List<JSONObject> phases,
            String path,Map<String,String> heads) {
        check(fast.getOpcode().name().equals(certified.operator()),"original metric node operator "+path);
        if(fast.getOpcode()==EGraphNode.Opcode.VARIABLE) {
            check(certified.bindingIndex()>=0&&certified.bindingIndex()<bindings.size(),"bound original variable coordinate");
            var binding=bindings.get(certified.bindingIndex());
            var originals=new ArrayList<QuantiVar>();
            for(var group:List.of(form.getParams(),form.getMatrixQuantiVars(),form.getInheritedQuantiVars(),form.getLocalQuantiVars()))originals.addAll(group);
            var original=originals.stream().filter(v->v.getBindingPath().equals(binding.bindingPath())
                    &&(v.getName().equals(fast.getAlphaName())||v.getName().equals(fast.getSourceName())
                    ||v.getOriginalNames().contains(fast.getSourceName())||v.getOriginalNames().contains(fast.getSemanticIdentity()))).findFirst().orElseThrow();
            check(original.getExactAlloyType().equals(fast.getExactAlloyType()),"actual source variable exact type and original owner path");
            var type=AlloyTypeBridge.graphType(Objects.requireNonNull(fast.getExactAlloyType()));
            var carrier=original.getExactAlloyType().relationArity()>1?type:
                    "int".equalsIgnoreCase(original.getTypeName())?GraphType.INT:
                    GraphType.constructor("AlloyCarrier",List.of(GraphType.constructor(original.getTypeName())));
            check(carrier.equals(binding.declaration().observationType()),"owned original binding carrier/type projection");
            check(type.equals(certified.observationType()),"literal variable observation agrees with original exact relation type");
        } else check(Objects.equals(NestedPaidDeclarationReplayTest.type(fast),certified.observationType()),"literal original result type "+path);
        if(certified.isTemporalReference()) {
            check(fast.getSourceName().equals(certified.payload()),"original local reference slot/arity");
            String local=fast.getSourceName();int colon=local.indexOf(':');
            int start=Integer.parseInt(local.substring("temporal[".length(),colon));
            int count=Integer.parseInt(local.substring(colon+1,local.length()-1));
            check(count==certified.observationPhases().size(),"reference branch tuple arity");
            for(int j=0;j<count;j++) {
                var actual=form.getTemporalChildren().get(start+j);var projected=phases.get(certified.observationPhases().get(j));
                check(actual.getPhasePath().equals(projected.getString("sourcePath"))&&actual.getTemporalOp().name().equals(projected.getString("kind")),"original reference typed branch identity");
            }
            check(operator(form.getTemporalChildren().get(start).getTemporalOp()).equals(certified.observationTemporalOperator()),"original temporal operator payload");
        } else if(fast.getOpcode()==EGraphNode.Opcode.GLOBALBINDING||fast.getOpcode()==EGraphNode.Opcode.CONSTANT)
            check(Objects.equals(fast.getSourceName(),certified.payload()),"original atom with literal type");
        check(certified.certifiedAlternatives().isEmpty(),"finite witness has no unresolved representative choice");
        var children=FastChainGeometry.children(fast);check(children.size()==certified.children().size(),"same original metric child coverage");
        heads.put(path,certified.operator()+"/"+certified.semanticPayload());
        for(int i=0;i<children.size();i++)endpoint(children.get(i),certified.children().get(i),form,bindings,phases,path+"/child/"+i,heads);
    }
    static JSONArray actions(JSONObject trace,List<Map<String,String>> a,List<Map<String,String>> b) {
        var result=new JSONArray();
        for(int phase=0;phase<trace.getJSONArray("phases").length();phase++)
            for(Object item:trace.getJSONArray("phases").getJSONObject(phase).getJSONObject("program").getJSONArray("operations")) {
                var op=(JSONObject)item;
                var source=op.isNull("source")?null:op.getString("source");var target=op.isNull("target")?null:op.getString("target");
                String kind=source==null?"insert":target==null?"delete":op.getInt("cost")==0?"zero-transport":"update";
                result.put(new JSONObject().put("phase",phase).put("kind",kind).put("cost",op.getInt("cost"))
                        .put("source",source==null?JSONObject.NULL:source).put("target",target==null?JSONObject.NULL:target)
                        .put("before",source==null?JSONObject.NULL:Objects.requireNonNull(a.get(phase).get(source)))
                        .put("after",target==null?JSONObject.NULL:Objects.requireNonNull(b.get(phase).get(target))));
            }
        return result;
    }
    static JSONObject actualAttempt(Canonical.Prepared a,Canonical.Prepared b,RepairView av,RepairView bv,
            NativeRepairTrace fast,NativeRepairTrace certified) {
        var payments=certified.json().getJSONObject("selectedTemporalReferencePayments");
        var ap=phases(payments,"source");var bp=phases(payments,"target");
        var af=CanonicalDistance.prepare(a.normalizedForms()).comparisonForms();var bf=CanonicalDistance.prepare(b.normalizedForms()).comparisonForms();
        var ah=new ArrayList<Map<String,String>>();var bh=new ArrayList<Map<String,String>>();
        for(int i=0;i<af.size();i++){var h=new HashMap<String,String>();endpoint(af.get(i).getMatrixEGraph(),av.phases().get(i).matrix(),af.get(i),av.phases().get(i).bindings(),ap,"root",h);ah.add(h);}
        for(int i=0;i<bf.size();i++){var h=new HashMap<String,String>();endpoint(bf.get(i).getMatrixEGraph(),bv.phases().get(i).matrix(),bf.get(i),bv.phases().get(i).bindings(),bp,"root",h);bh.add(h);}
        var fa=actions(fast.json(),ah,bh);var ca=actions(certified.json(),ah,bh);
        check(fa.length()==ca.length(),"identical selected operation coverage after phase-order repair");
        var differences=new JSONArray();
        for(int i=0;i<fa.length();i++)if(!fa.getJSONObject(i).similar(ca.getJSONObject(i)))
            differences.put(new JSONObject().put("operation",i).put("fast",fa.get(i)).put("certified",ca.get(i)));
        check(differences.isEmpty(),"all ordered zero/paid matrix actions agree under typed original endpoints: "+differences);
        boolean zeroReference=false;
        for(Object value:fa) {
            var step=(JSONObject)value;
            if(step.getInt("phase")==2&&step.getInt("cost")==0
                    &&!step.isNull("before")&&!step.isNull("after")
                    &&step.getString("before").startsWith("REF/")&&step.getString("after").startsWith("REF/"))zeroReference=true;
        }
        check(zeroReference,"original selected zero REF step retained, not replaced by another optimum");
        check(fast.json().getJSONObject("nativeTemporalProgram").similar(certified.json().getJSONObject("nativeTemporalProgram")),"same actual temporal edits and zero reads");
        JSONObject sourceEdge=null,targetEdge=null;
        for(Object value:payments.getJSONArray("source"))if(((JSONObject)value).getInt("ownerPhase")==2)sourceEdge=(JSONObject)value;
        for(Object value:payments.getJSONArray("target"))if(((JSONObject)value).getInt("ownerPhase")==2)targetEdge=(JSONObject)value;
        check(sourceEdge!=null&&targetEdge!=null&&sourceEdge.getInt("ownerRole")!=targetEdge.getInt("ownerRole"),"actual original left/right branch owners differ");
        boolean paired=false;
        for(Object value:payments.getJSONArray("selectedCorrespondence")) {
            var link=(JSONArray)value;
            if(link.getInt(0)==sourceEdge.getInt("postorder")&&link.getInt(1)==targetEdge.getInt("postorder"))paired=true;
        }
        check(!paired,"actual Fast zero step is not the selected temporal occurrence pair");
        boolean reconstructed=false;
        for(Object value:payments.getJSONArray("referenceTransports")) {
            var link=(JSONObject)value;
            if(link.getInt("sourceOccurrence")==sourceEdge.getInt("postorder")
                    &&link.getInt("targetOccurrence")==targetEdge.getInt("postorder")) {
                check(link.getString("kind").equals("selected-skeleton-reconstruction"),"explicit whole-skeleton premise, not a fabricated matched pair");
                check(link.getInt("ownerRole")==sourceEdge.getInt("ownerRole")
                        &&link.getInt("targetOwnerRole")==targetEdge.getInt("ownerRole"),"different original roles remain in reconstruction evidence");
                reconstructed=true;
            }
        }
        check(reconstructed,"actual zero REF step has selected whole-skeleton reconstruction evidence");
        var rejected=certified.json();var phase=rejected.getJSONArray("phases").getJSONObject(2);
        var derivation=phase.getJSONObject("receipt").getJSONObject("derivation");
        derivation.put("cost",1);derivation.getJSONArray("children").getJSONObject(0).getJSONObject("edit").put("headCost",1).put("cost",1);
        phase.getJSONObject("receipt").put("cost",1).put("replayedCost",1);
        reject(()->certified.replay(rejected),"extra charge inconsistent with checked reconstruction and selected decision");
        return new JSONObject().put("endpointCorrespondenceChecked",true).put("allZeroAndPaidOperationsRetained",true)
                .put("orderedActionsEqual",true).put("fastActions",fa).put("certifiedActions",ca).put("differences",differences)
                .put("sourceReference",sourceEdge).put("targetReference",targetEdge)
                .put("selectedFastCertificateAttempt","CHECKED_TYPED_SKELETON_RECONSTRUCTION_IN_PROCESS")
                .put("claim","Both selected paths execute with checked construction links and typed whole-skeleton reconstruction. Zero matrix alignment is not temporal operator equality. Public/source-wire/Java-Lean proof remains blocked.");
    }
    private static void corrupt(NativeRepairTrace trace) {
        for(String mode:List.of("owner","role","source","map","action","cost","certificate")) {
            var bad=trace.json();
            switch(mode) {
                case "owner" -> bad.getJSONObject("selectedTemporalReferencePayments").getJSONArray("source").getJSONObject(0).put("ownerPostorder",999);
                case "role" -> bad.getJSONObject("selectedTemporalReferencePayments").getJSONArray("source").getJSONObject(0).getJSONArray("typedBranches").getJSONObject(0).put("kind","FOREIGN");
                case "source" -> bad.getJSONObject("selectedTemporalReferencePayments").getJSONArray("source").getJSONObject(0).getJSONArray("typedBranches").getJSONObject(0).put("sourcePath","foreign");
                case "map" -> bad.getJSONObject("nativeTemporalProgram").put("selectedCorrespondence",new JSONArray());
                case "action" -> bad.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(0).put("source","foreign");
                case "cost" -> bad.put("matrixDistance",999);
                case "certificate" -> bad.put("constructionDerivations","foreign");
            }
            reject(()->trace.replay(bad),"original phase/certificate replay "+mode);
        }
        check(trace.replay(trace.json())==trace.distance(),"receipt immutable after tampering");
    }
    static JSONObject run(JSONObject identity,Path out,boolean actual)throws Exception {
        var pair=LocalOwnerTraceTest.prepare(identity);var rows=new JSONArray();
        for(int d=0;d<2;d++) {
            var a=pair.get(d);var b=pair.get(1-d);var fast=CanonicalRepairTraces.fast(a,b);
            check(fast.replay(fast.json())==fast.distance(),"original shorter Fast path replayed first");
            var ca=CanonicalAlloyPipeline.prepare(a);var cb=CanonicalAlloyPipeline.prepare(b);
            var cert=CanonicalRepairTraces.certified(ca,cb);check(cert.replay(cert.json())==cert.distance(),"independent certified execution and premises");
            var payments=cert.json().getJSONObject("selectedTemporalReferencePayments");
            var sl=sourceLinks(a,b,ca.repairView(),phases(payments,"source"));
            var tl=sourceLinks(b,a,cb.repairView(),phases(payments,"target"));
            var row=new JSONObject().put("identity",identity).put("direction",d).put("fast",fast.json()).put("certified",cert.json())
                    .put("sourceEnumeration",sl).put("targetEnumeration",tl);
            if(actual) {
                check(fast.distance()==9&&cert.distance()==9,"source-linked skeleton reconstruction discharges remaining reference charge");
                row.put("selectedPathAttempt",actualAttempt(a,b,ca.repairView(),cb.repairView(),fast,cert));
            }
            var bridge=NativeOperationComparison.compare(a,b,fast,cert);row.put("comparison",bridge.evidence());
            write(out.resolve(identity.getString("id").replace('/','_')+"-"+d+".json"),row);
            corrupt(cert);
            var fi=CanonicalRepairTraces.fast(a,a);var ci=CanonicalRepairTraces.certified(ca,ca);
            check(fi.distance()==0&&ci.distance()==0&&fi.replay(fi.json())==0&&ci.replay(ci.json())==0,"original identity endpoint remains included");
            rows.put(new JSONObject().put("direction",d).put("fast",fast.distance()).put("certified",cert.distance()).put("crossPath",bridge.status().name()));
        }
        return new JSONObject().put("identity",identity).put("rows",rows);
    }
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]).toAbsolutePath();Files.createDirectories(out);var results=new JSONArray();
        String body="((eventually some A) until (after no A))";
        var actual=fixture(out,"binary-promotion","var sig A {} pred a {always ((eventually some A) until (after no A))} pred b {(eventually some A) until (after no A)}");
        check(actual.getString("sha256").equals("3bf679623c458c0ffff7a7f0c892b891dc0ea57b5ae74eda7561ec9156431226"),"exact retained incident source bytes");
        results.put(run(actual,out,true));
        for(String op:List.of("releases","since","triggered")) {
            var expression=body.replace("until",op);
            results.put(run(fixture(out,"binary-"+op,"var sig A {} pred a {always "+expression+"} pred b {"+expression+"}"),out,false));
        }
        results.put(run(fixture(out,"binary-import","var sig A {var r:set A} pred a {all x:A | always ((eventually some x.r) until (after no x.r))} pred b {all x:A | (eventually some x.r) until (after no x.r)}"),out,false));
        results.put(run(fixture(out,"binary-local","var sig A {var r:set A} pred a {always ((some x:A | eventually some x.r) until (after no A))} pred b {(some x:A | eventually some x.r) until (after no A)}"),out,false));
        if(args.length>1) {
            var prior=new JSONObject(Files.readString(Path.of(args[1]).resolve("summary.json")));
            for(Object value:prior.getJSONArray("cases")) {
                var old=(JSONObject)value;var id=old.getJSONObject("identity").getString("id");
                if(id.equals("binary-promotion"))continue; // Exact source and selected reconstruction checked above.
                var result=run(old.getJSONObject("identity"),out,false);
                for(int i=0;i<2;i++) {
                    var before=old.getJSONArray("rows").getJSONObject(i);var after=result.getJSONArray("rows").getJSONObject(i);
                    check(before.getInt("fast")==after.getInt("fast")&&before.getInt("certified")==after.getInt("certified"),"same-identity prior costs "+id);
                    check(before.getString("comparison").equals(after.getString("crossPath")),"no prior checked obligation loss "+id);
                }
                results.put(result);
            }
        }
        write(out.resolve("summary.json"),new JSONObject().put("checks",checks).put("cases",results).put("queueCleared",false)
                .put("binaryStatus","TYPED_SKELETON_RECONSTRUCTION_9_9_CHECKED_IN_PROCESS").put("publicCertification","BLOCKED"));
        System.out.println("BinaryPhaseEnumerationTest: "+checks+" checks; public/cross-path closure remains OPEN");
    }
}
