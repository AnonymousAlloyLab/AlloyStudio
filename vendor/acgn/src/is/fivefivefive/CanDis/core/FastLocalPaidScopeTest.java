package is.fivefivefive.CanDis.core;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.ir.IRAgent;
import parser.ast.nodes.ModelUnit;

/** Actual source lineage, lexical ownership and immutable local seeds. */
public final class FastLocalPaidScopeTest {
    private static int checks;
    private static void check(boolean value,String why) { checks++; if(!value) throw new AssertionError(why); }
    private static void reject(Runnable action,String why) {
        checks++; try { action.run(); } catch(IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(why);
    }
    private static List<NormalForm> prepare(edu.mit.csail.sdg.parser.CompModule m,String name) {
        var v=new MASGVisitor(new GlobalVariables(),Set.of(name),m);v.visit(new ModelUnit(null,m),null);
        var a=new IRAgent(v.getForest().get(v.getForestId(name)));a.computeNormalForm();
        var result=List.copyOf(a.normalForms());result.forEach(NormalForm::freezeForCertification);return result;
    }
    private static void field(Object target,String name,Object value) {
        try { var f=target.getClass().getDeclaredField(name); f.setAccessible(true);f.set(target,value); }
        catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static List<EGraphNode> nodes(EGraphNode root) {
        var out=new ArrayList<EGraphNode>(); out.add(root);
        for(var c:root.getChildren()) out.addAll(nodes(c));return out;
    }
    private static EGraphNode binder(NormalForm form) {
        return nodes(form.getMatrixEGraph()).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.EXISTS).findFirst().orElseThrow();
    }
    private static void corrupt(List<NormalForm> original,Consumer<NormalForm> change,String why) {
        var copy=NormalForm.fastComparisonViews(original);change.accept(copy.get(0));
        boolean accepted;
        try { accepted=FastPaidDeclarationAlignment.supports(copy); }
        catch(IllegalArgumentException | IllegalStateException expected) { accepted=false; }
        check(!accepted,why);
    }
    private static EGraphNode domain(NormalForm form) {
        return binder(form).getChildren().get(0).getChildren().get(0);
    }
    private static List<EGraphNode.EClassRef> invocationPath(EGraphNode root,EGraphNode target) {
        if(root==target)return new ArrayList<>();
        for(var edge:root.getChildClasses()) {
            var rest=invocationPath(edge.getEClass().getRepresentative(),target);
            if(rest!=null){rest.add(0,edge);return rest;}
        }
        return null;
    }
    private static void refreshAncestors(EGraphNode root,EGraphNode target) {
        var path=Objects.requireNonNull(invocationPath(root,target));
        for(int i=path.size()-1;i>=0;i--) {
            var edge=path.get(i);field(edge.getEClass(),"slotsDirty",true);
            var map=new LinkedHashMap<String,String>();
            for(var slot:edge.getEClass().getSlots())map.put(slot,edge.getSlotMap().getOrDefault(slot,slot));
            field(edge,"slotMap",edge.getEClass().invoke(map).getSlotMap());
        }
        field(root.getEClass(),"slotsDirty",true);
    }
    private static void retainedInvocationMaps() throws Exception {
        byte[] bytes=Files.readAllBytes(Path.of("docs/distance-parity-20261107/retained-domain-maps.als"));
        check(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                .equals("fc175f27271e72d12ef8c0dcf2715b0807da318628e40a78b74686f53ba761a1"),"shared Lean map witness source");
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        var source=prepare(module,"retained");var target=prepare(module,"reference");
        for(String kind:List.of("identity","equivalent-renaming","changed-owned-slot","missing-live-slot","obsolete-slot","foreign-local-alias")) {
            var compared=NormalForm.fastComparisonViews(source);var form=compared.get(0);
            var selected=domain(form);var call=selected.getChildren().get(0);var edge=selected.getChildClasses().get(0);
            var before=new ArrayList<>(nodes(form.getMatrixEGraph()));
            if(kind.equals("equivalent-renaming")) {
                var old=new LinkedHashMap<>(edge.getSlotMap());
                for(var child:call.getChildClasses()) {
                    var map=new LinkedHashMap<String,String>();child.getSlotMap().forEach((k,v)->map.put(k,"middle:"+v));
                    field(child,"slotMap",Map.copyOf(map));
                }
                field(call.getEClass(),"slotsDirty",true);
                var inverse=new LinkedHashMap<String,String>();old.forEach((k,v)->inverse.put("middle:"+k,v));
                field(edge,"slotMap",call.getEClass().invoke(inverse).getSlotMap());
            } else if(kind.equals("changed-owned-slot")) {
                var map=new LinkedHashMap<>(edge.getSlotMap());check(map.size()==1,"single exposed CALL domain slot");
                var q=form.getMatrixQuantiVars().stream().filter(x->map.containsValue(x.getName())).findFirst().orElseThrow();
                var other=form.getMatrixQuantiVars().stream().filter(x->x!=q&&x.getExactAlloyType().sameOccurrenceEvidenceAs(q.getExactAlloyType())).findFirst().orElseThrow();
                map.replaceAll((k,v)->other.getName());field(edge,"slotMap",call.getEClass().invoke(map).getSlotMap());
                refreshAncestors(form.getMatrixEGraph(),call);
            } else if(kind.equals("missing-live-slot"))field(edge,"slotMap",Map.of());
            else if(kind.equals("obsolete-slot")) {
                var map=new LinkedHashMap<>(edge.getSlotMap());map.put("obsolete","unused");field(edge,"slotMap",Map.copyOf(map));
            } else if(kind.equals("foreign-local-alias")) {
                var local=nodes(binder(form).getChildren().get(1)).stream()
                    .filter(n->n.getOpcode()==EGraphNode.Opcode.VARIABLE&&n.getAlphaName().startsWith("@fast-local:")).findFirst().orElseThrow();
                var refs=invocationPath(form.getMatrixEGraph(),local);var incoming=refs.get(refs.size()-1);
                var map=new LinkedHashMap<>(incoming.getSlotMap());map.replaceAll((k,v)->"foreign-local");field(incoming,"slotMap",Map.copyOf(map));
                refreshAncestors(form.getMatrixEGraph(),local);
            }
            check(before.equals(nodes(form.getMatrixEGraph())),kind+" preserves all representative objects");
            boolean valid=Set.of("identity","equivalent-renaming","obsolete-slot").contains(kind);
            check(FastPaidDeclarationAlignment.supportsPair(compared,target)==valid,kind+" forward paid admission");
            check(FastPaidDeclarationAlignment.supportsPair(target,compared)==valid,kind+" reverse paid admission");
        }
        check(FastPaidDeclarationAlignment.supportsPair(source,target),"original map authority unchanged");
    }

    private static void siblingOwnerNames() throws Exception {
        byte[] bytes=Files.readAllBytes(Path.of("classified-data/lts/both/4z8ZaMtoPgQmEBZrD_inv7.als"));
        check(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                .equals("56db843ecbc8042d5f61db6f31287f3d1ae483a5ab2076fd10ee8783e86a1cce"),"event248 source bound to Lean witness");
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        var endpoints=new ArrayList<List<NormalForm>>();
        for(String predicate:List.of("inv7","inv7c")) {
            var originals=prepare(module,predicate);endpoints.add(originals);
            var source=originals.get(0);var view=NormalForm.fastComparisonViews(originals).get(0);
            int owners=predicate.equals("inv7")?2:4;
            check(FastLocalPaidScope.checkedOwnerCount(source)==owners,"original lexical owner census "+predicate);
            check(FastLocalPaidScope.checkedOwnerCount(view)==owners,"comparison lexical owner census "+predicate);

            var names=new HashMap<String,QuantiVar>();
            for(var q:source.getMatrixQuantiVars())names.put(q.getName(),q);
            for(var q:source.getLocalQuantiVars())names.put(q.getName(),q);
            var variables=new HashMap<Long,QuantiVar>();
            for(var n:nodes(source.getCertificationMatrixEGraph()))if(n.getOpcode()==EGraphNode.Opcode.VARIABLE) {
                var q=names.get(n.getAlphaName());check(q!=null,"source-issued variable binding");
                var previous=variables.putIfAbsent(n.getSourceOccurrenceLineage(),q);
                check(previous==null||previous==q,"original occurrence has one binding identity");
            }
            var globalNames=new HashMap<String,QuantiVar>();boolean oldRejects=false;
            for(var n:nodes(view.getMatrixEGraph()))if(n.getOpcode()==EGraphNode.Opcode.VARIABLE) {
                var q=variables.get(n.getSourceOccurrenceLineage());check(q!=null,"comparison retains original occurrence");
                var previous=globalNames.putIfAbsent(n.getAlphaName(),q);
                if(previous!=null&&previous!=q)oldRejects=true;
            }
            check(oldRejects==predicate.equals("inv7c"),"exact old global-name collision branch "+predicate);

            for(String kind:List.of("wrong-same-type-slot","foreign-local-name","sibling-occurrence")) {
                var changed=NormalForm.fastComparisonViews(originals).get(0);
                var owner=nodes(changed.getMatrixEGraph()).stream()
                        .filter(n->n.getOpcode()==EGraphNode.Opcode.COMPREHENSION).findFirst().orElseThrow();
                var declaration=owner.getChildren().get(0);
                var first=declaration.getChildren().get(1);var second=declaration.getChildren().get(2);
                check(first.getExactAlloyType().equals(second.getExactAlloyType()),"negative swaps same-type slots");
                var use=nodes(owner.getChildren().get(owner.getChildren().size()-1)).stream()
                        .filter(n->n.getOpcode()==EGraphNode.Opcode.VARIABLE&&n.getAlphaName().equals(first.getAlphaName()))
                        .findFirst().orElseThrow();
                if(kind.equals("sibling-occurrence"))field(use,"sourceOccurrenceLineage",second.getSourceOccurrenceLineage());
                else {
                    var route=Objects.requireNonNull(invocationPath(changed.getMatrixEGraph(),use));
                    var edge=route.get(route.size()-1);var map=new LinkedHashMap<>(edge.getSlotMap());
                    map.replaceAll((k,v)->kind.equals("wrong-same-type-slot")?second.getAlphaName():"foreign-local");
                    field(edge,"slotMap",Map.copyOf(map));refreshAncestors(changed.getMatrixEGraph(),use);
                }
                check(FastLocalPaidScope.checkedOwnerCount(changed)==0,"reject actual invocation corruption "+predicate+" "+kind);
                check(!FastPaidDeclarationAlignment.supportsSupplementalPair(List.of(changed),List.of(view))
                        && !FastPaidDeclarationAlignment.supportsSupplementalPair(List.of(view),List.of(changed)),
                        "supplemental admission rejects either corrupt endpoint "+predicate+" "+kind);
            }
            check(FastLocalPaidScope.checkedOwnerCount(view)==owners,"negative copies preserve original authority");
        }
        check(FastPaidDeclarationAlignment.supportsSupplementalPair(endpoints.get(0),endpoints.get(1))
                && FastPaidDeclarationAlignment.supportsSupplementalPair(endpoints.get(1),endpoints.get(0)),
                "both retained-local endpoints admit supplemental paid paths in both directions");
        System.out.println("LEXICAL_EVENT248 owners=2,4 forwardPaid="
                +FastPaidDeclarationAlignment.supportsPair(endpoints.get(0),endpoints.get(1))
                +" reversePaid="+FastPaidDeclarationAlignment.supportsPair(endpoints.get(1),endpoints.get(0))
                +"; owner admission only, no selected-path acceptance claimed");
    }
    private static void retainedCallDomain() throws Exception {
        byte[] bytes=Files.readAllBytes(Path.of("classified-data/productionLine_v1/over/qigwXZtriNfcmSNkQ_inv4.als"));
        check(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                .equals("25767bf1348b465e7fe4765f16acc3457db759331e418c5d40ecc76c1788a677"),"Lean witness source hash");
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        var source=prepare(module,"inv4");var target=prepare(module,"inv4c");
        check(FastPaidDeclarationAlignment.supportsPair(source,target),"retained CALL domain admits paid candidate");
        check(FastPaidDeclarationAlignment.supportsPair(target,source),"reverse retained CALL domain admission");
        check(FastPaidDeclarationAlignment.supportsPair(NormalForm.fastComparisonViews(source),NormalForm.fastComparisonViews(target)),
                "independent comparison domains admit paid candidate");
        check(FastPaidDeclarationAlignment.supportsPair(NormalForm.fastComparisonViews(target),NormalForm.fastComparisonViews(source)),
                "reverse independent comparison admission");
        check(domain(source.get(0)).getChildren().get(0).getOpcode()==EGraphNode.Opcode.CALL,"old GLOBAL/MINUS opcode gate rejects this source shape");
        corrupt(source,f->field(domain(f).getChildren().get(0),"callSourceAnchor",new Object()),"same-metadata foreign CALL anchor");
        corrupt(source,f->field(domain(f).getChildren().get(0),"callOccurrenceId",-1L),"foreign CALL occurrence");
        corrupt(source,f->field(domain(f),"exactAlloyType",null),"missing domain wrapper type");
        corrupt(source,f->field(domain(f).getChildren().get(0),"exactAlloyType",null),"missing domain expression type");
        corrupt(source,f->field(domain(f),"sourceOccurrenceLineage",-1L),"foreign retained domain occurrence");
        corrupt(source,f->field(binder(f),"sourceOccurrenceLineage",-1L),"foreign retained owner");
        corrupt(source,f->field(binder(f),"opcode",EGraphNode.Opcode.FORALL),"changed retained quantifier");
        corrupt(source,f->field(domain(f).getChildren().get(0).getChildren().get(0),"maxArity",1),"changed JOIN head arity");
        corrupt(source,f->{
            var variable=nodes(domain(f)).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.VARIABLE).findFirst().orElseThrow();
            field(variable,"alphaName","foreign-sibling-slot");
        },"otherwise typed dependency outside enclosing scope");
        corrupt(source,f->{
            var variable=nodes(domain(f)).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.VARIABLE).findFirst().orElseThrow();
            field(variable,"alphaName",binder(f).getChildren().get(0).getChildren().get(1).getAlphaName());
        },"domain cannot capture its newly introduced slot");
        check(FastPaidDeclarationAlignment.supportsPair(source,target),"source and prior candidate authority remain unchanged");
    }

    private static void repeatedSourceOwners() throws Exception {
        var source=Path.of("classified-data/lts/both/hFtGTLM2gs79Rewif_inv4.als");
        var bytes=Files.readAllBytes(source);
        check(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                .equals("f4718370b6661156be755ecab3317b7d7d6f04d465a13d21566f9d679a6599fd"),"revisit Lean source binding");
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        for(String name:List.of("inv4","inv4c")) {
            var originals=prepare(module,name);var view=NormalForm.fastComparisonViews(originals).get(0);
            check(FastLocalPaidScope.checkedOwnerCount(originals.get(0))==2,"source owner census "+name);
            check(FastLocalPaidScope.checkedOwnerCount(view)==2,"selected owner census "+name);
        }
        var originals=prepare(module,"inv4");
        var owners=nodes(originals.get(0).getCertificationMatrixEGraph()).stream()
                .filter(n->n.getOpcode()==EGraphNode.Opcode.COMPREHENSION).toList();
        check(owners.size()==2 && owners.get(0)!=owners.get(1)
                && owners.get(0).getSourceOccurrenceLineage()==owners.get(1).getSourceOccurrenceLineage(),
                "old uniqueness gate rejects cloned uses of the same source owner");
        for(String kind:List.of("owner","declaration","variable","domain","kind","body-read")) {
            var form=NormalForm.fastComparisonViews(originals).get(0);
            var repeated=nodes(form.getMatrixEGraph()).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.COMPREHENSION).toList();
            check(repeated.size()==2,"two selected invocations retained");
            var owner=repeated.get(1);var decl=owner.getChildren().get(0);
            if(kind.equals("owner"))field(owner,"sourceOccurrenceLineage",-1L);
            if(kind.equals("declaration"))field(decl,"sourceOccurrenceLineage",-1L);
            if(kind.equals("variable"))field(decl.getChildren().get(1),"sourceOccurrenceLineage",-1L);
            if(kind.equals("domain"))field(decl.getChildren().get(0),"sourceOccurrenceLineage",-1L);
            if(kind.equals("kind"))field(owner,"opcode",EGraphNode.Opcode.EXISTS);
            if(kind.equals("body-read")) {
                var read=nodes(owner.getChildren().get(owner.getChildren().size()-1)).stream()
                        .filter(n->n.getOpcode()==EGraphNode.Opcode.VARIABLE).findFirst().orElseThrow();
                field(read,"sourceOccurrenceLineage",-1L);
            }
            check(FastLocalPaidScope.checkedOwnerCount(form)==0,"reject corrupt repeated invocation "+kind);
        }
        check(FastLocalPaidScope.checkedOwnerCount(originals.get(0))==2,"repeat controls preserve original source");
    }

    private static void normalizedOwnerSupport() throws Exception {
        var bytes=Files.readAllBytes(Path.of("classified-data/coursesOld/both/yb4W6xJnxQXeQTxgY_inv14.als"));
        check(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                .equals("2ea808111dbae11c034cc548698973ea26bbba8b31161338f253b779c6d0f77f"),"selected support Lean source binding");
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        var originals=prepare(module,"inv14");var view=NormalForm.fastComparisonViews(originals).get(0);
        long issuedSlots=nodes(originals.get(0).getCertificationMatrixEGraph()).stream()
                .filter(n->n.getOpcode()==EGraphNode.Opcode.GENERICRELDECL)
                .mapToInt(n->n.getChildren().size()-1).sum();
        check(issuedSlots==4,"four source-walk declaration slots, independent of inactive seeds");
        check(FastLocalPaidScope.checkedOwnerCount(view)==2,"two exact live owners after SET normalization");
        for(String kind:List.of("owner","domain","dropped-binding","type","variable")) {
            var f=NormalForm.fastComparisonViews(originals).get(0);var owner=binder(f);var decl=owner.getChildren().get(0);
            if(kind.equals("owner"))field(owner,"sourceOccurrenceLineage",-1L);
            if(kind.equals("domain"))field(decl.getChildren().get(0),"sourceOccurrenceLineage",-1L);
            if(kind.equals("variable"))field(decl.getChildren().get(1),"sourceOccurrenceLineage",-1L);
            if(kind.equals("type"))field(decl.getChildren().get(1),"exactAlloyType",null);
            if(kind.equals("dropped-binding")) {
                var read=nodes(owner.getChildren().get(owner.getChildren().size()-1)).stream()
                        .filter(n->n.getOpcode()==EGraphNode.Opcode.VARIABLE).findFirst().orElseThrow();
                field(read,"alphaName","_l8");
            }
            check(FastLocalPaidScope.checkedOwnerCount(f)==0,"selected support rejects "+kind);
        }
        check(FastLocalPaidScope.checkedOwnerCount(view)==2,"support corruption leaves original selected graph intact");
    }
    public static void main(String[] args) throws Exception {
        String text=Files.readString(Path.of("classified-data/classroom_fol/both/7TYSDPnXqtud3bjku_inv12.als"));
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);
        var source=prepare(module,"inv12"); var target=prepare(module,"inv12c");
        check(FastPaidDeclarationAlignment.supportsPair(source,target),"actual source supports outer paid path");
        check(FastPaidDeclarationAlignment.supportsPair(target,source),"reverse source supports outer paid path");
        check(FastPaidDeclarationAlignment.supportsPair(NormalForm.fastComparisonViews(source),NormalForm.fastComparisonViews(target)),
                "metric local coordinate transfer licensed by exact source occurrence");
        check(!FastPaidDeclarationAlignment.supportsPair(source,source),"local/local alignment remains outside new slice");
        for(var q:source.get(0).getLocalQuantiVars()) {
            check(q.isFrozenForCertification(),"all local seeds frozen, including inactive seed");
            reject(()->q.setBindingPath("foreign"),"local owner immutable");
            reject(()->q.setCardinality(QuantiVar.Cardinality.SET),"local multiplicity immutable");
            reject(()->q.setQuantifier(QuantiVar.Quantifier.ALL),"local quantifier immutable");
        }
        corrupt(source,f->field(binder(f),"sourceOccurrenceLineage",-1L),"foreign local owner lineage");
        corrupt(source,f->field(binder(f).getChildren().get(0),"sourceOccurrenceLineage",-1L),"foreign declaration lineage");
        corrupt(source,f->field(binder(f).getChildren().get(0).getChildren().get(1),"sourceOccurrenceLineage",-1L),"foreign declaration variable lineage");
        corrupt(source,f->field(binder(f).getChildren().get(0).getChildren().get(0),"sourceOccurrenceLineage",-1L),"foreign domain lineage");
        corrupt(source,f->field(binder(f).getChildren().get(0).getChildren().get(0).getChildren().get(0),"semanticIdentity","alloy/signature/Student"),"same carrier different domain");
        corrupt(source,f->field(binder(f),"opcode",EGraphNode.Opcode.FORALL),"unsupported local quantifier");
        corrupt(source,f->field(binder(f).getChildren().get(0).getChildren().get(0),"opcode",EGraphNode.Opcode.SETOF),"unsupported local multiplicity");
        corrupt(source,f->field(binder(f).getChildren().get(0).getChildren().get(1),"alphaName","_q1"),"local declaration captures outer name");
        corrupt(source,f->{
            var local=binder(f).getChildren().get(0).getChildren().get(1).getAlphaName();
            var outside=f.getMatrixEGraph().getChildren().get(0).getChildren().get(0).getChildren().get(0);
            field(outside,"alphaName",local);
        },"local escapes outside owner");
        corrupt(source,f->{
            var use=nodes(binder(f).getChildren().get(1)).stream()
                    .filter(n->n.getOpcode()==EGraphNode.Opcode.VARIABLE && n.getAlphaName().startsWith("@fast-local:")).findFirst().orElseThrow();
            field(use,"alphaName","_q1");
        },"local use substituted with outer binder despite matching carrier");
        var foreign=prepare(CompUtil.parseEverything_fromString(A4Reporter.NOP,text),"inv12");
        var foreignVar=binder(foreign.get(0)).getChildren().get(0).getChildren().get(1);
        corrupt(source,f->field(binder(f).getChildren().get(0).getChildren().get(1),"exactAlloyType",foreignVar.getExactAlloyType()),
                "foreign parser-module type evidence");
        corrupt(source,f->field(binder(f).getChildren().get(0).getChildren().get(1),"exactAlloyType",null),"missing local type evidence");
        corrupt(source,f->field(f,"certificationMatrixEGraphRoot",foreign.get(0).getCertificationMatrixEGraph()),
                "another original source cannot license this metric occurrence");
        var reduced=CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                sig P { r:set P }
                sig S, T, U in P {}
                pred retained { some p:S | p in p.r implies (some t:T | p in t.r) }
                pred nested { some p:S | p in p.r implies (some t:T | p in t.r implies (some u:U | u in t.r and t in u.r)) }
                pred disjoint { some p:P | p in p.r implies (some disj t,u:T | p in t.r and u in t.r) }
                pred setLocal { some p:P | p in p.r implies (some t:set T | p in t.r) }
                pred parameter[p:P] { some q:P | q in p.r implies (some t:T | q in t.r) }
                pred temporal { some p:P | always (p in p.r implies (some t:T | p in t.r)) }
                """);
        check(FastPaidDeclarationAlignment.supports(prepare(reduced,"retained")),"reduced retained local source");
        var nested=prepare(reduced,"nested");
        check(FastLocalPaidScope.checkedOwnerCount(nested.get(0))==2
                && FastPaidDeclarationAlignment.supports(nested),"source-linked retained EXISTS chain admitted without hoisting");
        for(String name:List.of("disjoint","setLocal","parameter","temporal")) {
            var forms=prepare(reduced,name);
            check(!FastPaidDeclarationAlignment.supports(forms),"unsupported scope "+name+" "+CanonicalDistance.sourceIrTemporalFol(forms));
        }
        var budget=NormalForm.fastComparisonViews(source).get(0);
        EGraphNode repeated=new EGraphNode(89999,EGraphNode.Opcode.VARIABLE,List.of(),false,0,false,EGraphNode.Metatype.ATOMIC);
        repeated.setAlphaName("_q0");
        repeated.setExactAlloyType(source.get(0).getMatrixQuantiVars().get(0).getExactAlloyType());
        for(int i=0;i<14;i++) repeated=new EGraphNode(90000+i,EGraphNode.Opcode.AND,List.of(repeated,repeated),false,2,false,EGraphNode.Metatype.BOOLEAN);
        field(budget,"certificationMatrixEGraphRoot",repeated);
        reject(()->FastPaidDeclarationAlignment.supports(List.of(budget)),"repeated-DAG expanded visit budget");
        check(FastPaidDeclarationAlignment.supportsPair(source,target),"original authorities remain unchanged after corrupt-copy checks");
        retainedCallDomain();
        retainedInvocationMaps();
        siblingOwnerNames();
        repeatedSourceOwners();
        normalizedOwnerSupport();
        System.out.println("FastLocalPaidScopeTest: "+checks+" checks passed; scoped eligibility only");
    }
}
