// TODO: Before refractoring, write a statistics class to get the scopes under each token. 

package is.fivefivefive.ACGN.learn;

import java.util.List;
import java.util.LinkedList;
import java.util.Queue;
import java.util.ArrayList;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;


import is.fivefivefive.ACGN.alloy.Symbol;
import is.fivefivefive.ACGN.asg.AugmentedNode;
import is.fivefivefive.ACGN.asg.MASGEdge;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.alloyasg.etc.DoubleMap;

/**
 * Trainer for the ASG.
 * Pretrain the ASG with a given learning rate and decay.
 * @param trainSet The training set of ASGs.
 * @param validation The validation set of ASGs.
 * @param init The initial signatures.
 * @param lrInit The initial learning rate.
 * @param lrDecay The decay rate of the learning rate, should be less than 1, 1 is no decay.
 * @return The final signatures.
 * @return The final signatures.
 */
public class Trainer {
    public static List<Double> pretrainNaive(List<Multigraph> trainSet, List<Double> init, double lrInit, double lrDecay) {
        // TODO: AI generated the basis, refactor. REFACTOR: MUST USE SOFTMAX
        double lr = lrInit;
        List<Double> signatures = new ArrayList<Double>(init);
        // List<Double> losses = new ArrayList<Double>();
        // TODO: Compute initial individual losses
        // loss_i = sum(1 - softmax(sig_i - sig_j))

        for (Multigraph model : trainSet) {
            AugmentedNode root = model.getRoot();
            Queue<MASGEdge> edgeQueue = new LinkedList<MASGEdge>();
            edgeQueue.addAll(root.getDownlinks());
            while (!edgeQueue.isEmpty()) {
                MASGEdge edge = edgeQueue.poll();
                AugmentedNode target = edge.getTarget();
                double targetSig = target.getSignature();
                double sourceSig = edge.getSource().getSignature();
                double diff = targetSig - sourceSig;
                // double expDiff = Math.exp(diff);

                double newSig = sourceSig + lr * diff;
                edge.getSource().setSignature(newSig);
                edgeQueue.addAll(target.getDownlinks());
            }
            lr *= lrDecay;
        }
        
        return signatures;
    }
    public static List<Double> individualizedLoss(List<Multigraph> trainSet, List<Double> signatures) {
        List<Double> losses = new ArrayList<Double>();
        for (Multigraph model : trainSet) {
            AugmentedNode root = model.getRoot();
            Queue<MASGEdge> edgeQueue = new LinkedList<MASGEdge>();
            edgeQueue.addAll(root.getDownlinks());
            while (!edgeQueue.isEmpty()) {
                MASGEdge edge = edgeQueue.poll();
                AugmentedNode target = edge.getTarget();
                double targetSig = target.getSignature();
                double sourceSig = edge.getSource().getSignature();
                double diff = targetSig - sourceSig;
                double expDiff = Math.exp(diff);
                double loss = 1 - expDiff / (1 + expDiff);
                losses.add(loss);
            }
        }
        return losses;
    }
    public static Vector<Double> forward(Vector<Double> predictions, Vector<Double> targets) {
        // TODO: One step forward optimized with cross entropy loss
        return null;
    }
    public static double prediction(GlobalVariables gv, AugmentedNode vi, AugmentedNode vj, int position, double temperature) {
        double thetaI = vi.getSignature();
        double thetaJ = vj.getSignature();
        Symbol si = vi.getSymbol();
        double diff = thetaJ - thetaI;
        double expDiff = Math.exp(diff / temperature);
        double sum = 0;
        for (Symbol s : gv.getCandidates(si, position)) {
            double thetaK = s.getSignature();
            double expDiffK = Math.exp((thetaK - thetaI) / temperature);
            sum += expDiffK;
        }
        double result = expDiff /sum;
        return result;
    }
    public static double localLoss(double ground, double predicted) {
        return -1 * (ground * Math.log(predicted));
    }
    
    public static Vector<Double> softmax(Vector<Double> raw) {
        // generate the softmax
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < raw.size(); i++) {
            if (raw.get(i) > max) {
                max = raw.get(i);
            }
        }
        double sum = 0;
        for (int i = 0; i < raw.size(); i++) {
            sum += Math.exp(raw.get(i) - max);
        }
        Vector<Double> result = new Vector<Double>();
        for (int i = 0; i < raw.size(); i++) {
            result.add(Math.exp(raw.get(i) - max) / sum);
        }
        return result;
    }
    public static List<Double> pretrainByEnthalpy(List<Multigraph> trainSet, List<Double> init, 
            double lrInit, double lrDecay, double temp, double tolerance, 
            GlobalVariables gv) {
        // TODO after creating a new Visitor
        // Map<AugmentedNode, Set<AugmentedNode>> edgeMap = gv.getEdgeMap();
        double lr = lrInit;
        
        
        return null;
    }
    public static double crossEntropyLoss(Vector<Double> predictions, Vector<Double> targets) {
        double loss = 0;
        for (int i = 0; i < predictions.size(); i++) {
            double pred = predictions.get(i);
            double target = targets.get(i);
            loss += -target * Math.log(pred) - (1 - target) * Math.log(1 - pred);
        }
        return loss / predictions.size();
    }
}