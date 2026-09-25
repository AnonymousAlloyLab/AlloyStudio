package is.fivefivefive.ACGN.alloy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import is.fivefivefive.alloyasg.asg.ASGVisitor;

/*
 * Abstract Alloy Model Environment defined by a collection of predefined sets.
 * Consider a universal representation of the various Alloy models.
 */
public class AAME {
    // TODO
    private Map<String, Symbol> symbols;
    private List<ExtFact> facts;
    public AAME(ASGVisitor<Object> asgv) {
        //TODO: Find all of the nonpredicate symbols in the Alloy environment and put into the AAME
        
    }
    public AAME() {
        symbols = new HashMap<>();
        facts = new ArrayList<>();
    }
    public void fetchFromCloud(String cloud) {
        // TODO: Setup a database
    }
    // TODO: IMPLEMENT FACTS AND FIELD IMPLICIT FACTS. 
    public Map<String, Symbol> getSymbols() {
        return symbols;
    }
    public List<ExtFact> getFacts() {
        return facts;
    }
    public void setFacts(List<ExtFact> facts) {
        this.facts = facts;
    }
    public void addSymbol(String key, Symbol s) {
        symbols.put(key, s);
    }
    public Symbol getSymbol(String key) {
        return symbols.get(key);
    }
    public Symbol getSymbol(int key) {
        return symbols.get(String.valueOf(key));
    }
    public boolean hasSymbol(String key) {
        return symbols.containsKey(key);
    }
    public boolean hasSymbol(Symbol sym) {
        return symbols.containsValue(sym);
    }
    public int symbolsSize() {
        return symbols.size();
    }
    public void addFact(ExtFact f) {
        facts.add(f);
    }
}
