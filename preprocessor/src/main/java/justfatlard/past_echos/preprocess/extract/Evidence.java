package justfatlard.past_echos.preprocess.extract;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import justfatlard.past_echos.preprocess.diff.DiffFile.Block;
import justfatlard.past_echos.preprocess.diff.DiffFile.ChunkDiff;
import justfatlard.past_echos.preprocess.extract.Vocabulary.Kind;
import justfatlard.past_echos.preprocess.world.Structures;

/**
 * How strongly one differing block says a player was there, from 0 (regeneration noise) to 1
 * (placed). Regeneration redraws trees, plants, fluids, ore blobs and stone variants, so changes
 * among those weigh nothing; it never places what only players do.
 *
 * <p>Blocks {@link Vocabulary} doesn't know default to built, which a version's new natural
 * blocks are not. So a diff also says which blocks its generator makes: regeneration noise is
 * symmetric, putting a generated block on the reference side about as often as the saved side,
 * where players' blocks are nearly all on the saved side.
 *
 * <p>A block the saved world's generator made but the regenerating one can't, a mod's worldgen,
 * is on the saved side only. Chunks no player spent time near show it: what they gained there,
 * the generator placed, in about as many chunks as anywhere, where players' blocks crowd where
 * they were. That crowding overrules the reference side: where a mod's villages replace the
 * regenerating generator's, village blocks are on both sides, and players' farms are among them.
 * {@link Extract#accepted} tells those villages from the farms.
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
	/**
	 * And within a factor of this of the saved side: noise lays a generated block on both about
	 * alike, where a player's own blocks pile up on the saved side, and a regeneration's villages
	 * where the saved world's mod allowed none lay theirs on the reference side alone.
	 */
	static final int BALANCE = 4;
	/** Sampled unvisited chunks a block must be in to count as generated: more than one village's worth. */
	static final int MIN_UNVISITED_CHUNKS = 16;
	/**
	 * A built block counts as generated when the share of unvisited chunks holding it is at least
	 * this much of the share of visited ones. In the household world mods' grass and stone slabs
	 * were 0.9 to 1, clouds, sulfur and mushroom stairs 0.55 to 0.7; the blocks of players' farms,
	 * houses and mines 0.04 to 0.26, and up to 0.53 in one 10,000-chunk sample of three. Mods'
	 * biome-bound blocks, mud and bamboo slabs, were 0.24 to 0.35, so they are left to the rest.
	 */
	static final double UNVISITED_SHARE = 0.5;
	/**
	 * A block learned from the reference side is not generated after all when the share of
	 * unvisited chunks holding it is under this much of the share of visited ones: farmland and
	 * crafting tables were under 0.1 in the household world, where regeneration put other
	 * villages beside its mod's. Sulfur, both sides alike, was at 0.5.
	 */
	static final double CROWDED_SHARE = 0.25;

	/**
	 * A diff over a sample of chunks no player spent time near.
	 *
	 * @param compared how many chunks the sample compared, those that differ and those that don't
	 */
	public record Unvisited(List<ChunkDiff> diff, int compared) {
		public static final Unvisited NONE = new Unvisited(List.of(), 0);
	}

	private final Set<String> generated;
	private final Structures structures;

	private Evidence(Set<String> generated, Structures structures) {
		this.generated = generated;
		this.structures = structures;
	}

	/** Weights knowing only the vocabulary. */
	public static Evidence plain() {
		return new Evidence(Set.of(), Structures.NONE);
	}

	/** Weights for one diff, its generated blocks learned from it. */
	public static Evidence of(List<ChunkDiff> diff) {
		return of(diff, 0, Unvisited.NONE);
	}

	/**
	 * Weights for one diff over compared chunks, its generated blocks learned from it and from
	 * the unvisited sample.
	 */
	public static Evidence of(List<ChunkDiff> diff, int compared, Unvisited unvisited) {
		return of(diff, compared, unvisited, Structures.NONE);
	}

	/**
	 * Leaving out what lies in the recorded structures a regeneration places again: an ancient
	 * city or a stronghold regenerates differently, putting its bricks on both sides, unvisited
	 * chunks included, but says nothing of the same bricks in a player's house. A mod's
	 * structures stay in, its sky islands' clouds learned like any block it makes.
	 */
	public static Evidence of(List<ChunkDiff> diff, int compared, Unvisited unvisited, Structures structures) {
		Map<String, int[]> counts = new HashMap<>();
		Map<String, Integer> visitedHolding = new HashMap<>();
		for (ChunkDiff chunk : diff) {
			Set<String> holds = new HashSet<>();
			for (Block b : chunk.blocks()) {
				if (structures.regenerated(b.x(), b.y(), b.z())) {
					continue;
				}
				counts.computeIfAbsent(name(b.reference()), n -> new int[2])[0]++;
				counts.computeIfAbsent(name(b.saved()), n -> new int[2])[1]++;
				holds.add(name(b.saved()));
			}
			holds.forEach(n -> visitedHolding.merge(n, 1, Integer::sum));
		}
		Set<String> generated = new HashSet<>();
		counts.forEach((name, c) -> {
			if (c[0] >= MIN_GENERATED && c[0] * BALANCE >= c[1] && c[1] * BALANCE >= c[0]) {
				generated.add(name);
			}
		});
		Map<String, Integer> unvisitedHolding = new HashMap<>();
		for (ChunkDiff chunk : unvisited.diff()) {
			chunk.blocks().stream().filter(b -> !structures.regenerated(b.x(), b.y(), b.z())).map(b -> name(b.saved())).distinct()
					.forEach(n -> unvisitedHolding.merge(n, 1, Integer::sum));
		}
		Set<String> names = new HashSet<>(visitedHolding.keySet());
		names.addAll(unvisitedHolding.keySet());
		for (String name : names) {
			int n = unvisitedHolding.getOrDefault(name, 0);
			double expected = (double) visitedHolding.getOrDefault(name, 0) / Math.max(compared, 1) * unvisited.compared();
			if (n >= MIN_UNVISITED_CHUNKS && n >= UNVISITED_SHARE * expected) {
				generated.add(name);
			} else if (n < CROWDED_SHARE * expected && expected >= MIN_UNVISITED_CHUNKS) {
				// Crowding where players were outweighs the reference side, which another generator's villages fill.
				generated.remove(name);
			}
		}
		return new Evidence(Set.copyOf(generated), structures);
	}

	/** The built-looking blocks this diff shows the generator making. */
	public Set<String> generated() {
		return generated.stream().filter(n -> Vocabulary.kind(n) == Kind.BUILT).collect(Collectors.toUnmodifiableSet());
	}

	private Kind kind(String state) {
		Kind k = Vocabulary.kind(state);
		return k == Kind.BUILT && generated.contains(name(state)) ? Kind.GROWTH : k;
	}

	/**
	 * The weight of a differing block: none where a recorded structure regenerated otherwise, a
	 * trial chamber's copper weathered or an ancient city's bricks laid anew. A block of a
	 * player's where the regeneration has air or ground, a ruined portal's frame mended and lit,
	 * weighs as anywhere.
	 */
	public double weight(Block b) {
		Kind was = Vocabulary.kind(b.reference());
		boolean churned = (was == Kind.BUILT || was == Kind.STRUCTURE) && structures.regenerated(b.x(), b.y(), b.z());
		return churned ? 0 : weight(b.reference(), b.saved());
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
