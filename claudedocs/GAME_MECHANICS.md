# Battlecode Game Mechanics Reference

Based on Battlecode 2025 (Chromatic Conflict). 2026 mechanics TBD when released.

## Win Condition

**Primary:** First team to paint 70% of the map wins.

## Core Mechanics

### Paint System
- Robots have two bars: **Paint** and **HP**
- Actions consume paint
- Standing on enemy/neutral paint drains paint
- Being too close to allies drains paint
- Running out of paint → rapid HP loss → death at 0 HP

### Resources
- **Paint:** Used for actions, attacks, movement effects
- **Money:** Used for building units and towers

## Unit Types

### Robots (Mobile Units)

| Unit | Role | Key Ability |
|------|------|-------------|
| SOLDIER | Basic combat | Standard attacks |
| SPLASHER | Area damage | AOE paint attacks |
| MOPPER | Utility | Mop swing, paint cleanup |

### Towers (Stationary)

Three types, each with 3 upgrade levels:

| Tower Type | Purpose | Levels |
|------------|---------|--------|
| Paint Tower | Generate paint | L1, L2, L3 |
| Money Tower | Generate money | L1, L2, L3 |
| Defense Tower | Combat defense | L1, L2, L3 |

Towers are built on **ruins** using 5x5 patterns.

## Unit Attributes

Each unit has:
- `health` - HP pool
- `paintCost` / `moneyCost` - Creation cost
- `attackCost` - Paint cost to attack
- `attackStrength` / `aoeAttackStrength` - Damage values
- `paintCapacity` - Max paint storage
- `paintPerTurn` / `moneyPerTurn` - Generation rates
- `actionCooldown` - Turns between actions
- `actionRadiusSquared` - Attack/action range

## RobotController Actions

### Movement
```java
rc.move(Direction dir)           // Move one step
rc.canMove(Direction dir)        // Check if possible
rc.isMovementReady()             // Check cooldown
```

### Combat
```java
rc.attack(MapLocation loc)              // Attack location
rc.attack(MapLocation loc, boolean)     // Attack with color choice
rc.mopSwing(Direction dir)              // Mopper special attack
```

### Painting & Marking
```java
rc.mark(MapLocation loc, boolean)       // Mark tile
rc.removeMark(MapLocation loc)          // Remove mark
```

### Building
```java
rc.buildRobot(UnitType type, MapLocation loc)    // Spawn unit
rc.markTowerPattern(UnitType, MapLocation)       // Start tower
rc.completeTowerPattern(UnitType, MapLocation)   // Finish tower
rc.upgradeTower(MapLocation loc)                 // Upgrade tower
```

### Sensing
```java
rc.senseNearbyRobots()                           // All visible robots
rc.senseNearbyRobots(int radius, Team team)      // Filtered
rc.senseMapInfo(MapLocation loc)                 // Tile info
rc.senseNearbyRuins(int radius)                  // Find ruins
rc.sensePassability(MapLocation loc)             // Check walkable
```

### Communication
```java
rc.sendMessage(MapLocation loc, int msg)         // Send 4-byte message
rc.readMessages(int round)                       // Read messages
```

### Resource Transfer
```java
rc.transferPaint(MapLocation loc, int amount)    // Give paint to ally
```

### Status
```java
rc.getLocation()          // Current position
rc.getHealth()            // Current HP
rc.getPaint()             // Current paint
rc.getMoney()             // Team funds
rc.getRoundNum()          // Current round
rc.getTeam()              // Team affiliation
rc.getType()              // Unit type
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `RobotController` | Main interface for robot actions |
| `MapLocation` | Immutable 2D coordinates |
| `RobotInfo` | Sensed robot data |
| `MapInfo` | Tile information |
| `Direction` | 8 directions + CENTER |
| `Team` | Team affiliation (A, B, NEUTRAL) |
| `UnitType` | Robot/tower types |
| `PaintType` | Paint color types |
| `GameConstants` | Game balance values |
| `Clock` | Bytecode introspection |

## Strategic Considerations

### Traditional AI Focus
- **Pathfinding:** Navigate efficiently
- **Resource management:** Paint and money optimization
- **Communication:** Coordinate distributed robots
- **Tactics:** Local combat decisions
- **Strategy:** Global map control

### Computation Limits
- Each robot has limited bytecodes per turn
- Java allows precise tracking
- Optimize hot paths
- Use `Clock` to monitor usage
