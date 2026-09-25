package is.fivefivefive.ACGN.asg;
// import com.abdulfatir.jcomplexnumber.ComplexNumber;

import java.io.Serializable;

import is.fivefivefive.ACGN.structure.RLScopeTreeNode;

/*
 * An edge in the naive, intermediate Multi-ASG. 
 */
public class MASGEdge implements Serializable {
    private int position;
    private int timeOfVisit;
    private AugmentedNode source;
    private AugmentedNode target;
    private RLScopeTreeNode scope; // only used in RL
    private int depthInNode; // only used in RL, the depth of the edge in the current node, used for reward backpropagation
    public MASGEdge(AugmentedNode source, AugmentedNode target, int position, int timeOfVisit) {
        this.position = position;
        this.timeOfVisit = timeOfVisit;
        this.source = source;
        this.target = target;
    }
    public MASGEdge(AugmentedNode source, AugmentedNode target, int position, int timeOfVisit, RLScopeTreeNode scope, int depthInNode) {
        this.position = position;
        this.timeOfVisit = timeOfVisit;
        this.source = source;
        this.target = target;
        this.scope = scope;
        this.depthInNode = depthInNode;
    }
    public int getPosition() {
        return position;
    }
    public int getTimeOfVisit() {
        return timeOfVisit;
    }
    public AugmentedNode getSource() {
        return source;
    }
    public AugmentedNode getTarget() {
        return target;
    }
    public double getSignatureDiff() {
        return target.getSignature() - source.getSignature();
    }
    public int logEdgeLength() {
        return position * (timeOfVisit - 1);
    }
    public RLScopeTreeNode getScope() {
        return scope;
    }
    public void setScope(RLScopeTreeNode scope) {
        this.scope = scope;
    }
    public int getDepthInNode() {
        return depthInNode;
    }
    @Override
    public boolean equals(Object o) {
        if (o instanceof MASGEdge) {
            MASGEdge e = (MASGEdge) o;
            return e.getSource().equals(source) && e.getTarget().equals(target) && e.getPosition() == position && e.getTimeOfVisit() == timeOfVisit;
        }
        return false;
    }
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("MASGEdge: ");
        sb.append("Position: ").append(position).append(", ");
        sb.append("Source: ").append(source.getSyntactic()).append(", ").append(source.getSemantic()).append(", ");
        sb.append("Target: ").append(target.getSyntactic()).append(", ").append(target.getSemantic()).append(", ");
        sb.append("Time of Visit: ").append(timeOfVisit);
        return sb.toString();
    }
}
