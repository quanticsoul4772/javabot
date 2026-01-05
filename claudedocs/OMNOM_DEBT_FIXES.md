# omnom Technical Debt Fix Plan

**Date**: 2026-01-04
**Current Status**: 83 rounds, Week 1 foundation complete
**Target**: 166 rounds (double baseline), then 400 rounds (double SPAARK ~200)

---

## P0: Critical Fixes (Before Continuing)

### 1. Fix RobotPlayer Sensing Performance

**Problem**: G.init() + POI.update() called every unit every turn
- 5 soldiers × (G.init sensing + POI update) = expensive
- Redundant sensing (same data sensed 5 times per turn)

**Impact**: Bytecode waste, slower execution

**Fix**:
```java
// RobotPlayer.java
public static RobotInfo[] allAllies;
public static RobotInfo[] allEnemies;

// At start of each round (before any unit runs):
if (lastRound != rc.getRoundNum()) {
    allAllies = rc.senseNearbyRobots(-1, team);
    allEnemies = rc.senseNearbyRobots(-1, opponent);
    lastRound = rc.getRoundNum();
}

// In G.init(): Use cached values instead of sensing
allies = RobotPlayer.allAllies;
enemies = RobotPlayer.allEnemies;

// POI.update(): Only call once per round, not per unit
```

**Time**: 1 hour
**Test**: Check bytecode before/after, verify same behavior

---

### 2. Fix POI Not Tracking Own Towers

**Problem**: Soldier retreat calls POI.findNearestAllyTower() but POI only tracks towers we've SEEN
- Our own towers never sensed (we're inside them)
- findNearestAllyTower() returns null
- Retreat fails

**Impact**: Units can't retreat, die at paint=0

**Fix**:
```java
// Tower.java init (one-time)
POI.updateTower(G.me, G.team, G.type);  // Register self

// Or in RobotPlayer for towers:
if (rc.getType().isTowerType()) {
    POI.updateTower(rc.getLocation(), G.team, rc.getType());
}
```

**Time**: 30 minutes
**Test**: Soldier retreat should find tower and move toward it

---

### 3. Add Spawn Throttling to Tower

**Problem**: Spawns every turn if affordable
- Will deplete all chips quickly
- No reserve for upgrades or emergencies

**Impact**: Can't upgrade towers, economic instability

**Fix**:
```java
// Tower.java
private static int lastSpawnRound = -1;

public static void trySpawn() {
    // SPAARK spawn conditions
    boolean shouldSpawn = G.round < 10  // Always spawn early
        || G.rc.getNumberTowers() >= 25  // Maxed towers
        || (G.rc.getMoney() - 150 >= 900  // Have 900 buffer
            && (G.round < 100 || (lastSpawnRound + 1 < G.round)));

    if (!shouldSpawn) return;

    // Try to spawn...
}
```

**Time**: 30 minutes
**Test**: Check chip balance stays healthy, still spawns regularly

---

**Total P0 Time**: 2 hours
**Expected Impact**: +10-20 rounds (better survival, stable economy)

---

## P1: High Priority (Week 2)

### 4. Add Bug2 Pathfinding

**Problem**: directionTo() gets stuck on obstacles
- Units can't navigate around walls
- Gets blocked, wastes turns

**Impact**: Poor positioning, inefficient movement

**Fix**: Implement Bug2 from bot_spec.md Part 14.2
```java
// Nav.java - new file
private static MapLocation bugTarget;
private static boolean bugTracing;
// ... full Bug2 state machine
```

**Time**: 4 hours (implementation + testing)
**Test**: Units should reach targets without getting stuck

---

### 5. Add Mode System to Soldier

**Problem**: No modes, just linear logic
- Can't switch between explore/attack/retreat/build properly
- All behavior in one method

**Impact**: Inflexible strategy, can't match SPAARK patterns

**Fix**: Implement 5-mode system (EXPLORE, ATTACK, RETREAT, BUILD_TOWER, ATTACK_TOWER)
```java
// Soldier.java
private enum Mode { EXPLORE, ATTACK, RETREAT, BUILD_TOWER, ATTACK_TOWER }
private static Mode mode = Mode.EXPLORE;

public static void run() {
    // ... visibility ...

    updateMode();  // Check mode transitions

    switch (mode) {
        case EXPLORE: explore(); break;
        case ATTACK: attack(); break;
        case RETREAT: retreat(); break;
        // ...
    }
}
```

**Time**: 6 hours (5 mode implementations)
**Test**: Mode distribution should match SPAARK (~65% EXPLORE, etc.)

---

### 6. Fix Tower Spawn Conditions

**Problem**: Always spawns soldiers, no variety
- No debt-based selection
- Spawns even when broke

**Impact**: Suboptimal unit composition, resource waste

**Fix**: Implement SPAARK debt system from bot_spec.md Part 7.5
```java
// Tower.java
private static double soldierDebt = 0;
private static double splasherDebt = 0;
private static double mopperDebt = 0;

// Calculate weights, select highest debt unit
```

**Time**: 3 hours
**Test**: Should spawn variety of units (soldiers, moppers, splashers)

---

**Total P1 Time**: 13 hours
**Expected Impact**: +30-50 rounds (better movement, proper modes, balanced army)

---

## Medium Debt Fixes (As Needed)

### G.java Enhancements

**Missing allyRobotsString:**
- **Why**: O(1) position checking vs O(n) iteration
- **When**: Week 3 (micro optimization)
- **Time**: 1 hour

**Missing range20X/Y:**
- **Why**: Needed for SRP checking
- **When**: Week 4 (SRP implementation)
- **Time**: 30 min (copy from SPAARK)

**Missing paintPerChips():**
- **Why**: Needed for micro scoring
- **When**: Week 2 (micro implementation)
- **Time**: 15 min

**Dynamic grid size:**
- **Why**: Support different map sizes
- **When**: Week 5 (map adaptation)
- **Time**: 30 min

**Total**: 2-3 hours as needed

---

### POI.java Enhancements

**Type checking in findNearestAllyPaintTower:**
```java
// Check tower type ordinals (3-5 = paint towers)
if (type >= 3 && type <= 5) { ... }
```
- **When**: Week 3 (retreat optimization)
- **Time**: 30 min

**Symmetry detection:**
- **When**: Week 4 (advanced SPAARK features)
- **Time**: 3 hours

**Explored bitfield:**
- **When**: Week 4 (exploration optimization)
- **Time**: 2 hours

**Ruin tracking:**
- **When**: Week 3 (tower building)
- **Time**: 1 hour

**Total**: 6-7 hours as needed

---

### Tower.java Enhancements

**12 spawn locations (not 8):**
```java
// Add 2-away cardinal directions
locs[8] = me.add(NORTH).add(NORTH);
locs[9] = me.add(EAST).add(EAST);
locs[10] = me.add(SOUTH).add(SOUTH);
locs[11] = me.add(WEST).add(WEST);
```
- **When**: Week 3 (spawn optimization)
- **Time**: 30 min

**Attack logic:**
- **When**: Week 3 (tower defense)
- **Time**: 2 hours

**Income calculation fix:**
```java
// Track per-interval properly
private static int lastChips = 0;
int income = (G.chips - lastChips) / 10;
lastChips = G.chips;
```
- **When**: Week 2 (already partially done)
- **Time**: 15 min

**Total**: 2-3 hours

---

## Implementation Plan

### Immediate (Today - 2 hours)

**Session 1: P0 Fixes**
1. Fix RobotPlayer sensing (1 hour)
2. Fix POI own tower tracking (30 min)
3. Add Tower spawn throttling (30 min)

**Validation**: Run match, should reach ~100-120 rounds

---

### Week 2 (13 hours)

**Session 2: Bug2 Pathfinding** (4 hours)
- Implement Nav.java with Bug2
- Test: units navigate around obstacles

**Session 3: Mode System** (6 hours)
- Implement 5-mode soldier
- Test: mode distribution matches SPAARK

**Session 4: Spawn System** (3 hours)
- Implement debt-based spawning
- Test: varied unit composition

**Validation**: Should reach ~150-200 rounds

---

### Week 3-4: Feature Completion

**From spec priority list:**
- SRP building (4 hours)
- Tower building mode (4 hours)
- Micro scoring (4 hours)
- POI enhancements (6 hours)
- G.java utilities (2-3 hours)

**Validation**: Should reach 250-300 rounds

---

### Week 5-7: Advanced Features + Optimization

**Research innovations:**
- Boids (4 hours)
- Controlled Chaos (1 hour)
- Phase-weighted spawning (2 hours)

**SPAARK advanced:**
- Messaging (4 hours)
- Symmetry (3 hours)
- Self-destruct (2 hours)

**Optimization:**
- Bytecode profiling (3 hours)
- Parameter tuning (4 hours)

**Validation**: Target >400 rounds

---

## Autonomous Iteration Strategy

**After each fix:**
1. Run match: `./test/scripts/generate_report.sh omnom SPAARK`
2. Analyze logs (STATE, ECONOMY, SPAWN)
3. Identify next limiting factor
4. Implement fix
5. Validate improvement
6. Repeat

**Like spaark3**: 20+ iterations to go from 146 → 196 rounds

**For omnom**: Start at 83 rounds, iterate to 166 (double), then 400 (beats SPAARK)

---

## Next Steps

**Ready to start P0 fixes** (2 hours):
1. RobotPlayer sensing optimization
2. POI own tower tracking
3. Tower spawn throttling

Then validate with match → analyze → identify next fix → repeat.

**Estimated trajectory**:
- Current: 83 rounds
- After P0: ~100-120 rounds
- After P1: ~150-200 rounds
- After full features: ~300-400 rounds

Continue with P0 fixes?
