package unit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import battlecode.common.*;
import omnom.G;

/**
 * Unit tests for G.java global utilities.
 */
public class GTest {

    @Test
    public void testDirectionFromVector() {
        assertEquals(Direction.NORTH, G.directionFromVector(0, 1));
        assertEquals(Direction.SOUTH, G.directionFromVector(0, -1));
        assertEquals(Direction.EAST, G.directionFromVector(1, 0));
        assertEquals(Direction.WEST, G.directionFromVector(-1, 0));
        assertEquals(Direction.NORTHEAST, G.directionFromVector(1, 1));
        assertEquals(Direction.NORTHWEST, G.directionFromVector(-1, 1));
        assertEquals(Direction.SOUTHEAST, G.directionFromVector(1, -1));
        assertEquals(Direction.SOUTHWEST, G.directionFromVector(-1, -1));
        assertEquals(Direction.CENTER, G.directionFromVector(0, 0));
    }

    @Test
    public void testDirectionFromVectorLargeValues() {
        // Should normalize large vectors
        assertEquals(Direction.NORTHEAST, G.directionFromVector(100, 50));
        assertEquals(Direction.SOUTHWEST, G.directionFromVector(-100, -50));
    }

    @Test
    public void testMaxIndex() {
        int[] arr1 = {5, 10, 3, 20, 8};
        assertEquals(3, G.maxIndex(arr1));

        int[] arr2 = {100, 50, 75};
        assertEquals(0, G.maxIndex(arr2));

        int[] arr3 = {1, 2, 3, 4, 5};
        assertEquals(4, G.maxIndex(arr3));
    }

    @Test
    public void testLastVisitedWithOffset() {
        G.round = 100;
        G.markVisited(new MapLocation(10, 20));
        assertEquals(100, G.getLastVisited(new MapLocation(10, 20)));

        // Test /2 grid compression - adjacent tiles map to same grid cell
        assertEquals(100, G.getLastVisited(new MapLocation(11, 21)));
    }

    @Test
    public void testRecentlyVisited() {
        G.round = 100;
        G.markVisited(new MapLocation(10, 20));

        // Just visited (round 100), threshold 50: 100 - 100 = 0 < 50 = true
        assertTrue(G.recentlyVisited(new MapLocation(10, 20), 50));

        // threshold 5: 100 - 100 = 0 < 5 = true (this should be true, not false!)
        assertTrue(G.recentlyVisited(new MapLocation(10, 20), 5));

        // Move forward in time
        G.round = 110;
        // 110 - 100 = 10 < 50 = true
        assertTrue(G.recentlyVisited(new MapLocation(10, 20), 50));
        // 110 - 100 = 10 >= 5 = false
        assertFalse(G.recentlyVisited(new MapLocation(10, 20), 5));
    }

    @Test
    public void testCooldownAbove50Percent() {
        // > 50% paint = no penalty
        int cooldown = G.cooldown(300, 100, 500);
        assertEquals(100, cooldown);
    }

    @Test
    public void testCooldownBelow50Percent() {
        // < 50% paint = penalty added
        int cooldown = G.cooldown(100, 100, 500);
        assertTrue(cooldown > 100, "Cooldown should be penalized when paint < 50%");
    }
}
