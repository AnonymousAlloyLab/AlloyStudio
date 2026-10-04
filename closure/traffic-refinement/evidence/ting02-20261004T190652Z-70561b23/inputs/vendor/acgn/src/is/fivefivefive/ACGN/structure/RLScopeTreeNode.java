package is.fivefivefive.ACGN.structure;

import java.util.Map;
import java.util.HashMap;

import parser.etc.Pair;
import is.fivefivefive.ACGN.alloy.DeclRootSymbol;
import is.fivefivefive.ACGN.alloy.DummySymbol;
import is.fivefivefive.ACGN.alloy.Symbol;
import is.fivefivefive.ACGN.alloy.VarSymbol;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.etc.Triple;

public class RLScopeTreeNode extends ScopeTreeNode {
    public static final float OLD_VARS_RESERVE_RATE = 0.2f; // deprecated
    private Map<Pair<Symbol, Integer>, Map<Symbol, Float>> qDist;
    private Map<Symbol, String> sigCorr;
    private Symbol rootSymbol;
    private Map<Pair<Symbol, Integer>, Map<Symbol, Float>> qDistBackup; // backup the old Q-dist for local variables
                                                                        // after rescaling
    private Map<Pair<Symbol, Integer>, Float> localVarPriorProb;
    private boolean active = false;
    // active: gets the localized qDist to activate, but not ready for training
    // until poll() is called to aggregate the qDist from the children nodes.
    // deactivate when it collapses back to the parent node, and the qDist is dumped
    // to the parent node, and the current node is reset for the next round of
    // training.
    private boolean ready = true;

    // ready: leaf nodes are default ready for training, but non-leaf nodes need to
    // wait for the qDist from the children nodes to be polled up before they are
    // ready for training.
    // TODO: Use this to keep the old iteration of Q-values for the Scope Tree Node.
    public RLScopeTreeNode(int id, ScopeTreeNode parent) {
        super(id, parent);
        this.qDist = new HashMap<>();
        this.sigCorr = new HashMap<>();
        this.qDistBackup = new HashMap<>();
    }

    public RLScopeTreeNode(int id, ScopeTreeNode parent, Multigraph affl) {
        super(id, parent, affl);
        this.qDist = new HashMap<>();
        this.sigCorr = new HashMap<>();
        this.qDistBackup = new HashMap<>();
    }

    public RLScopeTreeNode(int id, Map<String, Symbol> symbols, ScopeTreeNode parent, Multigraph affl) {
        super(id, symbols, parent, affl);
        this.qDist = new HashMap<>();
        this.sigCorr = new HashMap<>();
        this.qDistBackup = new HashMap<>();
    }

    private void copyFromParent(RLScopeTreeNode parent) {
        // deep copy except the symbols which are invariant hashes
        parent.getqDist().forEach((k, v) -> this.qDist.put(k, new HashMap<>()));
        parent.getqDist().forEach((k, v) -> v.forEach((candidate, prob) -> this.qDist.get(k).put(candidate, prob)));
        parent.deready();
        parent.addChildren(this);
    }

    public RLScopeTreeNode(int id, RLScopeTreeNode parent) {
        super(id, parent);

        // this.qDist = parent == null ? new HashMap<>() : parent.getqDist();
        this.qDist = new HashMap<>();
        // deep copy except the symbols which are invariant hashes
        if (parent != null) {
            copyFromParent(parent);
        }
        this.sigCorr = new HashMap<>();
        this.qDistBackup = new HashMap<>();
    }

    public RLScopeTreeNode(int id, RLScopeTreeNode parent, Multigraph affl) {
        super(id, parent, affl);
        // this.qDist = parent == null ? new HashMap<>() : parent.getqDist();
        this.qDist = new HashMap<>();
        // deep copy except the symbols which are invariant hashes
        if (parent != null) {
            copyFromParent(parent);
        }
        this.sigCorr = new HashMap<>();
        this.qDistBackup = new HashMap<>();
    }

    public RLScopeTreeNode(int id, Map<String, Symbol> symbols, RLScopeTreeNode parent, Multigraph affl) {
        super(id, symbols, parent, affl);
        // this.qDist = parent == null ? new HashMap<>() : parent.getqDist();
        this.qDist = new HashMap<>();
        // deep copy except the symbols which are invariant hashes
        if (parent != null) {
            copyFromParent(parent);
        }
        this.sigCorr = new HashMap<>();
        this.qDistBackup = new HashMap<>();
    }

    public RLScopeTreeNode(int id, Map<String, Symbol> symbols, ScopeTreeNode parent, Multigraph affl,
            Map<Pair<Symbol, Integer>, Map<Symbol, Float>> qDist) {
        super(id, symbols, parent, affl);
        this.qDist = new HashMap<>();
        qDist.forEach((k, v) -> this.qDist.put(k, new HashMap<>()));
        qDist.forEach((k, v) -> v.forEach((candidate, prob) -> this.qDist.get(k).put(candidate, prob)));
        this.sigCorr = new HashMap<>();
        this.qDistBackup = new HashMap<>();
    }

    public void addSymbol(Symbol next) {
        if (rootSymbol == null) {
            rootSymbol = next;
        }
        super.addSymbol(next);
        if (next instanceof VarSymbol) {
            VarSymbol varNext = (VarSymbol) next;
            sigCorr.put(varNext, varNext.getType());
        }
    }

    public String typeOf(Symbol s) {
        if (sigCorr.containsKey(s)) {
            return sigCorr.get(s);
        }
        if (getParent() != null && getParent() instanceof RLScopeTreeNode) {
            return ((RLScopeTreeNode) getParent()).typeOf(s);
        }
        return null;
    }

    public Map<Pair<Symbol, Integer>, Map<Symbol, Float>> getqDist() {
        return qDist;
    }

    public void setqDist(Map<Pair<Symbol, Integer>, Map<Symbol, Float>> qDist) {
        this.qDist = qDist;
    }

    // encapsulation of the Q-table update AND do the backup of the old weights.
    public void updateQDistAt(Pair<Symbol, Integer> parentPair, Symbol candidate, float newProb) {
        qDistBackup.computeIfAbsent(parentPair, k -> new HashMap<>()).put(candidate,
                qDist.getOrDefault(parentPair, new HashMap<>()).getOrDefault(candidate, 0f));
        qDist.computeIfAbsent(parentPair, k -> new HashMap<>()).put(candidate, newProb);
    }

    public void resetQDist() {
        this.qDist = new HashMap<>();
        for (ScopeTreeNode children : getChildren()) {
            if (children instanceof RLScopeTreeNode) {
                ((RLScopeTreeNode) children).resetQDist();
            }
        }
    }

    public void localizeQDist(Map<Pair<Symbol, Integer>, Map<Symbol, Float>> localVarDist, Map<Pair<Symbol, Integer>, Map<Symbol, Float>> globalQTable) {
        if (getParent() == null || !(getParent() instanceof RLScopeTreeNode)) {
            throw new RuntimeException("Only non-root RLScopeTreeNode can be localized.");
        }
        // one down from the root
        for (Map.Entry<Pair<Symbol, Integer>, Map<Symbol, Float>> entry : qDist.entrySet()) {
            Pair<Symbol, Integer> key = entry.getKey();
            if (key.a instanceof DeclRootSymbol) continue; // skip localization for declarations. 
            Map<Symbol, Float> candidateProbs = entry.getValue();
            if (!candidateProbs.containsKey(DummySymbol.DUMMY_LOCAL_VAR)) continue; // skip localization if there is no local variable candidate, which means the localVarProb is 0
            // float localVarProb = candidateProbs.get(DummySymbol.DUMMY_LOCAL_VAR);
            float localVarWeightSum = 0f;
            for (Symbol localVar : symbolsAvailable().values()) {
                if (localVarDist.containsKey(key) && localVarDist.get(key).containsKey(localVar)) {
                    localVarWeightSum += localVarDist.get(key).get(localVar);
                } else {
                    throw new RuntimeException("Local variable " + localVar
                            + " does not have a weight in the local variable distribution for " + key.a
                            + " at position " + key.b);
                }
            }
            float localVarProb = globalQTable.getOrDefault(key, new HashMap<>()).getOrDefault(DummySymbol.DUMMY_LOCAL_VAR, 0f);
            // distribute the localVarProb to the local variables according to the
            // localVarDist, and update the qDist for the current node
            for (Symbol localVar : symbolsAvailable().values()) {
                if (localVarDist.containsKey(key) && localVarDist.get(key).containsKey(localVar)) {
                    float weight = localVarDist.get(key).get(localVar);
                    float localVarProbForThisVar = localVarWeightSum == 0 ? 0
                            : localVarProb * weight / localVarWeightSum;
                    qDist.computeIfAbsent(key, k -> new HashMap<>()).put(localVar, localVarProbForThisVar);
                    System.out.println("Localized QDist for " + localVar + " under " + key.a + " at position " + key.b
                            + " with probability " + localVarProbForThisVar);
                } else {
                    throw new RuntimeException("Local variable " + localVar
                            + " does not have a weight in the local variable distribution for " + key.a
                            + " at position " + key.b);
                }
            }
            // remove the DUMMY_LOCAL_VAR from the qDist after localizing, since the local
            // variable candidates are now explicitly represented in the qDist.
            qDist.get(key).remove(DummySymbol.DUMMY_LOCAL_VAR);
        }
        // after localizing the qDist, the current node is now active for RL training.
        active = true;
    }

    public void dumpLocalVariables(Map<Pair<Symbol, Integer>, Map<Symbol, Float>> localVarDist,
            Map<Triple<Symbol, Integer, Symbol>, Integer> localVarCounter) {
        Map<Pair<Symbol, Integer>, Float> totalLocalVarQRatio = new HashMap<>();
        boolean isDirectlyUnderRoot = getParent() != null && getParent().getParent() == null;
        for (Pair<Symbol, Integer> keyPair : qDist.keySet()) {
            Map<Symbol, Float> candidateProbs = qDist.get(keyPair);
            float totalLocalVarProb = 0f;
            // find the local variables down from each pair
            for (Symbol candidate : candidateProbs.keySet()) {
                if (getSymbols().containsValue(candidate)) {
                    // this is a local variable
                    float prob = candidateProbs.get(candidate);
                    totalLocalVarQRatio.put(keyPair, totalLocalVarQRatio.getOrDefault(keyPair, 0f) + prob);
                    totalLocalVarProb += prob;
                }
            }
            for (Symbol localVar : candidateProbs.keySet()) {
                if (getSymbols().containsValue(localVar)) {
                    // this is a local variable
                    if (!localVarPriorProb.containsKey(keyPair)) {
                        throw new RuntimeException(
                                "Local variable " + localVar + " does not have a prior probability in the old qDist of "
                                        + keyPair.a + " at position " + keyPair.b);
                    } else {
                        totalLocalVarQRatio.put(keyPair,
                                totalLocalVarQRatio.getOrDefault(keyPair, 0f) / localVarPriorProb.get(keyPair));
                    }
                    float prob = candidateProbs.get(localVar);
                    float localVarWeight = prob * getSymbols().size() * totalLocalVarQRatio.get(keyPair);
                    localVarDist.computeIfAbsent(keyPair, k -> new HashMap<>()).put(localVar, localVarWeight);
                    localVarCounter.put(Triple.of(keyPair.a, keyPair.b, localVar),
                            localVarCounter.getOrDefault(Triple.of(keyPair.a, keyPair.b, localVar), 0) + 1);
                    qDist.get(keyPair).put(localVar, 0f); // reset the probability so the qDist could be salvaged up
                } else if (!isDirectlyUnderRoot) {
                    // the scope is not directly under the root
                    // this is not a local variable within the scope, scale the probability up
                    float scale = 1 - totalLocalVarProb;
                    if (scale == 0) {
                        throw new RuntimeException("Total local variable probability is 1 for " + keyPair.a
                                + " at position " + keyPair.b + ", cannot scale up the non-local-variable candidates.");
                    }
                    float prob = candidateProbs.get(localVar);
                    qDist.get(keyPair).put(localVar, prob / scale);
                }
            }
            if (isDirectlyUnderRoot) {
                // if the scope is directly under the root, we can simply dump the local
                // variable probabilities to DUMMY_LOCAL_VAR
                qDist.get(keyPair).put(DummySymbol.DUMMY_LOCAL_VAR, totalLocalVarProb);
            }
        }
        // after dumping the local variables, the qDist of the current node is now
        // localized to the parent node, and can be used for the parent node's update.
        active = false;
    }

    public Symbol getRootSymbol() {
        return rootSymbol;
    }

    public void rescaleLocalVars(float newLocalVarProb) {
        // get the total probability of local variables in the current qDist for each
        // parent pair, and rescale them to newLocalVarProb, while keeping the relative
        // probabilities among the local variables unchanged.
        if (getSymbols().containsValue(DummySymbol.DUMMY_LOCAL_VAR)) {
            // scale the probability of DUMMY_LOCAL_VAR to newLocalVarProb, and scale the
            // probabilities of the other candidates accordingly to keep the total
            // probability sum to 1
            for (Map.Entry<Pair<Symbol, Integer>, Map<Symbol, Float>> entry : qDist.entrySet()) {
                Pair<Symbol, Integer> parentPair = entry.getKey();
                Map<Symbol, Float> candidateProbs = entry.getValue();
                float dummyLocalVarProb = candidateProbs.getOrDefault(DummySymbol.DUMMY_LOCAL_VAR, 0f);
                if (dummyLocalVarProb > 0) {
                    for (Map.Entry<Symbol, Float> candidateEntry : candidateProbs.entrySet()) {
                        Symbol candidate = candidateEntry.getKey();
                        float prob = candidateEntry.getValue();
                        if (candidate.equals(DummySymbol.DUMMY_LOCAL_VAR)) {
                            candidateProbs.put(candidate, newLocalVarProb);
                            qDistBackup.put(parentPair, new HashMap<>());
                            qDistBackup.get(parentPair).put(candidate, newLocalVarProb);
                        } else {
                            // rescale non-local-variable candidates to keep the total probability sum to 1
                            candidateProbs.put(candidate, prob * (1 - newLocalVarProb) / (1 - dummyLocalVarProb));
                        }
                    }
                }
            }
            return;
        }
        for (Map.Entry<Pair<Symbol, Integer>, Map<Symbol, Float>> entry : qDist.entrySet()) {
            Pair<Symbol, Integer> parentPair = entry.getKey();
            Map<Symbol, Float> candidateProbs = entry.getValue();
            float totalLocalVarProb = localVarProb(parentPair);
            // rescale the probabilities of the candidates for the parent pair
            if (totalLocalVarProb > 0) {
                float scale = newLocalVarProb / totalLocalVarProb;
                for (Map.Entry<Symbol, Float> candidateEntry : candidateProbs.entrySet()) {
                    Symbol candidate = candidateEntry.getKey();
                    float prob = candidateEntry.getValue();
                    candidateProbs.put(candidate, prob * scale);
                }
            }
            // rescale the other candidates to keep the total probability sum to 1
            float totalOtherProb = 1 - totalLocalVarProb;
            if (totalOtherProb > 0) {
                float otherScale = (1 - newLocalVarProb) / totalOtherProb;
                for (Map.Entry<Symbol, Float> candidateEntry : candidateProbs.entrySet()) {
                    Symbol candidate = candidateEntry.getKey();
                    float prob = candidateEntry.getValue();
                    if (!getSymbols().containsValue(candidate)) {
                        candidateProbs.put(candidate, prob * otherScale);
                    }
                }
            }
        }
    }

    public float localVarProb(Pair<Symbol, Integer> parentPair) {
        Map<Symbol, Float> candidateProbs = qDist.getOrDefault(parentPair, new HashMap<>());
        float totalLocalVarProb = 0f;
        for (Symbol localSymbol : getSymbols().values()) {
            if (candidateProbs.containsKey(localSymbol)) {
                totalLocalVarProb += candidateProbs.get(localSymbol);
            }
        }
        return totalLocalVarProb;
    }

    public void poll() {
        // for all RLScopeTreeNode children, poll them up to the current node, update
        // the qDist to the avg of the two
        Map<Pair<Symbol, Integer>, Map<Symbol, Float>> localizedQDist = new HashMap<>();
        int childCount = 0;
        for (ScopeTreeNode child : getChildren()) {
            if (child instanceof RLScopeTreeNode) {
                childCount++;
                RLScopeTreeNode rlChild = (RLScopeTreeNode) child;
                Map<Pair<Symbol, Integer>, Map<Symbol, Float>> childQDist = rlChild.getqDist();
                for (Map.Entry<Pair<Symbol, Integer>, Map<Symbol, Float>> entry : childQDist.entrySet()) {
                    Pair<Symbol, Integer> parentPair = entry.getKey();
                    Map<Symbol, Float> childCandidateProbs = entry.getValue();
                    for (Map.Entry<Symbol, Float> candidateEntry : childCandidateProbs.entrySet()) {
                        Symbol candidate = candidateEntry.getKey();
                        float childProb = candidateEntry.getValue();
                        float currentProb = localizedQDist.getOrDefault(parentPair, new HashMap<>())
                                .getOrDefault(candidate, 0f);
                        localizedQDist.computeIfAbsent(parentPair, k -> new HashMap<>()).put(candidate,
                                currentProb + childProb);
                    }
                }
            }
        }
        // average the qDist from the children
        for (Map.Entry<Pair<Symbol, Integer>, Map<Symbol, Float>> entry : localizedQDist.entrySet()) {
            Pair<Symbol, Integer> parentPair = entry.getKey();
            Map<Symbol, Float> candidateProbs = entry.getValue();
            for (Map.Entry<Symbol, Float> candidateEntry : candidateProbs.entrySet()) {
                Symbol candidate = candidateEntry.getKey();
                float totalProb = candidateEntry.getValue();
                localizedQDist.get(parentPair).put(candidate, totalProb / childCount);
            }
        }
        // update the current node's qDist to the localized qDist
        this.qDist = localizedQDist;
        // after polling, the current node is now ready for RL training.
        ready = true;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isReady() {
        return ready;
    }

    public void deready() {
        this.ready = false;
    }

    @Override
    public void resetChildren() {
        super.resetChildren();
        this.ready = true;
    }
}
