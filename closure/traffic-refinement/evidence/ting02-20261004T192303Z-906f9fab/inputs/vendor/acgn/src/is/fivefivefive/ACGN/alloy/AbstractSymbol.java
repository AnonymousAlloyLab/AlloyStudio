package is.fivefivefive.ACGN.alloy;

import java.io.Serializable;

public abstract class AbstractSymbol implements Symbol, Serializable {
    private double signature;
    public AbstractSymbol() {}
    public String getName() {
        return "<UNDEFINED>";
    }
    public String getType() {
        return "SYMBOL_UNDEFINED";
    }
    public boolean isEndSymbol() {
        return false;
    }
    public double getSignature() {
        return signature;
    }
    public void setSignature(double sig) {
        signature = sig;
    }
}
