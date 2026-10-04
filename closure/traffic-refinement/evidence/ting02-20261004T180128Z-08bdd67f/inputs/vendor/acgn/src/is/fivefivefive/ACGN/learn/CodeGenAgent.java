package is.fivefivefive.ACGN.learn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.Stack;
import java.util.Vector;

import is.fivefivefive.ACGN.alloy.DeclRootSymbol;
import is.fivefivefive.ACGN.alloy.DummySymbol;
import is.fivefivefive.ACGN.alloy.EndSymbol;
import is.fivefivefive.ACGN.alloy.FieldRelation;
import is.fivefivefive.ACGN.alloy.MiddleSymbol;
import is.fivefivefive.ACGN.alloy.PredRootSymbol;
import is.fivefivefive.ACGN.alloy.SetSymbol;
import is.fivefivefive.ACGN.alloy.ShadowSymbol;
import is.fivefivefive.ACGN.alloy.SigSymbol;
import is.fivefivefive.ACGN.alloy.Symbol;
import is.fivefivefive.ACGN.alloy.VarSymbol;
import is.fivefivefive.ACGN.asg.AugmentedNode;
import is.fivefivefive.ACGN.asg.MASGEdge;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.codegen.Generator;
import is.fivefivefive.ACGN.etc.BiMap;
import is.fivefivefive.ACGN.etc.Triple;
import is.fivefivefive.ACGN.exceptions.ExceedMaxStepException;
import is.fivefivefive.ACGN.exceptions.ScopeNotReadyException;
import is.fivefivefive.ACGN.structure.RLScopeTreeNode;
import is.fivefivefive.ACGN.structure.ScopeTreeNode;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.util.Probability;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.alloyasg.exceptions.ScopeNotFoundException;
import is.fivefivefive.alloyasg.vector.Vector1D;
import parser.etc.Pair;

public class CodeGenAgent {
    private GlobalVariables gv;
    // private MASGVisitor visitor; // the visitor for the specific Alloy Model
    static final int MAX_STEPS = 100;
    private Multigraph groundTruth;
    private Multigraph currentAns;
    private Map<Pair<Symbol, Integer>, Map<Symbol, Float>> globalQTable;
    // private BiMap<Integer, Symbol> symbolId;
    private Map<Pair<Symbol, Integer>, Float> edgeRewardMap; // reward for each edge
    private int globalNewVarCounter = 100;
    private int rlScopeTreeNodeId = 100;
    private int stepNum = 0;
    private BiMap<Symbol, AugmentedNode> dynamicUniqueNodes;
    private Map<Symbol, Set<Symbol>> coarseToFineBin; // local rather than global. 
    private List<String> actionSequence; // log the action sequence, then apply reinforcement learning by Q-learning.
    private Map<Symbol, Integer> tovMap; // track the times of visit. 
    private int treeId;
    private RLScopeTreeNode rootScope;
    private int initializationState = 0;
    private Map<Pair<Symbol, Integer>, Map<Symbol, Float>> localVarDist;
    private Map<Triple<Symbol, Integer, Symbol>, Integer> localVarCounter; // track the polling scaling
    private Set<Symbol> leaves; // track the leaf nodes for local reward calculation.
    private Map<Symbol, RLScopeTreeNode> symbolScopeMap; // track the scopes that a symbol appears in, for scope collapsing and reward backpropagation.
    private Set<RLScopeTreeNode> visitedScopes; // a temporary set to track the synchronization of the scope tree over updating of the Q-table
    private Map<Integer, Set<RLScopeTreeNode>> scopeDepthMap; // track the scopes at each depth for scope collapsing.
    private int maxScopeDepth = 0;
    private Stack<MASGEdge> generationStack; // track the generation stack for backpropagation. 
    // Each time we go down a level in the tree, we push the corresponding edge into the stack; each time we backtrack, 
    // we pop the edge from the stack and update the Q-table entry for that edge with the accumulated reward.
    private Map<Triple<Symbol, Integer, Symbol>, MASGEdge> edgeMap; 
    // map from (source symbol, position, target symbol) to the corresponding edge in the graph for quick access during backpropagation.
    private Map<Symbol, List<MASGEdge>> downlinkEdgeMap; 
    // map from symbol to the list of edges for quick access during backpropagation.
    private Map<Symbol, Float> impactMap;
    // TODO: fill the scope map so RL backpropagation works. 

    public CodeGenAgent(Multigraph groundTruth, MASGVisitor visitor, GlobalVariables gv) {
        this.groundTruth = groundTruth;
        this.currentAns = new Multigraph();
        // this.visitor = visitor;
        this.gv = gv;
        // this.symbolId = symbolId;
        this.dynamicUniqueNodes = new BiMap<Symbol, AugmentedNode>();
        for (Symbol sym : gv.getUniqueNodes().keys()) {
            this.dynamicUniqueNodes.put(sym, gv.getUniqueNodes().get(sym));
        }
        this.coarseToFineBin = new HashMap<Symbol, Set<Symbol>>();
        for (Symbol dummy : DummySymbol.ALL_DUMMIES) {
            this.coarseToFineBin.put(dummy, new LinkedHashSet<Symbol>());
            this.coarseToFineBin.get(dummy).addAll(visitor.getFineSymbolsForCoarseSymbol(dummy));
        }
        this.actionSequence = new ArrayList<>();
        this.tovMap = new HashMap<>();
        this.treeId = visitor.getForest().size();
        this.rootScope = new RLScopeTreeNode(rlScopeTreeNodeId, null, currentAns);
        rootScope.addSymbol(DummySymbol.DUMMY_LOCAL_VAR); // the dummy for local vars to be preserved in the root scope
        // initialize the Q-table for the root scope
        this.globalQTable = initialCoarseQTable();
        initializationState = 0;
        this.localVarDist = new HashMap<>();
        this.localVarCounter = new HashMap<>();
        this.edgeRewardMap = new HashMap<>();
        this.leaves = new LinkedHashSet<>();
        this.symbolScopeMap = new HashMap<>();
        for (Symbol sym : gv.getUniqueNodes().keys()) {
            this.symbolScopeMap.put(sym, rootScope);
        }
        this.visitedScopes = new HashSet<>();
        this.scopeDepthMap = new HashMap<>();
        this.generationStack = new Stack<>();
    }
    public int getInitializationState() {
        return initializationState;
    }
    private void connect(AugmentedNode source, AugmentedNode target, int position, RLScopeTreeNode scope) {
        if (source.equals(target)) {
            // create shadow instead
            connect(source, dynamicUniqueNodes.get(ShadowSymbol.SHADOW), position, scope);
            return;
        }
        MASGEdge edge = source.connect(target, position, currentAns, tovMap.getOrDefault(source.getSymbol(), 1));
        edge.setScope(scope);
        generationStack.push(edge);
        edgeMap.put(Triple.of(source.getSymbol(), position, target.getSymbol()), edge);
        downlinkEdgeMap.computeIfAbsent(source.getSymbol(), k -> new ArrayList<>()).add(edge);
    }
    public Map<Pair<Symbol, Integer>, Map<Symbol, Float>> initialCoarseQTable() {
        Map<Pair<Symbol, Integer>, Map<Symbol, Float>> qTable = new HashMap<>();
        Map<Pair<Symbol, Integer>, Set<Symbol>> edgeMap = gv.getCoarseGrainCandidateMap();
        for (Pair<Symbol, Integer> positional : edgeMap.keySet()) {
            Symbol source = positional.a;
            int position = positional.b;
            Map<Symbol, Float> coarseProbabilities = Probability.coarseTokenProbabilities(gv, dynamicUniqueNodes, source, position);
            // Map<Symbol, Float> fineProbabilities = coarseToFineInit(coarseProbabilities);
            qTable.put(positional, coarseProbabilities);
        }
        return qTable;
    }
    public void initialize() {
        // TODO: Initialize the agent with coarse-grained token candidates and the unique nodes presenting in the model. 
        // to begin with, find all signatures, fields, reference points; 
        Map<Pair<Symbol, Integer>, Map<Symbol, Float>> initialQTable = initialCoarseQTable();
        Map<Pair<Symbol, Integer>, Map<Symbol, Float>> fineQTable = new HashMap<>();
        // System.err.println("Initial Q-table for predroot at 1: " + initialQTable.get(Pair.of(DummySymbol.DUMMY_PREDROOT, 1)));
        Set<Symbol> deadendSymbols = new HashSet<>(); // track the symbols that lead to dead ends (no fine candidates) for pruning
        for (Pair<Symbol, Integer> key : initialQTable.keySet()) {
            Map<Symbol, Float> coarseProbabilities = initialQTable.get(key);
            System.err.println("Coarse probabilities for " + key.a.getName() + " at position " + key.b + ": " + coarseProbabilities);
            Map<Symbol, Float> fineProbabilities = coarseToFineInit(coarseProbabilities);
            System.err.println("Fine probabilities for " + key.a.getName() + " at position " + key.b + ": " + fineProbabilities);
            // remove undefined behavior for tokens with no fine candidates
            if (!fineProbabilities.isEmpty()) {
                fineQTable.put(key, fineProbabilities); 
            } else {
                int maxDownlinks = key.a.getMaxDownlinks();
                if (maxDownlinks != -1) {
                    // remove the entry; 
                    deadendSymbols.add(key.a);
                } else {
                    fineProbabilities.put(MASGVisitor.END_SYMBOL, 1.0f); // if it is a position without valid subtokens, replace it with end
                    fineQTable.put(key, fineProbabilities);
                }
            }
        }
        // remove all deadend references from the Qtable
        for (Symbol deadend : deadendSymbols) {
            fineQTable.entrySet().removeIf(entry -> entry.getKey().a.equals(deadend));

        }
        // also remove them from positions down from other symbols
        // if any symbol is removed, we need to rescale the probabilities for the remaining symbols to ensure they sum up to 1
        for (Map.Entry<Pair<Symbol, Integer>, Map<Symbol, Float>> entry : fineQTable.entrySet()) {
            Pair<Symbol, Integer> key = entry.getKey();
            Map<Symbol, Float> probMap = entry.getValue();
            boolean modified = false;
            for (Symbol deadend : deadendSymbols) {
                if (probMap.containsKey(deadend)) {
                    probMap.remove(deadend);
                    modified = true;
                }
            }
            if (modified) {
                // rescale the probabilities
                float sum = 0.0f;
                for (float prob : probMap.values()) {
                    sum += prob;
                }
                for (Symbol token : probMap.keySet()) {
                    probMap.put(token, probMap.get(token) / sum);
                }
            }
        }
        this.rootScope.setqDist(fineQTable); // set the Q-table of the root scope to the fine-grained initialized Q-table
        this.scopeDepthMap.put(0, new HashSet<>());
        this.scopeDepthMap.get(0).add(rootScope);
        stepNum = 0;
        this.tovMap = new HashMap<>();
        initialQTable = fineQTable;
        globalQTable = initialQTable; // set the global Q-table to the fine-grained initialized Q-table
        for (Pair<Symbol, Integer> key : initialQTable.keySet()) {
            if (globalQTable.get(key).containsKey(DummySymbol.DUMMY_LOCAL_VAR)) {
                localVarDist.put(key, new HashMap<>()); // initialize local variables for each position as empty
            }
        }
        if (initializationState == 0) {
            initializationState = 1;
        } else {
            initializationState = 2; // reinitialized flag
        }
    } 
    private Map<Symbol, Float> coarseToFineInit(Map<Symbol, Float> coarseProbabilities) {
        Map<Symbol, Float> fineProbabilities = new HashMap<>();
        float totalRemovedCoarseProb = 0.0f; 
        for (Map.Entry<Symbol, Float> entry : coarseProbabilities.entrySet()) {
            Symbol coarseToken = entry.getKey();
            Float coarseProb = entry.getValue();
            if ((! (coarseToken instanceof DummySymbol)) || (coarseToken.equals(DummySymbol.DUMMY_LOCAL_VAR))) fineProbabilities.put(coarseToken, coarseProb);
            else if (coarseToFineBin.get(coarseToken) != null && !coarseToFineBin.get(coarseToken).isEmpty()) {
                // expand the dummy token to fine tokens
                coarseToFineBin.get(coarseToken).forEach(fineToken -> {
                    float fineProb = coarseProb / coarseToFineBin.get(coarseToken).size();
                    fineProbabilities.put(fineToken, fineProb);
                });
                fineProbabilities.remove(coarseToken); // remove the dummy token itself from the fine probabilities
                System.err.println("Expanded " + coarseToken.getName() + " at position to fine tokens: " + coarseToFineBin.get(coarseToken) + " with probability " + coarseProb);
            } else {
                // scale other tokens up
                fineProbabilities.remove(coarseToken);
                totalRemovedCoarseProb += coarseProb;
            }
        }
        // rescale
        if (totalRemovedCoarseProb > 0) {
            float scaleFactor = 1.0f / (1.0f - totalRemovedCoarseProb);
            for (Map.Entry<Symbol, Float> e : fineProbabilities.entrySet()) {
                Symbol token = e.getKey();
                fineProbabilities.put(token, e.getValue() * scaleFactor);
            }
        }
        return fineProbabilities;
    }
    
    public String generateNextPred(String predName) throws ExceedMaxStepException {
        currentAns = new Multigraph();
        // rootScope = new RLScopeTreeNode(treeId, visitor.getRootScope(), currentAns);
        rlScopeTreeNodeId = 100; // reset the scope tree node id for each new generation
        tovMap = new HashMap<>();
        leaves = new LinkedHashSet<>();
        AugmentedNode rootNode = new AugmentedNode(-1, treeId);
        Symbol root = new PredRootSymbol(rootNode, predName);
        coarseToFineBin.put(DummySymbol.DUMMY_LOCAL_VAR, new LinkedHashSet<>()); // reset the local var bin for each new generation
        dynamicUniqueNodes.put(root, rootNode);
        rootNode.setSymbol(root);
        // Multigraph predGraph = new Multigraph(rootNode, gv);
        // rootScope.setqDist(globalQTable); // set the Q-table of the root scope to the global Q-table before generation
        currentAns.addVertex(rootNode);
        rootScope.resetChildren(); // reset the children of the root scope before generation to avoid interference from previous generations
        maxScopeDepth = 0; // reset the max scope depth for each new generation
        stepNum = 0; // reset the step number for each new generation
        actionSequence = new ArrayList<>(); // reset the action sequence for each new generation
        currentAns.setScope(rootScope);
        generationStack = new Stack<>(); // reset the generation stack for each new generation
        edgeMap = new HashMap<>(); // reset the edge map for each new generation
        downlinkEdgeMap = new HashMap<>(); // reset the downlink edge map for each new generation
        impactMap = new HashMap<>(); // reset the corresponding impacts
        // System.err.println("rootScope dist: " + rootScope.getqDist());
        try {
            generateNextNode(rootNode, rootScope);
        } catch (ExceedMaxStepException e) {
            System.out.println("Generation exceeded maximum steps: " + e.getMessage());
            e.printStackTrace();
            return generateNextPred(predName); // restart generation if exceeded max steps to avoid getting stuck;
        }
        System.out.println("Generation completed with " + stepNum + " steps. ");
        System.out.println("Generation action sequence: " + actionSequence);
        Generator generator = new Generator();
        String code = null;
        try {
            code = generator.toCode(currentAns, rootNode.getDownlinks().get(0).getTarget(), 1, null);
            System.out.println("Generated code for " + predName + ":\n" + code);
        } catch (Exception e) {
            System.out.println("Error during code generation: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
        this.globalQTable = rootScope.getqDist(); // update the global Q-table with the one from the root scope after generation
        treeId++; // increment the tree ID for the next generation
        return code;
    }

    public int generateNextNode(AugmentedNode localParent, RLScopeTreeNode scope) throws ExceedMaxStepException {
        if (stepNum > MAX_STEPS) {
            throw new ExceedMaxStepException("Exceeded maximum steps in generation. Current node: " + localParent.getSymbol().getName());
        }
        Symbol source = localParent.getSymbol();
        if (leaves.contains(source)) return 0;
        // update the times of visit only for the first position for each visit
        tovMap.putIfAbsent(source, 0);
        tovMap.put(source, tovMap.get(source) + 1);
        // System.out.println("Visiting node " + localParent.getSymbol().getName() + " at position " + position + " for the " + tovMap.get(localParent.getSymbol()) + " time(s).");
        currentAns.updateTimeOfVisitMap(localParent, tovMap.get(source));
        stepNum++;
        
        if (source instanceof MiddleSymbol && ((MiddleSymbol) source).isQt()) {
            fillHoleQt(source, scope);
            // currentAns.addVertex(qtNode);
            // connect(localParent, qtNode, position, scope);
            // TODO: recursively generate downstream
            return 0; // success
        }
        int position = 1;
        Symbol nextToken = null;
        List<AugmentedNode> nextGenNodesList = new LinkedList<>();
        while (position <= source.getMaxDownlinks() || (source.getMaxDownlinks() == -1 && nextToken != MASGVisitor.END_SYMBOL)) {
            nextToken = fillHole(source, position, scope);
            AugmentedNode nextNode = dynamicUniqueNodes.get(nextToken);
            currentAns.addVertex(nextNode);
            connect(localParent, nextNode, position, scope);
            if (nextToken instanceof EndSymbol) {
                leaves.add(nextToken);
                break;
            } 
            if (nextToken instanceof ShadowSymbol) {
                leaves.add(nextToken); // technically shadow is a leave
                nextGenNodesList.add(localParent);
            } else {
                nextGenNodesList.add(nextNode);
            }
            position++;
        }

        // no need to generate siblings, since there is only one node down the root
        for (AugmentedNode nextNode : nextGenNodesList) {
            Symbol sym = nextNode.getSymbol();
            if (sym.getMaxDownlinks() != 0) {
                generateNextNode(nextNode, scope);
            } else {
                leaves.add(sym);
            }
        }

        return 0; // success
    }
    public Symbol fillHole(Symbol source, int position, RLScopeTreeNode currentScope) {
        // TODO: 1. use a randomizer and the Q-table to select the next token;
        // 2. log the action into the sequence; 
        // 3. return the selected token;
        if (source instanceof PredRootSymbol) {
            return fillHole(DummySymbol.DUMMY_PREDROOT, position, currentScope);
        }
        if (!currentScope.getqDist().containsKey(Pair.of(source, position))) {
            System.out.println("Q-table missing for " + source.getName() + " at position " + position);
            throw new RuntimeException("Q-table missing for " + source.getName() + " at position " + position);
        }
        Map<Pair<Symbol, Integer>, Map<Symbol, Float>> qTable = currentScope.getqDist();
        Random rand = new Random();
        float randomValue = rand.nextFloat();
        float cumulativeProbability = 0.0f;
        Symbol nextToken = null;
        for (Map.Entry<Symbol, Float> entry : qTable.get(Pair.of(source, position)).entrySet()) {
            Symbol token = entry.getKey();
            Float prob = entry.getValue();
            cumulativeProbability += prob;
            if (cumulativeProbability > randomValue) {
                nextToken = token;
                break;
            }
        }
        if (nextToken instanceof DummySymbol) {
            // if the selected token is dummy, we need to revert and generate again
            System.err.println("Selected token is a dummy symbol: " + nextToken.getName() + " at position " + position + " of " + source.getName() + ". Reverting and selecting again.");
            System.err.println("Current Q-entry value: " + qTable.get(Pair.of(source, position)));
            System.err.println("for RL Scope Tree ID " + rlScopeTreeNodeId);
            return fillHole(source, position, currentScope);
        }
        if (nextToken == null) {
            System.err.println("Incomplete Q-table set for " + source.getName() + " at position " + position);
            System.err.println("Current Q-entry value: " + qTable.get(Pair.of(source, position)));
            System.err.println("with cumulative probability " + cumulativeProbability + " and random value " + randomValue);
            throw new RuntimeException("No next token selected for " + source.getName() + " at position " + position);
        }
        actionSequence.add(source.getName() + ", " + position + " -> " + nextToken.getName());
        return nextToken;
    }

    private AugmentedNode fillHoleQt(Symbol qtRoot, RLScopeTreeNode currentScope) throws ExceedMaxStepException {
        stepNum++;
        // String label = qtRoot.getName();
        Map<Pair<Symbol, Integer>, Map<Symbol, Float>> qTable = currentScope.getqDist();
        // char label3 = label.charAt(3);
        // int syntactic = label3 == 'E' ? 3 : -3;
        // char labelLast = label.charAt(label.length() - 1);
        // int semantic = labelLast - '0';
        // AugmentedNode qtNode = new AugmentedNode(syntactic, semantic, qtRoot);
        AugmentedNode qtNode = dynamicUniqueNodes.get(qtRoot); 
        currentAns.addVertex(qtNode);
        /*tovMap.putIfAbsent(qtRoot, 0);
        tovMap.put(qtRoot, tovMap.get(qtRoot) + 1);
        currentAns.updateTimeOfVisitMap(qtNode, tovMap.get(qtRoot));*/
        rlScopeTreeNodeId++;
        RLScopeTreeNode qtScope = new RLScopeTreeNode(rlScopeTreeNodeId, currentScope, currentAns);
        maxScopeDepth++;
        scopeDepthMap.putIfAbsent(maxScopeDepth, new HashSet<>());
        scopeDepthMap.get(maxScopeDepth).add(qtScope);
        // TODO: update the qtable of the new scope;
        Symbol qt1 = fillHole(qtRoot, 1, qtScope);
        AugmentedNode qt1Node = dynamicUniqueNodes.get(qt1);
        currentAns.addVertex(qt1Node);
        connect(qtNode, qt1Node, 1, qtScope);
        actionSequence.add(qtRoot.getName() + ", body (1)  -> " + qt1.getName());
        int i = 2; 
        Random random = new Random();
        while (true) {
            // find if end; if not end -> add a reldecl;
            if (!qTable.containsKey(Pair.of(qtRoot, i))) break; 
            float nextRandom = random.nextFloat();
            float endProb = qTable.get(Pair.of(qtRoot, i)).containsKey(MASGVisitor.END_SYMBOL) ? 
                qTable.get(Pair.of(qtRoot, i)).get(MASGVisitor.END_SYMBOL) :
                0;
            if (nextRandom < endProb) {
                Symbol endSymbol = MASGVisitor.END_SYMBOL;
                AugmentedNode endNode = dynamicUniqueNodes.get(endSymbol);
                currentAns.addVertex(endNode);
                connect(qtNode, endNode, i, qtScope);
                leaves.add(endSymbol);
                actionSequence.add(qtRoot.getName() + ", " + i + ", <END>");
                break;
            } else {
                Map<Symbol, Float> sigProbabilities = qTable.get(Pair.of(qtRoot, i));
                nextRandom -= endProb;
                float cumulativeProbability = 0.0f;
                for (Map.Entry<Symbol, Float> entry : sigProbabilities.entrySet()) {
                    Symbol sig = entry.getKey();
                    if (sig instanceof EndSymbol) continue;
                    float sigProb = entry.getValue();
                    cumulativeProbability += sigProb;
                    if (cumulativeProbability > nextRandom) {
                        System.out.println("Selected signature for relation declaration: " + sig.getName() + " at position " + i + " of " + qtRoot.getName());
                        Symbol relDeclRoot = sig;
                        AugmentedNode anDown = fillHoleRelDecl(relDeclRoot, qtNode, qtScope);
                        currentAns.addVertex(anDown);
                        connect(qtNode, anDown, i, qtScope);
                        actionSequence.add(qtRoot.getName() + ", " + i + ", RELDECL ");
                        // break;
                    }
                }
                i++;
            }
        }
        qtScope.localizeQDist(localVarDist, globalQTable);
        if (qt1.getMaxDownlinks() != 0) {
            generateNextNode(qt1Node, qtScope);
        }
        if (qtNode.getDownlinksAtTimeOfVisit(currentAns, tovMap.get(qtRoot)) == null) {
            System.err.println("[INVARIANT VIOLATION] No downlinks found for qt node " + qtRoot.getName() + " at time of visit " + tovMap.get(qtRoot));
            throw new RuntimeException("No downlinks found for qt node " + qtRoot.getName() + " at time of visit " + tovMap.get(qtRoot));
        }
        return qtNode;
    }

    private AugmentedNode fillHoleRelDecl(Symbol relDeclRoot, AugmentedNode qtNode, RLScopeTreeNode currentScope) throws ExceedMaxStepException {
        if (!dynamicUniqueNodes.containsKey(relDeclRoot)) {
            dynamicUniqueNodes.put(relDeclRoot, new AugmentedNode(-127, 0, relDeclRoot));
        }
        AugmentedNode relDeclNode = dynamicUniqueNodes.get(relDeclRoot);
        tovMap.putIfAbsent(relDeclRoot, 0);
        tovMap.put(relDeclRoot, tovMap.get(relDeclRoot) + 1);
        currentAns.updateTimeOfVisitMap(relDeclNode, tovMap.get(relDeclRoot));
        Map<Pair<Symbol, Integer>, Map<Symbol, Float>> qTable = currentScope.getqDist();
        Random random = new Random();
        // TODO: generate type first PROBLEM: HERE BEGINS WITH CONFINERS NOT NODES
        // DEFINED SIGNATURE TYPE, TRY FILLHOLE HERE
        Symbol relDeclPos1 = fillHole(relDeclRoot, 1, currentScope);
        AugmentedNode pos1Node = dynamicUniqueNodes.get(relDeclPos1);
        generateNextNode(pos1Node, currentScope);
        connect(relDeclNode, pos1Node, 1, currentScope);
        Symbol fullTypeSig = relDeclNode.getDownlinksAtTimeOfVisit(currentAns, tovMap.get(relDeclRoot)).get(0).getTarget().getSymbol();
        Symbol typeSig = typeCheckSymbol(fullTypeSig);
        if (typeSig == null) {
            System.out.println("Type checking failed for symbol: " + fullTypeSig.getName() + " in relation declaration " + relDeclRoot.getName());
            throw new RuntimeException("Type checking failed for symbol: " + fullTypeSig.getName() + " in relation declaration " + relDeclRoot.getName());
        }
        // AugmentedNode sigNode = dynamicUniqueNodes.get(fullTypeSig);
        // connect(relDeclNode, sigNode, 1, currentScope);
        String sigName = implicitType(typeSig);
        actionSequence.add(relDeclRoot.getName() + ", 1 " + sigName);        
        int i = 2; 
        while (true) {
            if (!qTable.containsKey(Pair.of(relDeclRoot, i))) break;
            float nextRandom = random.nextFloat();
            float endProb = qTable.get(Pair.of(relDeclRoot, i)).containsKey(MASGVisitor.END_SYMBOL) ? 
                qTable.get(Pair.of(relDeclRoot, i)).get(MASGVisitor.END_SYMBOL) : 
                0;
            if (nextRandom < endProb) {
                Symbol endSymbol = MASGVisitor.END_SYMBOL;
                AugmentedNode endNode = dynamicUniqueNodes.get(endSymbol);
                connect(relDeclNode, endNode, i, currentScope);
                leaves.add(endSymbol);
                actionSequence.add(relDeclRoot.getName() + ", " + i + " <END> ");
                break;
            }
            System.out.println("Generating variable declaration for relation declaration " + relDeclRoot.getName() + " at position " + i + " with signature " + sigName);
            Symbol newVar = addVariableDecl(sigName, treeId, qtNode, currentScope);
            AugmentedNode varNode = dynamicUniqueNodes.get(newVar);
            currentAns.addVertex(varNode);
            connect(relDeclNode, varNode, i, currentScope);
            actionSequence.add(relDeclRoot.getName() + ", " + i + " VAR_" + sigName);
            i++;
        }
        return relDeclNode;
    }
    
    private String implicitType(Symbol implicitTypeBop) {
        if (implicitTypeBop instanceof SigSymbol) {
            return implicitTypeBop.getName();
        } else if (implicitTypeBop instanceof FieldRelation) {
            return implicitTypeBop.getName();
        } else if (implicitTypeBop instanceof MiddleSymbol) {
            if (implicitTypeBop.getMaxDownlinks() == 2) {
                AugmentedNode node = dynamicUniqueNodes.get(implicitTypeBop);
                List<MASGEdge> downlinks = node.getDownlinksAtTimeOfVisit(currentAns, tovMap.getOrDefault(implicitTypeBop, 1));
                if (downlinks == null || downlinks.isEmpty() || downlinks.size() < 2) {
                    throw new RuntimeException("No downlinks found for symbol: " + implicitTypeBop.getName() + " at time of visit: " + tovMap.getOrDefault(implicitTypeBop, 1));
                }
                Symbol leftSym = downlinks.get(0).getTarget().getSymbol();
                Symbol rightSym = downlinks.get(1).getTarget().getSymbol();
                String leftType = implicitType(leftSym);
                String rightType = implicitType(rightSym);
                if (leftType != null && rightType != null) {
                    return leftType + " " + implicitTypeBop.getName() + " " + rightType;
                }
            } else {
                AugmentedNode node = dynamicUniqueNodes.get(implicitTypeBop);
                List<MASGEdge> downlinks = node.getDownlinksAtTimeOfVisit(currentAns, tovMap.getOrDefault(implicitTypeBop, 1));
                if (downlinks == null || downlinks.isEmpty()) {
                    throw new RuntimeException("No downlinks found for symbol: " + implicitTypeBop.getName() + " at time of visit: " + tovMap.getOrDefault(implicitTypeBop, 1));
                }
                StringBuilder typeBuilder = new StringBuilder();
                int i = 0;
                for (MASGEdge downlink : downlinks) {
                    Symbol targetSym = downlink.getTarget().getSymbol();
                    String targetType = implicitType(targetSym);
                    if (targetType != null) {
                        typeBuilder.append(targetType).append(" ");
                    }
                    if (i < downlinks.size() - 1) {
                        typeBuilder.append(implicitTypeBop.getName()).append(" ");
                    }
                    i++;
                }
                if (typeBuilder.length() > 0) {
                    return typeBuilder.toString().trim();
                } else {
                    return "Failure";
                }
            }
        }
        return "Failure";
    }

    private Symbol typeCheckSymbol(Symbol sym) {
        System.out.println("Type checking symbol: " + sym.getName() + " " + sym.getClass().getSimpleName());
        // like MASGVisitor.typeCheckExpr but with symbols
        if (sym instanceof VarSymbol) {
            VarSymbol varSym = (VarSymbol) sym;
            String sigName = varSym.getType().substring(4); // remove "VAR_" prefix
            return new SigSymbol(sigName);
        } else if (sym instanceof SigSymbol) {
            return (SigSymbol) sym;
        } else if (sym instanceof FieldRelation) {
            return (FieldRelation) sym;
        } else if (sym instanceof MiddleSymbol) {
            MiddleSymbol middleSym = (MiddleSymbol) sym;
            if (middleSym.getMaxDownlinks() == 2) {
                // a relation for union/intersection/diff/join, so here is the type
                System.out.println("Middle symbol with 2 downlinks, treating as relation: " + middleSym.getName());
                return sym;
            }
            AugmentedNode node = dynamicUniqueNodes.get(sym);
            List<MASGEdge> downlinks = node.getDownlinksAtTimeOfVisit(currentAns, tovMap.getOrDefault(sym, 1));
            if (downlinks == null || downlinks.isEmpty()) {
                throw new RuntimeException("No downlinks found for symbol: " + sym.getName() + " at time of visit: " + tovMap.getOrDefault(sym, 1));
            }
            for (MASGEdge downlink : downlinks) {
                Symbol targetSym = downlink.getTarget().getSymbol();
                if (targetSym instanceof SigSymbol) {
                    return (SigSymbol) targetSym;
                } else if (targetSym instanceof FieldRelation) {
                    return (FieldRelation) targetSym;
                }
            }
            return downlinks.isEmpty() ? null : typeCheckSymbol(downlinks.get(0).getTarget().getSymbol()); // recursive check
        } else {
            return null; // for other symbol types, return null or throw an exception based on your design choice
        }
    }
    /**
     * An action defined to add a variable declaration in the scope. Add non-existent local variables only.
     * TODO: For pre-generated local variables, make a storage. 
     * @param sigName The signature name that the variable belongs to.
     * @param treeId The tree ID representing the scope level (0 for global).
     * @param confinerNode The AugmentedNode that confines the variable.
     * @param currentScope The current RLScopeTreeNode representing the scope in which the variable is declared.
     */
    public Symbol addVariableDecl(String sigName, int treeId, AugmentedNode confinerNode, RLScopeTreeNode currentScope) {
        // add a new variable into the scope of coarse to fine bin;
        globalNewVarCounter++;
        // encoding corresponding to De Bruijn indices: the variable name is not important, but the place in the scope tree is
        Symbol newVar = new VarSymbol(sigName, "var_" + globalNewVarCounter, 
                "local_var_" + sigName + "_s" + rlScopeTreeNodeId + "_" + currentScope.size(), 
                treeId, confinerNode);
        actionSequence.add("ADD_VAR " + ((VarSymbol)newVar).getHashName());
        // this.symbolId.put(this.symbolId.size(), newVar);
        symbolScopeMap.put(newVar, currentScope);
        // update the coarse to fine bin on initialization
        if (!coarseToFineBin.containsKey(DummySymbol.DUMMY_LOCAL_VAR) || !coarseToFineBin.get(DummySymbol.DUMMY_LOCAL_VAR).contains(newVar)) {
            coarseToFineBin.computeIfAbsent(DummySymbol.DUMMY_LOCAL_VAR, k -> new LinkedHashSet<>()).add(newVar);
            for (Pair<Symbol, Integer> key : localVarDist.keySet()) {
                localVarDist.get(key).put(newVar, 1.0f); // initialize the local variable distribution for the new variable as 1 for all positions
                Triple<Symbol, Integer, Symbol> counterKey = Triple.of(key.a, key.b, newVar);
                localVarCounter.put(counterKey, 0); // initialize the local variable counter for polling scaling
            }
        }
        // update the dynamic unique nodes
        AugmentedNode newNode = new AugmentedNode(127, globalNewVarCounter); // var nodes have signature 127
        newNode.setSymbol(newVar);
        currentScope.addSymbol(newVar);
        leaves.add(newVar);
        this.dynamicUniqueNodes.put(newVar, newNode);
        return newVar;
    }

    private static final float INERTIA = Hyperparams.INERTIA;
    /**
     * Update the Q-table based on the action taken and the reward received. 
     * Only update the Q-table entry for the current scope here. 
     * @param source the source symbol from which the action was taken.
     * @param position the position in the ASG where the action was taken.
     * @param selection the selected symbol.
     * @param reward the reward received for the action, local reward.
     * @param currentScope the current scope node.
     * @throws IllegalArgumentException
     */
    public void updateQTable(Symbol source, 
            int position, 
            Symbol selection, 
            float reward, 
            RLScopeTreeNode currentScope) throws IllegalArgumentException {
        // TODO: Rewrite the Q-table update. We need to use the formulas directly and get the hierarchies right. 
        // This method is STRICTLY just updating the Q-table entry for the current scope because the new pipeline is rewired. 
        // Invariants: 1. the local reward for the current action is already calculated and stored in the edgeRewardMap; 
        // 2. update it iteratively up, once at a time, tracking the uncolored MASG edges only. 
        // TODO: Define the order of backpropagation first. 
        if (source instanceof PredRootSymbol) {
            updateQTable(DummySymbol.DUMMY_PREDROOT, position, selection, reward, currentScope);
            return;
        }
        Map<Pair<Symbol, Integer>, Map<Symbol, Float>> qTable = currentScope.getqDist();
        if (!qTable.containsKey(Pair.of(source, position)) || !qTable.get(Pair.of(source, position)).containsKey(selection)) {
            System.err.println("Q-table at current scope: " + currentScope.getId() + " is missing entry for symbol: " + source.getName() + " at position: " + position + " with selection: " + selection.getName());
            throw new IllegalArgumentException("Q-table entry missing for symbol: " + source.getName() + " at position: " + position + " with selection: " + selection.getName());
        }
        Map<Symbol, Float> actionValues = qTable.get(Pair.of(source, position));
        for (Symbol action : actionValues.keySet()) {
            float oldValue = actionValues.get(action);
            if (action.equals(selection)) {
                float update = INERTIA * oldValue + (1 - INERTIA) * reward;
                float logUpdate = (float) (Math.log(update + 1e-6)); // add a small constant to avoid log(0)'
                if (logUpdate == Float.NaN) {
                    System.err.println("Log update is NaN for action: " + action.getName() + " with update value: " + update);
                    throw new RuntimeException("Log update is NaN for action: " + action.getName() + " with update value: " + update);
                }
                actionValues.put(action, logUpdate);
                if (impactMap.containsKey(source)) {
                    impactMap.put(source, impactMap.get(source) + logUpdate * reward);
                } else {
                    impactMap.put(source, logUpdate * reward);
                }
            } else {
                float logUpdate = (float) (Math.log(oldValue + 1e-6)); // add a small constant to avoid log(0)
                if (logUpdate == Float.NaN) {
                    System.err.println("Log update is NaN for action: " + action.getName());
                    throw new RuntimeException("Log update is NaN for action: " + action.getName());
                }
                actionValues.put(action, logUpdate);
            }
        }
        softmax(actionValues, Hyperparams.TEMPERATURE);
    }

    private void softmax(Map<Symbol, Float> actionValues, float temperature) {
        float sumExp = 0.0f;
        for (float value : actionValues.values()) {
            sumExp += Math.exp(value / temperature);
        }
        for (Map.Entry<Symbol, Float> entry : actionValues.entrySet()) {
            float softmaxValue = (float) (Math.exp(entry.getValue() / temperature) / sumExp);
            entry.setValue(softmaxValue);
        }
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
    public float localReward(
            Symbol source, 
            int position, 
            Symbol candidate, 
            float rawReward, 
            RLScopeTreeNode currentScope
        ) throws IllegalArgumentException {
        Map<Pair<Symbol, Integer>, Map<Symbol, Float>> qTable = currentScope.getqDist();
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate symbol cannot be null");
        }
        if (candidate.getMaxDownlinks() == 0) {
            edgeRewardMap.put(Pair.of(source, position), rawReward);
            return rawReward;
        }
        List<MASGEdge> childEdges = downlinkEdgeMap.get(candidate);
        if (childEdges == null || childEdges.isEmpty()) {
            throw new IllegalArgumentException("No child edges found for candidate symbol: " + candidate.getName() + " at position: " + position);
        }
        int maxOrEndPosition = 0;
        float localReward = 0f;
        for (MASGEdge edge : childEdges) {
            Symbol child = edge.getTarget().getSymbol();
            Pair<Symbol, Integer> childKey = Pair.of(candidate, edge.getPosition());
            /* if (!qTable.containsKey(childKey)) {
                throw new IllegalArgumentException("Q-table entry missing for child symbol: " + child.getName() + " at position: " + edge.getPosition());
            } else if (!qTable.get(childKey).containsKey(child)) {
                throw new IllegalArgumentException("Q-table entry missing for child symbol: " + child.getName() + " at position: " + edge.getPosition() + " in the children of candidate symbol: " + candidate.getName());
            }*/
            // float childProb = qTable.get(childKey).get(child);
            // float childProb = impactMap.get(child);
            // float childReward = edgeRewardMap.getOrDefault(childKey, 0f);
            localReward += impactMap.containsKey(child) ? impactMap.get(child) : 1f; // use the impact as the reward signal for the child
            if (edgeRewardMap.containsKey(childKey)) {
                maxOrEndPosition = Math.max(maxOrEndPosition, edge.getPosition());
            }
        }
        localReward /= maxOrEndPosition; // average over the children
        edgeRewardMap.put(Pair.of(source, position), localReward);
        return localReward;
    }
    
    /**
     * Backpropagates the reward through the generated code structure.
     * @param reward the reward to backpropagate
     * @throws ScopeNotReadyException if any scope is not ready
     */
    public void backpropagate(float reward) throws ScopeNotReadyException {
        // edges from the stack
        while (!generationStack.isEmpty()) {
            MASGEdge edge = generationStack.pop();
            RLScopeTreeNode scope = edge.getScope();
            if (scope == null) {
                throw new ScopeNotReadyException("Scope not found for edge: " + edge.getSource().getSymbol().getName() + " -> " + edge.getTarget().getSymbol().getName() + " at position " + edge.getPosition());
            } else if (!scope.isReady()) {
                throw new ScopeNotReadyException("Scope not ready for edge: " + edge.getSource().getSymbol().getName() + " -> " + edge.getTarget().getSymbol().getName() + " at position " + edge.getPosition());
            }
            Symbol source = edge.getSource().getSymbol();
            int position = edge.getPosition();
            Symbol target = edge.getTarget().getSymbol();
            float edgeReward = localReward(source, position, target, reward, scope);
            updateQTable(source, position, target, edgeReward, scope);
            MASGEdge nextEdge = generationStack.empty() ? null : generationStack.peek();
            if (nextEdge == null) break;
            RLScopeTreeNode nextScope = nextEdge.getScope();
            if (nextScope == null) {
                throw new ScopeNotReadyException("Scope not found for next edge: " + nextEdge.getSource().getSymbol().getName() + " -> " + nextEdge.getTarget().getSymbol().getName() + " at position " + nextEdge.getPosition());
            } else if (!nextScope.isReady()) {
                throw new ScopeNotReadyException("Scope not ready for next edge: " + nextEdge.getSource().getSymbol().getName() + " -> " + nextEdge.getTarget().getSymbol().getName() + " at position " + nextEdge.getPosition());
            }
            if (nextScope != scope) {
                // scope end;
                scope.dumpLocalVariables(localVarDist,localVarCounter);
                if (!nextScope.isReady()) nextScope.poll();
            }
        }
        globalQTable = rootScope.getqDist(); // update the global Q-table with the one from the root scope after backpropagation
    }
    /*
     * // TODOS: 
     * 1. Coarse token to fine token expansion; not in initialization because new fine tokens as the newly declared variables; 
     * Fields and signatures could be initialized by the general coarse token metric. 
     * 2. RL Agent PER MODEL: - rewrite the dynamic unique nodes each iteration; 
     * REDESIGN some Q-learning to include some multivariance? Increase the uplooking depth? 
     * Use a map: [nextToken : probability] at each (currentToken, position) pair. 
     */

    // TODOS: Make two types of Q-learner: - keep the scopetree; - reset it totally. 
}

