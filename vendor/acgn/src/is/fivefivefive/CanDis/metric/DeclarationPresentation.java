package is.fivefivefive.CanDis.metric;

import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.metric.RepairView.Binding;
import is.fivefivefive.CanDis.metric.RepairView.BindingRole;

/** Reorders independent quantifier presentation, retaining each typed occurrence.
 * This is not a typed-slot automorphism and never changes a matrix mapping. */
public final class DeclarationPresentation {
    private DeclarationPresentation() { }

    public static boolean independent(Binding a, Binding b) {
        var x=a.declaration(); var y=b.declaration();
        return a.role()==BindingRole.MATRIX && b.role()==BindingRole.MATRIX
                && a.prenexPathErasureCertified() && b.prenexPathErasureCertified()
                && a.ownerPhase()==b.ownerPhase() && a.ownerContext().equals(b.ownerContext())
                && x.quantifier().equals(y.quantifier())
                && (x.quantifier().equals("ALL") || x.quantifier().equals("SOME"))
                && x.cardinality().equals("ONE") && y.cardinality().equals("ONE")
                && x.disjointnessClass()==0 && y.disjointnessClass()==0
                && x.dependencies().isEmpty() && y.dependencies().isEmpty()
                && x.observationType()!=null && y.observationType()!=null
                && x.observationDomain()!=null && y.observationDomain()!=null;
    }

    /** Stable selected-coordinate order inside independent homogeneous runs. */
    public static int[] order(List<Binding> bindings, List<String> identities,
            List<String> other, Map<String,String> selected) {
        DeclarationReplay.require(bindings.size()==identities.size(),"Presentation identity coverage");
        var ranks=new HashMap<String,Integer>();
        for(int i=0;i<other.size();i++) DeclarationReplay.require(ranks.put(other.get(i),i)==null,"Repeated counterpart");
        var out=new ArrayList<Integer>();
        for(int start=0;start<bindings.size();) {
            int end=start+1;
            while(end<bindings.size() && independent(bindings.get(end-1),bindings.get(end))) end++;
            var run=new ArrayList<Integer>();
            for(int i=start;i<end;i++) run.add(i);
            run.sort(Comparator.comparingInt((Integer i)->ranks.getOrDefault(selected.get(identities.get(i)),Integer.MAX_VALUE))
                    .thenComparingInt(Integer::intValue));
            out.addAll(run); start=end;
        }
        int[] order=out.stream().mapToInt(Integer::intValue).toArray();
        evidence(bindings,identities,order);
        return order;
    }

    /** Check every adjacent exchange, not merely the final same-kind endpoints. */
    public static JSONObject evidence(List<Binding> bindings,List<String> identities,int[] order) {
        DeclarationReplay.require(bindings.size()==order.length && identities.size()==order.length
                && new HashSet<>(identities).size()==order.length,"Presentation coverage");
        var current=new ArrayList<Integer>(); for(int i=0;i<order.length;i++) current.add(i);
        var swaps=new JSONArray();
        for(int i=0;i<order.length;i++) {
            int at=current.indexOf(order[i]);
            DeclarationReplay.require(at>=i,"Presentation bijection");
            while(at>i) {
                int a=current.get(at-1),b=current.get(at);
                DeclarationReplay.require(independent(bindings.get(a),bindings.get(b)),"Unproved prefix exchange");
                swaps.put(new JSONObject().put("position",at-1)
                        .put("left",endpoint(bindings.get(a),identities.get(a)))
                        .put("right",endpoint(bindings.get(b),identities.get(b))));
                Collections.swap(current,at-1,at); at--;
            }
        }
        return new JSONObject().put("order",new JSONArray(order)).put("adjacentExchanges",swaps)
                .put("law","INDEPENDENT_TYPED_ALL_SOME_PRESENTATION").put("cost",0);
    }

    private static JSONObject endpoint(Binding b,String identity) {
        var d=b.declaration();
        return new JSONObject().put("identity",identity).put("sourceOccurrence",b.bindingPath())
                .put("coordinate",b.coordinate()).put("phase",b.ownerPhase()).put("owner",b.ownerContext())
                .put("role",b.role().name()).put("quantifier",d.quantifier()).put("cardinality",d.cardinality())
                .put("type",d.observationType().toString()).put("domain",d.observationDomain().toString())
                .put("dependencies",new JSONArray(d.dependencies())).put("disjointnessClass",d.disjointnessClass());
    }

    public static Map<String,String> inverse(Map<String,String> selected) {
        var out=new LinkedHashMap<String,String>();
        for(var e:selected.entrySet()) DeclarationReplay.require(out.put(e.getValue(),e.getKey())==null,"Noninjective presentation map");
        return Map.copyOf(out);
    }

    public static int[][] distances(List<DeclarationReplay.Atom> a,List<DeclarationReplay.Atom> b) {
        DeclarationReplay.requireBounded(a.size(),b.size());
        int[][] dp=new int[a.size()+1][b.size()+1];
        for(int i=0;i<=a.size();i++) for(int j=0;j<=b.size();j++)
            dp[i][j]=i==0?j:j==0?i:Math.min(dp[i-1][j-1]+(a.get(i-1).tuple().equals(b.get(j-1).tuple())?0:1),
                    Math.min(dp[i-1][j]+1,dp[i][j-1]+1));
        return dp;
    }
}
