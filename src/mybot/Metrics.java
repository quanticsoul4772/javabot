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
    // Soldier states: IDLE=0, BUILDING=1, DEFENDING=2, RETREATING=3
    public static int[] soldierStateTurns = new int[4];

    // Splasher states: IDLE=0, MOVING_TO_SPLASH=1, ADVANCING=2
    public static int[] splasherStateTurns = new int[3];

    // Mopper states: IDLE=0, CHASING=1, CLEANING=2
    public static int[] mopperStateTurns = new int[3];

    // ==================== RESOURCE EFFICIENCY ====================
    public static int tilesContested = 0;      // Enemy tiles painted over
    public static int tilesExpanded = 0;       // Empty tiles painted
    public static int towersBuilt = 0;         // Successful tower completions
    public static int towerAttempts = 0;       // Tower building attempts

    // ==================== COMBAT STATS ====================
    public static int attacksLanded = 0;
    public static int splashesExecuted = 0;
    public static int mopSwings = 0;
    public static int retreatsTriggered = 0;   // Times we retreated

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
        if (ENABLED && state >= 0 && state < 4) {
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
}
