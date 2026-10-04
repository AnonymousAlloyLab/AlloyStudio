package is.fivefivefive.ACGN.test;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import is.fivefivefive.ACGN.learn.Hyperparams;

// TODO : FIX ONE NODE FOR THE BASELINE.
public class SGDEdgeTrainer {
    // check each time before training
    // public static final int END_SYMBOL_ID = 28;
    private static int endId = -1; // Will be set after loading edges
    private static void getEndSymbolId() {
        // open "node_id.csv" and find the ID for "<END>"
        try (BufferedReader br = new BufferedReader(new FileReader("node_id.csv"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length == 2 && parts[1].trim().equals("<END>")) {
                    endId = Integer.parseInt(parts[0].trim());
                    System.out.println("Detected <END> symbol ID: " + endId);
                    return;
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading node_id.csv: " + e.getMessage());
        }
        if (endId == -1) {
            System.err.println("Warning: <END> symbol ID not found in node_id.csv. Defaulting to 28.");
            endId = 0; // Default fallback
        }
    }
    static class Edge {
        int source, target, position, count;
        Edge(int s, int t, int p, int c) {
            source = s;
            target = t;
            position = p;
            count = c;
        }
    }

    static class Embeddings {
        float[] angle;
        
        Embeddings(int numNodes, Random rand) {
            angle = new float[numNodes];
            for (int i = 0; i < numNodes; i++) {
                angle[i] = i == endId ? 0f : (float) ((rand.nextFloat() * 2 - 1) * Math.PI); // angle in [-pi, pi]
            }
        }

        float projectionScore(int i, int j) {
            /*
            float cosI = (float) Math.cos(angle[i]);
            float sinI = (float) Math.sin(angle[i]);
            float cosJ = (float) Math.cos(angle[j]);
            float sinJ = (float) Math.sin(angle[j]);
            return cosJ * cosI + sinJ * sinI; // Re(e^{iθ_j} * e^{-iθ_i}) = cos(θ_j − θ_i)*/
            return angle[j] - angle[i]; // Simplified for demonstration
        }

        void saveToCSV(String filename) throws IOException {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename))) {
                for (int i = 0; i < angle.length; i++) {
                    writer.write(i + "," + angle[i]);
                    writer.newLine();
                }
            }
        }
    }

    static class SGDWorker implements Runnable {
        List<Edge> batch;
        Embeddings emb;
        float lr, T;
        int numNodes;
        float[] lossAccumulator;

        SGDWorker(List<Edge> batch, Embeddings emb, float lr, float T, int numNodes, float[] lossAccumulator) {
            this.batch = batch;
            this.emb = emb;
            this.lr = lr;
            this.T = T;
            this.numNodes = numNodes;
            this.lossAccumulator = lossAccumulator;
        }

        public void run() {
            float localLoss = 0f;
            for (Edge e : batch) {
                int i = e.source;
                int jTrue = e.target;
                float[] logits = new float[numNodes];
                float maxLogit = Float.NEGATIVE_INFINITY;

                for (int j = 0; j < numNodes; j++) {
                    logits[j] = emb.projectionScore(i, j) / T;
                    if (logits[j] > maxLogit) maxLogit = logits[j];
                }

                float sumExp = 0f;
                float[] probs = new float[numNodes];
                for (int j = 0; j < numNodes; j++) {
                    probs[j] = (float) Math.exp(logits[j] - maxLogit);
                    sumExp += probs[j];
                }
                for (int j = 0; j < numNodes; j++) probs[j] /= sumExp;

                localLoss += -Math.log(probs[jTrue] + 1e-9f);

                for (int j = 0; j < numNodes; j++) {
                    float grad = (j == jTrue ? 1f : 0f) - probs[j];
                    float delta = lr * grad / T;
                    if (j != endId) emb.angle[j] += delta;
                    if (i != endId) emb.angle[i] -= delta;

                    if (emb.angle[j] > Math.PI) emb.angle[j] -= 2 * (float) Math.PI;
                    if (emb.angle[j] < -Math.PI) emb.angle[j] += 2 * (float) Math.PI;
                    if (emb.angle[i] > Math.PI) emb.angle[i] -= 2 * (float) Math.PI;
                    if (emb.angle[i] < -Math.PI) emb.angle[i] += 2 * (float) Math.PI;
                }
            }
            synchronized (lossAccumulator) {
                lossAccumulator[0] += localLoss;
            }
        }
    }

    public static List<Edge> loadEdgesFromCSV(String filePath) throws IOException {
        List<Edge> edges = new ArrayList<>();
        int maxNodeId = -1;
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            boolean skipHeader = true;
            while ((line = br.readLine()) != null) {
                if (skipHeader) {
                    skipHeader = false;
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length != 4) continue;
                int src = Integer.parseInt(parts[0].trim());
                int tgt = Integer.parseInt(parts[1].trim());
                int pos = Integer.parseInt(parts[2].trim());
                int cnt = Integer.parseInt(parts[3].trim());
                edges.add(new Edge(src, tgt, pos, cnt));
                maxNodeId = Math.max(maxNodeId, Math.max(src, tgt));
            }
        }
        System.out.println("Detected node count: " + (maxNodeId + 1));
        return edges;
    }

    public static void train(List<Edge> edges, int numNodes, float initialLr, float T, int epochs, int numThreads, String outputPath) throws Exception {
        int restarts = 500;
        Embeddings bestEmb = null;
        float bestLoss = Float.MAX_VALUE;

        for (int restart = 0; restart < restarts; restart++) {
            Random rand = new Random();
            Embeddings emb = new Embeddings(numNodes, rand);
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            float lr = initialLr;
            float previousLoss = Float.MAX_VALUE;
            final float minLr = 1e-7f;
            final float maxLr = 1e-2f;
            int warmupEpochs = 50;

            for (int epoch = 0; epoch < warmupEpochs; epoch++) {
                Collections.shuffle(edges);
                int batchSize = (edges.size() + numThreads - 1) / numThreads;
                List<Future<?>> futures = new ArrayList<>();
                float[] lossAccumulator = new float[]{0f};

                for (int i = 0; i < numThreads; i++) {
                    int from = i * batchSize;
                    int to = Math.min(from + batchSize, edges.size());
                    if (from < to) {
                        List<Edge> batch = edges.subList(from, to);
                        SGDWorker worker = new SGDWorker(batch, emb, lr, T, numNodes, lossAccumulator);
                        futures.add(executor.submit(worker));
                    }
                }
                for (Future<?> f : futures) f.get();

                float loss = lossAccumulator[0];
                float lossDiff = previousLoss - loss;
                if (lossDiff < 0) {
                    lr *= 0.5f;
                } else {
                    lr *= 1.05f;
                }
                lr = Math.max(minLr, Math.min(maxLr, lr));
                previousLoss = loss;

                System.out.println("[Init " + restart + "] Epoch " + epoch + " done. Loss: " + loss + ", Learning Rate: " + lr);

                if (epoch == warmupEpochs - 1 && loss < bestLoss) {
                    bestLoss = loss;
                    bestEmb = new Embeddings(numNodes, rand);
                    System.arraycopy(emb.angle, 0, bestEmb.angle, 0, numNodes);
                }
            }
            executor.shutdown();
        }

        if (bestEmb != null) {
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            float lr = initialLr;
            float previousLoss = Float.MAX_VALUE;
            final float minLr = 1e-7f;
            final float maxLr = 1e-2f;

            for (int epoch = 0; epoch < epochs; epoch++) {
                Collections.shuffle(edges);
                int batchSize = (edges.size() + numThreads - 1) / numThreads;
                List<Future<?>> futures = new ArrayList<>();
                float[] lossAccumulator = new float[]{0f};

                for (int i = 0; i < numThreads; i++) {
                    int from = i * batchSize;
                    int to = Math.min(from + batchSize, edges.size());
                    if (from < to) {
                        List<Edge> batch = edges.subList(from, to);
                        SGDWorker worker = new SGDWorker(batch, bestEmb, lr, T, numNodes, lossAccumulator);
                        futures.add(executor.submit(worker));
                    }
                }
                for (Future<?> f : futures) f.get();

                float loss = lossAccumulator[0];
                float lossDiff = previousLoss - loss;
                if (lossDiff < 0) {
                    lr *= 0.5f;
                } else {
                    lr *= 1.05f;
                }
                lr = Math.max(minLr, Math.min(maxLr, lr));
                previousLoss = loss;

                System.out.println("[Final] Epoch " + epoch + " done. Loss: " + loss + ", Learning Rate: " + lr);
            }

            executor.shutdown();
            bestEmb.saveToCSV(outputPath);
            System.out.println("Saved best initialization with loss: " + bestLoss);
        }
    }

    public static void main(String[] args) throws Exception {
        getEndSymbolId();
        if (args.length < 1) {
            args = new String[1];
            args[0] = "edge_counts.csv";
        }
        
        List<Edge> edgeList = loadEdgesFromCSV(args[0]);
        int maxNodeId = edgeList.stream().flatMapToInt(e -> java.util.stream.IntStream.of(e.source, e.target)).max().orElse(0);
        int numNodes = maxNodeId + 1;
        train(edgeList, numNodes, Hyperparams.INITIAL_LEARNING_RATE, Hyperparams.TEMPERATURE, 1000, 32, "node_signatures_fixed.csv");
    }
}
