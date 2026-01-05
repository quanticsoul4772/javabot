package unit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import omnom.Random;

/**
 * Unit tests for Random.java xorshift32 RNG.
 */
public class RandomTest {

    @Test
    public void testDeterministic() {
        Random.state = 42;
        int first = Random.rand();

        Random.state = 42;
        int second = Random.rand();

        assertEquals(first, second, "Same seed should produce same output");
    }

    @Test
    public void testNextBooleanDistribution() {
        Random.state = 123;
        int trueCount = 0;

        for (int i = 0; i < 1000; i++) {
            if (Random.nextBoolean()) trueCount++;
        }

        // Should be roughly 50% (allow 40-60% range)
        assertTrue(trueCount > 400 && trueCount < 600,
            "Boolean distribution should be ~50%, got " + trueCount);
    }

    @Test
    public void testNextIntBounds() {
        Random.state = 456;

        for (int i = 0; i < 100; i++) {
            int val = Random.nextInt(10);
            assertTrue(val >= 0 && val < 10,
                "nextInt(10) should be in [0,10), got " + val);
        }
    }

    @Test
    public void testNextDouble() {
        Random.state = 789;

        for (int i = 0; i < 100; i++) {
            double val = Random.nextDouble();
            assertTrue(val >= 0.0 && val < 1.0,
                "nextDouble() should be in [0.0,1.0), got " + val);
        }
    }

    @Test
    public void testNotAllZeros() {
        Random.state = 1;
        int val = Random.rand();

        assertNotEquals(0, val, "xorshift32 should not return 0");
    }
}
