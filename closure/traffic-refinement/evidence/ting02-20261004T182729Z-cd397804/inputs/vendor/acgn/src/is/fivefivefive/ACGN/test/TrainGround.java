package is.fivefivefive.ACGN.test;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import is.fivefivefive.ACGN.learn.Hyperparams;

public class TrainGround {
    static class Edge {
        int source, target, position, count;
        Edge(int s, int t, int p, int c) {
            source = s; target = t; position = p; count = c;
        }
    }

    static class Embeddings {
        float[][] theta;
        int dim;

        Embeddings(int numNodes, int dim) {
            this.dim = dim;
            theta = new float[numNodes][dim];
            Random rand = new Random();
            for (int i = 0; i < numNodes; i++) {
                float angle = (float) ((rand.nextFloat() * 2 - 1) * Math.PI); // angle in [-pi, pi]
                theta[i][0] = (float) Math.cos(angle);
                if (dim > 1) theta[i][1] = (float) Math.sin(angle);
                for (int j = 2; j < dim; j++)
                    theta[i][j] = 0f; // zero out other dimensions if >2D
            }
        }

        float dotDiff(int i, int j) {
            float sum = 0;
            for (int k = 0; k < dim; k++)
                sum += theta[j][k] - theta[i][k];
            return sum;
        }

        void saveToCSV(String filename) throws IOException {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename))) {
                for (int i = 0; i < theta.length; i++) {
                    float x = theta[i][0];
                    float y = dim > 1 ? theta[i][1] : 0f;
                    float angle = (float) Math.atan2(y, x); // angle in [-pi, pi]
                    writer.write(i + "," + angle);
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
                    logits[j] = emb.dotDiff(i, j) / T;
                    if (logits[j] > maxLogit) maxLogit = logits[j];
                }

                float sumExp = 0f;
                float[] probs = new float[numNodes];
                for (int j = 0; j < numNodes; j++) {
                    probs[j] = (float) Math.exp(logits[j] - maxLogit);
                    sumExp += probs[j];
                }
                for (int j = 0; j < numNodes; j++) probs[j] /= sumExp;

                localLoss += -Math.log(probs[jTrue] + 1e-9f); // cross-entropy

                for (int j = 0; j < numNodes; j++) {
                    float grad = (j == jTrue ? 1f : 0f) - probs[j];
                    for (int d = 0; d < emb.dim; d++) {
                        float update = lr * grad / T;
                        emb.theta[j][d] += update;
                        emb.theta[i][d] -= update;
                    }
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

    public static void train(List<Edge> edges, int numNodes, int dim, float initialLr, float T, int epochs, int numThreads, String outputPath) throws Exception {
        Embeddings emb = new Embeddings(numNodes, dim);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        float lr = initialLr;
        float decayRate = 0.9995f; // decay factor per epoch

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
                    SGDWorker worker = new SGDWorker(batch, emb, lr, T, numNodes, lossAccumulator);
                    futures.add(executor.submit(worker));
                }
            }
            for (Future<?> f : futures) f.get();
            System.out.printf("Epoch %d done. Loss: %.6f, Learning Rate: %.6f\n", epoch, lossAccumulator[0], lr);
            lr *= decayRate;
        }

        executor.shutdown();
        emb.saveToCSV(outputPath);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java TrainGround <csv_file_path>");
            return;
        }
        List<Edge> edgeList = loadEdgesFromCSV(args[0]);
        int maxNodeId = edgeList.stream().flatMapToInt(e -> java.util.stream.IntStream.of(e.source, e.target)).max().orElse(0);
        int numNodes = maxNodeId + 1;
        train(edgeList, numNodes, 2, Hyperparams.INITIAL_LEARNING_RATE, Hyperparams.TEMPERATURE, 10000, 12, "node_signatures.csv");
    }
}