package is.fivefivefive.ACGN.asg;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import is.fivefivefive.alloyasg.asg.ASGVisitor;
import is.fivefivefive.alloyasg.asg.ASGraph;
import is.fivefivefive.alloyasg.etc.DoubleMap;
import is.fivefivefive.alloyasg.exceptions.ScopeNotFoundException;
import is.fivefivefive.alloyasg.exceptions.UnsupportedConstantException;
import is.fivefivefive.alloyasg.representations.NodeRepresentation;
import parser.ast.nodes.Node;
import parser.ast.nodes.UnaryExpr;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.structure.ScopeTreeNode;
import is.fivefivefive.ACGN.util.GlobalVariables;

/*
 * A naive, intermediate multigraph representation of the Multi-ASG.
 */
public class Multigraph implements Serializable {
    private Set<AugmentedNode> vertices;
    private List<MASGEdge> edges;
    private GlobalVariables globalVariables;
    private AugmentedNode root;
    private ScopeTreeNode scope;
    private Map<AugmentedNode, Integer> timeOfVisitMap;
    public Multigraph(Set<AugmentedNode> v, List<MASGEdge> e, AugmentedNode r) {
        vertices = v;
        edges = e;
        root = r;
        globalVariables = new GlobalVariables();
        timeOfVisitMap = new HashMap<AugmentedNode, Integer>();
    }
    public Multigraph(Set<AugmentedNode> v, List<MASGEdge> e, AugmentedNode r, GlobalVariables gv) {
        vertices = v;
        edges = e;
        root = r;
        globalVariables = gv;
        timeOfVisitMap = new HashMap<AugmentedNode, Integer>();
    }
    public Multigraph(AugmentedNode root, GlobalVariables gv) {
        vertices = new HashSet<AugmentedNode>();
        edges = new ArrayList<MASGEdge>();
        vertices.add(root);
        this.root = root;
        globalVariables = gv;
        // construct the graph with the given root
        Queue<AugmentedNode> nodeQueue = new LinkedList<AugmentedNode>();
        nodeQueue.add(root);
        while (!nodeQueue.isEmpty()) {
            AugmentedNode current = nodeQueue.poll();
            for (MASGEdge e : current.getDownlinks()) {
                if (!vertices.contains(e.getTarget())) {
                    vertices.add(e.getTarget());
                    edges.add(e);
                    nodeQueue.add(e.getTarget());
                }
            }
        }
        timeOfVisitMap = new HashMap<AugmentedNode, Integer>();
    }
    public Multigraph() {
        vertices = new HashSet<AugmentedNode>();
        edges = new ArrayList<MASGEdge>();
        globalVariables = new GlobalVariables();
        timeOfVisitMap = new HashMap<AugmentedNode, Integer>();
    }
    public void setScope(ScopeTreeNode scope) {
        this.scope = scope;
    }
    public ScopeTreeNode getScope() {
        return scope;
    }
    public Map<AugmentedNode, Integer> getTimeOfVisitMap() {
        return timeOfVisitMap;
    }
    public void updateTimeOfVisitMap(AugmentedNode node, int time) {
        timeOfVisitMap.put(node, time);
    }
    public Multigraph subgraph(AugmentedNode localRoot) {
        Set<AugmentedNode> newVertices = new HashSet<AugmentedNode>();
        List<MASGEdge> newEdges = new ArrayList<MASGEdge>();
        newVertices.add(localRoot);
        Queue<AugmentedNode> nodeQueue = new LinkedList<AugmentedNode>();
        nodeQueue.add(localRoot);
        while (!nodeQueue.isEmpty()) {
            AugmentedNode current = nodeQueue.poll();
            for (MASGEdge e : edges) {
                if (e.getSource().equals(current)) {
                    newVertices.add(e.getTarget());
                    newEdges.add(e);
                    nodeQueue.add(e.getTarget());
                }
            }
        }
        return new Multigraph(newVertices, newEdges, localRoot);
    }
    public Set<AugmentedNode> getVertices() {
        return vertices;
    }
    public List<MASGEdge> getEdges() {
        return edges;
    }
    public AugmentedNode getRoot() {
        return root;
    }
    public void addVertex(AugmentedNode v) {
        vertices.add(v);
        if (vertices.size() == 1) {
            root = v;
        }
    }
    public void removeVertex(AugmentedNode v) {
        if (v.equals(root)) {
            throw new IllegalArgumentException("Cannot remove the root node.");
        }
        vertices.remove(v);
    }
    public MASGEdge connect(AugmentedNode source, AugmentedNode target, Multigraph graph, int position, int timeOfVisit) throws IllegalArgumentException {
        if (!vertices.contains(source) || !vertices.contains(target)) {
            throw new IllegalArgumentException("Source and target must be in the graph.");
        }
        MASGEdge edge = new MASGEdge(source, target, position, timeOfVisit);
        if (!edges.contains(edge)) {
            edges.add(edge);
        }
        source.connect(target, position, graph, timeOfVisit);
        return edge;
    }
    public MASGEdge edgeBetween(AugmentedNode source, AugmentedNode target, int position, int timeOfVisit) {
        for (MASGEdge e : edges) {
            if (e.getSource().equals(source) && e.getTarget().equals(target) && e.getPosition() == position && e.getTimeOfVisit() == timeOfVisit) {
                return e;
            }
        }
        return null;
    }
    public List<MASGEdge> edgesUnder(AugmentedNode node) {
        List<MASGEdge> result = new ArrayList<MASGEdge>();
        for (MASGEdge e : edges) {
            if (e.getSource().equals(node)) {
                result.add(e);
            }
        }
        return result;
    }
    public List<MASGEdge> edgesAbove(AugmentedNode node) {
        List<MASGEdge> result = new ArrayList<MASGEdge>();
        for (MASGEdge e : edges) {
            if (e.getTarget().equals(node)) {
                result.add(e);
            }
        }
        return result;
    }
    public int size() {
        return vertices.size();
    }
    public static Multigraph fromAST(ASGVisitor<Object> visitor, int root) throws ScopeNotFoundException, UnsupportedConstantException {
        ASGraph ast = visitor.getGraph();
        GlobalVariables gv = new GlobalVariables();
        DoubleMap<Integer, Node> nodeMap = visitor.getNodeMap();
        Node rootNode = nodeMap.get(root);
        NodeRepresentation rootRep = new NodeRepresentation(visitor, rootNode);
        AugmentedNode rootAug = new AugmentedNode(rootRep);
        Set<AugmentedNode> vertices = new HashSet<AugmentedNode>();
        List<MASGEdge> edges = new ArrayList<MASGEdge>();
        vertices.add(rootAug);
        Queue<Integer> nodeQueue = new LinkedList<Integer>();
        nodeQueue.add(root);
        Map<NodeRepresentation, Integer> timeOfVisit = new HashMap<NodeRepresentation, Integer>();
        while (!nodeQueue.isEmpty()) {
            int localRoot = nodeQueue.poll();
            NodeRepresentation localRootRep = new NodeRepresentation(visitor, nodeMap.get(localRoot));
            AugmentedNode localRootAug = new AugmentedNode(localRootRep);
            double[] rootRow = ast.getRow(localRoot);
            Node localRootNode = nodeMap.get(localRoot);
            if (localRootNode.getChildren().size() == 0) {
                // leaf node, we skip the iteration
                continue;
            }
            for (int i = 0; i < rootRow.length; i++) {
                Node n = nodeMap.get(i);
                // boolean flagNonSemantic = false;
                int indexOfRealChild = i;
                if (isNOOP(n) || n instanceof parser.ast.nodes.Paragraph) {
                    // SKIP AND DIRECTLY FIND ITS CHILDREN
                    Node child = n.getChildren().get(0); // only one child
                    indexOfRealChild = nodeMap.rget(child);
                    n = child;
                    // flagNonSemantic = true;
                }
                if (rootRow[indexOfRealChild] > 0) {
                    NodeRepresentation nr = new NodeRepresentation(visitor, n);
                    if (timeOfVisit.containsKey(nr)) {
                        timeOfVisit.put(nr, timeOfVisit.get(nr) + 1);
                    } else {
                        timeOfVisit.put(nr, 1);
                    }
                    if (i == localRoot) {
                        AugmentedNode shadow = new AugmentedNode(localRootRep);
                        vertices.add(shadow);
                        MASGEdge edge = new MASGEdge(localRootAug, shadow, indexOfRealChild, timeOfVisit.get(nr));
                        edges.add(edge);
                        gv.addEdge(edge, indexOfRealChild);
                        continue;
                    }

                    AugmentedNode an = new AugmentedNode(nr);
                    vertices.add(an);
                    nodeQueue.add(indexOfRealChild);
                    edges.add(new MASGEdge(localRootAug, an, indexOfRealChild, timeOfVisit.get(nr)));
                }
            }
        }
        return new Multigraph(vertices, edges, rootAug, gv);
    }
    public static Multigraph fromAST(ASGVisitor<Object> visitor, int root, GlobalVariables gv) throws ScopeNotFoundException, UnsupportedConstantException {
        Multigraph mg = fromAST(visitor, root);
        mg.globalVariables.combine(gv);
        return mg;
    }
    private static boolean isNOOP(Node node) {
        return (node instanceof UnaryExpr &&
            (((UnaryExpr) node).getOp() == parser.ast.nodes.UnaryExpr.UnaryOp.NOOP));
    }
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Multigraph: \n");
        sb.append("Vertices: \n");
        for (AugmentedNode v : vertices) {
            sb.append(v.getSymbol()).append("\n");
            if (v.getSymbol() == null) {
                sb.append(v.getSyntactic() + ", " + v.getSemantic()).append("\n");
            }
        }
        sb.append("Edges: \n");
        for (MASGEdge e : edges) {
            sb.append(e.toString()).append("\n");
        }
        return sb.toString();
    }
}
