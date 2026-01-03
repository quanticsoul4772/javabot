# CLAUDE.md

Development guidance for MIT Battlecode 2025 bots.

## Build Commands

```bash
./gradlew build                    # Compile all players
./gradlew run                      # Run match with gradle.properties settings
./gradlew run -PteamA=spaark2 -PteamB=SPAARK -Pmaps=DefaultSmall
./gradlew test                     # Run JUnit tests
./gradlew listPlayers              # Show available bot packages
./gradlew listMaps                 # Show available maps
./gradlew zipForSubmit             # Create submission.zip for tournament
./gradlew update                   # Update to latest Battlecode version
```

**Windows Note**: Requires JAVA_HOME set to Java 21.
- JAVA_HOME: `C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot`

## Game Overview

Win condition: paint 70%+ of the map, or have most tiles painted at round 2000.

### Unit Types
| Unit | Paint Ability | Role |
|------|---------------|------|
| Soldier | Empty tiles only | Expand territory, build towers, attack |
| Splasher | Enemy + empty tiles | Contest enemy territory (critical!) |
| Mopper | Remove enemy paint | Support, mop swing AOE |

### Key Constraints
- **Bytecode limits**: 15,000/robot, 20,000/tower per turn
- **Paint Towers are critical**: Losing all = no paint = death spiral
- Units take damage on enemy paint, heal on ally paint

## Bot Packages

Each bot is a separate package in `src/`. See each bot's README for details.

| Package | Description |
|---------|-------------|
| `spaark2` | SPAARK-inspired with POI, Micro, debt-based spawning |
| `mybot` | Legacy bot with priority chain pattern |

## Match Configuration

Edit `gradle.properties`:
```properties
teamA=spaark2
teamB=SPAARK
maps=DefaultSmall
```

## Viewing Matches

Run client: `client/Battlecode Client.exe`
Replays saved to `matches/`.

## Common Patterns

### Bytecode Optimization
```java
// Backward loops save ~2 bytecode per iteration
for (int i = array.length; --i >= 0;) { }

// Early exit when match found
if (condition) break;

// Throttled operations (every N rounds)
if (round - lastRound < 5) return;
```

### Navigation
Most bots use Bug2 pathfinding with obstacle tracing.

### Retreat Logic
Units typically retreat when paint is low AND money is low.
