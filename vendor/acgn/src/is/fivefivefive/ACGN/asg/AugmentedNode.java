package is.fivefivefive.ACGN.asg;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import is.fivefivefive.ACGN.util.Hasher;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.alloyasg.representations.NodeRepresentation;
import parser.etc.Pair;
import is.fivefivefive.ACGN.alloy.Symbol;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.test.Playground;

/*
 * A node in the Abstract Semantic Graph.
 * NOTE THAT SIGNATURE DOES NOT RELATED TO THE SEMANTIC AND SYNTACTIC PREASSIGNED CODES ANYMORE! 
 */
public class AugmentedNode implements Serializable {
    private byte syntactic;
    private int semantic;
    // private double signature;
    private List<MASGEdge> uplinks;
    private List<MASGEdge> downlinks;
    private Map<Pair<Multigraph, Integer>, List<MASGEdge>> downlinkMapTOV; // map by time of visit
    private boolean isShadow;
    private Symbol symbol;
    private int maxDownlinks = -1; // -1 means no limit
    private Map<Pair<Multigraph, Integer>, ExactAlloyType> exactTypeMapTOV;
    private Map<Multigraph, Integer> exactTypeVisitMap;
    private ExactAlloyType defaultExactType;
    public AugmentedNode(int syntactic, int semantic, Symbol symbol) throws IllegalArgumentException {
        if (syntactic > 127 || syntactic < -128) {
            throw new IllegalArgumentException("Syntactic is a single byte! ");
        }
        this.syntactic = (byte) syntactic;
        this.semantic = semantic;
        this.isShadow = false;
        this.symbol = symbol;
        uplinks = new ArrayList<>();
        downlinks = new ArrayList<>();
        downlinkMapTOV = new HashMap<>();
        exactTypeMapTOV = new HashMap<>();
        exactTypeVisitMap = new HashMap<>();
    }
    public AugmentedNode(NodeRepresentation nr) {
        this((byte) nr.getSyntacticRepresentation(), (int) nr.getSemanticRepresentation());
    }
    // Create a shadow node to resolve self loops
    public AugmentedNode(AugmentedNode original) {
        this.syntactic = original.getSyntactic();
        this.semantic = (int) Math.round(original.getSemantic());
        this.symbol = MASGVisitor.SHADOW_SYMBOL;
        this.uplinks = new ArrayList<>();
        this.downlinks = new ArrayList<>();
        this.downlinkMapTOV = new HashMap<>();
        this.exactTypeMapTOV = new HashMap<>();
        this.exactTypeVisitMap = new HashMap<>();
        this.defaultExactType = original.defaultExactType;
        // NO DOWNLINKS FOR SHADOW NODES
        // TODO: Exponential forms must be removed! What are exponential? 
        this.isShadow = true;
    }
    public AugmentedNode(int syntactic, int semantic) {
        this(syntactic, semantic, null);
    }
    public Symbol getSymbol() {
        return symbol;
    }
    public void setSymbol(Symbol symbol) {
        this.symbol = symbol;
    }
    public byte getSyntactic() {
        return syntactic;
    }
    public double getSemantic() {
       return semantic;
    }
    public double getSignature() {
        return this.symbol.getSignature();
    }
    public List<MASGEdge> getUplinks() {
        return uplinks;
    }
    public List<MASGEdge> getDownlinks() {
        return downlinks;
    }
    public void setSignature(double signature) {
        this.symbol.setSignature(signature);
    }
    public void initSignature() {
        this.symbol.setSignature(0);
    }
    public boolean isShadow() {
        return isShadow;
    }
    public Map<Pair<Multigraph, Integer>, List<MASGEdge>> getDownlinkMapTOV() {
        return downlinkMapTOV;
    }
    public List<MASGEdge> getDownlinksAtTimeOfVisit(Multigraph graph, int timeOfVisit) {
        Pair<Multigraph, Integer> key = Pair.of(graph, timeOfVisit);
        return downlinkMapTOV.get(key);
    }
    public MASGEdge connect(AugmentedNode target, int position, Multigraph graph, int timeOfVisit) {
        if (Playground.DEBUG) 
            System.out.println("Graph: " + graph.getRoot().getSyntactic() + " " + graph.getRoot().getSemantic());
        if (graph.getRoot() == null) {
            throw new IllegalArgumentException("Graph root cannot be null");
        }
        MASGEdge e = new MASGEdge(this, target, position, timeOfVisit);
        downlinks.add(e);
        target.uplinks.add(e);
        Pair<Multigraph, Integer> key = Pair.of(graph, timeOfVisit);
        downlinkMapTOV.putIfAbsent(key, new ArrayList<MASGEdge>());
        downlinkMapTOV.get(key).add(e);
        if (Playground.DEBUG) {
            System.out.println("Connecting " + this.syntactic + " " + this.semantic + " to " + target.syntactic + " " + target.semantic + " at " + position + ", for " + timeOfVisit + "-th time under the graph with root " + graph.getRoot().getSyntactic() + " " + graph.getRoot().getSemantic());
            System.out.println("key: " + key.a.getRoot().semantic + " " + key.b);
            System.out.println(downlinkMapTOV.get(key));
        }
        //System.out.println("Connecting " + this.syntactic + " " + this.semantic + " to " + target.syntactic + " " + target.semantic + " at " + position + ", for " + timeOfVisit + "-th time");
        return e;
    }
    public void initLocalTovAsRoot(Multigraph graph) {
        Pair<Multigraph, Integer> key = Pair.of(graph, 1);
        downlinkMapTOV.putIfAbsent(key, new ArrayList<MASGEdge>());
    }
    public MASGEdge inverseConnect(AugmentedNode source, int position, Multigraph graph, int timeOfVisit) {
        MASGEdge e = new MASGEdge(source, this, position, timeOfVisit);
        uplinks.add(e);
        source.downlinks.add(e);
        Pair<Multigraph, Integer> key = Pair.of(graph, timeOfVisit);
        source.downlinkMapTOV.putIfAbsent(key, new ArrayList<MASGEdge>());
        source.downlinkMapTOV.get(key).add(e);
        return e;
    }
    public static MASGEdge connect(AugmentedNode source, AugmentedNode target, Multigraph graph, int position, int timeOfVisit) {
        MASGEdge e = new MASGEdge(source, target, position, timeOfVisit);
        source.downlinks.add(e);
        Pair<Multigraph, Integer> key = Pair.of(graph, timeOfVisit);
        source.downlinkMapTOV.putIfAbsent(key, new ArrayList<MASGEdge>());
        source.downlinkMapTOV.get(key).add(e);
        target.uplinks.add(e);
        return e;
    }
    @Override
    public boolean equals(Object o) {
        if (o instanceof AugmentedNode) {
            AugmentedNode n = (AugmentedNode) o;
            return n.getSyntactic() == syntactic && n.getSemantic() == semantic;
        }
        return false;
    }
    // hashCode by the Cantor formula
    @Override 
    public int hashCode() {
        int synPositive = syntactic + 128;
        return Hasher.hashByTwo(synPositive, semantic);
    }
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AugmentedNode: ");
        sb.append("Syntactic: ").append(syntactic).append(", ");
        sb.append("Semantic: ").append(semantic).append(", ");
        // sb.append("Signature: ").append(getSignature()).append(", ");
        for (MASGEdge e : downlinks) {
            sb.append('\n');
            sb.append("Downlink: ").append(e).append(", ");
        }
        return sb.toString();
    }
    public int getMaxDownlinks() {
        return maxDownlinks;
    }
    public void setMaxDownlinks(int maxDownlinks) {
        this.maxDownlinks = maxDownlinks;
        if (this.symbol != null) this.symbol.setMaxDownlinks(maxDownlinks);
    }

    public void setExactType(
            Multigraph graph,
            int timeOfVisit,
            ExactAlloyType exactType) {
        if (timeOfVisit <= 0) {
            throw new IllegalArgumentException("Exact type occurrence must be positive");
        }
        if (exactTypeMapTOV == null) {
            exactTypeMapTOV = new HashMap<>();
        }
        Pair<Multigraph, Integer> key = Pair.of(
                java.util.Objects.requireNonNull(graph, "graph"), timeOfVisit);
        ExactAlloyType checked = java.util.Objects.requireNonNull(exactType, "exactType");
        ExactAlloyType previous = exactTypeMapTOV.putIfAbsent(key, checked);
        if (previous != null && !previous.sameOccurrenceEvidenceAs(checked)) {
            throw new IllegalStateException(
                    "One MASG occurrence received incompatible exact Alloy type evidence");
        }
        if (exactTypeVisitMap == null) {
            exactTypeVisitMap = new HashMap<>();
        }
        exactTypeVisitMap.merge(graph, timeOfVisit, Math::max);
    }

    public int nextExactTypeVisit(Multigraph graph) {
        Multigraph checked = java.util.Objects.requireNonNull(graph, "graph");
        if (exactTypeVisitMap == null) {
            exactTypeVisitMap = new HashMap<>();
        }
        int next = Math.addExact(exactTypeVisitMap.getOrDefault(checked, 0), 1);
        exactTypeVisitMap.put(checked, next);
        return next;
    }

    public ExactAlloyType getExactType(Multigraph graph, int timeOfVisit) {
        ExactAlloyType occurrence = exactTypeMapTOV == null
                ? null : exactTypeMapTOV.get(Pair.of(graph, timeOfVisit));
        return occurrence == null ? defaultExactType : occurrence;
    }

    public void setDefaultExactType(ExactAlloyType exactType) {
        ExactAlloyType checked = java.util.Objects.requireNonNull(exactType, "exactType");
        if (defaultExactType != null
                && !defaultExactType.sameOccurrenceEvidenceAs(checked)) {
            throw new IllegalStateException(
                    "One shared MASG leaf received incompatible exact Alloy type evidence");
        }
        defaultExactType = checked;
    }
}
