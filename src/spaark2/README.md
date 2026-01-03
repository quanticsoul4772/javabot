# spaark2 Bot Documentation

A SPAARK-inspired Battlecode 2025 bot with advanced coordination systems.

## Architecture Overview

```
RobotPlayer.java          Entry point - dispatches by unit type
├── Soldier.java          Combat unit with mode-based state machine
├── Splasher.java         Territory control - paints over enemy paint
├── Mopper.java           Support unit - cleans paint, transfers resources
├── Tower.java            Spawns units with debt-based coordination
├── Nav.java              Bug2 pathfinding with micro integration
├── Micro.java            Combat movement scoring system
├── POI.java              Global tower tracking (Points of Interest)
└── Globals.java          Shared constants and spawn tracking
```

## Core Systems

### 1. POI (Points of Interest) System
Global tower tracking enabling units to find towers beyond sensor range.

```java
// Usage in any unit
POI.init(rc);
POI.scanNearby(rc);  // Throttled to every 5 rounds

// Find nearest paint tower for retreat
MapLocation tower = POI.findNearestAllyPaintTower(myLoc, myTeam);
```

**Capabilities:**
- Tracks up to 144 towers (max ruins on 60x60 map)
- Maintains tower type counters for spawn decisions
- Automatic tower discovery via `scanNearby()`

### 2. Micro System
Paint-aware and threat-aware movement scoring for combat situations.

```java
// Score all 9 directions (8 + CENTER)
Micro.score(rc, targetDirection);
Direction best = Micro.getBestDirection();

// Check if unit should engage in combat
if (Micro.shouldEngage(rc)) {
    Nav.moveToWithMicro(rc, target);
}
```

**Scoring Factors:**
| Factor | Score Impact |
|--------|-------------|
| Target direction | +20 |
| Ally paint tile | +10 |
| Enemy paint tile | -25 |
| Enemy threat range | -50 |
| Impassable | MIN_VALUE |

### 3. Spawn Coordination
Debt-based spawning with dynamic weight adjustment.

```java
// Weights adjust based on game state
soldierWeight = max(0.3, 1.5 - (numTowers * 0.05))
splasherWeight = 0.2 + (paintTowers * 0.15)
mopperWeight = 1.2

// Spawn highest-debt unit type
debt = fractionalAccumulator + weight - spawnedCount
```

### 4. Navigation (Bug2)
Pathfinding with obstacle tracing and direction switching.

```java
Nav.moveTo(rc, target);           // Standard Bug2
Nav.moveToWithMicro(rc, target);  // Combat-aware movement
Nav.retreatFrom(rc, threat);      // Retreat with micro scoring
Nav.moveAway(rc, threat);         // Simple flee
Nav.moveRandom(rc);               // Random exploration
```

## Unit Behaviors

### Soldier
**Modes:** EXPLORE, BUILD_TOWER, RETREAT

| Priority | Behavior |
|----------|----------|
| P0 | Attack enemies in range (towers first) |
| P1 | Chase enemy towers |
| P2 | Engage enemy units with micro |
| P3 | Rush center (early game) |
| P4 | Build towers at ruins |
| P5 | Explore unpainted areas |

**Mode Transitions:**
- RETREAT: paint < 50 AND chips < 6000
- EXIT RETREAT: paint >= 100 OR chips >= 6000

### Splasher
**Role:** Contest enemy territory (only unit that can paint over enemy paint)

| Priority | Behavior |
|----------|----------|
| P0 | Retreat if resources low |
| P1 | Attack best splash target |
| P2 | Move toward enemy paint |

**Splash Scoring:**
- Enemy paint tile: +3 points
- Neutral tile: +1 point
- Enemy unit in splash: +2 points
- Already ally paint: 0 points

### Mopper
**Role:** Support - clean enemy paint, transfer resources

| Priority | Behavior |
|----------|----------|
| P0 | Retreat if resources low |
| P1 | Swing attack (if 2+ enemies in arc) |
| P2 | Mop enemy paint |
| P3 | Transfer paint to low-paint allies |
| P4 | Move toward work areas |

### Tower
**Role:** Spawn units, attack enemies

**Spawn Strategy:**
- Early game (< round 50): Soldiers only
- After 3 units: Debt-based spawning
- AOE attack prioritized when 2+ enemies

## Constants Reference

### Spawn Weights (Globals.java)
```java
SOLDIER_WEIGHT = 1.5
SPLASHER_WEIGHT = 0.2
MOPPER_WEIGHT = 1.2
```

### Retreat Thresholds
```java
RETREAT_PAINT = 150    // Must be below all three
RETREAT_CHIPS = 6000   // to trigger retreat
RETREAT_ALLIES = 9
```

### Paint Costs
```java
SOLDIER_PAINT_COST = 200
SPLASHER_PAINT_COST = 200
MOPPER_PAINT_COST = 100
```

## Bytecode Optimization Patterns

### Backward Loop Pattern
```java
// Saves ~2 bytecode per iteration
for (int i = array.length; --i >= 0;) {
    // process array[i]
}
```

### Early Exit Pattern
```java
// Break when first match found
for (int i = enemies.length; --i >= 0;) {
    if (enemy.getType().isTowerType()) {
        target = enemy;
        break;  // Don't continue searching
    }
}
```

### Throttled Scanning
```java
// POI scans only every 5 rounds
if (round - lastScanRound < 5) return;
lastScanRound = round;
```

## File Details

| File | Lines | Purpose |
|------|-------|---------|
| RobotPlayer.java | 27 | Entry point, type dispatch |
| Soldier.java | 355 | Combat unit with state machine |
| Splasher.java | 206 | Territory control |
| Mopper.java | 234 | Support unit |
| Tower.java | 244 | Unit spawning, defense |
| Nav.java | 222 | Bug2 pathfinding |
| Micro.java | 107 | Combat movement scoring |
| POI.java | 188 | Global tower tracking |
| Globals.java | 80 | Constants and state |

**Total:** ~1,663 lines

## Testing

```bash
# Test against SPAARK
./gradlew run -PteamA=spaark2 -PteamB=SPAARK -Pmaps=DefaultSmall

# Build only
./gradlew build

# Check for bytecode issues (errors show [A:] or [B:] prefix)
./gradlew run ... 2>&1 | grep "Bytecode overflow"
```

## Known Limitations

1. **Static Variables**: All units share static state (by design in Battlecode)
2. **POI Accuracy**: Tower tracking relies on units discovering towers
3. **No Communication**: Uses shared static state instead of message passing
4. **Bytecode Budget**: 15,000/unit, 20,000/tower per turn
