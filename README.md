# MIT Battlecode 2025

Competitive bots for MIT Battlecode 2025. Win condition: paint 70%+ of the map.

## Quick Start

```bash
# Build all bots
./gradlew build

# Run a match
./gradlew run -PteamA=spaark2 -PteamB=SPAARK -Pmaps=DefaultSmall

# List available bots
./gradlew listPlayers

# Create submission zip
./gradlew zipForSubmit
```

Windows requires JAVA_HOME set to Java 21:
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot"
```

## Available Bots

| Bot | Description |
|-----|-------------|
| `spaark2` | SPAARK-inspired with POI, Micro, debt-based spawning |
| `mybot` | Legacy bot with priority chain pattern |
| `examplefuncsplayer` | Reference implementation |

Each bot has its own README in `src/<botname>/README.md`.

## Project Structure

```
javabot/
├── src/
│   ├── spaark2/            # SPAARK-inspired bot
│   ├── mybot/              # Legacy bot
│   └── examplefuncsplayer/ # Reference
├── client/                 # Battlecode client
├── matches/                # Replay files
├── maps/                   # Custom maps
└── gradle.properties       # Default match config
```

## Commands

```bash
./gradlew build          # Compile all bots
./gradlew run            # Run with gradle.properties settings
./gradlew test           # Run tests
./gradlew listPlayers    # Show available bots
./gradlew listMaps       # Show available maps
./gradlew zipForSubmit   # Create submission zip
./gradlew update         # Update Battlecode version
```

## Configuration

Edit `gradle.properties`:
```properties
teamA=spaark2
teamB=SPAARK
maps=DefaultSmall
```

## Viewing Matches

Run client: `client/Battlecode Client.exe`

Replays saved to `matches/`.

## Game Constraints

- Bytecode: 15,000/robot, 20,000/tower per turn
- Win: 70%+ painted, or most tiles at round 2000
- Damage on enemy paint, heal on ally paint
- Losing all paint towers = no paint regeneration

## Unit Types

| Unit | Paint Ability | Role |
|------|---------------|------|
| Soldier | Empty tiles | Expand, build towers, attack |
| Splasher | Enemy + empty | Contest territory (critical) |
| Mopper | Remove enemy | Support, AOE swing |

## License

MIT License
