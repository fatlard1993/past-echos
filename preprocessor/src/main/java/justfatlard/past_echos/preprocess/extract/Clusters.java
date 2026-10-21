package justfatlard.past_echos.preprocess.extract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import justfatlard.past_echos.preprocess.diff.DiffFile.Block;
import justfatlard.past_echos.preprocess.diff.DiffFile.ChunkDiff;
import justfatlard.past_echos.preprocess.extract.Vocabulary.Kind;

/**
 * Groups the differing blocks that carry evidence into clusters: blocks within {@link #REACH} of
 * each other on every axis join, so a doorway or a window of untouched blocks does not split a
 * build. Noise-weight blocks never join, so trees beside a build stay out of it.
 */
public final class Clusters {
	public static final int REACH = 2;

	/**
	 * The evidence blocks of one cluster and the box they span. The score sums every weight but
	 * earthwork's, which only says how far the cluster reaches; placed counts the blocks only
	 * players place, and dominance the share of the commonest block among the placed and
	 * structure blocks. A block the cluster both loses and gains counts in none of them: that is
	 * a structure regenerated differently, as ocean ruins crumble at random, where a player's
	 * blocks are only ever gained.
	 *
	 * <p>Rebuilt counts the built-looking blocks the regeneration has in the cluster's span where
	 * the saved world has none: where a mod's villages replace the regenerating generator's, the
	 * span holds a whole village on each side, where a player built on what was ground.
	 */
	public record Cluster(List<Block> blocks, double score, int placed, double dominance, int rebuilt, int minX, int minY, int minZ, int maxX, int maxY,
			int maxZ) {
	}

	private Clusters() {
	}

	/** Every cluster, highest score first. */
	public static List<Cluster> find(List<ChunkDiff> diff) {
		return find(diff, Evidence.of(diff));
	}

	public static List<Cluster> find(List<ChunkDiff> diff, Evidence evidence) {
		List<Block> blocks = new ArrayList<>();
		List<Double> weights = new ArrayList<>();
		for (ChunkDiff chunk : diff) {
			for (Block b : chunk.blocks()) {
				double w = evidence.weight(b.reference(), b.saved());
				if (w > 0) {
					blocks.add(b);
					weights.add(w);
				}
			}
		}
		Map<Long, Integer> at = new HashMap<>();
		for (int i = 0; i < blocks.size(); i++) {
			Block b = blocks.get(i);
			at.put(key(b.x(), b.y(), b.z()), i);
		}
		int[] parent = new int[blocks.size()];
		for (int i = 0; i < parent.length; i++) {
			parent[i] = i;
		}
		for (int i = 0; i < blocks.size(); i++) {
			Block b = blocks.get(i);
			for (int dx = -REACH; dx <= REACH; dx++) {
				for (int dy = -REACH; dy <= REACH; dy++) {
					for (int dz = -REACH; dz <= REACH; dz++) {
						Integer j = at.get(key(b.x() + dx, b.y() + dy, b.z() + dz));
						if (j != null) {
							union(parent, i, j);
						}
					}
				}
			}
		}
		Map<Long, ChunkDiff> byChunk = new HashMap<>();
		diff.forEach(c -> byChunk.put((long) c.x() << 32 | (c.z() & 0xFFFFFFFFL), c));
		Map<Integer, List<Integer>> members = new HashMap<>();
		for (int i = 0; i < blocks.size(); i++) {
			members.computeIfAbsent(find(parent, i), r -> new ArrayList<>()).add(i);
		}
		List<Cluster> clusters = new ArrayList<>();
		for (List<Integer> group : members.values()) {
			List<Block> in = new ArrayList<>(group.size());
			double score = 0;
			int placed = 0;
			Map<String, Integer> materials = new HashMap<>();
			Set<String> lost = new HashSet<>();
			Set<String> gained = new HashSet<>();
			for (int i : group) {
				if (weights.get(i) == Evidence.REMOVED) {
					lost.add(base(blocks.get(i).reference()));
				} else if (weights.get(i) >= Evidence.PLACED) {
					gained.add(base(blocks.get(i).saved()));
				}
			}
			Set<String> churned = lost;
			churned.retainAll(gained);
			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
			for (int i : group) {
				Block b = blocks.get(i);
				in.add(b);
				double w = weights.get(i);
				boolean counts = w == Evidence.REMOVED ? !churned.contains(base(b.reference()))
						: w < Evidence.PLACED || !churned.contains(base(b.saved()));
				if (counts && w != Evidence.EARTHWORK) {
					score += w;
				}
				if (counts && w >= Evidence.PLACED) {
					placed++;
				}
				if (counts && w >= Evidence.STRUCTURE) {
					materials.merge(base(b.saved()), 1, Integer::sum);
				}
				minX = Math.min(minX, b.x());
				minY = Math.min(minY, b.y());
				minZ = Math.min(minZ, b.z());
				maxX = Math.max(maxX, b.x());
				maxY = Math.max(maxY, b.y());
				maxZ = Math.max(maxZ, b.z());
			}
			clusters.add(new Cluster(in, score, placed, dominance(materials), rebuilt(byChunk, minX, minY, minZ, maxX, maxY, maxZ), minX, minY, minZ, maxX, maxY,
					maxZ));
		}
		clusters.sort((a, b) -> Double.compare(b.score, a.score));
		return clusters;
	}

	private static int rebuilt(Map<Long, ChunkDiff> byChunk, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		int rebuilt = 0;
		for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
			for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
				ChunkDiff chunk = byChunk.get((long) cx << 32 | (cz & 0xFFFFFFFFL));
				if (chunk == null) {
					continue;
				}
				for (Block b : chunk.blocks()) {
					boolean in = b.x() >= minX && b.x() <= maxX && b.y() >= minY && b.y() <= maxY && b.z() >= minZ && b.z() <= maxZ;
					if (in && Vocabulary.kind(b.reference()) == Kind.BUILT && Vocabulary.kind(b.saved()) != Kind.BUILT) {
						rebuilt++;
					}
				}
			}
		}
		return rebuilt;
	}

	private static String base(String state) {
		int bracket = state.indexOf('[');
		return bracket < 0 ? state : state.substring(0, bracket);
	}

	private static double dominance(Map<String, Integer> materials) {
		int total = materials.values().stream().mapToInt(Integer::intValue).sum();
		return total == 0 ? 1 : materials.values().stream().mapToInt(Integer::intValue).max().orElse(0) / (double) total;
	}

	/** 26 bits each of x and z, 12 of y: the overworld's ±30 million and -2048..2047. */
	static long key(int x, int y, int z) {
		return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
	}

	private static int find(int[] parent, int i) {
		while (parent[i] != i) {
			parent[i] = parent[parent[i]];
			i = parent[i];
		}
		return i;
	}

	private static void union(int[] parent, int a, int b) {
		int ra = find(parent, a);
		int rb = find(parent, b);
		if (ra != rb) {
			parent[ra] = rb;
		}
	}
}
