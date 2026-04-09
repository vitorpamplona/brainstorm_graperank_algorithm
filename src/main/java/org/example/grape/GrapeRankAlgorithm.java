package org.example.grape;

import java.util.Map;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class GrapeRankAlgorithm {

    public static GrapeRankAlgorithmResult graperankAlgorithm(
            Map<String, List<GrapeRankInput>> graperankInputs,
            Map<String, ScoreCard> graperankScorecards) {

        int rounds = 0;
        boolean shouldBreak;

        while (true) {
            shouldBreak = true;

            for (Map.Entry<String, ScoreCard> entry : graperankScorecards.entrySet()) {
                ScoreCard scorecard = entry.getValue();

                if (scorecard.getObserver().equals(scorecard.getObservee())) {
                    continue;
                }

                // handling empty case. to investigate later
                List<GrapeRankInput> relevantDataPoints = graperankInputs.getOrDefault(scorecard.getObservee(),List.of());

                double sumOfWeights = 0;
                double sumOfWxr = 0;

                for (GrapeRankInput relevantDataPoint : relevantDataPoints) {
                    double infOfRater = graperankScorecards.get(relevantDataPoint.getRater()).getInfluence();
                    double weight = relevantDataPoint.getConfidence()
                            * infOfRater
                            * Constants.GLOBAL_ATTENUATION_FACTOR;

                    double wxr = weight * relevantDataPoint.getRating();

                    sumOfWeights += weight;
                    sumOfWxr += wxr;
                }

                double avgScore = (sumOfWeights != 0) ? sumOfWxr / sumOfWeights : 0;
                scorecard.setAverageScore(avgScore);
                scorecard.setInput(sumOfWeights);

                // Convert input to confidence (you need to define the logic for this)
                scorecard.setConfidence(convertInputToConfidence(scorecard.getInput(), Constants.GLOBAL_RIGOR));

                double computedInfluence = Math.max(scorecard.getAverageScore() * scorecard.getConfidence(), 0);
                double deltaInfluence = Math.abs(computedInfluence - scorecard.getInfluence());

                if (deltaInfluence > Constants.THRESHOLD_OF_LOOP_BREAK_GIVEN_MINIMUM_DELTA_INFLUENCE) {
                    shouldBreak = false;
                }

                scorecard.setInfluence(computedInfluence);
            }

            rounds++;
            System.out.println("NUMBER OF ROUNDS: " + rounds);

            if (shouldBreak) {
                break;
            }
        }

        for (ScoreCard scorecard : graperankScorecards.values()) {
            scorecard.setVerified(scorecard.getInfluence() >= Constants.DEFAULT_CUTOFF_OF_VALID_USER);
        }

        return new GrapeRankAlgorithmResult(graperankScorecards, rounds);

    }

    public static double convertInputToConfidence(double input, double rigor) {
        double rigority = -Math.log(rigor);
        double fooB = -input * rigority;
        double fooA = Math.exp(fooB);
        double confidence = 1 - fooA;
        return confidence;
    }

    public List<GrapeRankInput> getGrapeRankInputsOfRelationships(
            List<Neo4jHelper.RelationshipInfo> outgoingRelationships,
            String observer) {
        List<GrapeRankInput> graperankInputs = new ArrayList<>();

        for (Neo4jHelper.RelationshipInfo outgoingRelationshipObj : outgoingRelationships) {
            String outgoingRelationship = outgoingRelationshipObj.getRelationship();
            String outgoingRelationshipTarget = outgoingRelationshipObj.getTarget();
            String outgoingRelationshipSource = outgoingRelationshipObj.getSource();

            double rating = 0;

            switch (outgoingRelationship) {
                case "FOLLOWS":
                    rating = Constants.DEFAULT_RATING_FOR_FOLLOW;
                    break;
                case "MUTES":
                    rating = Constants.DEFAULT_RATING_FOR_MUTE;
                    break;
                case "REPORTS":
                    rating = Constants.DEFAULT_RATING_FOR_REPORT;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown relationship type: " + outgoingRelationship);
            }

            double confidence = 0;

            switch (outgoingRelationship) {
                case "FOLLOWS":
                    if (outgoingRelationshipSource.equals(observer)) {
                        confidence = Constants.DEFAULT_CONFIDENCE_FOR_FOLLOW_FROM_OBSERVER;
                    } else {
                        confidence = Constants.DEFAULT_CONFIDENCE_FOR_FOLLOW;
                    }
                    break;
                case "MUTES":
                    confidence = Constants.DEFAULT_CONFIDENCE_FOR_MUTE;
                    break;
                case "REPORTS":
                    confidence = Constants.DEFAULT_CONFIDENCE_FOR_REPORT;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown relationship type: " + outgoingRelationship);
            }

            GrapeRankInput newInput = new GrapeRankInput(outgoingRelationshipSource, outgoingRelationshipTarget, rating,
                    confidence);
            graperankInputs.add(newInput);
        }

        return graperankInputs;
    }

    public Map<String, ScoreCard> initGrapeRankScorecards(List<String> relevantUsers, String observer, Map<String, Double> userDistanceMap) {
        Map<String, ScoreCard> result = new HashMap<>();

        for (String user : relevantUsers) {
            if (!user.equals(observer)) {
                Double distance = userDistanceMap.getOrDefault(user,(double) 999);
                

                result.put(user, new ScoreCard(observer, user, distance));
            } else {

                result.put(user, new ScoreCard(
                        observer,
                        user,
                        1.0,
                        Double.POSITIVE_INFINITY,
                        1.0,
                        1.0));
            }
        }

        return result;
    }

    private static final int BATCH_SIZE = 1000;

    public static <T> List<List<T>> chunked(List<T> seq, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < seq.size(); i += size) {
            chunks.add(seq.subList(i, Math.min(i + size, seq.size())));
        }
        return chunks;
    }

    public GrapeRankResult graperankAllSteps(String observer) {
        long startTime = System.currentTimeMillis();

        Neo4jHelper neo4jHelper = new Neo4jHelper();

        // ---------------------------------------------------------------
        // STEP 1: Find all users reachable from observer  (~5-10% of total time)
        //
        // This fires a single variable-length path query: [:FOLLOWS*1..992].
        // On a 2M-user graph Neo4j must explore the entire follow-reachable
        // subgraph. For a popular observer this touches most of the graph.
        // Estimated cost: 60-120s on a 2M-user network.
        // ---------------------------------------------------------------
        List<String> relevantUsers = neo4jHelper.getUsersConnectedToObserver(observer, 992);
        Map<String, Double> userDistanceMap = new HashMap<>();

        // ---------------------------------------------------------------
        // STEP 2: Compute hop distances  (~20-40% of total time)
        //
        // Fires 8 SEPARATE variable-length path queries, one per hop limit
        // ([:FOLLOWS*1..8], [:FOLLOWS*1..7], ... [:FOLLOWS*1..1]).
        // Each query re-traverses the graph from scratch — Neo4j does not
        // cache or reuse intermediate BFS results between queries.
        // On a 2M-user graph each query costs 30-60s, totalling 240-480s.
        //
        // Potential improvement: a single Cypher query using
        // shortestPath() or APOC could return each user with its distance
        // in one pass, eliminating 7 of the 8 redundant traversals.
        // ---------------------------------------------------------------
        Map<Integer, List<String>> hopsMap = new HashMap<>();
        hopsMap.put(8, neo4jHelper.getUsersConnectedToObserver(observer, 8));
        hopsMap.put(7, neo4jHelper.getUsersConnectedToObserver(observer, 7));
        hopsMap.put(6, neo4jHelper.getUsersConnectedToObserver(observer, 6));
        hopsMap.put(5, neo4jHelper.getUsersConnectedToObserver(observer, 5));
        hopsMap.put(4, neo4jHelper.getUsersConnectedToObserver(observer, 4));
        hopsMap.put(3, neo4jHelper.getUsersConnectedToObserver(observer, 3));
        hopsMap.put(2, neo4jHelper.getUsersConnectedToObserver(observer, 2));
        hopsMap.put(1, neo4jHelper.getUsersConnectedToObserver(observer, 1));

        for (int hop = 8; hop >= 1; hop--) {
            List<String> usersAtHop = hopsMap.get(hop);
            for (String user : usersAtHop) {
                userDistanceMap.put(user, (double) hop);
            }
        }

        int numOfIts = (int) Math.round((double) relevantUsers.size() / BATCH_SIZE);
        System.out.println("How many Neo4j iterations: " + numOfIts);

        Map<String, List<GrapeRankInput>> graperankInputs = new HashMap<>();

        Map<String, List<String>> followersByUser = new HashMap<>();

        Map<String, List<String>> reportersByUser = new HashMap<>();

        // ---------------------------------------------------------------
        // STEP 3: Fetch all relationships in batches  (~40-50% of total time)
        //
        // For each batch of 1,000 users, fires 3 Neo4j queries:
        //   - getOutgoingRelationshipsBulk  (FOLLOWS|MUTES|REPORTS outgoing)
        //   - getIncomingFollowRelationshipsBulk  (who follows these users)
        //   - getIncomingReportRelationshipsBulk  (who reports these users)
        //
        // With 2M users this produces ~2,000 batches x 3 queries = 6,000
        // Neo4j round-trips. Each query UNWINDs 1,000 pubkeys and does
        // index lookups + relationship traversals.
        // Estimated cost: 480-600s on a 2M-user network.
        //
        // Potential improvements:
        //   - Increase BATCH_SIZE to reduce round-trips
        //   - Combine the 3 queries into a single query per batch
        //   - Fetch all relationships in one query instead of batching
        // ---------------------------------------------------------------
        int iteration = 0;
        for (List<String> usersBatch : chunked(relevantUsers, BATCH_SIZE)) {

            long batchStartTime = System.currentTimeMillis();
            List<Neo4jHelper.RelationshipInfo> outgoingRelationships = neo4jHelper.getOutgoingRelationshipsBulk(
                    usersBatch);

            List<Neo4jHelper.RelationshipInfo> incomingFollowRelationships = neo4jHelper.getIncomingFollowRelationshipsBulk(
                    usersBatch);

            List<Neo4jHelper.RelationshipInfo> incomingReportRelationships = neo4jHelper.getIncomingReportRelationshipsBulk(
                    usersBatch);

            long batchEndTime = System.currentTimeMillis();
            System.out.println(
                    iteration + " :: Getting relationships batched took " + (batchEndTime - batchStartTime) / 1000.0 + " seconds");

            List<GrapeRankInput> graperankInputsOfUser = getGrapeRankInputsOfRelationships(
                    outgoingRelationships, observer);

            for (GrapeRankInput grprIn : graperankInputsOfUser) {
                graperankInputs.computeIfAbsent(grprIn.getRatee(), k -> new ArrayList<>()).add(grprIn);
            }

            for (Neo4jHelper.RelationshipInfo rel : incomingFollowRelationships) {

                String followedUser = rel.getTarget();
                String follower = rel.getSource();

                followersByUser
                    .computeIfAbsent(followedUser, k -> new ArrayList<>())
                    .add(follower);
            }

            for (Neo4jHelper.RelationshipInfo rel : incomingReportRelationships) {

                String reportedUser = rel.getTarget();
                String reporter = rel.getSource();

                reportersByUser
                    .computeIfAbsent(reportedUser, k -> new ArrayList<>())
                    .add(reporter);
            }

            iteration++;
        }

        // ---------------------------------------------------------------
        // STEP 4: Run GrapeRank algorithm  (~8% of total time)
        //
        // The iterative influence computation. On a 2M-user graph with
        // ~43M edges this takes ~94s and converges in 4-5 rounds.
        // This is the only step the benchmark suite currently measures.
        // ---------------------------------------------------------------
        Map<String, ScoreCard> scorecards = initGrapeRankScorecards(relevantUsers, observer,userDistanceMap);

        long algoStartTime = System.currentTimeMillis();
        GrapeRankAlgorithmResult algorithmResult = graperankAlgorithm(graperankInputs, scorecards);
        long algoEndTime = System.currentTimeMillis();
        System.out.println("Algorithm took " + (algoEndTime - algoStartTime) / 1000.0 + " seconds");

        // ---------------------------------------------------------------
        // STEP 5: Count trusted followers/reporters  (~2-5% of total time)
        //
        // For each of the 2M users, iterates over their follower list and
        // checks each follower's influence against the cutoff thresholds.
        // With ~39M follow edges this is ~39M HashMap lookups + comparisons.
        // ---------------------------------------------------------------
        System.out.println("Getting trusted followers for each pubkey...");
        Map<String, ScoreCard> finalScorecards = algorithmResult.getScorecards();

        for (Map.Entry<String, ScoreCard> entry : finalScorecards.entrySet()) {
            String userPubkey = entry.getKey();
            ScoreCard scoreCard = entry.getValue();

            List<String> followers = followersByUser.getOrDefault(userPubkey, Collections.emptyList());

            long trustedFollowersCount = followers.stream()
                .filter(followerPubkey -> {
                    ScoreCard followerScoreCard = finalScorecards.get(followerPubkey);
                    return followerScoreCard != null && followerScoreCard.getInfluence() > Constants.DEFAULT_CUTOFF_OF_VALID_USER;
                })
                .count();

            scoreCard.setTrustedFollowers((double) trustedFollowersCount);

            List<String> reporters = reportersByUser.getOrDefault(userPubkey, Collections.emptyList());

            long trustedReportersCount = reporters.stream()
                .filter(reporterPubkey -> {
                    ScoreCard reporterScoreCard = finalScorecards.get(reporterPubkey);
                    return reporterScoreCard != null && reporterScoreCard.getInfluence() > Constants.DEFAULT_CUTOFF_OF_TRUSTED_REPORTER;
                })
                .count();

            scoreCard.setTrustedReporters((double) trustedReportersCount);
        }

        long finalTime = System.currentTimeMillis() - startTime;
        System.out.println("Entire process took " + (finalTime) / 1000.0 + " seconds");

        return new GrapeRankResult(
                algorithmResult.getScorecards(),
                algorithmResult.getRounds(),
                finalTime / 1000.0,
                relevantUsers.size() > 1);

    }

}