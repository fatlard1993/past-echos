package justfatlard.past_echos.preprocess.regen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import justfatlard.past_echos.preprocess.world.Dimension;
import justfatlard.past_echos.preprocess.world.Nbt;

/**
 * Regenerates a saved world's chunks as untouched terrain, with the official server jar of the
 * version that generated them, into a reference world beside the saved one.
 *
 * The reference starts from the saved world's own level.dat (and 26.x's world_gen_settings.dat,
 * and any datapacks), so seed, generator and its options carry over exactly. From 1.13.1 the
 * server is asked for each chunk with /forceload. Before that there is no such command, so the
 * spawn is moved over the chunks still needed and the server restarted: it generates its spawn
 * area, 25 chunks across, before it reports ready. Chunks already settled in an existing
 * reference are not asked for again, so an interrupted run resumes.
 */
public final class Regenerator {
	/** 1.13.1, the first version with /forceload. */
	private static final int FORCELOAD_DATA_VERSION = 1628;
	/** Chunks either side of the spawn chunk that end up settled: the area reaches 12, decoration needs one more. */
	private static final int SPAWN_REACH = 10;
	private static final int BATCH = 256;
	private static final Duration POLL = Duration.ofSeconds(2);
	private static final Duration BATCH_TIMEOUT = Duration.ofMinutes(15);
	private static final Duration SAVE_TIMEOUT = Duration.ofMinutes(5);

	public record Result(Path world, Dimension dimension, List<int[]> unsettled, int serverRuns) {
	}

	private final ServerJars jars;
	private final String maxHeap;
	private final Consumer<String> progress;

	public Regenerator(ServerJars jars, String maxHeap, Consumer<String> progress) {
		this.jars = jars;
		this.maxHeap = maxHeap;
		this.progress = progress;
	}

	/**
	 * @param saved   the saved world's folder (holding level.dat)
	 * @param version the Minecraft version whose generator made the chunks, as Mojang names it
	 * @param needed  chunk positions {x, z} to regenerate
	 * @param workDir where the server runs; the reference world is workDir/world
	 */
	public Result regenerate(Path saved, String version, List<int[]> needed, Path workDir) throws IOException {
		Path jar = jars.jar(version);
		Files.createDirectories(workDir);
		Path world = workDir.resolve("world");
		if (!Files.exists(world.resolve("level.dat"))) {
			seedFrom(saved, world);
		}
		List<int[]> remaining = unsettled(world, needed);
		progress.accept(String.format("%s: %,d of %,d chunks to generate", version, remaining.size(), needed.size()));
		int runs = remaining.isEmpty() ? 0
				: usesForceload(version, readLevelDat(world.resolve("level.dat")))
						? forceload(jar, workDir, world, remaining)
						: spawnAreas(jar, workDir, world, remaining);
		Dimension reference = Dimension.read(Dimension.overworldRegions(world));
		return new Result(world, reference, needed.stream().filter(c -> !reference.settled(c[0], c[1])).toList(), runs);
	}

	static boolean usesForceload(String version, Map<String, Object> level) {
		Long dataVersion = Nbt.number(level, "Data", "DataVersion").orElse(null);
		if (dataVersion != null) {
			return dataVersion >= FORCELOAD_DATA_VERSION;
		}
		// Worlds before 1.9 carry no DataVersion, and every one of those predates /forceload.
		String[] parts = version.split("\\.");
		try {
			int major = Integer.parseInt(parts[0]);
			int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
			int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
			return major > 1 || minor > 13 || minor == 13 && patch >= 1;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static void seedFrom(Path saved, Path world) throws IOException {
		Files.createDirectories(world);
		Files.copy(saved.resolve("level.dat"), world.resolve("level.dat"));
		Path settings = saved.resolve("data/minecraft/world_gen_settings.dat");
		if (Files.exists(settings)) {
			Files.createDirectories(world.resolve("data/minecraft"));
			Files.copy(settings, world.resolve("data/minecraft/world_gen_settings.dat"));
		}
		Path datapacks = saved.resolve("datapacks");
		if (Files.isDirectory(datapacks)) {
			try (Stream<Path> walk = Files.walk(datapacks)) {
				for (Path from : (Iterable<Path>) walk::iterator) {
					Path to = world.resolve(saved.relativize(from).toString());
					if (Files.isDirectory(from)) {
						Files.createDirectories(to);
					} else {
						Files.copy(from, to);
					}
				}
			}
		}
	}

	private static List<int[]> unsettled(Path world, List<int[]> needed) throws IOException {
		Dimension reference = Dimension.read(Dimension.overworldRegions(world));
		return needed.stream().filter(c -> !reference.settled(c[0], c[1])).toList();
	}

	private int forceload(Path jar, Path workDir, Path world, List<int[]> remaining) throws IOException {
		List<int[]> ordered = new ArrayList<>(remaining);
		// Region by region, so each batch touches few files and its chunks neighbour each other.
		ordered.sort(Comparator.<int[]>comparingInt(c -> c[0] >> 5).thenComparingInt(c -> c[1] >> 5)
				.thenComparingInt(c -> c[1]).thenComparingInt(c -> c[0]));
		try (Server server = Server.start(jar, workDir, maxHeap)) {
			for (int from = 0; from < ordered.size(); from += BATCH) {
				List<int[]> batch = ordered.subList(from, Math.min(from + BATCH, ordered.size()));
				for (int[] c : batch) {
					server.send("forceload add " + c[0] * 16 + " " + c[1] * 16);
				}
				awaitSettled(server, world, batch);
				server.send("forceload remove all");
				progress.accept(String.format("  %,d of %,d generated", from + batch.size(), ordered.size()));
			}
			server.stop();
		}
		return 1;
	}

	private void awaitSettled(Server server, Path world, List<int[]> batch) throws IOException {
		long deadline = System.nanoTime() + BATCH_TIMEOUT.toNanos();
		while (true) {
			server.send("save-all flush");
			server.await(line -> line.contains("Saved the game"), SAVE_TIMEOUT);
			// Read while the server runs: a chunk caught mid-write reads as missing and is asked about again.
			if (unsettled(world, batch).isEmpty()) {
				return;
			}
			if (System.nanoTime() > deadline) {
				throw new IOException("chunks still generating after " + BATCH_TIMEOUT);
			}
			try {
				Thread.sleep(POLL);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted", e);
			}
		}
	}

	private int spawnAreas(Path jar, Path workDir, Path world, List<int[]> remaining) throws IOException {
		int runs = 0;
		while (!remaining.isEmpty()) {
			// The spawn area's corner on the first chunk still needed, reading west to east, north to south.
			int[] first = remaining.stream().min(Comparator.<int[]>comparingInt(c -> c[0]).thenComparingInt(c -> c[1])).orElseThrow();
			moveSpawn(world.resolve("level.dat"), first[0] + SPAWN_REACH, first[1] + SPAWN_REACH);
			try (Server server = Server.start(jar, workDir, maxHeap)) {
				server.stop();
			}
			runs++;
			List<int[]> left = unsettled(world, remaining);
			progress.accept(String.format("  spawn area %d: %,d chunks left", runs, left.size()));
			if (left.size() == remaining.size()) {
				// Nothing new settled: the server ignored the moved spawn, so stop rather than loop.
				return runs;
			}
			remaining = left;
		}
		return runs;
	}

	private static void moveSpawn(Path levelDat, int chunkX, int chunkZ) throws IOException {
		Map<String, Object> level = readLevelDat(levelDat);
		Map<String, Object> data = Nbt.compound(level, "Data").orElseThrow(() -> new IOException(levelDat + " has no Data"));
		data.put("SpawnX", chunkX * 16 + 8);
		data.put("SpawnZ", chunkZ * 16 + 8);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
			gzip.write(Nbt.write(level));
		}
		Files.write(levelDat, out.toByteArray());
	}

	public static Map<String, Object> readLevelDat(Path levelDat) throws IOException {
		try (InputStream in = new GZIPInputStream(Files.newInputStream(levelDat))) {
			return Nbt.read(in.readAllBytes(), ByteOrder.BIG_ENDIAN);
		}
	}
}
