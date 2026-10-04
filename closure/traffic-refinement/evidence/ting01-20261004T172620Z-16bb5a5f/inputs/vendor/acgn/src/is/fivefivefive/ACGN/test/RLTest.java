package is.fivefivefive.ACGN.test;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.List;

import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.Expr;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.alloy.Symbol;
import is.fivefivefive.ACGN.asg.AugmentedNode;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.etc.BiMap;
import is.fivefivefive.ACGN.exceptions.ExceedMaxStepException;
import is.fivefivefive.ACGN.exceptions.ScopeNotReadyException;
import is.fivefivefive.ACGN.learn.CodeGenAgent;
import is.fivefivefive.ACGN.learn.Hyperparams;
import is.fivefivefive.ACGN.learn.RLAgentFrame;
import is.fivefivefive.ACGN.learn.Rewarder;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.util.InstancePool;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.alloyasg.etc.DoubleMap;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;
import parser.ast.nodes.Predicate;
import parser.ast.visitor.ASTNodeFinder;
import parser.etc.Pair;

// TODO: GENERATION RULE: NO <SHADOW> TOKENS UNDER THE ROOT. 
public class RLTest {
    public static final boolean DEBUG = false;
    private static PrintStream rewardStream;
    private static PrintStream outputStream;
    private static PrintStream errorStream;
    private static PrintStream codeOutputStream;

    static {
        try {
            rewardStream = new PrintStream(new File("reward_rl.log"));
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Unable to create reward_rl.log", e);
        }
        try {
            outputStream = new PrintStream(new File("output_rl.log"));
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Unable to create output_rl.log", e);
        }
        // outputStream = System.out;
        try {
            errorStream = new PrintStream(new File("error_rl.log"));
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Unable to create error_rl.log", e);
        }
        try {
            codeOutputStream = new PrintStream(new File("code_rl.log"));
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Unable to create code_rl.log", e);
        }
    }

    private static Pair<Boolean, Double> learn(GlobalVariables gv, String path, int maxSteps) throws FileNotFoundException, ScopeNotReadyException, ExceedMaxStepException {
        CompModule cm = Rewarder.fromFile(path);
        if (cm == null) {
            System.out.println("Failed to compile Alloy module at " + path);
            return Pair.of(false, 0.0);
        }
        ModelUnit mu = new ModelUnit(null, cm);
        List<Node> predicates = ASTNodeFinder.findNodesByTypeAndName(mu, Predicate.class, "inv", false);
        predicates.addAll(ASTNodeFinder.findNodesByTypeAndName(mu, Predicate.class, "Inv", false));
        predicates.addAll(ASTNodeFinder.findNodesByTypeAndName(mu, Predicate.class, "prop", false));
        if (predicates.isEmpty()) {
            System.out.println("No predicate found with the name 'inv', 'Inv', or 'prop'.");
            return Pair.of(false, 0.0);
        }
        if (predicates.size() > 2) {
            System.out.println("Warning: More than 2 predicates found with the name 'inv', 'Inv', or 'prop'. Using the first pair.");
        }
        Node groundTruthNode = predicates.get(1);
        Node studentSolutionNode = predicates.get(0);
        Predicate groundTruth = (Predicate) groundTruthNode;
        Predicate studentSolution = (Predicate) studentSolutionNode;
        MASGVisitor visitor = new MASGVisitor(cm);
        visitor.visit(mu, null);
        Multigraph groundTruthGraph = visitor.getForest().get(2);
        Multigraph studentSolutionGraph = visitor.getForest().get(1);
        AugmentedNode groundTruthRoot = groundTruthGraph.getRoot();
        AugmentedNode studentSolutionRoot = studentSolutionGraph.getRoot();
        if (groundTruthRoot == null || studentSolutionRoot == null) {
            System.out.println("Failed to create AugmentedNode for ground truth or student solution.");
            throw new RuntimeException("AugmentedNode creation failed.");
        }
        // unique nodes? gv? 
        BiMap<Symbol, AugmentedNode> uniqueNodes = gv.getUniqueNodes();
        System.out.println(uniqueNodes);
        gv.addCustomUniqueNodes(visitor.getUniqueNode());
        
        // System.out.println(uniqueNodes.rget(groundTruthGraph.getRoot()).getName());

        CodeGenAgent agent = new CodeGenAgent(groundTruthGraph, visitor, gv);
        agent.initialize();
        // begin RL
        Pair<InstancePool, InstancePool> instancePoolPair = Rewarder.instances(cm, groundTruth.getName(), Hyperparams.POOL_SIZE);
        double maxReward = 0;
        for (int i = 0; i < maxSteps; ++i) {
            String name = "invX" + i;
            String nextPredCode = null;
            try {
                nextPredCode = agent.generateNextPred(name);
            } catch (Exception e) {
                System.out.println("Error generating next predicate: " + e.getMessage());
                System.out.println("in file " + path);
                throw e;
            }
            // add the new predicate into the Alloy API
            // Expr newPred = CompUtil.parseOneExpression_fromString(cm, nextPredCode);
            double reward = Rewarder.computeReward(cm, instancePoolPair, groundTruth.getName(), name, nextPredCode, Hyperparams.POOL_SIZE);
            if (reward > maxReward) {
                maxReward = reward;
                System.setOut(codeOutputStream);
                System.out.println("New Best: Code for step " + i + "with reward " + reward + " in file " + path + ": ");
                System.out.println(nextPredCode);
                System.out.println("--------------------------------");
                System.setOut(outputStream);
            }
            System.setOut(rewardStream);
            System.out.println("Step " + i + ": reward = " + reward + ", maxReward = " + maxReward);
            System.out.println("Code for step " + i + ": \n" + nextPredCode);
            System.setOut(outputStream);
            if (reward == 1) {
                System.out.println("Successfully learned the predicate: ");
                System.out.println(nextPredCode);
                return Pair.of(true, i + 1.0);
            } else {
                try {
                    agent.backpropagate((float) reward);
                } catch (Exception e) {
                    System.out.println("Error during backpropagation: " + e.getMessage());
                    System.out.println("in file " + path);
                    throw e;
                }
            }
        }
        System.out.println("Failed to learn the predicate within the maximum steps.");
        return Pair.of(false, maxReward);
    }

    public static void main(String[] args) throws FileNotFoundException, ScopeNotReadyException, ExceedMaxStepException {
        System.setOut(outputStream);
        System.setErr(errorStream);
        GlobalVariables gv = GlobalVariables.readFromFile("global_variables.ser");
        if (gv == null) {
            System.out.println("Failed to load global variables.");
            return;
        }
        gv.loadPretrainedSignatures("node_id.csv", "node_signatures_fixed.csv");
        final int CPU_THREADS = Runtime.getRuntime().availableProcessors();
        // parallelize by files
        String path = "classified-data";
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            System.out.println("Invalid directory: " + path);
            return;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            System.out.println("No files found in the directory: " + path);
            return;
        }
        // PARALLELIZE
        for (File file : files) {
            // first layer are still directories
            if (file.isDirectory()) {
                System.out.println("Processing directory: " + file.getName());
                String subDirPath = file.getAbsolutePath();
                File[] subFiles = new File(subDirPath).listFiles();
                
                if (subFiles != null && subFiles.length > 0) {
                    for (File subFile : subFiles) {
                        String dirName = subFile.getName();
                        File[] subsubFiles = subFile.listFiles();
                        for (File subsubFile : subsubFiles) {
                            if (subsubFile.isDirectory()) {
                                System.out.println("Processing subdirectory: " + subsubFile.getName());
                            } else if (subsubFile.isFile() && subsubFile.getName().endsWith(".als")) {
                                // add a new thread for each file
                                // new Thread(new RLWorker(gv, subsubFile.getAbsolutePath())).start();
                                try {
                                    Pair<Boolean, Double> result = learn(gv, subsubFile.getAbsolutePath(), 1000);
                                    if (result.a) {
                                        System.setOut(rewardStream);
                                        System.out.println("Successfully learned from " + subsubFile.getAbsolutePath() + " in " + result.b + " steps.");
                                        System.setOut(outputStream);
                                    } else {
                                        System.setOut(rewardStream);
                                        System.out.println("Failed to learn from " + subsubFile.getAbsolutePath() + ". Max reward: " + result.b);
                                        System.setOut(outputStream);
                                    }
                                } catch (FileNotFoundException e) {
                                    System.out.println("File not found: " + subsubFile.getAbsolutePath());
                                }
                            }
                        }
                    }
                } else {
                    System.out.println("No .als files found in the directory: " + subDirPath
                            + ". Skipping this directory.");
                }
            }
        }
        System.out.println("All files processed.");
    }
    private static class RLWorker implements Runnable {
        private GlobalVariables gv;
        private String path;

        public RLWorker(GlobalVariables gv, String path) {
            this.gv = gv;
            this.path = path;
        }

        @Override
        public void run() {
            try {
                Pair<Boolean, Double> result = learn(gv, path, 1000);
                if (result.a) {
                    System.setOut(rewardStream);
                    System.out.println("Successfully learned from " + path + " in " + result.b + " steps.");
                    System.setOut(outputStream);
                } else {
                    System.setOut(rewardStream);
                    System.out.println("Failed to learn from " + path + ". Max reward: " + result.b);
                    System.setOut(outputStream);
                }
            } catch (FileNotFoundException e) {
                System.out.println("File not found: " + path);
            } catch (ScopeNotReadyException e) {
                System.out.println("Scope not ready: " + path);
            } catch (ExceedMaxStepException e) {
                System.out.println("Exceeded max steps during learning: " + path);
            }
        }
    }
}
