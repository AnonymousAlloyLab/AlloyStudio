package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;

/** Source-bound imported/local registry and corrupted-authority controls. */
public final class ImportedPhaseScopeTest {
    private static int checks, negative, uses, sameTypeOwnerSlots, multipleHeirs;
    private static void check(boolean ok,String why) { checks++;if(!ok)throw new AssertionError(why); }
    private static Object get(Object o,String name)throws Exception {
        var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);
    }
    private static void set(Object o,String name,Object value)throws Exception {
        var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);
    }
    private static Object invoke(Class<?> c,String name,Class<?>[] types,Object receiver,Object... args)throws Exception {
        var m=c.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(receiver,args);
    }
    private interface Attempt { void run() throws Exception; }
    private static void reject(Attempt action,String why)throws Exception {
        checks++;negative++;
        try {action.run();} catch(IllegalArgumentException|IllegalStateException expected){return;}
        catch(InvocationTargetException wrapped){
            if(wrapped.getCause() instanceof IllegalArgumentException||wrapped.getCause() instanceof IllegalStateException)return;
            throw wrapped;
        }
        throw new AssertionError("Accepted invalid "+why);
    }
    private static List<EGraphNode> variables(EGraphNode root) {
        var found=new ArrayList<EGraphNode>();var todo=new ArrayDeque<EGraphNode>();todo.add(root);
        while(!todo.isEmpty()){var node=todo.remove();if(node.getOpcode()==EGraphNode.Opcode.VARIABLE)found.add(node);todo.addAll(node.getChildren());}
        return found;
    }
    private static JSONObject inspect(Canonical.Prepared prepared,RepairView projected)throws Exception {
        var delegate=(CanonicalDistance.Prepared)get(prepared,"delegate");var forms=delegate.comparisonForms();
        var heirs=new IdentityHashMap<QuantiVar,Set<Integer>>();var evidence=new JSONArray();
        for(int phase=0;phase<forms.size();phase++) {
            var form=forms.get(phase);var imports=form.checkedPhaseLocalBindingImports();
            var descriptors=(Map<?,?>)invoke(CanonicalDistance.class,"variableBindings",new Class<?>[]{NormalForm.class},null,form);
            for(var imported:imports) {
                var q=imported.variable();var descriptor=descriptors.get(q.getName());
                check(descriptor==null,"registry visibility does not authorize a new metric candidate");negative++;
                heirs.computeIfAbsent(q,k->new HashSet<>()).add(phase);
                var old=new ArrayList<QuantiVar>();for(var list:List.of(form.getParams(),form.getMatrixQuantiVars(),form.getInheritedQuantiVars(),form.getLocalQuantiVars()))old.addAll(list);
                check(old.stream().noneMatch(v->v==q),"import did not become a phase declaration");
                int owner=-1;for(int i=0;i<forms.size();i++)if(forms.get(i).comparisonSource()==imported.ownerPhase())owner=i;
                check(owner>=0,"retained owner is in actual phase inventory");
                for(var occurrence:variables(form.getMatrixEGraph()))if(q.getName().equals(occurrence.getAlphaName())) {
                    uses++;check(!(Boolean)invoke(EGraphNode.class,"hasComparisonRegisteredBinding",new Class<?>[0],occurrence),"endpoint lookup does not add cost-bearing registration");
                    check(q.getExactAlloyType().sameOccurrenceEvidenceAs(occurrence.getExactAlloyType()),"exact imported occurrence type");
                    check(imported.ownerPhase().getLocalQuantiVars().stream().anyMatch(v->v==q),"actual owner retains imported slot");
                }
                for(var mode:List.of("targetPhasePath","ownerPhasePath","binderContext","sourceBinderLineage")) {
                    Object saved=get(imported,mode);
                    try{set(imported,mode,saved instanceof Long?((Long)saved)+1:"foreign");
                        reject(form::checkedPhaseLocalBindingImports,mode);}
                    finally{set(imported,mode,saved);}
                }
                for(var mode:List.of("ownerPhase","ownerBinder")) {
                    Object saved=get(imported,mode);
                    try{set(imported,mode,mode.equals("ownerPhase")?form.comparisonSource():form.getMatrixEGraph());
                        reject(form::checkedPhaseLocalBindingImports,mode+" identity");}
                    finally{set(imported,mode,saved);}
                }
                Object exactType=get(q,"exactAlloyType");
                try{set(q,"exactAlloyType",null);reject(form::checkedPhaseLocalBindingImports,"missing import exact type");}
                finally{set(q,"exactAlloyType",exactType);}
                for(var other:imported.ownerPhase().getLocalQuantiVars())if(other!=q) {
                    try{set(imported,"variable",other);reject(form::checkedPhaseLocalBindingImports,"another slot of the same owner");}
                    finally{set(imported,"variable",q);}
                    break;
                }
                var originalImports=get(form.comparisonSource(),"phaseLocalBindingImports");
                var localSeeds=get(form.comparisonSource(),"localBindingSeeds");
                var viewSeeds=get(form,"localBindingSeeds");
                // A same-name local record must never overwrite the imported owner.
                var foreignSeeds=new HashMap<Object,Object>((Map<?,?>)localSeeds);
                var ownerSeeds=(Map<?,?>)get(imported.ownerPhase(),"localBindingSeeds");
                foreignSeeds.put(q.getName(),ownerSeeds.get(q.getName()));
                try{set(form.comparisonSource(),"localBindingSeeds",foreignSeeds);
                    set(form,"localBindingSeeds",foreignSeeds);
                    reject(form::checkedPhaseLocalBindingImports,"colliding local/import name");}
                finally{set(form.comparisonSource(),"localBindingSeeds",localSeeds);set(form,"localBindingSeeds",viewSeeds);}
                check(get(form.comparisonSource(),"phaseLocalBindingImports")==originalImports,"import inventory retained");
                evidence.put(new JSONObject().put("phase",phase).put("ownerPhase",owner).put("alpha",q.getName())
                    .put("sourceBinderLineage",imported.sourceBinderLineage()).put("context",imported.binderContext())
                    .put("exactType",q.getExactAlloyType().stableString()).put("oldLookup",false).put("newLookup",true));
            }
        }
        for(var phases:heirs.values())if(phases.size()>1)multipleHeirs++;
        for(var form:forms)for(var a:form.checkedPhaseLocalBindingImports())for(var b:form.checkedPhaseLocalBindingImports())
            if(a.variable()!=b.variable()&&a.ownerPhase()==b.ownerPhase()
                    &&a.binderContext().equals(b.binderContext())
                    &&a.variable().getExactAlloyType().equals(b.variable().getExactAlloyType()))sameTypeOwnerSlots++;
        for(var phase:projected.phases())for(var b:phase.bindings())if(b.role()==RepairView.BindingRole.LOCAL_INHERITED) {
            check(b.originalBinding()!=null,"sealed projected original binding");
            int coordinate=b.coordinate();try{set(b,"coordinate",coordinate+1);reject(b::originalBinding,"projected coordinate");}
            finally{set(b,"coordinate",coordinate);}
        }
        return new JSONObject().put("imports",evidence);
    }
    public static void main(String[] args)throws Exception {
        var manifest=new JSONObject(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);
        var rows=new JSONArray();var ownerEvidence=new JSONArray();
        for(Object raw:manifest.getJSONArray("pairs")) {
            var request=(JSONObject)raw;var id=request.getJSONObject("identity");var pair=LocalOwnerTraceTest.prepare(id);
            var projected=List.of(CanonicalAlloyPipeline.prepare(pair.get(0)),CanonicalAlloyPipeline.prepare(pair.get(1)));
            for(int side=0;side<2;side++)ownerEvidence.put(inspect(pair.get(side),projected.get(side).repairView()).put("identity",id).put("side",side));
            for(int side=0;side<2;side++) {
                var fast=CanonicalRepairTraces.fast(pair.get(side),pair.get(side));
                var cert=CanonicalRepairTraces.certified(projected.get(side),projected.get(side));
                var receipt=new JSONObject().put("identity",id).put("side",side).put("fast",fast.json()).put("certified",cert.json());
                Files.writeString(out.resolve(id.getString("id")+"-"+side+"-self.json"),receipt.toString(),StandardOpenOption.CREATE_NEW);
                QueueProbe.orderedReplay(fast.distance(),cert.distance(),()->fast.replay(fast.json()),()->cert.replay(cert.json()),receipt);
                check(fast.distance()==0&&cert.distance()==0,"self distance must remain zero");
            }
            for(int d=0;d<2;d++) {
                var fast=CanonicalRepairTraces.fast(pair.get(d),pair.get(1-d));
                Files.writeString(out.resolve(id.getString("id")+"-"+d+"-fast.json"),fast.json().toString(),StandardOpenOption.CREATE_NEW);
                var cert=CanonicalRepairTraces.certified(projected.get(d),projected.get(1-d));
                var receipt=new JSONObject().put("identity",id).put("direction",d).put("fast",fast.json()).put("certified",cert.json());
                Files.writeString(out.resolve(id.getString("id")+"-"+d+"-native.json"),receipt.toString(),StandardOpenOption.CREATE_NEW);
                QueueProbe.orderedReplay(fast.distance(),cert.distance(),()->fast.replay(fast.json()),()->cert.replay(cert.json()),receipt);
                var result=NativeOperationComparison.compare(pair.get(d),pair.get(1-d),fast,cert);
                check(result.status()!=NativeOperationComparison.Status.INVALID_REPLAY&&result.status()!=NativeOperationComparison.Status.ERROR,"no new invalid replay: "+result.reason());
                if(request.getString("family").equals("MULTIPLE_HEIRS_CONTROL")
                        ||request.optString("kind").endsWith("alpha"))
                    check(fast.distance()==0&&cert.distance()==0,"alpha renaming must remain zero in both directions");
                if(request.getString("family").equals("TEMPORAL_VARIABLE_OWNER"))
                    check(result.status()==NativeOperationComparison.Status.MATCHED,"owner family must replay and match in both directions");
                var row=new JSONObject().put("identity",id).put("direction",d).put("status",result.status()).put("reason",result.reason())
                    .put("fast",fast.distance()).put("certified",cert.distance()).put("comparison",result.evidence());
                rows.put(row);Files.writeString(out.resolve(id.getString("id")+"-"+d+"-comparison.json"),row.toString(),StandardOpenOption.CREATE_NEW);
                var tampered=fast.json();for(Object phase:tampered.getJSONArray("sourceBindingContexts")) {
                    var imports=((JSONObject)phase).getJSONArray("imports");if(imports.isEmpty())continue;
                    imports.getJSONObject(0).put("ownerPhase","foreign");reject(()->fast.replay(tampered),"receipt import owner");break;
                }
            }
        }
        check(sameTypeOwnerSlots>0,"multiple same-type bindings are retained under one owner");
        check(multipleHeirs>0,"one owner identity is retained across multiple descendant phases");
        var summary=new JSONObject().put("checks",checks).put("negativeControls",negative).put("importedUses",uses)
            .put("sameTypeOwnerSlots",sameTypeOwnerSlots).put("multipleHeirs",multipleHeirs)
            .put("rows",rows).put("ownerEvidence",ownerEvidence).put("scope","Registry repair only; full orbit transport OPEN");
        Files.writeString(out.resolve("summary.json"),summary.toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("ImportedPhaseScopeTest checks="+checks+" negative="+negative+" importedUses="+uses);
    }
}
