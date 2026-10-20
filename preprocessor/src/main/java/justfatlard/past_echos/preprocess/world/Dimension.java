package justfatlard.past_echos.preprocess.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * One dimension's region folder: which chunks it holds and how far each got through generation.
 * Blocks are not kept; read them a region at a time with {@link RegionFile#read}.
 */
public final class Dimension {
	private static final byte DECORATED = 1;
	private static final byte HAS_STATUS = 2;

	public final List<Path> regionFiles;
	public final List<RegionFile.Unreadable> unreadable = new ArrayList<>();
	private final Map<Long, Byte> flags = new HashMap<>();
	private final Map<Long, Long> inhabited = new HashMap<>();

	private Dimension(List<Path> regionFiles) {
		this.regionFiles = regionFiles;
	}

	/** The overworld's region folder: region/ until 26.x, dimensions/minecraft/overworld/region/ after. */
	public static Path overworldRegions(Path world) {
		Path modern = world.resolve("dimensions/minecraft/overworld/region");
		return Files.isDirectory(modern) ? modern : world.resolve("region");
	}

	public static Dimension read(Path regionFolder) throws IOException {
		return read(regionFolder, file -> true);
	}

	/** Only the region files the filter keeps: the chunks a long regeneration is waiting on, say. */
	public static Dimension read(Path regionFolder, Predicate<Path> regions) throws IOException {
		if (!Files.isDirectory(regionFolder)) {
			return new Dimension(List.of());
		}
		List<Path> files;
		try (Stream<Path> list = Files.list(regionFolder)) {
			files = list.filter(RegionFile::isRegion).filter(regions).sorted().toList();
		}
		// A world converted to Anvil keeps its old .mcr files beside the .mca ones; Anvil is current.
		if (files.stream().anyMatch(f -> f.toString().endsWith(".mca"))) {
			files = files.stream().filter(f -> f.toString().endsWith(".mca")).toList();
		}
		Dimension d = new Dimension(files);
		for (Path file : files) {
			RegionFile.Contents contents = RegionFile.read(file);
			for (Chunk c : contents.chunks()) {
				d.flags.put(key(c.x, c.z), (byte) ((c.decorated() ? DECORATED : 0) | (c.hasStatus() ? HAS_STATUS : 0)));
				c.inhabitedTime().ifPresent(t -> d.inhabited.put(key(c.x, c.z), t));
			}
			d.unreadable.addAll(contents.unreadable());
		}
		return d;
	}

	/**
	 * The region files holding these chunks and their west, north and north-west neighbours,
	 * which a numeric-id chunk needs to count as settled.
	 */
	public static Predicate<Path> regionsHolding(Collection<int[]> chunks) {
		Set<String> regions = new HashSet<>();
		for (int[] c : chunks) {
			for (int[] n : new int[][] {{0, 0}, {-1, 0}, {0, -1}, {-1, -1}}) {
				regions.add("r." + (c[0] + n[0] >> 5) + "." + (c[1] + n[1] >> 5) + ".");
			}
		}
		return file -> {
			String name = file.getFileName().toString();
			return regions.contains(name.substring(0, name.lastIndexOf('.') + 1));
		};
	}

	static long key(int x, int z) {
		return (long) x << 32 | (z & 0xFFFFFFFFL);
	}

	public boolean contains(int x, int z) {
		return flags.containsKey(key(x, z));
	}

	public int size() {
		return flags.size();
	}

	/** Every chunk position, as {x, z}. */
	public List<int[]> positions() {
		return flags.keySet().stream().map(k -> new int[] {(int) (k >> 32), (int) (long) k}).toList();
	}

	/**
	 * Whether the chunk's blocks are final as far as generation goes. A chunk with a status is
	 * final once full, its neighbours' decoration having run first. A numeric-id chunk is also
	 * decorated by its west, north and north-west neighbours, whose decoration covers a square
	 * offset 8 blocks into it, so it is final only once all four have run.
	 */
	public boolean settled(int x, int z) {
		Byte f = flags.get(key(x, z));
		if (f == null || (f & DECORATED) == 0) {
			return false;
		}
		if ((f & HAS_STATUS) != 0) {
			return true;
		}
		return decorated(x - 1, z) && decorated(x, z - 1) && decorated(x - 1, z - 1);
	}

	/**
	 * Whether a player spent at least this many ticks near the chunk, so could have built in it.
	 * A chunk saved before 1.6 records no time and counts as visited.
	 */
	public boolean visited(int x, int z, long minTicks) {
		Long t = inhabited.get(key(x, z));
		return t == null || t >= minTicks;
	}

	private boolean decorated(int x, int z) {
		Byte f = flags.get(key(x, z));
		return f != null && (f & DECORATED) != 0;
	}
}
