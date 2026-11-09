package is.fivefivefive.CanDis.core;

import java.nio.file.*;
import java.util.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.theory.CertificateVerifier;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;
import is.fivefivefive.CanDis.metric.RepairProjection;
import parser.ast.nodes.ModelUnit;

/** ONE barriers, exact paid occurrences and unsupported-scope controls. */
public final class OnePaidDeclarationAlignmentTest {
    private static int checks;
    private static void check(boolean value,String why) { checks++; if(!value) throw new AssertionError(why); }
    private static List<NormalForm> prepare(edu.mit.csail.sdg.parser.CompModule m,String name) {
        var v=new MASGVisitor(new GlobalVariables(),Set.of(name),m);v.visit(new ModelUnit(null,m),null);
        var a=new IRAgent(v.getForest().get(v.getForestId(name)));a.computeNormalForm();
        var forms=List.copyOf(a.normalForms());forms.forEach(NormalForm::freezeForCertification);return forms;
    }
    private static QuantiVar variable(String name,int code) {
        var q=new QuantiVar(name.hashCode(),name,code%2==0?"A":"Z");
        q.setCardinality(QuantiVar.Cardinality.ONE);
        q.setQuantifier(List.of(QuantiVar.Quantifier.ONE,QuantiVar.Quantifier.ALL,QuantiVar.Quantifier.SOME).get(code/2));
        return q;
    }
    private static int update(QuantiVar a,QuantiVar b) {
        return a.getQuantifier()==b.getQuantifier() && a.getTypeName().equals(b.getTypeName())?0:1;
    }
    private static int[][] dp(List<QuantiVar> a,List<QuantiVar> b) {
        int[][] d=new int[a.size()+1][b.size()+1];
        for(int i=0;i<=a.size();i++) d[i][0]=i;
        for(int j=0;j<=b.size();j++) d[0][j]=j;
        for(int i=1;i<=a.size();i++) for(int j=1;j<=b.size();j++)
            d[i][j]=Math.min(d[i-1][j-1]+update(a.get(i-1),b.get(j-1)),Math.min(d[i-1][j]+1,d[i][j-1]+1));
        return d;
    }
    private static void brute(List<QuantiVar> a,List<QuantiVar> b,int i,int j,int cost,int minimum,
            Map<String,String> links,Set<Map<String,String>> out) {
        if(cost>minimum) return;
        if(i==a.size() && j==b.size()) { if(cost==minimum) out.add(Map.copyOf(links)); return; }
        if(i<a.size()) brute(a,b,i+1,j,cost+1,minimum,links,out);
        if(j<b.size()) brute(a,b,i,j+1,cost+1,minimum,links,out);
        if(i<a.size() && j<b.size()) {
            int charge=update(a.get(i),b.get(j));
            if(charge!=0) links.put(a.get(i).getName(),b.get(j).getName());
            brute(a,b,i+1,j+1,cost+charge,minimum,links,out);
            if(charge!=0) links.remove(a.get(i).getName());
        }
    }

    private static List<EGraphNode> sourceNodes(EGraphNode root) {
        var nodes=new ArrayList<EGraphNode>();var pending=new ArrayDeque<EGraphNode>();pending.add(root);
        while(!pending.isEmpty()) {
            if(nodes.size()>=4096)throw new AssertionError("fixture graph budget");
            var n=pending.removeFirst();nodes.add(n);pending.addAll(n.getChildren());
        }
        return nodes;
    }

    /** Teacher is a subset of Person: the apparently unused u occurs in its
     * source-linked domain guard. Uniqueness remains over the whole (t,u) tuple. */
    static int checkedGroupedTeacherFixture(String natural) throws Exception {
        int before=checks;
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,
                natural.replace("one t:Teacher","one t,u:Teacher"));
        var forms=prepare(module,"inv8");var form=forms.get(0);var qs=form.getMatrixQuantiVars();
        check(qs.stream().map(QuantiVar::getQuantifier).toList().equals(List.of(
                QuantiVar.Quantifier.ALL,QuantiVar.Quantifier.ONE,QuantiVar.Quantifier.ONE)),
                "outer ALL and two ordered ONE slots retained");
        check(form.getLocalQuantiVars().isEmpty() && form.getTemporalChildren().isEmpty(),"closed prefix fixture");
        check(qs.get(1).getBindingPath().equals(qs.get(2).getBindingPath())
                && !qs.get(0).getBindingPath().equals(qs.get(1).getBindingPath()),"actual grouped owner under outer ALL");
        check(form.hasOriginalGroupedOne(qs.subList(1,3)) && form.originalGroupedOneOriginsIntact(),
                "producer-linked ordered tuple evidence");
        var nodes=sourceNodes(form.getCertificationMatrixEGraph());
        for(int i=1;i<3;i++) {
            var q=qs.get(i);
            check(q.getTypeName().equals("Person") && q.getCardinality()==QuantiVar.Cardinality.ONE
                    && q.getDisjointnessClass()==0 && q.getDeBruijnKey().equals(
                        q.getBindingPath()+"#"+(i-1)+":ONE:Person"),"literal carrier/multiplicity/slot "+i);
            var uses=nodes.stream().filter(n->n.getOpcode()==EGraphNode.Opcode.VARIABLE
                    && q.getName().equals(n.getAlphaName())).toList();
            check(uses.size()==(i==1?2:1),"t used in body and guard; u used only in guard");
            for(var n:uses)check(q.getOriginalNames().contains(n.getSemanticIdentity())
                    && q.getOriginalNames().contains(n.getSourceName())
                    && q.getExactAlloyType().sameOccurrenceEvidenceAs(n.getExactAlloyType()),
                    "domain/body occurrence retains original binder and parser type");
            check(nodes.stream().filter(n->n.getOpcode()==EGraphNode.Opcode.IN && n.getChildren().size()==2)
                    .anyMatch(n->q.getName().equals(n.getChildren().get(0).getAlphaName())
                        && n.getChildren().get(1).getOpcode()==EGraphNode.Opcode.GLOBALBINDING
                        && "alloy/signature/Teacher".equals(n.getChildren().get(1).getSemanticIdentity())
                        && q.getExactAlloyType().sameOccurrenceEvidenceAs(n.getChildren().get(1).getExactAlloyType())),
                    "actual typed Teacher domain guard "+i);
        }
        check(FastPaidDeclarationAlignment.supports(forms),"source-observed grouped ONE admission");
        var comparison=NormalForm.fastComparisonViews(forms).get(0);
        check(FastPaidDeclarationAlignment.supports(List.of(comparison)),"comparison retains checked group");
        for(int i=0;i<qs.size();i++)check(qs.get(i)==comparison.getMatrixQuantiVars().get(i),"original binding identity");
        check(!FastDeclarationPermutation.admission(form,qs).test(1,2),"no free ONE swap");
        check(FastGroupedDeclarationOrbit.orders(form,comparison,qs).size()==1,"ONE orbit remains identity only");
        var evidence=TheoryAlloyAdapter.adapt(forms,form.getCertificationMatrixEGraph().getSemanticProfile());
        evidence.requireRepairProjectionSources(forms);
        for(var c:evidence.semanticArtifact().binderOccurrenceCertificates())CertificateVerifier.verify(c);
        for(var c:evidence.semanticArtifact().flatConstructions())CertificateVerifier.verify(c);
        var view=RepairProjection.project(evidence,forms);
        check(view.phases().get(0).quantifiers().size()==3,"certified source retains all three coordinates");
        var copied=new NormalForm();copied.addEClass(form.getCertificationMatrixEGraph());
        qs.forEach(copied::addMatrixQuantiVar);copied.freezeForCertification();
        check(!FastPaidDeclarationAlignment.supports(List.of(copied)),"copied source/bindings cannot invent grouped owner evidence");
        var unobserved=prepare(CompUtil.parseEverything_fromString(A4Reporter.NOP,
                natural.replace("one t:Teacher","one t,u:Person")),"inv8");
        var u=unobserved.get(0).getMatrixQuantiVars().get(2);
        check(sourceNodes(unobserved.get(0).getCertificationMatrixEGraph()).stream().noneMatch(n->
                n.getOpcode()==EGraphNode.Opcode.VARIABLE && u.getName().equals(n.getAlphaName())),
                "Person control has no u occurrence or subset guard");
        check(!FastPaidDeclarationAlignment.supports(unobserved),"genuinely unobserved grouped slot stays unsupported");
        for(String replacement:List.of("one t:set Teacher","one disj t,u:Teacher"))
            check(!FastPaidDeclarationAlignment.supports(prepare(CompUtil.parseEverything_fromString(
                    A4Reporter.NOP,natural.replace("one t:Teacher",replacement)),"inv8")),
                    "SET/disjoint boundary "+replacement);
        return checks-before;
    }
    public static void main(String[] args) throws Exception {
        var original=List.of(variable("0",1),variable("1",0),variable("2",5),variable("3",4),
                variable("4",0),variable("5",3),variable("6",2));
        var method=CanonicalDistance.class.getDeclaredMethod("canonicalQuantifierOrder",List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked") var ordered=(List<QuantiVar>)method.invoke(null,original);
        check(ordered.stream().map(QuantiVar::getName).toList().equals(List.of("0","1","3","2","4","6","5")),
                "each ONE is a fixed barrier, only inner ALL/SOME runs sort");
        check(original.stream().map(QuantiVar::getName).toList().equals(List.of("0","1","2","3","4","5","6")),
                "source occurrence order remains immutable");
        for(int n=0;n<=2;n++) for(int m=0;m<=2;m++) {
            int limit=1;for(int i=0;i<n+m;i++) limit*=6;
            for(int code=0;code<limit;code++) {
                int value=code; var a=new ArrayList<QuantiVar>();var b=new ArrayList<QuantiVar>();
                for(int i=0;i<n;i++) { a.add(variable("a"+i,value%6));value/=6; }
                for(int i=0;i<m;i++) { b.add(variable("b"+i,value%6));value/=6; }
                var d=dp(a,b);Set<Map<String,String>> actual=new HashSet<>(),expected=new HashSet<>();
                FastPaidDeclarationAlignment.enumerate(a,b,d,OnePaidDeclarationAlignmentTest::update,actual::add);
                brute(a,b,0,0,0,d[n][m],new HashMap<>(),expected);
                check(actual.equals(expected),"all minimum charged paths over ALL/SOME/ONE and two carriers");
                for(var links:actual) check(new HashSet<>(links.values()).size()==links.size(),"paid links remain injective");
            }
        }
        String natural=Files.readString(Path.of("classified-data/classroom_fol/both/TA943B2hFMmbp96Pj_inv8.als"));
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,natural);
        var left=prepare(module,"inv8"); var right=prepare(module,"inv8c");
        check(FastPaidDeclarationAlignment.supportsPair(left,right) && FastPaidDeclarationAlignment.supportsPair(right,left),
                "original pair in both directions");
        check(left.get(0).getMatrixQuantiVars().stream().map(q->q.getQuantifier().name()).toList()
                .equals(List.of("ALL","ONE")),"ONE cannot cross the outer ALL");
        checkedGroupedTeacherFixture(natural);
        var local=CompUtil.parseEverything_fromString(A4Reporter.NOP,
                Files.readString(Path.of("classified-data/classroom_fol/both/7TYSDPnXqtud3bjku_inv12.als"))
                    .replace("some c:Class","one c:Class"));
        var localForms=prepare(local,"inv12");
        check(!localForms.get(0).getLocalQuantiVars().isEmpty(),"scoped control retains a local binder");
        check(FastLocalPaidScope.supports(localForms.get(0)) && FastPaidDeclarationAlignment.supports(localForms),
                "ONE prefix admits paid links only after actual local source scope validation");
        check(localForms.get(0).getMatrixQuantiVars().get(0).getQuantifier()==QuantiVar.Quantifier.ONE,
                "scoped admission preserves original ONE barrier");
        check(FastPaidDeclarationAlignment.supportsPair(localForms,prepare(local,"inv12c"))
                && FastPaidDeclarationAlignment.supportsPair(prepare(local,"inv12c"),localForms),"scoped ONE both directions");
        var controls=CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                sig A { r:set A }
                pred temporal { one x:A | always x in x.r }
                pred parameter[p:A] { one x:A | p in x.r }
                pred oneFirst { one x:A | some y:A | x in y.r }
                pred someFirst { some y:A | one x:A | x in y.r }
                pred rowUnique { one x:A | one y:A | x->y in r }
                pred columnUnique { one y:A | one x:A | x->y in r }
                """);
        for(String name:List.of("temporal","parameter"))
            check(!FastPaidDeclarationAlignment.supports(prepare(controls,name)),"excluded scope "+name);
        var oneFirst=prepare(controls,"oneFirst"); var someFirst=prepare(controls,"someFirst");
        check(!CanonicalDistance.sourceIrTemporalFol(oneFirst).equals(CanonicalDistance.sourceIrTemporalFol(someFirst)),
                "ONE/SOME source order is retained");
        var rows=CanonicalDistance.prepare(prepare(controls,"rowUnique"));
        var columns=CanonicalDistance.prepare(prepare(controls,"columnUnique"));
        check(CanonicalDistance.distance(rows,columns)>0
                && CanonicalDistance.distance(rows,columns)==CanonicalDistance.distance(columns,rows),
                "equal-carrier ONE owners cannot be freely exchanged by alpha alignment");
        System.out.println("OnePaidDeclarationAlignmentTest: "+checks+" checks passed; fixed prefix, no free ONE exchange");
    }
}
