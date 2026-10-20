package justfatlard.past_echos.preprocess.extract;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import justfatlard.past_echos.preprocess.diff.DiffFile.Block;
import justfatlard.past_echos.preprocess.diff.DiffFile.ChunkDiff;
import justfatlard.past_echos.preprocess.extract.Vocabulary.Kind;

/**
 * How strongly one differing block says a player was there, from 0 (regeneration noise) to 1
 * (placed). Regeneration redraws trees, plants, fluids, ore blobs and stone variants, so changes
 * among those weigh nothing; it never places what only players do.
 *
 * <p>Blocks {@link Vocabulary} doesn't know default to built, which a version's new natural
 * blocks are not. So a diff also says which blocks its generator makes: regeneration noise is
 * symmetric, putting a generated block on the reference side about as often as the saved side,
 * where players' blocks are nearly all on the saved side.
 */
public final class Evidence {
	public static final double PLACED = 1;
	/**
	 * A structure block the generator also scatters: mineshaft rails and planks, dungeon walls, and
	 * the cobblestone where lava meets water. From 1.18 these come and go between generations.
	 */
	public static final double STRUCTURE = 0.5;
	/** A built block gone: a structure looted or mined out. Lost scattered structure blocks weigh nothing. */
	public static final double REMOVED = 0.3;
	/**
	 * Ground dug out or filled in. Lakes and springs carve differently on regeneration, by
	 * hundreds of blocks, so earthwork joins clusters but never makes one an echo alone.
	 */
	public static final double EARTHWORK = 0.25;
	/** A placed block whose state changed: a door opened, a lamp lit. */
	public static final double RESTATED = 0.1;
	/** Reference-side blocks a built block needs to count as generated: more than a broken village door or two. */
	static final int MIN_GENERATED = 16;
	/** And at least a quarter as many as on the saved side, where a player's own blocks pile up. */
	static final int BALANCE = 4;

	private final Set<String> generated;

	private Evidence(Set<String> generated) {
		this.generated = generated;
	}

	/** Weights knowing only the vocabulary. */
	public static Evidence plain() {
		return new Evidence(Set.of());
	}

	/** Weights for one diff, its generated blocks learned from it. */
	public static Evidence of(List<ChunkDiff> diff) {
		Map<String, int[]> counts = new HashMap<>();
		for (ChunkDiff chunk : diff) {
			for (Block b : chunk.blocks()) {
				counts.computeIfAbsent(name(b.reference()), n -> new int[2])[0]++;
				counts.computeIfAbsent(name(b.saved()), n -> new int[2])[1]++;
			}
		}
		return new Evidence(counts.entrySet().stream()
				.filter(e -> e.getValue()[0] >= MIN_GENERATED && e.getValue()[0] * BALANCE >= e.getValue()[1])
				.map(Map.Entry::getKey)
				.collect(Collectors.toUnmodifiableSet()));
	}

	/** The built-looking blocks this diff shows the generator making. */
	public Set<String> generated() {
		return generated.stream().filter(n -> Vocabulary.kind(n) == Kind.BUILT).collect(Collectors.toUnmodifiableSet());
	}

	private Kind kind(String state) {
		Kind k = Vocabulary.kind(state);
		return k == Kind.BUILT && generated.contains(name(state)) ? Kind.GROWTH : k;
	}

	public double weight(String reference, String saved) {
		if (Vocabulary.spreads(reference) || Vocabulary.spreads(saved)) {
			return 0;
		}
		Kind was = kind(reference);
		Kind now = kind(saved);
		if (now == Kind.BUILT) {
			return was == Kind.BUILT && name(reference).equals(name(saved)) ? RESTATED : PLACED;
		}
		if (now == Kind.STRUCTURE) {
			return was == Kind.STRUCTURE || was == Kind.BUILT ? 0 : STRUCTURE;
		}
		if (was == Kind.BUILT) {
			return REMOVED;
		}
		if (was == Kind.STRUCTURE) {
			return 0;
		}
		boolean dug = was == Kind.GROUND && now == Kind.AIR && !Vocabulary.falls(reference);
		boolean filled = was == Kind.AIR && now == Kind.GROUND && !Vocabulary.falls(saved);
		return dug || filled ? EARTHWORK : 0;
	}

	/** The block without its properties; numeric ids keep their meta, which is part of the block. */
	private static String name(String state) {
		int bracket = state.indexOf('[');
		return bracket < 0 ? state : state.substring(0, bracket);
	}
}
