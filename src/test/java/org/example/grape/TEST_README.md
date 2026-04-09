# GrapeRank Test Suite

Test infrastructure for validating correctness and measuring performance of the GrapeRank algorithm without requiring Neo4j or Redis.

## How to Run

```bash
# Compile (with Maven when network is available)
mvn compile test-compile

# Correctness tests
java -cp target/classes:target/test-classes org.example.grape.GrapeRankCorrectnessTest

# Performance benchmark
java -cp target/classes:target/test-classes org.example.grape.GrapeRankBenchmark
```

## Strategy

The GrapeRank algorithm computes reputation/influence scores iteratively across a social graph. Testing it against a live Neo4j database is slow, fragile, and non-reproducible. Instead, these tests generate **synthetic graphs in-memory** that feed directly into `GrapeRankAlgorithm.graperankAlgorithm()`, bypassing the database layer entirely.

All graphs are **deterministic** (seeded `Random` instances), so results are identical across runs and platforms. This makes them safe for regression testing: if you refactor the algorithm and results change, a test will fail.

The test suite is split into two files with distinct goals:

| File | Goal | When to run |
|------|------|-------------|
| `GrapeRankCorrectnessTest` | Verify results stay correct | After every code change |
| `GrapeRankBenchmark` | Compare execution speed | When evaluating optimisations |

---

## File Overview

### `TestGraphGenerator.java` — Synthetic Graph Factory

Generates six graph topologies of increasing size and complexity. Each graph is returned as a `TestGraph` containing:
- Pre-built `Map<String, List<GrapeRankInput>>` (ratings keyed by ratee)
- Pre-built `Map<String, ScoreCard>` (initial scorecards with BFS hop distances)
- Edge counts (follows, mutes, reports)

The generator replicates the same rating/confidence logic used by `GrapeRankAlgorithm.getGrapeRankInputsOfRelationships()` and `initGrapeRankScorecards()`, so results are equivalent to what the real pipeline would produce.

#### Graph Topologies

| # | Graph | Users | Follows | Mutes | Reports | Seed | Purpose |
|---|-------|------:|--------:|------:|--------:|-----:|---------|
| 1 | **LinearChain** | 100 | 99 | 10 | 6 | 42 | Simplest possible graph to validate basic propagation |
| 2 | **StarGraph** | 501 | 635 | 10 | 30 | 123 | Tests hub-and-spoke influence distribution |
| 3 | **CommunityClusters** | 1,001 | 14,644 | 100 | 75 | 456 | Tests influence flow across community boundaries |
| 4 | **DenseSocialNetwork** | 5,000 | 97,156 | 7,346 | 2,217 | 789 | Realistic social graph with power-law degree distribution |
| 5 | **LargeScaleNetwork** | 20,000 | 290,121 | 15,167 | 4,428 | 2024 | Pure performance stress test at scale |
| 6 | **AdversarialNetwork** | 2,000 | 29,344 | 888 | 785 | 1337 | Tests algorithm's resilience to malicious actors |

#### Graph Descriptions

**1. LinearChain (100 users)**
A simple chain: `observer -> user1 -> user2 -> ... -> user99`. Every 10th user mutes the user 5 positions ahead; a few scattered reports. Tests that influence decays monotonically with distance from the observer and that the attenuation factor works correctly across hops.

**2. StarGraph (501 users)**
The observer directly follows 50 "hub" users. Each hub follows 9 "leaf" users. Additionally, ~10% of hub pairs have cross-follows, some hubs mute other hubs, and hubs randomly report leaves. Tests that direct follows from the observer create strong influence, and that second-hop users (leaves) receive attenuated but non-zero influence.

**3. CommunityClusters (1,001 users)**
Five communities of 200 users each. Within each community, every user follows 10-19 random peers (dense intra-community connectivity). Between communities, ~15% of users follow someone in the adjacent community (sparse bridges). Communities also mute and report members of non-adjacent communities. The observer follows 3 "leaders" per community. Tests that influence propagates within communities and crosses community boundaries through bridge follows.

**4. DenseSocialNetwork (5,000 users)**
A realistic social network with power-law degree distribution (preferential attachment via `Math.pow(random, 1.5)`). The observer follows 100 users. Each user follows 12-27 others with a bias toward popular (lower-ID) users. Approximately 50% of users mute 1-5 others, and ~10% of users file 1-8 reports. Tests algorithm behavior on a graph with realistic structural properties — skewed degree distribution, clustering, and a mix of all three relationship types at realistic ratios.

**5. LargeScaleNetwork (20,000 users)**
Structurally similar to DenseSocialNetwork but 4x larger: 200 observer follows, 8-21 follows per user, ~30% users muting, ~6% users reporting. This graph exists purely for performance benchmarking. It generates ~290K follow edges and ~20K negative signals, creating enough computational load to produce meaningful timing measurements.

**6. AdversarialNetwork (2,000 users)**
Explicitly splits users into 1,000 "good" and 1,000 "bad" actors. Good users follow each other densely and report bad users. Bad users follow each other, mute good users, and try to look legitimate by following some good users. Some bad users also adversarially report good users. The observer is in the good group and follows 50 good users. Tests that the algorithm correctly assigns higher influence to the good community and that adversarial mutes/reports from untrusted users don't corrupt the scores.

---

### `GrapeRankCorrectnessTest.java` — Correctness Test Suite

A standalone test runner (no JUnit required) that executes the algorithm on all six graphs and runs **77 checks** organized into five categories:

#### 1. Structural Invariants (6 checks per graph = 36 total)

These must hold for **any** correct GrapeRank implementation, regardless of optimisation strategy:

| Check | What it verifies | Why it matters |
|-------|-----------------|----------------|
| **Observer scorecard exists** | The observer has a scorecard in the result | Basic data integrity |
| **Observer influence = 1.0** | Observer always has full influence over their own view | Foundation of the algorithm — observer is the root of trust |
| **All influences >= 0** | No user has negative influence | `max(avgScore * confidence, 0)` guarantees this; a violation means the formula is broken |
| **All confidences in [0, 1]** | Confidence values stay within valid range | `convertInputToConfidence` is a CDF bounded by [0, 1]; violations indicate math errors |
| **Verified flag matches cutoff** | `verified == (influence >= 0.02)` | The verified flag must be consistent with the configured cutoff |
| **Influence formula holds** | `influence == max(averageScore * confidence, 0)` for all non-observer users | The core computation: if this fails, the algorithm loop has a bug |

An additional convergence check verifies the algorithm finishes within 1000 rounds.

#### 2. Graph-Specific Correctness (5 tests)

Higher-level behavioral properties that depend on graph structure:

| Test | Graph | What it checks |
|------|-------|---------------|
| **Linear chain decay** | LinearChain | Influence at hop *k* >= influence at hop *k+1* for the first 8 hops. Validates that attenuation works correctly in the simplest topology. |
| **Hub influence** | StarGraph | All 50 hubs (directly followed by observer) have positive influence and are marked verified. If the observer's follow confidence (0.5) doesn't propagate correctly, this fails. |
| **Community leaders verified** | CommunityClusters | The 3 leaders per community (directly followed by observer) have positive influence. Tests that the algorithm handles multiple disjoint trust paths. |
| **Good > bad average influence** | Adversarial | Average influence of good users exceeds average of bad users. The key test of the algorithm's sybil-resistance: bad users who are not in the observer's trust chain should not accumulate influence. |
| **More good verified than bad** | Adversarial | More good users pass the verification threshold than bad users. A weaker but important check — even if some bad users game the system, the majority should not. |

#### 3. Determinism (2 tests)

Rebuilds the LinearChain graph from scratch and reruns the algorithm. Compares round counts plus influence, averageScore, and confidence for every user at `1e-15` tolerance. Confirms that:
- Graph generation is deterministic (seeded RNG)
- Algorithm execution is deterministic (no non-deterministic iteration order effects)

This is critical: if the algorithm becomes non-deterministic (e.g., due to parallel HashMap iteration), this test will catch it.

#### 4. `convertInputToConfidence` Unit Tests (3 tests)

Isolated tests for the confidence conversion function `1 - e^(-input * -ln(rigor))`:

| Test | Assertion |
|------|-----------|
| **Zero input** | `convertInputToConfidence(0, 0.5) == 0.0` — no evidence means no confidence |
| **Large input** | `convertInputToConfidence(100, 0.5) > 0.99` — overwhelming evidence approaches certainty |
| **Monotonicity** | Confidence is non-decreasing as input grows from 0.1 to 10.0 — more evidence never reduces confidence |

#### 5. Fingerprint Regression (18 tests: 3 per graph)

Asserts **exact numeric values** captured from the current implementation:

| Graph | Rounds | Verified Users | Influence Sum |
|-------|-------:|---------------:|--------------:|
| LinearChain_100 | 3 | 2 | 1.2597406869 |
| StarGraph_500 | 4 | 51 | 15.9297270139 |
| CommunityClusters_1000 | 4 | 16 | 6.1170816899 |
| DenseSocialNetwork_5000 | 5 | 104 | 37.3203422750 |
| LargeScaleNetwork_20000 | 4 | 203 | 68.9590461545 |
| AdversarialNetwork_2000 | 5 | 63 | 21.5680374168 |

These are the strongest regression tests. If an optimization changes even a single user's influence score by more than `1e-6`, the influence sum will drift and the test will fail. **This is intentional** — you want to know when results change, even slightly.

When you intentionally change the algorithm (e.g., changing `GLOBAL_ATTENUATION_FACTOR`), update these values after confirming the new results are correct.

---

### `GrapeRankBenchmark.java` — Performance Benchmark

Measures execution time of the core `graperankAlgorithm()` method across all six graphs. Designed to produce stable, comparable numbers for evaluating optimisations.

#### Methodology

1. **Graph construction** — each graph is built once and timed separately, so you can see build overhead vs. algorithm time.

2. **Warm-up** — 2 untimed iterations per graph allow JIT compilation to stabilise hot paths. Without warm-up, the first run can be 2-10x slower due to interpretation overhead.

3. **Timed iterations** — 5 measured runs per graph. Between runs, scorecards are reset to their initial state (algorithm mutates them in-place).

4. **Statistics** — reports min, average, median, and max timings, plus derived users/second throughput. The median is most useful for comparison since it's resistant to GC pauses.

#### Output Sections

| Section | What it shows |
|---------|--------------|
| **Graph Construction** | Time to generate edges, compute BFS distances, and build GrapeRankInput/ScoreCard maps |
| **Algorithm Benchmark** | Core algorithm execution: iteration rounds, wall-clock timing statistics, throughput |
| **Memory** | Heap usage after all benchmarks (post-GC) |

#### How to Use for Optimization

1. Run the benchmark on the current code and save the output
2. Make your optimization
3. Run `GrapeRankCorrectnessTest` first — if fingerprints pass, results are unchanged
4. Run the benchmark again and compare median timings

The LargeScaleNetwork (20K users, 290K follows) is the most useful graph for performance work because it runs long enough (~200ms) to produce stable timings, while being large enough to expose algorithmic inefficiencies (O(n^2) vs O(n) behavior, cache misses, excessive object allocation, etc.).
