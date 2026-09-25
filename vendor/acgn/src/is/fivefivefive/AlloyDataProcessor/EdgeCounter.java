package is.fivefivefive.AlloyDataProcessor;
import is.fivefivefive.ACGN.alloy.DummySymbol;
import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.alloy.FieldRelation;
import is.fivefivefive.ACGN.alloy.LetSymbol;
import is.fivefivefive.ACGN.alloy.PredRootSymbol;
import is.fivefivefive.ACGN.alloy.RefSymbol;
import is.fivefivefive.ACGN.alloy.SigSymbol;
import is.fivefivefive.ACGN.alloy.SubsetRelation;
import is.fivefivefive.ACGN.alloy.Symbol;
import is.fivefivefive.ACGN.alloy.VarSymbol;
import is.fivefivefive.ACGN.asg.MASGEdge;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.alloyasg.etc.DoubleMap;
import is.fivefivefive.ACGN.etc.Triple;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import parser.ast.nodes.ModelUnit;
import parser.util.AlloyUtil;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.mit.csail.sdg.parser.CompModule;

public class EdgeCounter {
    private static GlobalVariables gv = new GlobalVariables();
    private static int modelCount = 0;
    private static String finalDebugMessage = "";
    public static Map<Triple<Symbol, Symbol, Integer>, Integer> countEdges(String dir) {
        File dirFile = new File(dir);
        if (!dirFile.isDirectory()) {
            System.out.println("The provided path is not a directory.");
            return null;
        }
        Map<Triple<Symbol, Symbol, Integer>, Integer> edgeCountMap = new HashMap<>();
        File[] files = dirFile.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".als")) {
                    try {
                        CompModule module = AlloyUtil.compileAlloyModule(dir + "/" + file.getName());
                        ModelUnit mu = new ModelUnit(null, module);
                        MASGVisitor visitor = new MASGVisitor(gv, module);
                        mu.accept(visitor, null);
                        for (int id : visitor.getForest().keys()) {
                            if (id == 0) continue; // Skip the root node
                            // if (id == 1) continue; // Skip the student-written solution for now, they have cringe errors
                            if (id > 2) continue; // Skip the checking predicates
                            Multigraph graph = visitor.getForest().get(id);
                            for (MASGEdge e : graph.getEdges()) {
                                Symbol source = getSymbolForPretrain(e.getSource().getSymbol());
                                if (e.getSource().getSymbol() instanceof CallSymbol) {
                                    // output the debug with the filename
                                    finalDebugMessage += "Debug: Found CALL in file " + file.getName() + " at model count " + modelCount + " for id " + id + "\n";
                                    finalDebugMessage += "Source node symbol: " + e.getSource().getSymbol().getName() + "\n";
                                    finalDebugMessage += "Target node symbol: " + e.getTarget().getSymbol().getName() + "\n";
                                    finalDebugMessage += "Edge position: " + e.getPosition() + "\n";
                                }
                                Symbol target = getSymbolForPretrain(e.getTarget().getSymbol());
                                int position = e.getPosition();
                                /* 
                                if (target == MASGVisitor.END_SYMBOL) {
                                    continue; // Skip edges to the end symbol
                                }
                                int position = e.getPosition();
                                if (position > 2 && (!source.getName().equals("ITE_EXPR") && !source.getName().equals("ITE_FORMULA"))) {
                                    position = 2; // commute
                                }*/
                                Triple<Symbol, Symbol, Integer> edge = new Triple<>(source, target, position);
                                edgeCountMap.put(edge, edgeCountMap.getOrDefault(edge, 0) + 1);
                            }
                        }
                        modelCount++;
                        System.out.println(modelCount + " models processed.");
                    } catch (Exception e) {
                        System.out.println("Error processing file: " + file.getName());
                        e.printStackTrace();
                        System.out.println("Count of models before this error: " + modelCount);
                        throw e;
                    }
                } else if (file.isDirectory()) {
                    // Recursively process subdirectories
                    Map<Triple<Symbol, Symbol, Integer>, Integer> subDirEdgeCount = countEdges(file.getAbsolutePath());
                    if (subDirEdgeCount != null) {
                        for (Map.Entry<Triple<Symbol, Symbol, Integer>, Integer> entry : subDirEdgeCount.entrySet()) {
                            edgeCountMap.put(entry.getKey(), edgeCountMap.getOrDefault(entry.getKey(), 0) + entry.getValue());
                        }
                    }
                }
            }
        }
        return edgeCountMap;
    }
    public static Symbol getSymbolForPretrain(Symbol original) {
        if (original instanceof CallSymbol) {
            return ((CallSymbol) original).semanticOperator();
        } else if (original instanceof VarSymbol && ((VarSymbol) original).isGlobal()) {
            return DummySymbol.DUMMY_GLOBAL_VAR;
        } else if (original instanceof VarSymbol && !((VarSymbol) original).isGlobal()) {
            return DummySymbol.DUMMY_LOCAL_VAR;
        } else if (original instanceof LetSymbol) {
            return DummySymbol.DUMMY_LET;
        } else if (original instanceof RefSymbol) {
            return DummySymbol.DUMMY_REF;
        } else if (original instanceof FieldRelation) {
            return DummySymbol.DUMMY_FIELD;
        } else if (original instanceof SigSymbol) {
            return DummySymbol.DUMMY_SIG;
        } else if (original instanceof SubsetRelation) {
            return DummySymbol.DUMMY_SUBSET;
        } else if (original instanceof PredRootSymbol) {
            return DummySymbol.DUMMY_PREDROOT;
        } else {
            return original;
        }
    }
    public static void main(String[] args) {
        String dir = "classified-data"; // Replace with your directory path
        Map<Triple<Symbol, Symbol, Integer>, Integer> edgeCountMap = countEdges(dir);
        // write GlobalVariables gv to a file
        System.out.println(gv.getUniqueNodes().size());
        GlobalVariables.writeToFile("global_variables.ser", gv);
        int uniqueNodeCount = 0;
        DoubleMap<Symbol, Integer> nodeId = new DoubleMap<>();
        String nodeIdFilePath = "node_id.csv";
        if (edgeCountMap != null) {
            // TODO:  combine the entries with the same key
            for (Map.Entry<Triple<Symbol, Symbol, Integer>, Integer> entry : edgeCountMap.entrySet()) {
                
                // write the data to a file
                Triple<Symbol, Symbol, Integer> edge = entry.getKey();
                int count = entry.getValue();
                System.out.println("Edge: " + edge.x + " -> " + edge.y + " at position " + edge.z + " Count: " + count);
                // write the data to a file， generate the CSV
                if (!nodeId.containsKey(edge.x)) {
                    nodeId.put(edge.x, uniqueNodeCount);
                    String csvLine = uniqueNodeCount + "," + edge.x.getName();
                    try (java.io.FileWriter writer = new java.io.FileWriter(nodeIdFilePath, true)) {
                        writer.write(csvLine + "\n");
                    } catch (java.io.IOException e) {
                        System.out.println("Error writing to CSV file: " + e.getMessage());
                    }
                    uniqueNodeCount++;
                }
                if (!nodeId.containsKey(edge.y)) {
                    nodeId.put(edge.y, uniqueNodeCount);
                    String csvLine = uniqueNodeCount + "," + edge.y.getName();
                    try (java.io.FileWriter writer = new java.io.FileWriter(nodeIdFilePath, true)) {
                        writer.write(csvLine + "\n");
                    } catch (java.io.IOException e) {
                        System.out.println("Error writing to CSV file: " + e.getMessage());
                    }
                    uniqueNodeCount++;
                }
                String x = edge.x.getName();
                int xId = nodeId.get(edge.x);
                String y = edge.y.getName();
                int yId = nodeId.get(edge.y);
                int z = edge.z;
                String csvLine = x + "," + xId + "," + y + "," + yId + "," + z + "," + count;
                String pureCsvLine = xId + "," + yId + "," + z + "," + count;
                String csvFilePath = "edge_counts_dirty_indiced_wshadow.csv"; // Replace with your desired CSV file path
                String pureCsvFilePath = "edge_counts.csv";
                try (java.io.FileWriter writer = new java.io.FileWriter(csvFilePath, true)) {
                    writer.write(csvLine + "\n");
                } catch (java.io.IOException e) {
                    System.out.println("Error writing to CSV file: " + e.getMessage());
                }
                try (java.io.FileWriter writer = new java.io.FileWriter(pureCsvFilePath, true)) {
                    writer.write(pureCsvLine + "\n");
                } catch (java.io.IOException e) {
                    System.out.println("Error writing to CSV file: " + e.getMessage());
                }
                
            }
        } else {
            System.out.println("No edges found.");
        }
        System.out.println(finalDebugMessage);
    }
    public static Map<String, Float> generateNodeSignatureMap(String nodeIdCsvPath, String signatureCsvPath) {
        Map<Integer, String> idToSymbol = new HashMap<>();
        Map<Integer, Float> idToSignature = new HashMap<>();
        Map<String, Float> symbolToSignature = new HashMap<>();

        // Read nodeIdCsvPath: id,symbolName
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(nodeIdCsvPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    int id = Integer.parseInt(parts[0].trim());
                    String symbolName = parts[1].trim();
                    idToSymbol.put(id, symbolName);
                }
            }
        } catch (java.io.IOException e) {
            System.out.println("Error reading node ID CSV: " + e.getMessage());
        }

        // Read signatureCsvPath: id,signature
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(signatureCsvPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    int id = Integer.parseInt(parts[0].trim());
                    float signature = Float.parseFloat(parts[1].trim());
                    idToSignature.put(id, signature);
                }
            }
        } catch (java.io.IOException e) {
            System.out.println("Error reading signature CSV: " + e.getMessage());
        }

        // Combine maps
        for (Map.Entry<Integer, String> entry : idToSymbol.entrySet()) {
            int id = entry.getKey();
            String symbolName = entry.getValue();
            if (idToSignature.containsKey(id)) {
                symbolToSignature.put(symbolName, idToSignature.get(id));
            }
        }
        // writeout to (id, symbolName, signature) to a new CSV
        String outputCsvPath = "node_signatures.csv";
        try (java.io.FileWriter writer = new java.io.FileWriter(outputCsvPath)) {
            writer.write("id,symbolName,signature\n");
            for (Map.Entry<Integer, String> entry : idToSymbol.entrySet()) {
                int id = entry.getKey();
                String symbolName = entry.getValue();
                float signature = idToSignature.getOrDefault(id, 0.0f);
                String csvLine = id + "," + symbolName + "," + signature;
                writer.write(csvLine + "\n");
            }
        } catch (java.io.IOException e) {
            System.out.println("Error writing to output CSV: " + e.getMessage());
        }
        return symbolToSignature;
    }
}
