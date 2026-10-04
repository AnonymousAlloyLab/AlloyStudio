package is.fivefivefive.ACGN.test;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.List;

import edu.mit.csail.sdg.parser.CompModule;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.codegen.Generator;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.alloyasg.etc.DoubleMap;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;
import parser.ast.nodes.Predicate;
import parser.ast.visitor.ASTNodeFinder;
import parser.ast.visitor.PrettyStringVisitor;
import parser.util.AlloyUtil;

public class Playground {

    public static final boolean DEBUG = false;
    public static void main(String[] args) throws FileNotFoundException {
        PrintStream p = new PrintStream(new File("output.log"));
        System.setOut(p);
        String file = "dynamic_ball_graph.als";
        CompModule module = AlloyUtil.compileAlloyModule(file);
        ModelUnit mu = new ModelUnit(null, module);
        List<Node> root = ASTNodeFinder.findNodesByTypeAndName(mu, Predicate.class, "moved", false);
        Predicate rootMoved = (Predicate) root.get(0);
        GlobalVariables gv = new GlobalVariables();
        MASGVisitor visitor = new MASGVisitor(gv, module);
        visitor.visit(mu, null);
        System.out.println("Finished visiting the model unit.");
        DoubleMap<Integer, Multigraph> map = visitor.getForest();
        /*for (int i : map.keys()) {
            System.out.println("Graph " + i + ":");
            Multigraph graph = map.get(i);
            System.out.println(graph);
        }*/
        Generator generator = new Generator();
        int graphId = 5;
        System.out.println(map.get(graphId).getRoot().getSyntactic() + " " + map.get(graphId).getRoot().getSemantic());
        String code3 = generator.toCode(map.get(graphId), map.get(graphId).getRoot(), 1, null);
        System.out.println("Generated code for graph " + graphId + ": ");
        System.out.println(code3);
        PrettyStringVisitor psv = new PrettyStringVisitor();
        String str = psv.visit(mu, null);
        System.out.println("Pretty String of the model unit: ");
        System.out.println(str);
        System.out.println("Done with Playground.");

    }


}
