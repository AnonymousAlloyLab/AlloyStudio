package is.fivefivefive.CanDis.metric;

import java.util.*;
import java.util.function.BiPredicate;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original-coordinate incidence check, conditional on the caller's typed binding admission.
 * Does not search a matrix mapping, admit a permutation, or change a scalar distance. */
public final class DeclarationMatrixLink {
    private final List<String> left, right;
    private final Map<String,String> selected;
    private final Set<String> targets, observedLeft, observedRight;
    private final boolean permitAbsentKeep;

    public DeclarationMatrixLink(List<String> left, List<String> right, Map<String,String> selected,
            Set<String> observedLeft, Set<String> observedRight) {
        this(left,right,selected,observedLeft,observedRight,false);
    }

    /** Enabled only by callers with a complete, single-owner occurrence census. */
    public DeclarationMatrixLink(List<String> left, List<String> right, Map<String,String> selected,
            Set<String> observedLeft, Set<String> observedRight,boolean permitAbsentKeep) {
        DeclarationReplay.requireBounded(left.size(),right.size());
        DeclarationReplay.require(selected.size() <= 4096 && observedLeft.size() <= 4096
                && observedRight.size() <= 4096,"Joint binding budget");
        this.left = List.copyOf(left); this.right = List.copyOf(right);
        this.selected = Map.copyOf(selected);
        targets = Set.copyOf(selected.values());
        this.observedLeft = Set.copyOf(observedLeft); this.observedRight = Set.copyOf(observedRight);
        this.permitAbsentKeep=permitAbsentKeep;
        DeclarationReplay.require(new HashSet<>(left).size() == left.size()
                && new HashSet<>(right).size() == right.size(),"Repeated declaration identity");
        DeclarationReplay.require(targets.size() == selected.size(),"Noninjective matrix correspondence");
    }

    public boolean allows(int i,int j,int cost) {
        if(allows(i,j)) return true;
        return permitAbsentKeep && cost==0 && i>=0 && j>=0
                && !selected.containsKey(left.get(i)) && !targets.contains(right.get(j))
                && (!observedLeft.contains(left.get(i)) || !observedRight.contains(right.get(j)));
    }

    /** Materializes each added keep, then verifies the receipt under that fixed
     * completed map. Caller must replay the unchanged matrix derivation too. */
    public JSONObject completeAndVerify(List<DeclarationReplay.Atom> source,List<DeclarationReplay.Atom> target,
            JSONObject receipt) {
        DeclarationReplay.require(permitAbsentKeep,"Missing closed-owner absence admission");
        DeclarationReplay.require(source.size()==left.size() && target.size()==right.size(),"Completion endpoint coverage");
        DeclarationReplay.verify(source,target,receipt);
        var completed=new LinkedHashMap<>(selected); var occupied=new HashSet<>(targets);
        var premises=new JSONArray();
        for(Object item:receipt.getJSONArray("operations")) {
            var op=(JSONObject)item; int i=op.getInt("leftIndex"),j=op.getInt("rightIndex");
            DeclarationReplay.require(allows(i,j,op.getInt("cost")),"Partial correspondence conflict");
            if(i<0 || j<0 || completed.containsKey(left.get(i))) continue;
            String a=left.get(i),b=right.get(j);
            DeclarationReplay.require(op.getInt("cost")==0 && source.get(i).tuple().equals(target.get(j).tuple())
                    && !occupied.contains(b) && (!observedLeft.contains(a) || !observedRight.contains(b)),
                    "Unproved declaration completion");
            completed.put(a,b); occupied.add(b);
            premises.put(new JSONObject().put("sourceBinding",a).put("targetBinding",b)
                    .put("sourceAbsent",!observedLeft.contains(a)).put("targetAbsent",!observedRight.contains(b))
                    .put("source",source.get(i).json()).put("target",target.get(j).json()));
        }
        new DeclarationMatrixLink(left,right,completed,observedLeft,observedRight).verify(source,target,receipt);
        return new JSONObject().put("selectedMapping",new JSONObject(new TreeMap<>(selected)))
                .put("completedMapping",new JSONObject(new TreeMap<>(completed))).put("absencePremises",premises)
                .put("scope","SINGLE_CLOSED_NON_TEMPORAL_PREFIX").put("complete",false);
    }

    /** Recomputes the mapping/absence premises from caller-owned endpoints, not wire flags. */
    public void verifyCompletion(List<DeclarationReplay.Atom> source,List<DeclarationReplay.Atom> target,
            JSONObject receipt,JSONObject evidence) {
        var expected=completeAndVerify(source,target,receipt);
        var provided=new JSONObject();
        for(String key:expected.keySet()) provided.put(key,evidence.get(key));
        DeclarationReplay.require(expected.similar(provided),"Substituted completion/absence evidence");
    }

    public boolean allows(int i, int j) {
        DeclarationReplay.require(i >= -1 && i < left.size() && j >= -1 && j < right.size()
                && (i >= 0 || j >= 0),"Joint move indices");
        if (i < 0) return !targets.contains(right.get(j));
        if (j < 0) return !selected.containsKey(left.get(i));
        String a = left.get(i), b = right.get(j);
        if (selected.containsKey(a)) return b.equals(selected.get(a));
        if (targets.contains(b)) return false;
        // Even unused-both keeps need an explicit completion; this method
        // verifies a fixed correspondence, not the possibility of extending it.
        return false;
    }

    /** Checks every keep (including cost zero), delete and insert independently of the DP. */
    public int verify(List<DeclarationReplay.Atom> source, List<DeclarationReplay.Atom> target,
            JSONObject receipt) {
        DeclarationReplay.require(source.size() == left.size() && target.size() == right.size(),"Joint endpoint coverage");
        int cost = DeclarationReplay.verify(source,target,receipt);
        JSONArray ops = receipt.getJSONArray("operations");
        for (int k = 0; k < ops.length(); k++) {
            JSONObject op = ops.getJSONObject(k);
            DeclarationReplay.require(allows(DeclarationReplay.integer(op,"leftIndex"),
                    DeclarationReplay.integer(op,"rightIndex")),"Declaration/matrix link at action " + k);
        }
        return cost;
    }

    /** One polynomial completion, not enumeration: within admitted equal-tuple
     * classes, place mapped sources in selected target order, then unused ones.
     * The caller must still capture a minimum DP path and replay every link. */
    public int[] connectingPermutation(List<DeclarationReplay.Atom> source,
            BiPredicate<Integer,Integer> admittedExchange) {
        DeclarationReplay.require(source.size() == left.size(),"Permutation source size");
        DeclarationReplay.require(left.size() <= 64,"Connecting permutation budget");
        int[] order = new int[left.size()];
        boolean[] seen = new boolean[order.length];
        Map<String,Integer> rank = new HashMap<>();
        for(int j=0;j<right.size();j++) rank.put(right.get(j),j);
        for(int i=0;i<order.length;i++) order[i]=i;
        for(int i=0;i<order.length;i++) {
            if(seen[i]) continue;
            var positions = new ArrayList<Integer>(); positions.add(i); seen[i]=true;
            for(int j=i+1;j<order.length;j++) {
                if(seen[j] || !source.get(i).tuple().equals(source.get(j).tuple())) continue;
                boolean admitted=true;
                for(int k:positions) admitted &= admittedExchange.test(k,j) && admittedExchange.test(j,k);
                if(admitted) { positions.add(j); seen[j]=true; }
            }
            var sorted = new ArrayList<>(positions);
            sorted.sort(Comparator.comparingInt((Integer k) -> rank.getOrDefault(selected.get(left.get(k)),Integer.MAX_VALUE))
                    .thenComparingInt(Integer::intValue));
            for(int k=0;k<positions.size();k++) order[positions.get(k)]=sorted.get(k);
        }
        requirePermutation(source,order,admittedExchange);
        return order;
    }

    public static void requirePermutation(List<DeclarationReplay.Atom> source,int[] order,
            BiPredicate<Integer,Integer> admittedExchange) {
        DeclarationReplay.require(source.size()==order.length && order.length<=64,"Permutation size/budget");
        boolean[] used=new boolean[order.length];
        for(int i=0;i<order.length;i++) {
            int j=order[i];
            DeclarationReplay.require(j>=0 && j<order.length && !used[j],"Permutation is not bijective");
            DeclarationReplay.require(i==j || source.get(i).tuple().equals(source.get(j).tuple())
                    && admittedExchange.test(i,j) && admittedExchange.test(j,i),"Unadmitted connecting permutation");
            used[j]=true;
        }
    }

    public static <T> List<T> permute(List<T> values,int[] order) {
        DeclarationReplay.require(values.size()==order.length,"Permutation endpoint size");
        var result=new ArrayList<T>(order.length); var seen=new boolean[order.length];
        for(int j:order) {
            DeclarationReplay.require(j>=0 && j<order.length && !seen[j],"Permutation endpoint index");
            seen[j]=true; result.add(values.get(j));
        }
        return List.copyOf(result);
    }
}
