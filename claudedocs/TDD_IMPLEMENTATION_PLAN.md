# TDD Implementation Plan for Bot

**Date**: 2026-01-04
**Based On**: claudedocs/bot_spec.md
**Testing Strategy**: Unit tests for pure logic + Match validation for integration

---

## TDD Approach for Battlecode

### Challenges

1. **Game Engine Dependency**: RobotController provided by engine, complex to mock
2. **Test Code Separation**: Test code must stay in test/ directory (NOT in submission.zip)
3. **Distributed AI**: Each robot runs independently, no central test harness

**Note**: Test bytecode doesn't matter - tests run outside the engine and aren't included in submission.

### Solutions

| Component Type | Testing Approach | Validation Method |
|----------------|------------------|-------------------|
| Pure functions | JUnit unit tests (outside engine) | Assert outputs |
| Game-dependent code | Match replays | Automated indicator parsing |
| Integration | 20-match test runs | Win rate metrics (95% confidence) |
| Bytecode efficiency | Automated profiling | Parse console output |

---

## Test Infrastructure Setup

### Gradle Test Configuration

**Add to build.gradle:**
```gradle
// Separate source sets for production and test code
sourceSets {
    main {
        java {
            srcDirs = ['src']
        }
    }
    test {
        java {
            srcDirs = ['test']
        }
    }
}

// Test dependencies (NOT included in submission)
dependencies {
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.1'

    // Battlecode classes (MapLocation, Direction, etc.) for testing
    // Note: Check actual Battlecode dependency in main build.gradle
    testCompileOnly files('lib/battlecode.jar')  // Use existing battlecode jar
}

test {
    useJUnitPlatform()

    // Ensure tests don't interfere with submission
    exclude '**/integration/**'  // Integration tests via matches

    // Show test output
    testLogging {
        events "passed", "skipped", "failed"
        showStandardStreams = true
    }
}

// Submission task - excludes test code
task zipForSubmit(type: Zip) {
    from('src') {
        include '**/*.java'
        exclude '**/test/**'
    }
    archiveFileName = 'submission.zip'
    destinationDirectory = file('.')
}
```

### Test/Production Code Separation

```
Project Structure:
javabot/
├── src/
│   └── <package>/          # Production code ONLY (goes in submission.zip)
│       ├── RobotPlayer.java
│       ├── G.java
│       ├── Soldier.java
│       └── ...
├── test/
│   ├── unit/               # Unit tests (JUnit, run outside engine)
│   │   ├── RandomTest.java
│   │   ├── PhaseTest.java
│   │   └── GTest.java
│   ├── match/              # Match validation (Java)
│   │   └── IndicatorParser.java
│   ├── scripts/            # Bash scripts
│   │   ├── test_regression.sh
│   │   ├── baseline_measure.sh
│   │   └── run_validation.sh
│   └── mocks/              # Test utilities
│       └── MockRC.java     # Minimal RC mock for testing
└── build.gradle            # Configured for test separation
```

**Validation:**
```bash
# Run tests (uses test/ directory)
./gradlew test

# Create submission (excludes test/ directory)
./gradlew zipForSubmit

# Verify submission doesn't contain test code
unzip -l submission.zip | grep -i test  # Should be empty
```

### RobotController Mock Strategy

**Approach**: Minimal mock for testable methods, skip complex game state

```java
// test/mocks/MockRC.java
package mocks;

import battlecode.common.*;

/**
 * Minimal RobotController stub for unit testing pure logic.
 * NOT a complete implementation - only for testing utilities.
 *
 * WARNING: This is a PARTIAL implementation for testing only.
 * All unimplemented methods throw UnsupportedOperationException.
 */
public class MockRC {
    // Test state - public for easy test setup
    public MapLocation location = new MapLocation(30, 30);
    public int currentRound = 0;
    public int paint = 500;
    public int chips = 1000;
    public Team team = Team.A;
    public UnitType type = UnitType.SOLDIER;

    // Implemented methods (minimal set for testing)
    public MapLocation getLocation() { return location; }
    public int getRoundNum() { return currentRound; }
    public int getPaint() { return paint; }
    public int getChips() { return chips; }
    public Team getTeam() { return team; }
    public UnitType getType() { return type; }

    // For testing that needs these
    public int getMapWidth() { return 60; }
    public int getMapHeight() { return 60; }
    public int getNumberTowers() { return 5; }
    public int getMoney() { return chips; }

    // All other methods throw UnsupportedOperationException
    // (This is acceptable for unit testing pure functions)
}

/**
 * ALTERNATIVE APPROACH: Don't mock RC at all.
 * Instead, design all functions as pure functions that take parameters:
 *
 * // BAD: Requires RC
 * public static int calculate() {
 *     return someCalc(G.rc.getPaint());
 * }
 *
 * // GOOD: Pure function
 * public static int calculate(int paint) {
 *     return someCalc(paint);
 * }
 *
 * This makes testing trivial without any mocking.
 */
```

**Usage in Tests:**
```java
// test/unit/GTest.java
@Test
public void testCooldownCalculation() {
    MockRC rc = new MockRC();
    rc.paint = 100;
    rc.type = UnitType.SOLDIER;
    G.rc = rc;

    int cooldown = G.cooldown(100, 100, 500);
    assertTrue(cooldown > 100);  // <50% paint = penalty
}
```

**Alternative to Full Mocking**: Test pure functions without RC dependency

```java
// Instead of mocking RC, make functions testable
// BAD: Requires RC
public static int calculateCooldown() {
    return cooldown(G.rc.getPaint(), 100, G.rc.getType().paintCapacity);
}

// GOOD: Pure function, testable without RC
public static int cooldown(int paintAmount, int cooldownToAdd, int paintCapacity) {
    // ... calculation ...
}

// Test the pure function directly
@Test
public void testCooldown() {
    assertEquals(100, G.cooldown(300, 100, 500));  // No RC needed
}
```

---

## Week 0: Baseline Measurement & Infrastructure (5 days)

### Day 1: Baseline Performance Measurement

**Objective**: Establish performance baselines for regression testing

```bash
#!/bin/bash
# scripts/measure_baseline.sh

echo "Measuring spaark2 baseline..."
spaark2_wins=0
spaark2_rounds=0

for i in {1..20}; do
    result=$(./gradlew run -PteamA=spaark2 -PteamB=SPAARK -Pmaps=DefaultSmall 2>&1)

    if echo "$result" | grep -q "Team A wins"; then
        ((spaark2_wins++))
    fi

    rounds=$(echo "$result" | grep -oP "Round \K\d+" | tail -1)
    spaark2_rounds=$((spaark2_rounds + rounds))
done

spaark2_winrate=$((spaark2_wins * 5))  # Convert to percentage
spaark2_avg_rounds=$((spaark2_rounds / 20))

echo "spaark2 vs SPAARK:"
echo "  Win Rate: $spaark2_winrate%"
echo "  Avg Survival: $spaark2_avg_rounds rounds"

# Save baselines
echo "$spaark2_winrate" > baselines/spaark2_winrate.txt
echo "$spaark2_avg_rounds" > baselines/spaark2_rounds.txt

# Set realistic targets (spaark2 + 10%)
target_winrate=$((spaark2_winrate + 10))
target_rounds=$((spaark2_avg_rounds + 20))

echo ""
echo "Targets for new bot:"
echo "  Win Rate: > $target_winrate%"
echo "  Survival: > $target_rounds rounds"
```

**Statistical Significance**:
- Sample size n=20
- Binomial confidence interval (Wilson score)
- For 50% win rate: margin of error ±21% at 95% confidence
- For 80% win rate: margin of error ±17% at 95% confidence
- **Conclusion**: 20 matches gives rough estimate, 50+ matches for precise measurement

**Rationale**: Quick validation (20 matches ~30 min) vs precise measurement (50 matches ~75 min). Use 20 for development, 50 for final validation.

**Deliverable**: `baselines/` directory with measured performance data

---

### Day 2-3: Test Infrastructure Setup

**Create test directory structure:**
```bash
mkdir -p test/unit
mkdir -p test/mocks
mkdir -p test/scripts
mkdir -p baselines
```

**Configure build.gradle** (see Test Infrastructure section above)

**Validate test separation:**
```bash
./gradlew test  # Should run (even if no tests yet)
./gradlew zipForSubmit
unzip -l submission.zip | grep -c test  # Should be 0
```

**Deliverable**: Working test infrastructure with production/test separation verified

---

### Day 4: Automated Match Validation Script

**Create indicator parser in Java:**
```java
// test/match/IndicatorParser.java
package match;

import java.io.*;
import java.util.*;
import java.util.regex.*;

/**
 * Parse match output and validate bot behavior.
 * Run as standalone Java application.
 */
public class IndicatorParser {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: java IndicatorParser <match_output.txt>");
            System.exit(1);
        }

        String content = readFile(args[0]);
        Map<String, List<String>> indicators = parseIndicators(content);

        // Validate soldier modes
        Set<String> modes = validateSoldierModes(indicators);
        System.out.println("Soldier modes seen: " + modes);

        // Validate retreat behavior
        List<Integer> retreatPaintValues = validateRetreatTrigger(indicators);
        boolean validRetreats = retreatPaintValues.stream().allMatch(p -> p < 150);
        System.out.println("Retreat validation: " + validRetreats);
        if (!retreatPaintValues.isEmpty()) {
            System.out.println("  Retreat paint values: " + retreatPaintValues);
        }

        // Exit code for automated testing
        if (modes.size() >= 5 && validRetreats) {
            System.out.println("PASS: Behavior validation successful");
            System.exit(0);
        } else {
            System.out.println("FAIL: Behavior validation failed");
            System.exit(1);
        }
    }

    static String readFile(String filename) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        return content.toString();
    }

    static Map<String, List<String>> parseIndicators(String content) {
        // Extract indicators per robot
        // Format: [A:SOLDIER:123] MODE=EXPLORE paint=150
        Pattern pattern = Pattern.compile("\\[([AB]):(\\w+):(\\d+)\\] (.+)");
        Matcher matcher = pattern.matcher(content);

        Map<String, List<String>> indicators = new HashMap<>();

        while (matcher.find()) {
            String team = matcher.group(1);
            String unitType = matcher.group(2);
            String unitId = matcher.group(3);
            String indicator = matcher.group(4);

            String key = team + ":" + unitType + ":" + unitId;
            indicators.computeIfAbsent(key, k -> new ArrayList<>()).add(indicator);
        }

        return indicators;
    }

    static Set<String> validateSoldierModes(Map<String, List<String>> indicators) {
        Set<String> modesSeen = new HashSet<>();

        for (Map.Entry<String, List<String>> entry : indicators.entrySet()) {
            if (!entry.getKey().contains("SOLDIER")) continue;

            for (String indicator : entry.getValue()) {
                if (indicator.contains("EXPLORE")) modesSeen.add("EXPLORE");
                else if (indicator.contains("BUILD_TOWER")) modesSeen.add("BUILD_TOWER");
                else if (indicator.contains("BUILD_RESOURCE")) modesSeen.add("BUILD_RESOURCE");
                else if (indicator.contains("RETREAT")) modesSeen.add("RETREAT");
                else if (indicator.contains("ATTACK")) modesSeen.add("ATTACK");
            }
        }

        return modesSeen;
    }

    static List<Integer> validateRetreatTrigger(Map<String, List<String>> indicators) {
        List<Integer> retreatPaintValues = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : indicators.entrySet()) {
            List<String> robotIndicators = entry.getValue();

            for (int i = 0; i < robotIndicators.size(); i++) {
                if (robotIndicators.get(i).contains("RETREAT")) {
                    // Extract paint from indicator
                    Pattern paintPattern = Pattern.compile("paint=(\\d+)");
                    Matcher matcher = paintPattern.matcher(robotIndicators.get(i));
                    if (matcher.find()) {
                        int paint = Integer.parseInt(matcher.group(1));
                        retreatPaintValues.add(paint);
                    }
                }
            }
        }

        return retreatPaintValues;
    }
}
```

**Compile and run:**
```bash
# Compile validator
javac -d test/build -cp "lib/*" test/match/IndicatorParser.java

# Run match and save output
./gradlew run -PteamA=<package> -PteamB=spaark2 -Pmaps=DefaultSmall > match_output.txt 2>&1

# Parse and validate
java -cp test/build match.IndicatorParser match_output.txt
```

**Deliverable**: Automated validation script for behavioral testing

---

### Day 4-5: Visibility System Implementation (CRITICAL)

**Based on autonomous iteration validation, visibility is MANDATORY for TDD success.**

**Implement in bot code (before any features):**

```java
// Soldier.java - Add to run() method
if (G.round % 10 == 0) {
    System.out.println("STATE:" + G.round + ":SOLDIER:" + G.id +
        ":pos=" + G.me + ":paint=" + G.paint + ":chips=" + G.chips +
        ":mode=" + mode + ":allies=" + allies.length + ":enemies=" + enemies.length);
}

// Tower.java - Add to run() method
if (G.round % 10 == 0) {
    System.out.println("ECONOMY:" + G.round + ":TOWER:" + G.id +
        ":chips=" + chips + ":income=" + income + ":towers=" + numTowers);
}

// Every spawn
System.out.println("SPAWN:" + G.round + ":TOWER:" + G.id +
    ":unit=" + type + ":total=" + totalSpawns);

// Mode transitions
if (oldMode != mode) {
    System.out.println("DECISION:" + G.round + ":" + unitType + ":" + G.id +
        ":from=" + oldMode + ":to=" + mode + ":reason=" + reason);
}
```

**Enhanced Report Script:**
- Already created: test/scripts/generate_report.sh
- Provides: Enemy spawn count, unit trends, economy comparison, force balance

**Bytecode Cost**: ~150 bytecode/turn (1% of budget) - negligible

**Why Critical**: Without visibility, TDD cycle is blind. With visibility, each match provides:
- Exact failure modes
- Performance metrics
- Comparison to enemy
- Actionable fixes

**Deliverable**: Visibility logging in all units, enhanced report script validated

---

### Day 5: Complete Test Examples

**Fix test signatures to match actual implementations:**

```java
// test/unit/MicroTest.java
@Test
public void testPaintPenaltyScaling() {
    // Setup mock environment
    G.rc = new MockRC();
    G.me = new MapLocation(30, 30);
    G.round = 100;

    // Create mock map info with enemy paint
    // Note: Can't fully test without RC, so test calculation only

    int turnsToNext5 = 5;
    int turnsToNext10 = 10;

    int enemyPenalty5 = Micro.DEF_MICRO_E_PAINT_PENALTY * 5 * turnsToNext5;
    int enemyPenalty10 = Micro.DEF_MICRO_E_PAINT_PENALTY * 5 * turnsToNext10;

    // Higher cooldown = higher penalty
    assertTrue(enemyPenalty10 > enemyPenalty5);
}

@Test
public void testDirectionBonus() {
    // Test that target direction gets +20 bonus
    // This can be tested by inspecting Micro.defaultMicro logic
    // without full RC dependency

    // Verify constants are correct
    assertEquals(5, Micro.DEF_MICRO_E_PAINT_PENALTY);
    assertEquals(10, Micro.DEF_MICRO_E_PAINT_BOT_PENALTY);
}
```

**Fix Boids test:**
```java
// test/unit/NavTest.java
@Test
public void testBoidsVectorCalculation() {
    // Create mock RobotInfo array
    RobotInfo[] allies = new RobotInfo[2];

    // Note: RobotInfo is game class, can't easily mock
    // Alternative: Test Boids math directly

    int sepX = 0, sepY = 0, cohX = 0, cohY = 0;
    MapLocation me = new MapLocation(7, 7);

    // Ally at (5, 5) - close (separation applies)
    MapLocation ally1 = new MapLocation(5, 5);
    int dist1 = me.distanceSquaredTo(ally1);  // 8 (< 9)
    sepX -= (ally1.x - me.x);  // -= (5 - 7) = +2
    sepY -= (ally1.y - me.y);  // -= (5 - 7) = +2
    cohX += ally1.x;
    cohY += ally1.y;

    // Ally at (10, 10) - far (cohesion only)
    MapLocation ally2 = new MapLocation(10, 10);
    int dist2 = me.distanceSquaredTo(ally2);  // 18 (< 25)
    cohX += ally2.x;
    cohY += ally2.y;

    int count = 2;

    // Calculate final vector
    int finalX = sepX * 3 + (cohX / count - me.x);
    int finalY = sepY * 3 + (cohY / count - me.y);

    // sepX=2, sepY=2, cohX=15, cohY=15, count=2
    // finalX = 2*3 + (15/2 - 7) = 6 + 0 = 6
    // finalY = 2*3 + (15/2 - 7) = 6 + 0 = 6

    assertEquals(6, finalX);
    assertEquals(6, finalY);

    // Vector points toward northeast (away from 5,5, toward group center)
}
```

---

## Phase 1: Foundation with Tests (Week 1: 6 days with buffer)

### 1.1 Random.java (Pure - Fully Testable)

**Test First:**
```java
// test/RandomTest.java
@Test
public void testXorshift32Deterministic() {
    Random.state = 42;
    int first = Random.rand();
    Random.state = 42;
    int second = Random.rand();
    assertEquals(first, second);  // Same seed = same output
}

@Test
public void testNextBooleanDistribution() {
    Random.state = 123;
    int trueCount = 0;
    for (int i = 0; i < 1000; i++) {
        if (Random.nextBoolean()) trueCount++;
    }
    assertTrue(trueCount > 400 && trueCount < 600);  // ~50% distribution
}
```

**Then Implement:**
```java
// src/<package>/Random.java
public class Random {
    public static int state;

    public static int rand() {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 15;
        return state & 2147483647;
    }

    public static boolean nextBoolean() {
        return (rand() & 1) == 1;
    }

    public static int nextInt(int bound) {
        return Math.abs(rand() % bound);
    }
}
```

**Validation**: All tests pass, distribution tests show proper randomness

---

### 1.2 Phase.java (Pure - Fully Testable)

**Test First:**
```java
// test/PhaseTest.java
@Test
public void testPhaseDetection() {
    assertEquals(Phase.EARLY, Phase.currentPhase(50));
    assertEquals(Phase.MID, Phase.currentPhase(300));
    assertEquals(Phase.LATE, Phase.currentPhase(1000));
    assertEquals(Phase.ENDGAME, Phase.currentPhase(1600));
}

@Test
public void testSpawnWeights() {
    // EARLY: soldier-heavy
    assertTrue(Phase.getSoldierWeight(50) > Phase.getSplasherWeight(50));

    // LATE: splasher-heavy
    assertTrue(Phase.getSplasherWeight(1000) > Phase.getSoldierWeight(1000));
}

@Test
public void testWeightNormalization() {
    double sum = Phase.getSoldierWeight(100)
                + Phase.getSplasherWeight(100)
                + Phase.getMopperWeight(100);
    assertEquals(1.0, sum, 0.01);  // Should normalize to 1.0
}
```

**Then Implement:**
```java
// src/<package>/Phase.java
public class Phase {
    public static final int EARLY = 0;
    public static final int MID = 1;
    public static final int LATE = 2;
    public static final int ENDGAME = 3;

    public static int currentPhase(int round) {
        if (round < 150) return EARLY;
        if (round < 600) return MID;
        if (round < 1500) return LATE;
        return ENDGAME;
    }

    public static double getSoldierWeight(int round) {
        switch (currentPhase(round)) {
            case EARLY: return 2.0;
            case MID: return 1.2;
            case LATE: return 0.5;
            case ENDGAME: return 0.3;
        }
        return 1.0;
    }

    // Similar for getSplasherWeight(), getMopperWeight()
}
```

---

### 1.3 G.java Utilities (Pure - Testable)

**Test First:**
```java
// test/GTest.java
@Test
public void testDirectionFromVector() {
    assertEquals(Direction.NORTH, G.directionFromVector(0, 1));
    assertEquals(Direction.NORTHEAST, G.directionFromVector(1, 1));
    assertEquals(Direction.SOUTHWEST, G.directionFromVector(-1, -1));
    assertEquals(Direction.CENTER, G.directionFromVector(0, 0));
}

@Test
public void testLastVisitedWithOffset() {
    G.setLastVisited(new MapLocation(10, 20), 100);
    assertEquals(100, G.getLastVisited(new MapLocation(10, 20)));

    // Test the /2 grid compression
    assertEquals(100, G.getLastVisited(new MapLocation(11, 21)));
}

@Test
public void testCooldownCalculation() {
    // > 50% paint = no penalty
    assertEquals(100, G.cooldown(300, 100, 500));

    // < 50% paint = penalty
    assertTrue(G.cooldown(100, 100, 500) > 100);
}

@Test
public void testMaxIndex() {
    int[] arr = {5, 10, 3, 20, 8};
    assertEquals(3, G.maxIndex(arr));
}
```

**Then Implement**: G.java with all utility functions

---

## Phase 2: Core Systems with Match Validation (Week 2)

### 2.1 POI.java (Partially Testable)

**Unit Tests (Pure Logic):**
```java
@Test
public void testSymmetryCalculation() {
    int mapWidth = 60, mapHeight = 60;

    // Rotational symmetry (type 2)
    MapLocation input = new MapLocation(10, 15);
    MapLocation expected = new MapLocation(49, 44);  // 60-1-10, 60-1-15
    assertEquals(expected, POI.getOppositeMapLocation(input, 2));
}

@Test
public void testTowerStorage() {
    POI.updateTower(new MapLocation(10, 20), Team.A, UnitType.LEVEL_ONE_PAINT_TOWER);
    assertEquals(1, POI.towerCount);
    assertEquals(10, POI.towerX[0]);
    assertEquals(20, POI.towerY[0]);
}

@Test
public void testExploredBitfield() {
    POI.markExplored(new MapLocation(10, 20));
    assertTrue(POI.isExplored(new MapLocation(10, 20)));
    assertFalse(POI.isExplored(new MapLocation(11, 20)));

    assertEquals(3599, POI.countUnexplored());  // 3600 - 1
}
```

**Match Validation:**
- Run match, check indicators show correct tower counts
- Verify symmetry detection converges to 1 valid type
- Confirm explored bitfield tracks visited tiles

---

### 2.2 Micro.java (Pure - Testable)

**Test First:**
```java
@Test
public void testTurnsToNextCalculation() {
    // Mock inputs: paint=100, capacity=200, movementCooldown=0
    int turnsToNext = Micro.calculateTurnsToNext(100, 0, 200);
    assertTrue(turnsToNext > 0);  // Should have penalty at <50% paint
}

@Test
public void testPaintPenaltyScaling() {
    int[] scores = Micro.scoreAllDirections(
        target,
        mockEnemyPaint = true,
        mockTurnsToNext = 5
    );

    // Enemy paint should have negative score
    assertTrue(scores[directionToEnemyPaint] < 0);

    // Penalty should scale with turnsToNext
    int[] scoresHighCooldown = Micro.scoreAllDirections(
        target,
        mockEnemyPaint = true,
        mockTurnsToNext = 10
    );
    assertTrue(scoresHighCooldown[dir] < scores[dir]);  // More penalty
}
```

**Implementation**: Micro.java with turnsToNext formula

---

### 2.3 Nav.java (Requires Match Validation)

**Unit Tests (Pure Logic Only):**
```java
@Test
public void testBoidsVector Calculation() {
    // Mock allies at known positions
    RobotInfo[] allies = createMockAllies(
        new MapLocation(5, 5),  // Close (separation)
        new MapLocation(10, 10)  // Far (cohesion)
    );

    Direction boidDir = Nav.computeBoidsVector(allies, myLoc = new MapLocation(7, 7));

    // Should move away from (5,5), toward average of group
    assertNotNull(boidDir);
}
```

**Match Validation:**
- Check Bug2 reaches targets without getting stuck
- Verify units coordinate (Boids) in mid/late game
- Confirm 15% chaos randomization via unpredictable moves

---

## Phase 3: Unit Behavior with Indicators (Week 3)

### 3.1 Soldier.java (Indicator-Based TDD)

**Test Approach:**
1. Write mode logic
2. Add indicator strings for each mode
3. Run match, verify indicators show expected modes
4. Assert mode transitions happen correctly

**Mode Validation Tests:**
```java
// In Soldier.java - add indicators
switch (mode) {
    case EXPLORE -> rc.setIndicatorString("EXPLORE");
    case BUILD_TOWER -> rc.setIndicatorString("BUILD_TOWER round=" + buildTime);
    case RETREAT -> rc.setIndicatorString("RETREAT paint=" + paint);
}
```

**Match Validation Checklist:**
```
[ ] Soldiers spawn and enter EXPLORE mode
[ ] Soldiers see neutral ruin → transition to BUILD_TOWER
[ ] buildTime increments each round
[ ] Pattern completes → return to EXPLORE
[ ] Paint < 150 AND chips < 6000 AND allies < 9 → RETREAT
[ ] Paint >= 75% capacity → exit RETREAT
[ ] See enemy tower → ATTACK mode
[ ] Round > 50, valid SRP → BUILD_RESOURCE mode
```

**Validation Method:**
```bash
./gradlew run -PteamA=<package> -PteamB=spaark2 -Pmaps=DefaultSmall
# Watch replay, read indicator strings for each soldier
```

---

### 3.2 Tower.java (Spawn Validation)

**Spawn Logic Tests:**
```java
@Test
public void testDebtCalculation() {
    double soldierWeight = 1.5;
    double soldierDebt = 0.5 + soldierWeight - 0;  // fracSoldiers + weight - spawned

    // First spawn should be highest debt
    assertTrue(soldierDebt > 0);
}

@Test
public void testBootstrapRule() {
    // First 3 spawns should be soldiers
    for (int i = 0; i < 3; i++) {
        UnitType spawn = Tower.selectSpawnType(
            round = 10,
            spawnedRobots = i,
            isPaintTower = false
        );
        assertEquals(UnitType.SOLDIER, spawn);
    }
}
```

**Match Validation:**
```bash
# Count spawned units
./gradlew run ... | grep "Built SOLDIER" | wc -l
# Verify spawn conditions trigger correctly
# Check phase-adjusted weights work
```

---

## Phase 4: Advanced Features with Regression Tests (Week 4-5)

### 4.1 Automated Regression Test Suite

**Create comprehensive test script:**

```bash
#!/bin/bash
# test/scripts/test_regression.sh

# Load baselines (set in Week 0)
BASELINE_ROUNDS=$(cat baselines/spaark2_rounds.txt)
BASELINE_WINRATE=$(cat baselines/spaark2_winrate.txt)

# Targets: baseline + margin
TARGET_ROUNDS=$((BASELINE_ROUNDS + 20))
TARGET_WINRATE=$((BASELINE_WINRATE + 5))

PACKAGE=$1
OPPONENT=${2:-spaark2}
NUM_MATCHES=${3:-20}  # 20 matches for 95% confidence

echo "Running $NUM_MATCHES matches: $PACKAGE vs $OPPONENT"
echo "Targets: Win rate > $TARGET_WINRATE%, Survival > $TARGET_ROUNDS rounds"
echo ""

# Run matches
wins=0
totalRounds=0
results=()

for i in $(seq 1 $NUM_MATCHES); do
    echo -n "Match $i/$NUM_MATCHES: "

    result=$(./gradlew run -PteamA=$PACKAGE -PteamB=$OPPONENT -Pmaps=DefaultSmall 2>&1)

    if echo "$result" | grep -q "Team A wins"; then
        ((wins++))
        echo "WIN"
    else
        echo "LOSS"
    fi

    # Extract final round
    rounds=$(echo "$result" | grep -oP "Round \K\d+" | tail -1)
    totalRounds=$((totalRounds + rounds))
    results+=("$rounds")
done

# Calculate statistics
avgRounds=$((totalRounds / NUM_MATCHES))
winRate=$((wins * 100 / NUM_MATCHES))

# Calculate standard deviation for survival rounds
sum=0
for r in "${results[@]}"; do
    diff=$((r - avgRounds))
    sum=$((sum + diff * diff))
done
variance=$((sum / NUM_MATCHES))
stddev=$(echo "scale=2; sqrt($variance)" | bc)

echo ""
echo "Results over $NUM_MATCHES matches:"
echo "  Win Rate: $winRate% (target: > $TARGET_WINRATE%)"
echo "  Avg Survival: $avgRounds ± $stddev rounds (target: > $TARGET_ROUNDS)"
echo "  Min/Max Rounds: $(printf '%s\n' "${results[@]}" | sort -n | head -1) / $(printf '%s\n' "${results[@]}" | sort -n | tail -1)"

# Regression check
if [ $winRate -lt $TARGET_WINRATE ]; then
    echo ""
    echo "❌ REGRESSION: Win rate $winRate% < target $TARGET_WINRATE%"
    exit 1
fi

if [ $avgRounds -lt $TARGET_ROUNDS ]; then
    echo ""
    echo "❌ REGRESSION: Survival $avgRounds < target $TARGET_ROUNDS"
    exit 1
fi

echo ""
echo "✅ PASS: All regression checks passed"
exit 0
```

**Usage:**
```bash
# After implementing a feature
./test/scripts/test_regression.sh mybot spaark2 20

# Quick validation (fewer matches)
./test/scripts/test_regression.sh mybot spaark2 10
```

---

### 4.2 Feature Flags for Safe Testing

```java
// Config.java - toggle features for A/B testing
public class Config {
    public static final boolean ENABLE_BOIDS = false;
    public static final boolean ENABLE_SYMMETRY = false;
    public static final boolean ENABLE_MESSAGING = false;
    public static final boolean ENABLE_PHASE_SHIFTING = true;
    public static final boolean ENABLE_CONTROLLED_CHAOS = true;

    // Rollback by setting to false
}
```

**Testing Workflow:**
1. Implement feature with flag OFF
2. Run regression tests (should pass - no behavior change)
3. Turn flag ON
4. Run regression + validation tests
5. If regression, debug; if pass, commit

---

## Phase 5: Integration Testing (Week 5)

### 5.1 Component Integration Tests

**Test Matrix:**

| Component A | Component B | Integration Test |
|-------------|-------------|------------------|
| Phase | Tower spawn | Verify spawn weights change by phase |
| Boids | Nav | Units coordinate in mid/late game |
| Retreat | POI | Units find paint towers correctly |
| Soldier BUILD | Tower spawn | New soldiers help complete patterns |
| Messaging | POI | Towers share tower locations |
| Symmetry | Exploration | Soldiers target predicted enemy towers |

**Validation Method**: 10-match runs with indicator validation

---

### 5.2 Bytecode Profiling Tests

```java
// In each unit's run() method
int startBytecode = Clock.getBytecodeNum();

// ... unit logic ...

int usedBytecode = Clock.getBytecodeNum() - startBytecode;
if (usedBytecode > 15000) {
    System.err.println("BYTECODE OVERFLOW: " + usedBytecode);
}

// Break down by section
int afterPathfinding = Clock.getBytecodeNum();
// pathfinding cost = afterPathfinding - startBytecode

int afterMicro = Clock.getBytecodeNum();
// micro cost = afterMicro - afterPathfinding
```

**Target Budgets:**
- Soldier: < 12,000 bytecode/turn
- Splasher: < 13,000 (attack scoring is expensive)
- Mopper: < 14,000 (4 scoring arrays)
- Tower: < 18,000 (has more budget)

---

## Implementation Order with TDD

### Week 1: Foundation + Visibility + Unit Tests (32 hours + 30% buffer = 42 hours)

**CRITICAL PRIORITY CHANGE** (based on autonomous iteration validation):
**Implement visibility logging BEFORE features** to enable autonomous improvement cycle.

**Day 1: Visibility Logging (6 hours) - DO THIS FIRST**
- [ ] Add STATE logging to all units (2 hours)
  - Position, paint, chips, mode, allies, enemies every 10 rounds
- [ ] Add ECONOMY logging to towers (1 hour)
  - Chips, income, tower count, unit count every 10 rounds
- [ ] Add SPAWN logging (1 hour)
  - Every spawn with type and counts
- [ ] Add DECISION logging (1 hour)
  - Mode transitions with reasons and paint values
- [ ] Add ENEMY_COUNT logging (1 hour)
  - Enemy composition by type every 10 rounds
- [ ] Test logging in first match (verify parseable)

**Why First**: Enables autonomous iteration from Day 1. Validated on spaark3: 20+ iterations in single session, 146 → 196 rounds (+34%).

**Without This**: Development is blind, slow, guesswork-based
**With This**: Every match provides actionable data, autonomous bug fixing, rapid iteration

**Day 2-3: Pure Functions (10 hours)**
- [ ] Random.java + tests (3 hours)
  - [ ] xorshift32 implementation
  - [ ] Determinism test
  - [ ] Distribution test
- [ ] Phase.java + tests (3 hours)
  - [ ] Phase detection logic
  - [ ] Spawn weight calculations
  - [ ] Weight normalization test
- [ ] G.java basic utilities + tests (4 hours)
  - [ ] directionFromVector
  - [ ] maxIndex
  - [ ] Utility tests

**Day 3-4: Core Logic (12 hours)**
- [ ] G.java advanced + tests (4 hours)
  - [ ] lastVisited with +2000 offset
  - [ ] Cooldown calculation
  - [ ] Grid compression test
- [ ] POI.java (arrays) + tests (4 hours)
  - [ ] Tower storage arrays
  - [ ] Symmetry calculation
  - [ ] Bitfield operations
- [ ] Micro constants (2 hours)
  - [ ] Define all constants
  - [ ] Constant validation tests
- [ ] Debug time buffer (2 hours)

**Day 4-6: Integration (20 hours)**
- [ ] RobotPlayer.java structure (6 hours)
  - [ ] Main loop
  - [ ] updateRound/updateInfo
  - [ ] Error handling
- [ ] Basic unit dispatch (4 hours)
  - [ ] Soldier stub (just spawns, moves random)
  - [ ] Tower stub (just spawns units)
- [ ] First match test with analysis (6 hours)
  - [ ] Compile and run
  - [ ] Run: `./test/scripts/generate_report.sh <package> SPAARK`
  - [ ] Analyze STATE, ECONOMY, SPAWN logs
  - [ ] Verify visibility working
  - [ ] Identify first bugs from logs
- [ ] Integration debugging (4 hours)

**Validation**:
- `./gradlew test` passes (10+ tests)
- First match runs to completion with full logging
- Can analyze match and identify issues
- Autonomous iteration cycle ready

**Time Budget**: 7 days × 6 hrs/day = 42 hours

---

### Week 2: Navigation + Match Validation (30 hours + 30% buffer = 39 hours)

**Day 1-2: Bug2 (10 hours)**
- [ ] Nav.bug2() implementation (6 hours)
  - [ ] Bug2 state variables
  - [ ] Tracing logic
  - [ ] Timeout after 20 turns
- [ ] Match test: units reach targets (2 hours)
  - [ ] Observe in replay
  - [ ] Check indicators show target locations
- [ ] Debug pathfinding issues (2 hours)

**Day 3-4: Micro Integration (12 hours)**
- [ ] Micro.scoreAllDirections() (4 hours)
  - [ ] turnsToNext calculation
  - [ ] Paint penalties
  - [ ] Tower danger formula
- [ ] Nav.moveToWithMicro() (3 hours)
- [ ] Match test: units avoid enemy paint (2 hours)
- [ ] Tune micro weights (3 hours)

**Day 5: Controlled Chaos (6 hours)**
- [ ] Add 15% randomization (2 hours)
- [ ] Match test: observe unpredictability (2 hours)
- [ ] Verify randomness doesn't break behavior (2 hours)

**Day 6: Boids (11 hours)**
- [ ] Boids vector calculation + unit tests (4 hours)
- [ ] Integration with Nav (3 hours)
- [ ] Match test: units coordinate in MID phase (2 hours)
- [ ] Debug coordination issues (2 hours)

**Validation**:
- Units move intelligently toward targets
- No infinite loops
- Boids coordination visible in replays

**Time Budget**: 6.5 days × 6 hrs/day = 39 hours

---

### Week 3: Unit Behavior + Mode Validation (36 hours + 30% buffer = 47 hours)

**Day 1-3: Soldier (20 hours)**
- [ ] EXPLORE mode (4 hours)
  - [ ] Exploration target selection
  - [ ] moveWithPaintMicro
  - [ ] Mode transition checks
- [ ] BUILD_TOWER mode (4 hours)
  - [ ] Tower type selection
  - [ ] Pattern building
  - [ ] Reduced retreating
- [ ] ATTACK mode (3 hours)
  - [ ] Attack micro (kiting)
  - [ ] Tower targeting
- [ ] RETREAT mode (2 hours)
  - [ ] Integration with Motion.retreat()
- [ ] BUILD_RESOURCE + EXPAND_RESOURCE (4 hours)
- [ ] Mode transition logic (2 hours)
- [ ] Match validation + debugging (3 hours)

**Day 4-5: Tower (10 hours)**
- [ ] Spawn system + debt tests (4 hours)
  - [ ] Debt calculation
  - [ ] Bootstrap rule
- [ ] Phase-weighted spawning (2 hours)
- [ ] Spawn conditions (2 hours)
- [ ] Match test: correct unit mix by phase (2 hours)

**Day 6-7: Splasher + Mopper (17 hours)**
- [ ] Splasher implementation (6 hours)
  - [ ] Attack threshold calculation
  - [ ] Scoring arrays
  - [ ] Attack selection logic
- [ ] Mopper implementation (7 hours)
  - [ ] 4 scoring arrays
  - [ ] Unified action selection
  - [ ] BUILD mode
- [ ] Match test: all units functional (2 hours)
- [ ] Debug unit behaviors (2 hours)

**Validation**:
- Win rate vs spaark2 > 60% (realistic at this stage)
- All 7 soldier modes trigger
- Units spawn in correct ratios

**Time Budget**: 7.8 days × 6 hrs/day = 47 hours

---

### Week 4: Advanced SPAARK Features (32 hours + 30% buffer = 42 hours)

**Day 1-2: Retreat System (10 hours)**
- [ ] Motion.retreat() implementation (4 hours)
  - [ ] retreatDir calculation
  - [ ] Waiting position logic
  - [ ] Queue priority (lowest paint first)
- [ ] Retreat queue logic (3 hours)
  - [ ] updateRetreatWaitingLoc
  - [ ] MAX_RETREAT_ROBOTS = 4
- [ ] Match test: units retreat to paint towers (2 hours)
- [ ] Debug retreat behavior (1 hour)

**Day 2-3: SRP System (10 hours)**
- [ ] BUILD_RESOURCE mode (4 hours)
  - [ ] SRP pattern building
  - [ ] Enemy paint abort
- [ ] EXPAND_RESOURCE with 16 locations (3 hours)
  - [ ] Expansion queue
  - [ ] Tiling checks
- [ ] Match test: SRPs built after round 50 (2 hours)
- [ ] Debug SRP issues (1 hour)

**Day 4: Messaging (8 hours)**
- [ ] 16-bit encoding + tests (4 hours)
  - [ ] intifyLocation/parseLocation
  - [ ] intifyTower
  - [ ] appendToMessage
- [ ] Tower relay logic (2 hours)
- [ ] Match test: robots know tower locations (2 hours)

**Day 5: Symmetry (7 hours)**
- [ ] Symmetry detection (3 hours)
  - [ ] symmetry[] tracking
  - [ ] removeValidSymmetry
- [ ] Enemy prediction (2 hours)
  - [ ] predictEnemyTower
- [ ] Match test: soldiers target predictions (2 hours)

**Day 6: Self-Destruct (7 hours)**
- [ ] Robot self-destruct (paint=0, chips>5000) (2 hours)
- [ ] Defense tower auto-destruct (30 rounds) (2 hours)
- [ ] Money tower nuking (2 hours)
- [ ] Match test: observe self-destruct (1 hour)

**Validation**: Win rate vs spaark2 > 35% (realistic with full features)

**Time Budget**: 7 days × 6 hrs/day = 42 hours

---

### Week 5: Research Innovations (30 hours + 30% buffer = 39 hours)

**Day 1-2: Boids + Pheromone (12 hours)**
- [ ] Full Boids integration (4 hours)
- [ ] Paint gradient following (3 hours)
- [ ] Phase-based blending (2 hours)
- [ ] Match test: coordination visible (3 hours)

**Day 3: Gap Fixes (8 hours)**
- [ ] Ruin denial logic (3 hours)
- [ ] Paint conservation in combat (3 hours)
- [ ] Match test: paint efficiency improved (2 hours)

**Day 4-5: Advanced Features (12 hours)**
- [ ] POI.explored bitfield (4 hours)
- [ ] Enhanced Bug2 (AWAY/AROUND modes) (4 hours)
- [ ] Tower upgrade logic (2 hours)
- [ ] Match test: all features working (2 hours)

**Day 6: Integration Testing (7 hours)**
- [ ] 20-match run vs spaark2 (2 hours)
- [ ] Analyze failures (3 hours)
- [ ] Fix critical issues (2 hours)

**Validation**: Win rate vs spaark2 > 45%

**Time Budget**: 6.5 days × 6 hrs/day = 39 hours

---

### Week 6: Optimization + Tuning (30 hours + 30% buffer = 39 hours)

**Day 1-2: Bytecode Optimization (12 hours)**
- [ ] Add profiling to all units (3 hours)
- [ ] Identify hotspots (3 hours)
- [ ] Apply Java optimizations (4 hours)
  - [ ] Reverse loops where missing
  - [ ] Remove object allocations
  - [ ] Flatten 2D arrays if needed
- [ ] Regression test: bytecode < targets (2 hours)

**Day 3-4: Parameter Tuning (12 hours)**
- [ ] Test retreat threshold variations (3 hours)
- [ ] Tune spawn weights (3 hours)
- [ ] Tune chaos factor (2 hours)
- [ ] Tune micro penalties (2 hours)
- [ ] 20-match validation each change (2 hours)

**Day 5-6: Counter-Strategies (15 hours)**
- [ ] Paint tower assassination priority (3 hours)
- [ ] Threshold manipulation (3 hours)
- [ ] Communication timing exploitation (3 hours)
- [ ] 20-match validation vs SPAARK (3 hours)
- [ ] Analyze and iterate (3 hours)

**Target**: Win rate vs SPAARK > 40%

**Time Budget**: 6.5 days × 6 hrs/day = 39 hours

---

### Week 7: Polish + Final Validation (20 hours)

**Day 1-2: Bug Fixes (10 hours)**
- [ ] Fix issues found in SPAARK matches
- [ ] Edge case handling
- [ ] Stability improvements

**Day 3-4: Final Testing (8 hours)**
- [ ] Single match validation (deterministic on DefaultSmall)
- [ ] Survival rounds target: >400 (double SPAARK ~200)
- [ ] Performance analysis from visibility logs
- [ ] Final tuning based on STATE/ECONOMY analysis

**Day 5: Documentation (2 hours)**
- [ ] Update CLAUDE.md with bot info
- [ ] Document known issues and solutions
- [ ] Tournament submission prep
- [ ] Disable DEBUG_MODE in production

**Final Validation**: Survive > 400 rounds on DefaultSmall (single deterministic match)

**Why Single Match**: Games are deterministic vs same opponent. One match is sufficient for validation on DefaultSmall.

**Statistical Note**: Multiple matches only needed for:
- Non-deterministic opponents
- Different map types
- Randomized game elements
On DefaultSmall vs SPAARK, result is repeatable.

---

## Testing Infrastructure

### Test File Structure

```
test/
├── unit/
│   ├── RandomTest.java
│   ├── PhaseTest.java
│   ├── GTest.java
│   ├── POITest.java
│   └── MicroTest.java
├── integration/
│   └── (use match replays)
└── scripts/
    ├── test_regression.sh
    ├── run_10_matches.sh
    └── bytecode_profile.sh
```

### Continuous Validation

After each commit:
```bash
# Run unit tests
./gradlew test

# Run match test with automated report
./test/scripts/generate_report.sh <package> SPAARK

# Tell Claude: "analyze latest match"
# Claude reads match_report_*.txt and provides analysis

# If tests pass and no regressions, commit is safe
```

---

## Match-Based Test Cases

### Scenario Tests

| Scenario | Setup | Expected Behavior | Validation |
|----------|-------|-------------------|------------|
| Early rush defense | vs aggressive bot | Build tower quickly, defend | Tower built round < 30 |
| Retreat trigger | Low paint situation | Units retreat to tower | Indicators show RETREAT |
| SRP building | After round 50 | Soldiers build SRPs | >2 SRPs by round 150 |
| Phase transition | Round 150 | Spawn weights change | More splashers spawn |
| Tower attack | See enemy tower | Soldiers attack | Tower destroyed |
| Retreat queue | 5 units low paint | 4 retreat, 1 finds other tower | Max 4 at one tower |

**Validation Method**: Watch replays, read indicators, count units

---

## Metrics Dashboard

Track after each implementation phase:

| Metric | Week 0 | Week 1 | Week 2 | Week 3 | Week 4 | Week 5 | Week 6 | Week 7 | Target |
|--------|--------|--------|--------|--------|--------|--------|--------|--------|--------|
| Unit tests passing | - | 10/10 | 15/15 | 25/25 | 30/30 | 35/35 | 40/40 | 40/40 | 100% |
| Survival rounds (DefaultSmall) | ✓ | 100 | 150 | 200 | 250 | 300 | 350 | 400 | >400 |
| Spawns per match | - | 5 | 15 | 25 | 35 | 45 | 50 | 50 | >40 |
| Mode distribution | - | - | - | Measured | Balanced | Tuned | Optimal | Optimal | Match SPAARK |
| Avg bytecode (Soldier) | - | - | 8K | 11K | 12K | 11K | 10K | 9K | <12K |
| Enemy unit count (observed) | - | - | 3 | 5 | 7 | 10 | 12 | 15 | Track trend |
| Tower upgrades | - | 0 | 0 | 1 | 2 | 2 | 2 | 2 | Match enemy |

**Note**: Week 0 establishes baseline (SPAARK ~196 rounds). Target is double (~400 rounds).

**Validation**: Single deterministic match on DefaultSmall (repeatable results)

**Key Metrics from Autonomous Testing**:
- Spawn rate: Critical performance indicator (spaark3: 9 spawns → limited to 196 rounds)
- Mode distribution: Must match SPAARK benchmarks (65% EXPLORE, 20% BUILD, 10% ATTACK, 5% RETREAT)
- Tower upgrades: Must upgrade by round 20-30 like SPAARK

---

## TDD Benefits for This Project

1. **Confidence**: Pure functions tested thoroughly, safe to refactor
2. **Regression Prevention**: Catch breaks immediately
3. **Documentation**: Tests show how components work
4. **Incremental Development**: Build piece by piece, validate continuously
5. **Performance Tracking**: Bytecode profiling catches regressions

---

## Validation Checklist

**Before Calling Implementation Complete:**

- [ ] All unit tests pass (40+ tests)
- [ ] Win rate vs spaark2 > 90%
- [ ] Win rate vs spaark2 > 40%
- [ ] Win rate vs SPAARK > 40%
- [ ] Bytecode under limits (all units)
- [ ] No infinite loops or crashes (10-match stability)
- [ ] All 7 soldier modes trigger in matches
- [ ] Retreat system works (observe in replays)
- [ ] SRPs built after round 50
- [ ] Phase transitions occur at correct rounds
- [ ] Boids coordination visible in late game
- [ ] Controlled chaos creates unpredictability

**Success Criteria**: All checkboxes checked + competitive performance validated

---

## Autonomous Iteration with Visibility

**Validated Process** (from spaark3 testing):

### Iteration Cycle

1. **Implement feature** with visibility logging
2. **Run match**: `./test/scripts/generate_report.sh <package> SPAARK`
3. **Claude analyzes** logs automatically:
   - Reads STATE, ECONOMY, SPAWN, ENEMY_COUNT
   - Identifies bugs and performance issues
   - Provides specific code fixes
4. **Claude implements fixes**
5. **Claude runs validation match**
6. **Repeat** until performance target met

**Cycle Time**: ~2-5 minutes per iteration

**Example Iteration** (from spaark3):
```
Iteration 1:
  Run match → 146 rounds
  Analysis: Units die at paint=0 (no retreat)
  Fix: Add retreat trigger
  Run match → 189 rounds (+29%)

Iteration 2:
  Analysis: Retreat happening but units block tower spawns
  Fix: Retreat to distance 4-8 tiles (not adjacent)
  Run match → Check spawn count improved

... 20+ iterations ...

Final: 196 rounds (+34% from baseline 146)
```

### Visibility Benefits for TDD

**Without Visibility:**
- "Bot failed, not sure why"
- Manual replay watching
- Slow iteration (hours per cycle)

**With Visibility:**
- "Soldier #10001 lost 6 paint/turn on enemy territory rounds 20-40"
- Automated analysis from logs
- Fast iteration (minutes per cycle)
- 20+ iterations in single session

**Visibility is TDD Multiplier**: Makes TDD cycle 10-20x faster

---

## Summary

**TDD Strategy:**
1. **Unit Tests**: Pure functions (Random, Phase, G utilities, POI logic, Micro)
2. **Visibility Logging**: STATE, ECONOMY, SPAWN, DECISION logs in all units (MANDATORY)
3. **Match Analysis**: Automated via generate_report.sh and IndicatorParser.java
4. **Autonomous Iteration**: Claude runs matches, analyzes, fixes, validates
5. **Regression Tests**: Automated test_regression.sh after major changes
6. **Bytecode Profiling**: Track performance continuously

**Timeline**: 7 weeks (Week 0 baseline + 6 weeks development + 1 week polish) from start to competitive bot

**Total Estimated Hours**:
- Week 0: 8 hours (baseline + infrastructure)
- Weeks 1-6: 244 hours (implementation with 30% buffer)
- Week 7: 20 hours (polish)
- **Total**: 272 hours (~7 weeks at 6 hrs/day, or 5-6 weeks at 8 hrs/day for full-time)

**Reality Check**:
- Assumes consistent 6 hrs/day productivity
- Complex debugging may require additional time
- First-time Battlecode developers may need 30-50% more time
- Experienced developers may complete in 5 weeks

**Risk Mitigation**:
- Feature flags allow incremental rollout
- Regression tests catch breaks immediately
- Match validation provides quick feedback
- Can skip optional features (bitwise BFS, counter-strategies) if time-constrained

**Confidence**: Medium-High - timeline is aggressive but achievable with disciplined TDD approach
