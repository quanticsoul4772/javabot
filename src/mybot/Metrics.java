package mybot;

/**
 * Lightweight metrics collection for bot improvement feedback.
 * All static fields - persists across turns with zero allocation cost.
 *
 * Usage: Call track*() methods at decision points, Tower reports periodically.
 * Bytecode impact: ~5-10 per call (<0.1% of 15k budget)
 */
public class Metrics {

    // Feature toggle - set false for competition
    public static final boolean ENABLED = true;

    // ==================== PRIORITY TRACKING ====================
    // Soldier priorities P0-P8
    public static int[] soldierPriority = new int[9];

    // Splasher priorities P0-P5
    public static int[] splasherPriority = new int[6];

    // Mopper priorities P0-P5
    public static int[] mopperPriority = new int[6];

    // Tower priorities P0-P7
    public static int[] towerPriority = new int[8];

    // ==================== FSM STATE TRACKING ====================
    // Soldier states: IDLE=0, BUILDING_TOWER=1, BUILDING_SRP=2, DEFENDING=3, RETREATING=4
    public static int[] soldierStateTurns = new int[5];

    // Splasher states: IDLE=0, MOVING_TO_SPLASH=1, ADVANCING=2
    public static int[] splasherStateTurns = new int[3];

    // Mopper states: IDLE=0, CHASING=1, CLEANING=2
    public static int[] mopperStateTurns = new int[3];

    // ==================== RESOURCE EFFICIENCY ====================
    public static int tilesContested = 0;      // Enemy tiles painted over
    public static int tilesExpanded = 0;       // Empty tiles painted
    public static int towersBuilt = 0;         // Successful tower completions
    public static int towerAttempts = 0;       // Tower building attempts
    public static int ruinsDenied = 0;         // Ruins painted to deny enemy

    // ==================== COMBAT STATS ====================
    public static int attacksLanded = 0;
    public static int splashesExecuted = 0;
    public static int mopSwings = 0;
    public static int retreatsTriggered = 0;   // Times we retreated

    // ==================== RETREAT TRACKING ====================
    public static int retreatFoundTower = 0;      // Retreated and found tower
    public static int retreatFoundPaint = 0;      // Retreated and found ally paint
    public static int retreatWandering = 0;       // Retreated but wandering randomly
    public static int retreatSuccessful = 0;      // Actually refilled paint
    public static int retreatTurnsWandering = 0;  // Total turns spent wandering

    // ==================== UNIT LIFECYCLE ====================
    // 0=soldier, 1=splasher, 2=mopper, 3=tower
    public static int[] unitSpawned = new int[4];
    public static int[] unitDeaths = new int[4];
    public static int[] totalSurvivalTurns = new int[4];

    // ==================== PAINT COVERAGE ====================
    public static int allyPaintTiles = 0;
    public static int enemyPaintTiles = 0;
    public static int neutralPaintTiles = 0;

    // ==================== PAINT CONSERVATION ====================
    public static int combatTurnsOnAllyPaint = 0;
    public static int combatTurnsTotal = 0;

    // ==================== ECONOMY ====================
    public static int paintTowersBuilt = 0;
    public static int moneyTowersBuilt = 0;
    public static int defenseTowersBuilt = 0;

    // ==================== SRP (SPECIAL RESOURCE PATTERNS) ====================
    public static int srpAttempts = 0;
    public static int srpsBuilt = 0;

    // ==================== COMMUNICATION ====================
    public static int messagesSent = 0;
    public static int messagesActedOn = 0;

    // ==================== WIN CONDITION ====================
    public static int mapTilesTotal = 0;
    public static int mapTilesAlly = 0;
    public static int winProgressPct = 0;  // Current % toward 70% victory

    // ==================== TIMING MILESTONES ====================
    public static int roundFirstTower = -1;
    public static int roundFirstDefense = -1;
    public static int roundFirstSRP = -1;
    public static int roundReached50Pct = -1;
    public static int roundReached60Pct = -1;

    // ==================== ECONOMY TRACKING ====================
    public static int peakChips = 0;
    public static int peakPaint = 0;
    public static int lowPaintEvents = 0;  // Times unit paint < 50
    public static int totalPaintSpent = 0;

    // ==================== ENEMY INTELLIGENCE ====================
    public static int enemyUnitsSpotted = 0;
    public static int enemyTowersSpotted = 0;
    public static int enemyUnitsKilled = 0;  // Approximate via disappearance

    // ==================== HELPER METHODS ====================

    public static void trackSoldierPriority(int priority) {
        if (ENABLED && priority >= 0 && priority < 9) {
            soldierPriority[priority]++;
        }
    }

    public static void trackSplasherPriority(int priority) {
        if (ENABLED && priority >= 0 && priority < 6) {
            splasherPriority[priority]++;
        }
    }

    public static void trackMopperPriority(int priority) {
        if (ENABLED && priority >= 0 && priority < 6) {
            mopperPriority[priority]++;
        }
    }

    public static void trackTowerPriority(int priority) {
        if (ENABLED && priority >= 0 && priority < 8) {
            towerPriority[priority]++;
        }
    }

    public static void trackSoldierState(int state) {
        if (ENABLED && state >= 0 && state < 5) {
            soldierStateTurns[state]++;
        }
    }

    public static void trackSplasherState(int state) {
        if (ENABLED && state >= 0 && state < 3) {
            splasherStateTurns[state]++;
        }
    }

    public static void trackMopperState(int state) {
        if (ENABLED && state >= 0 && state < 3) {
            mopperStateTurns[state]++;
        }
    }

    // ==================== CONVENIENCE METHODS ====================

    public static void trackAttack() {
        if (ENABLED) attacksLanded++;
    }

    public static void trackSplash() {
        if (ENABLED) splashesExecuted++;
    }

    public static void trackMopSwing() {
        if (ENABLED) mopSwings++;
    }

    public static void trackRetreat() {
        if (ENABLED) retreatsTriggered++;
    }

    /**
     * Track retreat outcome. Type: "tower", "paint", "wandering", "success"
     */
    public static void trackRetreatOutcome(String type) {
        if (!ENABLED) return;
        switch(type) {
            case "tower": retreatFoundTower++; break;
            case "paint": retreatFoundPaint++; break;
            case "wandering":
                retreatWandering++;
                retreatTurnsWandering++;
                break;
            case "success": retreatSuccessful++; break;
        }
    }

    public static void trackTileContested() {
        if (ENABLED) tilesContested++;
    }

    public static void trackTileExpanded() {
        if (ENABLED) tilesExpanded++;
    }

    public static void trackTowerBuilt() {
        if (ENABLED) towersBuilt++;
    }

    public static void trackTowerAttempt() {
        if (ENABLED) towerAttempts++;
    }

    public static void trackRuinDenied() {
        if (ENABLED) ruinsDenied++;
    }

    public static void trackSRPAttempt() {
        if (ENABLED) srpAttempts++;
    }

    public static void trackSRPBuilt() {
        if (ENABLED) srpsBuilt++;
    }

    // ==================== NEW TRACKING METHODS ====================

    /**
     * Track unit spawn. Type: 0=soldier, 1=splasher, 2=mopper, 3=tower
     */
    public static void trackSpawn(int unitType) {
        if (ENABLED && unitType >= 0 && unitType < 4) {
            unitSpawned[unitType]++;
        }
    }

    /**
     * Track unit death with survival time.
     */
    public static void trackDeath(int unitType, int survivalTurns) {
        if (ENABLED && unitType >= 0 && unitType < 4) {
            unitDeaths[unitType]++;
            totalSurvivalTurns[unitType] += survivalTurns;
        }
    }

    /**
     * Track paint coverage sample from visible tiles.
     */
    public static void trackPaintSample(int ally, int enemy, int neutral) {
        if (ENABLED) {
            allyPaintTiles += ally;
            enemyPaintTiles += enemy;
            neutralPaintTiles += neutral;
        }
    }

    /**
     * Track combat turn - whether unit is on ally paint.
     */
    public static void trackCombatTurn(boolean onAllyPaint) {
        if (ENABLED) {
            combatTurnsTotal++;
            if (onAllyPaint) combatTurnsOnAllyPaint++;
        }
    }

    /**
     * Track message sent.
     */
    public static void trackMessageSent() {
        if (ENABLED) messagesSent++;
    }

    /**
     * Track message acted upon.
     */
    public static void trackMessageActedOn() {
        if (ENABLED) messagesActedOn++;
    }

    // ==================== WIN CONDITION TRACKING ====================

    /**
     * Track progress toward 70% win condition.
     */
    public static void trackWinProgress(int allyTiles, int totalTiles) {
        if (ENABLED) {
            mapTilesAlly = allyTiles;
            mapTilesTotal = totalTiles;
            winProgressPct = totalTiles > 0 ? allyTiles * 100 / totalTiles : 0;
        }
    }

    // ==================== MILESTONE TRACKING ====================

    /**
     * Track timing milestones. Type: "tower", "defense", "srp", "50pct", "60pct"
     */
    public static void trackMilestone(String type, int round) {
        if (!ENABLED) return;
        switch(type) {
            case "tower": if (roundFirstTower < 0) roundFirstTower = round; break;
            case "defense": if (roundFirstDefense < 0) roundFirstDefense = round; break;
            case "srp": if (roundFirstSRP < 0) roundFirstSRP = round; break;
            case "50pct": if (roundReached50Pct < 0) roundReached50Pct = round; break;
            case "60pct": if (roundReached60Pct < 0) roundReached60Pct = round; break;
        }
    }

    // ==================== ECONOMY TRACKING ====================

    /**
     * Track economy metrics (chips and paint levels).
     */
    public static void trackEconomy(int chips, int paint) {
        if (ENABLED) {
            if (chips > peakChips) peakChips = chips;
            if (paint > peakPaint) peakPaint = paint;
        }
    }

    /**
     * Track low paint event.
     */
    public static void trackLowPaint() {
        if (ENABLED) lowPaintEvents++;
    }

    /**
     * Track paint spent on actions.
     */
    public static void trackPaintSpent(int amount) {
        if (ENABLED) totalPaintSpent += amount;
    }

    // ==================== ENEMY INTELLIGENCE ====================

    /**
     * Track enemy sightings.
     */
    public static void trackEnemySighting(int units, int towers) {
        if (ENABLED) {
            enemyUnitsSpotted += units;
            enemyTowersSpotted += towers;
        }
    }

    /**
     * Track enemy kill (approximate).
     */
    public static void trackEnemyKill() {
        if (ENABLED) enemyUnitsKilled++;
    }

    // ==================== TOWER TYPE TRACKING ====================

    /**
     * Track tower built by type.
     */
    public static void trackTowerBuiltByType(String towerType) {
        if (!ENABLED) return;
        if (towerType.contains("PAINT")) {
            paintTowersBuilt++;
        } else if (towerType.contains("MONEY")) {
            moneyTowersBuilt++;
        } else if (towerType.contains("DEFENSE")) {
            defenseTowersBuilt++;
        }
    }

    // ==================== PER-UNIT REPORTING ====================
    // Note: In Battlecode, static state is NOT shared between robots.
    // Each robot has its own Metrics instance. These functions let each
    // robot report its own lifetime statistics.

    /**
     * Report this soldier's lifetime stats (call periodically or on death).
     */
    public static void reportSoldierStats(int robotId, int round) {
        if (!ENABLED) return;
        int total = 0;
        for (int p : soldierPriority) total += p;
        if (total == 0) return; // Nothing to report

        System.out.println("[SOLDIER #" + robotId + " r" + round + "] " +
            "P0=" + soldierPriority[0] + " P3=" + soldierPriority[3] +
            " P6=" + soldierPriority[6] + " P8=" + soldierPriority[8] +
            " attacks=" + attacksLanded + " towers=" + towersBuilt);
    }

    /**
     * Report this splasher's lifetime stats.
     */
    public static void reportSplasherStats(int robotId, int round) {
        if (!ENABLED) return;
        int total = 0;
        for (int p : splasherPriority) total += p;
        if (total == 0) return;

        System.out.println("[SPLASHER #" + robotId + " r" + round + "] " +
            "P2=" + splasherPriority[2] + " P4=" + splasherPriority[4] +
            " splashes=" + splashesExecuted);
    }

    /**
     * Report this mopper's lifetime stats.
     */
    public static void reportMopperStats(int robotId, int round) {
        if (!ENABLED) return;
        int total = 0;
        for (int p : mopperPriority) total += p;
        if (total == 0) return;

        System.out.println("[MOPPER #" + robotId + " r" + round + "] " +
            "P2=" + mopperPriority[2] + " P4=" + mopperPriority[4] +
            " mops=" + mopSwings);
    }

    // ==================== GAME SUMMARY REPORTING ====================

    /**
     * Report comprehensive game metrics (call from Tower every 100 rounds).
     */
    public static void reportGameSummary(int round) {
        if (!ENABLED) return;

        // Paint coverage
        int totalTiles = allyPaintTiles + enemyPaintTiles + neutralPaintTiles;
        int allyPct = totalTiles > 0 ? allyPaintTiles * 100 / totalTiles : 0;
        int enemyPct = totalTiles > 0 ? enemyPaintTiles * 100 / totalTiles : 0;

        // Check for win progress milestones
        if (winProgressPct >= 50 && roundReached50Pct < 0) {
            roundReached50Pct = round;
        }
        if (winProgressPct >= 60 && roundReached60Pct < 0) {
            roundReached60Pct = round;
        }

        // Survival times (average)
        int soldierSurvival = unitDeaths[0] > 0 ? totalSurvivalTurns[0] / unitDeaths[0] : 0;
        int splasherSurvival = unitDeaths[1] > 0 ? totalSurvivalTurns[1] / unitDeaths[1] : 0;

        // Paint conservation
        int conservationPct = combatTurnsTotal > 0 ?
            combatTurnsOnAllyPaint * 100 / combatTurnsTotal : 100;

        // Tower success rate
        int towerSuccessPct = towerAttempts > 0 ? towersBuilt * 100 / towerAttempts : 0;

        // Message effectiveness
        int msgEffectPct = messagesSent > 0 ? messagesActedOn * 100 / messagesSent : 0;

        System.out.println("[GAME r" + round + "] " +
            "WinProg=" + winProgressPct + "% " +
            "Paint=" + allyPct + "%/" + enemyPct + "% " +
            "Towers=" + paintTowersBuilt + "P/" + moneyTowersBuilt + "M/" + defenseTowersBuilt + "D " +
            "SRPs=" + srpsBuilt + " " +
            "Contested=" + tilesContested + " " +
            "Enemies=" + enemyUnitsSpotted + "/" + enemyTowersSpotted);

        // Milestone report at end of game
        if (round >= 1900) {
            System.out.println("[MILESTONES] " +
                "1stTower=r" + roundFirstTower + " " +
                "1stDefense=r" + roundFirstDefense + " " +
                "1stSRP=r" + roundFirstSRP + " " +
                "50%=r" + roundReached50Pct + " " +
                "60%=r" + roundReached60Pct);
        }
    }
}
