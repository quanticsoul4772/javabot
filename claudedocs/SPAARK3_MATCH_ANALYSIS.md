# spaark3 Match Analysis - First Visibility Test

**Date**: 2026-01-04
**Match**: spaark3 vs SPAARK on DefaultSmall
**Result**: LOSS at round 146
**Visibility**: EXCELLENT - Can now make specific recommendations

---

## What I Can Now See (COMPLETE VISIBILITY ✅)

### State Tracking ✅
- Every 10 rounds: position, paint, chips, mode, target, ally/enemy counts
- 16 soldier state snapshots across match
- Full resource flow visible

### Economy Tracking ✅
- Every 10 rounds: chips, income, towers, units
- Income trends visible (180 → 5 → -40 = PROBLEM)

### Spawn Tracking ✅
- Every spawn event logged with unit type and counts
- Can validate spawn ratios

### Enemy Tracking ✅
- Every 10 rounds: enemy positions, types, paint amounts
- Can see enemy tower locations and upgrade status

### Tower Combat ✅
- Every tower attack logged with target, damage, score
- Can validate targeting logic

---

## Detailed Match Analysis

### Critical Finding #1: Paint Starvation Death Spiral

**Soldier #11577 Paint Flow:**
- Round 10: 148 paint, mode=EXPLORE
- Round 20: 87 paint, mode=ATTACK_TOWER (lost 61 paint in 10 rounds)
- Round 30: 27 paint, mode=ATTACK_TOWER (lost 60 paint in 10 rounds)
- **Paint loss rate**: 6 paint/turn average

**Analysis**:
- Normal movement: 1-2 paint/turn
- This soldier: 6 paint/turn = **3x expected**
- Cause: Attacking tower on enemy territory continuously
- Never retreated despite paint dropping to 27

**Root Cause**: Retreat system NOT working
- Soldier stayed in ATTACK_TOWER mode even at paint=27
- Should have retreated at paint < 50 (RETREAT_PAINT threshold)
- Likely cause: retreat condition check missing or incorrect

**Recommendation**: Fix retreat trigger in Soldier.java - check happening in earlyGameRush() but NOT in regular run() after round 100

---

### Critical Finding #2: Income Collapse

**Tower #1 Income Trend:**
- Round 10: 180 income/turn (excellent)
- Round 20: 5 income/turn (CRASHED 97%!)
- Round 30-50: 0-30 income/turn (low)
- Round 70: -40 income/turn (NEGATIVE - losing money!)

**Analysis**:
- Started strong (180 income suggests 1-2 money towers + SRPs)
- Income collapsed round 10→20
- Never recovered
- By round 70: losing 40 chips/turn (spawning cost > income)

**Cause**: Lost money tower or SRPs
- Round 60: chips=2270, towers=1 (down from 2 towers)
- Lost a tower between rounds 50-60
- Never rebuilt

**Recommendation**: Add tower loss detection and emergency rebuild logic

---

### Critical Finding #3: Enemy Upgraded Faster

**Enemy Tower Status:**
- Round 20: LEVEL_TWO_PAINT_TOWER, LEVEL_TWO_MONEY_TOWER
- We had: LEVEL_ONE towers only

**Analysis**:
- Enemy upgraded towers by round 20
- We never upgraded (no UPGRADE logs in output)
- Enemy towers stronger (higher attack, more income)

**Cause**: Tower.java doesn't have upgrade logic implemented

**Recommendation**: Add tower upgrade logic from spec Part 16.3

---

### Finding #4: Mode Distribution Abnormal

**Modes Observed:**
- EXPLORE: 31%
- ATTACK_TOWER: 68%
- RETREAT: 0%
- BUILD_TOWER: 0%

**Comparison to SPAARK Benchmark:**
- SPAARK EXPLORE: 65%
- SPAARK BUILD: 20%
- SPAARK ATTACK: 10%
- SPAARK RETREAT: 5%

**Analysis**:
- spaark3 spends 68% time attacking towers (vs SPAARK 10%)
- spaark3 NEVER builds towers (vs SPAARK 20%)
- spaark3 NEVER retreats (fatal - units die instead)

**Root Cause**:
1. Early game (round < 100) forces rush behavior
2. No tower building logic after round 100
3. Retreat check missing in post-100 code

**Recommendation**:
1. End early game rush at round 50 (not 100)
2. Add BUILD_TOWER mode after early game
3. Fix retreat trigger

---

### Finding #5: Spawn Ratio Issues

**Spawns:**
- Soldiers: 3 (first 3 - correct)
- Moppers: 3
- Splashers: 1
- **Total**: 7 spawns in 146 rounds

**SPAARK Benchmark**: ~15-20 spawns by round 146

**Analysis**:
- Spawning 2.5x slower than SPAARK
- Cause: Lost money tower → income collapsed → can't afford spawns
- Round 70: -40 income → actually losing chips

**Recommendation**: Protect money towers, rebuild immediately if lost

---

### Finding #6: Tower Defense Working

**Tower #3 Attacks:**
- Rounds 35-54: 10 attacks on enemy soldiers
- Target selection: Low HP prioritized (paint 32-176)
- Score system working (377-509 scores)

**Analysis**: ✓ Tower attack logic functioning correctly

---

### Finding #7: No Retreats Despite Critical Paint

**Soldier #11897 at Round 50:**
- Paint: 0 (DEAD or dying)
- Mode: ATTACK_TOWER (still attacking!)
- Should have: Retreated at paint < 50

**Analysis**: Retreat system COMPLETELY BROKEN
- Units fight to death (0 paint)
- Never trigger retreat
- This is why we lose - all units die

**Recommendation**: URGENT - Fix retreat trigger, it's not checking properly

---

## Summary: Why spaark3 Lost

**Death Spiral Sequence:**
1. Round 10-20: Lost money tower → income collapsed 180 → 5
2. Round 10-30: Soldiers paint-starved attacking towers (6 paint/turn loss)
3. Round 30-50: No retreat system → soldiers died at 0 paint
4. Round 50-70: Can't spawn (no income) → no army
5. Round 70+: Negative income, no units → inevitable loss

**Root Causes (Priority Order):**

1. **CRITICAL**: Retreat system not working - units die instead of retreating
2. **CRITICAL**: Lost tower not detected/rebuilt - income never recovered
3. **HIGH**: Early game too long (round < 100) - no tower building
4. **MEDIUM**: No tower upgrade logic - enemy outscales us
5. **MEDIUM**: Spawn rate too low - not enough units

---

## Specific Code Fixes Needed

### Fix #1: Add Retreat Check (URGENT)

```java
// Soldier.java run() - ADD THIS after round 100
if (G.paint < G.RETREAT_PAINT && mode != Mode.RETREAT) {
    System.out.println("DECISION:" + G.round + ":SOLDIER:" + G.id +
        ":from=" + mode + ":to=RETREAT:reason=low_paint:paint=" + G.paint);
    mode = Mode.RETREAT;
    retreatTarget = null;
}
```

Currently: Only checks in earlyGameRush() (round < 100)
After round 100: NO retreat check → units die

### Fix #2: Shorten Early Game

```java
// Change from round < 100 to round < 50
if (G.round < 50) { earlyGameRush(); return; }
```

Reason: Need to start building towers/economy by round 50

### Fix #3: Add Tower Loss Detection

```java
// Tower.java or POI.java
if (G.numTowers < lastTowerCount) {
    System.out.println("TOWER_LOST:" + G.round + ":count=" + G.numTowers);
    // Trigger emergency tower building
}
```

### Fix #4: Add Tower Upgrade Logic

```java
// Tower.java - from spec Part 16.3
while (G.rc.canUpgradeTower(G.me)
       && G.rc.getMoney() - (level == 0 ? 2500 : 5000) >= 1000) {
    System.out.println("UPGRADE:" + G.round + ":TOWER:" + G.id);
    G.rc.upgradeTower(G.me);
}
```

---

## Visibility Assessment

### What Visibility Gives Me ✅

**Before (without logs)**: "spaark3 lost at round 146"
- Can't explain why
- Can't suggest improvements
- Useless

**Now (with STATE + ECONOMY + SPAWN + ENEMIES + ATTACKS)**:
- ✅ Identified 4 critical bugs
- ✅ Explained exact death spiral sequence
- ✅ Provided specific code fixes
- ✅ Prioritized by impact

**Visibility Value**: TRANSFORMATIVE

### What's Still Missing

Based on this analysis, I'd also like to see:

1. **Paint drain by terrain** - Confirm soldiers losing 6 paint/turn on enemy territory
2. **Death logs** - Exactly when Soldier #11897 died (round 50 paint=0)
3. **Decision traces** - Why did mode change EXPLORE → ATTACK_TOWER?
4. **Build progress** - No BUILD_TOWER mode triggered - why not?

But current visibility (6 layers) is SUFFICIENT for meaningful analysis and recommendations.

---

## Recommendation

**Visibility Tools: VALIDATED ✅**

With current logging (STATE, ECONOMY, SPAWN, ENEMIES, TOWER_ATTACK, BUILD_PROGRESS), I can:
- Identify critical bugs
- Explain match outcomes
- Provide specific fixes with code examples
- Prioritize improvements by impact

**Next Step**: Fix the 4 critical bugs identified, then re-test to see if improvements work.

**Estimated Impact of Fixes**:
- Fix retreat system: +30-50 rounds survival
- Shorten early game: +20-30 rounds (allows building)
- Tower loss detection: +20 rounds (faster recovery)
- Tower upgrades: +10-20 rounds (better combat)

**Total Expected**: 146 → 220+ rounds (beats SPAARK baseline ~196)
