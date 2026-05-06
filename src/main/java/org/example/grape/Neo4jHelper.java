package org.example.grape;

import java.util.ArrayList;
import java.util.List;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.neo4j.driver.util.Pair;

public class Neo4jHelper {

    private final Driver driver;

    public Neo4jHelper() {
        String uri = System.getenv("NEO4J_URL");
        // Authentication credentials
        String username =System.getenv("NEO4J_USERNAME");
        String password = System.getenv("NEO4J_PASSWORD");

        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }

    public List<String> getUsersConnectedToObserver(String observer, Integer hopsLimit) {
        String hopsLimitStr = (hopsLimit != null) ? hopsLimit.toString() : "";

        String query = "MATCH (user:NostrUser {pubkey: $pubkey})-[:FOLLOWS*1.." + hopsLimitStr +
                "]->(other:NostrUser) " +
                "WHERE other <> user " +
                "RETURN DISTINCT elementId(other) AS node_id, other.pubkey AS pubkey";

        List<String> resultList = new ArrayList<>();

        try (Session session = driver.session()) {

            List<Record> result = session.readTransaction(tx -> {
                Result statementResult = tx.run(query, Values.parameters("pubkey", observer));
                return statementResult.list();
            });

            if (result != null && !result.isEmpty()) {
                String observerNodeId = getNodeIdByPubkey(observer); // Get observer node ID

                if (observerNodeId != null) {
                    resultList.add(observer);

                    for (Record record : result) {
                        String pubkey = record.get("pubkey").asString();
                        resultList.add(pubkey);
                    }
                }
            }
        }

        return resultList;
    }

    public String getNodeIdByPubkey(String pubkey) {
        String query = "MATCH (u:NostrUser {pubkey: $pubkey}) " +
                "RETURN elementId(u) AS node_id LIMIT 1";

        try (Session session = driver.session()) {
            Record result = session.readTransaction(tx -> {
                Result statementResult = tx.run(query, Values.parameters("pubkey", pubkey));
                return statementResult.single();
            });

            if (result != null) {
                return result.get("node_id").asString();
            } else {
                return null;
            }
        }
    }

    /**
     * Fetches outgoing FOLLOWS/REPORTS/MUTES, incoming FOLLOWS, and incoming REPORTS
     * relationships for a batch of users in a single Cypher round-trip. The query
     * tags each row with a direction marker ('OUT', 'IN_FOLLOW', 'IN_REPORT') so
     * results can be split into the three lists the GrapeRank algorithm expects.
     */
    public BatchedRelationships getAllRelationshipsBulk(List<String> pubkeys) {
        String query =
                "UNWIND $pubkeys AS pk " +
                "MATCH (u:NostrUser {pubkey: pk}) " +
                "CALL { " +
                "  WITH u " +
                "  MATCH (u)-[r:FOLLOWS|REPORTS|MUTES]->(t:NostrUser) " +
                "  RETURN u.pubkey AS source, type(r) AS relationship, t.pubkey AS target, 'OUT' AS dir " +
                "  UNION " +
                "  WITH u " +
                "  MATCH (s:NostrUser)-[r:FOLLOWS]->(u) " +
                "  RETURN s.pubkey AS source, type(r) AS relationship, u.pubkey AS target, 'IN_FOLLOW' AS dir " +
                "  UNION " +
                "  WITH u " +
                "  MATCH (s:NostrUser)-[r:REPORTS]->(u) " +
                "  RETURN s.pubkey AS source, type(r) AS relationship, u.pubkey AS target, 'IN_REPORT' AS dir " +
                "} " +
                "RETURN source, relationship, target, dir";

        List<RelationshipInfo> outgoing = new ArrayList<>();
        List<RelationshipInfo> incomingFollow = new ArrayList<>();
        List<RelationshipInfo> incomingReport = new ArrayList<>();

        try (Session session = driver.session()) {
            session.executeRead(tx -> {
                Result result = tx.run(query, Values.parameters("pubkeys", pubkeys));

                while (result.hasNext()) {
                    Record record = result.next();
                    RelationshipInfo info = new RelationshipInfo(
                            record.get("source").asString(),
                            record.get("relationship").asString(),
                            record.get("target").asString()
                    );
                    String dir = record.get("dir").asString();
                    switch (dir) {
                        case "OUT":
                            outgoing.add(info);
                            break;
                        case "IN_FOLLOW":
                            incomingFollow.add(info);
                            break;
                        case "IN_REPORT":
                            incomingReport.add(info);
                            break;
                    }
                }
                return null;
            });
        }

        return new BatchedRelationships(outgoing, incomingFollow, incomingReport);
    }

    @Deprecated
    public List<RelationshipInfo> getIncomingFollowRelationshipsBulk(List<String> pubkeys) {
        String query =
                "UNWIND $pubkeys AS pubkey " +
                "MATCH (u:NostrUser {pubkey: pubkey}) " +
                "MATCH (source:NostrUser)-[r:FOLLOWS]->(u) " +
                "RETURN source.pubkey AS source, " +
                "       type(r) AS relationship, " +
                "       u.pubkey AS target";

        List<RelationshipInfo> resultList = new ArrayList<>();

        try (Session session = driver.session()) {
            session.executeRead(tx -> {
                Result result = tx.run(query, Values.parameters("pubkeys", pubkeys));

                while (result.hasNext()) {
                    Record record = result.next();
                    resultList.add(new RelationshipInfo(
                            record.get("source").asString(),
                            record.get("relationship").asString(),
                            record.get("target").asString()
                    ));
                }
                return null;
            });
        }

        return resultList;
    }

    @Deprecated
    public List<RelationshipInfo> getIncomingReportRelationshipsBulk(List<String> pubkeys) {
        String query =
                "UNWIND $pubkeys AS pubkey " +
                "MATCH (u:NostrUser {pubkey: pubkey}) " +
                "MATCH (source:NostrUser)-[r:REPORTS]->(u) " +
                "RETURN source.pubkey AS source, " +
                "       type(r) AS relationship, " +
                "       u.pubkey AS target";

        List<RelationshipInfo> resultList = new ArrayList<>();

        try (Session session = driver.session()) {
            session.executeRead(tx -> {
                Result result = tx.run(query, Values.parameters("pubkeys", pubkeys));

                while (result.hasNext()) {
                    Record record = result.next();
                    resultList.add(new RelationshipInfo(
                            record.get("source").asString(),
                            record.get("relationship").asString(),
                            record.get("target").asString()
                    ));
                }
                return null;
            });
        }

        return resultList;
    }


    @Deprecated
    public List<RelationshipInfo> getOutgoingRelationshipsBulk(List<String> pubkeys) {
        String query =
                "UNWIND $pubkeys AS pubkey " +
                        "MATCH (u:NostrUser {pubkey: pubkey}) " +
                        "MATCH (u)-[r:FOLLOWS|REPORTS|MUTES]->(target:NostrUser) " +
                        "RETURN u.pubkey AS source, " +
                        "       type(r) AS relationship, " +
                        "       target.pubkey AS target";

        List<RelationshipInfo> resultList = new ArrayList<>();

        try (Session session = driver.session()) {
            session.executeRead(tx -> {
                Result result = tx.run(query, Values.parameters("pubkeys", pubkeys));

                while (result.hasNext()) {
                    Record record = result.next();
                    resultList.add(new RelationshipInfo(
                            record.get("source").asString(),
                            record.get("relationship").asString(),
                            record.get("target").asString()
                    ));
                }
                return null;
            });
        }

        return resultList;
    }

    public class DistanceInfo {
        private String sourcePubkey;
        private String targetPubkey;
        private double distance;

        public DistanceInfo(String sourcePubkey, String targetPubkey, double distance) {
            this.sourcePubkey = sourcePubkey;
            this.targetPubkey = targetPubkey;
            this.distance = distance;
        }


        public String getSourcePubkey() { return sourcePubkey; }
        public String getTargetPubkey() { return targetPubkey; }
        public double getDistance() { return distance; }

        @Override
        public String toString() {
            return "DistanceInfo{" +
                    "source='" + sourcePubkey + '\'' +
                    ", target='" + targetPubkey + '\'' +
                    ", distance='" + distance + '\'' +
                    '}';
        }
    }

    public static class BatchedRelationships {
        private final List<RelationshipInfo> outgoing;
        private final List<RelationshipInfo> incomingFollow;
        private final List<RelationshipInfo> incomingReport;

        public BatchedRelationships(List<RelationshipInfo> outgoing,
                                    List<RelationshipInfo> incomingFollow,
                                    List<RelationshipInfo> incomingReport) {
            this.outgoing = outgoing;
            this.incomingFollow = incomingFollow;
            this.incomingReport = incomingReport;
        }

        public List<RelationshipInfo> getOutgoing() { return outgoing; }
        public List<RelationshipInfo> getIncomingFollow() { return incomingFollow; }
        public List<RelationshipInfo> getIncomingReport() { return incomingReport; }
    }

    public static class RelationshipInfo {
        private String source;
        private String relationship;
        private String target;

        public RelationshipInfo(String source, String relationship, String target) {
            this.source = source;
            this.relationship = relationship;
            this.target = target;
        }

        public String getSource() {
            return source;
        }

        public String getRelationship() {
            return relationship;
        }

        public String getTarget() {
            return target;
        }

        @Override
        public String toString() {
            return "RelationshipInfo{" +
                    "source='" + source + '\'' +
                    ", relationship='" + relationship + '\'' +
                    ", target='" + target + '\'' +
                    '}';
        }
    }
}