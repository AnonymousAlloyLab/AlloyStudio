package is.fivefivefive.ACGN.learn;

import java.util.List;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.ErrorWarning;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.Expr;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.alloyasg.etc.DoubleMap;
import is.fivefivefive.ACGN.util.InstancePool;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;
import parser.ast.nodes.Predicate;
import parser.etc.Pair;

/**
 * The Rewarder class is the utility file for all the processing of the Alloy model files until computing the RL reward.
 * It uses the MASGVisitor to traverse the model and extract the relevant graphs.
 * This class is part of the ACGN (Alloy Code Generation Network) project.
 */
// TODO: Implement LFU Cache for the pool of instances.
public class Rewarder {
    /**
     * Compiles an Alloy model from a file and returns the CompModule representation.
     * This method is used to parse the Alloy model and create a CompModule object that can be used for further processing.
     * @param file The path to the Alloy model file.
     * @return A CompModule representing the parsed Alloy model.
     */
    public static CompModule fromFile(String file) {
		String file_name = file;
		 A4Reporter rep = new A4Reporter() {
            @Override
            public void warning(ErrorWarning msg) {
                System.out.println(msg.toString().trim());
                System.out.flush();
            }
	    };
        CompModule world = CompUtil.parseEverything_fromFile(rep, null, file_name);
        return world;
    }

    /*
     * Returns a list of Multigraphs representing the predicate graph for the given predicate name.
     * The predicate graph is a forest of graphs, where each graph corresponds to a predicate with the given name.
     * @param cm The CompModule containing the predicate.
     * @param name The name of the predicate to find.
     * @return A list of Multigraphs representing the predicate graph.
     * Throws IllegalArgumentException if no predicate with the given name is found.
     * Throws IllegalArgumentException if the node found is not a Predicate.
     * Throws IllegalArgumentException if no graph is found for the predicate.
     */
    public static DoubleMap<Integer, Multigraph> predicateGraph(CompModule cm, String name) {
        MASGVisitor visitor = new MASGVisitor(cm);
        ModelUnit mu = new ModelUnit(null, cm);
        List<Node> roots = parser.ast.visitor.ASTNodeFinder.findNodesByTypeAndName(mu, Predicate.class, name, false);
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("No predicate found with name: " + name);
        }
        visitor.visit(mu, null);
        DoubleMap<Integer, Multigraph> forest = visitor.getForest();
        DoubleMap<Integer, Multigraph> graphs = new DoubleMap<>();
        // get the forest of graphs by the roots
        for (Node root : roots) {
            if (!(root instanceof Predicate)) {
                throw new IllegalArgumentException("Node is not a Predicate: " + root);
            }
            Predicate predicate = (Predicate) root;
            String rootName = predicate.getName();
            for (int i : forest.keys()) {
                Multigraph graph = forest.get(i);
                if (graph.getRoot().getSymbol().getName().equals(rootName)) {
                    graphs.put(i, graph);
                }
            }
        }
        return graphs;
    }

    /**
     * Adds commands to check the satisfiability of a predicate in the Alloy model.
     * This method generates the commands to check both the predicate and its negation.
     * @param predName The name of the predicate to check.
     * @return A string containing the commands to be added to the Alloy model file.
     */
    private static String addCommands(String predName) {
        StringBuilder sb = new StringBuilder();
        sb.append("run ").append(predName).append("\n");
        sb.append("run {!").append(predName).append("}\n");
        return sb.toString();
    }

    /**
     * Appends commands to the end of the Alloy model file.
     * This method is used to add commands for checking the satisfiability of a predicate.
     * @param modelPath The path to the Alloy model file.
     * @param predName The name of the predicate to check.
     */
    public static void putCommandsInTheEnd(String modelPath, String predName) {
        String commands = addCommands(predName);
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get(modelPath), commands.getBytes(), java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Retrieves instances of a predicate from the Alloy model.
     * This method executes the commands to check the satisfiability of the predicate and its negation,
     * returning the positive and negative instances as a Pair.
     * This is the starting points of a pool of instances that could be used for reward calculation in reinforcement learning.
     * @param cm The CompModule containing the Alloy model.
     * @param predName The name of the predicate to retrieve instances for.
     * @return A Pair containing the positive and negative instances of the predicate.
     * @throws IllegalArgumentException if no predicate with the given name is found or if no satisfiable instance is found.
     */
    public static Pair<InstancePool, InstancePool> instances(CompModule cm, String predName, int poolSize) {
        A4Reporter rep = new A4Reporter() {
            @Override
            public void warning(ErrorWarning msg) {
                System.out.println(msg.toString().trim());
                System.out.flush();
            }
        };
        String oracleExpression = asAlloyExpression(predName);
        Expr positiveExpression = CompUtil.parseOneExpression_fromString(cm, oracleExpression);
        Expr negativeExpression = CompUtil.parseOneExpression_fromString(cm, "!(" + oracleExpression + ")");
        Command commandPositive = new Command(
                true,
                Hyperparams.SCOPE,
                Hyperparams.SCOPE,
                Hyperparams.SCOPE,
                positiveExpression);
        Command commandNegative = new Command(
                true,
                Hyperparams.SCOPE,
                Hyperparams.SCOPE,
                Hyperparams.SCOPE,
                negativeExpression);
        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
        A4Solution posInstance = TranslateAlloyToKodkod.execute_command(rep, cm.getAllReachableSigs(), commandPositive, options);
        A4Solution negInstance = TranslateAlloyToKodkod.execute_command(rep, cm.getAllReachableSigs(), commandNegative, options);
        if (posInstance == null || negInstance == null) {
            throw new IllegalArgumentException("Trivial predicate: " + predName);
        }
        if (!posInstance.satisfiable() || !negInstance.satisfiable()) {
            throw new IllegalArgumentException("No satisfiable instance found for predicate: " + predName);
        }
        // Create instance pools for positive and negative instances
        InstancePool posInstancePool = new InstancePool(poolSize);
        InstancePool negInstancePool = new InstancePool(poolSize);
        // Add the first instances to the pools
        posInstancePool.add(posInstance);
        for (int i = 1; i < poolSize; i++) {
            posInstance = posInstance.next();
            if (posInstance == null || !posInstance.satisfiable()) {
                break;
            }
            posInstancePool.add(posInstance);
        }
        negInstancePool.add(negInstance);
        for (int i = 1; i < poolSize; i++) {
            negInstance = negInstance.next();
            if (negInstance == null || !negInstance.satisfiable()) {
                break;
            }
            negInstancePool.add(negInstance);
        }
        // Return the instances as a Pair
        return Pair.of(posInstancePool, negInstancePool);
    }

    // compute the reward based on the instances of the predicate
    /**
     * Computes the reward for a given predicate based on positive and negative instances.
     * This method evaluates the predicate on a pool of instances and calculates the reward based on the
     * number of positive and negative instances that satisfy the predicate.
     * @param cm The CompModule containing the Alloy model.
     * @param instances A Pair containing the positive and negative instances of the predicate.
     * @param originalPredName The name of the ground truth predicate to evaluate.
     * @param newPredName The name of the predicate to evaluate.
     * @param poolSize The size of the pool of instances to evaluate.
     * @return The computed reward as a double value.
     * Throws IllegalArgumentException if the instances are null or if the predicate cannot be evaluated.
     */
    public static double computeReward(CompModule cm, Pair<InstancePool, InstancePool> instances, String originalPredName, String newPredName, String newPredBody, int poolSize) {
        if (cm == null || instances == null || originalPredName == null || newPredName == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }
        if (poolSize <= 0) {
            throw new IllegalArgumentException("Pool size must be greater than zero");
        }
        // Get the positive and negative instances from the Pair
        InstancePool posInstances = instances.a;
        InstancePool negInstances = instances.b;
        if (posInstances == null || negInstances == null) {
            throw new IllegalArgumentException("Instance pools cannot be null");
        }
        // Get the first instances from the pools
        A4Solution posInstance = posInstances.getHead();
        A4Solution negInstance = negInstances.getHead();
        if (posInstance == null || negInstance == null) {
            throw new IllegalArgumentException("No instances found in the pools");
        }
        String candidateExpression = asAlloyExpression(newPredBody);
        String originalExpression = asAlloyExpression(originalPredName);
        int posIter = 0;
        int posCount = 0;
        while (posIter < poolSize && posInstance.satisfiable()) {
            boolean result = false;
            try {
                result = (boolean) posInstance.eval(CompUtil.parseOneExpression_fromString(cm, candidateExpression));
            } catch (Exception e) {
                System.err.println("[REWARDER] Exception in " + newPredName);
                System.err.println("Error evaluating predicate: " + e.getMessage());                
                return 0.0; // return zero reward if the predicate cannot be evaluated
            }
            
            if (result) {
                posCount++;
            } else {
                // If the instance does not satisfy the new predicate, increment its usage frequency
                posInstances.incrementUsageFrequency(posInstance);
            }
            posInstance = posInstance.next(); // get next instance
            posIter++;
        }
        // similarly for negative instances
        int negIter = 0;
        int negCount = 0;
        while (negIter < poolSize && negInstance.satisfiable()) {
            boolean result = (boolean) negInstance.eval(CompUtil.parseOneExpression_fromString(cm, candidateExpression));
            if (!result) {
                negCount++;
            } else {
                // If the instance does not satisfy the new predicate, increment its usage frequency
                negInstances.incrementUsageFrequency(negInstance);
            }
            negInstance = negInstance.next(); // get next instance
            negIter++;
        }
        int semanticCounterexamples = 0;
        if (posCount == posIter && negCount == negIter) {
            // All evaluated instances are correctly classified. Ask Alloy for
            // bounded semantic counterexamples and refresh the LFU pools with them.
            // check overcoverage
            String satCommandText1 = '(' + candidateExpression + ") && !(" + originalExpression + ")";
            // check undercoverage
            String satCommandText2 = "!(" + candidateExpression + ") && (" + originalExpression + ")";
            A4Reporter rep = new A4Reporter() {
                @Override
                public void warning(ErrorWarning msg) {
                    System.out.println(msg.toString().trim());
                    System.out.flush();
                }
            };

            A4Options options = new A4Options();
            options.solver = A4Options.SatSolver.SAT4J;
            Expr satCommand1 = CompUtil.parseOneExpression_fromString(cm, satCommandText1);
            Expr satCommand2 = CompUtil.parseOneExpression_fromString(cm, satCommandText2);
            // cm.addGlobal("l", CompUtil.parseOneExpression_fromString(cm, "List"));
            Command cmd1 = new Command(true, Hyperparams.SCOPE, Hyperparams.SCOPE, Hyperparams.SCOPE, satCommand1);
            Command cmd2 = new Command(true, Hyperparams.SCOPE, Hyperparams.SCOPE, Hyperparams.SCOPE, satCommand2);
            A4Solution satSolution1 = TranslateAlloyToKodkod.execute_command(rep, cm.getAllReachableSigs(), cmd1, options);
            A4Solution satSolution2 = TranslateAlloyToKodkod.execute_command(rep, cm.getAllReachableSigs(), cmd2, options);
            boolean noOvercoverage = satSolution1 == null || !satSolution1.satisfiable();
            boolean noUndercoverage = satSolution2 == null || !satSolution2.satisfiable();
            if (noOvercoverage && noUndercoverage) {
                return 1.0; // perfect coverage, no overcoverage or undercoverage
            }
            if (satSolution1 != null && satSolution1.satisfiable()) {
                // Overcoverage: candidate accepts an oracle-negative instance.
                negInstances.add(satSolution1);
                semanticCounterexamples++;
            }
            if (satSolution2 != null && satSolution2.satisfiable()) {
                // Undercoverage: candidate rejects an oracle-positive instance.
                posInstances.add(satSolution2);
                semanticCounterexamples++;
            }
        }
        // Calculate the reward based on the counts of positive and negative instances
        if (posIter == 0 || negIter == 0) {
            return 0.0;
        }
        double reward = (double) (posCount * negCount) / (double) (posIter * negIter + semanticCounterexamples);
        return reward;
    }

    private static String asAlloyExpression(String expression) {
        if (expression != null && expression.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return expression + "[]";
        }
        return expression;
    }
}
