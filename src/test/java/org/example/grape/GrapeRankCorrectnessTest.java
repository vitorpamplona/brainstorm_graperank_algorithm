package org.example.grape;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Correctness tests for the GrapeRank algorithm running on synthetic graphs.
 *
 * Verifies algorithm invariants and deterministic result fingerprints so that
 * any implementation change that accidentally alters results is caught.
 *
 * Run with:  java -cp target/classes:target/test-classes org.example.grape.GrapeRankCorrectnessTest
 * Or via Maven: mvn test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=org.example.grape.GrapeRankCorrectnessTest
 */
public class GrapeRankCorrectnessTest {

    // ----- simple test harness -----
    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> failures = new ArrayList<>();

    private static void check(boolean condition, String testName) {
        if (condition) {
            passed++;
        } else {
            failed++;
            failures.add(testName);
            System.out.println("  FAIL: " + testName);
        }
    }

    private static void checkEquals(double expected, double actual, double tolerance, String testName) {
        check(Math.abs(expected - actual) <= tolerance, testName + " (expected=" + expected + ", actual=" + actual + ")");
    }

    // ----- cached graph instances -----
    private static TestGraphGenerator.TestGraph linearChain;
    private static TestGraphGenerator.TestGraph starGraph;
    private static TestGraphGenerator.TestGraph communityClusters;
    private static TestGraphGenerator.TestGraph denseSocialNetwork;
    private static TestGraphGenerator.TestGraph largeScaleNetwork;
    private static TestGraphGenerator.TestGraph adversarialNetwork;

    private static Map<String, GrapeRankAlgorithmResult> results = new LinkedHashMap<>();

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println("  GrapeRank Correctness Test Suite");
        System.out.println("=================================================================\n");

        // ----- build graphs and run algorithm -----
        System.out.println("--- Building graphs and running algorithm ---\n");

        linearChain = TestGraphGenerator.linearChain();
        starGraph = TestGraphGenerator.starGraph();
        communityClusters = TestGraphGenerator.communityClusters();
        denseSocialNetwork = TestGraphGenerator.denseSocialNetwork();
        largeScaleNetwork = TestGraphGenerator.largeScaleNetwork();
        adversarialNetwork = TestGraphGenerator.adversarialNetwork();

        List<TestGraphGenerator.TestGraph> allGraphs = List.of(
                linearChain, starGraph, communityClusters,
                denseSocialNetwork, largeScaleNetwork, adversarialNetwork);

        for (TestGraphGenerator.TestGraph g : allGraphs) {
            long t0 = System.currentTimeMillis();
            GrapeRankAlgorithmResult res = GrapeRankAlgorithm.graperankAlgorithm(g.inputs, g.scorecards);
            long elapsed = System.currentTimeMillis() - t0;
            results.put(g.name, res);
            System.out.println("  " + g + " => " + res.getRounds() + " rounds, " + elapsed + " ms");
        }

        // ----- run all test groups -----
        System.out.println("\n--- Structural Invariants (all graphs) ---\n");
        for (TestGraphGenerator.TestGraph g : allGraphs) {
            testStructuralInvariants(g);
        }

        System.out.println("\n--- Graph-Specific Correctness ---\n");
        testLinearChainDecay();
        testStarGraphDirectFollows();
        testCommunityLeadersVerified();
        testAdversarialGoodVsBad();
        testAdversarialVerifiedCounts();

        System.out.println("\n--- Determinism ---\n");
        testDeterminism();

        System.out.println("\n--- convertInputToConfidence ---\n");
        testConfidenceZeroInput();
        testConfidenceLargeInput();
        testConfidenceMonotonic();

        // ----- fingerprints (for regression detection) -----
        System.out.println("\n--- Result Fingerprints (record these for regression detection) ---\n");
        printFingerprints();

        // ----- summary -----
        System.out.println("\n=================================================================");
        System.out.println("  Results: " + passed + " passed, " + failed + " failed");
        if (!failures.isEmpty()) {
            System.out.println("\n  Failures:");
            for (String f : failures) {
                System.out.println("    - " + f);
            }
        }
        System.out.println("=================================================================");

        System.exit(failed > 0 ? 1 : 0);
    }

    // ========================================================================
    //  Structural Invariants (must hold for ANY correct implementation)
    // ========================================================================

    private static void testStructuralInvariants(TestGraphGenerator.TestGraph graph) {
        GrapeRankAlgorithmResult res = results.get(graph.name);
        Map<String, ScoreCard> sc = res.getScorecards();

        // Observer influence = 1.0
        ScoreCard observerCard = sc.get(graph.observer);
        check(observerCard != null, graph.name + ": observer scorecard exists");
        if (observerCard != null) {
            checkEquals(1.0, observerCard.getInfluence(), 1e-12,
                    graph.name + ": observer influence = 1.0");
        }

        // All influences non-negative
        boolean allNonNeg = true;
        for (Map.Entry<String, ScoreCard> e : sc.entrySet()) {
            if (e.getValue().getInfluence() < 0) {
                allNonNeg = false;
                System.out.println("    Negative influence: " + e.getKey() + " = " + e.getValue().getInfluence());
                break;
            }
        }
        check(allNonNeg, graph.name + ": all influences non-negative");

        // All confidences in [0,1]
        boolean allConfOk = true;
        for (Map.Entry<String, ScoreCard> e : sc.entrySet()) {
            double conf = e.getValue().getConfidence();
            if (conf < 0 || conf > 1.0) {
                allConfOk = false;
                System.out.println("    Bad confidence: " + e.getKey() + " = " + conf);
                break;
            }
        }
        check(allConfOk, graph.name + ": all confidences in [0,1]");

        // Verified flag consistency
        boolean verifiedOk = true;
        for (Map.Entry<String, ScoreCard> e : sc.entrySet()) {
            ScoreCard s = e.getValue();
            if (s.getVerified() != null) {
                boolean expected = s.getInfluence() >= Constants.DEFAULT_CUTOFF_OF_VALID_USER;
                if (expected != s.getVerified()) {
                    verifiedOk = false;
                    System.out.println("    Verified mismatch: " + e.getKey()
                            + " influence=" + s.getInfluence() + " verified=" + s.getVerified());
                    break;
                }
            }
        }
        check(verifiedOk, graph.name + ": verified flag consistent with cutoff");

        // Convergence
        check(res.getRounds() > 0, graph.name + ": at least 1 round");
        check(res.getRounds() < 1000, graph.name + ": converges within 1000 rounds (actual=" + res.getRounds() + ")");

        // Influence formula: influence = max(avgScore * confidence, 0)
        boolean formulaOk = true;
        for (Map.Entry<String, ScoreCard> e : sc.entrySet()) {
            ScoreCard s = e.getValue();
            if (s.getObserver().equals(s.getObservee())) continue;
            double expected = Math.max(s.getAverageScore() * s.getConfidence(), 0);
            if (Math.abs(expected - s.getInfluence()) > 1e-12) {
                formulaOk = false;
                System.out.println("    Formula mismatch: " + e.getKey()
                        + " expected=" + expected + " actual=" + s.getInfluence());
                break;
            }
        }
        check(formulaOk, graph.name + ": influence = max(avgScore * confidence, 0)");
    }

    // ========================================================================
    //  Graph-specific correctness tests
    // ========================================================================

    private static void testLinearChainDecay() {
        GrapeRankAlgorithmResult res = results.get(linearChain.name);
        Map<String, ScoreCard> sc = res.getScorecards();

        // Influence should decay along the chain
        boolean decays = true;
        double prev = sc.get(String.format("user%06d", 1)).getInfluence();
        for (int i = 2; i <= 8 && i < 100; i++) {
            double curr = sc.get(String.format("user%06d", i)).getInfluence();
            if (curr > prev + 1e-9) {
                decays = false;
                System.out.println("    Chain decay violation: user" + (i - 1) + "=" + prev + " < user" + i + "=" + curr);
                break;
            }
            prev = curr;
        }
        check(decays, "LinearChain: influence decays along chain");
    }

    private static void testStarGraphDirectFollows() {
        GrapeRankAlgorithmResult res = results.get(starGraph.name);
        Map<String, ScoreCard> sc = res.getScorecards();

        boolean allPositive = true;
        boolean allVerified = true;
        for (int i = 1; i <= 50; i++) {
            String hub = String.format("user%06d", i);
            ScoreCard card = sc.get(hub);
            if (card.getInfluence() <= 0) {
                allPositive = false;
                System.out.println("    Hub " + hub + " has zero/negative influence");
            }
            if (!Boolean.TRUE.equals(card.getVerified())) {
                allVerified = false;
                System.out.println("    Hub " + hub + " not verified, influence=" + card.getInfluence());
            }
        }
        check(allPositive, "StarGraph: all hubs have positive influence");
        check(allVerified, "StarGraph: all hubs are verified");
    }

    private static void testCommunityLeadersVerified() {
        GrapeRankAlgorithmResult res = results.get(communityClusters.name);
        Map<String, ScoreCard> sc = res.getScorecards();

        // Observer follows first 3 users of each 200-user community
        // Community starts at IDs: 1, 201, 401, 601, 801
        int[] communityStarts = {1, 201, 401, 601, 801};
        boolean leadersPositive = true;
        for (int start : communityStarts) {
            for (int offset = 0; offset < 3; offset++) {
                String leader = String.format("user%06d", start + offset);
                ScoreCard card = sc.get(leader);
                if (card == null || card.getInfluence() <= 0) {
                    leadersPositive = false;
                    System.out.println("    Leader " + leader + " missing or zero influence");
                }
            }
        }
        check(leadersPositive, "CommunityClusters: community leaders have positive influence");
    }

    private static void testAdversarialGoodVsBad() {
        GrapeRankAlgorithmResult res = results.get(adversarialNetwork.name);
        Map<String, ScoreCard> sc = res.getScorecards();

        double goodSum = 0;
        int goodCount = 0;
        double badSum = 0;
        int badCount = 0;

        for (Map.Entry<String, ScoreCard> e : sc.entrySet()) {
            int id = Integer.parseInt(e.getKey().replace("user", ""));
            double inf = e.getValue().getInfluence();
            if (id < 1000) {
                goodSum += inf;
                goodCount++;
            } else {
                badSum += inf;
                badCount++;
            }
        }

        double goodAvg = goodSum / goodCount;
        double badAvg = badSum / badCount;

        check(goodAvg > badAvg,
                "Adversarial: good avg influence (" + String.format("%.6f", goodAvg)
                        + ") > bad avg (" + String.format("%.6f", badAvg) + ")");
    }

    private static void testAdversarialVerifiedCounts() {
        GrapeRankAlgorithmResult res = results.get(adversarialNetwork.name);
        Map<String, ScoreCard> sc = res.getScorecards();

        long goodVerified = sc.entrySet().stream()
                .filter(e -> Integer.parseInt(e.getKey().replace("user", "")) < 1000)
                .filter(e -> Boolean.TRUE.equals(e.getValue().getVerified()))
                .count();

        long badVerified = sc.entrySet().stream()
                .filter(e -> Integer.parseInt(e.getKey().replace("user", "")) >= 1000)
                .filter(e -> Boolean.TRUE.equals(e.getValue().getVerified()))
                .count();

        check(goodVerified > badVerified,
                "Adversarial: more good verified (" + goodVerified + ") than bad (" + badVerified + ")");
    }

    // ========================================================================
    //  Determinism: same graph twice => same results
    // ========================================================================

    private static void testDeterminism() {
        TestGraphGenerator.TestGraph fresh = TestGraphGenerator.linearChain();
        GrapeRankAlgorithmResult res1 = results.get(linearChain.name);
        GrapeRankAlgorithmResult res2 = GrapeRankAlgorithm.graperankAlgorithm(fresh.inputs, fresh.scorecards);

        check(res1.getRounds() == res2.getRounds(),
                "Determinism: round counts match (" + res1.getRounds() + " vs " + res2.getRounds() + ")");

        boolean allMatch = true;
        for (String user : res1.getScorecards().keySet()) {
            ScoreCard sc1 = res1.getScorecards().get(user);
            ScoreCard sc2 = res2.getScorecards().get(user);
            if (Math.abs(sc1.getInfluence() - sc2.getInfluence()) > 1e-15
                    || Math.abs(sc1.getAverageScore() - sc2.getAverageScore()) > 1e-15
                    || Math.abs(sc1.getConfidence() - sc2.getConfidence()) > 1e-15) {
                allMatch = false;
                System.out.println("    Determinism mismatch for " + user);
                break;
            }
        }
        check(allMatch, "Determinism: all user scores identical on repeated run");
    }

    // ========================================================================
    //  convertInputToConfidence unit tests
    // ========================================================================

    private static void testConfidenceZeroInput() {
        double c = GrapeRankAlgorithm.convertInputToConfidence(0, 0.5);
        checkEquals(0.0, c, 1e-15, "convertInputToConfidence: zero input => zero confidence");
    }

    private static void testConfidenceLargeInput() {
        double c = GrapeRankAlgorithm.convertInputToConfidence(100, 0.5);
        check(c > 0.99, "convertInputToConfidence: large input => near 1.0 (actual=" + c + ")");
    }

    private static void testConfidenceMonotonic() {
        boolean monotonic = true;
        double prev = 0;
        for (double input = 0.1; input <= 10.0; input += 0.1) {
            double c = GrapeRankAlgorithm.convertInputToConfidence(input, Constants.GLOBAL_RIGOR);
            if (c < prev - 1e-15) {
                monotonic = false;
                System.out.println("    Monotonicity violation at input=" + input + ": " + prev + " > " + c);
                break;
            }
            prev = c;
        }
        check(monotonic, "convertInputToConfidence: monotonically increasing");
    }

    // ========================================================================
    //  Fingerprints — exact numeric regression detection
    //
    //  These values were captured from the current algorithm implementation.
    //  If an optimisation changes algorithm output, these will fail — update
    //  the expected values only after confirming the new results are correct.
    // ========================================================================

    private static void printFingerprints() {
        // Expected values from current implementation (captured 2026-04-09)
        // Format: { name, rounds, verifiedCount, influenceSum (tolerance 1e-6) }
        Object[][] expected = {
                {"LinearChain_100",          3,    2L,    1.2597406869},
                {"StarGraph_500",            4,   51L,   15.9297270139},
                {"CommunityClusters_1000",   4,   16L,    6.1170816899},
                {"DenseSocialNetwork_5000",  5,  104L,   37.3203422750},
                {"LargeScaleNetwork_20000",  4,  203L,   68.9590461545},
                {"AdversarialNetwork_2000",  5,   63L,   21.5680374168},
        };

        for (Object[] exp : expected) {
            String name = (String) exp[0];
            int expRounds = (int) exp[1];
            long expVerified = (long) exp[2];
            double expInfluenceSum = (double) exp[3];

            GrapeRankAlgorithmResult res = results.get(name);
            if (res == null) {
                check(false, "Fingerprint: missing result for " + name);
                continue;
            }

            Map<String, ScoreCard> sc = res.getScorecards();

            check(res.getRounds() == expRounds,
                    "Fingerprint " + name + ": rounds (expected=" + expRounds + ", actual=" + res.getRounds() + ")");

            long verified = sc.values().stream()
                    .filter(s -> Boolean.TRUE.equals(s.getVerified())).count();
            check(verified == expVerified,
                    "Fingerprint " + name + ": verified count (expected=" + expVerified + ", actual=" + verified + ")");

            double influenceSum = sc.values().stream()
                    .mapToDouble(ScoreCard::getInfluence).sum();
            checkEquals(expInfluenceSum, influenceSum, 1e-6,
                    "Fingerprint " + name + ": influence sum");

            System.out.printf("  %-30s rounds=%-4d  verified=%-6d  influenceSum=%.10f  [OK]%n",
                    name, res.getRounds(), verified, influenceSum);
        }
    }
}
