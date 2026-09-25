package is.fivefivefive.ACGN.structure;

import is.fivefivefive.ACGN.alloy.Symbol;
import is.fivefivefive.ACGN.asg.Multigraph;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedList;

public class ScopeTreeNode {
    private int id;
    private Map<String, Symbol> symbols;
    private List<ScopeTreeNode> children;
    private ScopeTreeNode parent;
    private Multigraph affliation;
    private int nextBindingSlot;
    public ScopeTreeNode(int id, ScopeTreeNode parent, Multigraph affl) {
        this.id = id;
        symbols = new HashMap<>();
        children = new LinkedList<>();
        this.parent = parent;
        affliation = affl;
        nextBindingSlot = 0;
        if (parent != null) {
            parent.addChildren(this);
        }
    }
    public ScopeTreeNode(int id, ScopeTreeNode parent) {
        this(id, parent, parent.getAffliation());
    }
    public ScopeTreeNode(int id, Map<String, Symbol> symbols, ScopeTreeNode parent, Multigraph affl) {
        this.id = id;
        this.symbols = symbols;
        children = new LinkedList<>();
        this.parent = parent;
        affliation = affl;
        nextBindingSlot = 0;
        if (parent != null) {
            parent.addChildren(this);
        }
    }
    public List<ScopeTreeNode> getChildren() {
        return children;
    }
    public Multigraph getAffliation() {
        return affliation;
    }
    public void addChildren(ScopeTreeNode next) {
        children.add(next);
    }
    public Map<String, Symbol> getSymbols() {
        return symbols;
    }
    public Symbol getSymbol(String name) {
        if (!symbols.containsKey(name)) {
            if (parent == null) {
                return null;
            }
            return parent.getSymbol(name);
        }
        return symbols.get(name);
    }
    public boolean containsSymbol(Symbol sym) {
        return symbols.containsValue(sym);
    }
    public int getId() {
        return id;
    }
    public ScopeTreeNode getParent() {
        return parent;
    }
    public void addSymbol(Symbol next) {
        symbols.put(next.getName(), next);
    }
    public Map<String, Symbol> symbolsAvailable() {
        Map<String, Symbol> result = new HashMap<>();
        ScopeTreeNode current = this;
        int lvl = 0;
        while (current.parent != null) {
            Map<String, Symbol> currMap = current.getSymbols();
            for (String key : currMap.keySet()) {
                String newKey = lvl + "_" + key;
                result.put(newKey, currMap.get(key));
            }
            lvl += 1;
            current = current.parent;
        }
        return result;
    }
    public Map<String, Symbol> symbolsAvailableInSubgraph() {
        Map<String, Symbol> result = new HashMap<>();
        ScopeTreeNode current = this;
        int lvl = 0;
        while (current.parent != null && current.parent.getAffliation() == this.affliation) {
            Map<String, Symbol> currMap = current.getSymbols();
            for (String key : currMap.keySet()) {
                String newKey = lvl + "_" + key;
                result.put(newKey, currMap.get(key));
            }
            lvl += 1;
            current = current.parent;
        }
        return result;
    }
    public int size() {
        return symbols.size();
    }
    public int allocateBindingSlot() {
        return nextBindingSlot++;
    }
    public void resetChildren() {
        this.children = new LinkedList<>();
    }
}
