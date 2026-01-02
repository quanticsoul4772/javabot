# Performance Analysis and Improvement Recommendations

Analysis of mybot vs winning Battlecode 2025 strategies using mcp-reasoning tools.

## Current Bot Strengths

The bot already implements several winning-team patterns:

| Feature | Implementation | Status |
|---------|---------------|--------|
| Priority chain pattern | P0-P8 with early returns | Done |
| Paint tower emergency response | PAINT_TOWER_CRITICAL at health 100 | Done |
| Adaptive spawning | Counter enemy splashers (70% soldiers, 30% moppers) | Done |
| Mopper-splasher coordination | SPLASHER_THREATENED message | Done |
| Phase coordination | PHASE_DEFEND, PHASE_ALL_OUT_ATTACK | Done |
| Bug2 navigation | Paint awareness (+10 ally, -5 enemy) | Done |
| C-shape handling | Direction switch after 15 turns | Done |

## Gap Analysis

Based on winning team postmortems (Om Nom 3rd, Confused 2nd, SPAARK HS Champions):

### Gap 1: Ruin Denial Strategy

**Winning team insight:** "Paint ALL ruins you see (prevents enemy tower construction)"

**Current state:** Soldiers find buildable ruins but don't prioritize painting contested ruins to deny enemy towers.

**Impact:** Enemy can build towers on uncontested ruins, gaining compound economic advantage.

**Implementation:**
- Add P5.5 priority: Paint contested ruins (between early game defense and tower building)
- Use `rc.senseNearbyRuins(-1)` to detect ruins
- Check if ruin has enemy paint nearby and paint to deny
- Report via RUIN_FOUND message for coordination

```java
// Proposed addition to Soldier.java after P5 (early game defense)
// ===== PRIORITY 5.5: RUIN DENIAL =====
MapLocation[] ruins = rc.senseNearbyRuins(-1);
for (MapLocation ruin : ruins) {
    RobotInfo robot = rc.senseRobotAtLocation(ruin);
    if (robot != null) continue; // Already has tower

    // Check if ruin area has enemy paint
    MapInfo ruinInfo = rc.senseMapInfo(ruin);
    if (ruinInfo.getPaint().isEnemy()) {
        // Can't paint over enemy - let splashers handle
        Comms.reportRuin(rc, ruin);
        continue;
    }

    // Paint this ruin to deny enemy
    if (rc.canAttack(ruin)) {
        rc.attack(ruin);
        Metrics.trackRuinDenied();
        rc.setIndicatorString("P5.5: Denying ruin!");
        return;
    }
}
```

### Gap 2: Paint Conservation in Combat

**Winning team insight:** "Soldiers should almost always stay on painted tiles while attacking. Saving even a little paint means you can land a few more attacks."

**Current state:** `engageEnemy()` method considers paint in movement scoring but doesn't enforce staying on ally paint during combat.

**Impact:** Units take unnecessary paint damage during engagements, reducing combat effectiveness.

**Implementation:**
- Modify `engageEnemy()` to prefer attacking from ally-painted tiles
- Add check: if current tile is enemy paint and ally paint is adjacent, move first
- Only attack from enemy paint as fallback when no ally paint available

```java
// Proposed modification to engageEnemy() in Soldier.java
private static void engageEnemy(RobotController rc, RobotInfo enemy) throws GameActionException {
    MapLocation myLoc = rc.getLocation();
    MapLocation enemyLoc = enemy.getLocation();
    MapInfo currentTile = rc.senseMapInfo(myLoc);

    // PAINT CONSERVATION: Move to ally paint before attacking if possible
    if (!currentTile.getPaint().isAlly()) {
        // Find adjacent ally-painted tile within attack range of enemy
        Direction[] dirs = Utils.DIRECTIONS;
        for (Direction dir : dirs) {
            if (!rc.canMove(dir)) continue;
            MapLocation newLoc = myLoc.add(dir);
            MapInfo newTile = rc.senseMapInfo(newLoc);
            if (newTile.getPaint().isAlly() &&
                newLoc.distanceSquaredTo(enemyLoc) <= rc.getType().actionRadiusSquared) {
                rc.move(dir);
                myLoc = newLoc;
                break;
            }
        }
    }

    // Now attack from (hopefully) ally-painted position
    if (rc.canAttack(enemyLoc)) {
        rc.attack(enemyLoc);
        Metrics.trackAttack();
    }

    // Existing movement logic...
}
```

### Gap 3: SRP-Based Economy Tracking

**Winning team insight:** "Income = (20 + 3 * #SRPs) * #MoneyTowers. Track income changes to estimate economy."

**Current state:** `Utils.updateIncomeEstimate()` exists but only tracks raw income. Phase transitions use fixed round numbers (300, 600, 1500).

**Impact:** Suboptimal phase timing and spawn decisions when economic conditions differ from round-based assumptions.

**Implementation:**
- Calculate SRP count from income pattern
- Use economic advantage/disadvantage for phase decisions
- Adjust spawn ratios based on relative economy

```java
// Proposed addition to Utils.java
private static int[] incomeHistory = new int[10];
private static int incomeIndex = 0;
private static int estimatedSRPs = 0;
private static int estimatedMoneyTowers = 1;

public static void updateEconomyTracking(RobotController rc) throws GameActionException {
    int currentMoney = rc.getMoney();
    int income = currentMoney - lastMoney;
    lastMoney = currentMoney;

    // Track income history
    incomeHistory[incomeIndex] = income;
    incomeIndex = (incomeIndex + 1) % 10;

    // Average income over last 10 turns
    int avgIncome = 0;
    for (int i = 0; i < 10; i++) avgIncome += incomeHistory[i];
    avgIncome /= 10;

    // Back-calculate SRPs: income = (20 + 3*SRPs) * towers
    // Assume we know tower count from sensing
    if (estimatedMoneyTowers > 0) {
        int basePerTower = avgIncome / estimatedMoneyTowers;
        estimatedSRPs = Math.max(0, (basePerTower - 20) / 3);
    }
}

public static boolean isEconomicallyStrong() {
    return estimatedSRPs >= 3 || estimatedMoneyTowers >= 2;
}
```

## Priority Ranking

| Priority | Improvement | Impact | Complexity | Bytecode Cost |
|----------|-------------|--------|------------|---------------|
| 1 | Ruin Denial | HIGH | LOW | ~200/turn |
| 2 | Paint Conservation | HIGH | MEDIUM | ~100/turn |
| 3 | Economy Tracking | MEDIUM | MEDIUM | ~50/turn |

## Implementation Order

1. **Ruin Denial** (quick win)
   - Add sensing and priority logic to Soldier.java
   - Test: 10 matches, measure ruin control % at turn 100

2. **Paint Conservation** (combat improvement)
   - Modify engageEnemy() in Soldier.java
   - Apply similar logic to Splasher.java combat
   - Test: 10 matches, measure unit survival time

3. **Economy Tracking** (strategic refinement)
   - Enhance Utils.java with tracking
   - Modify Tower.java to use economic state for spawn decisions
   - Test: 10 matches on economy-focused maps

## Validation Approach

```bash
# Run 10 matches with indicators
./gradlew run -PteamA=mybot -PteamB=examplefuncsplayer -Pmaps=DefaultSmall 2>&1 | grep -E "P5.5|RUIN|denied"

# Compare win rates before/after each improvement
# Baseline: Current bot vs examplefuncsplayer
# Test: Each improvement individually
```

## Risk Assessment

| Improvement | Risk | Mitigation |
|-------------|------|------------|
| Ruin Denial | May divert from tower building | Only trigger when not actively building |
| Paint Conservation | May miss attack opportunities | Fallback: attack anyway if no ally paint |
| Economy Tracking | Estimation errors | Use conservative thresholds, round-based fallback |

## Sources

- Om Nom Postmortem (3rd place): battlecode.org/assets/files/postmortem-2025-om-nom.pdf
- Confused Postmortem (2nd place): battlecode.org/assets/files/postmortem-2025-confused.pdf
- SPAARK Postmortem (HS Champions): battlecode.org/assets/files/postmortem-2025-spaark.pdf
- WINNING_STRATEGIES.md: Local research compilation
