package omnom;

/**
 * xorshift32 RNG - faster than java.util.Random.
 * Source: SPAARK Random.java, research Part 5
 */
public class Random {
    public static int state;

    /**
     * xorshift32 algorithm.
     * Returns positive int (0 to 2^31-1).
     */
    public static int rand() {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 15;
        return state & 2147483647;  // Mask to positive
    }

    /**
     * Random boolean (50/50).
     */
    public static boolean nextBoolean() {
        return (rand() & 1) == 1;
    }

    /**
     * Random int in range [0, bound).
     */
    public static int nextInt(int bound) {
        return Math.abs(rand() % bound);
    }

    /**
     * Random double [0.0, 1.0).
     */
    public static double nextDouble() {
        return (double)rand() / 2147483647.0;
    }
}
