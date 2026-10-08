package live;

/** Integer reward bridge: full score requires complete bounded agreement. */
final class BehaviorReward {
    private BehaviorReward() { }

    static int millis(int positiveTested, int positiveAccepted, int negativeTested,
            int negativeRejected, int semanticCounterexamples, boolean completeAgreement) {
        if (positiveTested < 1 || negativeTested < 1 || positiveTested > 100 || negativeTested > 100
                || positiveAccepted < 0 || positiveAccepted > positiveTested
                || negativeRejected < 0 || negativeRejected > negativeTested
                || semanticCounterexamples < 0 || semanticCounterexamples > 2)
            throw new IllegalArgumentException();
        long numerator = (long) positiveAccepted * negativeRejected;
        long denominator = (long) positiveTested * negativeTested + semanticCounterexamples;
        int rounded = (int) ((2000L * numerator + denominator) / (2L * denominator));
        boolean full = completeAgreement && semanticCounterexamples == 0
                && positiveAccepted == positiveTested && negativeRejected == negativeTested;
        return full ? rounded : Math.min(999, rounded);
    }
}
