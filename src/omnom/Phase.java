package omnom;

/**
 * Phase Shifting system - adapt strategy by game stage.
 * Research validation: Score 0.785 (highest-ranked hypothesis)
 * Source: bot_spec.md Part 4
 */
public class Phase {
    public static final int EARLY = 0;
    public static final int MID = 1;
    public static final int LATE = 2;
    public static final int ENDGAME = 3;

    /**
     * Determine current game phase based on round number.
     */
    public static int currentPhase(int round) {
        if (round < 150) return EARLY;
        if (round < 600) return MID;
        if (round < 1500) return LATE;
        return ENDGAME;
    }

    /**
     * Get soldier spawn weight for current phase.
     * EARLY: 2.0 (soldier-heavy)
     * MID: 1.2 (balanced)
     * LATE: 0.5 (splasher-heavy)
     * ENDGAME: 0.3 (splasher-heavy)
     */
    public static double getSoldierWeight(int round) {
        switch (currentPhase(round)) {
            case EARLY: return 2.0;
            case MID: return 1.2;
            case LATE: return 0.5;
            case ENDGAME: return 0.3;
        }
        return 1.0;  // Fallback
    }

    /**
     * Get splasher spawn weight for current phase.
     */
    public static double getSplasherWeight(int round) {
        switch (currentPhase(round)) {
            case EARLY: return 0.1;
            case MID: return 0.5;
            case LATE: return 1.5;
            case ENDGAME: return 2.0;
        }
        return 0.5;  // Fallback
    }

    /**
     * Get mopper spawn weight for current phase.
     */
    public static double getMopperWeight(int round) {
        switch (currentPhase(round)) {
            case EARLY: return 0.5;
            case MID: return 1.0;
            case LATE: return 0.8;
            case ENDGAME: return 0.5;
        }
        return 1.0;  // Fallback
    }

    /**
     * Check if should build towers in current phase.
     */
    public static boolean shouldBuildTowers(int round) {
        // Build towers in all phases except very early
        return round >= 10;
    }
}
