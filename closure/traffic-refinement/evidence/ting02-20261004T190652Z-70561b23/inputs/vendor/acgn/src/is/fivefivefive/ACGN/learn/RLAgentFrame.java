package is.fivefivefive.ACGN.learn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;

import is.fivefivefive.ACGN.alloy.DeclRootSymbol;
import is.fivefivefive.ACGN.alloy.EndSymbol;
import is.fivefivefive.ACGN.alloy.PredRootSymbol;
import is.fivefivefive.ACGN.alloy.RefSymbol;
import is.fivefivefive.ACGN.alloy.ShadowSymbol;
import is.fivefivefive.ACGN.alloy.Symbol;
import is.fivefivefive.ACGN.alloy.VarSymbol;
import is.fivefivefive.ACGN.asg.AugmentedNode;
import is.fivefivefive.ACGN.asg.MASGEdge;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.codegen.Generator;
import is.fivefivefive.ACGN.etc.BiMap;
import is.fivefivefive.ACGN.etc.Triple;
import is.fivefivefive.ACGN.test.Playground;
import is.fivefivefive.ACGN.test.RLTest;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.util.Probability;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.AlloyDataProcessor.EdgeCounter;
import is.fivefivefive.alloyasg.etc.DoubleMap;
import parser.etc.Pair;

/**
 * DEPRECATED: This is the non-functional version. Scrap for reusable codes. 
 * RLAgentFrame is the frame for the RL agent.
 * It contains the global variables, the ground truth, the current answer, and
 * the Q-table.
 * The Q-table is a mapping from (source symbol, position) to a set of
 * probabilities for each candidate symbol.
 */
public class RLAgentFrame {
    public static final int MAX_STEPS = 500;
    private GlobalVariables gv;
    private Multigraph groundTruth;
    private Multigraph currentAns;
    private Map<Pair<Symbol, Integer>, float[]> qTable;
    private BiMap<Integer, Symbol> symbolId;
    private Map<Pair<Symbol, Integer>, Float> edgeRewardMap; // reward for each edge
    private int globalNewVarCounter = 100;
    private BiMap<Symbol, AugmentedNode> dynamicUniqueNodes;

    // private BiMap<Symbol, AugmentedNode> uniqueNodes;
    public RLAgentFrame(GlobalVariables gv, Multigraph groundTruth, BiMap<Symbol, AugmentedNode> uniqueNodes) {
        this.gv = gv;
        this.groundTruth = groundTruth;
        // this.uniqueNodes = uniqueNodes;
        qTable = gv.getInitQTable() == null ? new HashMap<Pair<Symbol, Integer>, float[]>() : gv.getInitQTable();
        symbolId = new BiMap<Integer, Symbol>();
    }

    public RLAgentFrame(GlobalVariables gv, Multigraph groundTruth, BiMap<Symbol, AugmentedNode> uniqueNodes,
            Multigraph currentAns) {
        this(gv, groundTruth, uniqueNodes);
        this.currentAns = currentAns;
    }

    // also the case with the initial Q-table
    public RLAgentFrame(GlobalVariables gv, Multigraph groundTruth, BiMap<Symbol, AugmentedNode> uniqueNodes,
            Multigraph currentAns, Map<Pair<Symbol, Integer>, float[]> qTable) {
        this(gv, groundTruth, uniqueNodes, currentAns);
        this.qTable = qTable;
    }

    /**
     * Initialize the Q-table with the pretrained signatures.
     * The Q-table is a mapping from (source symbol, position) to a set of
     * probabilities for each candidate symbol.
     */
    public void initialize() {
        // initialize the Q-table
        /*
         * if (gv.getInitQTable() != null) {
         * qTable = gv.getInitQTable();
         * return; // already initialized
         * }
         */
        Map<Pair<Symbol, Integer>, Set<Symbol>> edgeMap = gv.getEdgeMap();
        for (Pair<Symbol, Integer> positional : edgeMap.keySet()) {
            // calculate by the pretrained signatures
            Symbol transformedA = positional.a instanceof PredRootSymbol || positional.a instanceof VarSymbol
                    ? EdgeCounter.getSymbolForPretrain(positional.a)
                    : positional.a;
            if (!gv.getUniqueNodes().containsKey(transformedA)) {
                System.out.println("Transformed symbol not found in unique nodes: " + transformedA.getType() + " -> "
                        + transformedA.getName());
                continue; // non-presenting symbols
            }
            if (!qTable.containsKey(positional)) {
                float[] dist = Probability.probabilitiesBySignatures(gv, gv.getUniqueNodes(), transformedA,
                        positional.b);
                qTable.put(positional, dist);
            }
            Symbol parent = transformedA;
            if (!symbolId.containsValue(parent)) {
                int id = symbolId.size();
                symbolId.put(id, parent);
            }
            for (Symbol child : edgeMap.get(positional)) {
                if (!symbolId.containsValue(child)) {
                    int id = symbolId.size();
                    symbolId.put(id, child);
                }
            }
        }
        gv.setInitQTable(qTable);
    }

    /**
     * Get the probability of a candidate symbol given a source symbol and its
     * position in the ASG.
     * 
     * @param source
     * @param position
     * @param candidate
     * @return
     */
    public float getProbability(Symbol source, int position, Symbol candidate) {
        Pair<Symbol, Integer> positional = Pair.of(source, position);
        if (qTable.containsKey(positional)) {
            float[] probabilities = qTable.get(positional);
            int index = symbolId.rget(candidate);
            if (index >= 0 && index < probabilities.length) {
                return probabilities[index];
            }
        }
        return 0.0f; // Default probability if not found
    }

    private static final double INERTIA = Hyperparams.INERTIA;

    /**
     * Update the Q-table with the reward for a given source symbol and its
     * position.
     * The update is done using the inertia and the reward.
     * local inertia wanes
     * 
     * @param source
     * @param position
     * @param reward
     */
    public void updateQTable(Symbol source, int position, Symbol selection, float reward)
            throws IllegalArgumentException {
        float[] qVector = qTable.get(Pair.of(source, position));
        if (qVector == null) {
            // TODO: Add the default qVectors for those new positions without existing data;
            // try to copy the last position? but where is our <END>;
            throw new IllegalArgumentException("Q-table entry not found for " + source + " at position " + position);
        }
        LinkedHashSet<Symbol> candidateSet = (LinkedHashSet<Symbol>) gv.getCandidates(source, position);
        List<Symbol> candidateList = new ArrayList<>(candidateSet);
        int index = candidateList.indexOf(selection);
        float[] temp = new float[qVector.length];
        for (int i = 0; i < qVector.length; i++) {
            if (i == index) {
                temp[i] = (float) (Math.log(qVector[i]) * INERTIA
                        + (reward > 0 ? Math.log(reward) * (1 - INERTIA) : 0));
            } else {
                temp[i] = (float) (Math.log(qVector[i]) * INERTIA);
            }
        }
        // Softmax normalization
        float sum = 0.0f;
        for (float value : temp) {
            sum += Math.exp(value);
        }
        for (int i = 0; i < temp.length; i++) {
            temp[i] = (float) (Math.exp(temp[i]) / sum);
        }
        System.out.println("Updated Q-table: " + Pair.of(source, position) + " -> " + Arrays.toString(temp));
        qTable.put(Pair.of(source, position), temp);
    }

    /**
     * Calculate the local reward for a candidate symbol based on its children in
     * the ASG.
     * This method looks for all children of the candidate symbol and calculates the
     * local reward based on their probabilities.
     * * If the candidate symbol has no children, it returns the raw reward.
     * 
     * @param source    the source symbol from which the candidate is derived.
     * @param position  the position in the ASG where the candidate is located.
     * @param candidate the candidate symbol for which the local reward is
     *                  calculated.
     * @param rawReward the raw reward value associated with the generated current
     *                  predicate.
     * @return the calculated local reward based on the children of the candidate
     *         symbol.
     */
    public float localReward(Symbol source, int position, Symbol candidate, float rawReward) {
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate symbol cannot be null");
        }
        if (candidate.getMaxDownlinks() == 0) {
            edgeRewardMap.put(Pair.of(source, position), rawReward);
            return rawReward;
        }
        // look for all children of the candidate
        AugmentedNode candidateNode = gv.getUniqueNodes().get(candidate);
        if (candidateNode == null) {
            throw new IllegalArgumentException("Candidate node not found for " + candidate);
        }
        List<MASGEdge> downlinks = currentAns.edgesUnder(candidateNode);
        if (downlinks.isEmpty()) {
            return rawReward; // No children, return the raw reward
        }
        float ans = 0.0f;
        for (MASGEdge edge : downlinks) {
            AugmentedNode targetNode = edge.getTarget();
            Symbol targetSymbol = targetNode.getSymbol();
            if (targetSymbol == null) {
                throw new IllegalArgumentException("Target symbol cannot be null for edge: " + edge);
            }
            // Calculate the local reward based on the target symbol
            float localImpact = qTable.get(Pair.of(source, position))[symbolId.rget(targetSymbol)];
            float downReward = 0.0f;
            if (edgeRewardMap.containsKey(Pair.of(source, edge.getPosition()))) {
                downReward = edgeRewardMap.get(Pair.of(source, edge.getPosition()));
            } else {
                downReward = localReward(candidate, edge.getPosition(), targetSymbol, rawReward);
            }
            ans += localImpact * downReward;
        }
        return ans; // Placeholder for local reward calculation
    }

    /**
     * Generate the next predicate in the ASG based on the ground truth.
     * This method creates a root node and connects it to the parameters of the
     * ground truth.
     * It then generates the body root and recursively generates the next nodes in
     * the ASG.
     * 
     * @param predName The name of the predicate to be generated.
     * @return The generated code as a string.
     */
    public String generateNextPred(String predName) {
        // make a root node
        dynamicUniqueNodes = new BiMap<Symbol, AugmentedNode>();
        dynamicUniqueNodes.putAll(gv.getUniqueNodes()); // initialize the dynamic unique nodes with the global unique
                                                        // nodes;
        edgeRewardMap = new HashMap<Pair<Symbol, Integer>, Float>(); // reset the edge reward map
        AugmentedNode rootNode = new AugmentedNode(-1, -1);
        Symbol root = new PredRootSymbol(rootNode, predName);
        rootNode.setSymbol(root);
        root.setMaxDownlinks(1);
        Multigraph predGraph = new Multigraph(rootNode, gv);
        currentAns = predGraph;
        // same parameters as the ground truth
        List<MASGEdge> downlinks = groundTruth.getRoot().getDownlinks();
        if (RLTest.DEBUG) {
            System.out.println("Generating predicate: " + predName);
            System.out.println("Downlinks size: " + downlinks.size());
            // System.out.println(downlinks);
        }
        int iter = 2;
        if (downlinks.size() > 2) {
            for (int i = 0; i < downlinks.size() - 1; ++i) {
                MASGEdge edge = downlinks.get(i);
                System.out.println("Param: " + edge.getTarget().getSymbol().getName());
                AugmentedNode param = new AugmentedNode(edge.getTarget());
                predGraph.addVertex(param);
                predGraph.connect(rootNode, param, predGraph, iter, 1);
                iter++;
            }
        }

        rootNode.setMaxDownlinks(1);
        // generate the body root.
        /*int signal = generateNextNode(rootNode, 1, new HashMap<>(), 0, 0);
        if (signal == 1) {
            currentAns = null;
            return generateNextPred(predName);
        }*/
        generateNodesIter(rootNode, new HashMap<>());

        Generator generator = new Generator();
        String code = null;
        try {
            code = generator.toCode(currentAns, rootNode.getDownlinks().get(0).getTarget(), 1, null);
        } catch (Exception e) {
            return generateNextPred(predName); // regenerate the predicate
        }

        return code;

    }

    /**
     * Recursively generate the next node in the ASG based on the current node and
     * its position.
     * This method uses the Q-table to select candidates based on their
     * probabilities.
     * 
     * @param localRoot The current node in the ASG.
     * @param position  The position in the ASG where the next node will be
     *                  generated.
     * @param tovMap    A map tracking the number of times each symbol has been
     *                  visited.
     * @param depth     The depth of the node in the ASG.
     * @param stepNum   The number of steps taken.
     * @return A signal of success or failure.
     */
    public int generateNextNode(AugmentedNode localRoot, int position, Map<Symbol, Integer> tovMap, int depth,
            int stepNum) {
        if (stepNum > MAX_STEPS) {
            System.out.println("Max steps reached: " + stepNum);
            // GIVE THE ZERO REWARD.
            // updateQTable(localRoot.getSymbol(), position, 0);
            return 1;
        }
        System.out.println("Generating next node for " + localRoot.getSymbol().getName() + " at position " + position);
        BiMap<Symbol, AugmentedNode> uniqueNodes = dynamicUniqueNodes;
        // TODO : TOV TRACKER.
        if (localRoot.getMaxDownlinks() != -1 && position > localRoot.getMaxDownlinks()) {
            return 0; // No more positions to explore
        }
        Symbol localRootSym = localRoot.getSymbol();
        if (localRootSym instanceof PredRootSymbol) {
            localRootSym = EdgeCounter.getSymbolForPretrain(localRootSym);
            System.out.println("Transformed type: " + localRootSym.getName());
        }
        tovMap.putIfAbsent(localRootSym, 0);
        if (position == 1) {
            tovMap.put(localRootSym, tovMap.get(localRootSym) + 1);
            // Also update the graph's timeOfVisitMap to keep it in sync
            currentAns.updateTimeOfVisitMap(localRoot, tovMap.get(localRootSym));
        }
        System.out.println("current TOV: " + tovMap.get(localRootSym));
        Random rand = new Random();
        Set<Symbol> candidates = gv.getCandidates(localRootSym, position);
        /*
         * if (RLTest.DEBUG) {
         * System.out.println(qTable);
         * }
         */
        float[] distribution = qTable.get(Pair.of(localRootSym, position));
        System.out.println("Size of distribution: " + (distribution == null ? "null" : distribution.length));
        if (candidates == null || candidates.isEmpty() || distribution == null) {
            return 0; // No candidates or no distribution available
        }
        // Select a candidate based on the distribution
        float randomValue = rand.nextFloat();
        float cumulativeProbability = 0.0f;
        Symbol selectedCandidate = null;
        int i = 0;
        for (Symbol candidate : candidates) {
            if (!uniqueNodes.containsKey(candidate)) {
                continue;
            }
            cumulativeProbability += distribution[i];
            if (randomValue <= cumulativeProbability) {
                selectedCandidate = candidate;
                if (RLTest.DEBUG) {
                    System.out.println("Selected candidate: " + selectedCandidate.getName() + " with probability: "
                            + distribution[i] + " at position " + position);
                }
                break;
            }
            i++;
        }
        if (selectedCandidate == null) {
            return 0; // No candidate selected
        }
        // Create a new node for the selected candidate
        // AugmentedNode newNode =
        /*
         * if (!uniqueNodes.containsKey(selectedCandidate)) {
         * uniqueNodes.put(selectedCandidate,
         * gv.getUniqueNodes().get(selectedCandidate));
         * }
         */
        AugmentedNode newNode = uniqueNodes.get(selectedCandidate);
        // problem here: the newNode is sometimes null, when referring to a concrete
        // node derived from an abstract class; unknown reason.

        // TODO: Recursively generate the next node
        if ((!(selectedCandidate instanceof EndSymbol))
                && (localRoot.getMaxDownlinks() > position || localRoot.getMaxDownlinks() == -1)) {
            // next sibling
            int signal = generateNextNode(localRoot, position + 1, tovMap, depth, stepNum + 1);
            if (signal == 1) {
                float reward = localReward(localRootSym, position, selectedCandidate, 0);
                updateQTable(localRootSym, position, selectedCandidate, reward);
                return 1;
            }
        }
        boolean shadow = newNode.getSymbol() instanceof ShadowSymbol;
        // Use TOV from tovMap (which was set at position==1) for connecting
        // This ensures we use the correct TOV for this parent node
        int localRootTov = tovMap.get(localRootSym);
        localRoot.connect(newNode, position, currentAns, localRootTov);
        // TODO: special case: var declarations shall generate all new variables down
        // here.
        Symbol newSym = newNode.getSymbol();
        if (newSym instanceof DeclRootSymbol) {
            int signal = generateNextNode(newNode, 1, tovMap, depth + 1, stepNum + 1);
            if (signal == 1) {
                return 1;
            }
            // TODO: rewrite the DeclRoot structure to capture all the probabilities
            // correctly;
            AugmentedNode varNode0 = new AugmentedNode(127, globalNewVarCounter);
            globalNewVarCounter++;
            Symbol varSym0 = new VarSymbol("generic", "var0", -1, newNode.getDownlinks().get(0).getTarget());
            varNode0.setSymbol(varSym0);
            currentAns.addVertex(varNode0);
            newNode.connect(varNode0, 2, currentAns, localRootTov);
            dynamicUniqueNodes.put(varSym0, varNode0);
            int varId = 1;
            // look for other variables possibly generated by probabilities;
            while (true) {
                float selection = rand.nextFloat();
                float probabilityOfEnd = qTable.get(Pair.of(newSym, varId + 2))[0];
                if (selection <= probabilityOfEnd) {
                    break;
                }
                AugmentedNode varNode = new AugmentedNode(127, globalNewVarCounter);
                globalNewVarCounter++;
                Symbol varSym = new VarSymbol("generic", "var" + varId, -1, newNode.getDownlinks().get(0).getTarget());
                varNode.setSymbol(varSym);
                currentAns.addVertex(varNode);
                newNode.connect(varNode, varId + 2, currentAns, localRootTov);
                dynamicUniqueNodes.put(varSym, varNode);
                varId++;
            }
            return 0; // skip the generic code generation part for declaration nodes;
        }
        System.out.println("Downlinks size: " + localRoot.getDownlinks().size());
        System.out.println(newNode.getMaxDownlinks());
        if (newNode.getMaxDownlinks() != 0) {
            // first child
            int signal = generateNextNode(newNode, 1, tovMap, depth + 1, stepNum + 1);
            if (signal == 1) {
                float reward = localReward(localRootSym, position, selectedCandidate, 0);
                updateQTable(localRootSym, position, selectedCandidate, reward);
                return 1;
            }
        } else if (shadow) {
            int signal = generateNextNode(localRoot, 1, tovMap, depth + 1, stepNum + 1);
            if (signal == 1) {
                float reward = localReward(localRootSym, position, selectedCandidate, 0);
                updateQTable(localRootSym, position, selectedCandidate, reward);
                return 1;
            }
        }
        return 0;
    }
    private void generateNodesIter(AugmentedNode root, Map<Symbol, Integer> tovMap) {
        List<AugmentedNode> iterativeNodeList = new ArrayList<>();
        int depth = 0;
        iterativeNodeList.add(root);
        while (!iterativeNodeList.isEmpty()) {
            System.out.println("Depth: " + depth);
            List<AugmentedNode> nextDepthNodeList = new ArrayList<>();
            for (AugmentedNode currentNode : iterativeNodeList) {
                Symbol currentSym = currentNode.getSymbol();
                if (currentSym instanceof PredRootSymbol) {
                    currentSym = EdgeCounter.getSymbolForPretrain(currentSym);
                }
                tovMap.putIfAbsent(currentSym, 0);
                tovMap.put(currentSym, tovMap.get(currentSym) + 1);
                int localRootTov = tovMap.get(currentSym);
                currentAns.updateTimeOfVisitMap(currentNode, tovMap.get(currentSym));
                int position = 1;
                while (position <= currentNode.getMaxDownlinks() || currentNode.getMaxDownlinks() == -1) {
                    System.out.println("Position: " + position);
                    Set<Symbol> candidates = gv.getCandidates(currentSym, position);
                    float[] distribution = qTable.get(Pair.of(currentSym, position));
                    System.out.println("Distribution length: " + (distribution == null ? "null" : distribution.length) + " for " + currentSym.getName() + " at position " + position);
                    System.out.println("Candidates size: " + (candidates == null ? "null" : candidates.size()));
                    if (candidates.size() > 100) {
                        // mysteriously long candidates list; output it to see
                        System.out.println("Mysteriously long candidates list: " + candidates + " for " + currentSym.getName() + " at position " + position);
                    }
                    Random rand = new Random();
                    float randomValue = rand.nextFloat();
                    float cumulativeProbability = 0.0f;
                    Symbol selectedCandidate = null;
                    int i = 0;
                    for (Symbol candidate : candidates) {
                        if (!dynamicUniqueNodes.containsKey(candidate)) {
                            continue;
                        }
                        cumulativeProbability += distribution[i]; // maybe some problems with distribution length? 
                        if (randomValue <= cumulativeProbability) {
                            selectedCandidate = candidate;
                            System.out.println("Selected candidate: " + selectedCandidate.getName() + "at cumulative probability: " + cumulativeProbability);
                            break;
                        }
                        i++;
                        if (i == candidates.size()) {
                            System.out.println("No candidate selected for " + currentSym.getName() + " at position " + position);
                            throw new RuntimeException("No candidate selected for " + currentSym.getName() + " at position " + position);
                        }
                    }
                    AugmentedNode newNode = dynamicUniqueNodes.get(selectedCandidate);
                    if (newNode == null) {
                        System.out.println("New node is null for candidate: " + selectedCandidate.getName());
                        throw new NullPointerException("New node is null for candidate: " + selectedCandidate.getName());
                    }
                    boolean shadow = selectedCandidate instanceof ShadowSymbol;
                    if (shadow) {
                        newNode = new AugmentedNode(currentNode);
                        newNode.setSymbol(currentNode.getSymbol());
                    }
                    currentNode.connect(newNode, position, currentAns, localRootTov);
                    // TODO: handle the declare roots; dummy code down here. 
                    if (selectedCandidate instanceof DeclRootSymbol) {
                        AugmentedNode varNode0 = new AugmentedNode(127, globalNewVarCounter);
                        globalNewVarCounter++;
                        Symbol varSym0 = new VarSymbol("generic", "var0", -1,
                                currentNode); 
                            // the usually defined confiner node is still undefined in the current breadth-first generation
                            // try to use the parent node as the confiner node. Try generation. 
                        varNode0.setSymbol(varSym0);
                        currentAns.addVertex(varNode0);
                        newNode.connect(varNode0, 2, currentAns, localRootTov);
                        dynamicUniqueNodes.put(varSym0, varNode0);
                        int varId = 1;
                        float probabilityOfEnd = 0;
                        float selection = 1;
                        while (selection > probabilityOfEnd) {
                            selection = rand.nextFloat();
                            probabilityOfEnd = qTable.get(Pair.of(currentSym, varId + 2))[0];
                            if (selection <= probabilityOfEnd) {
                                break;
                            }
                            AugmentedNode varNode = new AugmentedNode(127, globalNewVarCounter);
                            globalNewVarCounter++;
                            Symbol varSym = new VarSymbol("generic", "var" + varId, -1,
                                    currentNode.getDownlinks().get(0).getTarget());
                            varNode.setSymbol(varSym);
                            currentAns.addVertex(varNode);
                            newNode.connect(varNode, varId + 2, currentAns, localRootTov);
                            dynamicUniqueNodes.put(varSym, varNode);
                            varId++;
                        }
                        continue; // finished the generation of new var declarations. 
                    }
                    nextDepthNodeList.add(newNode);
                    if (selectedCandidate instanceof EndSymbol) {
                        break;
                    }
                    position++;
                }
            }
            iterativeNodeList = nextDepthNodeList;
            depth++;
        }
        // every space have been filled here
    }

    public void testMethod() {

    }
}
