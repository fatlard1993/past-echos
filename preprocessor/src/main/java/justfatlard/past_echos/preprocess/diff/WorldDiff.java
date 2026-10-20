package justfatlard.past_echos.preprocess.diff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import justfatlard.past_echos.preprocess.diff.DiffFile.Block;
import justfatlard.past_echos.preprocess.diff.DiffFile.ChunkDiff;
import justfatlard.past_echos.preprocess.world.Chunk;
import justfatlard.past_echos.preprocess.world.Chunk.Section;
import justfatlard.past_echos.preprocess.world.Dimension;
import justfatlard.past_echos.preprocess.world.RegionFile;

/**
 * Compares a saved dimension with its reference, region file by region file, over the chunks
 * settled in both: anywhere else generation itself was unfinished, so a difference means nothing.
 */
public final class WorldDiff {
	public record Stats(int savedChunks, int compared, int differing, long blocks, int unsettledSaved, int unsettledReference,
			int excluded, List<RegionFile.Unreadable> unreadable) {
	}

	private WorldDiff() {
	}

	public static Stats diff(Dimension saved, Dimension reference, Path referenceRegions, DiffFile.Writer out) throws IOException {
		return diff(saved, reference, referenceRegions, (x, z) -> true, out);
	}

	/** Over the settled chunks the filter takes; the rest count as excluded. */
	public static Stats diff(Dimension saved, Dimension reference, Path referenceRegions, BiPredicate<Integer, Integer> include,
			DiffFile.Writer out) throws IOException {
		int compared = 0;
		int differing = 0;
		long blocks = 0;
		int unsettledSaved = 0;
		int unsettledReference = 0;
		int excluded = 0;
		List<RegionFile.Unreadable> unreadable = new ArrayList<>(saved.unreadable);
		unreadable.addAll(reference.unreadable);
		for (Path file : saved.regionFiles) {
			Map<Long, Chunk> ref = new HashMap<>();
			Path refFile = referenceRegions.resolve(file.getFileName().toString());
			if (Files.exists(refFile)) {
				RegionFile.read(refFile).chunks().forEach(c -> ref.put(key(c.x, c.z), c));
			}
			for (Chunk s : RegionFile.read(file).chunks()) {
				if (!saved.settled(s.x, s.z)) {
					unsettledSaved++;
					continue;
				}
				if (!include.test(s.x, s.z)) {
					excluded++;
					continue;
				}
				Chunk r = ref.get(key(s.x, s.z));
				if (r == null || !reference.settled(s.x, s.z)) {
					unsettledReference++;
					continue;
				}
				compared++;
				List<Block> diff = compare(s, r);
				if (!diff.isEmpty()) {
					differing++;
					blocks += diff.size();
					out.chunk(new ChunkDiff(s.x, s.z, diff));
				}
			}
		}
		return new Stats(saved.size(), compared, differing, blocks, unsettledSaved, unsettledReference, excluded, unreadable);
	}

	private static long key(int x, int z) {
		return (long) x << 32 | (z & 0xFFFFFFFFL);
	}

	/** Every block whose state differs; a section missing on one side is air there. */
	static List<Block> compare(Chunk saved, Chunk reference) {
		List<Block> out = new ArrayList<>();
		Map<Integer, Section> a = saved.sections();
		Map<Integer, Section> b = reference.sections();
		TreeSet<Integer> ys = new TreeSet<>(a.keySet());
		ys.addAll(b.keySet());
		for (int sy : ys) {
			Section s = a.get(sy);
			Section r = b.get(sy);
			// Both palettes as ids from one table, so the loop compares ints. A palette can name a
			// state twice once canonical, so palette indices alone would not do.
			Map<String, Integer> ids = new HashMap<>();
			int[] savedIds = ids(s, ids);
			int[] refIds = ids(r, ids);
			for (int i = 0; i < 4096; i++) {
				int si = s == null ? 0 : s.blocks()[i];
				int ri = r == null ? 0 : r.blocks()[i];
				if (savedIds[si] != refIds[ri]) {
					out.add(new Block(saved.x * 16 + (i & 15), sy * 16 + (i >> 8), saved.z * 16 + ((i >> 4) & 15),
							r == null ? Chunk.AIR : r.block(i), s == null ? Chunk.AIR : s.block(i)));
				}
			}
		}
		return out;
	}

	private static int[] ids(Section section, Map<String, Integer> table) {
		String[] palette = section == null ? new String[] {Chunk.AIR} : section.palette();
		int[] ids = new int[palette.length];
		for (int i = 0; i < palette.length; i++) {
			ids[i] = table.computeIfAbsent(palette[i], k -> table.size());
		}
		return ids;
	}
}
