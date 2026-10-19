package justfatlard.past_echos.preprocess.inventory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Gathers the facts a world leaves on disk, one file at a time and in whatever order an archive
 * stores them. Nothing is decided here about which files belong to which world; {@link Classifier}
 * does that once everything has been seen.
 */
public final class Collector implements EntrySink {
	static final int CLASSIC_MAGIC = 0x271BB788;
	// Pre-Classic and the earliest Classic saved the bare 256x256x64 block array, gzipped.
	static final int RD_RAW_SIZE = 256 * 256 * 64;
	private static final short MCSHARP_MAGIC = 1874;
	private static final int REGION_HEADER = 4096;
	private static final int MAX_LEVEL_FILE = 64 << 20;
	private static final int MAX_INFLATED = 320 << 20;
	private static final String WORLD_GEN_SETTINGS = "/data/minecraft/world_gen_settings.dat";

	private static final Pattern REGION_FILE = Pattern.compile("r\\.-?\\d+\\.-?\\d+\\.(mca|mcr)");
	private static final Pattern ALPHA_CHUNK = Pattern.compile("c\\.-?[0-9a-z]+\\.-?[0-9a-z]+\\.dat");
	private static final Pattern ALPHA_FOLDER = Pattern.compile("[0-9a-z]{1,2}");
	private static final Pattern DIM_FOLDER = Pattern.compile("DIM-?\\d+");

	final Map<String, Map<String, Object>> javaLevels = new LinkedHashMap<>();
	final Map<String, Long> javaLevelTimes = new LinkedHashMap<>();
	final Map<String, Map<String, Object>> worldGenSettings = new LinkedHashMap<>();
	final Map<String, Map<String, Object>> bedrockLevels = new LinkedHashMap<>();
	final Map<String, String> bedrockNames = new LinkedHashMap<>();
	final Map<String, RegionDir> regionDirs = new LinkedHashMap<>();
	final Map<String, AlphaRoot> alphaRoots = new LinkedHashMap<>();
	final Map<String, Set<String>> markers = new LinkedHashMap<>();
	final List<FiniteLevel> finiteLevels = new ArrayList<>();
	final List<String[]> skipped = new ArrayList<>();
	final List<String> warnings = new ArrayList<>();
	long filesSeen;
	long bytesSeen;

	static final class RegionDir {
		int anvilFiles;
		int mcregionFiles;
		long anvilChunks;
		long mcregionChunks;
		long bytes;
		long newest;
	}

	static final class AlphaRoot {
		long chunkFiles;
		long bytes;
		long newest;
	}

	/** A Classic- or Indev-era level: finite, seedless, and its own world. */
	record FiniteLevel(String path, String format, String name, String author, Long created, String size, long bytes, long mtime, String hash) {
	}

	@Override
	public void file(String path, long size, long mtime, Opener content) throws IOException {
		filesSeen++;
		bytesSeen += Math.max(size, 0);
		String name = basename(path);
		String parent = dirname(path);
		String lower = name.toLowerCase(Locale.ROOT);
		mark(path);

		if (REGION_FILE.matcher(name).matches() && basename(parent).equals("region")) {
			region(parent, name.endsWith(".mca"), size, mtime, content);
		} else if (ALPHA_CHUNK.matcher(name).matches() && ALPHA_FOLDER.matcher(basename(parent)).matches()
				&& ALPHA_FOLDER.matcher(basename(dirname(parent))).matches()) {
			AlphaRoot root = alphaRoots.computeIfAbsent(dirname(dirname(parent)), k -> new AlphaRoot());
			root.chunkFiles++;
			root.bytes += Math.max(size, 0);
			root.newest = Math.max(root.newest, mtime);
		} else if (name.equals("level.dat")) {
			levelDat(path, parent, mtime, read(content, path));
		} else if (path.endsWith(WORLD_GEN_SETTINGS)) {
			byte[] bytes = read(content, path);
			if (bytes != null) {
				try {
					worldGenSettings.put(path.substring(0, path.length() - WORLD_GEN_SETTINGS.length()), Nbt.read(Bytes.gunzip(bytes, MAX_LEVEL_FILE), ByteOrder.BIG_ENDIAN));
				} catch (IOException e) {
					warnings.add(path + ": unreadable world gen settings: " + e.getMessage());
				}
			}
		} else if (name.equals("levelname.txt")) {
			byte[] bytes = read(content, path);
			if (bytes != null) {
				bedrockNames.put(parent, new String(bytes, StandardCharsets.UTF_8).strip());
			}
		} else if (lower.endsWith(".mclevel")) {
			indev(path, mtime, read(content, path));
		} else if (lower.endsWith(".lvl")) {
			mcsharp(path, mtime, read(content, path));
		} else if (lower.endsWith(".cw")) {
			classicWorld(path, mtime, read(content, path));
		} else if (lower.endsWith(".dat") || lower.endsWith(".mine") || !name.contains(".")) {
			sniffClassic(path, mtime, content);
		}
	}

	/**
	 * Classic levels carry any name a player gave them, gzipped or not, so they are known by their
	 * first bytes. Four are read; more only when those look like a level.
	 */
	private void sniffClassic(String path, long mtime, Opener content) throws IOException {
		try (InputStream in = content.open()) {
			byte[] head = Bytes.readUpTo(in, 4);
			if (head.length < 4) {
				return;
			}
			boolean plain = Bytes.bigEndianInt(head, 0) == CLASSIC_MAGIC;
			if (!plain && !Bytes.isGzip(head)) {
				return;
			}
			byte[] more = Bytes.readUpTo(in, plain ? MAX_LEVEL_FILE : 1 << 16);
			byte[] start = concat(head, more);
			if (!plain && !inflatesToClassic(start)) {
				return;
			}
			byte[] rest = plain ? new byte[0] : Bytes.readUpTo(in, MAX_LEVEL_FILE);
			byte[] raw = concat(start, rest);
			if (raw.length > MAX_LEVEL_FILE) {
				warnings.add(path + ": larger than " + (MAX_LEVEL_FILE >> 20) + " MiB, not read");
				return;
			}
			if (plain) {
				classicInflated(path, mtime, raw, raw);
			} else {
				classicInflated(path, mtime, raw, Bytes.gunzip(raw, MAX_INFLATED));
			}
		}
	}

	private static boolean inflatesToClassic(byte[] gzipStart) {
		try (InputStream in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gzipStart))) {
			byte[] inflated = Bytes.readUpTo(in, 4);
			return inflated.length == 4 && Bytes.bigEndianInt(inflated, 0) == CLASSIC_MAGIC;
		} catch (IOException e) {
			return false;
		}
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	@Override
	public void skipped(String path, String reason) {
		skipped.add(new String[]{path, reason});
	}

	private void region(String dir, boolean anvil, long size, long mtime, Opener content) throws IOException {
		RegionDir stats = regionDirs.computeIfAbsent(dir, k -> new RegionDir());
		long chunks = 0;
		try (InputStream in = content.open()) {
			byte[] header = Bytes.readUpTo(in, REGION_HEADER);
			// The location table: one int per chunk slot, zero where no chunk was ever saved.
			for (int at = 0; at + 4 <= header.length; at += 4) {
				if (Bytes.bigEndianInt(header, at) != 0) {
					chunks++;
				}
			}
		}
		if (anvil) {
			stats.anvilFiles++;
			stats.anvilChunks += chunks;
		} else {
			stats.mcregionFiles++;
			stats.mcregionChunks += chunks;
		}
		stats.bytes += Math.max(size, 0);
		stats.newest = Math.max(stats.newest, mtime);
	}

	private void levelDat(String path, String dir, long mtime, byte[] bytes) {
		if (bytes == null) {
			return;
		}
		try {
			if (Bytes.isGzip(bytes)) {
				byte[] inflated = Bytes.gunzip(bytes, MAX_INFLATED);
				if (inflated.length > 0 && inflated[0] == 10) {
					javaLevels.put(dir, Nbt.read(inflated, ByteOrder.BIG_ENDIAN));
					javaLevelTimes.put(dir, mtime);
				} else {
					classicInflated(path, mtime, bytes, inflated);
				}
			} else if (bytes.length >= 8 && Bytes.littleEndianInt(bytes, 4) == bytes.length - 8) {
				// Bedrock: an 8-byte header (format version, payload length), then little-endian NBT.
				// Early Pocket Edition used the same header over a payload that isn't NBT.
				Map<String, Object> level;
				try {
					level = Nbt.read(java.util.Arrays.copyOfRange(bytes, 8, bytes.length), ByteOrder.LITTLE_ENDIAN);
				} catch (IOException notNbt) {
					level = Map.of();
				}
				bedrockLevels.put(dir, level);
			} else {
				warnings.add(path + ": level.dat in no format this knows");
			}
		} catch (IOException e) {
			warnings.add(path + ": unreadable level.dat: " + e.getMessage());
		}
	}

	private void classicInflated(String path, long mtime, byte[] raw, byte[] inflated) {
		if (inflated.length >= 5 && Bytes.bigEndianInt(inflated, 0) == CLASSIC_MAGIC) {
			int version = inflated[4];
			String format = "classic v" + version + (raw == inflated ? ", uncompressed" : "");
			if (version != 2) {
				finiteLevels.add(new FiniteLevel(path, format, null, null, null, null, raw.length, mtime, hash(raw)));
				return;
			}
			ClassicLevel level = ClassicLevel.read(inflated);
			String size = level.width() == null ? null : level.width() + "x" + level.depth() + "x" + level.height();
			Number created = level.primitives.get("createTime");
			finiteLevels.add(new FiniteLevel(path, format, level.strings.get("name"), level.strings.get("creator"),
					created != null && created.longValue() > 0 ? created.longValue() : null, size, raw.length, mtime, hash(raw)));
		} else if (inflated.length == RD_RAW_SIZE) {
			finiteLevels.add(new FiniteLevel(path, "pre-classic raw", null, null, null, "256x64x256", raw.length, mtime, hash(raw)));
		} else {
			warnings.add(path + ": gzipped, but neither NBT nor a Classic level");
		}
	}

	private void indev(String path, long mtime, byte[] bytes) {
		if (bytes == null) {
			return;
		}
		try {
			Map<String, Object> root = Nbt.read(Bytes.gunzip(bytes, MAX_INFLATED), ByteOrder.BIG_ENDIAN);
			String size = Nbt.number(root, "Map", "Width").map(w -> w + "x"
					+ Nbt.number(root, "Map", "Height").orElse(0L) + "x"
					+ Nbt.number(root, "Map", "Length").orElse(0L)).orElse(null);
			finiteLevels.add(new FiniteLevel(path, "indev",
					Nbt.string(root, "About", "Name").orElse(null),
					Nbt.string(root, "About", "Author").orElse(null),
					Nbt.number(root, "About", "CreatedOn").orElse(null),
					size, bytes.length, mtime, hash(bytes)));
		} catch (IOException e) {
			warnings.add(path + ": unreadable Indev level: " + e.getMessage());
		}
	}

	private void mcsharp(String path, long mtime, byte[] bytes) {
		if (bytes == null) {
			return;
		}
		try {
			byte[] inflated = Bytes.gunzip(bytes, MAX_INFLATED);
			if (inflated.length >= 2 && (short) ((inflated[1] & 0xFF) << 8 | inflated[0] & 0xFF) == MCSHARP_MAGIC) {
				finiteLevels.add(new FiniteLevel(path, "classic server (MCSharp .lvl)", null, null, null, null, bytes.length, mtime, hash(bytes)));
			}
		} catch (IOException e) {
			// .lvl is a common extension; a file that isn't one of these is not worth a warning.
		}
	}

	private void classicWorld(String path, long mtime, byte[] bytes) {
		if (bytes == null) {
			return;
		}
		try {
			Map<String, Object> root = Nbt.read(Bytes.gunzip(bytes, MAX_INFLATED), ByteOrder.BIG_ENDIAN);
			String size = Nbt.number(root, "X").map(x -> x + "x" + Nbt.number(root, "Y").orElse(0L) + "x" + Nbt.number(root, "Z").orElse(0L)).orElse(null);
			finiteLevels.add(new FiniteLevel(path, "classic server (ClassicWorld .cw)",
					Nbt.string(root, "Name").orElse(null),
					Nbt.string(root, "CreatedBy", "Username").orElse(null),
					null, size, bytes.length, mtime, hash(bytes)));
		} catch (IOException e) {
			warnings.add(path + ": unreadable ClassicWorld level: " + e.getMessage());
		}
	}

	/**
	 * Notes the folders that say something about a world without being read: datapacks, extra
	 * dimensions, Bedrock's LevelDB. Recorded against the folder that holds them.
	 */
	private void mark(String path) {
		String[] parts = path.split("/");
		StringBuilder prefix = new StringBuilder();
		for (int i = 0; i < parts.length - 1; i++) {
			String part = parts[i];
			String here = archiveAsFolder(prefix.toString());
			if (part.equals("datapacks") && i + 1 < parts.length - 1) {
				// A zipped pack shows up as "pack.zip!", since the walker reads into it like any archive.
				markers(here).add("datapack:" + parts[i + 1].replaceFirst("(?i)\\.zip!?$", ""));
			} else if (part.equals("dimensions") && i + 2 < parts.length - 1) {
				markers(here).add("dimension:" + parts[i + 1] + ":" + parts[i + 2]);
			} else if (DIM_FOLDER.matcher(part).matches()) {
				markers(here).add("dimension:" + part);
			} else if (part.equals("db") && parts[parts.length - 1].endsWith(".ldb")) {
				markers(here).add("leveldb");
			}
			if (i > 0) {
				prefix.append('/');
			}
			prefix.append(part);
		}
	}

	private Set<String> markers(String dir) {
		return markers.computeIfAbsent(dir, k -> new TreeSet<>());
	}

	private byte[] read(Opener content, String path) throws IOException {
		try (InputStream in = content.open()) {
			byte[] bytes = Bytes.readUpTo(in, MAX_LEVEL_FILE + 1);
			if (bytes.length > MAX_LEVEL_FILE) {
				warnings.add(path + ": larger than " + (MAX_LEVEL_FILE >> 20) + " MiB, not read");
				return null;
			}
			return bytes;
		}
	}

	private static String hash(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	static String basename(String path) {
		return path.substring(path.lastIndexOf('/') + 1);
	}

	/** The folder holding {@code path}; for an archive's top-level entries, the archive itself. */
	static String dirname(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? "" : archiveAsFolder(path.substring(0, slash));
	}

	private static String archiveAsFolder(String dir) {
		return dir.endsWith("!") ? dir.substring(0, dir.length() - 1) : dir;
	}
}
