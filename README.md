# MIT Battlecode 2025 Bot

A competitive bot for MIT Battlecode 2025. The objective is to paint 70% or more of the map before the opponent.

## Quick Start

```bash
# Build the bot
./gradlew build

# Run a match
./gradlew run -PteamA=mybot -PteamB=examplefuncsplayer -Pmaps=DefaultSmall

# Create submission zip
./gradlew zipForSubmit
```

On Windows, set JAVA_HOME to Java 21 if not already configured:
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot"
./gradlew build
```

## Architecture

The bot uses a priority chain pattern where each unit evaluates conditions in order, executing the first matching priority and returning early. This makes behavior predictable and easy to tune.

### Source Files

```
src/mybot/
├── RobotPlayer.java   # Entry point, dispatches by unit type
├── Soldier.java       # Territory expansion and tower construction
├── Splasher.java      # Area paint attacks, enemy territory contestation
├── Mopper.java        # Enemy paint removal, mop swing AOE
├── Tower.java         # Unit spawning, threat detection, coordination
├── Navigation.java    # Bug2 pathfinding with paint awareness
├── Comms.java         # 32-bit message encoding for coordination
├── Scoring.java       # Centralized decision weights
├── Utils.java         # Shared helpers and enemy detection
└── Metrics.java       # Optional performance tracking
```

### Unit Roles

| Unit | Paint Capability | Primary Role |
|------|------------------|--------------|
| Soldier | Empty tiles only | Expand territory, build towers, attack enemies |
| Splasher | Enemy and empty tiles | Contest enemy territory, area denial |
| Mopper | Remove enemy paint | Support splashers, mop swing AOE damage |

Splashers are the only unit that can paint over enemy-controlled tiles. This makes them essential for contested maps.

## Bot Improvements

The bot incorporates several improvements over the baseline scaffold.

### Priority Chain Pattern

Each unit evaluates priorities in fixed order:
- P0: Survival (retreat when health is critical)
- P0.5: Emergency response (paint tower critical alerts)
- P1-P2: High-value actions (resupply, critical alerts, tower defense)
- P3-P7: Standard behaviors (combat, building, exploration)
- P8: Default fallback (random exploration)

First matching condition wins, with early return. This prevents lower priorities from interfering with urgent actions.

### Spawn Composition by Game Phase

Tower spawn ratios adapt to game phase:

| Phase | Rounds | Soldiers | Moppers | Splashers |
|-------|--------|----------|---------|-----------|
| Early | 0-300 | 90% | 10% | 0% |
| Mid | 300-600 | 40% | 20% | 40% |
| Late | 600+ | 30% | 20% | 50% |

Rationale: Early game needs fast expansion. Mid game requires territory contestation. Late game benefits from splasher-heavy composition for final pushes.

### Adaptive Enemy Response

The tower detects enemy composition and adjusts spawning:

- **Rush detection**: If 2+ enemy soldiers appear before round 150, spawn 90% soldiers for 100 rounds
- **Enemy splasher counter**: If 2+ enemy splashers detected, spawn 70% soldiers and 30% moppers to hunt them
- **Panic mode**: If a paint tower is under attack, spawn only soldiers until threat clears

### Emergency Response System

Paint towers are critical infrastructure. Losing all paint towers means no paint regeneration, leading to defeat.

The bot implements a health-based early warning system:

1. Paint towers broadcast `PAINT_TOWER_CRITICAL` when health drops below 100
2. All units receive this message and respond with highest priority (P0.5)
3. Units converge on the tower location and engage enemies

This triggers before the tower dies, giving defenders time to respond.

### Inter-Unit Coordination

Units communicate using 32-bit encoded messages:

```
[4 bits: type][6 bits: x][6 bits: y][16 bits: payload]
```

Message types include:
- `ENEMY_SPOTTED`: Alert allies to enemy presence
- `RUSH_ALERT`: Trigger defensive response to early rush
- `PAINT_TOWER_DANGER`: Paint tower under attack
- `PAINT_TOWER_CRITICAL`: Paint tower health critical
- `TOWER_BUILDING`: Soldier constructing tower, needs splasher support
- `SPLASHER_THREATENED`: Splasher retreating, moppers intercept
- `PHASE_DEFEND`: Global defensive mode
- `PHASE_ALL_OUT_ATTACK`: Late game aggression

Towers can send 20 messages per turn; units can send 1 message per turn.

### Mopper-Splasher Coordination

Splashers are high-value targets because they're the only unit that can contest enemy territory. When a splasher retreats due to low health:

1. The splasher broadcasts `SPLASHER_THREATENED` to nearby moppers
2. Moppers within range (8 tiles) respond with P1.5 priority
3. Moppers intercept enemies threatening the splasher
4. Mop swing provides AOE damage to protect the retreating splasher

### Phase Coordination

Towers broadcast global phase messages every 50 rounds:

- **Defend phase**: Triggered by panic mode or rush detection. Units adjust thresholds to retreat earlier and prioritize tower defense.
- **Attack phase**: Triggered after round 1500. Units become more aggressive, tolerating lower health and paint before retreating.

Soldiers adjust their survival and resupply thresholds based on active phase.

### Navigation

The bot uses Bug2 pathfinding with these enhancements:

- **Paint awareness**: Prefers ally-painted tiles (+10 score) over enemy paint (-5 score)
- **C-shape handling**: Switches between left-hand and right-hand wall-following after 15 turns stuck
- **Distance weighting**: Strongly prefers moves that reduce distance to target

### Scoring System

All decision weights are centralized in `Scoring.java` for easy tuning:

- Tile scoring: Ally paint (+10), enemy paint (-5), neutral (0)
- Threat weights: Enemy soldier (-15), enemy splasher (-12), enemy mopper (-8), enemy tower (-20)
- Splash scoring: Enemy tile (+2), neutral (+1), ally tile (-3)
- Thresholds: Good tile (5+), bad tile (-10), splash worth (3+), high-value splash (5+)

### Metrics Collection

Optional metrics tracking for performance analysis. Enable with `Metrics.ENABLED = true`.

Tracks:
- Priority usage per unit type
- FSM state time distribution
- Combat statistics (attacks, splashes, mop swings)
- Resource efficiency (tiles painted, towers built)

Units self-report every 500 rounds. View during match:
```bash
./gradlew run -PteamA=mybot -PteamB=mybot -Pmaps=DefaultSmall 2>&1 | grep -E "SOLDIER|SPLASHER|MOPPER|TOWER"
```

## Project Structure

```
javabot/
├── src/
│   ├── mybot/              # Main bot implementation
│   └── examplefuncsplayer/ # Reference implementation
├── test/                   # JUnit tests
├── client/                 # Battlecode client executable
├── matches/                # Match replay files
├── maps/                   # Custom maps
├── claudedocs/             # Strategy notes and research
├── gradle.properties       # Default match configuration
└── CLAUDE.md               # Development guidance
```

## Configuration

Edit `gradle.properties` to change default matchups:
```properties
teamA=mybot
teamB=examplefuncsplayer
maps=DefaultSmall
```

## Useful Commands

```bash
./gradlew build          # Compile
./gradlew run            # Run with gradle.properties settings
./gradlew test           # Run JUnit tests
./gradlew listPlayers    # Show available bot packages
./gradlew listMaps       # Show available maps
./gradlew zipForSubmit   # Create submission zip
./gradlew update         # Update to latest Battlecode version
```

## Viewing Matches

Run the client after building: `client/Battlecode Client.exe`

Match replays are saved to the `matches/` directory.

## Game Constraints

- Bytecode limits: 15,000 per robot per turn, 20,000 per tower per turn
- Win condition: Paint 70%+ of the map, or have more painted tiles at round 2000
- Units take damage on enemy paint, heal on ally paint
- Paint towers provide paint regeneration; losing all paint towers is critical

## License

MIT License. See LICENSE file for details.
