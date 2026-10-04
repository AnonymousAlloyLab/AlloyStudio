package is.fivefivefive.ACGN.alloy;
/* 
import java.util.HashSet;
import java.util.Set;
*/
import is.fivefivefive.ACGN.asg.AugmentedNode;

public class VarSymbol extends AbstractSymbol {
    private String type; // signature that the variable is an element of 
    private String varName; // the name of the variable
    private int treeIdScope; // 0 for global symbols;
    private AugmentedNode node; // the node that this symbol is associated with
    private boolean isGlobal;
    // private Set<FieldConfiner> fieldConfinerSet; // the set of field confiners that this variable is subject to
    private AugmentedNode confinerNode; 
    private String hashName; // the name used for hashing, which is a combination of type and varName
    public VarSymbol(String sig, String varName, int treeId, AugmentedNode confinerNode) {
        type = "VAR_" + sig;
        this.varName = varName;
        treeIdScope = treeId;
        node = new AugmentedNode(-1, 0);
        // fieldConfinerSet = new HashSet<>();
        this.confinerNode = confinerNode;
        isGlobal = (treeId == 0);
        hashName = type + "_" + varName;
    }
    public VarSymbol(String sig, String varName, String hashName, int treeId, AugmentedNode confinerNode) {
        type = "VAR_" + sig;
        this.varName = varName;
        treeIdScope = treeId;
        node = new AugmentedNode(-1, 0);
        // fieldConfinerSet = new HashSet<>();
        this.confinerNode = confinerNode;
        isGlobal = (treeId == 0);
        this.hashName = hashName;
    }
    public String getType() {
        return type;
    }
    public String getName() {
        return varName;
    }
    public AugmentedNode toNode() {
        return node;
    }
    public boolean isEndSymbol() {
        return false;
    }
    public int getScopeId() {
        return treeIdScope;
    }
    /* public Set<FieldConfiner> getFieldConfinerSet() {
         return fieldConfinerSet;
    }
    public void setFieldConfinerSet(Set<FieldConfiner> fieldConfinerSet) {
        this.fieldConfinerSet = fieldConfinerSet;
    }*/ 
    public AugmentedNode getConfinerNode() {
        return confinerNode;
    }
    public void setConfinerNode(AugmentedNode confinerNode) {
        this.confinerNode = confinerNode;
    }
    @Override
    public int getMaxDownlinks() {
        return 0; // VarSymbol does not have downlinks
    }
    @Override
    public void setMaxDownlinks(int maxDownlinks) {
        // VarSymbol does not have downlinks, so this method does nothing
    }
    public boolean isGlobal() {
        return isGlobal;
    }
    public String getHashName() {
        return hashName;
    }
    public void setHashName(String hashName) {
        this.hashName = hashName;
    }
    public int hashCode() {
        return hashName.hashCode();
    }
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if (obj instanceof VarSymbol) {
            VarSymbol other = (VarSymbol) obj;
            return this.hashName.equals(other.hashName);
        } else {
            return false;
        }
    }
}
