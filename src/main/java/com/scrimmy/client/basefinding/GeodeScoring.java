package com.scrimmy.client.basefinding;

/** Pure scoring model. Ratios alone are evidence, never proof of a base. */
public final class GeodeScoring {
    private GeodeScoring() {}

    public record Stats(
            int budding,
            int exposedBudding,
            int exposed,
            int empty,
            int small,
            int medium,
            int large,
            int clusters,
            boolean manMade) {
        public Stats {
            long filled = (long) small + medium + large + clusters;
            if (budding < 0
                    || exposedBudding < 0
                    || exposedBudding > budding
                    || exposed < 0
                    || exposed > (long) budding * 6
                    || empty < 0
                    || small < 0
                    || medium < 0
                    || large < 0
                    || clusters < 0
                    || filled + empty != exposed)
                throw new IllegalArgumentException("Invalid geode face counts");
        }

        public int filled() {
            return small + medium + large + clusters;
        }
    }

    public record Weights(
            double clusters,
            double fill,
            double anomaly,
            double manMade,
            double neighbors,
            double naturalFill,
            double naturalClusters) {}

    public record Breakdown(
            double clusters,
            double fill,
            double anomaly,
            double manMade,
            double neighbors,
            double sampleReliability,
            double score) {}

    public static Breakdown score(Stats s, Weights w, int nearbyOvergrown) {
        if (s.exposed <= 0) return new Breakdown(0, 0, 0, 0, 0, 0, 0);
        double c = clamp(s.clusters / (double) s.exposed),
                f = clamp(s.filled() / (double) s.exposed);
        // Worldgen selects a budding BLOCK with p=.35, then places ONE of four stages
        // on its FIRST growable face. It does not roll p=.35 independently per face.
        double facesPerBlock = Math.min(1, s.exposedBudding / (double) s.exposed);
        double naturalFill = w.naturalFill * facesPerBlock,
                naturalClusters = w.naturalClusters * facesPerBlock;
        double cluster = w.clusters * excess(c, naturalClusters);
        double fill = w.fill * excess(f, naturalFill);
        // Binomial variance is a heuristic: worldgen faces are correlated. Shrink tiny samples.
        double sigma = Math.sqrt(Math.max(.001, naturalFill * (1 - naturalFill)) / s.exposed);
        double anomaly =
                w.anomaly * clamp((f - naturalFill - 2 * sigma) / Math.max(.01, 1 - naturalFill));
        double made = s.manMade ? w.manMade : 0;
        double neighbors = w.neighbors * clamp(nearbyOvergrown / 2.0);
        double reliability = Math.min(1, s.exposed / 24.0);
        double total =
                Math.max(
                        0,
                        Math.min(100, (cluster + fill + anomaly + made + neighbors) * reliability));
        return new Breakdown(cluster, fill, anomaly, made, neighbors, reliability, total);
    }

    public static String confidence(double score) {
        return score >= 80 ? "High" : score >= 60 ? "Medium" : "Low";
    }

    public static int confidenceRank(String confidence) {
        return "High".equals(confidence) ? 2 : "Medium".equals(confidence) ? 1 : 0;
    }

    private static double excess(double value, double baseline) {
        return clamp((value - baseline) / Math.max(.001, 1 - baseline));
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
