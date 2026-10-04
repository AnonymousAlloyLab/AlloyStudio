package is.fivefivefive.ACGN.alloy;
import is.fivefivefive.ACGN.asg.AugmentedNode;

public class LetSymbol extends RefSymbol {
    public LetSymbol(AugmentedNode n, String nm) {
        this(n, nm, "let/node-" + n.getSemantic());
    }

    public LetSymbol(AugmentedNode n, String nm, String lexicalIdentity) {
        super(n, nm, lexicalIdentity, null, -1, null);
        setType("Let");
    }
}
