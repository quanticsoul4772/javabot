# Battlecode 2025 Winning Strategies Research

## Executive Summary

This report synthesizes strategies from top Battlecode 2025 teams including **Just Woke Up** (1st), **Confused** (2nd), **Om Nom** (3rd/US Qualifier Winner), and **SPAARK** (HS Champions). Key success factors: rush defense, paint conservation, tower economics, and bytecode-efficient pathfinding.

---

## 1. Tournament Results & Key Teams

| Place | Team | Notable Achievement |
|-------|------|---------------------|
| 1st | Just Woke Up | Unique defense tower spawning criteria |
| 2nd | Confused (skipiano) | Solo player, topped scrimmage ladder |
| 3rd | Om Nom | US Qualifiers winner, 3rd year team |
| HS 1st | SPAARK | 4 high schoolers, 3rd year competing |

---

## 2. Critical Game Mechanics

### Win Condition
- **Primary:** First team to paint 70% of the map
- **Secondary:** Destroy all enemy units
- **Tiebreaker:** Most painted territory at round 2000

### Resources
- **Paint:** Used for actions, attacks, survival
- **Chips (Money):** Used for spawning units and building towers

### The Paint Tower Problem
> "Losing a Paint Tower at the beginning of the game was devastating, because if you didn't rebuild it, you would never make paint again and all your units would die."
> — Om Nom Postmortem

This made **rush strategies extremely effective** in early tournaments.

---

## 3. Winning Strategies

### 3.1 Rush Strategy (Early Meta)
**Used by:** Confused (dominated Sprint 2)

- Send soldiers directly to enemy base
- Target enemy Paint Towers first
- Prevents enemy from ever recovering

**Counter:** Was eventually nerfed after Sprint 2 balance changes.

### 3.2 Tower Economics
**Key Insight from Om Nom:**

- Teams often lose despite having lots of money
- **Solution:** "Nuke" (sacrifice) towers to trade money for paint
- Monitor Paint Tower count - if it drops to 0, immediately build another

**Income Estimation Formula:**
```
Income = (20 + 3 × #SRPs) × #Money Towers
```
- SRPs increase income by multiples of 3
- Towers increase income by multiples of 20
- Track income changes over 10 turns to estimate enemy economy

### 3.3 Paint Conservation
**Critical Micro Optimization:**

> "Saving even a little paint means you can land a few more attacks, which can easily win the game."

**Implementation:**
1. Soldiers should almost always stay on painted tiles while attacking
2. Add greedy tiebreaker in pathfinding to prefer painted squares
3. Reuse existing paint trails when possible

### 3.4 Aggressive Expansion
**Late-game winning strategy:**

1. Build towers along the way while rushing
2. Paint ALL ruins you see (prevents enemy tower construction)
3. Send splashers toward enemy territory for area denial

---

## 4. Unit Roles & Composition

### Soldiers (Primary Unit)
- **Only unit that can build towers** (paint 5x5 patterns)
- **Only unit that can create SRPs** (economy boost patterns)
- **2.5x DPS of Splashers** - best for combat
- Use for: Tower building, attacking, precise painting

### Splashers (Area Control)
- Area-of-effect painting
- Push enemy territory
- Less efficient for single-target damage

### Moppers (Utility)
- Remove enemy paint
- Defensive/support role

### Towers
| Type | Purpose | Priority |
|------|---------|----------|
| Paint Tower | Generate paint | **CRITICAL** - protect at all costs |
| Money Tower | Generate chips | Build after Paint Tower secure |
| Defense Tower | Combat | Late game or under threat |

---

## 5. Pathfinding (Bytecode Critical)

### The Bytecode Problem
- **Limit:** 10,000 bytecode per robot per turn
- **A* / BFS:** Cannot complete within bytecode limit
- **Solution:** Bug Navigation (Bug-Nav)

### Bug Navigation Algorithm
```
Two states:
1. MOVE_TOWARD: Go directly toward target
2. TRACE_OBSTACLE: Follow obstacle wall until clear

Switch from MOVE_TOWARD → TRACE_OBSTACLE when blocked
Switch from TRACE_OBSTACLE → MOVE_TOWARD when distance to target < initial blocking distance
```

### Bug-Nav Improvements
1. **C-Shape Problem:** Simple bug-nav fails on C-shaped obstacles
   - **Fix:** Add stack to store past turning directions

2. **Timeout:** If following obstacle too long, reverse direction
   - Prevents going the long way around

3. **Distributed BFS:**
   - Too expensive for one turn (~15,000 bytecode for 20 walls)
   - Spread computation across multiple turns
   - Use spare bytecode at end of each turn

### Bytecode-Efficient Patterns
```java
// Static variables: 1 bytecode cheaper than instance
static int counter;

// Avoid nested loops
// Array init ~640 max before bytecode exceeded

// Bitshift operations are faster
int doubled = value << 1;  // faster than value * 2
```

---

## 6. Communication Strategies

### 2025 Specifics
- Towers can send 20 messages/turn
- Robots can send 1 message/turn
- Messages are 4 bytes (32 bits)

### Efficient Message Format
```
[4 bits: type][6 bits: x][6 bits: y][16 bits: payload]
```

### Communication Patterns

1. **Zone Reporting:**
   - Discretize map into 3x3 zones
   - Report zone-level info (resources, enemies)
   - Reduces message count

2. **Buffer Pool Pattern:**
   - Read global array to local copy once
   - Write to local copy with dirty flags
   - Flush only dirty indices
   - **Saves:** Up to 50% of bytecode on communication

3. **Tower as Hub:**
   - Towers have more bytecode and messages
   - Use towers as communication relay points

---

## 7. Development Advice

### From Top Teams

1. **Start Simple**
   > "Code a very basic bot first to get used to the documentation, then start from scratch with a well-thought structure."

2. **Prioritize Core Infrastructure**
   - Pathfinding
   - Resource gathering
   - Basic communications
   - Functional attacking code
   - These don't change when devs rebalance

3. **Replay Analysis is Essential**
   - Watch your losses
   - Identify specific failure modes
   - Prioritize high-impact fixes

4. **Working > Optimal**
   > "Having something working first is more important than optimality, especially given the distributed, high-dimensional, bytecode-intensive nature of Battlecode."

### Solo Competitor Advantage (from Confused, 2nd place)
- Faster iteration, no coordination overhead
- Can pivot strategy quickly
- Focus on fundamentals over fancy features

---

## 8. Actionable Improvements for mybot

### Priority 1: Paint Tower Protection
- [ ] Detect if Paint Tower count drops
- [ ] Emergency rebuild protocol
- [ ] Early-game turtle near Paint Tower

### Priority 2: Rush Detection & Counter
- [ ] Detect incoming enemy soldiers
- [ ] Spawn defensive units
- [ ] Tower attack capability

### Priority 3: Paint Conservation
- [ ] Pathfinding prefers painted tiles
- [ ] Stay on paint while attacking
- [ ] Track paint usage efficiency

### Priority 4: Economy Tracking
- [ ] Estimate own SRP/Tower count
- [ ] Estimate enemy economy
- [ ] Balance money vs paint spending

### Priority 5: Improved Pathfinding
- [ ] Add obstacle tracing timeout
- [ ] Implement direction reversal for C-shapes
- [ ] Greedy painted-tile preference

---

## Sources

- [Om Nom Postmortem (3rd Place)](https://battlecode.org/assets/files/postmortem-2025-om-nom.pdf)
- [Confused Postmortem (2nd Place)](https://battlecode.org/assets/files/postmortem-2025-confused.pdf)
- [SPAARK Postmortem (HS Champions)](https://battlecode.org/assets/files/postmortem-2025-spaark.pdf)
- [The Kragle Postmortem](https://battlecode.org/assets/files/postmortem-2025-the-kragle.pdf)
- [SPAARK GitHub Repository](https://github.com/erikji/battlecode25)
- [Battlecode Strategy Guide by xsquare](https://battlecode.org/assets/files/battlecode-guide-xsquare.pdf)
- [TheDuck314 Battlecode 2014 (1st Place)](https://github.com/TheDuck314/battlecode2014)
- [MIT Battlecode 2024 Finalist Reflections](https://towardsdatascience.com/battlecode-2024-finalist-ad3166c1acd5/)
- [Battlecode Official Site](https://battlecode.org/)
