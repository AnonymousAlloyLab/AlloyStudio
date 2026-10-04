package is.fivefivefive.ACGN.util;

import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.List;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashMap;

import is.fivefivefive.ACGN.alloy.DummySymbol;
import is.fivefivefive.ACGN.alloy.PredRootSymbol;
import is.fivefivefive.ACGN.alloy.Symbol;
import is.fivefivefive.ACGN.asg.AugmentedNode;
import is.fivefivefive.ACGN.asg.MASGEdge;
import is.fivefivefive.ACGN.etc.BiMap;
import is.fivefivefive.ACGN.test.Playground;
import is.fivefivefive.AlloyDataProcessor.EdgeCounter;
import is.fivefivefive.alloyasg.etc.DoubleMap;
import parser.etc.Pair;

// TODO: Globalize all unique nodes. 
public final class GlobalVariables implements Serializable {
    private Map<Pair<Symbol, Integer>, Set<Symbol>> edgeMap;
    private Map<Pair<Symbol, Integer>, Set<Symbol>> coarseGrainCandidateMap;
    private Map<Symbol, Integer> maxChildCount;
    private Map<Pair<Symbol, Integer>, float[]> initQTable;
    private BiMap<Symbol, AugmentedNode> uniqueNode;
    private List<Symbol> concreteRoots; // the predicate roots
    private Map<Symbol, Set<Symbol>> coarseToFineBin;
    private double uniformVarSig;
    //private Map<String, Set<Symbol>> classOfSymbols;
    public GlobalVariables() {
        edgeMap = new HashMap<Pair<Symbol, Integer>, Set<Symbol>>();
        maxChildCount = new HashMap<Symbol, Integer>();
        initQTable = new HashMap<Pair<Symbol, Integer>, float[]>();
        uniqueNode = new BiMap<Symbol, AugmentedNode>();
        coarseGrainCandidateMap = new HashMap<Pair<Symbol, Integer>, Set<Symbol>>();
        uniformVarSig = 0.0;
        coarseToFineBin = new HashMap<Symbol, Set<Symbol>>();
        for (Symbol dummy : DummySymbol.ALL_DUMMIES) {
            coarseToFineBin.put(dummy, new LinkedHashSet<Symbol>());
        }
    }
    public Map<Pair<Symbol, Integer>, Set<Symbol>> getEdgeMap() {
        return edgeMap;
    }
    public Set<Symbol> getCandidates(Symbol source, int position) {
        return edgeMap.get(Pair.of(source, position));
    }
    public Set<Symbol> getCoarseGrainCandidates(Symbol source, int position) {
        return coarseGrainCandidateMap.get(Pair.of(source, position));
    }
    public Map<Pair<Symbol, Integer>, Set<Symbol>> getCoarseGrainCandidateMap() {
        return coarseGrainCandidateMap;
    }
    public Map<Symbol, Set<Symbol>> getCoarseToFineBin() {
        return coarseToFineBin;
    }
    public void addEdge(Symbol source, Symbol target, int position) {
        Symbol coarseSource = source instanceof PredRootSymbol ? DummySymbol.DUMMY_PREDROOT : source; // predroot is the only dummy being nonterminal
        if (!edgeMap.containsKey(Pair.of(source, position))) {
            edgeMap.put(Pair.of(source, position), new LinkedHashSet<Symbol>());
            coarseGrainCandidateMap.put(Pair.of(coarseSource, position), new LinkedHashSet<Symbol>());
        }
        if (maxChildCount.containsKey(source)) {
            int count = maxChildCount.get(source);
            if (count < position + 1) {
                maxChildCount.put(source, position + 1);
            }
        } else {
            maxChildCount.put(source, position + 1);
        }
        edgeMap.get(Pair.of(source, position)).add(target);
        Symbol coarseHash = EdgeCounter.getSymbolForPretrain(target);
        if (coarseHash instanceof DummySymbol) {
            coarseToFineBin.get(coarseHash).add(target);
        }
        coarseGrainCandidateMap.get(Pair.of(coarseSource, position)).add(coarseHash); // the coarse-grain version
        // System.out.println("Added coarse candidate: " + coarseHash.getName() + " for source: " + coarseSource.getName() + " at position: " + position);
    }
    public void addEdge(MASGEdge edge, int position) {
        addEdge(edge.getSource().getSymbol(), edge.getTarget().getSymbol(), position);
    }
    public void addEdge(AugmentedNode source, AugmentedNode target, int position) {
        addEdge(source.getSymbol(), target.getSymbol(), position);

    }
    public void combine(GlobalVariables another) {
        // Combine the edgeMaps
        for (Pair<Symbol, Integer> source : another.getEdgeMap().keySet()) {
            if (!edgeMap.containsKey(source)) {
                edgeMap.put(source, new LinkedHashSet<Symbol>());
                coarseGrainCandidateMap.put(source, new LinkedHashSet<Symbol>());
            }
            edgeMap.get(source).addAll(another.getEdgeMap().get(source));
            coarseGrainCandidateMap.get(source).addAll(another.getCoarseGrainCandidates(source.a, source.b));
        }
    }
    public int getMaxChildCount(Symbol source) {
        if (maxChildCount.containsKey(source)) {
            return maxChildCount.get(source);
        } else {
            return 0; // No children
        }
    }
    public Map<Symbol, Integer> getMaxChildCountMap() {
        return maxChildCount;
    }
    public void setInitQTable(Map<Pair<Symbol, Integer>, float[]> initQTable) {
        this.initQTable = initQTable;
        System.out.println("Initialized Q-table with " + initQTable.size() + " entries.");
        writeToFile("global_variables.tmp.ser", this);
    }
    public Map<Pair<Symbol, Integer>, float[]> getInitQTable() {
        return initQTable;
    }
    public BiMap<Symbol, AugmentedNode> getUniqueNodes() {
        return uniqueNode;
    }
    public void setUniqueNodes(BiMap<Symbol, AugmentedNode> uniqueNode) {
        this.uniqueNode = uniqueNode;
    }
    public void addUniqueNodes(BiMap<Symbol, AugmentedNode> nextUniqueNodes) {
        for (Symbol key : nextUniqueNodes.keys()) {
            Symbol keyMod = EdgeCounter.getSymbolForPretrain(key);
            AugmentedNode node = nextUniqueNodes.get(key);
            if (keyMod instanceof DummySymbol) {
                // make a dummy AugmentedNode
                int dummyId = uniqueNode.size();
                AugmentedNode dummyNode = new AugmentedNode(-1, dummyId, keyMod);
                node = dummyNode;
            }
            if (!uniqueNode.containsKey(keyMod)) {
                System.out.println("Adding key: " + keyMod);
                uniqueNode.put(keyMod, node);
            }
        }
    }
    public void addCustomUniqueNodes(BiMap<Symbol, AugmentedNode> visitorNodes) {
        for (Symbol symbol : visitorNodes.keys()) {
            AugmentedNode node = visitorNodes.get(symbol);
            Symbol categorySymbol = EdgeCounter.getSymbolForPretrain(symbol);
            if (categorySymbol instanceof DummySymbol && !(categorySymbol.getType().equals("predroot"))) {
                // transformed
                double signature = uniqueNode.get(categorySymbol).getSignature();
                if (uniformVarSig == 0.0) {
                    uniformVarSig = signature;
                }
                double randomNoise = (Math.random() - 1) * 0.01; // small noise
                node.setSignature(signature + randomNoise);
                node.setMaxDownlinks(0);
                System.out.println("Adding custom unique node: " + categorySymbol.getType() + " -> " + symbol.getName() + " with signature " + signature);
                uniqueNode.put(symbol, node);
                
            }
            if (categorySymbol instanceof DummySymbol && (categorySymbol.getType().equals("predroot"))) {
                String name = symbol.getName();
                if (name.startsWith("run") || name.equals("overconstrained") || name.equals("underconstrained") || name.equals("both")) {
                    continue; // skip check commands
                }
                // add all predicate candidates down
                System.out.println("Merging predroot downlinks: " + categorySymbol.getType() + " -> " + symbol.getName());
                // update edgeMap
                Map<Pair<Symbol, Integer>, Set<Symbol>> additionalEdges = new HashMap<Pair<Symbol, Integer>, Set<Symbol>>();
                for (Pair<Symbol, Integer> key : edgeMap.keySet()) {
                    if (key.a instanceof PredRootSymbol) {
                        Set<Symbol> targets = edgeMap.get(key);
                        Pair<Symbol, Integer> newKey = Pair.of(categorySymbol, key.b);
                        additionalEdges.putIfAbsent(newKey, new LinkedHashSet<Symbol>());
                        additionalEdges.get(newKey).addAll(targets);
                    }
                }
                edgeMap.putAll(additionalEdges);
            }
            if (!uniqueNode.containsKey(symbol) && !(categorySymbol.getType().equals("predroot"))) {
                uniqueNode.put(symbol, node);
            }
        }
    }
    public static void writeToFile(String filename, GlobalVariables gv) {
        // serialize the GlobalVariables object to a file
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filename)))
        {
            oos.writeObject(gv);
            BiMap.writeToFile("map_" + filename, gv.uniqueNode);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public static GlobalVariables readFromFile(String filename) {
        // deserialize the GlobalVariables object from a file
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(filename))) {
            GlobalVariables gv = (GlobalVariables) ois.readObject();
            System.out.println(gv.edgeMap.size());
            System.out.println(gv.uniqueNode.size());
            return gv;
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }
    
    public void loadPretrainedSignatures(String nodeIdCsvPath, String signatureCsvPath) {
        Map<String, Float> symbolSignatureMap = EdgeCounter.generateNodeSignatureMap(nodeIdCsvPath, signatureCsvPath);
        for (Symbol symbol : uniqueNode.keys()) {
            String key = symbol.getName();
            if (symbolSignatureMap.containsKey(key)) {
                float signature = symbolSignatureMap.get(key);
                uniqueNode.get(symbol).setSignature(signature);
            } else {
                System.out.println("ERR: No pretrained signature for: " + key);
                // DEFAULT TO ZERO
                uniqueNode.get(symbol).setSignature(0.0);
            }
        }
    }
    public double getUniformVarSig() {
        return uniformVarSig;
    }
    public void setUniformVarSig(double uniformVarSig) {
        this.uniformVarSig = uniformVarSig;
    }
}
