package justfatlard.past_echos.preprocess.diff;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

	public record Outcome(Path diffFile, WorldDiff.Stats stats, Regenerator.Result reference) {
	}

	private Diff() {
	}

	public static void main(String[] args) throws IOException {
		String version = null;
		Path out = null;
		String heap = "3G";
		Path world = null;
		Path referenceWorld = null;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--version" -> version = value(args, ++i, "--version needs a Minecraft version");
				case "--out" -> out = Path.of(value(args, ++i, "--out needs a folder"));
				case "--heap" -> heap = value(args, ++i, "--heap needs a size such as 3G");
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
			Outcome o = referenceRoot == null ? run(root, version, out, heap, System.err::println) : against(root, referenceRoot, version, out);
			WorldDiff.Stats s = o.stats;
			System.out.printf("%s: %,d saved chunks, %,d compared, %,d differ (%,d blocks)%n",
					version, s.savedChunks(), s.compared(), s.differing(), s.blocks());
			if (s.unsettledSaved() > 0) {
				System.out.printf("  %,d saved chunks skipped: generation unfinished when saved%n", s.unsettledSaved());
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
	public static Outcome run(Path world, String version, Path out, String heap, java.util.function.Consumer<String> progress) throws IOException {
		Files.createDirectories(out);
		Dimension saved = Dimension.read(Dimension.overworldRegions(world));
		List<int[]> needed = new ArrayList<>();
		for (int[] c : saved.positions()) {
			if (saved.settled(c[0], c[1])) {
				needed.add(c);
			}
		}
		Regenerator regenerator = new Regenerator(new ServerJars(ServerJars.defaultCache()), heap, progress);
		Regenerator.Result reference = regenerator.regenerate(world, version, needed, out.resolve("reference"));
		return write(saved, reference, version, out);
	}

	/** Diffs against an untouched earlier copy of the world instead of a regeneration. */
	public static Outcome against(Path world, Path referenceWorld, String version, Path out) throws IOException {
		Files.createDirectories(out);
		Dimension saved = Dimension.read(Dimension.overworldRegions(world));
		Dimension reference = Dimension.read(Dimension.overworldRegions(referenceWorld));
		List<int[]> unsettled = new ArrayList<>();
		for (int[] c : saved.positions()) {
			if (saved.settled(c[0], c[1]) && !reference.settled(c[0], c[1])) {
				unsettled.add(c);
			}
		}
		return write(saved, new Regenerator.Result(referenceWorld, reference, unsettled, 0), version, out);
	}

	private static Outcome write(Dimension saved, Regenerator.Result reference, String version, Path out) throws IOException {
		Path diffFile = out.resolve("overworld.diff.gz");
		WorldDiff.Stats stats;
		try (DiffFile.Writer writer = new DiffFile.Writer(diffFile)) {
			stats = WorldDiff.diff(saved, reference.dimension(), Dimension.overworldRegions(reference.world()), writer);
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
				 "unreadable": %d,
				 "serverRuns": %d
				}
				""", version.replace("\"", "\\\""), stats.savedChunks(), stats.compared(), stats.differing(), stats.blocks(),
				stats.unsettledSaved(), stats.unsettledReference(), stats.unreadable().size(), reference.serverRuns()));
		return new Outcome(diffFile, stats, reference);
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
		System.err.println("usage: past-echos diff [--version V] [--out DIR] [--heap SIZE] [--reference OLDER] WORLD");
		System.err.println();
		System.err.println("  WORLD        a world folder (the one holding level.dat), or a zip holding one");
		System.err.println("  --version    the version that generated the world, when level.dat doesn't say");
		System.err.println("  --out        where the reference world and the diff go (default WORLD-diff)");
		System.err.println("  --heap       the regenerating server's heap (default 3G)");
		System.err.println("  --reference  an untouched earlier copy of the world to diff against instead of a");
		System.err.println("               regeneration: no noise and any version or mod, but chunks it lacks are skipped");
		System.err.println();
		System.err.println("Without --reference, downloads Mojang's server jar for the version and runs it on this");
		System.err.println("machine, which accepts the Minecraft EULA (https://aka.ms/MinecraftEULA) for that server.");
		if (problem != null) {
			System.exit(2);
		}
	}
}
