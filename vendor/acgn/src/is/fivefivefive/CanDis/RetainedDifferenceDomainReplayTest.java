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
import is.fivefivefive.CanDis.theory.*;
import parser.ast.nodes.ModelUnit;

/** Retained carrier-minus-outer domains: source eligibility, not domain erasure. */
public final class RetainedDifferenceDomainReplayTest {
    private static int checks;
    private static final Path SOURCE=Path.of("classified-data/coursesNew/both/5vxYh7NCpEPMmC47p_inv14.als");
    private record Pair(Canonical.Prepared a,Canonical.Prepared b){Pair reverse(){return new Pair(b,a);}}
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static void reject(Runnable r,String why){checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException e){return;}throw new AssertionError(why);}
    private static boolean supports(List<NormalForm> forms){try{var m=Class.forName("is.fivefivefive.CanDis.core.FastPaidDeclarationAlignment").getDeclaredMethod("supports",List.class);m.setAccessible(true);return (boolean)m.invoke(null,forms);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static Pair prepare(String text,String left,String right)throws Exception{
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var p=new ArrayList<Canonical.Prepared>();
        for(String name:List.of(left,right)){var v=new MASGVisitor(new GlobalVariables(),Set.of(name),m);v.visit(new ModelUnit(null,m),null);p.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));}return new Pair(p.get(0),p.get(1));
    }
    private static void field(Object o,String name,Object value){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static EGraphNode declaration(NormalForm f){var pending=new ArrayDeque<EGraphNode>();pending.add(f.getMatrixEGraph());int budget=4096;while(!pending.isEmpty()){check(--budget>0,"node budget");var n=pending.remove();if(n.getOpcode()==EGraphNode.Opcode.GENERICRELDECL)return n;pending.addAll(n.getChildren());}throw new AssertionError("missing retained declaration");}
    private static EGraphNode domain(NormalForm f){return declaration(f).getChildren().get(0);}
    private static void corrupt(Pair p,Consumer<NormalForm> change,String why){var fs=CanonicalDistance.prepare(p.a.normalizedForms()).comparisonForms();change.accept(fs.get(0));check(!supports(fs),why);}
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static int[] map(JSONArray j){int[] a=new int[j.length()];for(int i=0;i<a.length;i++)a[i]=j.getInt(i);return a;}
    private static Map<String,String> map(JSONObject j){var m=new TreeMap<String,String>();for(String k:j.keySet())m.put(k,j.getString(k));return m;}
    private static List<String> ordered(List<String> names,JSONArray atoms){var r=new ArrayList<String>();for(Object x:atoms)r.add(names.get(new JSONArray(((JSONObject)x).getString("occurrence")).getInt(4)));return r;}
    private static List<DeclarationReplay.Atom> fastAtoms(NormalForm f){var atoms=CanonicalDistance.diagnosticDeclarationAtoms(f);return CanonicalDistance.diagnosticDeclarationOrder(f).stream().map(atoms::get).toList();}
    private static void localEvidence(Canonical.Prepared p){
        var e=TheoryAlloyAdapter.adapt(p.normalizedForms(),p.semanticProfile());e.requireRepairProjectionSources(p.normalizedForms());
        check(e.localBinderDescriptors().size()==1,"one original retained local owner");
        for(var entry:e.localBinderDescriptors().entrySet()){
            var n=entry.getKey();var d=entry.getValue();var q=p.normalizedForms().get(0).getLocalQuantiVars().stream().filter(v->e.localBinderSourceCoordinates().get(n).containsKey(v.getName())).findFirst().orElseThrow();
            check(n.getOpcode()==EGraphNode.Opcode.ONE&&q.getQuantifier()==QuantiVar.Quantifier.ONE&&q.getCardinality()==QuantiVar.Cardinality.ONE,"actual local ONE owner/kind");
            check(d.coordinates().size()==1&&d.coordinates().get(0).domain().toString().contains("alloy-local-domain-port"),"ambient domain retained as typed input port, not erased annotation");
            check(d.coordinates().get(0).type().equals(GraphType.constructor("AlloyCarrier",List.of(GraphType.constructor(q.getCarrierTypeName())))),"local typed coordinate");
            for(var proof:d.automorphisms().generatorCertificates())CertificateVerifier.verify(proof);
        }
    }
    // Finite endpoint bridge: matrix binding indices are original list positions,
    // NOT the descriptor's type-sorted coordinates. Both are checked separately.
    private static String fastHead(EGraphNode n,NormalForm f,Phase p){
        if(n.getOpcode()==EGraphNode.Opcode.VARIABLE){
            String name=n.getAlphaName();
            if(name.startsWith("@fast-local:"))return "VARIABLE/local:"+name.substring(12);
            int ordinal=-1;for(int i=0;i<f.getMatrixQuantiVars().size();i++)if(f.getMatrixQuantiVars().get(i).getName().equals(name))ordinal=i;
            check(ordinal>=0,"original outer alias");int index=-1;
            for(int i=0;i<p.bindings().size();i++){var b=p.bindings().get(i);if(b.role()==BindingRole.MATRIX&&b.ordinal()==ordinal){check(index<0&&b.ownerPhase()==0&&b.ownerContext().isEmpty(),"unique original owner");index=i;}}
            check(index>=0,"projected original index");return "VARIABLE/outer:"+index;
        }
        String atom=switch(n.getOpcode()){case GLOBALBINDING,CONSTANT,REF -> n.getSemanticIdentity()==null?n.getSourceName():n.getSemanticIdentity();default -> "";};
        return n.getOpcode()+"/identity="+atom+(n.getOpcode()==EGraphNode.Opcode.GENERICRELDECL?";structure=certified-local-declaration":";type="+NestedPaidDeclarationReplayTest.type(n));
    }
    private static String certHead(Node n){if(n.isVariable()){if(n.bindingIndex()>=0)return "VARIABLE/outer:"+n.bindingIndex();String s=n.lexicalVariable();check(s.matches("@local\\[[0-9]+:[0-9]+\\]"),"local owner coordinate");return "VARIABLE/local:"+s.substring(7,s.length()-1);}return n.operator()+"/"+n.semanticPayload();}
    private static String fastKey(EGraphNode n,NormalForm f,Phase p){var xs=new ArrayList<String>();for(var c:FastChainGeometry.children(n))xs.add(fastKey(c,f,p));if(n.isOrderInsensitive())Collections.sort(xs);return new JSONArray().put(fastHead(n,f,p)).put(xs).toString();}
    private static String certKey(Node n){var xs=new ArrayList<String>();for(var c:n.children())xs.add(certKey(c));if(n.orderInsensitive())Collections.sort(xs);return new JSONArray().put(certHead(n)).put(xs).toString();}
    private static void bridge(EGraphNode a,Node b,NormalForm f,Phase p,String ap,String bp,Map<String,String> paths,int depth){
        check(depth<128&&paths.size()<4096,"bridge budget");check(fastHead(a,f,p).equals(certHead(b)),"typed original endpoint head "+ap+" "+fastHead(a,f,p)+" vs "+certHead(b));
        check(a.isOrderInsensitive()==b.orderInsensitive(),"order metadata");check(b.certifiedAlternatives().isEmpty(),"no unchecked alternative");paths.put(ap,bp);
        var ac=FastChainGeometry.children(a);var bc=b.children();check(ac.size()==bc.size(),"child coverage");var used=new HashSet<Integer>();
        for(int i=0;i<ac.size();i++){int j=i;if(a.isOrderInsensitive()){var matches=new ArrayList<Integer>();for(int k=0;k<bc.size();k++)if(!used.contains(k)&&fastKey(ac.get(i),f,p).equals(certKey(bc.get(k))))matches.add(k);check(matches.size()==1,"unambiguous typed occurrence bridge "+ap);j=matches.get(0);}check(used.add(j),"bijective child bridge");bridge(ac.get(i),bc.get(j),f,p,ap+"/"+i,bp+"/"+j,paths,depth+1);}
    }
    private static void actions(JSONObject d,String a,String b,Map<String,String> ap,Map<String,String> bp,JSONArray out){
        String kind=d.getString("kind");check(List.of("node","insert","delete").contains(kind),"known action kind");out.put(new JSONObject().put("kind",kind).put("cost",d.getInt(kind.equals("node")?"headCost":"cost")).put("source",a==null?JSONObject.NULL:Objects.requireNonNull(ap.get(a))).put("target",b==null?JSONObject.NULL:Objects.requireNonNull(bp.get(b))));
        if(kind.equals("node"))for(Object x:d.getJSONArray("children")){var c=(JSONObject)x;int i=c.getInt("leftIndex"),j=c.getInt("rightIndex");actions(c.getJSONObject("edit"),i<0?null:a+"/"+i,j<0?null:b+"/"+j,ap,bp,out);}
    }
    private static JSONArray declarationActions(JSONObject receipt){var out=new JSONArray();for(Object item:receipt.getJSONArray("operations")){var op=(JSONObject)item;var action=new JSONObject();for(String k:List.of("kind","cost","leftIndex","rightIndex"))action.put(k,op.get(k));for(String k:List.of("source","target"))action.put(k,op.isNull(k)?JSONObject.NULL:op.getJSONObject(k).getJSONArray("tuple"));out.put(action);}return out;}
    private static JSONObject replay(Pair p,JSONObject old){
        var va=NestedPaidDeclarationReplayTest.checkedProjection(p.a,p.b);var vb=NestedPaidDeclarationReplayTest.checkedProjection(p.b,p.a);
        var capture=DistanceReplayDiagnostics.capture(p.a,p.b);var cert=capture.getJSONObject("certified");var cp=cert.getJSONArray("phases").getJSONObject(0);var cm=map(cp.getJSONArray("alphaMapping"));
        check(QuotientRepairDistance.verifyFixedMatrixReplay(va,vb,cm,cm,cp.getJSONObject("receipt"))==44,"shorter certified fixed-choice matrix44");
        if(old!=null){check(old.getJSONObject("certified").getJSONArray("phases").similar(cert.getJSONArray("phases")),"certified matrix receipts unchanged");check(old.getJSONObject("certified").getJSONObject("declarationSlice").similar(cert.getJSONObject("declarationSlice")),"certified declaration receipts unchanged");}
        var na=p.a.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();var nb=p.b.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();var chosen=new TreeMap<String,String>();for(int i=0;i<cm.length;i++)if(cm[i]>=0)chosen.put(na.get(i),nb.get(cm[i]));
        var cd=cert.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt");var aa=NestedPaidDeclarationReplayTest.atoms(va.phases().get(0),cd.getJSONArray("source"));var bb=NestedPaidDeclarationReplayTest.atoms(vb.phases().get(0),cd.getJSONArray("target"));
        var ca=ordered(na,cd.getJSONArray("source"));var cb=ordered(nb,cd.getJSONArray("target"));
        check(new DeclarationMatrixLink(ca,cb,chosen,Set.copyOf(na),Set.copyOf(nb)).verify(aa,bb,cd)==3,"same paid path includes zero keep, deletion/insertion and two paid updates");reject(()->new DeclarationMatrixLink(ca,cb,Map.of("_q0","_q0"),Set.copyOf(na),Set.copyOf(nb)).verify(aa,bb,cd),"old partial mapping cannot license paid path");
        var bad=copy(cd);bad.getJSONArray("source").getJSONObject(0).put("admission","foreign");reject(()->DeclarationReplay.verify(aa,bb,bad),"foreign declaration premise");
        var fast=capture.getJSONObject("fast");var fp=fast.getJSONArray("phases").getJSONObject(0);var fm=map(fp.getJSONObject("alphaMapping"));check(fm.equals(chosen),"same original-coordinate paid mapping");
        var fa=CanonicalDistance.prepare(p.a.normalizedForms());var fb=CanonicalDistance.prepare(p.b.normalizedForms());var fr=fp.getJSONObject("receipt");var cr=cp.getJSONObject("receipt");
        check(CanonicalDistance.verifyFixedMatrixReplay(fa,fb,fm,fm,fr)==44,"Fast fixed original matrix44");reject(()->CanonicalDistance.verifyFixedMatrixReplay(fa,fb,fm,Map.of(),fr),"missing paid links");reject(()->CanonicalDistance.verifyFixedMatrixReplay(fb,fa,fm,fm,fr),"substituted original endpoints");reject(()->CanonicalDistance.verifyFixedMatrixReplay(fa,fb,fm,fm,copy(fr).put("cost",43)),"forged action cost");
        for(var arm:List.of(fast,cert))check(arm.getInt("distance")==47&&arm.getInt("quantifierDistance")==3&&arm.getInt("temporalDistance")==0&&arm.getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"joint numerical coverage47");
        var fd=fast.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0).getJSONObject("receipt");
        check(new DeclarationMatrixLink(ca,cb,fm,Set.copyOf(na),Set.copyOf(nb)).verify(fastAtoms(p.a.normalizedForms().get(0)),fastAtoms(p.b.normalizedForms().get(0)),fd)==3,"original Fast declarations independently replayed with same joint map");
        check(declarationActions(fd).similar(declarationActions(cd)),"ordered original declaration actions equal");
        var ap=new HashMap<String,String>();var bp=new HashMap<String,String>();bridge(fa.comparisonForms().get(0).getMatrixEGraph(),va.phases().get(0).matrix(),p.a.normalizedForms().get(0),va.phases().get(0),"$","$",ap,0);bridge(fb.comparisonForms().get(0).getMatrixEGraph(),vb.phases().get(0).matrix(),p.b.normalizedForms().get(0),vb.phases().get(0),"$","$",bp,0);
        var ai=new HashMap<String,String>();var bi=new HashMap<String,String>();ap.values().forEach(x->ai.put(x,x));bp.values().forEach(x->bi.put(x,x));var af=new JSONArray();var ac=new JSONArray();actions(fr.getJSONObject("derivation"),"$","$",ap,bp,af);actions(cr.getJSONObject("derivation"),"$","$",ai,bi,ac);
        int first=0;while(first<Math.min(af.length(),ac.length())&&af.getJSONObject(first).similar(ac.getJSONObject(first)))first++;
        check(af.similar(ac),"ordered matrix actions differ at "+first+": "+(first<af.length()?af.get(first):"EOF")+" / "+(first<ac.length()?ac.get(first):"EOF"));
        var nativeResult=NativeOperationComparison.compare(p.a,p.b,CanonicalAlloyPipeline.prepare(p.a),CanonicalAlloyPipeline.prepare(p.b));
        check(nativeResult.status()==NativeOperationComparison.Status.MATCHED
                &&nativeResult.evidence().getJSONArray("fastActions").similar(nativeResult.evidence().getJSONArray("certifiedActions")),
                "original ordinals link vector indices to canonical binder slots");
        check(!capture.getBoolean("complete")&&!capture.getBoolean("publicSequenceLinked")&&!capture.getBoolean("javaLeanCorrespondenceChecked"),"full certification remains blocked");
        return new JSONObject().put("capture",capture).put("fastActions",af).put("certifiedActions",ac).put("declarationActions",declarationActions(fd)).put("finiteOrderedActionsEqual",true).put("nativeComparison",nativeResult.evidence()).put("certificationStatus","BLOCKED");
    }
    public static void main(String[] args)throws Exception{
        var out=args.length==0?Files.createTempDirectory("retained-difference-"):Path.of(args[0]);Files.createDirectories(out);var p=prepare(Files.readString(SOURCE),"inv14","inv14c");
        check(supports(p.a.normalizedForms())&&supports(p.b.normalizedForms()),"both original endpoints admitted");check(supports(CanonicalDistance.prepare(p.a.normalizedForms()).comparisonForms()),"comparison domain linked to original source");localEvidence(p.a);
        var foreign=prepare(Files.readString(SOURCE),"inv14","inv14c");var foreignType=domain(foreign.a.normalizedForms().get(0)).getExactAlloyType();
        corrupt(p,f->field(domain(f),"opcode",EGraphNode.Opcode.SETOF),"set local multiplicity");
        corrupt(p,f->field(domain(f).getChildren().get(0),"opcode",EGraphNode.Opcode.PLUS),"unsupported domain union");
        corrupt(p,f->field(domain(f).getChildren().get(0),"sourceOccurrenceLineage",-1L),"foreign domain expression lineage");
        corrupt(p,f->field(domain(f).getChildren().get(0).getChildren().get(0),"semanticIdentity","alloy/signature/Other"),"different carrier identity");
        corrupt(p,f->field(domain(f).getChildren().get(0).getChildren().get(1),"sourceOccurrenceLineage",-1L),"foreign dependency occurrence");
        corrupt(p,f->field(domain(f).getChildren().get(0).getChildren().get(1),"alphaName",declaration(f).getChildren().get(1).getAlphaName()),"self-domain capture");
        corrupt(p,f->field(domain(f).getChildren().get(0).getChildren().get(1),"alphaName","_q0"),"wrong outer type/owner");
        corrupt(p,f->field(domain(f),"exactAlloyType",foreignType),"foreign parser module");
        corrupt(p,f->field(domain(f),"exactAlloyType",null),"missing result type");
        corrupt(p,f->field(declaration(f),"sourceOccurrenceLineage",-1L),"foreign declaration owner");
        corrupt(p,f->field(f,"certificationMatrixEGraphRoot",foreign.a.normalizedForms().get(0).getCertificationMatrixEGraph()),"foreign original source");
        for(int d=0;d<2;d++){var old=args.length<2?null:new JSONObject(Files.readString(Path.of(args[1]).resolve("walk-"+d+".json")));Files.writeString(out.resolve("after-"+d+".json"),replay(d==0?p:p.reverse(),old).toString(2),StandardOpenOption.CREATE_NEW);}
        String base="sig P { r:set P } pred b {some P} ";
        for(String kind:List.of("all","some","one","lone")){
            boolean nonmonotone=kind.equals("one")||kind.equals("lone");
            String body=nonmonotone?"x in x.r implies ("+kind+" y:P-x | y in x.r)":"("+kind+" y:P-x | y in x.r) iff x in x.r";
            var pair=prepare(base+"pred a {all x:P | "+body+"}","a","b");check(!pair.a.normalizedForms().get(0).getLocalQuantiVars().isEmpty()&&supports(pair.a.normalizedForms()),"retained difference kind "+kind);
            if(nonmonotone){var duplicated=prepare(base+"pred a {all x:P | ("+kind+" y:P-x | y in x.r) iff x in x.r}","a","b");check(!supports(duplicated.a.normalizedForms()),"iff duplicates nonmonotone local owners; existing multi-owner boundary");}
        }
        var sameType=prepare(base+"pred a {all x:P | all z:P | z in x.r implies (one y:P-x | y in z.r)}","a","b");
        check(supports(sameType.a.normalizedForms()),"two same-type outer owners");
        corrupt(sameType,f->{var dep=domain(f).getChildren().get(0).getChildren().get(1);String other=f.getMatrixQuantiVars().stream().map(QuantiVar::getName).filter(n->!n.equals(dep.getAlphaName())).findFirst().orElseThrow();field(dep,"alphaName",other);},"same-type foreign outer owner rejected by original occurrence");
        for(String text:List.of("pred a[z:P] {all x:P | x in z.r implies (one y:P-x | y in x.r)}","pred a {all x:P | always (x in x.r implies (one y:P-x | y in x.r))}","pred a {all x:P | x in x.r implies (one y:set(P-x) | y in x.r)}","pred a {all x:P | x in x.r implies (one disj y,z:P-x | y in x.r and z in x.r)}","pred a {all x:P | x in x.r implies (one y:x-P | y in x.r)}","pred a {all x:P | x in x.r implies (one y:x.r | y in x.r)}")){
            var pair=prepare(base+text,"a","b");check(!supports(pair.a.normalizedForms()),"unsupported retained scope "+text);}
        var controls=new JSONArray();for(var row:List.of(new String[]{"coursesOld/both/xJnitBpFv9K74WNvf_inv14","inv14","49"},new String[]{"classroom_fol/both/JoNXaXfKNJzGh3BFa_inv12","inv12","22"},new String[]{"classroom_rl/over/AE26k3aCggeBrqCrh_inv8","inv8","23"},new String[]{"coursesNew/both/5TvX7MDJN22SSvqyR_inv13","inv13","10"})){
            var pair=prepare(Files.readString(Path.of("classified-data",row[0]+".als")),row[1],row[1]+"c");for(var dir:List.of(pair,pair.reverse()))check(Canonical.distance(dir.a,dir.b)==Integer.parseInt(row[2])&&CanonicalAlloyPipeline.distance(CanonicalAlloyPipeline.prepare(dir.a),CanonicalAlloyPipeline.prepare(dir.b))==Integer.parseInt(row[2]),"prior family "+row[0]);controls.put(row[0]);}
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);System.out.println("RetainedDifferenceDomainReplayTest: "+checks+" checks passed");
    }
}
