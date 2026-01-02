# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew build                    # Compile all players
./gradlew run                      # Run match with gradle.properties settings
./gradlew run -PteamA=mybot -PteamB=examplefuncsplayer -Pmaps=DefaultSmall
./gradlew test                     # Run JUnit tests
./gradlew listPlayers              # Show available bot packages
./gradlew listMaps                 # Show available maps
./gradlew zipForSubmit             # Create submission.zip for tournament
./gradlew update                   # Update to latest Battlecode version
```

**Windows Note**: Requires JAVA_HOME set to Java 21. If not set in environment:
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot"
./gradlew build
```

## Architecture

This is a **MIT Battlecode 2025** bot. Win condition: paint 70%+ of the map.

### Bot Package: `mybot`

```
RobotPlayer.java  ← Entry point, dispatches to unit handlers by type
├── Soldier.java  ← Combat unit: attacks, builds towers, paints empty tiles
├── Mopper.java   ← Utility: cleans enemy paint, mop swing AOE
├── Splasher.java ← Territory control: ONLY unit that can paint over enemy paint
├── Tower.java    ← Stationary: spawns units, attacks enemies
├── Navigation.java ← Bug2 pathfinding with paint-aware scoring
├── Utils.java    ← Shared helpers: RNG, directions, tower tracking
├── Comms.java    ← 32-bit message encoding for robot communication
├── Scoring.java  ← Centralized scoring weights for splash targets
└── Metrics.java  ← Lightweight metrics collection (toggle with ENABLED flag)
```

### Key Constraints
- **Bytecode limits**: 15,000/robot, 20,000/tower per turn
- **Paint Towers are critical**: Losing all Paint Towers = no paint = death spiral
- Units take damage on enemy paint, heal on ally paint
- **Splashers are essential**: Only splashers can paint over enemy territory

### Unit Roles
| Unit | Paint Ability | Primary Role |
|------|---------------|--------------|
| Soldier | Empty tiles only | Expand territory, build towers |
| Splasher | Enemy + empty tiles | Contest enemy territory (critical!) |
| Mopper | Clean enemy paint | Support, mop swing AOE attack |

### Spawn Ratios (Tower.java)
- **Early game** (0-300): 90% soldiers, 10% moppers
- **Mid game** (300-600): 40% soldiers, 20% moppers, 40% splashers
- **Late game** (600+): 30% soldiers, 20% moppers, 50% splashers

### Message Format (Comms.java)
32-bit encoding: `[4 bits: type][6 bits: x][6 bits: y][16 bits: payload]`

### Navigation (Navigation.java)
Bug2 algorithm with:
- Paint-aware tile scoring (+10 ally, -5 enemy)
- C-shape escape: switches left/right hand rule after 15 turns stuck

## Match Configuration

Edit `gradle.properties` to change default matchups:
```properties
teamA=mybot
teamB=examplefuncsplayer
maps=DefaultSmall
```

## Viewing Matches

Run client after building: `client/Battlecode Client.exe`
Match replays saved to `matches/` directory.

## Metrics System

Toggle metrics with `Metrics.ENABLED` in `Metrics.java`. When enabled:
- Towers report spawns every 500 rounds
- Units report priority usage every 500 rounds

View metrics during match:
```bash
./gradlew run -PteamA=mybot -PteamB=mybot -Pmaps=DefaultSmall 2>&1 | grep -E "TOWER|SOLDIER|SPLASHER|MOPPER"
```

## Priority Chain Pattern

All units use a priority chain (if-else with early returns):
- **P0**: Survival (critical health → retreat)
- **P1**: Resupply (low paint → return to tower)
- **P2-P7**: Unit-specific behaviors
- **P8/P5**: Default exploration

Higher priority = checked first. First matching condition wins.
