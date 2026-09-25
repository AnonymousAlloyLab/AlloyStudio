package is.fivefivefive.AlloyDataProcessor;

import java.io.File;
import java.util.List;

import edu.mit.csail.sdg.parser.CompModule;
import is.fivefivefive.ACGN.learn.Rewarder;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;
import parser.ast.nodes.Predicate;
import parser.ast.visitor.ASTNodeFinder;

public class CommandScript {
    public static void main(String[] args) {
        addAllCommands("classified-data");
    }
    private static void addAllCommands(String path) {
        File dataFolder = new File(path);
        File[] fList = dataFolder.listFiles();
        for (File f: fList) {
            if (f.isFile()) {
                String absPath = f.getAbsolutePath();
                String groundTruthPredName = getGroundTruthPredName(absPath);
                if (groundTruthPredName == null || groundTruthPredName.isEmpty()) {
                    System.out.println("No ground truth predicate found in " + absPath);
                    continue;
                }
                Rewarder.putCommandsInTheEnd(absPath, groundTruthPredName);
            } else {
                addAllCommands(f.getAbsolutePath());
            }
        }
    }
    private static String getGroundTruthPredName(String path) {
        CompModule cm = parser.util.AlloyUtil.compileAlloyModule(path);
        ModelUnit mu = new ModelUnit(null, cm);
        List<Node> nodes = ASTNodeFinder.findNodesByTypeAndName(mu, Predicate.class, "inv", false);
        nodes.addAll(ASTNodeFinder.findNodesByTypeAndName(mu, Predicate.class, "Inv", false));
        nodes.addAll(ASTNodeFinder.findNodesByTypeAndName(mu, Predicate.class, "prop", false));
        if (nodes.isEmpty()) {
            throw new RuntimeException("No predicate found with the name 'inv', 'Inv', or 'prop'.");
        }
        if (nodes.size() > 2) {
            System.out.println("Warning: More than 2 predicates found with the name 'inv', 'Inv', or 'prop'. Using the first one.");
        }
        Node groundTruthNode = nodes.get(1);
        Predicate groundTruth = (Predicate) groundTruthNode;
        String groundTruthPredName = groundTruth.getName();
        return groundTruthPredName;
    }
}
