package org.example.grape;

import java.util.*;

/**
 * Performance benchmark for the GrapeRank algorithm on synthetic graphs.
 *
 * Measures wall-clock time for graph construction and algorithm execution
 * across all six test topologies.  Runs multiple iterations to report
 * min / avg / max / median timings so that different implementations
 * can be compared reliably.
 *
 * Run with:  mvn exec:java -Dexec.classpathScope=test \
 *               -Dexec.mainClass=org.example.grape.GrapeRankBenchmark
 *
 * Or simply:  java -cp target/test-classes:target/classes org.example.grape.GrapeRankBenchmark
 */
public class GrapeRankBenchmark {

    // How many timed iterations to run per graph (after warm-up)
    private static final int WARMUP_ITERATIONS = 2;
    private static final int TIMED_ITERATIONS = 5;

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println("  GrapeRank Algorithm Benchmark");
        System.out.println("  Warm-up iterations : " + WARMUP_ITERATIONS);
        System.out.println("  Timed iterations   : " + TIMED_ITERATIONS);
        System.out.println("  Java version       : " + System.getProperty("java.version"));
        System.out.println("  Available CPUs      : " + Runtime.getRuntime().availableProcessors());
        System.out.println("  Max heap            : " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
        System.out.println("=================================================================\n");

        // ----- Build graphs (timed once) -----
        System.out.println("--- Graph Construction ---\n");
        System.out.printf("%-30s %8s %8s %8s %8s %10s%n",
                "Graph", "Users", "Follows", "Mutes", "Reports", "Build(ms)");
        System.out.println("-".repeat(84));

        List<TestGraphGenerator.TestGraph> graphs = new ArrayList<>();

        graphs.add(timeGraphBuild("LinearChain", TestGraphGenerator::linearChain));
        graphs.add(timeGraphBuild("StarGraph", TestGraphGenerator::starGraph));
        graphs.add(timeGraphBuild("CommunityClusters", TestGraphGenerator::communityClusters));
        graphs.add(timeGraphBuild("DenseSocialNetwork", TestGraphGenerator::denseSocialNetwork));
        graphs.add(timeGraphBuild("LargeScaleNetwork", TestGraphGenerator::largeScaleNetwork));
        graphs.add(timeGraphBuild("AdversarialNetwork", TestGraphGenerator::adversarialNetwork));

        // ----- Algorithm Benchmark -----
        System.out.println("\n--- Algorithm Benchmark ---\n");
        System.out.printf("%-30s %6s %10s %10s %10s %10s %12s%n",
                "Graph", "Rounds", "Min(ms)", "Avg(ms)", "Median(ms)", "Max(ms)", "Users/sec");
        System.out.println("-".repeat(100));

        for (TestGraphGenerator.TestGraph graph : graphs) {
            benchmarkGraph(graph);
        }

        // ----- Memory snapshot -----
        System.out.println("\n--- Memory After All Benchmarks ---");
        Runtime rt = Runtime.getRuntime();
        rt.gc();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        System.out.println("  Heap used: " + usedMB + " MB");

        System.out.println("\nDone.");
    }

    // ---------- Graph build timing ----------

    @FunctionalInterface
    interface GraphSupplier {
        TestGraphGenerator.TestGraph get();
    }

    private static TestGraphGenerator.TestGraph timeGraphBuild(String label, GraphSupplier supplier) {
        long start = System.nanoTime();
        TestGraphGenerator.TestGraph g = supplier.get();
        long elapsed = System.nanoTime() - start;

        System.out.printf("%-30s %8d %8d %8d %8d %10.1f%n",
                g.name, g.userCount, g.followCount, g.muteCount, g.reportCount,
                elapsed / 1_000_000.0);
        return g;
    }

    // ---------- Algorithm benchmark ----------

    private static void benchmarkGraph(TestGraphGenerator.TestGraph graph) {
        int lastRounds = 0;

        // Warm-up (not timed) — lets the JIT compile hot paths
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            resetScorecards(graph);
            GrapeRankAlgorithmResult res = GrapeRankAlgorithm.graperankAlgorithm(graph.inputs, graph.scorecards);
            lastRounds = res.getRounds();
        }

        // Timed iterations
        long[] timings = new long[TIMED_ITERATIONS];
        for (int i = 0; i < TIMED_ITERATIONS; i++) {
            resetScorecards(graph);

            long start = System.nanoTime();
            GrapeRankAlgorithmResult res = GrapeRankAlgorithm.graperankAlgorithm(graph.inputs, graph.scorecards);
            timings[i] = System.nanoTime() - start;
            lastRounds = res.getRounds();
        }

        Arrays.sort(timings);
        double minMs = timings[0] / 1_000_000.0;
        double maxMs = timings[TIMED_ITERATIONS - 1] / 1_000_000.0;
        double medMs = timings[TIMED_ITERATIONS / 2] / 1_000_000.0;
        double avgMs = Arrays.stream(timings).average().orElse(0) / 1_000_000.0;
        double usersPerSec = graph.userCount / (avgMs / 1000.0);

        System.out.printf("%-30s %6d %10.1f %10.1f %10.1f %10.1f %12.0f%n",
                graph.name, lastRounds, minMs, avgMs, medMs, maxMs, usersPerSec);
    }

    /**
     * Resets all scorecards to their initial state so the algorithm can run
     * from scratch.  The algorithm mutates scorecards in-place, so we need
     * to rebuild them between runs.
     */
    private static void resetScorecards(TestGraphGenerator.TestGraph graph) {
        for (Map.Entry<String, ScoreCard> entry : graph.scorecards.entrySet()) {
            ScoreCard sc = entry.getValue();
            if (sc.getObserver().equals(sc.getObservee())) {
                // Observer: keep at full influence
                sc.setAverageScore(1.0);
                sc.setInput(Double.POSITIVE_INFINITY);
                sc.setConfidence(1.0);
                sc.setInfluence(1.0);
                sc.setVerified(null);
            } else {
                // Non-observer: reset to initial zero state
                sc.setAverageScore(0);
                sc.setInput(0);
                sc.setConfidence(0);
                sc.setInfluence(0);
                sc.setVerified(null);
            }
        }
    }
}
