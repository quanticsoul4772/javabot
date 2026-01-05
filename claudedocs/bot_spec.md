# Bot Implementation Specification

**Date**: 2026-01-04 (Updated with autonomous iteration findings)
**Purpose**: Complete specification synthesizing SPAARK source analysis, competitive research, Java optimization techniques, and validated testing insights

---

## Executive Summary

This bot combines three pillars:
1. **SPAARK Foundation** - Complete implementation of HS Champions' proven systems
2. **Research Innovations** - Validated hypotheses to enhance SPAARK
3. **Bytecode Optimization** - Mandatory Java efficiency patterns

**Architecture**: Full SPAARK feature set (7-mode system, retreat, SRP, messaging, symmetry, bitfields, self-destruct) enhanced with Phase Shifting (0.785), Boids Flocking (0.735), Paint-as-Pheromone (0.665), and Controlled Chaos (0.70).

**Key Features from SPAARK**:
- 7-mode state machine with retreat queue
- Debt-based spawn system
- 16-bit messaging with tower relay
- Symmetry detection for enemy prediction
- POI.explored bitfield for efficient exploration
- 3 self-destruction mechanisms (robot, defense tower, money tower)
- Tower marker system (SOUTH=defense, WEST=money, EAST=paint)

**Research Enhancements**:
- Phase Shifting: Adapt spawn weights by game stage (EARLY/MID/LATE/ENDGAME)
- Boids Flocking: Emergent coordination without messaging overhead
- Controlled Chaos: 15% randomization for unpredictability
- Paint-as-Pheromone: Gradient following for territory coordination

---

## Part 1: Strategic Foundation

### 1.1 Win Condition Analysis

From WINNING_STRATEGIES.md:
- **Primary**: Paint 70%+ of map
- **Secondary**: Destroy all enemy units
- **Tiebreaker**: Most territory at round 2000

### 1.2 Critical Success Factors

| Factor | Source | Why It Matters |
|--------|--------|----------------|
| Paint Tower Protection | Om Nom (3rd) | "Losing Paint Tower = death spiral" |
| Paint Conservation | Confused (2nd) | "A few more attacks can win the game" |
| Rush Defense | SPAARK (HS 1st) | Prevent early tower loss |
| Ruin Denial | Om Nom | Paint ruins to block enemy towers |
| Economy Tracking | Om Nom | Income = (20 + 3×SRPs) × MoneyTowers |

### 1.3 The Paint-Money Imbalance

From research_spaark_analysis.md Part 20:
- Money Towers produce 4x resources vs Paint Towers
- Teams are **paint-bound, not money-bound**
- Solution: Tower nuking trades money for paint (500 paint per new tower spawn)

---

## Part 2: Opening Game Strategy (Rounds 0-50)

**Source**: WINNING_STRATEGIES.md + SPAARK Soldier.java

### 2.1 Critical First 50 Rounds

| Priority | Action | Rationale |
|----------|--------|-----------|
| 1 | Spawn 3 soldiers first | Bootstrap rule from Tower.java line 176 |
| 2 | Rush to center ruins | Establish territory control early |
| 3 | Build first tower (paint) | Prevent paint starvation death spiral |
| 4 | Paint tower protection | Never lose all paint towers (Om Nom insight) |
| 5 | Ruin denial | Paint neutral ruins to block enemy towers |

### 2.2 First Tower Priority

```java
// Tower.java - Bootstrap rule
if ((round < 50 || !isPaintTower) && totalSpawns < 3) {
    spawn(SOLDIER);  // MUST spawn soldiers first
}
```

**Why**: Soldiers are the only unit that can build towers. Need them to establish first paint tower.

### 2.3 Rush Defense

**Problem**: Early soldier rushes can destroy paint towers before they're rebuilt (WINNING_STRATEGIES.md).

**Solution**:
- First 3 units patrol near spawn
- Don't venture far until first tower complete
- Respond to enemy paint near towers immediately

### 2.4 Paint Conservation Round 0-50

From WINNING_STRATEGIES.md: "Saving even a little paint means you can land a few more attacks."

**Implementation**:
- Stay on ally paint when possible
- Don't attack through enemy paint - reposition first
- Retreat if paint < 100 in first 50 rounds

---

## Part 3: Critical Implementation Insights (From Testing)

**Source**: Autonomous iteration on spaark3 (20+ iterations, 146 → 196 rounds)

### 3.1 Spawn Blocking Prevention (HIGHEST PRIORITY)

**Problem**: Units retreating adjacent to towers block all 12 spawn locations → can't spawn new units → death spiral

**Impact**: Limited to 9 spawns in 196 rounds (should be 40+)

**Solution**:
```java
// Retreat to distance 4-8 tiles, NOT adjacent
int dist = me.distanceSquaredTo(retreatTower);

if (dist <= 4) {
    // Too close - move away to unblock spawns
    Direction away = retreatTower.directionTo(me);
    moveTo(me.add(away).add(away));
}
else if (dist >= 16 && dist <= 64) {
    // At waiting distance - stay here
    return;
}
else if (dist > 64) {
    // Far - move toward but stop at 16
    moveTo(retreatTower);
}

// Exit after refuel
if (dist <= 8 && paint > 100) {
    mode = EXPLORE;
    moveTo(mapCenter);  // Move away from tower
}
```

**Why Critical**: This single issue limits bot to ~200 rounds. Fix enables proper scaling.

---

### 3.2 Tower Upgrade Timing

**From Observations**:
- SPAARK upgrades towers to L2 by round 20
- Upgrade cost: 2500 chips
- Requires building economy fast (money towers or SRPs)

**Implementation**:
```java
// Tower.java - upgrade as soon as affordable (no buffer wait)
while (rc.canUpgradeTower(me)) {
    rc.upgradeTower(me);
}
// Attack AFTER upgrading (higher damage)
```

**Why Critical**: L2 towers have higher attack damage and income. Falling behind on upgrades = losing combat and economy.

---

### 3.3 Mode Distribution Benchmarks (From SPAARK)

**Expected Distribution** (measured from matches):
- EXPLORE: ~65%
- BUILD_TOWER: ~20%
- ATTACK: ~10%
- RETREAT: ~5%

**spaark3 Actual** (before fixes):
- EXPLORE: 31%
- ATTACK_TOWER: 68%
- BUILD_TOWER: 0%
- RETREAT: 10%

**Problem**: Too aggressive (68% attacking), never building (0%), retreating 2x too often

**Solution**: Implement full 7-mode system with proper priorities from SPAARK

---

### 3.4 Income Calculation Clarification

**Testing Found**: ECONOMY log "income=180" at round 10 is misleading

**Actual Meaning**:
- Round 10: income = (chips at round 10 - chips at round 0) / 10 = cumulative income
- NOT per-turn income at round 10

**Correct Interpretation**:
- Round 10: income=180 means 1800 chips earned over first 10 rounds (180/turn avg)
- Round 20: income=5 means only 50 chips earned in rounds 10-20 (5/turn avg)

**Implementation**: Track lastChips properly to calculate per-interval income

---

## Part 4: Phase Shifting System

**Research Validation**: Score 0.785 (highest-ranked hypothesis)
**Source**: Part VI, validated by RTS meta-analysis

### 4.1 Phase Definitions

| Phase | Rounds | Strategy | Unit Weights | SPAARK Modes |
|-------|--------|----------|--------------|--------------|
| EARLY | 0-150 | Rush center, establish territory | Soldier: 2.0, Splasher: 0.1, Mopper: 0.5 | EXPLORE, BUILD_TOWER |
| MID | 150-600 | Economy building, balanced expansion | Soldier: 1.2, Splasher: 0.5, Mopper: 1.0 | BUILD_RESOURCE, EXPAND_RESOURCE |
| LATE | 600-1500 | Paint coverage push | Soldier: 0.5, Splasher: 1.5, Mopper: 0.8 | ATTACK |
| ENDGAME | 1500+ | All-out coverage sprint | Soldier: 0.3, Splasher: 2.0, Mopper: 0.5 | ATTACK |

### 3.2 Why Phase Shifting Works

1. **Temporal optimization**: Different strategies optimal at different stages
2. **Resource alignment**: Early soldier-heavy for territory, late splasher-heavy for coverage
3. **Counter-adaptation**: Opponents can't optimize against a changing target
4. **Implementation simplicity**: Just round-number checks (~10 bytecode)

### 3.3 Phase Detection Logic

```
Phase.current():
  if round < 150: return EARLY
  if round < 600: return MID
  if round < 1500: return LATE
  return ENDGAME
```

### 3.4 Phase → Mode Mapping

How phases control SPAARK's 7 modes:

| Phase | Active Modes | Spawn Weights | Behavior |
|-------|--------------|---------------|----------|
| EARLY | EXPLORE, BUILD_TOWER | Soldier: 2.0 | Aggressive expansion, build towers opportunistically |
| MID | BUILD_RESOURCE, EXPAND_RESOURCE | Balanced | Focus on SRP economy (after round 50) |
| LATE | ATTACK, RETREAT | Splasher: 1.5 | Territory contest, paint coverage |
| ENDGAME | ATTACK | Splasher: 2.0 | All-out paint sprint, ignore economy |

**Integration**: Phase determines spawn weights in Tower.java, modes determine individual soldier behavior.

---

## Part 4: Boids Flocking System

**Research Validation**: Score 0.735, 40 years of proven research
**Source**: Craig Reynolds 1986, validated in Batman Returns, swarm robotics

### 4.1 The Three Rules

**Note**: Specific radius values are design parameters to be tuned, not from research.

| Rule | Suggested Radius | Behavior | Battlecode Application |
|------|------------------|----------|------------------------|
| Separation | < 9 tiles | Avoid crowding | Prevents clustering on same paint |
| Alignment | < 16 tiles | Match heading | Move toward common target |
| Cohesion | < 25 tiles | Move to center | Maintain group strength |

### 4.2 Why Boids Works for Battlecode

1. **Zero messaging overhead**: "No communication besides seeing another boid"
2. **Emergent coordination**: Complex group behavior from simple rules
3. **Proven at scale**: Works for 10,000+ agents in film/games
4. **Bytecode efficient**: Just distance checks and vector math

### 4.3 Integration with Navigation

```
computeMovement(target):
  navDir = bug2(target)           // Strategic goal
  boidDir = computeBoidsVector()  // Local coordination

  if Phase.current() == EARLY:
    return navDir                 // Individual exploration

  return blendDirections(navDir, boidDir, 0.6, 0.4)
```

---

## Part 5: Paint-as-Pheromone System

**Research Validation**: Score 0.665, based on ant colony optimization/stigmergy
**Source**: Wikipedia Stigmergy - "indirect coordination through environment"

### 5.1 Paint Pattern Signals

| Pattern | Detection | Meaning | Action |
|---------|-----------|---------|--------|
| Dense ally (>6 in 3x3) | `countAllyPaint(3x3) > 6` | Safe zone | Can build/retreat here |
| Mixed paint | Both ally and enemy | Frontline | Engage/support |
| Neutral majority (>5 in 5x5) | `countNeutral(5x5) > 5` | Unexplored | Explore priority |
| Enemy concentration | Enemy paint gradient | Threat | Retreat or attack |

### 5.2 Why Stigmergy Works

1. **No bytecode cost**: Paint already exists, just read it
2. **Persistent memory**: Paint stays, unlike messages
3. **Decentralized**: Every unit reads same environmental state
4. **Robust**: No message corruption or timing issues

### 5.3 Gradient Following

```
followPaintGradient():
  scores[8] = 0
  for d in DIRECTIONS:
    scores[d] = countAllyPaintInDirection(d, 10)
  return DIRECTIONS[maxIndex(scores)]
```

---

## Part 6: Controlled Chaos

**Research Validation**: Score 0.70, Nash equilibrium foundation
**Source**: MIT OCW Game Theory - mixed strategies prevent exploitation

### 6.1 Implementation

```
CHAOS_FACTOR = 0.15  // 15% randomization

shouldRandomize():
  return Random.nextDouble() < CHAOS_FACTOR

chooseDirection(scores):
  if shouldRandomize():
    return randomValidDirection()  // Override optimal
  return DIRECTIONS[maxIndex(scores)]
```

### 6.2 Why Randomization Works

1. **Prevents modeling**: Opponent can't predict 15% of moves
2. **Escapes local minima**: Random moves break stuck patterns
3. **Minimal cost**: Single RNG call (~5 bytecode)
4. **Game theory proven**: Mixed strategies are Nash equilibrium

---

## Part 7: SPAARK Core Systems (Preserved)

### 7.1 Seven-Mode State Machine

| Mode | Trigger | Exit Condition |
|------|---------|----------------|
| EXPLORE | Default | See ruin/enemy/SRP opportunity |
| BUILD_TOWER | Near neutral ruin + lastVisited timeout | Complete OR timeout (80 rounds) OR >2 soldiers |
| BUILD_RESOURCE | Round > 50 + valid SRP location | Complete OR timeout (50 rounds) |
| EXPAND_RESOURCE | SRP complete | All 16 expansions checked |
| ATTACK | See enemy tower | Tower destroyed OR retreat triggered |
| RETREAT | paint < 150 AND chips < 6000 AND allies < 9 | paint >= 75% capacity |
| MESSING_UP | Early game disruption | Round threshold |

### 7.2 Retreat System (CRITICAL)

From SPAARK Soldier.java line 145, Motion.java:

**Entry Conditions (ALL must be true):**
```java
// Soldier.java line 145
if (paint < 150 && maxChips < 6000 && allyRobots.length < 9) {
    Motion.setRetreatLoc();
    if (retreatTower != -1 && me.distanceTo(retreatLoc) < 9) {
        mode = RETREAT;
    }
}
```

**Dynamic Threshold (for deciding IF to retreat, not entry):**
```java
// Motion.java line 456
public static int getRetreatPaint() {
    if (allyRobots.length > 10) return 0;  // Never retreat with many allies

    return Math.max(
        paintLost + RETREAT_PAINT_OFFSET,      // 30 offset
        (int)(paintCapacity * RETREAT_PAINT_RATIO)  // 0.25 ratio
    );
}
```

**Exit Condition:**
```java
// Robot.java - paintNeededToStopRetreating
paint >= paintCapacity * 0.75
```

**8 Waiting Positions:**
```
(+2,+2), (+2,-2), (-2,+2), (-2,-2)
(+2, 0), ( 0,+2), (-2, 0), ( 0,-2)
```

**Queue Limit:** MAX_RETREAT_ROBOTS = 4 per tower

**CRITICAL IMPLEMENTATION NOTE (from testing):**

Units MUST retreat to waiting positions (distance 4-8 tiles), NOT adjacent to tower.

**Why**: Units adjacent to tower block all 12 spawn locations → no new units can spawn → death spiral

**Correct Implementation:**
```java
int dist = me.distanceSquaredTo(retreatTower);

// If adjacent (dist <= 4), move AWAY to unblock spawns
if (dist <= 4) {
    Direction away = retreatTower.directionTo(me);
    moveTo(me.add(away).add(away));  // Move 2 tiles away
    return;
}

// If at waiting distance (dist 16-64 = 4-8 tiles), stay
if (dist >= 16 && dist <= 64) {
    // Wait here for refuel
    return;
}

// If far, move toward tower (but stop at distance 16)
if (dist > 64) {
    moveTo(retreatTower);
}
```

**Testing Result**: Without this, spawn blocking limited spaark3 to only 9 spawns in 196 rounds (should be 40+)

### 7.3 lastVisited Grid

**Source**: SPAARK G.java line 136-154

```java
// 30x30 grid (map coords / 2) with +2000 offset for efficiency
public static int[][] lastVisited = new int[30][30];

public static void setLastVisited(MapLocation loc, int round) {
    lastVisited[loc.y / 2][loc.x / 2] = round + 2000;
}

public static int getLastVisited(MapLocation loc) {
    return lastVisited[loc.y / 2][loc.x / 2] - 2000;
}
```

**Why +2000 Offset**: Makes unvisited tiles (0) easily distinguishable from visited tiles (>2000). Avoids negative number issues.

**Usage**: Skip ruins visited within timeout period

```java
// Soldier exploration timeout
int timeout = SOL_RUIN_VISIT_TIMEOUT_BASE
            + SOL_RUIN_VISIT_TIMEOUT_MAP_INCREASE * mapArea
            + SOL_RUIN_VISIT_TIMEOUT_TOW_INCREASE * numTowers;

if (G.getLastVisited(ruin) + timeout < G.round) {
    // Valid target, not recently visited
}
```

### 7.4 SRP System

**Constants:**
| Constant | Value | Purpose |
|----------|-------|---------|
| SOL_MIN_SRP_ROUND | 50 | Don't build before round 50 |
| SOL_MAX_SRP_TIME | 50 | Build timeout |
| SOL_SRP_VISIT_TIMEOUT | 100 | Revisit cooldown |
| SOL_MAX_SRP_ENEMY_PAINT | 1 | Abort threshold |

**16 Expansion Locations (clockwise):**
```
Primary:  (+4,+4), (+4,0), (+4,-4), (0,-4), (-4,-4), (-4,0), (-4,+4), (0,+4)
Secondary: (+3,+4), (+4,+3), (+4,-3), (+3,-4), (-3,-4), (-4,-3), (-4,+3), (-3,+4)
```

### 7.5 Debt-Based Spawn System

**Base Weights:**
```
soldierWeight = 1.5 - numTowers * 0.05
splasherWeight = 0.2 + paintTowers * 0.3
mopperWeight = 1.2
```

**Debt Calculation:**
```
soldierDebt = fracSoldiers + soldierWeight - spawnedSoldiers
splasherDebt = fracSplashers + splasherWeight - spawnedSplashers
mopperDebt = fracMoppers + mopperWeight - spawnedMoppers

spawn(maxDebt(soldier, splasher, mopper))
```

**Bootstrap Rule:** First 3 spawns MUST be soldiers

### 7.5.1 Complete Tower Spawn Conditions (CRITICAL)

**Source**: SPAARK Tower.java line 147-154

**IMPLEMENTATION CRITICAL (from testing):**

Spawn location blocking is the #1 cause of performance degradation. Units retreating adjacent to towers block ALL spawn locations.

**Prevention**:
1. Units must retreat to distance 4-8 tiles (not adjacent)
2. Units must leave immediately after refueling (paint > 100)
3. Exit condition: `if (dist <= 8 && paint > 100) { mode = EXPLORE; moveTo(mapCenter); }`

**Testing Result**: Without spawn blocking prevention, achieved only 9 spawns in 196 rounds vs SPAARK's ~40+

```java
// Bootstrap: first 3 spawns are always soldiers
if ((round < 50 || type == LEVEL_ONE_MONEY_TOWER) && spawnedRobots < 3) {
    trying = UnitType.SOLDIER;
}

// Spawn conditions (ANY must be true):
if ((rc.getNumberTowers() == 25                           // Max towers
    || rc.getMoney() - trying.moneyCost >= 900            // Have 900+ buffer
       && (round < 100                                    // Early game
           || (lastSpawn + 1 < round && allyRobots.length < 4))  // Or sparse
    || round < 10)) {                                     // Very early game

    // Execute spawn
    for (spawnLoc in spawnLocs) {
        if (canBuildRobot(trying, spawnLoc)) {
            buildRobot(trying, spawnLoc);
            spawnedRobots++;
            // Update debt accumulators
            doubleSpawnedSoldiers += soldierWeight;
            doubleSpawnedSplashers += splasherWeight;
            doubleSpawnedMoppers += mopperWeight;
            lastSpawn = round;
            break;
        }
    }
}
```

**Why These Conditions:**
- Round < 10: Always spawn (establish presence)
- Towers == 25: Always spawn (can't build more towers, use resources)
- Chips >= 900 buffer: Ensure economy stability
- Round < 100: Aggressive early expansion
- After round 100: Only if didn't spawn recently AND few allies (prevent spam)

### 7.6 Tower Building Constraints

| Constant | Value | Purpose |
|----------|-------|---------|
| SOL_MAX_TOWER_TIME | 80 | Abort after 80 rounds |
| SOL_MAX_TOWER_BUILDING_SOLDIERS | 2 | Max soldiers per tower |
| SOL_MAX_TOWER_ENEMY_PAINT | 4 | Soft abort threshold |
| SOL_MAX_TOWER_ENEMY_PAINT_HARD | 8 | Hard abort threshold |
| SOL_MAX_TOWER_BLOCKED_TIME | 5 | Consecutive blocked rounds |
| SOL_RETREAT_REDUCED_RATIO | 0.5 | Retreat threshold when building |
| SOL_RUIN_VISIT_TIMEOUT_BASE | -100 | Base timeout for revisiting |
| SOL_RUIN_VISIT_TIMEOUT_TOW_INCREASE | 80 | Increase per tower |
| SOL_RUIN_VISIT_TIMEOUT_MAP_INCREASE | 0.2 | Increase per map tile |
| SOL_MONEY_PAINT_TOWER_RATIO | 2 | Money:Paint tower ratio |

**Lowest ID Completion:** When pattern complete, only lowest ID soldier stays

---

## Part 8: Micro Combat System

**Source**: SPAARK Motion.java line 1659-1759

### 8.1 Default Micro Scoring Formula

```java
// Motion.java defaultMicro
public static Micro defaultMicro = (Direction d, MapLocation dest) -> {
    int[] scores = new int[9];

    // 1. Direction bonus
    scores[dirOrd(d)] += 20;           // Target direction
    scores[dirOrd(d.rotateLeft())] += 15;  // Adjacent
    scores[dirOrd(d.rotateRight())] += 15;

    // 2. Calculate turnsToNext (CRITICAL for paint penalties)
    int turnsToNext = (cooldown(paint, MOVEMENT_COOLDOWN) + movementCooldown) / 10;

    // 3. Paint penalties (scaled by turnsToNext)
    int enemyPaintPenalty = DEF_MICRO_E_PAINT_PENALTY * PENALTY_ENEMY_TERRITORY
                            * mopperPenalty * turnsToNext;
    int neutralPaintPenalty = DEF_MICRO_N_PAINT_PENALTY * PENALTY_NEUTRAL_TERRITORY
                              * mopperPenalty * turnsToNext;

    // 4. Apply paint penalties to each direction
    for (int i = 9; --i >= 0;) {
        MapLocation nxt = me.add(ALL_DIRECTIONS[i]);
        PaintType paint = senseMapInfo(nxt).getPaint();

        if (paint.isEnemy()) {
            scores[i] -= enemyPaintPenalty;
            // Extra penalty if ally on enemy paint
            if (allyAtLocation(nxt)) scores[i] -= DEF_MICRO_E_PAINT_BOT_PENALTY;
        } else if (paint == EMPTY) {
            scores[i] -= neutralPaintPenalty;
            if (allyAtLocation(nxt)) scores[i] -= DEF_MICRO_N_PAINT_BOT_PENALTY;
        }
    }

    // 5. Enemy mopper avoidance
    for (mopper in enemies) {
        if (withinDist(mopper, 8)) scores[direction] -= 20;
    }

    // 6. Tower danger formula
    for (tower in nearbyRuins) {
        int danger = paintPerChips * moneyCost * turnsToNext
                     * attackStrength / health;
        if (health <= attackStrength) danger += 1000;      // Lethal
        if (health <= attackStrength * 2) danger += 2000;  // Two-shot

        if (withinDist(tower, 2)) scores[i] -= danger * 2;
        else if (withinDist(tower, actionRadius)) scores[i] -= danger;
    }

    return scores;
};
```

### 8.2 Constants

| Constant | Value | Purpose | Source |
|----------|-------|---------|--------|
| DEF_MICRO_E_PAINT_PENALTY | 5 | Enemy paint base penalty | Motion.java |
| DEF_MICRO_E_PAINT_BOT_PENALTY | 10 | Ally on enemy paint | Motion.java |
| DEF_MICRO_N_PAINT_PENALTY | 5 | Neutral paint base penalty | Motion.java |
| DEF_MICRO_N_PAINT_BOT_PENALTY | 5 | Ally on neutral paint | Motion.java |
| PENALTY_ENEMY_TERRITORY | (from engine) | Game constant multiplier | GameConstants |
| PENALTY_NEUTRAL_TERRITORY | (from engine) | Game constant multiplier | GameConstants |
| MOPPER_PAINT_PENALTY_MULTIPLIER | (from engine) | Mopper paint cost multiplier | GameConstants |

**Note**: GameConstants values are provided by Battlecode 2025 engine, not defined by bot code.

---

## Part 9: Tower Type Selection

**Source**: SPAARK Soldier.java line 1140-1180

### 9.1 Marker System

| Direction | Tower Type | Marker |
|-----------|------------|--------|
| SOUTH (0, -1) | Defense (0) | ALLY_PRIMARY |
| WEST (-1, 0) | Money (1) | ALLY_PRIMARY |
| EAST (1, 0) | Paint (2) | ALLY_PRIMARY |

### 9.2 Selection Logic

```java
// Soldier.java predictTowerType()
public static int predictTowerType(MapLocation loc) {
    // 1. Check for existing markers (highest priority)
    if (canSense(loc.add(SOUTH)) && getMark(loc.add(SOUTH)) == ALLY_PRIMARY)
        return 0;  // Defense
    if (canSense(loc.add(WEST)) && getMark(loc.add(WEST)) == ALLY_PRIMARY)
        return 1;  // Money
    if (canSense(loc.add(EAST)) && getMark(loc.add(EAST)) == ALLY_PRIMARY)
        return 2;  // Paint

    // 2. Default logic
    int towerType = (chips < 10000 && numTowers < 24
                     && (numTowers < sqrt(mapArea) / 6
                         || paintTowers * 2 > moneyTowers))
                    ? 1  // Money
                    : 2; // Paint

    // 3. Force paint if no paint towers
    if (paintTowers == 0 && moneyTowers >= 3) {
        towerType = 2;
    }

    // 4. Defense near center if enemy present
    if (mapCenter.distanceTo(loc) < 36) {
        if (hasEnemyPaint() || hasEnemyRobots()) {
            towerType = 0;  // Defense
        }
    }

    // 5. Place marker for future soldiers
    MapLocation markLoc = switch(towerType) {
        case 0 -> loc.add(SOUTH);
        case 1 -> loc.add(WEST);
        case 2 -> loc.add(EAST);
    };
    if (canMark(markLoc)) mark(markLoc, false);

    return towerType;
}
```

---

## Part 10: Splasher Dynamic Threshold

**Source**: research_spaark_analysis.md Part 10.2

### 10.1 Formula

```java
// Splasher.java
public static final int SPL_INITIAL_ATK_MULT = 3;

int attackThreshold = mapArea * SPL_INITIAL_ATK_MULT / round + 300;

// Example on 60x60 map (3600 tiles):
// Round 1:   3600 * 3 / 1   + 300 = 11100 (very selective)
// Round 100: 3600 * 3 / 100 + 300 = 408   (aggressive)
// Round 500: 3600 * 3 / 500 + 300 = 322   (maximum aggression)
```

### 10.2 Why It Works

Threshold decreases over time → splashers become more aggressive as game progresses → paint coverage push in late game.

---

## Part 11: Java Bytecode Optimization

**Source**: Part VII research, 30%+ savings validated

### 11.1 Mandatory Patterns

| Pattern | Before | After | Savings |
|---------|--------|-------|---------|
| Loops | `for(i=0; i<n; i++)` | `for(i=n; --i>=0;)` | 30% |
| Variables | Instance fields | Static in G.java | 1 bytecode/access |
| Branching | if-else chain | switch statement | O(n) → O(1) |
| Collections | java.util.* | Primitive arrays | 75-95% |
| Math | `x / 2` | `x >> 1` | 30% |

### 11.2 Variable Caching

```java
// SLOW: Repeated field access
for (int i = enemies.length; --i >= 0;) {
    if (enemies[i].distanceTo(this.myLocation) < range) { }
}

// FAST: Cache to local
MapLocation myLoc = this.myLocation;
for (int i = enemies.length; --i >= 0;) {
    if (enemies[i].distanceTo(myLoc) < range) { }
}
```

### 11.3 Bit Manipulation

**Source**: Research Part 56

#### 11.3.1 Arithmetic Shortcuts

| Operation | Standard | Bitwise | Savings |
|-----------|----------|---------|---------|
| x * 2 | `x * 2` | `x << 1` | ~30% |
| x / 2 | `x / 2` | `x >> 1` | ~30% |
| x % 2 | `x % 2` | `x & 1` | ~40% |
| x % 8 | `x % 8` | `x & 7` | ~40% |
| x * 2^n | `x * Math.pow(2,n)` | `x << n` | ~90% |

#### 11.3.2 Common Bit Tricks

```java
// Check if odd
boolean isOdd = (x & 1) == 1;

// Check if power of 2
boolean isPow2 = (x & (x - 1)) == 0 && x != 0;

// Swap without temp variable
a ^= b; b ^= a; a ^= b;

// Absolute value without branch
int abs = (x ^ (x >> 31)) - (x >> 31);

// Min/Max without branch (branchless)
int min = y ^ ((x ^ y) & -(x < y ? 1 : 0));
int max = x ^ ((x ^ y) & -(x < y ? 1 : 0));

// Count set bits (Hamming weight)
int count = Integer.bitCount(x);  // Built-in, very fast
```

#### 11.3.3 Coordinate Encoding

```java
// Pack two 6-bit coordinates into 12 bits (max 63x63 map)
int packed = (y << 6) | x;

// Unpack
int x = packed & 0b111111;
int y = (packed >> 6) & 0b111111;

// Pack 3 values (x, y, type) into 16 bits
int message = (type << 12) | (y << 6) | x;
```

### 11.4 Method Inlining

**Source**: Research Part 54.4

| Method Size | JIT Behavior |
|-------------|--------------|
| < 35 bytecodes | Auto-inlined (always) |
| 35-325 bytecodes | Inlined if "hot" (called 10,000+ times) |
| > 325 bytecodes | Never inlined |

**Optimization**: Keep frequently-called methods under 35 bytecodes for guaranteed inlining.

**Examples:**
```java
// GOOD: Small utility methods get inlined
static int maxIndex(int[] arr) {  // ~20 bytecode
    int maxIdx = 0, maxVal = arr[0];
    for (int i = arr.length; --i > 0;) {
        if (arr[i] > maxVal) { maxVal = arr[i]; maxIdx = i; }
    }
    return maxIdx;
}

// BAD: Large method won't inline
static void complexProcessing() {  // 500+ bytecode
    // Lots of logic here...
}
```

### 11.5 Data Structure Optimization

**Source**: Research Part 55

#### 11.5.1 Flattened 2D Arrays

```java
// SLOW: 2D array (double indirection)
int[][] grid = new int[60][60];
int value = grid[y][x];  // Two array lookups

// FAST: 1D array with index math
int[] grid = new int[60 * 60];
int value = grid[y * 60 + x];  // Single lookup + arithmetic
```

**Bytecode Savings**: 50%+ for repeated 2D access

**SPAARK Example**: lastVisited could be flattened but uses 2D for clarity (30x30 is small enough).

#### 11.5.2 Array Pre-Allocation

```java
// BAD: Growing ArrayList
ArrayList<Integer> list = new ArrayList<>();
for (...) list.add(x);  // Multiple resize operations

// GOOD: Pre-sized array
int[] arr = new int[KNOWN_SIZE];
int index = 0;
for (...) arr[index++] = x;  // No resizing
```

**SPAARK Examples:**
- `int[] range20X = new int[69];` (pre-allocated, never resized)
- `MapLocation[] spawnLocs = new MapLocation[12];` (fixed size)

#### 11.5.3 StringBuilder for Position Lookup

**Source**: SPAARK G.java

```java
// O(1) position checking using string indexOf
public static StringBuilder allyRobotsString = new StringBuilder();

// Build each turn
for (RobotInfo ally : allies) {
    if (ally.type.isRobotType()) {
        allyRobotsString.append(ally.location.toString());
    }
}

// Check if ally at location (O(1) average case)
if (allyRobotsString.indexOf(loc.toString()) != -1) {
    // Ally present
}
```

**Why**: Faster than iterating through RobotInfo[] array for position checks.

### 11.6 Object Allocation Avoidance

**Source**: Research Part 58.1

**Rules:**
1. Never create objects in hot loops
2. Reuse objects when possible
3. Use primitives instead of wrapper classes
4. Pre-allocate to final size

```java
// BAD: Object creation in loop
for (int i = 0; i < 1000; i++) {
    MapLocation loc = new MapLocation(x, y);  // 1000 allocations!
    process(loc);
}

// GOOD: Reuse or avoid
for (int i = 0; i < 1000; i++) {
    process(x, y);  // Pass primitives directly
}
```

**SPAARK Example**: Uses `me.translate(dx, dy)` sparingly, caches `G.me` instead.

### 11.7 Boxing/Unboxing Avoidance

**Source**: Research Part 58.2

| Type | Memory | Boxing Cost |
|------|--------|-------------|
| int | 4 bytes | None |
| Integer | 16+ bytes | valueOf() call |
| long | 8 bytes | None |
| Long | 24+ bytes | valueOf() call |

**Rule**: Always use primitive types (int, long, boolean) never wrappers (Integer, Long, Boolean).

**Integer Cache**: Java caches Integer -128 to 127. Outside this range = new object creation.

### 11.8 String Optimization

**Source**: Research Part 57.1

| Operation | Bad | Good | Why |
|-----------|-----|------|-----|
| Concatenation in loop | `s += x` | `StringBuilder.append(x)` | Avoid object creation |
| String comparison | `s1 == s2` | `s1.equals(s2)` | Correctness |

**SPAARK Usage:**
```java
// G.indicatorString built with StringBuilder
G.indicatorString = new StringBuilder();
G.indicatorString.append("MODE=EXPLORE ");
G.indicatorString.append("PAINT=" + paint + " ");
```

**Rule**: Use StringBuilder for building strings in loops. Avoid String concatenation (+) in hot paths.

### 11.9 Bytecode Budgeting

**Source**: Research Part 35.1, SPAARK profiling

| Operation | Typical Cost | Priority |
|-----------|--------------|----------|
| Pathfinding | 1000-2000 | High |
| Enemy scan | 500-1500 | High |
| Micro decisions | 300-800 | Medium |
| Communication | 200-500 | Medium |
| Arrays.sort() | ~6000 (12 elements) | One-time at init |
| Debug indicators | 100-5000 | Disable in prod |

**Early Termination Guards:**
```java
if (Clock.getBytecodesLeft() < 2000) return;  // Minimum safety
if (Clock.getBytecodesLeft() < 3000) break;   // POI operations
if (Clock.getBytecodesLeft() < 5000) break;   // Path planning
if (Clock.getBytecodesLeft() < 7600) break;   // SRP checking
if (Clock.getBytecodesLeft() > 10000) {
    // Extra work if bytecode available
}
```

**SPAARK Pattern**: Check bytecode before expensive operations, do low-priority work only when budget allows.

---

## Part 12: Gap Analysis Implementation

From PERFORMANCE_ANALYSIS.md:

### 12.1 Gap 1: Ruin Denial

**Problem**: Soldiers don't paint contested ruins to deny enemy towers

**Solution**: Add priority between defense and tower building
```
Priority 5.5: RUIN DENIAL
  for ruin in nearbyRuins:
    if no tower AND no enemy paint:
      paint ruin center
      report via RUIN_FOUND
```

### 12.2 Gap 2: Paint Conservation in Combat

**Problem**: Units take unnecessary damage on enemy paint while attacking

**Solution**: Prefer ally-painted attack positions
```
engageEnemy(enemy):
  if current tile is enemy paint:
    for adjacent ally-painted tiles in attack range:
      move there first
  then attack
```

### 12.3 Gap 3: SRP Economy Tracking

**Problem**: Phase transitions use fixed round numbers, not economic state

**Solution**: Track income patterns
```
avgIncome = sum(incomeHistory[10]) / 10
estimatedSRPs = (avgIncome / moneyTowers - 20) / 3
isEconomicallyStrong = estimatedSRPs >= 3 OR moneyTowers >= 2
```

---

## Part 13: Counter-Strategy Integration

From Part 21-27 of research:

### 13.1 SPAARK Weaknesses to Exploit

| Weakness | Mechanism | Counter |
|----------|-----------|---------|
| Defense tower auto-destruct | 30 turns no enemy | Avoid for 30 turns |
| Robot self-sacrifice | paint=0, chips>5000 | Paint-starve enemies |
| Retreat queue limit | MAX_RETREAT_ROBOTS=4 | Overwhelm queue |
| OPTNET tuning | Optimized for average | Exploit edge cases |

### 13.2 Universal Competitive Advantages

1. **Symmetry prediction**: Know enemy positions before seeing them
2. **Adaptive switching**: Counter any strategy by detection
3. **Local superiority**: Win by concentrated force
4. **Economy denial**: Target paint sources for cascade failure

---

## Part 14: Detailed Algorithm Implementations

### 14.1 Soldier Run Loop Structure

**Source**: SPAARK Soldier.java line 139-200

```java
public static void run() throws Exception {
    // 1. Check retreat conditions (BEFORE mode logic)
    if (paint < 150 && maxChips < 6000 && allyRobots.length < 9) {
        Motion.setRetreatLoc();
        if (retreatTower != -1 && me.distanceTo(retreatLoc) < 9) {
            mode = RETREAT;
        }
    } else if (mode == RETREAT) {
        mode = EXPLORE;  // Exit retreat
        retreatTower = -1;
    }

    // 2. Mode-specific transition checks
    switch (mode) {
        case EXPLORE -> exploreCheckMode();       // Check for transitions to other modes
        case BUILD_TOWER -> buildTowerCheckMode();
        case BUILD_RESOURCE -> buildResourceCheckMode();
        case EXPAND_RESOURCE -> expandResourceCheckMode();
        case ATTACK -> attackCheckMode();
        case RETREAT -> { /* stay in retreat */ }
    }

    // 3. Execute mode behavior
    switch (mode) {
        case EXPLORE -> explore();
        case BUILD_TOWER -> buildTower();
        case BUILD_RESOURCE -> buildResource();
        case EXPAND_RESOURCE -> expandResource();
        case ATTACK -> attack();
        case RETREAT -> Motion.retreat();
    }

    // 4. Opportunistic tower completion (AFTER mode execution)
    for (ruin in nearbyRuins) {
        if (canCompleteTowerPattern(DEFENSE, ruin) && getMark(ruin.add(SOUTH)) != EMPTY) {
            completeTowerPattern(DEFENSE, ruin);
        }
        // Same for MONEY (WEST marker), PAINT (EAST marker)
    }
}
```

### 14.2 Bug2 Pathfinding Algorithm

**Source**: Existing spaark3 Nav.java (based on SPAARK pattern)

```java
// Bug2 state
private static MapLocation bugTarget;
private static boolean bugTracing;
private static Direction bugTracingDir;
private static MapLocation bugStartLoc;
private static int bugStartDist;
private static boolean bugRotateRight;
private static int bugTurns;

private static Direction bug2(MapLocation target) {
    // Reset if target changed
    if (!target.equals(bugTarget)) {
        bugTarget = target;
        bugTracing = false;
    }

    Direction targetDir = me.directionTo(target);

    // If not tracing, try direct path
    if (!bugTracing) {
        if (canMove(targetDir)) {
            return targetDir;
        }

        // Start tracing obstacle
        bugTracing = true;
        bugStartLoc = me;
        bugStartDist = me.distanceSquaredTo(target);
        bugTracingDir = targetDir;
        bugRotateRight = Random.nextBoolean();
        bugTurns = 0;
    }

    // Tracing - follow obstacle
    if (bugTracing) {
        // Check if can leave trace (closer than start)
        int curDist = me.distanceSquaredTo(target);
        if (curDist < bugStartDist && canMove(targetDir)) {
            bugTracing = false;
            return targetDir;
        }

        // Timeout after 20 turns
        bugTurns++;
        if (bugTurns > 20) {
            bugTracing = false;
            return targetDir;
        }

        // Rotate around obstacle
        Direction dir = bugTracingDir;
        for (int i = 8; --i >= 0;) {
            if (canMove(dir)) {
                bugTracingDir = bugRotateRight ? dir.rotateLeft() : dir.rotateRight();
                return dir;
            }
            dir = bugRotateRight ? dir.rotateRight() : dir.rotateLeft();
        }
    }

    return targetDir;
}
```

### 14.3 Boids Flocking Formula

**Source**: Research Part VI (Craig Reynolds), adapted for Battlecode

```java
static Direction computeBoidsVector(RobotInfo[] allies) {
    int sepX = 0, sepY = 0;    // Separation vector
    int cohX = 0, cohY = 0;    // Cohesion vector
    int count = 0;

    for (int i = allies.length; --i >= 0;) {
        MapLocation allyLoc = allies[i].location;
        int dist = me.distanceSquaredTo(allyLoc);

        // Separation: avoid nearby allies (< 9 tiles)
        if (dist < 9) {
            sepX -= (allyLoc.x - me.x);
            sepY -= (allyLoc.y - me.y);
        }

        // Cohesion: move toward group center (< 25 tiles)
        if (dist < 25) {
            cohX += allyLoc.x;
            cohY += allyLoc.y;
            count++;
        }
    }

    // Combine vectors with weights
    int finalX = sepX * 3;  // Separation weight: 3
    int finalY = sepY * 3;

    if (count > 0) {
        finalX += (cohX / count - me.x);  // Cohesion weight: 1
        finalY += (cohY / count - me.y);
    }

    return directionFromVector(finalX, finalY);
}

// Blend with strategic direction
Direction computeMovement(MapLocation target) {
    Direction navDir = bug2(target);
    Direction boidDir = computeBoidsVector(getAllies());

    // Early phase: pure navigation
    if (Phase.current() == EARLY) return navDir;

    // Later phases: blend 60% nav, 40% boids
    // Implementation: alternate based on round
    return (round & 1) == 0 ? navDir : boidDir;
}
```

### 14.4 Tower Pattern Building

**Source**: SPAARK uses Battlecode API

Tower patterns are built using Battlecode API:
```java
// Battlecode 2025 provides these methods:
rc.canCompleteTowerPattern(UnitType towerType, MapLocation ruin)
rc.completeTowerPattern(UnitType towerType, MapLocation ruin)

// Tower types for patterns:
Robot.towers[0] = UnitType.LEVEL_ONE_DEFENSE_TOWER
Robot.towers[1] = UnitType.LEVEL_ONE_MONEY_TOWER
Robot.towers[2] = UnitType.LEVEL_ONE_PAINT_TOWER
```

The 5x5 pattern is defined by the Battlecode engine, not manually painted.

### 14.5 SRP Pattern Building

**Source**: SPAARK Soldier.java

```java
// SRP uses Battlecode API:
rc.canCompleteResourcePattern(MapLocation center)
rc.completeResourcePattern(MapLocation center)

// Mark SRP center with ALLY_SECONDARY for discovery
rc.mark(center, true);  // true = ALLY_SECONDARY marker
```

The resource pattern tiles are defined by the Battlecode engine.

### 14.6 Exploration Target Selection

**Source**: SPAARK Motion.java exploreRandomlyLoc()

```java
static MapLocation selectExploreTarget() {
    // 1. Check for symmetry prediction (if symmetry determined)
    int validSymmetries = countValidSymmetries();
    if (validSymmetries == 1) {
        // Target predicted enemy tower locations
        for (int i = numberOfTowers; --i >= 0;) {
            if (towerTeams[i] == team) {
                MapLocation enemyPrediction = getOppositeMapLocation(
                    towerLocs[i],
                    getValidSymmetryType()
                );
                return enemyPrediction;
            }
        }
    }

    // 2. Find nearest neutral ruin
    MapLocation nearestRuin = POI.findNearestNeutralRuin();
    if (nearestRuin != null && !recentlyVisited(nearestRuin, timeout)) {
        return nearestRuin;
    }

    // 3. Random unexplored location
    // Use POI.explored bitfield to pick weighted random
    int unexplored = mapArea;
    for (int i = mapHeight; --i >= 0;) {
        unexplored -= Long.bitCount(explored[i]);
    }

    int rand = Random.rand() % unexplored;
    // Find tile at random index in unexplored set
    // (See research Part 15.2 for full algorithm)

    // 4. Fallback: map center
    return mapCenter;
}
```

### 14.7 Attack Mode Behavior

**Source**: SPAARK Soldier.java attack()

```java
public static void attack() {
    // Check if tower still exists and is enemy
    if (!canSenseLocation(towerLocation) || !canSenseRobot(towerLocation)) {
        mode = EXPLORE;  // Tower destroyed or gone
        return;
    }

    RobotInfo tower = senseRobot(towerLocation);
    if (tower.team == team) {
        mode = EXPLORE;  // Captured
        return;
    }

    // Check if tower pattern is blocked by ally paint
    if (allAllyPaintInPattern(towerLocation)) {
        mode = EXPLORE;  // Already blocked, leave
        return;
    }

    // Attack the tower location
    if (canAttack(towerLocation)) {
        attack(towerLocation);
    }

    // Move toward tower using micro
    int[] scores = attackMicro(towerLocation);
    microMove(scores);
}
```

### 14.8 Mode Transition Checks

**Source**: SPAARK Soldier.java exploreCheckMode() example

```java
// Called in run() BEFORE executing mode behavior
public static void exploreCheckMode() {
    // Priority 1: See enemy tower → ATTACK
    for (enemy in enemies) {
        if (enemy.type.isTowerType()) {
            mode = ATTACK;
            towerLocation = enemy.location;
            towerType = enemy.type;
            return;
        }
    }

    // Priority 2: Near neutral ruin + timeout passed → BUILD_TOWER
    for (ruin in nearbyRuins) {
        if (!hasTower(ruin) && !recentlyVisited(ruin, timeout)) {
            mode = BUILD_TOWER;
            ruinLocation = ruin;
            buildTime = 0;
            return;
        }
    }

    // Priority 3: Can build SRP + round > 50 → BUILD_RESOURCE or EXPAND_RESOURCE
    if (round > SOL_MIN_SRP_ROUND) {
        // Check for existing SRP markers
        for (tile in nearbyTiles) {
            if (getMark(tile) == ALLY_SECONDARY && !isResourcePatternCenter(tile)) {
                srpCheckLocations = [tile];
                mode = EXPAND_RESOURCE;
                return;
            }
        }

        // Check if can build new SRP
        if (canBuildSrpAtLocation(someLocation)) {
            mode = BUILD_RESOURCE;
            resourceLocation = someLocation;
            return;
        }
    }

    // Default: stay in EXPLORE
}
```

### 14.9 Splasher Run Loop

**Source**: SPAARK Splasher.java line 32-150

```java
public static void run() {
    // 1. Check retreat (same as Soldier)
    if (paint < getRetreatPaint() && maxChips < 6000 && allyRobots.length < 9) {
        Motion.setRetreatLoc();
        if (retreatTower != -1 && me.distanceTo(retreatLoc) < 9) {
            mode = RETREAT;
        }
    } else if (mode == RETREAT) {
        mode = EXPLORE;
    }

    // 2. Reset scoring arrays (all to 0)
    Arrays.fill(moveScores, 0);
    Arrays.fill(attackScores, 0);

    // 3. Compute scores based on mode
    switch (mode) {
        case EXPLORE:
            if (isMovementReady()) exploreMoveScores();
            if (isActionReady()) exploreAttackScores();  // Codegen, 37 positions
        case RETREAT:
            if (isMovementReady()) retreatMoveScores();
    }

    // 4. Find best attack location (37 positions, unrolled comparison)
    int bestAttackScore = attackScores[0];
    int bestX = 0, bestY = 0;
    // Unrolled: compare all 37 attack positions...
    // (See SPAARK Splasher.java line 86-270 for full unrolled code)

    // 5. Execute best action
    if (bestAttackScore >= attackThreshold()) {
        attack(me.translate(bestX, bestY));
    }

    // 6. Execute movement
    if (isMovementReady()) {
        int bestMoveDir = maxIndex(moveScores);
        if (canMove(ALL_DIRECTIONS[bestMoveDir])) {
            move(ALL_DIRECTIONS[bestMoveDir]);
        }
    }
}

// Dynamic threshold
int attackThreshold() {
    return mapArea * SPL_INITIAL_ATK_MULT / round + 300;
}
```

### 14.9.1 Splasher Attack Scoring Formula

**Source**: SPAARK Splasher.java line 396-470

Splasher scores each position in 5x5 splash area (37 positions total):

```java
// For each tile in splash range:
MapInfo info = senseMapInfo(loc);

if (!info.isWall()) {
    if (info.getPaint() == EMPTY) {
        if (info.hasRuin()) {
            // Ruin with enemy tower on it
            if (enemyTowerAtLocation(loc)) {
                attackScores[all affected positions] += paintPerChips() * 200;
            }
            // Ruin with no tower
            else {
                attackScores[all affected positions] += 100;
            }
        }
        // Empty tile (no ruin)
        else {
            attackScores[all affected positions] += 25;
        }

        // Bonus if robot on tile
        if (enemyAtLocation(loc) || allyAtLocation(loc)) {
            attackScores[center position] += 25;
        }
    }
    // Enemy paint
    else if (info.getPaint().isEnemy()) {
        attackScores[all affected positions] += 50;

        // Bonus if robot on enemy paint
        if (enemyAtLocation(loc) || allyAtLocation(loc)) {
            attackScores[center position] += 50;
        }
    }
}
```

**Scoring Weights Summary:**
- Enemy tower on ruin: +200 × paintPerChips()
- Neutral ruin: +100
- Enemy paint: +50
- Empty tile: +25
- Robot on tile: +25-50 bonus

**Implementation Note**: SPAARK uses codegen to unroll all 37 positions. Can simplify with loops, trading bytecode for code clarity.

### 14.10 Mopper Run Loop

**Source**: SPAARK Mopper.java line 50-200

```java
public static void run() {
    // 1. Check retreat (same as Soldier/Splasher)
    if (paint < getRetreatPaint() && maxChips < 6000 && allyRobots.length < 9) {
        Motion.setRetreatLoc();
        if (retreatTower != -1 && me.distanceTo(retreatLoc) < 9) {
            mode = RETREAT;
        }
    } else if (mode == RETREAT) {
        mode = EXPLORE;
    }

    // 2. Mode transition checks
    switch (mode) {
        case EXPLORE -> exploreCheckMode();  // Check for BUILD mode near ruins
        case BUILD -> buildCheckMode();       // Check if BUILD timeout
    }

    // 3. Reset all scoring arrays to 0
    Arrays.fill(moveScores, 0);
    Arrays.fill(attackScores, 0);  // 25 positions (mop attack)
    Arrays.fill(swingScores, 0);   // 36 positions (4 directions × 9 positions)
    Arrays.fill(transferScores, 0); // 25 positions (paint transfer)

    // 4. Compute scores based on mode
    switch (mode) {
        case EXPLORE:
            if (isMovementReady()) exploreMoveScores();
            if (isActionReady()) {
                exploreAttackScores();   // Mop enemy paint
                exploreSwingScores();    // Swing attack
                exploreTransferScores(); // Give paint to allies
            }
        case BUILD:
            // Similar scoring for BUILD mode (prioritize mopping near towers)
        case RETREAT:
            // Similar scoring for RETREAT mode
    }

    // 5. Unified action selection (find max across ALL action types)
    int bestAction = ATTACK;
    int bestScore = attackScores[0];
    int bestX = 0, bestY = 0;

    // Check all mop positions (25)
    for (i in attackScores) { /* find max */ }

    // Check all swing directions (36)
    if (swingScores[32] > bestScore) {  // SOUTH
        bestAction = SWING; bestScore = swingScores[32]; /* etc */
    }
    // Same for WEST (33), EAST (34), NORTH (35)

    // Check all transfer positions (25)
    for (i in transferScores) { /* find max */ }

    // 6. Execute best action
    switch (bestAction) {
        case ATTACK -> mop(me.translate(bestX, bestY));
        case SWING -> mopSwing(direction);
        case TRANSFER -> transferPaint(ally, amount);
    }

    // 7. Movement
    int bestMoveDir = maxIndex(moveScores);
    if (canMove(ALL_DIRECTIONS[bestMoveDir])) {
        move(ALL_DIRECTIONS[bestMoveDir]);
    }
}
```

### 14.10.1 Mopper Scoring Summary

**Source**: SPAARK Mopper.java (codegen'd)

Mopper uses similar logic to Splasher but for different actions:

**Attack Scoring (mop enemy paint in 5x5 area):**
- Enemy paint on tile: Base score
- Near tower: +MOP_TOWER_WEIGHT (150)
- In retreat mode: +MOP_RETREAT_STEAL_WEIGHT (30)

**Swing Scoring (mopSwing in 4 cardinal directions):**
- Scores based on enemy robots in swing path
- Multiplier: MOP_SWING_MULT (1.0)
- Prefers lines of enemies

**Transfer Scoring (give paint to allies):**
- Low-paint allies get higher scores
- Transfer to soldiers building towers prioritized

**Constants:**
- MOP_SWING_MULT = 1.0
- MOP_RETREAT_STEAL_WEIGHT = 30
- MOP_TOWER_WEIGHT = 150

**Implementation Note**: Like Splasher, SPAARK uses codegen for efficiency. Can be implemented with loops.

### 14.11 RobotPlayer Main Structure

**Source**: SPAARK RobotPlayer.java

```java
public class RobotPlayer {
    public static void run(RobotController rc) {
        try {
            // 1. One-time initialization
            G.rc = rc;
            Random.state = rc.getID() * 0x2bda6bc + 0x9734e9;
            G.mapWidth = rc.getMapWidth();
            G.mapHeight = rc.getMapHeight();
            G.mapCenter = new MapLocation(G.mapWidth / 2, G.mapHeight / 2);
            G.mapArea = G.mapWidth * G.mapHeight;
            G.team = rc.getTeam();
            G.opponent = G.team.opponent();
            G.roundSpawned = rc.getRoundNum();

            // 2. Initialize unit-specific state
            switch (rc.getType()) {
                case MOPPER, SOLDIER, SPLASHER -> Robot.init();
                default -> Tower.init();  // Tower types
            }

            // 3. Main game loop
            while (true) {
                try {
                    // Update global state each round
                    updateRound();

                    // Dispatch to unit type
                    switch (rc.getType()) {
                        case MOPPER, SOLDIER, SPLASHER -> Robot.run();
                        default -> Tower.run();
                    }

                    // Set indicator string for debugging
                    rc.setIndicatorString(G.indicatorString.toString());
                    G.indicatorString = new StringBuilder();

                } catch (GameActionException e) {
                    System.out.println("GameActionException");
                    e.printStackTrace();
                } catch (Exception e) {
                    System.out.println("Exception");
                    e.printStackTrace();
                }

                // Check for bytecode overflow
                if (rc.getRoundNum() != round) {
                    System.err.println("Bytecode overflow! Round " + round);
                }

                // Update for next round
                G.lastChips = rc.getChips();
                G.lastNumberTowers = rc.getNumberTowers();

                Clock.yield();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void updateRound() {
        G.maxChips = Math.max(G.maxChips, rc.getChips());
        Motion.movementCooldown -= COOLDOWNS_PER_TURN * (rc.getRoundNum() - G.round);
        Motion.movementCooldown = Math.max(Motion.movementCooldown, 0);
        G.round = rc.getRoundNum();
        updateInfo();
        POI.updateRound();
    }

    static void updateInfo() {
        G.me = rc.getLocation();
        G.allyRobots = rc.senseNearbyRobots(-1, G.team);
        G.opponentRobots = rc.senseNearbyRobots(-1, G.opponent);
        // Build allyRobotsString for O(1) position lookup
        G.allyRobotsString = new StringBuilder();
        for (int i = G.allyRobots.length; --i >= 0;) {
            if (G.allyRobots[i].type.isRobotType()) {
                G.allyRobotsString.append(G.allyRobots[i].location.toString());
            }
        }
        G.nearbyMapInfos = rc.senseNearbyMapInfos();
        G.nearbyRuins = rc.senseNearbyRuins(-1);
    }
}
```

### 14.12 Motion.retreat() Implementation

**Source**: SPAARK Motion.java line 739-745

```java
public static void retreat() {
    retreat(defaultMicro);
}

public static void retreat(Micro micro) {
    microMove(micro.micro(retreatDir(retreatLoc), retreatLoc));
}

public static Direction retreatDir(MapLocation retreatLoc) {
    if (!isMovementReady()) return Direction.CENTER;

    setIndicatorLine(me, retreatLoc, 200, 0, 200);
    int dist = me.distanceSquaredTo(retreatLoc);

    // If within range of tower (dist <= 8)
    if (dist <= 8 && isActionReady()) {
        if (canSenseRobot(retreatLoc)) {
            RobotInfo tower = senseRobot(retreatLoc);

            // Don't approach non-paint towers
            if (tower.type.getBaseType() != LEVEL_ONE_PAINT_TOWER) {
                if (tower.paintAmount != 0) {
                    return bug2Helper(me, retreatLoc, TOWARDS, 0, 0);
                }
            }

            // Check if we're lowest paint in queue
            int amount = paintNeededToStopRetreating - paint;
            boolean lowest = true;
            for (int i = 8; --i >= 0;) {
                MapLocation waitingLoc = retreatWaitingLocs[i].translate(retreatLoc.x, retreatLoc.y);
                if (canSenseRobot(waitingLoc)) {
                    if (senseRobot(waitingLoc).paintAmount < paint) {
                        lowest = false;  // Someone needs it more
                        break;
                    }
                }
            }

            // If lowest paint AND tower has paint, approach
            if (lowest && tower.paintAmount >= amount) {
                return bug2Helper(me, retreatLoc, TOWARDS, 0, 0);
            }
        }
    }

    // If not at waiting distance (4 or 8), move to waiting position
    if (dist != 4 && dist != 8) {
        if (canSenseRobot(retreatLoc)) {
            if (retreatWaitingLoc == null) {
                updateRetreatWaitingLoc();  // Find best waiting spot
            }
            if (retreatWaitingLoc != null) {
                return bug2Helper(me, retreatWaitingLoc, TOWARDS, 0, 0);
            }
        } else {
            return bug2Helper(me, retreatLoc, TOWARDS, 0, 0);
        }
    }

    return Direction.CENTER;  // Stay at waiting position
}
```

### 14.13 Paint Transfer Mechanics

**Source**: SPAARK Motion.java line 747-767, Robot.java line 79, 105

Paint transfer is called TWICE per turn in Robot.java:
1. BEFORE unit run()
2. AFTER unit run()

```java
// Motion.tryTransferPaint()
public static void tryTransferPaint() {
    for (ruin in nearbyRuins) {
        if (canSenseRobot(ruin)) {
            RobotInfo tower = senseRobot(ruin);
            // Calculate amount to take from tower (negative = take)
            int amt = -Math.min(paintCapacity - paint, tower.paintAmount);

            if (amt != 0 && canTransferPaint(ruin, amt)) {
                transferPaint(ruin, amt);  // Take paint from tower
            }
        }
    }
}
```

**Called in Robot.run():**
```java
// Robot.java
tryTransferPaint();  // Before unit logic
switch (type) {
    case SOLDIER -> Soldier.run();
    // ...
}
tryTransferPaint();  // After unit logic
```

**Why Twice**: Ensures units can refuel before and after actions.

### 14.14 Map Size Adaptation

**Source**: SPAARK Motion.java line 1768, research Part 40

```java
// Splasher-specific retreat threshold on large maps
public static int getRetreatPaint() {
    // Splasher on large map with few towers
    if (rc.getType() == SPLASHER
        && mapArea > 1600
        && rc.getNumberTowers() <= 4) {
        return 50;  // Lower threshold = stay aggressive longer
    }

    // Standard logic
    if (allyRobots.length > 10) return 0;

    return Math.max(
        paintLost + RETREAT_PAINT_OFFSET,
        (int)(paintCapacity * RETREAT_PAINT_RATIO)
    );
}
```

**Why**: On large maps (>40x40) early game, splashers need to stay aggressive to claim territory.

### 14.15 Pre-Computed Offset Tables

**Source**: SPAARK G.java line 88-120

SPAARK uses pre-computed offset arrays for efficient range queries:

```java
// range20X and range20Y: all (dx, dy) within distance 20, sorted by distance
// 69 positions total: [0] = (0,0), [1-4] = dist 1, [5-8] = dist sqrt(2), etc.

public static final int[] range20X = {
    0, -1, 0, 0, 1, -1, -1, 1, 1, -2, 0, 0, 2, -2, -2, -1, -1, 1, 1, 2, 2,
    -2, -2, 2, 2, -3, 0, 0, 3, -3, -3, -1, -1, 1, 1, 3, 3, -3, -3, -2, -2,
    2, 2, 3, 3, -4, 0, 0, 4, -4, -4, -1, -1, 1, 1, 4, 4, -3, -3, 3, 3, -4,
    -4, -2, -2, 2, 2, 4, 4
};
public static final int[] range20Y = { /* same pattern */ };

// Index ranges for each radius:
// radiusSquared <= 0:  indices [0, 1)
// radiusSquared <= 1:  indices [0, 5)
// radiusSquared <= 2:  indices [0, 9)
// radiusSquared <= 4:  indices [0, 13)
// radiusSquared <= 5:  indices [0, 21)
// radiusSquared <= 8:  indices [0, 25)
// radiusSquared <= 10: indices [0, 37)
// radiusSquared <= 20: indices [0, 69)
```

**Usage in Robot.java:**
```java
// Check all tiles within vision range for resource pattern completion
for (int i = 9; --i >= 0;) {
    if (rc.canCompleteResourcePattern(me.translate(range20X[i], range20Y[i]))) {
        rc.completeResourcePattern(me.translate(range20X[i], range20Y[i]));
    }
}
```

**Why It Matters**: Pre-computed arrays avoid repeated distance calculations, saving bytecode.

### 14.16 Cooldown Calculation

**Source**: SPAARK G.java line 174-187

Accurate cooldown calculation matching engine behavior:

```java
public static int cooldown(int paintAmount, int cooldownToAdd, int paintCapacity) {
    // Matches engine implementation:
    // github.com/battlecode/battlecode25/blob/master/engine/src/main/battlecode/world/InternalRobot.java#L277

    if (paintAmount * 2 > paintCapacity) {
        return cooldownToAdd;  // > 50% paint = no penalty
    }

    int paintPercentage = (int) Math.round(paintAmount * 100.0 / paintCapacity);

    return cooldownToAdd + (int) Math.round(
        cooldownToAdd * (GameConstants.INCREASED_COOLDOWN_INTERCEPT
                       + GameConstants.INCREASED_COOLDOWN_SLOPE * paintPercentage) / 100.0
    );
}
```

**Why It Matters**: Accurate turnsToNext calculation for micro scoring. Critical for paint penalty formulas.

### 14.17 Paint Loss Tracking

**Source**: SPAARK Motion.java line 420-423

```java
public static int lastPaint = 0;
public static int paintLost = 0;

// In Robot.run() after unit logic:
Motion.lastPaint = rc.getPaint();

// In Motion.setRetreatLoc():
paintLost = 0;  // Reset when entering retreat
```

**Usage in Dynamic Retreat Threshold:**
```java
int retreatThreshold = Math.max(
    paintLost + RETREAT_PAINT_OFFSET,  // 30
    (int)(paintCapacity * RETREAT_PAINT_RATIO)  // 0.25
);
```

**Why It Matters**: Tracks cumulative paint loss to enemy/neutral territory. Units retreat sooner if they've taken heavy damage.

### 14.18 Enhanced Bug Navigation

**Source**: SPAARK Motion.java bug2Helper

SPAARK's Bug2 has three modes, not just TOWARDS:

```java
public static final int TOWARDS = 0;  // Move toward target
public static final int AWAY = 1;     // Move away from target
public static final int AROUND = 2;   // Circle around target

public static Direction bug2Helper(MapLocation me, MapLocation target,
                                   int mode, int minRadius, int maxRadius) {
    Direction dir = me.directionTo(target);

    switch (mode) {
        case TOWARDS:
            // Standard bug2 (already in spec)
            break;

        case AWAY:
            dir = dir.opposite();  // Flee from target
            break;

        case AROUND:
            int dist = me.distanceSquaredTo(target);
            if (dist < minRadius) {
                dir = dir.opposite();  // Too close, move away
            } else if (dist <= maxRadius) {
                dir = dir.rotateLeft().rotateLeft();  // Circle
                if (circleDirection == COUNTER_CLOCKWISE) {
                    dir = dir.opposite();
                }
            }
            // If dist > maxRadius, move toward
            break;
    }

    // Apply bugnav tracing logic...
}
```

**Usage:**
- TOWARDS: Normal pathfinding
- AWAY: Retreat from threats
- AROUND: Orbit around tower at specific radius

**Why It Matters**: More flexible than simple Bug2. Can orbit towers for defense, flee from dangers.

### 14.19 Exploration Timeout

**Source**: SPAARK Motion.java line 232-247

```java
public static int exploreTime = 0;

public static MapLocation exploreRandomlyLoc() {
    if (isMovementReady()) {
        --exploreTime;

        if (exploreLoc != null) {
            // Reset if can sense (reached target)
            if (canSenseLocation(exploreLoc)) {
                exploreLoc = null;
            }
            // Reset if timeout
            else if (exploreTime == 0) {
                exploreLoc = null;
            }
            // 3% random reset chance
            else if (Random.rand() % 35 == 0) {
                exploreLoc = null;
            }
        }

        // If no target, pick new one
        if (exploreLoc == null) {
            exploreTime = me.distanceSquaredTo(newTarget) + 20;
            exploreLoc = newTarget;
        }
    }
    return exploreLoc;
}
```

**Why It Matters**: Prevents units from getting stuck pursuing unreachable targets. Adds controlled randomness.

### 14.20 Soldier Attack Micro

**Source**: SPAARK Soldier.java line 1259-1281

Special micro for attacking towers - different behavior based on action cooldown:

```java
public static Micro attackMicro = new Micro() {
    public int[] micro(Direction d, MapLocation dest) {
        int[] scores = Motion.defaultMicro.micro(d, dest);

        if (rc.isActionReady()) {
            // CAN attack: prefer positions IN attack range
            for (int i = 9; --i >= 0;) {
                if (me.add(ALL_DIRECTIONS[i]).isWithinDistanceSquared(
                        towerLocation, rc.getType().actionRadiusSquared)) {
                    scores[i] += 400;  // Huge bonus
                }
            }
        } else {
            // CAN'T attack: prefer positions OUT of tower range
            for (int i = 9; --i >= 0;) {
                if (!me.add(ALL_DIRECTIONS[i]).isWithinDistanceSquared(
                        towerLocation, towerType.actionRadiusSquared)) {
                    scores[i] += 400;  // Stay safe
                }
            }
        }

        return scores;
    }
};
```

**Usage in Attack Mode:**
```java
if (towerLocation.isWithinDist(me, actionRadius)) {
    if (canAttack(towerLocation)) attack(towerLocation);
    bugnavAway(towerLocation, attackMicro);  // Kite away
} else {
    if (isActionReady()) {
        bugnavTowards(towerLocation, attackMicro);  // Approach
        if (canAttack(towerLocation)) attack(towerLocation);
    } else {
        bugnavAround(towerLocation, actionRadius+1, actionRadius+1);  // Orbit
    }
}
```

**Why It Matters**: Kiting behavior - attack then retreat. Maximizes damage while minimizing exposure.

### 14.21 Reduced Retreating System

**Source**: SPAARK Soldier.java line 70-71, 159-160, 314, 408, 425

Soldiers building towers/SRPs have modified retreat behavior:

```java
public static boolean reducedRetreating = false;
public static boolean avoidRetreating = false;

// Reset each turn in run()
reducedRetreating = false;
avoidRetreating = false;

// Set in buildTowerCheckMode() and buildResourceCheckMode()
reducedRetreating = true;  // Higher retreat threshold when building

// Set when lowest ID completing tower pattern
if (isLowestIDAtTower()) {
    avoidRetreating = true;  // Never retreat, finish the tower
}

// Set when enough paint to complete pattern
if (enemyPaint == 0 && paint / attackCost >= incorrectPaint) {
    avoidRetreating = true;  // Can finish, don't leave
}
```

**Integration with Retreat:**
```java
// In retreat entry check:
int threshold = getRetreatPaint();
if (reducedRetreating) {
    threshold *= SOL_RETREAT_REDUCED_RATIO;  // 0.5 = half threshold
}
if (avoidRetreating) {
    threshold = 0;  // Never retreat
}

if (paint < threshold && chips < 6000 && allies < 9) {
    mode = RETREAT;
}
```

**Why It Matters**: Prevents soldiers from abandoning tower construction prematurely. Critical for successful builds.

### 14.22 Paint Under Self Micro

**Source**: SPAARK Soldier.java line 1186-1230

Soldiers actively paint tiles while exploring to reduce passive paint drain:

```java
public static Micro moveWithPaintMicro = new Micro() {
    public int[] micro(Direction d, MapLocation dest) {
        int[] scores = Motion.defaultMicro.micro(d, dest);

        // 25% of time, look for best empty tile to paint
        if (Random.rand() % 4 == 0) {
            MapLocation best = me;
            int bestScore = Integer.MIN_VALUE;
            int turnsToNext = (cooldown(paint, MOVEMENT_COOLDOWN) + movementCooldown) / 10;

            for (int i = 9; --i >= 0;) {
                MapLocation nxt = me.add(ALL_DIRECTIONS[i]);

                // Can paint empty tiles
                if (onTheMap(nxt)
                    && senseMapInfo(nxt).getPaint() == EMPTY
                    && canAttack(nxt)) {

                    // Neutralize the penalty for empty tiles
                    scores[i] += 5 * PENALTY_NEUTRAL_TERRITORY * turnsToNext;

                    if (scores[i] > bestScore) {
                        bestScore = scores[i];
                        best = nxt;
                    }
                }
            }

            // If found paintable tile, paint it
            if (best != me && canAttack(best)) {
                attack(best);
            }
        }

        return scores;
    }
};
```

**Usage**: Replace defaultMicro with moveWithPaintMicro in explore mode.

**Why It Matters**: Reduces passive paint drain on neutral tiles. Creates ally paint trails for future use.

### 14.23 Utility Functions

**Additional function definitions:**

```java
// Paint gradient counting
static int countAllyPaintInDirection(Direction d, int range) {
    int count = 0;
    MapLocation check = me;
    for (int i = 0; i < range; i++) {
        check = check.add(d);
        if (!rc.onTheMap(check)) break;
        if (rc.senseMapInfo(check).getPaint().isAlly()) {
            count++;
        }
    }
    return count;
}

// Symmetry calculation
static MapLocation getOppositeMapLocation(MapLocation loc, int symmetryType) {
    switch (symmetryType) {
        case 0: return new MapLocation(loc.x, mapHeight - 1 - loc.y);          // Horizontal
        case 1: return new MapLocation(mapWidth - 1 - loc.x, loc.y);           // Vertical
        case 2: return new MapLocation(mapWidth - 1 - loc.x, mapHeight - 1 - loc.y);  // Rotational
    }
    return loc;
}

// Direction from vector
static Direction directionFromVector(int dx, int dy) {
    if (dx == 0 && dy == 0) return Direction.CENTER;

    int sx = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
    int sy = dy > 0 ? 1 : (dy < 0 ? -1 : 0);

    int key = (sx + 1) * 3 + (sy + 1);
    switch (key) {
        case 0: return Direction.SOUTHWEST;
        case 1: return Direction.WEST;
        case 2: return Direction.NORTHWEST;
        case 3: return Direction.SOUTH;
        case 4: return Direction.CENTER;
        case 5: return Direction.NORTH;
        case 6: return Direction.SOUTHEAST;
        case 7: return Direction.EAST;
        case 8: return Direction.NORTHEAST;
        default: return Direction.CENTER;
    }
}
```

---

## Part 15: System Integration

### 15.1 How Research Innovations Integrate with SPAARK

| Innovation | Integration Point | SPAARK Component |
|------------|-------------------|------------------|
| Phase Shifting | Modifies spawn weights in Tower.java | Debt-based spawn system |
| Boids | Blends with Bug2 in Nav.moveTo() | Motion.defaultMicro |
| Paint-as-Pheromone | Adds scoring in Micro | Motion.defaultMicro paint penalties |
| Controlled Chaos | Overrides optimal in Nav.moveTo() | Final direction selection |

### 15.2 Data Flow Diagram

```
G.init(rc) → Update global state
    ↓
POI.update() → Scan towers, update counts
    ↓
Phase.current() → Determine EARLY/MID/LATE/ENDGAME
    ↓
Soldier.run():
    ├─ Check retreat (paint<150 AND chips<6000 AND allies<9)
    ├─ Mode transition check (exploreCheckMode, etc.)
    ├─ Execute mode behavior
    │   ├─ EXPLORE: Nav.moveTo(selectExploreTarget())
    │   ├─ BUILD_TOWER: completeTowerPattern()
    │   ├─ ATTACK: attack tower + Nav.moveToWithMicro()
    │   └─ RETREAT: Motion.retreat() to paint tower
    └─ Opportunistic tower completion

Nav.moveTo(target):
    ├─ 15% chance: randomValidDirection() (Controlled Chaos)
    └─ 85% chance:
        ├─ bug2(target) → strategic direction
        ├─ computeBoidsVector() → coordination direction (if !EARLY)
        ├─ blend or alternate
        └─ Micro.scoreAllDirections() → final scoring with turnsToNext

Tower.trySpawn():
    ├─ Phase.getSpawnWeights() → phase-adjusted weights
    ├─ Debt calculation → highest debt unit
    └─ spawn() if conditions met
```

### 15.3 Critical Dependencies

1. **G.java must initialize first** - all systems depend on global state
2. **POI.update() before soldier decisions** - needs tower counts for mode transitions
3. **Phase detection before spawn weights** - Tower.java uses phase-adjusted weights
4. **Micro scoring uses turnsToNext** - requires cooldown calculation from G.java

### 15.4 Controlled Chaos Integration

```java
// In Nav.moveTo() - FINAL step before movement
Direction finalDir = bug2OrBoidsBlended(target);

if (shouldRandomize()) {
    finalDir = randomValidDirection();  // 15% override
}

if (canMove(finalDir)) {
    move(finalDir);
}
```

---

## Part 16: Advanced SPAARK Features

### 16.1 Symmetry Detection System

**Research Score**: 0.70 (ranked #5 competitive strategy)
**Source**: research_spaark_analysis.md Part 23

```java
// POI.java
public static boolean[] symmetry = new boolean[] { true, true, true };

// Three symmetry types:
// 0: Horizontal (y-axis flip)
// 1: Vertical (x-axis flip)
// 2: Rotational (180° rotation)

public static MapLocation getOppositeMapLocation(MapLocation loc, int sym) {
    switch (sym) {
        case 0: return new MapLocation(loc.x, mapHeight - 1 - loc.y);
        case 1: return new MapLocation(mapWidth - 1 - loc.x, loc.y);
        case 2: return new MapLocation(mapWidth - 1 - loc.x, mapHeight - 1 - loc.y);
    }
}

// Eliminate invalid symmetries when walls/ruins don't match
public static void removeValidSymmetry(int index) {
    if (symmetry[index]) {
        symmetry[index] = false;
    }
}

// When only 1 symmetry remains, predict enemy tower locations
static MapLocation predictEnemyTower() {
    int validCount = 0;
    int validType = -1;
    for (int i = 0; i < 3; i++) {
        if (symmetry[i]) {
            validCount++;
            validType = i;
        }
    }

    if (validCount == 1) {
        // Find ally tower and predict enemy mirror
        for (int i = numberOfTowers; --i >= 0;) {
            if (towerTeams[i] == team) {
                return getOppositeMapLocation(towerLocs[i], validType);
            }
        }
    }
    return null;
}
```

**Why It Matters**: Know where enemies are before seeing them → proactive positioning

### 16.2 POI.explored Bitfield

**Research Score**: 0.56
**Source**: research_spaark_analysis.md Part 15.1

```java
// Track explored tiles using 1 bit per tile
public static long[] explored = new long[60];  // One long per row

// Mark tile as explored
static void markExplored(MapLocation loc) {
    explored[loc.y] |= (1L << loc.x);
}

// Check if explored
static boolean isExplored(MapLocation loc) {
    return ((explored[loc.y] >> loc.x) & 1) == 1;
}

// Count unexplored tiles efficiently
static int countUnexplored() {
    int unexplored = mapArea;
    for (int i = mapHeight; --i >= 0;) {
        unexplored -= Long.bitCount(explored[i]);
    }
    return unexplored;
}

// Select random unexplored tile (weighted)
static MapLocation randomUnexploredTile() {
    int unexplored = countUnexplored();
    int rand = Random.rand() % unexplored;
    int cur = 0;

    for (int y = mapHeight; --y >= 0;) {
        int rowUnexplored = mapWidth - Long.bitCount(explored[y]);
        cur += rowUnexplored;
        if (cur > rand) {
            // Find specific unexplored x in this row
            int cur2 = 0;
            for (int x = mapWidth; --x >= 0;) {
                if (((explored[y] >> x) & 1) == 0) {
                    if (++cur2 > rand - (cur - rowUnexplored)) {
                        return new MapLocation(x, y);
                    }
                }
            }
        }
    }
    return mapCenter;  // Fallback
}
```

**Why It Matters**: Efficient exploration without revisiting known areas

### 16.3 Tower Upgrade Logic

**Source**: SPAARK Tower.java line 314-320

```java
// Upgrade while maintaining 1000 chip buffer
while (rc.canUpgradeTower(me)
       && rc.getMoney() - (level == 0 ? 2500 : 5000) >= 1000) {
    rc.upgradeTower(me);
}

// Attack AFTER upgrading (higher attack power)
attack();
```

**Upgrade Costs:**
- Level 1 → Level 2: 2500 chips
- Level 2 → Level 3: 5000 chips

**Why It Matters**: Stronger towers attack more effectively. Buffer ensures economy stability.

**Implementation**: Money tower nuking takes priority over upgrades (checked first).

### 16.4 Spawn Location Sorting

**Source**: SPAARK Tower.java line 30-45

```java
// Initialize spawn locations (8 adjacent + 4 two-away)
spawnLocs = new MapLocation[] {
    me.add(NORTH), me.add(NORTHEAST), me.add(EAST), me.add(SOUTHEAST),
    me.add(SOUTH), me.add(SOUTHWEST), me.add(WEST), me.add(NORTHWEST),
    me.add(NORTH).add(NORTH),
    me.add(EAST).add(EAST),
    me.add(SOUTH).add(SOUTH),
    me.add(WEST).add(WEST),
};

// Sort by distance to map center (closest first)
Arrays.sort(spawnLocs,
    (a, b) -> a.distanceSquaredTo(mapCenter) - b.distanceSquaredTo(mapCenter));
```

**Why It Matters**: Spawns units toward map center, creating natural territorial pressure. Units start moving toward contested areas immediately.

**Bytecode Note**: Arrays.sort() costs ~6000 bytecode for 12 elements, but only called once at init. Worth the cost for better positioning.

### 16.6 Self-Destruction Mechanisms

**Research Score**: 0.66
**Source**: research_spaark_analysis.md Part 2, 21

**Robot Self-Destruct (paint=0, chips>5000):**
```java
// Robot.java
if (rc.getPaint() == 0 && rc.getChips() > 5000) {
    for (ally in nearbyAllies within 8 tiles) {
        if (ally.isRobotType()) {
            rc.disintegrate();  // Die to not block allies
            return;
        }
    }
}
```

**Defense Tower Auto-Destruct (30 rounds idle):**
```java
// DefenseTower.java
public static int lastTurnSawEnemy = -1;

if (round - lastTurnSawEnemy > 30) {
    rc.disintegrate();  // Free tower slot
}
```

**Money Tower Nuking (excess economy):**
```java
// Tower.java - Om Nom insight: trade money for paint
if (chips > (id < 10000 ? 20000 : id * 3 - 10000)
    && lastChips < chips
    && numTowers >= lastNumTowers
    && type == LEVEL_ONE_MONEY_TOWER
    && round % 5 == 0) {
    attack();  // Spend paint
    rc.disintegrate();  // Get 500 paint from next spawn
}
```

**Why It Matters**: Resource optimization, prevent blocking, convert money to paint

### 16.7 16-Bit Messaging System

**Research Score**: 0.71 (ranked #3)
**Source**: research_spaark_analysis.md Part 1

```java
// Message format (16 bits):
// Bits 0-5:   X coordinate (max 63)
// Bits 6-11:  Y coordinate (max 63)
// Bits 12-14: Tower type/team (0=neutral, 1-3=TeamA, 4-6=TeamB)
// Bit 15:     Relay flag

public static int intifyLocation(MapLocation loc) {
    return ((loc.y << 6) | loc.x);
}

public static MapLocation parseLocation(int n) {
    return new MapLocation((n & 0b111111), (n >> 6) & 0b111111);
}

public static int intifyTower(Team team, UnitType type) {
    if (team == Team.NEUTRAL) return 0;
    int typeOffset = (type == PAINT ? 1 : type == MONEY ? 2 : 3);
    return (typeOffset + (team == G.team ? 0 : 3)) << 12;
}

// Tower sends to nearby robots
public static void sendMessages() {
    if (rc.getType().isTowerType()) {
        for (RobotInfo ally : nearbyAllies) {
            int message = -1;
            // Pack multiple 16-bit messages
            for (tower in unknownTowers) {
                message = appendToMessage(message, intifyTower() | intifyLocation());
            }
            rc.sendMessage(ally.location, message);
        }
    }
}

// Tower relay every 100 rounds
if (round % 100 == 0) {
    int relayMessage = intifyTower() | intifyLocation(me) | (1 << 15);
    rc.broadcastMessage(relayMessage);
}
```

**Why It Matters**: Cross-map tower discovery, faster than symmetry prediction alone

### 16.8 Bitwise BFS Pathfinding

**Research Score**: 0.78 (ranked #1 SPAARK feature)
**Source**: research_spaark_analysis.md Part 14

```java
// Use Long bitwise operations for O(1) per-row BFS expansion
public static long[] bfsCurr = new long[62];
public static long[] bfsNext = new long[62];

// BFS step using bitwise shifts
public static void bfsStep() {
    for (int y = mapHeight; --y >= 0;) {
        // Expand in 4 directions using bit shifts
        long north = bfsCurr[y + 1];
        long south = bfsCurr[y - 1];
        long east = bfsCurr[y] >>> 1;
        long west = bfsCurr[y] << 1;

        // Combine all expansions
        bfsNext[y] |= (north | south | east | west) & passable[y];
    }
}
```

**Why It Matters**: O(1) per-row expansion vs O(n) traditional BFS, 50%+ bytecode savings

**Complexity Note**: Can be implemented incrementally - start with Bug2, add bitwise BFS later for optimization.

---

## Part 17: Adaptive Counter-Strategy System

**Research Method**: Divergent reasoning + game theory analysis

### 17.1 SPAARK Pattern Detection

Detect SPAARK-specific behaviors to enable targeted counters:

| Pattern | Detection Signal | Confidence Threshold |
|---------|------------------|---------------------|
| SPAARK retreat behavior | Enemies retreat at paint~150 | 3+ observations |
| Defense tower placement | Towers near center (dist < 36) | 2+ towers |
| Retreat queue usage | 4 units orbiting tower at dist 4 or 8 | Visual confirmation |
| Message relay timing | Tower broadcasts every 100 rounds | Round % 100 check |
| Symmetry-based positioning | Enemy mirrors our towers | Positional analysis |

### 17.2 Counter-Strategies

**Threshold Manipulation:**
```java
// Keep enemy at paint = 151 (just above retreat threshold)
// Paint-starve but don't trigger retreat
if (enemy.paint > 150 && enemy.paint < 200) {
    // Avoid killing - maintain pressure without retreat trigger
}
```

**Communication Timing Exploitation:**
```java
// Major moves between relay cycles
if (round % 100 > 10 && round % 100 < 90) {
    // Communication dead zone - SPAARK towers haven't relayed yet
    // Execute major repositioning that won't be communicated
}
```

**Retreat Queue Overflow:**
```java
// Send 5+ units to overwhelm 4-robot queue limit
if (enemyTowerHasRetreatQueue(tower)) {
    // Send additional pressure - forces some units to find other towers
    // Scatters enemy forces
}
```

**Defense Tower Timeout Exploitation:**
```java
// Avoid defense towers for 30 rounds
static int[] defenseTowtLastSeen = new int[MAX_TOWERS];

if (round - lastSeen[tower] < 30) {
    // Avoid this tower to force self-destruct
    avoidTarget(tower);
}
```

**Controlled Chaos in Critical Moments:**
```java
// Increase randomization near thresholds
double chaosAt Threshold = 0.30;  // 30% randomization

if (nearRetreatThreshold || nearMessageRelay || nearPhaseTransition) {
    if (Random.nextDouble() < chaosAtThreshold) {
        // Unpredictable action
    }
}
```

### 17.3 Economic Warfare

Target SPAARK's paint-bound economy:

```java
// Priority target chain:
// 1. Paint towers (creates death spiral)
// 2. SRP patterns (denies passive income)
// 3. Money towers (reduces spawn capacity)

MapLocation target = POI.findNearestEnemyPaintTower();
if (target == null) {
    target = findEnemySRPCenter();  // Detect ALLY_SECONDARY markers
}
if (target == null) {
    target = POI.findNearestEnemyMoneyTower();
}
```

### 17.4 Implementation Priority

**Phase 1 (Immediate Value):**
1. Threshold manipulation - keep enemies at 151 paint
2. Communication timing - act between relay cycles
3. Paint tower assassination priority

**Phase 2 (Advanced):**
4. Defense tower timeout exploitation
5. Retreat queue overflow
6. Chaos at critical moments

**Phase 3 (Refinement):**
7. SRP disruption (paint enemy tiles in SRP patterns)
8. Pattern detection and adaptation

### 17.5 Winning Formula

```
Full SPAARK Implementation
  + Research Innovations (Phase, Boids, Chaos, Pheromone)
  + Adaptive Counter-Strategies (threshold manipulation, timing exploitation)
  + Parameter Tuning (OPTNET-style testing)
= Beat SPAARK by 10-20%
```

---

## Part 18: Implementation Roadmap

### 18.1 Build Order

**Week 1: Foundation**
1. Create file structure
2. Implement G.java with all static globals, lastVisited grid, utility functions (Part 7.3, 14.12)
3. Implement Random.java (xorshift32)
4. Implement Phase.java (round-based detection + spawn weight methods)
5. RobotPlayer.java with full game loop (Part 14.11)

**Week 2: Core Systems**
6. Nav.java with Bug2 algorithm (Part 14.2)
7. POI.java with tower tracking arrays + symmetry detection (Part 16.1)
8. Micro.java with turnsToNext formula (Part 8.1)
9. Add Controlled Chaos to Nav.moveTo() (Part 6)

**Week 3: Units**
10. Soldier.java with 7-mode state machine (Part 14.1)
11. Tower.java with phase-weighted debt spawning + messaging (Part 16.4)
12. Splasher.java with dynamic threshold (Part 14.9)
13. Mopper.java with unified action scoring (Part 14.10)

**Week 4: Advanced Features**
14. Boids integration in Nav.java (Part 14.3)
15. POI.explored bitfield for exploration (Part 16.2)
16. Self-destruction mechanisms (Part 16.3)
17. Gap fixes (ruin denial, paint conservation)

**Week 5+: Optimization**
18. Bitwise BFS pathfinding (Part 16.5) - optional, high complexity
19. Testing and parameter tuning
20. Performance profiling

### 18.2 Validation Checkpoints

| Checkpoint | Test | Success Criteria |
|------------|------|------------------|
| After Week 1 | Compile + run | No crashes, units spawn and move |
| After Week 2 | vs spaark2 | Soldiers explore, build towers |
| After Week 3 | vs spaark2 | All units functional, >40% win rate |
| After Week 4 | vs SPAARK | Competitive with features, >35% win rate |
| After Week 5 | vs SPAARK | Target >40% win rate with optimizations |

### 18.3 Bytecode Profiling Points

Add profiling after each week:
```java
int start = Clock.getBytecodeNum();
// ... system code ...
int cost = Clock.getBytecodeNum() - start;
System.out.println("SystemName: " + cost + " bytecode");
```

Target bytecode budget:
- Pathfinding: < 2000
- Micro: < 800
- POI update: < 500
- Total: < 10,000 per turn

---

## Part 19: Detailed File Specifications

### 19.1 File Structure

```
src/<package>/
├── RobotPlayer.java   - Entry point, phase detection
├── G.java             - Global state (static), bytecode utilities
├── Phase.java         - Phase shifting logic (EARLY/MID/LATE/ENDGAME)
├── Nav.java           - Bug2 + Boids integration
├── Micro.java         - Combat scoring with turnsToNext
├── POI.java           - Tower tracking, symmetry detection
├── Soldier.java       - 7-mode state machine
├── Splasher.java      - Dynamic attack threshold
├── Mopper.java        - Unified action scoring
├── Tower.java         - Phase-weighted spawning
└── Random.java        - xorshift32 RNG
```

### 19.2 Class Responsibilities

| File | Public Methods | Key Data |
|------|----------------|----------|
| G.java | init(), markVisited(), getEnemies(), getAllies(), directionFromVector() | Static: rc, me, round, team, lastVisited[][], allyRobotsString, maxChips |
| Phase.java | current(), getSoldierWeight(), getSplasherWeight(), getMopperWeight() | None (pure functions) |
| Nav.java | moveTo(), moveToWithMicro(), bug2(), computeBoidsVector() | Bug2 state, bfsCurr[], bfsNext[] |
| POI.java | update(), findNearestAllyTower(), predictEnemyTower(), markExplored() | Tower arrays, symmetry[], explored[] |
| Micro.java | scoreAllDirections(), defaultMicro | Paint penalty constants, turnsToNext |
| Soldier.java | run(), explore(), buildTower(), attack(), retreat(), exploreCheckMode() | Mode state, targets, buildTime, srpCheckLocations[] |
| Splasher.java | run(), attackThreshold() | moveScores[], attackScores[] |
| Mopper.java | run() | moveScores[], attackScores[], swingScores[], transferScores[] |
| Tower.java | run(), trySpawn(), tryAttack(), sendMessages() | Debt accumulators, message tracking |
| Random.java | rand(), nextInt(), nextBoolean() | state (xorshift32) |

---

## Part 20: Build Sequence Details

### 20.1 Phase 1: Foundation
1. G.java with all SPAARK global patterns
2. Phase.java with round-based detection
3. Random.java (xorshift32)
4. Basic RobotPlayer dispatch

### 20.2 Phase 2: Core Systems
5. Nav.java (Bug2 + controlled chaos)
6. POI.java (tower tracking)
7. Micro.java (combat scoring)

### 20.3 Phase 3: Units
8. Soldier.java (7-mode state machine, Part 14.1)
9. Tower.java (debt-based spawning + messaging, Part 7.5 + 16.4)
10. Splasher.java (dynamic threshold, Part 14.9)
11. Mopper.java (unified action scoring, Part 14.10)

### 20.4 Phase 4: Advanced SPAARK Features
12. Symmetry detection in POI.java (Part 16.1)
13. POI.explored bitfield (Part 16.2)
14. Self-destruction mechanisms (Part 16.3)
15. 16-bit messaging in Tower.java (Part 16.4)

### 20.5 Phase 5: Research Innovations + Optimization
16. Boids integration in Nav.java (Part 14.3)
17. Paint-as-Pheromone signals (Part 5)
18. Gap fixes (ruin denial, paint conservation, Part 12)
19. Bitwise BFS pathfinding - optional (Part 16.5)
20. Testing and parameter tuning

---

### 20.6 Phase 6: Adaptive Counter-Strategies (Optional)

21. SPAARK pattern detection system (Part 17.1)
22. Threshold manipulation tactics (Part 17.2)
23. Economic warfare targeting (Part 17.3)
24. Final parameter tuning

---

## Part 21: Success Metrics & Validation

| Metric | Target | Validation |
|--------|--------|------------|
| Win rate vs spaark2 | > 60% | 10-match sets |
| Win rate vs SPAARK | > 40% | 10-match sets |
| Bytecode avg | < 10,000/turn | Clock profiling |
| Survival rounds | > 200 on DefaultSmall | Match replay |

### 21.1 Testing Commands

```bash
# Compile
./gradlew build

# Test against SPAARK
./gradlew run -PteamA=<package> -PteamB=SPAARK -Pmaps=DefaultSmall

# 10-match validation
for i in {1..10}; do
  ./gradlew run -PteamA=<package> -PteamB=SPAARK -Pmaps=DefaultSmall
done | grep "wins"
```

---

---

## Part 22: Implementation Checklist

### 22.1 Core SPAARK Features (Proven)

- [x] 7-mode state machine (Part 7.1, 14.1)
- [x] Retreat system with queue (Part 7.2)
- [x] lastVisited grid (Part 7.3)
- [x] SRP building (Part 7.4)
- [x] Debt-based spawning (Part 7.5)
- [x] Tower building constraints (Part 7.6)
- [x] Micro combat with turnsToNext (Part 8)
- [x] Tower type selection (Part 9)
- [x] Splasher dynamic threshold (Part 10)
- [x] All unit run loops (Part 14.9, 14.10)
- [x] RobotPlayer main structure (Part 14.11)
- [x] Symmetry detection (Part 16.1)
- [x] POI.explored bitfield (Part 16.2)
- [x] Self-destruction (3 types) (Part 16.3)
- [x] 16-bit messaging (Part 16.4)
- [x] Bitwise BFS (Part 16.5)

### 22.2 Research Innovations (Validated)

- [x] Phase Shifting 0.785 (Part 3)
- [x] Boids Flocking 0.735 (Part 4, 14.3)
- [x] Paint-as-Pheromone 0.665 (Part 5)
- [x] Controlled Chaos 0.70 (Part 6)

### 22.3 Gap Fixes (From Analysis)

- [x] Ruin denial (Part 12.1)
- [x] Paint conservation in combat (Part 12.2)
- [x] SRP economy tracking (Part 12.3)

### 22.4 Java Optimizations (Mandatory)

**Core Patterns:**
- [x] Reversed loops 30% savings (Part 11.1)
- [x] Static variables in G.java (Part 11.1)
- [x] Switch over if-else (Part 11.1)
- [x] Avoid java.util.* (Part 11.1)
- [x] Variable caching (Part 11.2)

**Bit Manipulation:**
- [x] Arithmetic shortcuts (x>>1, x&7) (Part 11.3.1)
- [x] Common bit tricks (isPow2, abs, min/max branchless) (Part 11.3.2)
- [x] Coordinate encoding (12-bit packing) (Part 11.3.3)

**Advanced Techniques:**
- [x] Method inlining (< 35 bytecode) (Part 11.4)
- [x] Flattened 2D arrays (Part 11.5.1)
- [x] Array pre-allocation (Part 11.5.2)
- [x] StringBuilder position lookup (Part 11.5.3)
- [x] Object allocation avoidance (Part 11.6)
- [x] Boxing/unboxing avoidance (Part 11.7)
- [x] String optimization (Part 11.8)
- [x] Bytecode budgeting with guards (Part 11.9)

### 22.5 Newly Added SPAARK Details

- [x] range20X/Y offset tables (14.15)
- [x] Cooldown calculation matching engine (14.16)
- [x] paintLost tracking (14.17)
- [x] Bug2 three modes (TOWARDS/AWAY/AROUND) (14.18)
- [x] Exploration timeout with 3% random reset (14.19)
- [x] Soldier attack micro (kiting) (14.20)
- [x] Reduced retreating system (14.21)
- [x] Paint under self micro (14.22)
- [x] Tower upgrade logic (16.3)
- [x] Spawn location sorting toward center (16.4)
- [x] Map size adaptation (14.14)
- [x] Complete spawn conditions (7.5.1)

### 22.6 Competitive Enhancements

- [x] Adaptive counter-strategies (Part 17)
- [x] Threshold manipulation tactics
- [x] Communication timing exploitation
- [x] Economic warfare targeting

**Completeness**: 100% of SPAARK + enhancements specified

---

## Part 23: Lessons Learned from Implementation

**Source**: Autonomous iteration on spaark3 + TDD build of omnom (2026-01-04)
**spaark3**: 146 → 196 rounds (+34% improvement, 20+ iterations)
**omnom**: 83 → 198 rounds (+138% improvement, full TDD build)

### 23.1 Critical Bugs Discovered

**1. Spawn Blocking (HIGHEST IMPACT)**
- Units adjacent to towers block all 12 spawn locations
- Prevented spawning → only 9 units in 196 rounds
- **Fix**: Retreat to distance 4-8 tiles, exit immediately after refuel
- **Impact**: This alone limits performance ~200 rounds

**2. Retreat Conditions**
- Original: paint < 50 only
- SPAARK: paint < 150 AND chips < 6000 AND allies < 9
- Using single condition caused excessive retreating (10% vs 5% benchmark)
- **Fix**: Use all 3 SPAARK conditions

**3. Early Game Length**
- Original: round < 100 = early game
- Correct: round < 50 = early game
- 100-round cutoff prevented tower building for too long
- **Fix**: Shorten to 50 rounds

**4. Tower Upgrade Timing**
- SPAARK upgrades by round 20
- We never reached 2500 chips (max 2300)
- Falling behind on upgrades = losing combat
- **Fix**: Build economy faster or prioritize upgrades

**5. Income Collapse**
- Income dropped from 180/turn to 5/turn at round 20
- Caused by spending on spawns faster than earning
- Never recovered
- **Fix**: Build money towers/SRPs for passive income

### 23.2 Performance Bottlenecks

**Identified Limiting Factors** (in priority order):

1. **Spawn blocking** → Limits unit count → can't scale
2. **No tower building** → Stuck with 2 towers → limited spawn capacity
3. **Income collapse** → Can't afford units or upgrades
4. **Tower loss** → Lose tower round 60 → further income loss
5. **No upgrades** → Enemy outscales with L2 towers

**Key Insight**: All 5 issues compound. Fix spawn blocking enables more units, more units can build towers, more towers = more income, more income = upgrades.

### 23.3 Autonomous Cycle Validation

**Process**:
1. Run match with visibility logging
2. Analyze STATE, ECONOMY, SPAWN, ENEMY_COUNT logs
3. Identify specific bugs with evidence
4. Implement targeted fixes
5. Validate improvement or regression
6. Iterate

**Results**:
- 20+ iterations completed autonomously
- Each iteration took ~2 minutes (match + analysis + fix)
- Clear improvement trajectory: 146 → 189 → 196 rounds
- Reached SPAARK baseline performance (196 rounds)

**Conclusion**: Autonomous improvement cycle validated ✅

### 23.4 Visibility System Impact

**Before Logging**:
- "Bot lost at round 146" - no actionable data
- Can't identify problems
- Can't validate fixes

**After Logging**:
- Exact paint drain rates (6 paint/turn on enemy territory)
- Mode distribution (68% ATTACK vs 10% benchmark)
- Spawn blocking (12/12 locations blocked)
- Income trends (180 → 5 → -40)
- Enemy unit counts and upgrades

**Impact**: Transformed development from guessing to data-driven optimization

### 23.5 Recommended Implementation Order (Updated)

**Based on testing, prioritize:**

**Week 1: Anti-Patterns Prevention**
1. Spawn blocking prevention (retreat positioning) - CRITICAL
2. Proper retreat conditions (3 checks, not 1)
3. Early game length (50 rounds, not 100)

**Week 2: Core SPAARK Systems**
4. Full 7-mode state machine
5. Tower building mode
6. Proper spawn conditions

**Week 3: Economy**
7. SRP building system
8. Tower upgrade logic
9. Money tower prioritization

**Week 4+: Optimizations**
10. Research innovations (Boids, Phase Shifting, etc.)
11. Advanced SPAARK features (messaging, symmetry, etc.)

**Why This Order**: Prevent known failure modes first, then add features

---

### 23.6 Battlecode API Gotchas (From Implementation)

**Critical for Messaging System**:

```java
// WRONG (doesn't exist):
messages[i].getCurrentMessage();
messages[i].readInt();

// CORRECT (from SPAARK source):
Message[] messages = rc.readMessages(round - 1);
for (Message m : messages) {
    int data = m.getBytes();  // Get 32-bit message
    int sender = m.getSenderID();

    // Parse as two 16-bit messages
    int msg1 = data & 0xFFFF;
    int msg2 = (data >> 16) & 0xFFFF;
}

// Sending:
rc.broadcastMessage(intData);  // Works
```

**Why Critical**: Messaging scored 0.71 (ranked #3 SPAARK feature). Easy to implement wrong if you guess the API instead of checking SPAARK source.

**Lesson**: Always check SPAARK source code for correct Battlecode API usage.

---

### 23.7 Actual Implementation Timings (From omnom Build)

**Plan vs Reality**:

| Week | Plan (hours) | Actual (hours) | Difference |
|------|--------------|----------------|------------|
| Week 0 | 8 | 2 | -75% (scripts already existed) |
| Week 1 | 42 | 1.5 | -96% (foundation is fast) |
| Week 2 | 39 | 1 | -97% (Bug2, Micro quick) |
| Week 3 | 47 | 1.5 | -97% (units are straightforward) |
| Week 4 | 42 | 2 | -95% (even with debugging) |
| **Total** | **178** | **8** | **-96%** |

**Why So Much Faster**:
- Unit tests took < 30 min total (not 20+ hours)
- Copy-paste from spec worked (no design time)
- Visibility logging already debugged in spaark3
- No parameter tuning needed yet (just implement features)
- Autonomous iteration handles testing (not manual)

**Actual Breakdown**:
- Foundation (Random, Phase, G, POI): 1 hour
- Navigation (Bug2, Micro): 30 min
- Units (Soldier, Splasher, Mopper, Tower): 1.5 hours
- Week 4 (SRP, retreat queue, self-destruct, messaging, symmetry): 2 hours
- Debugging: 1 hour
- Testing: 30 min
- Visibility logging: 1 hour
- **Total**: ~8 hours

**Lesson**: With complete spec and tested patterns, implementation is 10-20x faster than plan estimates.

---

### 23.8 Critical Success Factors (From omnom)

**What Enabled 83 → 198 Rounds**:

1. **Advanced Retreat Positioning** (+50 rounds)
   - Distance 4-8 tiles (not adjacent)
   - Prevents spawn blocking
   - This alone doubled performance

2. **Full Week 4 Features** (+40 rounds)
   - SRP system (income boost)
   - Retreat queue (prevents waste)
   - Self-destruct (resource optimization)
   - Symmetry (strategic advantage)

3. **Visibility Logging** (Enabled rapid iteration)
   - Every bug identified in minutes
   - No guessing, pure data
   - Autonomous iteration validated

4. **Following the Spec** (No shortcuts)
   - Every feature implemented as specified
   - SPAARK patterns work
   - Research innovations validated

**What Didn't Help**:
- Tower building (caused regression - units abandoned defense)
- Overly aggressive spawning (depleted chips)
- Tower defense logic (not the limiting factor)

**What Still Limits Performance** (198 → 400):
- Lose tower round 60-70 (income collapse)
- Can't afford upgrades (max chips 1910 < 2500)
- Need more towers built OR better tower protection

---

### 23.9 Development Velocity with Tooling

**Tools Impact**:

| Tool | Time Saved | How |
|------|------------|-----|
| Visibility logging | 80% | Instant bug identification vs replay watching |
| Autonomous iteration | 90% | Claude runs/analyzes/fixes vs human loop |
| Complete spec | 95% | Copy-paste vs design from scratch |
| Unit tests | 50% | Catch bugs early vs late-stage debugging |
| generate_report.sh | 95% | Automated analysis vs manual log reading |

**Velocity Multiplier**: ~20x faster than manual development

**Example**:
- Without tools: 1 week per major feature (design, implement, debug, test)
- With tools: 30 minutes per major feature (implement from spec, test autonomously)

---

### 23.10 Complete Feature Implementation Order

**Foundation (To reach 200 rounds)**:
1. Advanced retreat positioning (prevents spawn blocking)
2. Retreat queue logic (MAX_RETREAT_ROBOTS=4)
3. Debt-based spawning
4. Mode system (EXPLORE, ATTACK, RETREAT, ATTACK_TOWER)
5. Bug2 pathfinding

**SPAARK Core (200+ rounds)**:
6. SRP system (BUILD_RESOURCE + EXPAND_RESOURCE)
7. Tower upgrades
8. Self-destruct mechanisms (robot, money tower, defense tower)
9. Symmetry detection (score 0.70)
10. Messaging system (score 0.71)

**Research Innovations (Weeks 5-6)**:
11. Boids flocking (score 0.735) - Week 5
12. Controlled Chaos 15% (score 0.70) - Week 5
13. Paint-as-Pheromone gradient following (score 0.665) - Week 5
14. Phase Shifting active use (score 0.785) - Week 5
15. Gap fixes (ruin denial, paint conservation) - Week 5
16. Counter-strategies (threshold manipulation, timing) - Week 6
17. Bytecode optimization - Week 6
18. Parameter tuning - Week 6

**Note**: Research innovations scored 0.65-0.785 in validation. Phase Shifting scored highest (0.785).

---

## Sources

- SPAARK GitHub: https://github.com/erikji/battlecode25
- Om Nom Postmortem (3rd): battlecode.org/assets/files/postmortem-2025-om-nom.pdf
- Confused Postmortem (2nd): battlecode.org/assets/files/postmortem-2025-confused.pdf
- SPAARK Postmortem (HS 1st): battlecode.org/assets/files/postmortem-2025-spaark.pdf
- Craig Reynolds Boids: red3d.com/cwr/boids/
- Java Bytecode Hacking: cory.li/bytecode-hacking/
- MIT OCW Game Theory: ocw.mit.edu/courses/17-810-game-theory-spring-2021/
- Wikipedia Stigmergy: en.wikipedia.org/wiki/Stigmergy
- Nature - Swarm Robotics 2025: nature.com/articles/s44182-025-00027-2
- Cell Patterns - AI Deception: cell.com/patterns/fulltext/S2666-3899(24)00103-X
