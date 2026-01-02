# Battlecode Strategy Notes

Compiled insights from past competitions and winning teams.

## General Principles

### What Makes Battlecode Different
- **Distributed AI:** No central controller, each robot decides independently
- **Limited Computation:** Bytecode limits force efficient algorithms
- **Imperfect Information:** Robots can only sense within range
- **Communication Constraints:** Limited message passing between units

### Key Success Factors
1. **Robust basics** - Reliable pathfinding, resource management
2. **Effective communication** - Coordinate without central control
3. **Adaptive strategy** - Respond to opponent behavior
4. **Computational efficiency** - Stay under bytecode limits

## 2025 Specific Insights (Chromatic Conflict)

### Paint Mechanics
- **Paint is survival:** Run out → rapid death
- **Territory control:** Painting tiles claims map percentage
- **Ally proximity penalty:** Spread units to avoid paint drain
- **Tower placement:** Ruins are key strategic points

### Tower Strategy
- **Paint towers:** Sustain army operations
- **Money towers:** Enable unit production
- **Defense towers:** Protect critical areas
- **Upgrade timing:** Balance expansion vs strengthening

### Unit Composition
- **Soldiers:** Bread and butter, reliable
- **Splashers:** Area control, efficient painting
- **Moppers:** Counter enemy paint, utility

## Common Patterns from Top Teams

### Early Game
1. Scout map layout
2. Identify ruin locations
3. Establish resource generation
4. Expand territory methodically

### Mid Game
1. Contest key map areas
2. Upgrade critical towers
3. Respond to enemy positioning
4. Balance offense/defense

### Late Game
1. Push for 70% coverage
2. Deny enemy territory
3. Protect winning position
4. All-out offense if behind

## Technical Approaches

### Pathfinding
- **BFS/A*** for optimal paths
- **Bug algorithms** for bytecode efficiency
- **Caching** frequently used paths
- **Dynamic updates** for changing map

### Communication
- **Shared array** for team-wide data (if available)
- **Message passing** for point-to-point
- **Implicit signals** through actions
- **Role assignment** to avoid duplication

### State Machines
- Define clear robot roles
- Transition based on conditions
- Avoid complex nested logic
- Test state transitions thoroughly

## Development Process

### Iteration Strategy
1. **Week 1:** Get basics working, understand game
2. **Week 2:** Implement core strategies
3. **Week 3:** Tune and optimize
4. **Week 4:** Polish and adapt

### Testing Approach
- Run matches against `examplefuncsplayer`
- Test on multiple map types
- Simulate edge cases
- Scrimmage early and often

### Code Organization
```
src/mybot/
├── RobotPlayer.java    # Entry point, dispatch by type
├── Soldier.java        # Soldier behavior
├── Splasher.java       # Splasher behavior
├── Mopper.java         # Mopper behavior
├── Tower.java          # Tower behavior
├── Pathfinding.java    # Navigation utilities
├── Communication.java  # Message handling
└── Utils.java          # Shared helpers
```

## Debugging Tips

### Client Indicators
```java
rc.setIndicatorString("Debug message");  // Text display
rc.setIndicatorDot(loc, r, g, b);        // Visual marker
rc.setIndicatorLine(loc1, loc2, r, g, b); // Line drawing
```

### Common Issues
- **Bytecode exceeded:** Optimize loops, cache results
- **Null pointer:** Check sensor results before use
- **Wrong direction:** Validate movement logic
- **Message corruption:** Verify encoding/decoding

## Resources for Learning

### Recommended Reading Order
1. Official game specifications
2. Javadoc for RobotController
3. Example player code
4. Past team postmortems
5. Lecture recordings (if available)

### Past Postmortems to Study
- SPAARK (2025 HS Champion): https://github.com/erikji/battlecode25
- Check battlecode.org/past.html for more
