package justfatlard.past_echos.preprocess.diff;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import justfatlard.past_echos.preprocess.regen.Regenerator;
import justfatlard.past_echos.preprocess.regen.ServerJars;
import justfatlard.past_echos.preprocess.world.Dimension;
import justfatlard.past_echos.preprocess.world.Nbt;

/**
 * Stage one: regenerates a world's overworld from its seed with the official server jar and
 * writes where the saved blocks differ, for the extraction stage to sort into builds and noise.
 * An untouched earlier copy of the world can stand in for the regeneration.
 */
public final class Diff {
	private static final int LEVEL_SEARCH_DEPTH = 4;
	/** A minute of a player nearby: less is a flyover, too short to have built anything. */
	public static final long MIN_INHABITED = 1200;
	/** The diff over a sample of unvisited chunks, from which extraction learns the generator's own blocks. */
	public static final String UNVISITED_FILE = "unvisited.diff.gz";
	/**
	 * About this many unvisited chunks are sampled. At 3,000 a single village in the sample made
	 * its doors and farmland look generated; at 10,000 villages come in about their usual share.
	 */
	static final int UNVISITED_SAMPLE = 10_000;
	/** Sampled in squares this many chunks across, which regenerate at little more than their own cost. */
	static final int PATCH = 4;

	/** @param unvisitedCompared how many sampled unvisited chunks went into {@link #UNVISITED_FILE} */
	public record Outcome(Path diffFile, WorldDiff.Stats stats, int unvisitedCompared, Regenerator.Result reference) {
	}

	private Diff() {
	}

	public static void main(String[] args) throws IOException {
		String version = null;
		Path out = null;
		String heap = "3G";
		long minInhabited = MIN_INHABITED;
		Path world = null;
		Path referenceWorld = null;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--version" -> version = value(args, ++i, "--version needs a Minecraft version");
				case "--out" -> out = Path.of(value(args, ++i, "--out needs a folder"));
				case "--heap" -> heap = value(args, ++i, "--heap needs a size such as 3G");
				case "--min-inhabited" -> minInhabited = Long.parseLong(value(args, ++i, "--min-inhabited needs a number of ticks"));
				case "--reference" -> referenceWorld = Path.of(value(args, ++i, "--reference needs a world folder or zip"));
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
			usage("give a world folder or a zip holding one");
			return;
		}
		if (out == null) {
			out = Path.of(world.getFileName().toString().replaceFirst("\\.zip$", "") + "-diff");
		}

		FileSystem zip = world.toString().endsWith(".zip") ? FileSystems.newFileSystem(world) : null;
		FileSystem referenceZip = referenceWorld != null && referenceWorld.toString().endsWith(".zip") ? FileSystems.newFileSystem(referenceWorld) : null;
		try {
			Path root = zip == null ? world : findWorld(zip.getPath("/"));
			Path referenceRoot = referenceZip != null ? findWorld(referenceZip.getPath("/")) : referenceWorld;
			if (version == null) {
				version = Nbt.string(Regenerator.readLevelDat(root.resolve("level.dat")), "Data", "Version", "Name").orElse(null);
			}
			if (version == null) {
				usage("this world names no version (worlds before 1.9 don't); give the one it was generated with as --version");
				return;
			}
			Outcome o = referenceRoot == null ? run(root, version, out, heap, minInhabited, System.err::println)
					: against(root, referenceRoot, version, out, minInhabited);
			WorldDiff.Stats s = o.stats;
			System.out.printf("%s: %,d saved chunks, %,d compared, %,d differ (%,d blocks)%n",
					version, s.savedChunks(), s.compared(), s.differing(), s.blocks());
			if (s.unsettledSaved() > 0) {
				System.out.printf("  %,d saved chunks skipped: generation unfinished when saved%n", s.unsettledSaved());
			}
			if (s.excluded() > 0) {
				System.out.printf("  %,d chunks skipped: under %,d ticks of a player nearby%n", s.excluded(), minInhabited);
			}
			if (o.unvisitedCompared() > 0) {
				System.out.printf("  %,d of those sampled to learn the generator's own blocks%n", o.unvisitedCompared());
			}
			if (s.unsettledReference() > 0) {
				System.out.printf("  %,d chunks skipped: %s%n", s.unsettledReference(),
						referenceRoot == null ? "not regenerated" : "missing or unfinished in the reference");
			}
			if (!s.unreadable().isEmpty()) {
				System.out.printf("  %,d chunks unreadable, first: %s%n", s.unreadable().size(), s.unreadable().getFirst());
			}
			System.out.println("  -> " + o.diffFile);
		} finally {
			if (zip != null) {
				zip.close();
			}
			if (referenceZip != null) {
				referenceZip.close();
			}
		}
	}

	/** Regenerates into out/reference and writes out/overworld.diff.gz and out/diff.json. */
	public static Outcome run(Path world, String version, Path out, String heap, long minInhabited, java.util.function.Consumer<String> progress)
			throws IOException {
		Files.createDirectories(out);
		Dimension saved = Dimension.read(Dimension.overworldRegions(world));
		List<int[]> needed = new ArrayList<>();
		for (int[] c : saved.positions()) {
			if (saved.settled(c[0], c[1]) && saved.visited(c[0], c[1], minInhabited)) {
				needed.add(c);
			}
		}
		List<int[]> sample = unvisitedSample(saved, minInhabited);
		needed.addAll(sample);
		Regenerator regenerator = new Regenerator(new ServerJars(ServerJars.defaultCache()), heap, progress);
		Regenerator.Result reference = regenerator.regenerate(world, version, needed, out.resolve("reference"));
		return write(world, saved, reference, version, out, minInhabited, sample);
	}

	/** Diffs against an untouched earlier copy of the world instead of a regeneration. */
	public static Outcome against(Path world, Path referenceWorld, String version, Path out, long minInhabited) throws IOException {
		Files.createDirectories(out);
		Dimension saved = Dimension.read(Dimension.overworldRegions(world));
		Dimension reference = Dimension.read(Dimension.overworldRegions(referenceWorld));
		List<int[]> unsettled = new ArrayList<>();
		for (int[] c : saved.positions()) {
			if (saved.settled(c[0], c[1]) && !reference.settled(c[0], c[1])) {
				unsettled.add(c);
			}
		}
		return write(world, saved, new Regenerator.Result(referenceWorld, reference, unsettled, 0), version, out, minInhabited,
				unvisitedSample(saved, minInhabited));
	}

	/**
	 * Settled chunks under minInhabited ticks, in whole patches picked at random but the same
	 * each run, up to about {@link #UNVISITED_SAMPLE}. Empty when every chunk was visited.
	 */
	static List<int[]> unvisitedSample(Dimension saved, long minInhabited) {
		Map<Long, List<int[]>> patches = new TreeMap<>();
		for (int[] c : saved.positions()) {
			if (saved.settled(c[0], c[1]) && !saved.visited(c[0], c[1], minInhabited)) {
				patches.computeIfAbsent((long) Math.floorDiv(c[0], PATCH) << 32 | (Math.floorDiv(c[1], PATCH) & 0xFFFFFFFFL), p -> new ArrayList<>()).add(c);
			}
		}
		List<List<int[]>> order = new ArrayList<>(patches.values());
		Collections.shuffle(order, new Random(0));
		List<int[]> sample = new ArrayList<>();
		for (List<int[]> patch : order) {
			if (sample.size() >= UNVISITED_SAMPLE) {
				break;
			}
			sample.addAll(patch);
		}
		return sample;
	}

	private static Outcome write(Path world, Dimension saved, Regenerator.Result reference, String version, Path out, long minInhabited,
			List<int[]> sample) throws IOException {
		Path diffFile = out.resolve("overworld.diff.gz");
		Path referenceRegions = Dimension.overworldRegions(reference.world());
		WorldDiff.Stats stats;
		try (DiffFile.Writer writer = new DiffFile.Writer(diffFile)) {
			stats = WorldDiff.diff(saved, reference.dimension(), referenceRegions, (x, z) -> saved.visited(x, z, minInhabited), writer);
		}
		Set<Long> sampled = new HashSet<>();
		sample.forEach(c -> sampled.add((long) c[0] << 32 | (c[1] & 0xFFFFFFFFL)));
		int unvisitedCompared = 0;
		if (!sample.isEmpty()) {
			Dimension sampleRegions = Dimension.read(Dimension.overworldRegions(world), Dimension.regionsHolding(sample));
			try (DiffFile.Writer writer = new DiffFile.Writer(out.resolve(UNVISITED_FILE))) {
				unvisitedCompared = WorldDiff.diff(sampleRegions, reference.dimension(), referenceRegions,
						(x, z) -> sampled.contains((long) x << 32 | (z & 0xFFFFFFFFL)), writer).compared();
			}
		}
		Files.writeString(out.resolve("diff.json"), String.format("""
				{
				 "version": "%s",
				 "savedChunks": %d,
				 "compared": %d,
				 "differing": %d,
				 "blocks": %d,
				 "unsettledSaved": %d,
				 "unsettledReference": %d,
				 "unvisited": %d,
				 "unvisitedCompared": %d,
				 "unreadable": %d,
				 "serverRuns": %d
				}
				""", version.replace("\"", "\\\""), stats.savedChunks(), stats.compared(), stats.differing(), stats.blocks(),
				stats.unsettledSaved(), stats.unsettledReference(), stats.excluded(), unvisitedCompared, stats.unreadable().size(), reference.serverRuns()));
		return new Outcome(diffFile, stats, unvisitedCompared, reference);
	}

	/** The shallowest folder holding a level.dat. */
	public static Path findWorld(Path root) throws IOException {
		try (Stream<Path> walk = Files.walk(root, LEVEL_SEARCH_DEPTH)) {
			return walk.filter(p -> p.getFileName() != null && p.getFileName().toString().equals("level.dat"))
					.min(Comparator.comparingInt(Path::getNameCount))
					.map(Path::getParent)
					.orElseThrow(() -> new IOException("no level.dat in " + root));
		}
	}

	private static String value(String[] args, int i, String missing) {
		if (i >= args.length) {
			usage(missing);
		}
		return args[i];
	}

	private static void usage(String problem) {
		if (problem != null) {
			System.err.println(problem);
			System.err.println();
		}
		System.err.println("usage: past-echos diff [--version V] [--out DIR] [--heap SIZE] [--reference OLDER] [--min-inhabited TICKS] WORLD");
		System.err.println();
		System.err.println("  WORLD        a world folder (the one holding level.dat), or a zip holding one");
		System.err.println("  --version    the version that generated the world, when level.dat doesn't say");
		System.err.println("  --out        where the reference world and the diff go (default WORLD-diff)");
		System.err.println("  --heap       the regenerating server's heap (default 3G)");
		System.err.println("  --reference  an untouched earlier copy of the world to diff against instead of a");
		System.err.println("               regeneration: no noise and any version or mod, but chunks it lacks are skipped");
		System.err.println("  --min-inhabited  skip chunks a player spent fewer ticks near (default " + MIN_INHABITED + ", a minute;");
		System.err.println("               0 keeps all); worlds before 1.6 don't record it and keep every chunk");
		System.err.println();
		System.err.println("Without --reference, downloads Mojang's server jar for the version and runs it on this");
		System.err.println("machine, which accepts the Minecraft EULA (https://aka.ms/MinecraftEULA) for that server.");
		if (problem != null) {
			System.exit(2);
		}
	}
}
