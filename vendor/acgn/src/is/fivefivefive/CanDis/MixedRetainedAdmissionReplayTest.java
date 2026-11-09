package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.metric.RepairView.*;
import parser.ast.nodes.ModelUnit;

/** Finite original-ordinal replay; does not widen the production comparator's layout slice. */
public final class MixedRetainedAdmissionReplayTest {
    private static int checks;
    private static final Path SOURCE=Path.of("classified-data/classroom_fol/both/p3hJuKvQ3SpwKDZHa_inv12.als");
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void reject(Runnable action,String why){checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError(why);}
    private static Object call(String owner,String name,Class<?>[] types,Object...args){
        try{var m=Class.forName("is.fivefivefive.CanDis.core."+owner).getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void field(Object target,String name,Object value){try{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static List<Canonical.Prepared> prepare(String text,String...names)throws Exception{
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var v=new MASGVisitor(new GlobalVariables(),Set.of(names),module);v.visit(new ModelUnit(null,module),null);
        var out=new ArrayList<Canonical.Prepared>();for(String name:names)out.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));return out;
    }
    private static NormalForm original(Canonical.Prepared p){return p.normalizedForms().get(0);}
    private static NormalForm comparison(Canonical.Prepared p){return CanonicalDistance.prepare(p.normalizedForms()).comparisonForms().get(0);}
    private static boolean pair(NormalForm a,NormalForm b){return (Boolean)call("FastPaidDeclarationAlignment","supportsPair",new Class[]{List.class,List.class},List.of(a),List.of(b));}
    private static int ownerCount(NormalForm f){return (Integer)call("FastLocalPaidScope","checkedOwnerCount",new Class[]{NormalForm.class},f);}
    private static List<EGraphNode> nodes(EGraphNode root){var out=new ArrayList<EGraphNode>();var todo=new ArrayDeque<EGraphNode>();todo.add(root);while(!todo.isEmpty()){check(out.size()<512,"bounded fixture nodes");var n=todo.removeFirst();out.add(n);todo.addAll(n.getChildren());}return out;}
    private static List<EGraphNode> owners(NormalForm f){return nodes(f.getMatrixEGraph()).stream().filter(n->n.getChildren().size()==2&&n.getChildren().get(0).getOpcode()==EGraphNode.Opcode.GENERICRELDECL).toList();}
    private static void corrupt(Canonical.Prepared a,Canonical.Prepared b,Consumer<NormalForm> edit,String why){var f=comparison(a);edit.accept(f);check(!pair(f,comparison(b)),why);}
    private static JSONObject copy(JSONObject value){return new JSONObject(value.toString());}

    private static String fastHead(EGraphNode n,NormalForm source,Phase phase){
        if(n.getOpcode()==EGraphNode.Opcode.VARIABLE){
            var originals=nodes(source.getCertificationMatrixEGraph()).stream().filter(x->x.getOpcode()==EGraphNode.Opcode.VARIABLE&&x.getSourceOccurrenceLineage()==n.getSourceOccurrenceLineage()).toList();
            check(!originals.isEmpty(),"actual source variable occurrence");var o=originals.get(0);
            check(o.getExactAlloyType().sameOccurrenceEvidenceAs(n.getExactAlloyType()),"original variable parser/type evidence");
            for(int i=0;i<source.getMatrixQuantiVars().size();i++)if(source.getMatrixQuantiVars().get(i).getName().equals(o.getAlphaName())){
                check(o.getAlphaName().equals(n.getAlphaName())&&phase.bindings().get(i).ordinal()==i,"original outer slot, not canonical descriptor coordinate");return "VARIABLE/outer:"+i;
            }
            var locals=source.getLocalQuantiVars().stream().filter(q->q.getName().equals(o.getAlphaName())).toList();check(locals.size()==1,"unique actual local owner");
            var matcher=java.util.regex.Pattern.compile("local#([0-9]+)#([0-9]+):.*").matcher(locals.get(0).getDeBruijnKey());check(matcher.matches(),"source local lexical key");
            String coordinate=matcher.group(1)+":"+matcher.group(2);check(n.getAlphaName().equals("@fast-local:"+coordinate),"local source/current coordinate");return "VARIABLE/local:"+coordinate;
        }
        String identity=switch(n.getOpcode()){case GLOBALBINDING,CONSTANT -> n.getSemanticIdentity()==null?n.getSourceName():n.getSemanticIdentity();default -> "";};
        return n.getOpcode()+"/identity="+identity+(n.getOpcode()==EGraphNode.Opcode.GENERICRELDECL?";structure=certified-local-declaration":";type="+NestedPaidDeclarationReplayTest.type(n));
    }
    private static String certHead(Node n){
        if(n.isVariable()){if(n.bindingIndex()>=0)return "VARIABLE/outer:"+n.bindingIndex();check(n.lexicalVariable().matches("@local\\[[0-9]+:[0-9]+\\]"),"projected local owner");return "VARIABLE/local:"+n.lexicalVariable().substring(7,n.lexicalVariable().length()-1);}
        return n.operator()+"/"+n.semanticPayload();
    }
    private static String fastKey(EGraphNode n,NormalForm source,Phase phase){var children=new ArrayList<String>();for(var c:FastChainGeometry.children(n))children.add(fastKey(c,source,phase));if(n.isOrderInsensitive())Collections.sort(children);return new JSONArray().put(fastHead(n,source,phase)).put(children).toString();}
    private static String certKey(Node n){var children=new ArrayList<String>();for(var c:n.children())children.add(certKey(c));if(n.orderInsensitive())Collections.sort(children);return new JSONArray().put(certHead(n)).put(children).toString();}
    private static void bridge(EGraphNode a,Node b,NormalForm source,Phase phase,String ap,String bp,Map<String,String> paths){
        check(paths.size()<512&&fastHead(a,source,phase).equals(certHead(b))&&a.isOrderInsensitive()==b.orderInsensitive(),"typed source-linked endpoint head "+ap);paths.put(ap,bp);
        var ac=FastChainGeometry.children(a);var bc=b.children();check(ac.size()==bc.size(),"same metric child coverage");var used=new HashSet<Integer>();
        for(int i=0;i<ac.size();i++){
            int j=i;if(a.isOrderInsensitive()){String key=fastKey(ac.get(i),source,phase);var matches=new ArrayList<Integer>();for(int k=0;k<bc.size();k++)if(!used.contains(k)&&key.equals(certKey(bc.get(k))))matches.add(k);check(matches.size()==1,"unambiguous occurrence correspondence");j=matches.get(0);}
            check(used.add(j),"injective endpoint correspondence");bridge(ac.get(i),bc.get(j),source,phase,ap+"/"+i,bp+"/"+j,paths);
        }
    }
    private static void actions(JSONObject d,String a,String b,Map<String,String> ap,Map<String,String> bp,JSONArray out){
        check(out.length()<512,"action budget");String kind=d.getString("kind");check(List.of("node","insert","delete").contains(kind),"no implicit alternative admission");
        out.put(new JSONObject().put("stage","matrix").put("kind",kind).put("cost",d.getInt(kind.equals("node")?"headCost":"cost")).put("source",a==null?JSONObject.NULL:Objects.requireNonNull(ap.get(a))).put("target",b==null?JSONObject.NULL:Objects.requireNonNull(bp.get(b))));
        if(kind.equals("node"))for(Object item:d.getJSONArray("children")){var c=(JSONObject)item;int i=c.getInt("leftIndex"),j=c.getInt("rightIndex");actions(c.getJSONObject("edit"),i<0?null:a+"/"+i,j<0?null:b+"/"+j,ap,bp,out);}
    }
    private static List<String> ordinals(List<DeclarationReplay.Atom> atoms){return atoms.stream().map(a->Integer.toString(new JSONArray(a.occurrence()).getInt(4))).toList();}
    private static JSONArray declarationActions(JSONObject receipt,boolean certified){
        var out=new JSONArray();for(Object item:receipt.getJSONArray("operations")){var op=(JSONObject)item;var row=new JSONObject().put("stage","declaration").put("kind",op.getString("kind")).put("cost",op.getInt("cost"));
            for(String side:List.of("source","target")){if(op.isNull(side)){row.put(side,JSONObject.NULL);row.put(side+"Tuple",JSONObject.NULL);continue;}var atom=op.getJSONObject(side);String id=atom.getString("occurrence");int ordinal=certified?new JSONArray(id).getInt(4):Integer.parseInt(id.substring(id.lastIndexOf('/')+1));row.put(side,ordinal).put(side+"Tuple",atom.getJSONArray("tuple"));}out.put(row);}return out;
    }
    private static void verify(Canonical.Prepared a,Canonical.Prepared b,RepairView av,RepairView bv,JSONObject raw,boolean after){
        var cert=raw.getJSONObject("certified");var cp=cert.getJSONArray("phases").getJSONObject(0);int[] mapping=cp.getJSONArray("alphaMapping").toList().stream().mapToInt(x->((Number)x).intValue()).toArray();
        check(Arrays.equals(mapping,mapping.length==2?new int[]{0,-1}:new int[]{0})&&cp.getJSONArray("alphaMapping").similar(cert.getJSONArray("selectedGlobalMapping")),"source-ordinal selected correspondence");
        check(QuotientRepairDistance.verifyFixedMatrixReplay(av,bv,mapping,mapping,cp.getJSONObject("receipt"))==24,"certified fixed matrix24 first");
        check(NestedPaidDeclarationReplayTest.certChecker(mapping).verify(av.phases().get(0).matrix(),bv.phases().get(0).matrix(),cp.getJSONObject("receipt"))==24,"separate explicit node-cost verifier24");
        var components=cert.getJSONObject("declarationSlice").getJSONArray("components");check(components.length()==2&&DeclarationReplay.verify(List.of(),List.of(),components.getJSONObject(0).getJSONObject("receipt"))==0,"parameter identity");
        var cd=components.getJSONObject(1).getJSONObject("receipt");var ca=NestedPaidDeclarationReplayTest.atoms(av.phases().get(0),cd.getJSONArray("source"));var cb=NestedPaidDeclarationReplayTest.atoms(bv.phases().get(0),cd.getJSONArray("target"));
        var an=ordinals(ca);var bn=ordinals(cb);check(new DeclarationMatrixLink(an,bn,Map.of("0","0"),Set.copyOf(an),Set.copyOf(bn)).verify(ca,cb,cd)==2,"actual paid original-ordinal declaration2");
        check(cert.getJSONObject("selectedQuantifierCorrespondence").similar(new JSONObject().put("0",0)),"paid diagonal covers selected map");
        for(String side:List.of("left","right")){var order=cert.getJSONObject("selectedDeclarationPermutation").getJSONArray(side);for(int i=0;i<order.length();i++)check(order.getInt(i)==i,"no selected nonidentity orbit");}
        check(TemporalReplay.verify(av.temporalRoot(),bv.temporalRoot(),TemporalNode::label,TemporalNode::children,cert.getJSONObject("temporalSlice"))==0,"unchanged temporal identity");
        check(PhaseReplay.verifyDiagnosticOrder(cert.getJSONObject("temporalSlice"),cert.getJSONObject("declarationSlice"),cert.getJSONArray("phases"),cert.getJSONArray("orderedDiagnosticActions"))==26&&cert.getInt("distance")==26,"all shorter native costs and order");
        int[] absent=new int[mapping.length];Arrays.fill(absent,-1);reject(()->QuotientRepairDistance.verifyFixedMatrixReplay(av,bv,mapping,absent,cp.getJSONObject("receipt")),"unpaid matrix map");
        reject(()->QuotientRepairDistance.verifyFixedMatrixReplay(bv,av,mapping,mapping,cp.getJSONObject("receipt")),"substituted endpoints");
        var forged=copy(cd);forged.getJSONArray("source").getJSONObject(0).put("admission","foreign");reject(()->DeclarationReplay.verify(ca,cb,forged),"foreign declaration source/domain");
        var fp=CanonicalDistance.prepare(a.normalizedForms());var gp=CanonicalDistance.prepare(b.normalizedForms());var fast=raw.getJSONObject("fast");var map=after?Map.of("_q0","_q0"):Map.<String,String>of();
        check(CanonicalDistance.verifyFixedMatrixReplay(fp,gp,map,map,fast.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt"))==(after?24:25),"own Fast fixed body cost");
        if(!after){check(!fast.getJSONObject("declarationSlice").has("components"),"historical Fast missing declaration link retained");return;}
        check(fast.getJSONObject("selectedPaidDeclarationCorrespondence").similar(new JSONObject(map))&&fast.getInt("distance")==26,"actual new paid path, not scalar substitution");
        var fd=fast.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0).getJSONObject("receipt");
        var sourceAtoms=CanonicalDistance.diagnosticDeclarationAtoms(original(a));var targetAtoms=CanonicalDistance.diagnosticDeclarationAtoms(original(b));
        // The emitted declaration DP uses typed canonical order; atoms retain
        // original ordinal identity. Do not confuse these two coordinates.
        var fa=CanonicalDistance.diagnosticDeclarationOrder(original(a)).stream().map(sourceAtoms::get).toList();
        var fb=CanonicalDistance.diagnosticDeclarationOrder(original(b)).stream().map(targetAtoms::get).toList();
        var namesA=fa.stream().map(x->new JSONArray(x.admission()).getString(0)).toList();var namesB=fb.stream().map(x->new JSONArray(x.admission()).getString(0)).toList();
        check(new DeclarationMatrixLink(namesA,namesB,map,Set.copyOf(namesA),Set.copyOf(namesB)).verify(fa,fb,fd)==2,"Fast joint original declaration2");
        check(PhaseReplay.verifyDiagnosticOrder(fast.getJSONObject("temporalSlice"),fast.getJSONObject("declarationSlice"),fast.getJSONArray("phases"),fast.getJSONArray("orderedDiagnosticActions"))==26,"Fast full native diagnostic order");
        reject(()->CanonicalDistance.verifyFixedMatrixReplay(fp,gp,map,Map.of(),fast.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt")),"Fast unpaid mapping");
        var bad=copy(fd);bad.getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(1,"ForeignType");reject(()->DeclarationReplay.verify(fa,fb,bad),"Fast declaration type substitution");
        check(declarationActions(fd,false).similar(declarationActions(cd,true)),"all ordered declarations in original coordinates");
    }
    public static void main(String[] args)throws Exception{
        Path out=args.length==0?Files.createTempDirectory("mixed-retained-"):Path.of(args[0]);Files.createDirectories(out);
        var pair=prepare(Files.readString(SOURCE),"inv12","inv12c");var a=pair.get(0);var b=pair.get(1);
        var views=List.of(NestedPaidDeclarationReplayTest.checkedProjection(a,b),NestedPaidDeclarationReplayTest.checkedProjection(b,a));
        views.forEach(v->NestedPaidDeclarationReplayTest.noChoices(v.phases().get(0).matrix()));
        check(views.get(0).phases().get(0).bindings().stream().map(Binding::coordinate).toList().equals(List.of(1,0)),"actual nonidentity descriptor coordinate bridge");
        check(owners(original(a)).stream().map(EGraphNode::getOpcode).toList().equals(List.of(EGraphNode.Opcode.EXISTS,EGraphNode.Opcode.FORALL)),"actual retained SOME/ALL order");
        check(ownerCount(original(a))==2&&ownerCount(comparison(a))==2,"original and comparison linked mixed owners");
        check(pair(comparison(a),comparison(b))&&pair(comparison(b),comparison(a)),"candidate coverage both directions");
        for(int d=0;d<2;d++){
            var l=pair.get(d);var r=pair.get(1-d);var lv=views.get(d);var rv=views.get(1-d);var raw=DistanceReplayDiagnostics.capture(l,r);
            if(args.length==2){var before=new JSONObject(Files.readString(Path.of(args[1]).resolve("boundary-"+d+".json"))).getJSONObject("diagnostic");verify(l,r,lv,rv,before,false);check(before.getJSONObject("certified").similar(raw.getJSONObject("certified")),"certified receipt unchanged byte-value structure");}
            verify(l,r,lv,rv,raw,true);
            var ap=new HashMap<String,String>();var bp=new HashMap<String,String>();bridge(comparison(l).getMatrixEGraph(),lv.phases().get(0).matrix(),original(l),lv.phases().get(0),"$","$",ap);bridge(comparison(r).getMatrixEGraph(),rv.phases().get(0).matrix(),original(r),rv.phases().get(0),"$","$",bp);
            var ai=new HashMap<String,String>();var bi=new HashMap<String,String>();ap.values().forEach(x->ai.put(x,x));bp.values().forEach(x->bi.put(x,x));
            var fa=declarationActions(raw.getJSONObject("fast").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0).getJSONObject("receipt"),false);
            var ca=declarationActions(raw.getJSONObject("certified").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt"),true);
            actions(raw.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").getJSONObject("derivation"),"$","$",ap,bp,fa);
            actions(raw.getJSONObject("certified").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").getJSONObject("derivation"),"$","$",ai,bi,ca);
            int first=0;while(first<Math.min(fa.length(),ca.length())&&fa.getJSONObject(first).similar(ca.getJSONObject(first)))first++;
            var result=new JSONObject().put("diagnostic",raw).put("fastActions",fa).put("certifiedActions",ca).put("orderedActionsEqual",fa.similar(ca)).put("firstDifference",first).put("publicCertification","BLOCKED");
            Files.writeString(out.resolve("after-"+d+".json"),result.toString(2),StandardOpenOption.CREATE_NEW);
            check(fa.similar(ca),"ordered original-coordinate actions differ at "+first);
            var comparator=NativeOperationComparison.compare(l,r,CanonicalAlloyPipeline.prepare(l),CanonicalAlloyPipeline.prepare(r));
            check(comparator.status()==NativeOperationComparison.Status.MATCHED
                    &&comparator.evidence().getJSONArray("fastActions").similar(comparator.evidence().getJSONArray("certifiedActions")),
                    "production original-coordinate correspondence agrees with fixed-choice replay");
            check(comparator.evidence().getJSONObject("diagnostic").getJSONObject("certified").similar(raw.getJSONObject("certified")),
                    "recaptured certified path is the independently checked path");
        }
        check(!pair(original(a),original(a)),"local/local scope unchanged");
        corrupt(a,b,f->field(owners(f).get(1),"opcode",EGraphNode.Opcode.EXISTS),"same supported kind but wrong original quantifier");
        corrupt(a,b,f->field(owners(f).get(1),"sourceOccurrenceLineage",-1L),"foreign owner");
        corrupt(a,b,f->field(owners(f).get(1).getChildren().get(0),"sourceOccurrenceLineage",-1L),"foreign declaration");
        corrupt(a,b,f->field(owners(f).get(1).getChildren().get(0).getChildren().get(1),"sourceOccurrenceLineage",-1L),"foreign variable");
        corrupt(a,b,f->field(owners(f).get(1).getChildren().get(0).getChildren().get(0),"sourceOccurrenceLineage",-1L),"foreign domain");
        corrupt(a,b,f->field(owners(f).get(1).getChildren().get(0).getChildren().get(0).getChildren().get(0),"semanticIdentity","foreign"),"same-type changed domain");
        corrupt(a,b,f->field(owners(f).get(1).getChildren().get(0).getChildren().get(1),"alphaName","_q0"),"local captures outer");
        corrupt(a,b,f->field(f,"matrixEGraphRoot",owners(f).get(1)),"changed parent edge");
        var foreign=prepare(Files.readString(SOURCE),"inv12").get(0);
        corrupt(a,b,f->field(f,"certificationMatrixEGraphRoot",original(foreign).getCertificationMatrixEGraph()),"foreign source ledger");
        corrupt(a,b,f->field(owners(f).get(1).getChildren().get(0).getChildren().get(1),"exactAlloyType",owners(original(foreign)).get(1).getChildren().get(0).getChildren().get(1).getExactAlloyType()),"foreign parser type");
        corrupt(a,b,f->field(f,"temporalOp",NormalForm.TemporalOp.ALWAYS),"temporal owner");
        for(String name:List.of("params","inheritedQuantiVars"))corrupt(a,b,f->field(f,name,List.of(original(a).getMatrixQuantiVars().get(0))),"unsupported "+name);
        corrupt(a,b,f->field(f,"phaseLocalBindingImports",List.of(new Object())),"nonempty imported-owner boundary");
        String prefix="sig P { r:set P } sig S,T,U in P {} pred base { all p:P | some p.r } ";
        for(String quantifier:List.of("some","all","one","lone")){
            String text=prefix+"pred nested { some p:S | p in p.r implies (some t:T | p in t.r implies ("+quantifier+" u:U | u in t.r and t in u.r)) }";
            var forms=prepare(text,"nested","base");var f=original(forms.get(0));
            check(owners(f).size()==2,"actual two retained owners "+quantifier);
            check(ownerCount(f)==(quantifier.equals("some")||quantifier.equals("all")?2:0),"ordered supported chain kinds "+quantifier);
        }
        for(String body:List.of("some disj t,u:T | t in p.r and u in p.r","some t:set T | p in t.r","some t:p.r | some u:t.r | u in p.r")){
            var forms=prepare(prefix+"pred negative { some p:S | p in p.r implies ("+body+") }","negative","base");
            check(!original(forms.get(0)).getLocalQuantiVars().isEmpty(),"negative fixture retains actual local metadata: "+body);
            check(!pair(original(forms.get(0)),original(forms.get(1))),"disjoint/set/dependent domains unsupported: "+body);
        }
        var controls=new JSONArray();
        for(var row:List.of(new String[]{"JoNXaXfKNJzGh3BFa_inv12","inv12","22"},new String[]{"SASEHD8jPo2HgsuoJ_inv14","inv14","27"},new String[]{"YKy8LYsKdapW3xqbn_inv14","inv14","20"},new String[]{"xJnitBpFv9K74WNvf_inv14","inv14","49"},new String[]{"85tWoTeBhoEDhCKmw_inv14","inv14","37"},new String[]{"Wq9Au27ys6iRip6CD_inv14","inv14","36"},new String[]{"ft2Q8x9bQa59gFBR4_inv14","inv14","17"},new String[]{"hJWX73Rdw3RziF3uu_inv14","inv14","17"})){
            var forms=prepare(Files.readString(Path.of("classified-data",row[0].startsWith("xJnit")?"coursesOld":"classroom_fol","both",row[0]+".als")),row[1],row[1]+"c");
            for(int d=0;d<2;d++){var r=DistanceReplayDiagnostics.capture(forms.get(d),forms.get(1-d));for(String arm:List.of("fast","certified"))check(r.getJSONObject(arm).getInt("distance")==Integer.parseInt(row[2])&&r.getJSONObject(arm).getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"prior cost/joint-link snapshot "+row[0]);}
            controls.put(new JSONObject().put("source",row[0]).put("distance",Integer.parseInt(row[2])));
        }
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("MixedRetainedAdmissionReplayTest: "+checks+" checks passed; both directions26, ordered native actions; production layout UNCHECKABLE");
    }
}
