package is.fivefivefive.CanDis.metric;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import is.fivefivefive.CanDis.core.OrderedTreeEditDistance;

/** Fixed-choice execution and resource tests, with the optimizer forbidden. */
public final class MetricDecisionProgramTest {
    private record N(String label,boolean unordered,List<N> children,List<N> alternatives) { }
    private static int checks;
    private static N leaf(String label){return new N(label,false,List.of(),List.of());}
    private static N node(String label,boolean unordered,N... children){return new N(label,unordered,List.of(children),List.of());}
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    private static void reject(Runnable r){checks++;try{r.run();}catch(RuntimeException expected){return;}throw new AssertionError("Accepted corrupted program");}
    private static final MatrixReplay.NativeCost<N> COST=new MatrixReplay.NativeCost<>() {
        public String label(N n){return n.label;}
        public String evidence(N n){return "typed-owner/"+n.label;}
        public List<N> children(N n){return n.children;}
        public List<N> alternatives(N n){return n.alternatives;}
        public boolean unordered(N a,N b){return a.label.equals(b.label)&&a.unordered&&b.unordered;}
        public int size(N n){int result=1;for(N c:n.children)result=Math.addExact(result,size(c));return result;}
        public int update(N a,N b){return a.label.equals(b.label)?0:1;}
        public int distance(N a,N b){throw new AssertionError("Executable emission/replay called the optimizer");}
    };
    private static MatrixDecision<N> identity(N a) {
        var edges=new ArrayList<MatrixDecision.Edge<N>>();
        for(int i=0;i<a.children.size();i++)edges.add(new MatrixDecision.Edge<>(i,i,identity(a.children.get(i))));
        return MatrixDecision.node(a,a,0,0,a.unordered,edges);
    }
    private static void execute(MatrixDecision<N> choice) {
        var replay=new MatrixReplay<>(COST,16_000_000);var receipt=replay.capture(choice);
        var program=replay.executable(choice.source(),choice.target(),receipt);
        check(replay.verifyExecutable(choice.source(),choice.target(),receipt,program)==choice.cost(),"independent fixed program");
        var corrupt=new JSONObject(program.toString());
        if(!corrupt.getJSONArray("operations").isEmpty()) {
            corrupt.getJSONArray("operations").getJSONObject(0).put("cost",999);
            reject(()->new MatrixReplay<>(COST,16_000_000).verifyExecutable(choice.source(),choice.target(),receipt,corrupt));
        }
    }
    public static void main(String[] args) {
        N a=leaf("a"),b=leaf("b"),sub=node("sub",false,a,b),empty=node("R",false);
        execute(MatrixDecision.node(a,b,1,1,false,List.of()));
        execute(MatrixDecision.whole(sub,null,3));execute(MatrixDecision.whole(null,sub,3));
        N l=node("R",false,sub);
        execute(MatrixDecision.node(l,empty,3,0,false,List.of(new MatrixDecision.Edge<>(0,-1,MatrixDecision.whole(sub,null,3)))));
        N x=node("U",true,a,b,a),y=node("U",true,b,a,a);
        execute(MatrixDecision.node(x,y,0,0,true,List.of(new MatrixDecision.Edge<>(0,1,identity(a)),
                new MatrixDecision.Edge<>(1,0,identity(b)),new MatrixDecision.Edge<>(2,2,identity(a)))));
        N alt=new N("choice",false,List.of(sub),List.of(b,a));
        execute(MatrixDecision.select(alt,a,1,-1,identity(a)));
        execute(MatrixDecision.select(null,alt,-1,1,MatrixDecision.whole(null,a,1)));
        // Raw child insertion retains and charges the raw tree, unlike phase-minimum selection.
        execute(MatrixDecision.whole(null,alt,4));
        N deep=a;for(int i=0;i<145;i++)deep=node("D",false,deep);execute(identity(deep));
        var wide=new ArrayList<N>();for(int i=0;i<280;i++)wide.add(a);
        N broad=new N("T",false,wide,List.of());execute(identity(broad));
        var adapter=new OrderedTreeEditDistance.Adapter<N>() {
            public String label(N n){return n.label;}public List<N> children(N n){return n.children;}
        };
        var alignment=OrderedTreeEditDistance.alignment(broad,broad,adapter);
        var temporal=TemporalReplay.capture(broad,broad,0,n->n.label,n->n.children,alignment);
        check(TemporalReplay.verify(broad,broad,n->n.label,n->n.children,temporal)==0,"temporal width beyond old shape cap");
        check(temporal.getJSONArray("zeroCostReads").length()==281,"zero temporal reads retained");
        var corrupt=new JSONObject(temporal.toString());corrupt.getJSONArray("selectedCorrespondence").getJSONArray(0).put(1,999);
        reject(()->TemporalReplay.verify(broad,broad,n->n.label,n->n.children,corrupt));
        var atoms=new ArrayList<DeclarationReplay.Atom>();var moves=new ArrayList<DeclarationReplay.Move>();
        for(int i=0;i<280;i++){atoms.add(new DeclarationReplay.Atom("owner/"+i,List.of("ALL","A","ONE","0"),"source/"+i));moves.add(new DeclarationReplay.Move(i,i));}
        var declarations=DeclarationReplay.captureSelected(atoms,atoms,moves,0);
        check(DeclarationReplay.execute(atoms,atoms,declarations)==0,"declarations beyond old shape cap");
        var shared=new JSONArray().put("payload");for(int i=0;i<32;i++)shared=new JSONArray().put(shared).put(shared);
        final JSONArray expanded=shared;reject(()->TraceResources.requireJson(expanded));
        reject(()->TraceResources.requireCells(Long.MAX_VALUE,2));
        checks++;
        try { TraceResources.requireCells(Integer.MAX_VALUE,Integer.MAX_VALUE);throw new AssertionError("Overflow accepted"); }
        catch(IllegalStateException expected){check(expected.getMessage().equals("NATIVE_TRACE_RESOURCE_BUDGET"),"overflow is resource refusal");}
        reject(()->TraceResources.requireCells(-1,-1));
        System.out.println("MetricDecisionProgramTest: "+checks+" checks");
    }
}
