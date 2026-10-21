package justfatlard.past_echos.preprocess.extract;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import justfatlard.past_echos.preprocess.diff.Diff;
import justfatlard.past_echos.preprocess.diff.DiffFile;
import justfatlard.past_echos.preprocess.diff.DiffFile.Block;
import justfatlard.past_echos.preprocess.diff.DiffFile.ChunkDiff;
import justfatlard.past_echos.preprocess.extract.Clusters.Cluster;
import justfatlard.past_echos.preprocess.extract.EchoFile.Echo;
import justfatlard.past_echos.preprocess.regen.Json;
import justfatlard.past_echos.preprocess.world.Chunk;
import justfatlard.past_echos.preprocess.world.Dimension;
import justfatlard.past_echos.preprocess.world.Nbt;
import justfatlard.past_echos.preprocess.world.RegionFile;

/**
 * Stage two: sorts a world's diff into player work and regeneration noise, and cuts each piece
 * of work out of the saved world with the terrain around it, as an echo.
 */
public final class Extract {
	/**
	 * The least score an echo needs, ruling out stray pieces: mineshaft rails and cobwebs stay
	 * under 5. Larger noise (lava-made cobblestone reached 40) is ruled out by what it lacks.
	 */
	public static final double MIN_SCORE = 10;
	/**
	 * The fewest blocks only players place (a door, a crafting table, glass) an echo needs.
	 * Generation makes dungeon walls and lava-meets-water cobblestone by the dozen, but none of these.
	 */
	public static final int MIN_PLACED = 3;
	/**
	 * The largest share one block may have of an echo's placed and structure blocks. A natural
	 * block the vocabulary misses, and that grows with time so its noise is lopsided, clusters
	 * nearly alone; builds mix materials (the test hut's commonest, planks, is 41%).
	 */
	public static final double MAX_DOMINANCE = 0.8;
	/**
	 * The most built blocks the regeneration may have in a cluster's span where the saved world
	 * has none, per block the cluster gained. Where a mod's villages replace the regenerating
	 * generator's, the span holds a whole village on each side, where a player built on what was
	 * ground: in the household world its players' builds had 0 to 0.4 and its villages 1.3 to 80.
	 */
	public static final double MAX_REBUILT = 1;
	/**
	 * How far round a cluster to look for a village the regeneration built of the same blocks:
	 * a mod's house can stand beside the regenerating generator's houses rather than on one.
	 */
	public static final int NEAR = 64;
	/**
	 * How alike, as the cosine of counts by block, what a cluster gained and what the regeneration
	 * built near it must be for the cluster to be part of that village. In the household world
	 * pieces of villages were 0.51 to 0.99, its players' builds 0 to 0.02, all else under 0.42.
	 */
	public static final double NEAR_LIKENESS = 0.5;
	/** And how much the regeneration must have built near it: more than a stray door or two. */
	public static final int MIN_NEAR = 64;
	/** Terrain kept around each echo's evidence, on every side. */
	public static final int MARGIN = 1;
	/**
	 * The largest share of an echo's worked columns that may lie under natural ground. Mines and
	 * tunnels are left for later: a torch-lit branch mine passes every other test.
	 */
	public static final double MAX_COVERED = 0.5;
	/** The widest echo the mod can place, ruling out noise that clusters across a whole region. */
	public static final int MAX_SPAN = 240;
	/**
	 * How alike two echoes' placed blocks must be, as the cosine of their counts by block, to be
	 * copies of one thing. The household world's pirate ships and boats were 0.95 to 1 alike in
	 * any rotation, cloud kingdom ruins and village houses over 0.95; its players' builds had no
	 * look-alike far off at all.
	 */
	public static final double COPY_LIKENESS = 0.95;
	/** Copies nearer than this may be one player's row of houses. */
	public static final int COPIES_APART = 256;
	/** As many copies of one thing as make it a generated structure. */
	public static final int COPIES = 3;
	/** How alike in palette, as the same cosine, two echoes must be for their layouts to be compared. */
	public static final double COPY_PALETTE = 0.8;
	/**
	 * The share of the larger echo's evidence two echoes must hold in the same layout, in some
	 * turn or mirroring, to be copies of one generated thing, so that a single copy far off is
	 * enough. In the household world ships, village houses and sky ruins held 0.57 to 1 with their
	 * copies; its players' builds 0.02 to 0.09 with their look-alikes, torch-lit cobbled deepslate
	 * shafts of one palette among them.
	 */
	public static final double COPY_SHAPE = 0.5;

	private Extract() {
	}

	public static void main(String[] args) throws IOException {
		Path diffDir = null;
		Path world = null;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--diff" -> {
					if (++i >= args.length) {
						usage("--diff needs the folder past-echos diff wrote");
					}
					diffDir = Path.of(args[i]);
				}
				case "--help", "-h" -> {
					usage(null);
					return;
				}
				default -> {
					if (args[i].startsWith("-") || world != null) {
						usage("unexpected " + args[i]);
					}
					world = Path.of(args[i]);
				}
			}
		}
		if (world == null) {
			usage("give the world the diff was made from");
			return;
		}
		if (diffDir == null) {
			diffDir = Path.of(world.getFileName().toString().replaceFirst("\\.zip$", "") + "-diff");
		}
		Path diffFile = diffDir.resolve("overworld.diff.gz");
		if (!Files.exists(diffFile)) {
			usage("no diff at " + diffFile + "; run past-echos diff on the world first");
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> info = (Map<String, Object>) Json.parse(Files.readString(diffDir.resolve("diff.json")));
		String version = (String) info.get("version");
		Path unvisitedFile = diffDir.resolve(Diff.UNVISITED_FILE);
		Evidence.Unvisited unvisited = Files.exists(unvisitedFile) && info.get("unvisitedCompared") instanceof Number n
				? new Evidence.Unvisited(DiffFile.read(unvisitedFile), n.intValue())
				: Evidence.Unvisited.NONE;
		int compared = ((Number) info.get("compared")).intValue();

		FileSystem zip = world.toString().endsWith(".zip") ? FileSystems.newFileSystem(world) : null;
		try {
			Path root = zip == null ? world : Diff.findWorld(zip.getPath("/"));
			List<ChunkDiff> diff = DiffFile.read(diffFile);
			Evidence evidence = Evidence.of(diff, compared, unvisited);
			if (unvisited.compared() > 0) {
				System.out.printf("learned from %,d unvisited chunks; generated: %s%n", unvisited.compared(),
						evidence.generated().stream().sorted().collect(Collectors.joining(", ")));
			}
			Cut whole = cut(diff, evidence, Dimension.overworldRegions(root));
			List<Echo> cut = whole.echoes();
			List<Echo> unrepeated = unrepeated(cut);
			List<Echo> echoes = unrepeated.stream().filter(Extract::onSurface).toList();
			Path out = diffDir.resolve("echoes.gz");
			EchoFile.write(out, version, echoes);
			Files.writeString(diffDir.resolve("echoes.json"), summary(version, echoes));
			int repeated = cut.size() - unrepeated.size();
			int underground = unrepeated.size() - echoes.size();
			System.out.printf("%s: %,d %s%s%s%s%n", version, echoes.size(), echoes.size() == 1 ? "echo" : "echoes",
					repeated > 0 ? String.format(", %,d copies of generated structures skipped", repeated) : "",
					underground > 0 ? String.format(", %,d underground skipped", underground) : "",
					whole.tooWide() > 0 ? String.format(", %,d wider than %d skipped", whole.tooWide(), MAX_SPAN) : "");
			for (Echo e : echoes) {
				System.out.printf("  %6.1f  %dx%dx%d at %d,%d,%d  %s%n", e.score(), e.sizeX(), e.sizeY(), e.sizeZ(), e.x(), e.y(), e.z(),
						topEvidence(e, 4).entrySet().stream().map(t -> t.getValue() + " " + t.getKey()).collect(Collectors.joining(", ")));
			}
			System.out.println("  -> " + out);
		} finally {
			if (zip != null) {
				zip.close();
			}
		}
	}

	/** The clusters in a diff that count as player work. */
	public static List<Cluster> accepted(List<ChunkDiff> diff, Evidence evidence) {
		Map<Long, ChunkDiff> byChunk = new HashMap<>();
		diff.forEach(c -> byChunk.put(chunkKey(c.x(), c.z()), c));
		return Clusters.find(diff, evidence).stream()
				.filter(c -> c.score() >= MIN_SCORE && c.placed() >= MIN_PLACED && c.dominance() <= MAX_DOMINANCE)
				.filter(c -> !inVillage(c, byChunk, evidence))
				.toList();
	}

	/** Whether the regeneration built a village where the cluster stands, or beside it of the same blocks. */
	private static boolean inVillage(Cluster c, Map<Long, ChunkDiff> byChunk, Evidence evidence) {
		if (total(rebuilt(c, 0, byChunk)) > MAX_REBUILT * c.placed()) {
			return true;
		}
		Map<String, Integer> near = rebuilt(c, NEAR, byChunk);
		if (total(near) < MIN_NEAR) {
			return false;
		}
		Map<String, Integer> gained = new HashMap<>();
		for (Block b : c.blocks()) {
			if (evidence.weight(b.reference(), b.saved()) >= Evidence.PLACED) {
				gained.merge(base(b.saved()), 1, Integer::sum);
			}
		}
		return likeness(gained, near) >= NEAR_LIKENESS;
	}

	/** The built-looking blocks the regeneration has within reach of a cluster's span where the saved world has none, by block. */
	private static Map<String, Integer> rebuilt(Cluster c, int reach, Map<Long, ChunkDiff> byChunk) {
		Map<String, Integer> rebuilt = new HashMap<>();
		for (int cx = (c.minX() - reach) >> 4; cx <= (c.maxX() + reach) >> 4; cx++) {
			for (int cz = (c.minZ() - reach) >> 4; cz <= (c.maxZ() + reach) >> 4; cz++) {
				ChunkDiff chunk = byChunk.get(chunkKey(cx, cz));
				if (chunk == null) {
					continue;
				}
				for (Block b : chunk.blocks()) {
					boolean in = b.x() >= c.minX() - reach && b.x() <= c.maxX() + reach && b.y() >= c.minY() - reach && b.y() <= c.maxY() + reach
							&& b.z() >= c.minZ() - reach && b.z() <= c.maxZ() + reach;
					if (in && Vocabulary.kind(b.reference()) == Vocabulary.Kind.BUILT && Vocabulary.kind(b.saved()) != Vocabulary.Kind.BUILT) {
						rebuilt.merge(base(b.reference()), 1, Integer::sum);
					}
				}
			}
		}
		return rebuilt;
	}

	private static int total(Map<String, Integer> counts) {
		return counts.values().stream().mapToInt(Integer::intValue).sum();
	}

	private static String base(String state) {
		int bracket = state.indexOf('[');
		return bracket < 0 ? state : state.substring(0, bracket);
	}

	/** The echoes in a diff above ground, cut from the saved world's region folder, highest score first. */
	public static List<Echo> run(List<ChunkDiff> diff, Path savedRegions) throws IOException {
		return unrepeated(cut(diff, savedRegions)).stream().filter(Extract::onSurface).toList();
	}

	/**
	 * The echoes without {@link #COPIES} or more far-apart copies alike in palette, or one alike
	 * in layout: those are a generated structure the regeneration placed elsewhere or not at all,
	 * a mod's ship, village house or sky ruin.
	 */
	public static List<Echo> unrepeated(List<Echo> echoes) {
		List<Map<String, Integer>> placed = echoes.stream().map(Extract::placed).toList();
		Map<Integer, Layout> layouts = new HashMap<>();
		List<Echo> kept = new ArrayList<>();
		for (int i = 0; i < echoes.size(); i++) {
			int copies = 1;
			boolean copied = false;
			for (int j = 0; j < echoes.size() && copies < COPIES && !copied; j++) {
				long dx = echoes.get(i).x() - echoes.get(j).x();
				long dz = echoes.get(i).z() - echoes.get(j).z();
				if (dx * dx + dz * dz <= (long) COPIES_APART * COPIES_APART) {
					continue;
				}
				double likeness = likeness(placed.get(i), placed.get(j));
				copies += likeness >= COPY_LIKENESS ? 1 : 0;
				copied = likeness >= COPY_PALETTE
						&& layouts.computeIfAbsent(i, k -> Layout.of(echoes.get(k))).shared(layouts.computeIfAbsent(j, k -> Layout.of(echoes.get(k)))) >= COPY_SHAPE;
			}
			if (copies < COPIES && !copied) {
				kept.add(echoes.get(i));
			}
		}
		return kept;
	}

	/** An echo's evidence blocks by block, as positions in its box. */
	private record Layout(Map<String, List<int[]>> cells, int size) {
		static Layout of(Echo e) {
			Map<String, List<int[]>> cells = new HashMap<>();
			int size = 0;
			for (int y = 0; y < e.sizeY(); y++) {
				for (int z = 0; z < e.sizeZ(); z++) {
					for (int x = 0; x < e.sizeX(); x++) {
						String state = e.state(x, y, z);
						if (e.mark(x, y, z) == EchoFile.EVIDENCE && Vocabulary.kind(state) != Vocabulary.Kind.AIR) {
							cells.computeIfAbsent(base(state), k -> new ArrayList<>()).add(new int[] {x, y, z});
							size++;
						}
					}
				}
			}
			return new Layout(cells, size);
		}

		/**
		 * The most blocks the two hold alike in the same places, over every turn and mirroring of
		 * the smaller and every shift that lays one of its rarest blocks on one of the larger's, as
		 * a share of the larger.
		 */
		double shared(Layout o) {
			Layout small = size <= o.size ? this : o;
			Layout large = small == this ? o : this;
			Map<String, Set<Long>> at = new HashMap<>();
			large.cells.forEach((name, list) -> at.put(name, list.stream().map(c -> Clusters.key(c[0], c[1], c[2])).collect(Collectors.toSet())));
			String anchor = small.cells.keySet().stream().filter(at::containsKey).min(Comparator.comparingInt(n -> at.get(n).size())).orElse(null);
			if (anchor == null) {
				return 0;
			}
			int best = 0;
			for (int turn = 0; turn < 8; turn++) {
				int[] from = turned(small.cells.get(anchor).getFirst(), turn);
				for (int[] to : large.cells.get(anchor)) {
					int hits = 0;
					for (Map.Entry<String, List<int[]>> e : small.cells.entrySet()) {
						Set<Long> there = at.get(e.getKey());
						if (there == null) {
							continue;
						}
						for (int[] c : e.getValue()) {
							int[] t = turned(c, turn);
							hits += there.contains(Clusters.key(t[0] - from[0] + to[0], t[1] - from[1] + to[1], t[2] - from[2] + to[2])) ? 1 : 0;
						}
					}
					best = Math.max(best, hits);
				}
			}
			return (double) best / large.size;
		}

		private static int[] turned(int[] c, int turn) {
			int x = (turn & 4) != 0 ? -c[0] : c[0];
			int z = c[2];
			for (int i = 0; i < (turn & 3); i++) {
				int t = x;
				x = -z;
				z = t;
			}
			return new int[] {x, c[1], z};
		}
	}

	/** The echo's evidence by block, air left out. */
	private static Map<String, Integer> placed(Echo e) {
		Map<String, Integer> counts = new HashMap<>();
		for (int i = 0; i < e.marks().length; i++) {
			String state = e.palette()[e.states()[i]];
			if (e.marks()[i] == EchoFile.EVIDENCE && Vocabulary.kind(state) != Vocabulary.Kind.AIR) {
				counts.merge(base(state), 1, Integer::sum);
			}
		}
		return counts;
	}

	private static double likeness(Map<String, Integer> a, Map<String, Integer> b) {
		double dot = 0;
		double na = 0;
		double nb = 0;
		for (Map.Entry<String, Integer> e : a.entrySet()) {
			dot += (double) e.getValue() * b.getOrDefault(e.getKey(), 0);
			na += (double) e.getValue() * e.getValue();
		}
		for (int v : b.values()) {
			nb += (double) v * v;
		}
		return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
	}

	public static boolean onSurface(Echo echo) {
		return covered(echo) <= MAX_COVERED;
	}

	/**
	 * The share of an echo's worked columns where, looking down from the top, natural ground comes
	 * before the player's work. Plants, trees and water are looked through.
	 */
	public static double covered(Echo e) {
		int worked = 0;
		int covered = 0;
		for (int z = 0; z < e.sizeZ(); z++) {
			for (int x = 0; x < e.sizeX(); x++) {
				for (int y = e.sizeY() - 1; y >= 0; y--) {
					if (e.mark(x, y, z) == EchoFile.EVIDENCE) {
						worked++;
						break;
					}
					if (Vocabulary.kind(e.state(x, y, z)) == Vocabulary.Kind.GROUND) {
						boolean below = false;
						for (int b = y - 1; b >= 0 && !below; b--) {
							below = e.mark(x, b, z) == EchoFile.EVIDENCE;
						}
						worked += below ? 1 : 0;
						covered += below ? 1 : 0;
						break;
					}
				}
			}
		}
		return worked == 0 ? 0 : (double) covered / worked;
	}

	/** Every echo in a diff, underground ones too, highest score first. */
	public static List<Echo> cut(List<ChunkDiff> diff, Path savedRegions) throws IOException {
		return cut(diff, Evidence.of(diff), savedRegions).echoes();
	}

	/** The echoes, and how many clusters were too wide to place and so not cut. */
	public record Cut(List<Echo> echoes, int tooWide) {
	}

	public static Cut cut(List<ChunkDiff> diff, Evidence evidence, Path savedRegions) throws IOException {
		// A cluster too wide on its own is dropped before merging, so it takes no neighbour with it.
		List<Box> clusters = accepted(diff, evidence).stream().map(Box::of).toList();
		List<Box> placeable = clusters.stream().filter(Box::placeable).toList();
		List<Box> merged = merge(placeable);
		List<Box> boxes = merged.stream().filter(Box::placeable).toList();
		Map<Long, ChunkDiff> diffByChunk = new HashMap<>();
		diff.forEach(c -> diffByChunk.put(chunkKey(c.x(), c.z()), c));
		Map<String, Path> regionFiles = regionFiles(savedRegions);

		List<Cutting> cuttings = boxes.stream().map(b -> new Cutting(b, evidence)).toList();
		Map<String, List<Cutting>> byRegion = new LinkedHashMap<>();
		for (Cutting c : cuttings) {
			for (int cx = c.box.minX >> 4; cx <= c.box.maxX >> 4; cx++) {
				for (int cz = c.box.minZ >> 4; cz <= c.box.maxZ >> 4; cz++) {
					List<Cutting> in = byRegion.computeIfAbsent((cx >> 5) + "." + (cz >> 5), r -> new ArrayList<>());
					if (!in.contains(c)) {
						in.add(c);
					}
				}
			}
		}
		for (Map.Entry<String, List<Cutting>> region : byRegion.entrySet()) {
			Path file = regionFiles.get(region.getKey());
			if (file == null) {
				continue;
			}
			for (Chunk chunk : RegionFile.read(file).chunks()) {
				for (Cutting c : region.getValue()) {
					c.copy(chunk);
				}
			}
		}
		for (Cutting c : cuttings) {
			for (int cx = c.box.minX >> 4; cx <= c.box.maxX >> 4; cx++) {
				for (int cz = c.box.minZ >> 4; cz <= c.box.maxZ >> 4; cz++) {
					ChunkDiff d = diffByChunk.get(chunkKey(cx, cz));
					if (d != null) {
						d.blocks().forEach(c::mark);
					}
				}
			}
		}
		return new Cut(cuttings.stream().map(Cutting::echo).sorted((a, b) -> Double.compare(b.score(), a.score())).toList(),
				clusters.size() - placeable.size() + merged.size() - boxes.size());
	}

	/** A cluster's span with the margin around it. Clusters whose boxes touch become one. */
	private record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, double score) {
		static Box of(Cluster c) {
			return new Box(c.minX() - MARGIN, c.minY() - MARGIN, c.minZ() - MARGIN, c.maxX() + MARGIN, c.maxY() + MARGIN, c.maxZ() + MARGIN, c.score());
		}

		boolean placeable() {
			return maxX - minX < MAX_SPAN && maxZ - minZ < MAX_SPAN;
		}

		boolean touches(Box o) {
			return minX <= o.maxX && o.minX <= maxX && minY <= o.maxY && o.minY <= maxY && minZ <= o.maxZ && o.minZ <= maxZ;
		}

		Box with(Box o) {
			return new Box(Math.min(minX, o.minX), Math.min(minY, o.minY), Math.min(minZ, o.minZ),
					Math.max(maxX, o.maxX), Math.max(maxY, o.maxY), Math.max(maxZ, o.maxZ), score + o.score);
		}

		boolean contains(int x, int y, int z) {
			return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
		}
	}

	private static List<Box> merge(List<Box> boxes) {
		List<Box> out = new ArrayList<>(boxes);
		boolean merged = true;
		while (merged) {
			merged = false;
			outer:
			for (int i = 0; i < out.size(); i++) {
				for (int j = i + 1; j < out.size(); j++) {
					if (out.get(i).touches(out.get(j))) {
						out.set(i, out.get(i).with(out.remove(j)));
						merged = true;
						break outer;
					}
				}
			}
		}
		return out;
	}

	/** One echo being filled in: saved states first, then marks from the diff. */
	private static final class Cutting {
		final Box box;
		final int sizeX;
		final int sizeY;
		final int sizeZ;
		final Map<String, Integer> palette = new LinkedHashMap<>();
		final int[] states;
		final byte[] marks;
		final List<Map<String, Object>> blockEntities = new ArrayList<>();
		final Evidence evidence;
		int dataVersion = EchoFile.NO_DATA_VERSION;

		Cutting(Box box, Evidence evidence) {
			this.box = box;
			this.evidence = evidence;
			sizeX = box.maxX - box.minX + 1;
			sizeY = box.maxY - box.minY + 1;
			sizeZ = box.maxZ - box.minZ + 1;
			states = new int[sizeX * sizeY * sizeZ];
			marks = new byte[states.length];
			// Positions no saved chunk covers stay air.
			palette.put(Chunk.AIR, 0);
		}

		int index(int x, int y, int z) {
			return ((y - box.minY) * sizeZ + (z - box.minZ)) * sizeX + (x - box.minX);
		}

		void copy(Chunk chunk) {
			int x0 = Math.max(box.minX, chunk.x * 16);
			int x1 = Math.min(box.maxX, chunk.x * 16 + 15);
			int z0 = Math.max(box.minZ, chunk.z * 16);
			int z1 = Math.min(box.maxZ, chunk.z * 16 + 15);
			if (x0 > x1 || z0 > z1) {
				return;
			}
			if (chunk.dataVersion != null) {
				dataVersion = Math.max(dataVersion, chunk.dataVersion);
			}
			for (Map.Entry<Integer, Chunk.Section> e : chunk.sections().entrySet()) {
				int y0 = Math.max(box.minY, e.getKey() * 16);
				int y1 = Math.min(box.maxY, e.getKey() * 16 + 15);
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						for (int x = x0; x <= x1; x++) {
							String state = e.getValue().block((y & 15) << 8 | (z & 15) << 4 | (x & 15));
							states[index(x, y, z)] = palette.computeIfAbsent(state, s -> palette.size());
						}
					}
				}
			}
			for (Map<String, Object> entity : chunk.blockEntities()) {
				int x = Nbt.number(entity, "x").orElse(0L).intValue();
				int y = Nbt.number(entity, "y").orElse(0L).intValue();
				int z = Nbt.number(entity, "z").orElse(0L).intValue();
				if (box.contains(x, y, z)) {
					Map<String, Object> moved = new LinkedHashMap<>(entity);
					moved.put("x", x - box.minX);
					moved.put("y", y - box.minY);
					moved.put("z", z - box.minZ);
					blockEntities.add(moved);
				}
			}
		}

		void mark(Block b) {
			if (box.contains(b.x(), b.y(), b.z())) {
				marks[index(b.x(), b.y(), b.z())] = evidence.weight(b.reference(), b.saved()) > 0 ? EchoFile.EVIDENCE : EchoFile.CHANGED;
			}
		}

		Echo echo() {
			return new Echo(box.minX, box.minY, box.minZ, sizeX, sizeY, sizeZ, box.score, dataVersion, palette.keySet().toArray(String[]::new), states, marks,
					blockEntities);
		}
	}

	private static Map<String, Long> topEvidence(Echo e, int limit) {
		Map<String, Long> counts = new HashMap<>();
		for (int i = 0; i < e.states().length; i++) {
			if (e.marks()[i] == EchoFile.EVIDENCE) {
				counts.merge(e.palette()[e.states()[i]], 1L, Long::sum);
			}
		}
		Map<String, Long> top = new LinkedHashMap<>();
		counts.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(limit)
				.forEach(t -> top.put(t.getKey(), t.getValue()));
		return top;
	}

	private static String summary(String version, List<Echo> echoes) {
		StringBuilder json = new StringBuilder("{\n \"version\": \"").append(version.replace("\"", "\\\"")).append("\",\n \"echoes\": [");
		for (int i = 0; i < echoes.size(); i++) {
			Echo e = echoes.get(i);
			json.append(i == 0 ? "\n" : ",\n").append(String.format(
					"  {\"x\": %d, \"y\": %d, \"z\": %d, \"size\": [%d, %d, %d], \"score\": %.1f, \"dataVersion\": %d, \"evidence\": %d, \"changed\": %d, \"blockEntities\": %d, \"top\": {%s}}",
					e.x(), e.y(), e.z(), e.sizeX(), e.sizeY(), e.sizeZ(), e.score(), e.dataVersion(), e.count(EchoFile.EVIDENCE), e.count(EchoFile.CHANGED),
					e.blockEntities().size(),
					topEvidence(e, 5).entrySet().stream().map(t -> "\"" + t.getKey().replace("\"", "\\\"") + "\": " + t.getValue())
							.collect(Collectors.joining(", "))));
		}
		return json.append(echoes.isEmpty() ? "]\n}\n" : "\n ]\n}\n").toString();
	}

	private static long chunkKey(int x, int z) {
		return (long) x << 32 | (z & 0xFFFFFFFFL);
	}

	/** The region files by "rx.rz", Anvil's over the McRegion ones a converted world keeps beside them. */
	private static Map<String, Path> regionFiles(Path folder) throws IOException {
		Map<String, Path> files = new HashMap<>();
		if (!Files.isDirectory(folder)) {
			return files;
		}
		try (Stream<Path> list = Files.list(folder)) {
			for (Path f : list.filter(RegionFile::isRegion).sorted().toList()) {
				String name = f.getFileName().toString();
				files.merge(name.substring(2, name.lastIndexOf('.')), f, (a, b) -> a.toString().endsWith(".mca") ? a : b);
			}
		}
		return files;
	}

	private static void usage(String problem) {
		if (problem != null) {
			System.err.println(problem);
			System.err.println();
		}
		System.err.println("usage: past-echos extract [--diff DIR] WORLD");
		System.err.println();
		System.err.println("  WORLD   the world the diff was made from: a folder, or a zip holding one");
		System.err.println("  --diff  the folder past-echos diff wrote (default WORLD-diff)");
		System.err.println();
		System.err.println("Writes echoes.gz (each echo's blocks, for the mod) and echoes.json (a summary) into the diff folder.");
		if (problem != null) {
			System.exit(2);
		}
	}
}
