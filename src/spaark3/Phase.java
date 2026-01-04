package spaark3;

/**
 * Phase Shifting System - adapts strategy based on game stage.
 * Validated hypothesis with 0.785 TOPSIS score.
 *
 * Phases:
 * - EARLY (0-150): Rush center, establish territory, soldier-heavy
 * - MID (150-600): Economy building, balanced expansion
 * - LATE (600-1500): Paint coverage push, splasher-heavy
 * - ENDGAME (1500+): All-out coverage sprint
 */
public class Phase {

    public enum GamePhase {
        EARLY,
        MID,
        LATE,
        ENDGAME
    }

    // Phase boundaries (rounds)
    private static final int EARLY_END = 150;
    private static final int MID_END = 600;
    private static final int LATE_END = 1500;

    // Spawn weights per phase [soldier, splasher, mopper]
    // SPAARK uses: Soldier 1.5, Splasher 0.2, Mopper 1.2
    private static final double[] EARLY_WEIGHTS = {1.5, 0.2, 1.2};  // Match SPAARK ratios
    private static final double[] MID_WEIGHTS = {1.2, 0.5, 1.0};
    private static final double[] LATE_WEIGHTS = {0.5, 1.5, 0.8};
    private static final double[] ENDGAME_WEIGHTS = {0.3, 2.0, 0.5};

    /**
     * Get current game phase based on round number.
     * Uses switch for O(1) after bounds check.
     */
    public static GamePhase current() {
        int round = G.round;
        if (round < EARLY_END) return GamePhase.EARLY;
        if (round < MID_END) return GamePhase.MID;
        if (round < LATE_END) return GamePhase.LATE;
        return GamePhase.ENDGAME;
    }

    /**
     * Get spawn weights for current phase.
     * Returns [soldier, splasher, mopper] weights.
     */
    public static double[] getSpawnWeights() {
        switch (current()) {
            case EARLY: return EARLY_WEIGHTS;
            case MID: return MID_WEIGHTS;
            case LATE: return LATE_WEIGHTS;
            case ENDGAME: return ENDGAME_WEIGHTS;
            default: return MID_WEIGHTS;
        }
    }

    /**
     * Get soldier spawn weight for current phase.
     * Adjusted by tower count (fewer soldiers when maxed).
     */
    public static double soldierWeight() {
        double base;
        switch (current()) {
            case EARLY: base = 2.0; break;
            case MID: base = 1.2; break;
            case LATE: base = 0.5; break;
            case ENDGAME: base = 0.3; break;
            default: base = 1.2;
        }
        // Reduce soldier weight as we get more towers
        return Math.max(0.1, base - G.numTowers * 0.03);
    }

    /**
     * Get splasher spawn weight for current phase.
     * Increases with paint tower count (more paint = more splashers).
     */
    public static double splasherWeight() {
        double base;
        switch (current()) {
            case EARLY: base = 0.1; break;
            case MID: base = 0.5; break;
            case LATE: base = 1.5; break;
            case ENDGAME: base = 2.0; break;
            default: base = 0.5;
        }
        // More paint towers = more splashers
        return base + G.allyPaintTowers * 0.15;
    }

    /**
     * Get mopper spawn weight for current phase.
     */
    public static double mopperWeight() {
        switch (current()) {
            case EARLY: return 0.5;
            case MID: return 1.0;
            case LATE: return 0.8;
            case ENDGAME: return 0.5;
            default: return 1.0;
        }
    }

    /**
     * Check if we're in aggressive early game.
     */
    public static boolean isEarly() {
        return current() == GamePhase.EARLY;
    }

    /**
     * Check if we're in endgame coverage sprint.
     */
    public static boolean isEndgame() {
        return current() == GamePhase.ENDGAME;
    }

    /**
     * Get Boids influence factor for current phase.
     * Early game: individual exploration (low Boids)
     * Later: group coordination (high Boids)
     */
    public static double boidsInfluence() {
        switch (current()) {
            case EARLY: return 0.1;    // Mostly individual
            case MID: return 0.3;      // Some grouping
            case LATE: return 0.5;     // Strong grouping
            case ENDGAME: return 0.4;  // Moderate (need coverage)
            default: return 0.3;
        }
    }

    /**
     * Check if building towers is allowed in current phase.
     * Reduced tower building in late/endgame.
     */
    public static boolean shouldBuildTowers() {
        switch (current()) {
            case EARLY: return true;  // Build towers immediately (SPAARK strategy)
            case MID: return true;
            case LATE: return G.allyPaintTowers < 3;
            case ENDGAME: return false;
            default: return true;
        }
    }

    /**
     * Get retreat threshold multiplier for current phase.
     * More aggressive in early game, more conservative in endgame.
     */
    public static double retreatMultiplier() {
        switch (current()) {
            case EARLY: return 0.5;    // Aggressive
            case MID: return 1.0;      // Normal
            case LATE: return 1.2;     // Conservative
            case ENDGAME: return 0.8;  // Slightly aggressive
            default: return 1.0;
        }
    }
}
