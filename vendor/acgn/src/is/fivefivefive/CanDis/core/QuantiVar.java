package is.fivefivefive.CanDis.core;

import is.fivefivefive.ACGN.alloy.ExactAlloyType;

/**
 * This class encodes quantified variables. They are defined as "slots" in the quantification system
 * invariants: variables are up to De Bruijn indices, but still have names; most importantly, encoding types.
 */
public class QuantiVar {
    public enum Quantifier {
        SUM,
        COMPREHENSION,
        ALL,
        SOME,
        NO,
        ONE,
        LONE,
        NOTONE,
        NOTLONE
    }
    public enum Cardinality {
        SET,
        SOME,
        ONE,
        LONE,
        EXACTLY
    }

    private int id;
    private String name;
    private String originalName;
    private final java.util.Set<String> originalNames;
    private String typeName;
    private String carrierTypeName;
    private String deBruijnKey;
    private Quantifier quantifier;
    private Cardinality cardinality;
    private int disjointnessClass;
    private String bindingPath;
    private ExactAlloyType exactAlloyType;
    private volatile boolean frozenForCertification;
    public QuantiVar(int id, String name, String typeName) {
        this(id, name, name, typeName);
    }
    public QuantiVar(int id, String name, String originalName, String typeName) {
        this.id = id;
        this.name = name;
        this.originalName = originalName;
        this.originalNames = new java.util.LinkedHashSet<>();
        addOriginalName(originalName);
        this.typeName = typeName;
        this.carrierTypeName = typeName;
        this.deBruijnKey = name;
        this.quantifier = Quantifier.SOME;
        this.cardinality = Cardinality.SET;
        this.disjointnessClass = 0;
        this.bindingPath = "";
    }
    public int getId() {
        return id;
    }
    public String getName() {
        return name;
    }
    public String getOriginalName() {
        return originalName;
    }
    public java.util.Set<String> getOriginalNames() {
        return java.util.Collections.unmodifiableSet(originalNames);
    }
    public synchronized void addOriginalName(String alias) {
        requireMutable();
        if (alias != null && !alias.isEmpty()) {
            originalNames.add(alias);
            if (originalName == null || originalName.isEmpty()) {
                originalName = alias;
            }
        }
    }
    public String getTypeName() {
        return typeName;
    }
    public String getCarrierTypeName() {
        return carrierTypeName == null || carrierTypeName.isEmpty() ? typeName : carrierTypeName;
    }
    public synchronized void setCarrierTypeName(String carrierTypeName) {
        requireMutable();
        this.carrierTypeName = carrierTypeName;
    }
    public String getDeBruijnKey() {
        return deBruijnKey == null ? name : deBruijnKey;
    }
    public synchronized void setDeBruijnKey(String deBruijnKey) {
        requireMutable();
        this.deBruijnKey = deBruijnKey;
    }
    public Quantifier getQuantifier() {
        return quantifier;
    }
    public synchronized void setQuantifier(Quantifier quantifier) {
        requireMutable();
        this.quantifier = quantifier == null ? Quantifier.SOME : quantifier;
    }
    public Cardinality getCardinality() {
        return cardinality == null ? Cardinality.SET : cardinality;
    }
    public synchronized void setCardinality(Cardinality cardinality) {
        requireMutable();
        this.cardinality = cardinality == null ? Cardinality.SET : cardinality;
    }
    public boolean isDisj() {
        return disjointnessClass > 0;
    }
    public synchronized void setDisj(boolean disj) {
        requireMutable();
        this.disjointnessClass = disj ? 1 : 0;
    }
    public int getDisjointnessClass() {
        return disjointnessClass;
    }
    public synchronized void setDisjointnessClass(int disjointnessClass) {
        requireMutable();
        this.disjointnessClass = Math.max(0, disjointnessClass);
    }
    public String getBindingPath() {
        return bindingPath == null ? "" : bindingPath;
    }
    public synchronized void setBindingPath(String bindingPath) {
        requireMutable();
        this.bindingPath = bindingPath == null ? "" : bindingPath;
    }
    public ExactAlloyType getExactAlloyType() {
        return exactAlloyType;
    }
    public synchronized void mergeExactAlloyType(ExactAlloyType evidence) {
        requireMutable();
        if (evidence == null) {
            return;
        }
        if (exactAlloyType == null) {
            exactAlloyType = evidence;
            return;
        }
        if (exactAlloyType.sameOccurrenceEvidenceAs(evidence)) {
            return;
        }
        if (exactAlloyType.equals(evidence)) {
            if (!exactAlloyType.hasParserAuthenticatedAncestry()
                    && evidence.hasParserAuthenticatedAncestry()) {
                exactAlloyType = evidence;
                return;
            }
            if (exactAlloyType.hasParserAuthenticatedAncestry()
                    && !evidence.hasParserAuthenticatedAncestry()) {
                return;
            }
        }
        throw new IllegalStateException(
                "One quantified slot received incompatible exact type evidence");
    }
    public synchronized void freezeForCertification() {
        frozenForCertification = true;
    }
    public boolean isFrozenForCertification() {
        return frozenForCertification;
    }
    private void requireMutable() {
        if (frozenForCertification) {
            throw new IllegalStateException(
                    "A certified quantified binding is immutable");
        }
    }
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof QuantiVar)) return false;
        QuantiVar qv = (QuantiVar) o;
        return this.id == qv.id;
    }
    public int hashCode() {
        return id;
    }
    public boolean sameType(QuantiVar qv) {
        return qv != null && java.util.Objects.equals(this.typeName, qv.typeName);
    }
    public String toString() {
        // in JSON form
        return "{\"id\": " + id + ", \"name\": \"" + name + "\", \"originalName\": \"" + originalName
                + "\", \"type\": \"" + typeName + "\", \"quantifier\": \"" + quantifier
                + "\", \"carrierType\": \"" + getCarrierTypeName()
                + "\", \"cardinality\": \"" + getCardinality()
                + "\", \"disj\": " + isDisj()
                + ", \"disjointnessClass\": " + getDisjointnessClass()
                + ", \"deBruijnKey\": \"" + getDeBruijnKey() + "\"}";
    }
}
