package is.fivefivefive.CanDis.metric;

import java.util.List;

/** A retained native recurrence decision. No optimizer or JSON is used here. */
public record MatrixDecision<N>(N source, N target, String kind, int cost, int headCost,
        boolean unordered, int leftAlternative, int rightAlternative,
        MatrixDecision<N> selected, List<Edge<N>> children) {
    public record Edge<N>(int leftIndex, int rightIndex, MatrixDecision<N> decision) { }
    public MatrixDecision {
        if (cost < 0 || headCost < 0) throw new IllegalArgumentException("Negative matrix decision charge");
        children = List.copyOf(children);
    }
    public static <N> MatrixDecision<N> whole(N left,N right,int cost) {
        return new MatrixDecision<>(left,right,left==null?"insert":"delete",cost,0,false,-1,-1,null,List.of());
    }
    public static <N> MatrixDecision<N> node(N left,N right,int cost,int head,boolean unordered,List<Edge<N>> edges) {
        int charged=head;for(var edge:edges)charged=Math.addExact(charged,edge.decision.cost);
        if(charged!=cost)throw new IllegalStateException("Native decision charge differs from recurrence");
        return new MatrixDecision<>(left,right,"node",cost,head,unordered,-1,-1,null,edges);
    }
    public static <N> MatrixDecision<N> select(N left,N right,int li,int ri,MatrixDecision<N> selected) {
        return new MatrixDecision<>(left,right,"select",selected.cost,0,false,li,ri,selected,List.of());
    }
}
