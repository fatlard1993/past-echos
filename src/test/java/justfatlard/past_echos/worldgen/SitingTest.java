package justfatlard.past_echos.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import java.util.function.BiPredicate;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

/** The structure json's defaults: a 6 block spread, and 2 more per chunk of the longest side. */
class SitingTest {
	private static final int MAX_SLOPE = 6;
	private static final int PER_CHUNK = 2;

	private static OptionalInt site(int sizeX, int sizeZ, IntBinaryOperator top, BiPredicate<Integer, Integer> wet) {
		return Siting.ground(0, 0, sizeX - 1, sizeZ - 1, new Siting.Ground() {
			@Override
			public int top(int x, int z) {
				return top.applyAsInt(x, z);
			}

			@Override
			public boolean wet(int x, int z) {
				return wet.test(x, z);
			}
		}, MAX_SLOPE, PER_CHUNK);
	}

	/** Ground rising one block in every eight along z: 10 across the farm, 1 across the hut. */
	private static int ramp(int x, int z) {
		return 64 + z / 8;
	}

	@Test
	void aLongBuildStandsOnAGentleSlope() {
		// The household farm, 50 by 85: allowed 6 + 85 * 2 / 16 = 16.
		assertEquals(16, Siting.allowedSlope(85, MAX_SLOPE, PER_CHUNK));
		OptionalInt ground = site(50, 85, SitingTest::ramp, (x, z) -> false);
		assertTrue(ground.isPresent());
		// Sampled at z 0, 16 ... 80 and the far edge, 84: tops 64, 66, 68, 70, 72, 74, 74.
		assertEquals(70, ground.getAsInt(), "the median of the samples");
	}

	@Test
	void aSmallBuildStillWantsLevelGround() {
		// The hut, 9 across: allowed 6 + 1 = 7, so a 9-block step under it is too much.
		assertTrue(site(9, 9, (x, z) -> x < 5 ? 64 : 73, (x, z) -> false).isEmpty());
		assertTrue(site(9, 9, SitingTest::ramp, (x, z) -> false).isPresent());
	}

	@Test
	void theLongSideSetsTheAllowanceButNotPastIt() {
		// 20 blocks of rise across the farm is more than 16.
		assertTrue(site(50, 85, (x, z) -> 64 + z * 20 / 84, (x, z) -> false).isEmpty());
	}

	@Test
	void aPondIsAllowedButNotALake() {
		// One wet sample among the farm's 24 is a pond; a quarter wet is a lake shore.
		assertTrue(site(50, 85, (x, z) -> 64, (x, z) -> x == 0 && z == 0).isPresent());
		assertTrue(site(50, 85, (x, z) -> 64, (x, z) -> x < 16).isEmpty());
		assertTrue(site(9, 9, (x, z) -> 64, (x, z) -> x == 4 && z == 4).isEmpty(), "never on water at the middle");
	}
}
