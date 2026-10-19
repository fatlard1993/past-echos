package justfatlard.past_echos.preprocess.inventory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import justfatlard.past_echos.preprocess.inventory.WorldRecord.Era;
import justfatlard.past_echos.preprocess.inventory.WorldRecord.Pipeline;

/**
 * Turns collected facts into world records: which chunk folders belong to which level.dat, what
 * era each world is from, and how its untouched terrain can be regenerated.
 */
public final class Classifier {
	/** A region folder's place under its world: overworld, a DIM folder, or a namespaced dimension. */
	private static final Pattern REGION_UNDER_ROOT = Pattern.compile("region|(DIM-?\\d+)/region|dimensions/([^/]+)/(.+)/region");
	private static final Pattern ALPHA_UNDER_ROOT = Pattern.compile("|(DIM-?\\d+)");
	private static final Set<String> MOD_LOADER_BRANDS = Set.of("fabric", "forge", "neoforge", "quilt", "fml", "liteloader", "rift");
	private static final Set<String> MOD_LOADER_KEYS = Set.of("FML", "fml", "Forge", "forge", "fabric");

	private Classifier() {
	}

	public static List<WorldRecord> classify(Collector facts) {
		Map<String, List<Claim>> claims = new LinkedHashMap<>();
		Set<String> claimedRegions = new HashSet<>();
		Set<String> claimedAlpha = new HashSet<>();

		for (String dir : facts.regionDirs.keySet()) {
			owner(dir, facts.javaLevels.keySet(), REGION_UNDER_ROOT).ifPresent(claim -> {
				claims.computeIfAbsent(claim.root, k -> new ArrayList<>()).add(claim.withSource(dir));
				claimedRegions.add(dir);
			});
		}
		for (String dir : facts.alphaRoots.keySet()) {
			owner(dir, facts.javaLevels.keySet(), ALPHA_UNDER_ROOT).ifPresent(claim -> {
				claims.computeIfAbsent(claim.root, k -> new ArrayList<>()).add(claim.withSource(dir).alpha());
				claimedAlpha.add(dir);
			});
		}

		List<WorldRecord> records = new ArrayList<>();
		for (var level : facts.javaLevels.entrySet()) {
			records.add(java(level.getKey(), level.getValue(), claims.getOrDefault(level.getKey(), List.of()), facts));
		}
		records.addAll(orphans(facts, claimedRegions, claimedAlpha));
		records.addAll(bedrock(facts));
		for (Collector.FiniteLevel level : facts.finiteLevels) {
			records.add(finite(level));
		}
		return records;
	}

	private record Claim(String root, String dimension, String source, boolean isAlpha) {
		Claim withSource(String dir) {
			return new Claim(root, dimension, dir, isAlpha);
		}

		Claim alpha() {
			return new Claim(root, dimension, source, true);
		}
	}

	/**
	 * The nearest world folder holding {@code chunkDir} in one of the places a world keeps chunks.
	 * Nearest, so a world backed up inside another world's folder keeps its own chunks; and only
	 * those places, so a folder of loose backups under a world isn't swallowed by it.
	 */
	private static Optional<Claim> owner(String chunkDir, Set<String> roots, Pattern underRoot) {
		for (String at = chunkDir; !at.isEmpty(); at = Collector.dirname(at)) {
			if (roots.contains(at)) {
				String relative = at.equals(chunkDir) ? "" : chunkDir.substring(at.length() + 1);
				Matcher m = underRoot.matcher(relative);
				if (m.matches()) {
					return Optional.of(new Claim(at, dimension(m), null, false));
				}
			}
		}
		return Optional.empty();
	}

	private static String dimension(Matcher m) {
		if (m.groupCount() == 3 && m.group(2) != null) {
			String namespace = m.group(2);
			String path = m.group(3);
			return namespace.equals("minecraft") ? path : namespace + ":" + path;
		}
		String dim = m.group(1);
		if (dim == null) {
			return "overworld";
		}
		return switch (dim) {
			case "DIM-1" -> "the_nether";
			case "DIM1" -> "the_end";
			default -> dim;
		};
	}

	private static WorldRecord java(String root, Map<String, Object> level, List<Claim> claims, Collector facts) {
		Map<String, Object> data = Nbt.compound(level, "Data").orElse(level);
		Map<String, Object> genSettings = facts.worldGenSettings.containsKey(root)
				? Nbt.compound(facts.worldGenSettings.get(root), "data").orElse(facts.worldGenSettings.get(root))
				: Nbt.compound(data, "WorldGenSettings").orElse(null);

		List<String> notes = new ArrayList<>();
		Map<String, Long> chunks = new TreeMap<>();
		long bytes = 0;
		boolean anvil = false;
		boolean mcregion = false;
		boolean alpha = false;
		for (Claim claim : claims) {
			if (claim.isAlpha) {
				Collector.AlphaRoot stats = facts.alphaRoots.get(claim.source);
				chunks.merge(claim.dimension, stats.chunkFiles, Long::sum);
				bytes += stats.bytes;
				alpha = true;
			} else {
				Collector.RegionDir stats = facts.regionDirs.get(claim.source);
				// After the 1.2 conversion a world keeps its old .mcr files next to the new .mca:
				// the same chunks twice, so only the Anvil copy is counted.
				chunks.merge(claim.dimension, stats.anvilFiles > 0 ? stats.anvilChunks : stats.mcregionChunks, Long::sum);
				bytes += stats.bytes;
				anvil |= stats.anvilFiles > 0;
				mcregion |= stats.mcregionFiles > 0;
			}
		}

		String format;
		if (anvil) {
			format = "anvil";
			if (mcregion) {
				notes.add("converted from McRegion: its oldest chunks predate 1.2");
			}
			if (alpha) {
				notes.add("chunk folders from the Alpha format left behind");
			}
		} else if (mcregion) {
			format = "mcregion";
			if (alpha) {
				notes.add("converted from the Alpha format: its oldest chunks predate Beta 1.3");
			}
		} else if (alpha) {
			format = "alpha";
		} else {
			format = "none";
		}

		String version = Nbt.string(data, "Version", "Name").orElse(null);
		Integer dataVersion = Nbt.number(data, "DataVersion").map(Long::intValue).orElse(null);
		List<Integer> history = intList(data.get("version_history"));
		Era era = switch (format) {
			case "alpha" -> Era.ALPHA_FORMAT;
			case "mcregion" -> Era.MCREGION;
			case "anvil" -> dataVersion != null ? Era.ofDataVersion(dataVersion) : Era.ANVIL_UNTAGGED;
			// No chunks to go by: the level.dat's own format number is the next best witness.
			default -> dataVersion != null ? Era.ofDataVersion(dataVersion)
					: switch (Nbt.number(data, "version").orElse(0L).intValue()) {
						case 19132 -> Era.MCREGION;
						case 19133 -> Era.ANVIL_UNTAGGED;
						default -> Era.UNKNOWN;
					};
		};
		if (version == null) {
			version = switch (format) {
				case "alpha" -> "Infdev-Beta 1.2 (no version tag)";
				case "mcregion" -> "Beta 1.3-1.1 (no version tag)";
				case "anvil" -> "1.2-1.8 (no version tag)";
				default -> null;
			};
		}

		Long seed = Nbt.number(data, "RandomSeed")
				.or(() -> genSettings == null ? Optional.empty() : Nbt.number(genSettings, "seed"))
				.orElse(null);
		String generator = generator(data, genSettings);
		if (generator != null && !isDefaultGenerator(generator)) {
			notes.add("generator: " + generator);
		}

		boolean modded = false;
		if (Nbt.number(data, "WasModded").orElse(0L) == 1) {
			modded = true;
		}
		for (String key : MOD_LOADER_KEYS) {
			if (level.containsKey(key) || data.containsKey(key)) {
				modded = true;
				notes.add("mod loader data: " + key);
			}
		}
		for (Object brand : list(data.get("ServerBrands"))) {
			String b = String.valueOf(brand).toLowerCase(Locale.ROOT);
			if (MOD_LOADER_BRANDS.contains(b)) {
				modded = true;
			}
			if (!b.equals("vanilla")) {
				notes.add("brand: " + brand);
			}
		}
		for (String dimension : chunks.keySet()) {
			if (!Set.of("overworld", "the_nether", "the_end").contains(dimension)) {
				modded = true;
				notes.add("extra dimension: " + dimension);
			}
		}
		for (String marker : facts.markers.getOrDefault(root, Set.of())) {
			if (marker.startsWith("datapack:")) {
				notes.add(marker.replace(":", ": "));
			}
		}
		Nbt.compound(data, "DataPacks").ifPresent(packs -> {
			for (Object pack : list(packs.get("Enabled"))) {
				String p = String.valueOf(pack);
				if (p.startsWith("mod:") || p.startsWith("fabric")) {
					notes.add("mod datapacks enabled");
					break;
				}
			}
		});
		if (modded) {
			notes.add(0, "modded: modded terrain won't regenerate without the same mods");
		}

		Pipeline pipeline;
		if (format.equals("none")) {
			pipeline = Pipeline.EMPTY;
		} else if (seed == null) {
			pipeline = Pipeline.NEEDS_SEED;
			notes.add("seed not found in level.dat or world_gen_settings.dat");
		} else if (era == Era.ALPHA_FORMAT || era == Era.MCREGION) {
			pipeline = Pipeline.CLIENT_JAR;
		} else {
			pipeline = Pipeline.SERVER_JAR;
			if (era == Era.ANVIL_UNTAGGED) {
				notes.add("if last played in 1.2.1-1.2.4, those have no server jar");
			}
		}

		return new WorldRecord(root, Nbt.string(data, "LevelName").orElse(Collector.basename(root)), era, format, version, dataVersion, history,
				seed, generator, Nbt.number(data, "LastPlayed").orElse(facts.javaLevelTimes.get(root)), chunks, bytes, modded, pipeline, notes, null);
	}

	private static String generator(Map<String, Object> data, Map<String, Object> genSettings) {
		Optional<String> legacy = Nbt.string(data, "generatorName");
		if (legacy.isPresent()) {
			return legacy.get();
		}
		if (genSettings == null) {
			return null;
		}
		Optional<Map<String, Object>> overworld = Nbt.compound(genSettings, "dimensions", "minecraft:overworld", "generator");
		if (overworld.isEmpty()) {
			return null;
		}
		String type = Nbt.string(overworld.get(), "type").orElse("?");
		Optional<String> settings = Nbt.string(overworld.get(), "settings");
		Optional<String> preset = Nbt.string(overworld.get(), "biome_source", "preset");
		if (type.equals("minecraft:noise")) {
			return settings.orElse("custom noise settings") + preset.filter(p -> !p.equals("minecraft:overworld")).map(p -> ", biomes " + p).orElse("");
		}
		return type;
	}

	private static boolean isDefaultGenerator(String generator) {
		return generator.equals("default") || generator.equals("minecraft:overworld");
	}

	private static List<WorldRecord> orphans(Collector facts, Set<String> claimedRegions, Set<String> claimedAlpha) {
		Map<String, Map<String, Long>> byRoot = new TreeMap<>();
		Map<String, long[]> sizes = new TreeMap<>();
		Map<String, String> formats = new TreeMap<>();
		for (var entry : facts.regionDirs.entrySet()) {
			if (claimedRegions.contains(entry.getKey())) {
				continue;
			}
			String dir = entry.getKey();
			String root = Collector.dirname(dir);
			String dimension = "overworld";
			String parentName = Collector.basename(root);
			if (parentName.matches("DIM-?\\d+")) {
				dimension = parentName;
				root = Collector.dirname(root);
			}
			Collector.RegionDir stats = entry.getValue();
			byRoot.computeIfAbsent(root, k -> new TreeMap<>()).merge(dimension, stats.anvilFiles > 0 ? stats.anvilChunks : stats.mcregionChunks, Long::sum);
			sizes.computeIfAbsent(root, k -> new long[2])[0] += stats.bytes;
			sizes.get(root)[1] = Math.max(sizes.get(root)[1], stats.newest);
			formats.merge(root, stats.anvilFiles > 0 ? "anvil" : "mcregion", (a, b) -> a.equals("anvil") || b.equals("anvil") ? "anvil" : a);
		}
		for (var entry : facts.alphaRoots.entrySet()) {
			if (claimedAlpha.contains(entry.getKey())) {
				continue;
			}
			Collector.AlphaRoot stats = entry.getValue();
			byRoot.computeIfAbsent(entry.getKey(), k -> new TreeMap<>()).merge("overworld", stats.chunkFiles, Long::sum);
			sizes.computeIfAbsent(entry.getKey(), k -> new long[2])[0] += stats.bytes;
			sizes.get(entry.getKey())[1] = Math.max(sizes.get(entry.getKey())[1], stats.newest);
			formats.putIfAbsent(entry.getKey(), "alpha");
		}

		List<WorldRecord> records = new ArrayList<>();
		for (var entry : byRoot.entrySet()) {
			String root = entry.getKey();
			String format = formats.get(root);
			Era era = switch (format) {
				case "alpha" -> Era.ALPHA_FORMAT;
				case "mcregion" -> Era.MCREGION;
				default -> Era.ANVIL_UNTAGGED;
			};
			records.add(new WorldRecord(root, Collector.basename(root), era, format, null, null, List.of(), null, null,
					sizes.get(root)[1], entry.getValue(), sizes.get(root)[0], false, Pipeline.NEEDS_SEED,
					List.of("chunks without a level.dat: the seed has to come from somewhere else"), null));
		}
		return records;
	}

	private static List<WorldRecord> bedrock(Collector facts) {
		Set<String> dirs = new java.util.TreeSet<>(facts.bedrockLevels.keySet());
		dirs.addAll(facts.bedrockNames.keySet());
		facts.markers.forEach((dir, marks) -> {
			if (marks.contains("leveldb")) {
				dirs.add(dir);
			}
		});
		List<WorldRecord> records = new ArrayList<>();
		for (String dir : dirs) {
			Map<String, Object> level = facts.bedrockLevels.getOrDefault(dir, Map.of());
			boolean leveldb = facts.markers.getOrDefault(dir, Set.of()).contains("leveldb");
			String name = facts.bedrockNames.getOrDefault(dir, Nbt.string(level, "LevelName").orElse(Collector.basename(dir)));
			List<Integer> opened = intList(level.get("lastOpenedWithVersion"));
			String version = opened.isEmpty() ? null : String.join(".", opened.stream().map(String::valueOf).toList());
			// Bedrock keeps LastPlayed in seconds.
			Long lastPlayed = Nbt.number(level, "LastPlayed").map(s -> s * 1000).orElse(null);
			records.add(new WorldRecord(dir, name, Era.BEDROCK, leveldb ? "bedrock leveldb" : "pocket legacy", version, null, List.of(),
					Nbt.number(level, "RandomSeed").orElse(null), null, lastPlayed, Map.of(), 0, false, Pipeline.OUT_OF_SCOPE,
					List.of("Bedrock terrain generation is a different engine from Java's"), null));
		}
		return records;
	}

	private static WorldRecord finite(Collector.FiniteLevel level) {
		List<String> notes = new ArrayList<>();
		if (level.size() != null) {
			notes.add("size " + level.size());
		}
		if (level.author() != null) {
			notes.add("by " + level.author());
		}
		return new WorldRecord(level.path(), level.name() != null ? level.name() : Collector.basename(level.path()), Era.FINITE, level.format(),
				null, null, List.of(), null, null, level.created() != null ? level.created() : level.mtime(), Map.of(), level.bytes(), false,
				Pipeline.HEURISTIC, notes, level.hash());
	}

	private static List<?> list(Object value) {
		return value instanceof List<?> list ? list : List.of();
	}

	private static List<Integer> intList(Object value) {
		if (value instanceof int[] ints) {
			return java.util.Arrays.stream(ints).boxed().toList();
		}
		List<Integer> out = new ArrayList<>();
		for (Object o : list(value)) {
			if (o instanceof Number n) {
				out.add(n.intValue());
			}
		}
		return out;
	}
}
