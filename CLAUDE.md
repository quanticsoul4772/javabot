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
├── Soldier.java  ← Combat unit: attacks, builds towers, defends
├── Mopper.java   ← Utility: cleans enemy paint
├── Splasher.java ← AOE attacker: area paint damage
├── Tower.java    ← Stationary: spawns units, attacks enemies
├── Navigation.java ← Bug2 pathfinding with paint-aware scoring
├── Utils.java    ← Shared helpers: RNG, directions, tower tracking
└── Comms.java    ← 32-bit message encoding for robot communication
```

### Key Constraints
- **Bytecode limits**: 15,000/robot, 20,000/tower per turn
- **Paint Towers are critical**: Losing all Paint Towers = no paint = death spiral
- Units take damage on enemy paint, heal on ally paint

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
