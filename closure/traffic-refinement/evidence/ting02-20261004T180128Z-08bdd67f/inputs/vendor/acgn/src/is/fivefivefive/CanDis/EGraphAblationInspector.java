package is.fivefivefive.CanDis;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.adapter.AlloyAstTermAdapter;
import is.fivefivefive.CanDis.core.egraph.AblationEngine;
import is.fivefivefive.CanDis.core.egraph.AlloyTerm;
import is.fivefivefive.CanDis.core.egraph.DeBruijnVariables;
import is.fivefivefive.CanDis.core.egraph.JavaEgglog;
import is.fivefivefive.CanDis.core.egraph.JavaEgglogDeBruijn;
import is.fivefivefive.CanDis.core.egraph.RawDeBruijnEGraph;
import is.fivefivefive.CanDis.core.egraph.RawEGraph;
import is.fivefivefive.CanDis.core.egraph.SlottedEGraph;
import is.fivefivefive.alloyasg.etc.DoubleMap;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Predicate;
import parser.util.AlloyUtil;

/** Prints raw and alpha-canonical ablation terms for one dataset file. */
public final class EGraphAblationInspector {
    private EGraphAblationInspector() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: EGraphAblationInspector <model.als>");
        }
        Path file = Paths.get(args[0]);
        CompModule module = AlloyUtil.compileAlloyModule(file.toString());
        if (module == null) {
            throw new IllegalStateException("Alloy parser returned no module");
        }
        ModelUnit model = new ModelUnit(null, module);
        Map<String, Predicate> predicates = new HashMap<>();
        Map<String, Integer> predicateIds = new HashMap<>();
        int predicateId = 1;
        for (Predicate predicate : model.getPredDeclList()) {
            predicates.put(predicate.getName(), predicate);
            predicateIds.put(predicate.getName(), predicateId++);
        }
        String preferred = file.getFileName().toString();
        int dot = preferred.lastIndexOf('.');
        preferred = dot < 0 ? preferred : preferred.substring(0, dot);
        int underscore = preferred.lastIndexOf('_');
        preferred = underscore < 0 ? null : preferred.substring(underscore + 1);
        String[] names = DatasetConventions.findPredicatePairNames(preferred, predicates);
        if (names == null) {
            throw new IllegalStateException("No predicate pair found");
        }
        AlloyTerm[] terms = new AlloyTerm[names.length];
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            AlloyTerm raw = AlloyAstTermAdapter.fromPredicate(predicates.get(name));
            terms[i] = raw;
            System.out.println(name + " raw:\n" + raw);
            System.out.println(name + " De Bruijn:\n" + DeBruijnVariables.encode(raw));
            System.out.println(name + " alpha:\n" + SlottedEGraph.alphaRepresentative(raw));
            System.out.println(name + " egglog normal:\n" + JavaEgglog.normalForm(raw));
            System.out.println(name + " slotted normal:\n" + SlottedEGraph.normalForm(raw));
        }
        printDistance("raw e-graph", new RawEGraph().compare(terms[0], terms[1]));
        printDistance("raw e-graph + De Bruijn", new RawDeBruijnEGraph().compare(terms[0], terms[1]));
        printDistance("Java egglog", new JavaEgglog().compare(terms[0], terms[1]));
        printDistance("Java egglog + De Bruijn", new JavaEgglogDeBruijn().compare(terms[0], terms[1]));
        printDistance("slotted e-graph", new SlottedEGraph().compare(terms[0], terms[1]));
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(model, null);
        DoubleMap<Integer, Multigraph> forest = visitor.getForest();
        Canonical.Prepared left = Canonical.prepare(forest.get(predicateIds.get(names[0])));
        Canonical.Prepared right = Canonical.prepare(forest.get(predicateIds.get(names[1])));
        System.out.println("canonical distance: " + Canonical.distance(left, right));
        System.out.println("canonical edits: " + Canonical.edits(left, right));
        System.out.println(names[0] + " canonical IR:\n" + Canonical.irTemporalFol(left));
        System.out.println(names[1] + " canonical IR:\n" + Canonical.irTemporalFol(right));
        for (Command command : module.getAllCommands()) {
            if (!command.check) {
                continue;
            }
            A4Options options = new A4Options();
            options.solver = A4Options.SatSolver.SAT4J;
            A4Solution solution = TranslateAlloyToKodkod.execute_command(
                    new edu.mit.csail.sdg.alloy4.A4Reporter(), module.getAllReachableSigs(), command, options);
            System.out.println(command + " counterexample: " + (solution != null && solution.satisfiable()));
            break;
        }
    }

    private static void printDistance(String label, AblationEngine.Result result) {
        System.out.println(label + " minimum distance: " + result.distance
                + " (equivalent=" + result.equivalent + ")");
    }
}
