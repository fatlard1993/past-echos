package justfatlard.past_echos.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Whether a footprint is fit to hold a ruin, and at what ground height. Terrain adaptation levels
 * what it stands on, so a long build may stand on land that falls further across it than a small
 * one: the height it may vary by grows with the footprint's longest side.
 */
public final class Siting {
	/** Ground sampled about this far apart, and at every edge. */
	static final int STEP = 16;
	/** The share of samples that may be water: a pond in a field, not a lake. */
	static final double MAX_WET = 1.0 / 8;

	public interface Ground {
		/** The y of the top block at x, z. */
		int top(int x, int z);

		/** Whether that top block is water or another fluid. */
		boolean wet(int x, int z);
	}

	private Siting() {
	}

	/** The height spread allowed under a footprint whose longest side is this long. */
	public static int allowedSlope(int longest, int maxSlope, int slopePerChunk) {
		return maxSlope + longest * slopePerChunk / STEP;
	}

	/** The median ground height across the footprint, or empty when it is too steep or too wet. */
	public static OptionalInt ground(int x0, int z0, int x1, int z1, Ground ground, int maxSlope, int slopePerChunk) {
		List<Integer> tops = new ArrayList<>();
		int wet = 0;
		for (int x : steps(x0, x1)) {
			for (int z : steps(z0, z1)) {
				tops.add(ground.top(x, z));
				wet += ground.wet(x, z) ? 1 : 0;
			}
		}
		int cx = (x0 + x1) / 2;
		int cz = (z0 + z1) / 2;
		if (ground.wet(cx, cz) || wet > tops.size() * MAX_WET) {
			return OptionalInt.empty();
		}
		tops.sort(null);
		int allowed = allowedSlope(Math.max(x1 - x0, z1 - z0) + 1, maxSlope, slopePerChunk);
		if (tops.getLast() - tops.getFirst() > allowed) {
			return OptionalInt.empty();
		}
		return OptionalInt.of(tops.get(tops.size() / 2));
	}

	private static List<Integer> steps(int from, int to) {
		List<Integer> at = new ArrayList<>();
		for (int v = from; v < to; v += STEP) {
			at.add(v);
		}
		at.add(to);
		return at;
	}
}
