package org.example.grape;

import java.util.Map;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class GrapeRankAlgorithm {

    public static int graperankAlgorithm(
            int[][] inputRaterIds,
            double[][] inputRatings,
            double[][] inputConfidences,
            double[] influence,
            double[] averageScore,
            double[] input,
            double[] confidence,
            int observerId) {

        int n = influence.length;
        int rounds = 0;

        while (true) {
            boolean shouldBreak = true;

            for (int i = 0; i < n; i++) {
                if (i == observerId) {
                    continue;
                }

                int[] raters = inputRaterIds[i];
                double[] ratings = inputRatings[i];
                double[] confs = inputConfidences[i];

                double sumOfWeights = 0;
                double sumOfWxr = 0;

                int len = raters.length;
                for (int j = 0; j < len; j++) {
                    double infOfRater = influence[raters[j]];
                    double weight = confs[j] * infOfRater * Constants.GLOBAL_ATTENUATION_FACTOR;
                    sumOfWeights += weight;
                    sumOfWxr += weight * ratings[j];
                }

                double avgScore = (sumOfWeights != 0) ? sumOfWxr / sumOfWeights : 0;
                averageScore[i] = avgScore;
                input[i] = sumOfWeights;

                double conf = convertInputToConfidence(sumOfWeights, Constants.GLOBAL_RIGOR);
                confidence[i] = conf;

                double computedInfluence = Math.max(avgScore * conf, 0);
                double deltaInfluence = Math.abs(computedInfluence - influence[i]);

                if (deltaInfluence > Constants.THRESHOLD_OF_LOOP_BREAK_GIVEN_MINIMUM_DELTA_INFLUENCE) {
                    shouldBreak = false;
                }

                influence[i] = computedInfluence;
            }

            rounds++;
            System.out.println("NUMBER OF ROUNDS: " + rounds);

            if (shouldBreak) {
                break;
            }
        }

        return rounds;
    }

    public static GrapeRankAlgorithmResult graperankAlgorithm(
            Map<String, List<GrapeRankInput>> graperankInputs,
            Map<String, ScoreCard> graperankScorecards) {

        int n = graperankScorecards.size();
        HashMap<String, Integer> pubkeyToId = new HashMap<>(n * 2);
        String[] idToPubkey = new String[n];

        int idx = 0;
        int observerId = -1;
        for (Map.Entry<String, ScoreCard> entry : graperankScorecards.entrySet()) {
            String pubkey = entry.getKey();
            pubkeyToId.put(pubkey, idx);
            idToPubkey[idx] = pubkey;
            ScoreCard sc = entry.getValue();
            if (sc.getObserver().equals(sc.getObservee())) {
                observerId = idx;
            }
            idx++;
        }

        double[] influence = new double[n];
        double[] averageScore = new double[n];
        double[] input = new double[n];
        double[] confidence = new double[n];

        for (int i = 0; i < n; i++) {
            ScoreCard sc = graperankScorecards.get(idToPubkey[i]);
            influence[i] = sc.getInfluence();
            averageScore[i] = sc.getAverageScore();
            input[i] = sc.getInput();
            confidence[i] = sc.getConfidence();
        }

        int[][] inputRaterIds = new int[n][];
        double[][] inputRatings = new double[n][];
        double[][] inputConfidences = new double[n][];

        for (int i = 0; i < n; i++) {
            List<GrapeRankInput> rdps = graperankInputs.getOrDefault(idToPubkey[i], List.of());
            int rl = rdps.size();
            int[] raters = new int[rl];
            double[] ratings = new double[rl];
            double[] confs = new double[rl];
            int filled = 0;
            for (int j = 0; j < rl; j++) {
                GrapeRankInput in = rdps.get(j);
                Integer rid = pubkeyToId.get(in.getRater());
                if (rid == null) {
                    continue;
                }
                raters[filled] = rid;
                ratings[filled] = in.getRating();
                confs[filled] = in.getConfidence();
                filled++;
            }
            if (filled != rl) {
                raters = Arrays.copyOf(raters, filled);
                ratings = Arrays.copyOf(ratings, filled);
                confs = Arrays.copyOf(confs, filled);
            }
            inputRaterIds[i] = raters;
            inputRatings[i] = ratings;
            inputConfidences[i] = confs;
        }

        int rounds = graperankAlgorithm(
                inputRaterIds, inputRatings, inputConfidences,
                influence, averageScore, input, confidence,
                observerId);

        for (int i = 0; i < n; i++) {
            if (i == observerId) {
                continue;
            }
            ScoreCard sc = graperankScorecards.get(idToPubkey[i]);
            sc.setAverageScore(averageScore[i]);
            sc.setInput(input[i]);
            sc.setConfidence(confidence[i]);
            sc.setInfluence(influence[i]);
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

        List<String> relevantUsers = neo4jHelper.getUsersConnectedToObserver(observer, 992);

        int n = relevantUsers.size();
        HashMap<String, Integer> pubkeyToId = new HashMap<>(n * 2);
        String[] idToPubkey = new String[n];
        for (int i = 0; i < n; i++) {
            String pk = relevantUsers.get(i);
            pubkeyToId.put(pk, i);
            idToPubkey[i] = pk;
        }
        Integer observerIdBoxed = pubkeyToId.get(observer);
        int observerId = observerIdBoxed == null ? -1 : observerIdBoxed;

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

        int[][] inputRaterIds = new int[n][];
        double[][] inputRatings = new double[n][];
        double[][] inputConfidences = new double[n][];
        int[] inputCounts = new int[n];

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
                Integer rateeId = pubkeyToId.get(grprIn.getRatee());
                if (rateeId == null) {
                    continue;
                }
                Integer raterId = pubkeyToId.get(grprIn.getRater());
                if (raterId == null) {
                    continue;
                }
                int r = rateeId;
                int idx = inputCounts[r];
                int[] rArr = inputRaterIds[r];
                if (rArr == null) {
                    rArr = new int[4];
                    inputRaterIds[r] = rArr;
                    inputRatings[r] = new double[4];
                    inputConfidences[r] = new double[4];
                } else if (idx == rArr.length) {
                    int newLen = rArr.length * 2;
                    inputRaterIds[r] = Arrays.copyOf(rArr, newLen);
                    inputRatings[r] = Arrays.copyOf(inputRatings[r], newLen);
                    inputConfidences[r] = Arrays.copyOf(inputConfidences[r], newLen);
                }
                inputRaterIds[r][idx] = raterId;
                inputRatings[r][idx] = grprIn.getRating();
                inputConfidences[r][idx] = grprIn.getConfidence();
                inputCounts[r] = idx + 1;
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

        for (int i = 0; i < n; i++) {
            int c = inputCounts[i];
            if (inputRaterIds[i] == null) {
                inputRaterIds[i] = new int[0];
                inputRatings[i] = new double[0];
                inputConfidences[i] = new double[0];
            } else if (c != inputRaterIds[i].length) {
                inputRaterIds[i] = Arrays.copyOf(inputRaterIds[i], c);
                inputRatings[i] = Arrays.copyOf(inputRatings[i], c);
                inputConfidences[i] = Arrays.copyOf(inputConfidences[i], c);
            }
        }

        double[] influence = new double[n];
        double[] averageScore = new double[n];
        double[] input = new double[n];
        double[] confidence = new double[n];
        if (observerId >= 0) {
            averageScore[observerId] = 1.0;
            input[observerId] = Double.POSITIVE_INFINITY;
            confidence[observerId] = 1.0;
            influence[observerId] = 1.0;
        }

        long algoStartTime = System.currentTimeMillis();
        int rounds = graperankAlgorithm(
                inputRaterIds, inputRatings, inputConfidences,
                influence, averageScore, input, confidence,
                observerId);
        long algoEndTime = System.currentTimeMillis();
        System.out.println("Algorithm took " + (algoEndTime - algoStartTime) / 1000.0 + " seconds");

        Map<String, ScoreCard> finalScorecards = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            String pk = idToPubkey[i];
            ScoreCard sc;
            if (i == observerId) {
                sc = new ScoreCard(observer, pk, averageScore[i], input[i], confidence[i], influence[i]);
            } else {
                double dist = userDistanceMap.getOrDefault(pk, 999.0);
                sc = new ScoreCard(observer, pk, dist);
                sc.setAverageScore(averageScore[i]);
                sc.setInput(input[i]);
                sc.setConfidence(confidence[i]);
                sc.setInfluence(influence[i]);
            }
            sc.setVerified(influence[i] >= Constants.DEFAULT_CUTOFF_OF_VALID_USER);
            finalScorecards.put(pk, sc);
        }

        System.out.println("Getting trusted followers for each pubkey...");

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

                finalScorecards,
                rounds,
                finalTime / 1000.0,
                relevantUsers.size() > 1);

    }

}