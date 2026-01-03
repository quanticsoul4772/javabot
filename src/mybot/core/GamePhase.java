package mybot.core;

/**
 * Game phase detection and dynamic thresholds.
 * Provides phase-aware configuration for adaptive strategies.
 */
public class GamePhase {

    public enum Phase {
        EARLY,      // 0-100: Expand territory, build first towers
        MID_EARLY,  // 100-300: Contest territory, transition to splashers
        MID,        // 300-600: Heavy territory fighting
        MID_LATE,   // 600-800: Push for dominance
        LATE        // 800+: All-out aggression
    }

    /**
     * Get current game phase based on round number.
     */
    public static Phase get(int round) {
        if (round < 100) return Phase.EARLY;
        if (round < 300) return Phase.MID_EARLY;
        if (round < 600) return Phase.MID;
        if (round < 800) return Phase.MID_LATE;
        return Phase.LATE;
    }

    // ==================== DYNAMIC THRESHOLDS ====================

    /**
     * Get health threshold for retreat based on phase.
     * Later phases = more aggressive (lower threshold).
     */
    public static int getHealthCritical(Phase phase) {
        switch (phase) {
            case EARLY:     return 20;  // Conservative
            case MID_EARLY: return 15;
            case MID:       return 15;
            case MID_LATE:  return 10;  // Aggressive
            case LATE:      return 5;   // Very aggressive
            default:        return 15;
        }
    }

    /**
     * Get paint threshold for resupply based on phase.
     * Later phases = fight with less paint.
     */
    public static int getPaintLow(Phase phase) {
        switch (phase) {
            case EARLY:     return 60;  // Conservative
            case MID_EARLY: return 50;
            case MID:       return 40;
            case MID_LATE:  return 35;  // Aggressive
            case LATE:      return 30;  // Very aggressive
            default:        return 50;
        }
    }

    /**
     * Get paint threshold for resuming activity after resupply.
     */
    public static int getPaintResume(Phase phase) {
        switch (phase) {
            case EARLY:     return 90;
            case MID_EARLY: return 80;
            case MID:       return 70;
            case MID_LATE:  return 60;
            case LATE:      return 50;
            default:        return 80;
        }
    }

    // ==================== SPAWN RATIOS ====================

    /**
     * Get soldier spawn ratio for phase.
     */
    public static float getSoldierRatio(Phase phase) {
        switch (phase) {
            case EARLY:     return 0.90f;  // Almost all soldiers early
            case MID_EARLY: return 0.50f;
            case MID:       return 0.30f;
            case MID_LATE:  return 0.20f;
            case LATE:      return 0.15f;  // Few soldiers late
            default:        return 0.30f;
        }
    }

    /**
     * Get splasher spawn ratio for phase.
     */
    public static float getSplasherRatio(Phase phase) {
        switch (phase) {
            case EARLY:     return 0.00f;  // No splashers early
            case MID_EARLY: return 0.30f;
            case MID:       return 0.50f;
            case MID_LATE:  return 0.60f;
            case LATE:      return 0.70f;  // Most splashers late
            default:        return 0.50f;
        }
    }

    /**
     * Get mopper spawn ratio for phase.
     */
    public static float getMopperRatio(Phase phase) {
        switch (phase) {
            case EARLY:     return 0.10f;
            case MID_EARLY: return 0.20f;
            case MID:       return 0.20f;
            case MID_LATE:  return 0.20f;
            case LATE:      return 0.15f;
            default:        return 0.20f;
        }
    }

    // ==================== STRATEGIC FLAGS ====================

    /**
     * Should we prioritize building towers in this phase?
     */
    public static boolean shouldBuildTowers(Phase phase) {
        return phase == Phase.EARLY || phase == Phase.MID_EARLY;
    }

    /**
     * Should we prioritize building SRPs in this phase?
     */
    public static boolean shouldBuildSRPs(Phase phase) {
        return phase == Phase.EARLY || phase == Phase.MID_EARLY || phase == Phase.MID;
    }

    /**
     * Should we be aggressive and push enemy territory?
     */
    public static boolean shouldPushTerritory(Phase phase) {
        return phase == Phase.MID || phase == Phase.MID_LATE || phase == Phase.LATE;
    }

    /**
     * Is this late game where we should go all-out?
     */
    public static boolean isLateGame(Phase phase) {
        return phase == Phase.LATE;
    }

    /**
     * Is this early game where we should focus on economy?
     */
    public static boolean isEarlyGame(Phase phase) {
        return phase == Phase.EARLY || phase == Phase.MID_EARLY;
    }

    // ==================== CONVENIENCE ====================

    /**
     * Get current phase from round number.
     */
    public static Phase current(int round) {
        return get(round);
    }

    /**
     * Get phase name for logging.
     */
    public static String getName(Phase phase) {
        return phase.name();
    }
}
