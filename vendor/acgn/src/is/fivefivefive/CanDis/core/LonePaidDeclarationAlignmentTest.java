package is.fivefivefive.CanDis.core;

import java.nio.file.*;
import java.util.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.ir.IRAgent;
import parser.ast.nodes.ModelUnit;

/** LONE barriers, exact paid occurrences and unsupported-scope controls. */
public final class LonePaidDeclarationAlignmentTest {
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
        q.setQuantifier(List.of(QuantiVar.Quantifier.LONE,QuantiVar.Quantifier.ALL,QuantiVar.Quantifier.SOME).get(code/2));
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
    public static void main(String[] args) throws Exception {
        var original=List.of(variable("0",1),variable("1",0),variable("2",5),variable("3",4),
                variable("4",0),variable("5",3),variable("6",2));
        var method=CanonicalDistance.class.getDeclaredMethod("canonicalQuantifierOrder",List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked") var ordered=(List<QuantiVar>)method.invoke(null,original);
        check(ordered.stream().map(QuantiVar::getName).toList().equals(List.of("0","1","3","2","4","6","5")),
                "each LONE is a fixed barrier, only inner ALL/SOME runs sort");
        check(original.stream().map(QuantiVar::getName).toList().equals(List.of("0","1","2","3","4","5","6")),
                "source occurrence order remains immutable");
        for(int n=0;n<=2;n++) for(int m=0;m<=2;m++) {
            int limit=1;for(int i=0;i<n+m;i++) limit*=6;
            for(int code=0;code<limit;code++) {
                int value=code; var a=new ArrayList<QuantiVar>();var b=new ArrayList<QuantiVar>();
                for(int i=0;i<n;i++) { a.add(variable("a"+i,value%6));value/=6; }
                for(int i=0;i<m;i++) { b.add(variable("b"+i,value%6));value/=6; }
                var d=dp(a,b);Set<Map<String,String>> actual=new HashSet<>(),expected=new HashSet<>();
                FastPaidDeclarationAlignment.enumerate(a,b,d,LonePaidDeclarationAlignmentTest::update,actual::add);
                brute(a,b,0,0,0,d[n][m],new HashMap<>(),expected);
                check(actual.equals(expected),"all minimum charged paths over ALL/SOME/LONE and two carriers");
                for(var links:actual) check(new HashSet<>(links.values()).size()==links.size(),"paid links remain injective");
            }
        }
        String natural=Files.readString(Path.of("classified-data/classroom_fol/both/83RWHQkLc3bdocWg4_inv12.als"));
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,natural);
        var left=prepare(module,"inv12");var right=prepare(module,"inv12c");
        check(FastPaidDeclarationAlignment.supportsPair(left,right) && FastPaidDeclarationAlignment.supportsPair(right,left),
                "actual pair admitted in both directions");
        check(left.get(0).getMatrixQuantiVars().stream().map(q->q.getQuantifier().name()).toList()
                .equals(List.of("LONE","SOME","SOME")),"original nested prefix remains intact");
        var one=prepare(CompUtil.parseEverything_fromString(A4Reporter.NOP,natural.replace("lone t: Teacher","one t: Teacher")),"inv12");
        check(one.get(0).getMatrixQuantiVars().get(0).getQuantifier()==QuantiVar.Quantifier.ONE
                && FastPaidDeclarationAlignment.supports(one),"ONE now admits only fixed-prefix paid edits");
        for(String replacement:List.of("lone t: set Teacher","lone disj t,u: Teacher")) {
            var changed=CompUtil.parseEverything_fromString(A4Reporter.NOP,natural.replace("lone t: Teacher",replacement));
            check(!FastPaidDeclarationAlignment.supports(prepare(changed,"inv12")),"unsupported declaration "+replacement);
        }
        var local=CompUtil.parseEverything_fromString(A4Reporter.NOP,
                Files.readString(Path.of("classified-data/classroom_fol/both/7TYSDPnXqtud3bjku_inv12.als"))
                    .replace("some c:Class","lone c:Class"));
        var localForms=prepare(local,"inv12");
        check(!localForms.get(0).getLocalQuantiVars().isEmpty(),"scoped control actually retains local binder");
        check(FastLocalPaidScope.supports(localForms.get(0)) && FastPaidDeclarationAlignment.supports(localForms),
                "LONE prefix admits paid links only after actual local source scope validation");
        check(localForms.get(0).getMatrixQuantiVars().get(0).getQuantifier()==QuantiVar.Quantifier.LONE,
                "scoped admission preserves original LONE barrier");
        check(FastPaidDeclarationAlignment.supportsPair(localForms,prepare(local,"inv12c"))
                && FastPaidDeclarationAlignment.supportsPair(prepare(local,"inv12c"),localForms),"scoped LONE both directions");
        System.out.println("LonePaidDeclarationAlignmentTest: "+checks+" checks passed; fixed prefix, no free LONE exchange");
    }
}
