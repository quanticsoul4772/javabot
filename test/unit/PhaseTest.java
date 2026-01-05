package unit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import omnom.Phase;

/**
 * Unit tests for Phase.java shifting system.
 */
public class PhaseTest {

    @Test
    public void testPhaseDetection() {
        assertEquals(Phase.EARLY, Phase.currentPhase(50));
        assertEquals(Phase.EARLY, Phase.currentPhase(149));
        assertEquals(Phase.MID, Phase.currentPhase(150));
        assertEquals(Phase.MID, Phase.currentPhase(599));
        assertEquals(Phase.LATE, Phase.currentPhase(600));
        assertEquals(Phase.LATE, Phase.currentPhase(1499));
        assertEquals(Phase.ENDGAME, Phase.currentPhase(1500));
        assertEquals(Phase.ENDGAME, Phase.currentPhase(2000));
    }

    @Test
    public void testSoldierWeightDecreases() {
        // Soldier weight should decrease over phases
        double early = Phase.getSoldierWeight(50);
        double mid = Phase.getSoldierWeight(300);
        double late = Phase.getSoldierWeight(1000);
        double endgame = Phase.getSoldierWeight(1600);

        assertTrue(early > mid, "Soldier weight should decrease EARLY→MID");
        assertTrue(mid > late, "Soldier weight should decrease MID→LATE");
        assertTrue(late > endgame, "Soldier weight should decrease LATE→ENDGAME");
    }

    @Test
    public void testSplasherWeightIncreases() {
        // Splasher weight should increase over phases
        double early = Phase.getSplasherWeight(50);
        double mid = Phase.getSplasherWeight(300);
        double late = Phase.getSplasherWeight(1000);
        double endgame = Phase.getSplasherWeight(1600);

        assertTrue(early < mid, "Splasher weight should increase EARLY→MID");
        assertTrue(mid < late, "Splasher weight should increase MID→LATE");
        assertTrue(late < endgame, "Splasher weight should increase LATE→ENDGAME");
    }

    @Test
    public void testWeightsNormalize() {
        // Weights should sum to reasonable total
        for (int round : new int[]{50, 300, 1000, 1600}) {
            double sum = Phase.getSoldierWeight(round)
                       + Phase.getSplasherWeight(round)
                       + Phase.getMopperWeight(round);

            // Should be positive and reasonable
            assertTrue(sum > 0, "Total weight should be positive at round " + round);
            assertTrue(sum < 10, "Total weight should be reasonable at round " + round);
        }
    }

    @Test
    public void testShouldBuildTowers() {
        assertFalse(Phase.shouldBuildTowers(5), "Don't build towers very early");
        assertTrue(Phase.shouldBuildTowers(10), "Should build towers after round 10");
        assertTrue(Phase.shouldBuildTowers(100), "Should build towers mid-game");
    }
}
