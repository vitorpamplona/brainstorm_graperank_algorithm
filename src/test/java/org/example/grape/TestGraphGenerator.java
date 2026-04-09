package org.example.grape;

import java.util.*;

/**
 * Generates deterministic synthetic graphs for testing and benchmarking
 * the GrapeRank algorithm without requiring Neo4j.
 *
 * Six topologies of increasing size:
 *   1. Linear chain        (~100 users)
 *   2. Star hub-and-spoke  (~500 users)
 *   3. Community clusters   (~1,000 users)
 *   4. Dense social network (~5,000 users)
 *   5. Large-scale network  (~20,000 users)
 *   6. Adversarial network  (~2,000 users)
 */
public class TestGraphGenerator {

    // ---------- Result container ----------

    public static class TestGraph {
        public final String name;
        public final String observer;
        public final Map<String, List<GrapeRankInput>> inputs;
        public final Map<String, ScoreCard> scorecards;
        public final int userCount;
        public final int followCount;
        public final int muteCount;
        public final int reportCount;

        public TestGraph(String name,
                         String observer,
                         Map<String, List<GrapeRankInput>> inputs,
                         Map<String, ScoreCard> scorecards,
                         int userCount, int followCount, int muteCount, int reportCount) {
            this.name = name;
            this.observer = observer;
            this.inputs = inputs;
            this.scorecards = scorecards;
            this.userCount = userCount;
            this.followCount = followCount;
            this.muteCount = muteCount;
            this.reportCount = reportCount;
        }

        @Override
        public String toString() {
            return name + " [users=" + userCount
                    + ", follows=" + followCount
                    + ", mutes=" + muteCount
                    + ", reports=" + reportCount + "]";
        }
    }

    // ---------- Internal relationship representation ----------

    private static class Edge {
        final String source;
        final String target;
        final String type; // FOLLOWS, MUTES, REPORTS

        Edge(String source, String target, String type) {
            this.source = source;
            this.target = target;
            this.type = type;
        }
    }

    // ---------- Helpers ----------

    private static String userId(int i) {
        return String.format("user%06d", i);
    }

    /** BFS over follow edges to compute hop distances from observer. */
    private static Map<String, Double> computeDistances(String observer, List<Edge> edges, Set<String> allUsers) {
        // Build adjacency from follow edges (directed: source follows target, so traverse source -> target)
        Map<String, List<String>> adj = new HashMap<>();
        for (Edge e : edges) {
            if ("FOLLOWS".equals(e.type)) {
                adj.computeIfAbsent(e.source, k -> new ArrayList<>()).add(e.target);
            }
        }

        Map<String, Double> dist = new HashMap<>();
        Queue<String> queue = new LinkedList<>();
        dist.put(observer, 0.0);
        queue.add(observer);

        while (!queue.isEmpty()) {
            String cur = queue.poll();
            double curDist = dist.get(cur);
            if (curDist >= 8) continue; // max 8 hops
            List<String> neighbors = adj.getOrDefault(cur, Collections.emptyList());
            for (String next : neighbors) {
                if (!dist.containsKey(next)) {
                    dist.put(next, curDist + 1);
                    queue.add(next);
                }
            }
        }

        // Users not reachable via follows get hop 999
        for (String u : allUsers) {
            dist.putIfAbsent(u, 999.0);
        }
        return dist;
    }

    /** Convert edges into GrapeRankInputs keyed by ratee, using the same logic as GrapeRankAlgorithm. */
    private static Map<String, List<GrapeRankInput>> buildInputs(String observer, List<Edge> edges) {
        Map<String, List<GrapeRankInput>> map = new HashMap<>();
        for (Edge e : edges) {
            double rating;
            double confidence;
            switch (e.type) {
                case "FOLLOWS":
                    rating = Constants.DEFAULT_RATING_FOR_FOLLOW;
                    confidence = e.source.equals(observer)
                            ? Constants.DEFAULT_CONFIDENCE_FOR_FOLLOW_FROM_OBSERVER
                            : Constants.DEFAULT_CONFIDENCE_FOR_FOLLOW;
                    break;
                case "MUTES":
                    rating = Constants.DEFAULT_RATING_FOR_MUTE;
                    confidence = Constants.DEFAULT_CONFIDENCE_FOR_MUTE;
                    break;
                case "REPORTS":
                    rating = Constants.DEFAULT_RATING_FOR_REPORT;
                    confidence = Constants.DEFAULT_CONFIDENCE_FOR_REPORT;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown type: " + e.type);
            }
            GrapeRankInput input = new GrapeRankInput(e.source, e.target, rating, confidence);
            map.computeIfAbsent(e.target, k -> new ArrayList<>()).add(input);
        }
        return map;
    }

    /** Build scorecards using the same logic as GrapeRankAlgorithm.initGrapeRankScorecards. */
    private static Map<String, ScoreCard> buildScorecards(String observer, Set<String> allUsers,
                                                           Map<String, Double> distances) {
        Map<String, ScoreCard> result = new HashMap<>();
        for (String user : allUsers) {
            if (user.equals(observer)) {
                result.put(user, new ScoreCard(observer, user, 1.0, Double.POSITIVE_INFINITY, 1.0, 1.0));
            } else {
                double dist = distances.getOrDefault(user, 999.0);
                result.put(user, new ScoreCard(observer, user, dist));
            }
        }
        return result;
    }

    /** Assemble a TestGraph from observer + edges. */
    private static TestGraph assemble(String name, String observer, List<Edge> edges, Set<String> allUsers) {
        int follows = 0, mutes = 0, reports = 0;
        for (Edge e : edges) {
            switch (e.type) {
                case "FOLLOWS":  follows++;  break;
                case "MUTES":    mutes++;    break;
                case "REPORTS":  reports++;  break;
            }
        }

        Map<String, Double> distances = computeDistances(observer, edges, allUsers);
        Map<String, List<GrapeRankInput>> inputs = buildInputs(observer, edges);
        Map<String, ScoreCard> scorecards = buildScorecards(observer, allUsers, distances);

        return new TestGraph(name, observer, inputs, scorecards,
                allUsers.size(), follows, mutes, reports);
    }

    // ========================================================================
    // Graph 1: Linear Chain  (~100 users)
    //   observer -> u1 -> u2 -> ... -> u99
    //   Plus a few mutes and reports sprinkled in.
    // ========================================================================
    public static TestGraph linearChain() {
        int size = 100;
        String observer = userId(0);
        Set<String> users = new LinkedHashSet<>();
        List<Edge> edges = new ArrayList<>();
        Random rng = new Random(42);

        for (int i = 0; i < size; i++) {
            users.add(userId(i));
        }

        // Chain of follows
        for (int i = 0; i < size - 1; i++) {
            edges.add(new Edge(userId(i), userId(i + 1), "FOLLOWS"));
        }

        // Every 10th user mutes the user 5 ahead
        for (int i = 0; i + 5 < size; i += 10) {
            edges.add(new Edge(userId(i), userId(i + 5), "MUTES"));
        }

        // A few reports
        for (int i = 2; i < size - 20; i += 15) {
            edges.add(new Edge(userId(i), userId(i + 20), "REPORTS"));
        }

        return assemble("LinearChain_100", observer, edges, users);
    }

    // ========================================================================
    // Graph 2: Star / Hub-and-Spoke  (~500 users)
    //   Observer follows 50 hubs. Each hub follows 9 leaves (total ~450 leaves).
    //   Some cross-hub follows, mutes between hubs, reports on random leaves.
    // ========================================================================
    public static TestGraph starGraph() {
        int hubCount = 50;
        int leavesPerHub = 9;
        String observer = userId(0);
        Set<String> users = new LinkedHashSet<>();
        List<Edge> edges = new ArrayList<>();
        Random rng = new Random(123);

        users.add(observer);

        int nextId = 1;

        // Create hubs
        List<String> hubs = new ArrayList<>();
        for (int h = 0; h < hubCount; h++) {
            String hub = userId(nextId++);
            hubs.add(hub);
            users.add(hub);
            edges.add(new Edge(observer, hub, "FOLLOWS"));
        }

        // Each hub gets leaves
        for (String hub : hubs) {
            for (int l = 0; l < leavesPerHub; l++) {
                String leaf = userId(nextId++);
                users.add(leaf);
                edges.add(new Edge(hub, leaf, "FOLLOWS"));
            }
        }

        // Cross-hub follows (10% of hub pairs)
        for (int i = 0; i < hubs.size(); i++) {
            for (int j = i + 1; j < hubs.size(); j++) {
                if (rng.nextDouble() < 0.10) {
                    edges.add(new Edge(hubs.get(i), hubs.get(j), "FOLLOWS"));
                }
            }
        }

        // Mutes between some hubs
        for (int i = 0; i < hubs.size(); i += 5) {
            if (i + 3 < hubs.size()) {
                edges.add(new Edge(hubs.get(i), hubs.get(i + 3), "MUTES"));
            }
        }

        // Reports on random leaves
        List<String> userList = new ArrayList<>(users);
        for (int i = 0; i < 30; i++) {
            String reporter = hubs.get(rng.nextInt(hubs.size()));
            String target = userList.get(rng.nextInt(userList.size()));
            if (!reporter.equals(target) && !target.equals(observer)) {
                edges.add(new Edge(reporter, target, "REPORTS"));
            }
        }

        return assemble("StarGraph_500", observer, edges, users);
    }

    // ========================================================================
    // Graph 3: Community Clusters  (~1,000 users)
    //   5 communities of 200 users each. Dense intra-community follows (~15 per user).
    //   Sparse inter-community follows (~2 per user). Observer follows 3 leaders per community.
    //   Mutes & reports between communities.
    // ========================================================================
    public static TestGraph communityClusters() {
        int communityCount = 5;
        int usersPerCommunity = 200;
        String observer = userId(0);
        Set<String> users = new LinkedHashSet<>();
        List<Edge> edges = new ArrayList<>();
        Random rng = new Random(456);

        users.add(observer);

        List<List<String>> communities = new ArrayList<>();
        int nextId = 1;

        for (int c = 0; c < communityCount; c++) {
            List<String> community = new ArrayList<>();
            for (int u = 0; u < usersPerCommunity; u++) {
                String uid = userId(nextId++);
                community.add(uid);
                users.add(uid);
            }
            communities.add(community);

            // Observer follows first 3 users (leaders) of each community
            for (int l = 0; l < 3; l++) {
                edges.add(new Edge(observer, community.get(l), "FOLLOWS"));
            }
        }

        // Dense intra-community follows
        for (List<String> community : communities) {
            for (String user : community) {
                int followCount = 10 + rng.nextInt(10); // 10-19 follows
                for (int f = 0; f < followCount; f++) {
                    String target = community.get(rng.nextInt(community.size()));
                    if (!target.equals(user)) {
                        edges.add(new Edge(user, target, "FOLLOWS"));
                    }
                }
            }
        }

        // Sparse inter-community follows
        for (int c = 0; c < communityCount; c++) {
            List<String> src = communities.get(c);
            List<String> dst = communities.get((c + 1) % communityCount);
            for (int i = 0; i < src.size(); i++) {
                if (rng.nextDouble() < 0.15) {
                    edges.add(new Edge(src.get(i), dst.get(rng.nextInt(dst.size())), "FOLLOWS"));
                }
            }
        }

        // Inter-community mutes
        for (int c = 0; c < communityCount; c++) {
            List<String> src = communities.get(c);
            List<String> dst = communities.get((c + 2) % communityCount);
            for (int i = 0; i < 20; i++) {
                edges.add(new Edge(
                        src.get(rng.nextInt(src.size())),
                        dst.get(rng.nextInt(dst.size())),
                        "MUTES"));
            }
        }

        // Reports: some users report members of distant communities
        for (int c = 0; c < communityCount; c++) {
            List<String> src = communities.get(c);
            List<String> dst = communities.get((c + 3) % communityCount);
            for (int i = 0; i < 15; i++) {
                edges.add(new Edge(
                        src.get(rng.nextInt(src.size())),
                        dst.get(rng.nextInt(dst.size())),
                        "REPORTS"));
            }
        }

        return assemble("CommunityClusters_1000", observer, edges, users);
    }

    // ========================================================================
    // Graph 4: Dense Social Network  (~5,000 users)
    //   Realistic power-law-ish social graph.
    //   Each user follows ~20 others (preferential attachment style),
    //   ~2 mutes, ~0.5 reports on average.
    // ========================================================================
    public static TestGraph denseSocialNetwork() {
        int totalUsers = 5000;
        String observer = userId(0);
        Set<String> users = new LinkedHashSet<>();
        List<Edge> edges = new ArrayList<>();
        Random rng = new Random(789);

        for (int i = 0; i < totalUsers; i++) {
            users.add(userId(i));
        }

        // Observer follows 100 random users (first-hop "friends")
        List<String> observerFollows = new ArrayList<>();
        Set<Integer> picked = new HashSet<>();
        while (observerFollows.size() < 100) {
            int idx = 1 + rng.nextInt(totalUsers - 1);
            if (picked.add(idx)) {
                String target = userId(idx);
                observerFollows.add(target);
                edges.add(new Edge(observer, target, "FOLLOWS"));
            }
        }

        // Each non-observer user follows ~20 others with preferential attachment
        // (lower-indexed users are more likely to be followed, simulating popularity)
        for (int i = 1; i < totalUsers; i++) {
            String src = userId(i);
            int followCount = 12 + rng.nextInt(16); // 12-27 follows
            Set<Integer> targets = new HashSet<>();
            for (int f = 0; f < followCount; f++) {
                // Power-law-ish: square root biases toward popular (lower-id) users
                int target = (int) (Math.pow(rng.nextDouble(), 1.5) * totalUsers);
                if (target != i && targets.add(target)) {
                    edges.add(new Edge(src, userId(target), "FOLLOWS"));
                }
            }
        }

        // Mutes: ~2 per user on average
        for (int i = 0; i < totalUsers; i++) {
            if (rng.nextDouble() < 0.5) {
                int muteCount = 1 + rng.nextInt(5);
                for (int m = 0; m < muteCount; m++) {
                    int target = rng.nextInt(totalUsers);
                    if (target != i) {
                        edges.add(new Edge(userId(i), userId(target), "MUTES"));
                    }
                }
            }
        }

        // Reports: ~0.5 per user on average
        for (int i = 0; i < totalUsers; i++) {
            if (rng.nextDouble() < 0.1) {
                int reportCount = 1 + rng.nextInt(8);
                for (int r = 0; r < reportCount; r++) {
                    int target = rng.nextInt(totalUsers);
                    if (target != i) {
                        edges.add(new Edge(userId(i), userId(target), "REPORTS"));
                    }
                }
            }
        }

        return assemble("DenseSocialNetwork_5000", observer, edges, users);
    }

    // ========================================================================
    // Graph 5: Large-Scale Network  (~20,000 users)
    //   Similar to dense social but 4x bigger for pure perf testing.
    //   Average ~15 follows, ~1 mute, ~0.3 reports per user.
    // ========================================================================
    public static TestGraph largeScaleNetwork() {
        int totalUsers = 20_000;
        String observer = userId(0);
        Set<String> users = new LinkedHashSet<>();
        List<Edge> edges = new ArrayList<>();
        Random rng = new Random(2024);

        for (int i = 0; i < totalUsers; i++) {
            users.add(userId(i));
        }

        // Observer follows 200 users
        Set<Integer> picked = new HashSet<>();
        int count = 0;
        while (count < 200) {
            int idx = 1 + rng.nextInt(totalUsers - 1);
            if (picked.add(idx)) {
                edges.add(new Edge(observer, userId(idx), "FOLLOWS"));
                count++;
            }
        }

        // Each user follows ~15 others
        for (int i = 1; i < totalUsers; i++) {
            String src = userId(i);
            int followCount = 8 + rng.nextInt(14); // 8-21 follows
            Set<Integer> targets = new HashSet<>();
            for (int f = 0; f < followCount; f++) {
                int target = (int) (Math.pow(rng.nextDouble(), 1.5) * totalUsers);
                if (target != i && targets.add(target)) {
                    edges.add(new Edge(src, userId(target), "FOLLOWS"));
                }
            }
        }

        // Mutes: ~1 per user on average
        for (int i = 0; i < totalUsers; i++) {
            if (rng.nextDouble() < 0.3) {
                int muteCount = 1 + rng.nextInt(4);
                for (int m = 0; m < muteCount; m++) {
                    int target = rng.nextInt(totalUsers);
                    if (target != i) {
                        edges.add(new Edge(userId(i), userId(target), "MUTES"));
                    }
                }
            }
        }

        // Reports: ~0.3 per user on average
        for (int i = 0; i < totalUsers; i++) {
            if (rng.nextDouble() < 0.06) {
                int reportCount = 1 + rng.nextInt(6);
                for (int r = 0; r < reportCount; r++) {
                    int target = rng.nextInt(totalUsers);
                    if (target != i) {
                        edges.add(new Edge(userId(i), userId(target), "REPORTS"));
                    }
                }
            }
        }

        return assemble("LargeScaleNetwork_20000", observer, edges, users);
    }

    // ========================================================================
    // Graph 6: Adversarial Network  (~2,000 users)
    //   1,000 "good" users + 1,000 "bad" users.
    //   Good users follow each other densely and report bad users.
    //   Bad users follow each other and mute good users.
    //   Observer is in the good group.
    //   Tests that the algorithm correctly penalises bad actors.
    // ========================================================================
    public static TestGraph adversarialNetwork() {
        int goodCount = 1000;
        int badCount = 1000;
        String observer = userId(0); // observer is good
        Set<String> users = new LinkedHashSet<>();
        List<Edge> edges = new ArrayList<>();
        Random rng = new Random(1337);

        // Good users: 0 .. goodCount-1
        List<String> good = new ArrayList<>();
        for (int i = 0; i < goodCount; i++) {
            String uid = userId(i);
            good.add(uid);
            users.add(uid);
        }

        // Bad users: goodCount .. goodCount+badCount-1
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < badCount; i++) {
            String uid = userId(goodCount + i);
            bad.add(uid);
            users.add(uid);
        }

        // Observer follows 50 good users
        for (int i = 1; i < 51 && i < goodCount; i++) {
            edges.add(new Edge(observer, good.get(i), "FOLLOWS"));
        }

        // Good-to-good: dense follows (~15 per user)
        for (int i = 0; i < goodCount; i++) {
            int followCount = 10 + rng.nextInt(10);
            for (int f = 0; f < followCount; f++) {
                int target = rng.nextInt(goodCount);
                if (target != i) {
                    edges.add(new Edge(good.get(i), good.get(target), "FOLLOWS"));
                }
            }
        }

        // Bad-to-bad: dense follows (~15 per user)
        for (int i = 0; i < badCount; i++) {
            int followCount = 10 + rng.nextInt(10);
            for (int f = 0; f < followCount; f++) {
                int target = rng.nextInt(badCount);
                if (target != i) {
                    edges.add(new Edge(bad.get(i), bad.get(target), "FOLLOWS"));
                }
            }
        }

        // Some bad users follow good users (trying to look legitimate)
        for (int i = 0; i < badCount; i++) {
            if (rng.nextDouble() < 0.3) {
                int target = rng.nextInt(goodCount);
                edges.add(new Edge(bad.get(i), good.get(target), "FOLLOWS"));
            }
        }

        // Good users report bad users
        for (int i = 0; i < goodCount; i++) {
            if (rng.nextDouble() < 0.25) {
                int reportCount = 1 + rng.nextInt(4);
                for (int r = 0; r < reportCount; r++) {
                    int target = rng.nextInt(badCount);
                    edges.add(new Edge(good.get(i), bad.get(target), "REPORTS"));
                }
            }
        }

        // Bad users mute good users
        for (int i = 0; i < badCount; i++) {
            if (rng.nextDouble() < 0.3) {
                int muteCount = 1 + rng.nextInt(5);
                for (int m = 0; m < muteCount; m++) {
                    int target = rng.nextInt(goodCount);
                    edges.add(new Edge(bad.get(i), good.get(target), "MUTES"));
                }
            }
        }

        // Bad users also report good users (adversarial behavior)
        for (int i = 0; i < badCount; i++) {
            if (rng.nextDouble() < 0.15) {
                int target = rng.nextInt(goodCount);
                edges.add(new Edge(bad.get(i), good.get(target), "REPORTS"));
            }
        }

        return assemble("AdversarialNetwork_2000", observer, edges, users);
    }

    // ========================================================================
    // Graph 7: Massive Network  (~2,000,000 users)
    //   Realistic large-scale social graph for serious performance testing.
    //   The observer is a "popular user" — follows 2,000 accounts and is
    //   followed back by many of them, creating a dense trust neighbourhood.
    //   Average ~20 follows, ~2 mutes, ~0.5 reports per user.
    //   Power-law degree distribution via preferential attachment.
    //   Too large for the standard correctness suite — run via benchmark only.
    // ========================================================================
    public static TestGraph massiveNetwork() {
        int totalUsers = 2_000_000;
        String observer = userId(0);
        Set<String> users = new LinkedHashSet<>(totalUsers * 2);
        List<Edge> edges = new ArrayList<>(totalUsers * 25);
        Random rng = new Random(999_999);

        System.out.println("  [MassiveNetwork] Creating " + totalUsers + " users...");
        for (int i = 0; i < totalUsers; i++) {
            users.add(userId(i));
        }

        // Observer is a popular user — follows 2,000 accounts
        System.out.println("  [MassiveNetwork] Adding observer follows (popular user, 2000 follows)...");
        Set<Integer> observerFollows = new HashSet<>();
        while (observerFollows.size() < 2000) {
            // Bias toward lower IDs (other popular users) for first half,
            // uniform for second half — mimics a real popular account
            int idx;
            if (observerFollows.size() < 1000) {
                idx = 1 + (int) (Math.pow(rng.nextDouble(), 2.0) * Math.min(50_000, totalUsers - 1));
            } else {
                idx = 1 + rng.nextInt(totalUsers - 1);
            }
            if (observerFollows.add(idx)) {
                edges.add(new Edge(observer, userId(idx), "FOLLOWS"));
            }
        }

        // Many users follow the observer back (popular user has many followers).
        // ~5,000 users follow the observer — this creates dense inbound ratings.
        System.out.println("  [MassiveNetwork] Adding followers of observer (popular user, ~5000 followers)...");
        for (int i = 1; i < totalUsers; i++) {
            if (rng.nextDouble() < 0.0025) { // ~5,000 users
                edges.add(new Edge(userId(i), observer, "FOLLOWS"));
            }
        }

        // Each non-observer user follows ~20 others (power-law distribution).
        // Lower-ID users are more popular (preferential attachment).
        System.out.println("  [MassiveNetwork] Adding follow edges (~20/user)...");
        for (int i = 1; i < totalUsers; i++) {
            String src = userId(i);
            int followCount = 12 + rng.nextInt(16); // 12-27 follows, avg ~20
            Set<Integer> targets = new HashSet<>();
            for (int f = 0; f < followCount; f++) {
                int target = (int) (Math.pow(rng.nextDouble(), 1.5) * totalUsers);
                if (target != i && targets.add(target)) {
                    edges.add(new Edge(src, userId(target), "FOLLOWS"));
                }
            }
        }

        // Mutes: ~2 per user on average
        System.out.println("  [MassiveNetwork] Adding mutes (~2/user)...");
        for (int i = 0; i < totalUsers; i++) {
            if (rng.nextDouble() < 0.5) {
                int muteCount = 1 + rng.nextInt(5); // 1-5 mutes
                for (int m = 0; m < muteCount; m++) {
                    int target = rng.nextInt(totalUsers);
                    if (target != i) {
                        edges.add(new Edge(userId(i), userId(target), "MUTES"));
                    }
                }
            }
        }

        // Reports: ~0.5 per user on average
        System.out.println("  [MassiveNetwork] Adding reports (~0.5/user)...");
        for (int i = 0; i < totalUsers; i++) {
            if (rng.nextDouble() < 0.1) {
                int reportCount = 1 + rng.nextInt(8); // 1-8 reports
                for (int r = 0; r < reportCount; r++) {
                    int target = rng.nextInt(totalUsers);
                    if (target != i) {
                        edges.add(new Edge(userId(i), userId(target), "REPORTS"));
                    }
                }
            }
        }

        System.out.println("  [MassiveNetwork] Assembling graph (" + edges.size() + " edges)...");
        return assemble("MassiveNetwork_2000000", observer, edges, users);
    }

    /** Returns all six standard test graphs in order of increasing size. */
    public static List<TestGraph> allGraphs() {
        List<TestGraph> graphs = new ArrayList<>();
        graphs.add(linearChain());
        graphs.add(starGraph());
        graphs.add(communityClusters());
        graphs.add(denseSocialNetwork());
        graphs.add(largeScaleNetwork());
        graphs.add(adversarialNetwork());
        return graphs;
    }
}
