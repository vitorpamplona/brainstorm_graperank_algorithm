package org.example.grape;

import java.util.Map;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.stream.IntStream;

public class GrapeRankAlgorithm {

    private static final boolean DEBUG_LOG_ROUNDS = false;

    public static GrapeRankAlgorithmResult graperankAlgorithm(
            Map<String, List<GrapeRankInput>> graperankInputs,
            Map<String, ScoreCard> graperankScorecards) {

        int n = graperankScorecards.size();
        ScoreCard[] cards = new ScoreCard[n];
        Map<String, Integer> indexOf = new HashMap<>(n * 2);
        {
            int i = 0;
            for (Map.Entry<String, ScoreCard> entry : graperankScorecards.entrySet()) {
                cards[i] = entry.getValue();
                indexOf.put(entry.getKey(), i);
                i++;
            }
        }

        int[][] raterIndices = new int[n][];
        double[][] ratings = new double[n][];
        double[][] confidences = new double[n][];
        boolean[] skip = new boolean[n];

        for (int i = 0; i < n; i++) {
            ScoreCard sc = cards[i];
            if (sc.getObserver().equals(sc.getObservee())) {
                skip[i] = true;
                raterIndices[i] = new int[0];
                ratings[i] = new double[0];
                confidences[i] = new double[0];
                continue;
            }
            List<GrapeRankInput> relevantDataPoints =
                    graperankInputs.getOrDefault(sc.getObservee(), List.of());
            int m = relevantDataPoints.size();
            int[] idxArr = new int[m];
            double[] rArr = new double[m];
            double[] cArr = new double[m];
            for (int j = 0; j < m; j++) {
                GrapeRankInput dp = relevantDataPoints.get(j);
                idxArr[j] = indexOf.get(dp.getRater());
                rArr[j] = dp.getRating();
                cArr[j] = dp.getConfidence();
            }
            raterIndices[i] = idxArr;
            ratings[i] = rArr;
            confidences[i] = cArr;
        }

        double[] prevInfluence = new double[n];
        double[] nextInfluence = new double[n];
        double[] avgScores = new double[n];
        double[] sumWeights = new double[n];
        double[] confidenceOut = new double[n];
        for (int i = 0; i < n; i++) {
            prevInfluence[i] = cards[i].getInfluence();
            nextInfluence[i] = prevInfluence[i];
            avgScores[i] = cards[i].getAverageScore();
            sumWeights[i] = cards[i].getInput();
            confidenceOut[i] = cards[i].getConfidence();
        }

        final double threshold = Constants.THRESHOLD_OF_LOOP_BREAK_GIVEN_MINIMUM_DELTA_INFLUENCE;
        final double globalAttenuation = Constants.GLOBAL_ATTENUATION_FACTOR;
        final double globalRigor = Constants.GLOBAL_RIGOR;

        int rounds = 0;
        while (true) {
            final double[] prev = prevInfluence;
            final double[] next = nextInfluence;
            final DoubleAccumulator maxDelta = new DoubleAccumulator(Math::max, 0.0);

            IntStream.range(0, n).parallel().forEach(i -> {
                if (skip[i]) {
                    next[i] = prev[i];
                    return;
                }
                int[] raterIdx = raterIndices[i];
                double[] rArr = ratings[i];
                double[] cArr = confidences[i];
                double sumOfWeights = 0;
                double sumOfWxr = 0;
                for (int j = 0; j < raterIdx.length; j++) {
                    double infOfRater = prev[raterIdx[j]];
                    double weight = cArr[j] * infOfRater * globalAttenuation;
                    sumOfWeights += weight;
                    sumOfWxr += weight * rArr[j];
                }
                double avgScore = (sumOfWeights != 0) ? sumOfWxr / sumOfWeights : 0;
                double conf = convertInputToConfidence(sumOfWeights, globalRigor);
                double computedInfluence = Math.max(avgScore * conf, 0);
                double deltaInfluence = Math.abs(computedInfluence - prev[i]);

                avgScores[i] = avgScore;
                sumWeights[i] = sumOfWeights;
                confidenceOut[i] = conf;
                next[i] = computedInfluence;
                maxDelta.accumulate(deltaInfluence);
            });

            double[] tmp = prevInfluence;
            prevInfluence = nextInfluence;
            nextInfluence = tmp;

            rounds++;

            if (maxDelta.get() <= threshold) {
                break;
            }
        }

        if (DEBUG_LOG_ROUNDS) {
            System.out.println("NUMBER OF ROUNDS: " + rounds);
        }

        double[] finalInfluence = prevInfluence;
        for (int i = 0; i < n; i++) {
            ScoreCard sc = cards[i];
            if (!skip[i]) {
                sc.setAverageScore(avgScores[i]);
                sc.setInput(sumWeights[i]);
                sc.setConfidence(confidenceOut[i]);
                sc.setInfluence(finalInfluence[i]);
            }
            sc.setVerified(sc.getInfluence() >= Constants.DEFAULT_CUTOFF_OF_VALID_USER);
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

        List<String> relevantUsers = neo4jHelper.getUsersConnectedToObserver(observer, 992);
        Map<String, Double> userDistanceMap = new HashMap<>();

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

        Map<String, ScoreCard> scorecards = initGrapeRankScorecards(relevantUsers, observer,userDistanceMap);

        long algoStartTime = System.currentTimeMillis();
        GrapeRankAlgorithmResult algorithmResult = graperankAlgorithm(graperankInputs, scorecards);
        long algoEndTime = System.currentTimeMillis();
        System.out.println("Algorithm took " + (algoEndTime - algoStartTime) / 1000.0 + " seconds");


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

            //

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