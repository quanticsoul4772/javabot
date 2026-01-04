package spaark3;

/**
 * Custom xorshift32 random number generator.
 * Much cheaper than java.util.Random in bytecode.
 * Deterministic seeding by robot ID for reproducibility.
 */
public class Random {

    private static int state;

    /**
     * Initialize RNG with combined seed.
     * Note: Battlecode is deterministic - same map = same game.
     */
    public static void init(int robotId, int mapWidth, int mapHeight, int spawnX, int spawnY) {
        // Combine sources of entropy (deterministic within same map)
        state = robotId ^ (mapWidth * 1000) ^ (mapHeight * 100000) ^ (spawnX * 17) ^ (spawnY * 31);
        if (state == 0) state = 1;  // xorshift can't have 0 state
        // Mix the state a bit
        nextInt();
        nextInt();
    }

    /**
     * Simple init with just robot ID (legacy).
     */
    public static void init(int seed) {
        state = seed;
        if (state == 0) state = 1;
    }

    /**
     * Generate next random integer (positive).
     * Uses xorshift32 algorithm - very fast.
     */
    public static int nextInt() {
        state ^= state << 13;
        state ^= state >>> 17;
        state ^= state << 5;
        return state & 0x7FFFFFFF;  // Mask to positive
    }

    /**
     * Generate random integer in range [0, bound).
     */
    public static int nextInt(int bound) {
        return nextInt() % bound;
    }

    /**
     * Generate random double in range [0.0, 1.0).
     */
    public static double nextDouble() {
        return (nextInt() & 0xFFFFFF) / (double) 0x1000000;
    }

    /**
     * Generate random boolean (50/50).
     */
    public static boolean nextBoolean() {
        return (nextInt() & 1) == 1;
    }
}
