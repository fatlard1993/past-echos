package justfatlard.past_echos.preprocess.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One saved chunk, its blocks read lazily as named states so chunks of the same version compare
 * directly. Numeric ids (to 1.12) read as "id:meta", palettes (from 1.13) as
 * "name[key=value,...]" without the minecraft: prefix.
 */
public final class Chunk {
	public static final String AIR = "air";
	/** Statuses meaning decoration has run: 1.14 on use full, 1.13 had finalized, fullchunk and postprocessed. */
	private static final Set<String> FINISHED = Set.of("full", "fullchunk", "postprocessed", "finalized");
	/** Spanning bit packing ended with 20w17a, so 1.16 and later pack whole values into each long. */
	private static final int PACKED_FROM = 2529;
	private static final int MCREGION_HEIGHT = 128;

	public final int x;
	public final int z;
	public final Integer dataVersion;
	private final Map<String, Object> root;
	private Map<Integer, Section> sections;

	/** 16x16x16 blocks, as indices into the palette in YZX order (index = y << 8 | z << 4 | x). */
	public record Section(int y, String[] palette, short[] blocks) {
		public String block(int index) {
			return palette[blocks[index]];
		}
	}

	private Chunk(int x, int z, Map<String, Object> root) {
		this.x = x;
		this.z = z;
		this.root = root;
		this.dataVersion = Nbt.number(root, "DataVersion").map(Long::intValue).orElse(null);
	}

	static Chunk of(int x, int z, Map<String, Object> root) {
		return new Chunk(x, z, root);
	}

	/** Sections live under Level until 1.18, at the root after. */
	private Map<String, Object> level() {
		return Nbt.compound(root, "Level").orElse(root);
	}

	/**
	 * Whether the generator's decoration has run here: TerrainPopulated in numeric-id worlds, a
	 * finished status in palette worlds.
	 */
	public boolean decorated() {
		Map<String, Object> level = level();
		Object status = level.get("Status");
		if (status instanceof String s) {
			return FINISHED.contains(s.replace("minecraft:", ""));
		}
		return Nbt.number(level, "TerrainPopulated").orElse(0L) != 0;
	}

	/** Whether the chunk records a generation status (1.13 on), rather than only TerrainPopulated. */
	public boolean hasStatus() {
		return level().get("Status") instanceof String;
	}

	/** The non-empty sections by section y. Air-only sections are left out. */
	public Map<Integer, Section> sections() {
		if (sections == null) {
			sections = readSections();
		}
		return sections;
	}

	private Map<Integer, Section> readSections() {
		Map<String, Object> level = level();
		Map<Integer, Section> out = new TreeMap<>();
		if (level.get("Blocks") instanceof byte[] blocks) {
			// McRegion and Alpha: one 128-high column in XZY order.
			readColumn(blocks, (byte[]) level.get("Data"), out);
			return out;
		}
		Object list = level.containsKey("Sections") ? level.get("Sections") : level.get("sections");
		if (!(list instanceof List<?> sectionList)) {
			return out;
		}
		boolean spanning = dataVersion != null && dataVersion < PACKED_FROM;
		for (Object o : sectionList) {
			if (!(o instanceof Map<?, ?> raw)) {
				continue;
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> s = (Map<String, Object>) raw;
			int y = Nbt.number(s, "Y").orElse(0L).intValue();
			Section section = s.get("Blocks") instanceof byte[] ids
					? numeric(y, ids, (byte[]) s.get("Add"), (byte[]) s.get("Data"))
					: paletted(y, s, spanning);
			if (section != null && !(section.palette.length == 1 && section.palette[0].equals(AIR))) {
				out.put(y, section);
			}
		}
		return out;
	}

	private static Section numeric(int y, byte[] ids, byte[] add, byte[] data) {
		Map<Integer, Short> index = new HashMap<>();
		List<String> palette = new ArrayList<>();
		short[] blocks = new short[4096];
		for (int i = 0; i < 4096; i++) {
			int id = (ids[i] & 0xFF) | (add == null ? 0 : nibble(add, i) << 8);
			int meta = data == null ? 0 : nibble(data, i);
			// Air is air whatever its data nibble.
			int key = id == 0 ? 0 : id << 4 | meta;
			blocks[i] = index.computeIfAbsent(key, k -> {
				palette.add(k == 0 ? AIR : id + ":" + meta);
				return (short) (palette.size() - 1);
			});
		}
		return new Section(y, palette.toArray(String[]::new), blocks);
	}

	private static void readColumn(byte[] ids, byte[] data, Map<Integer, Section> out) {
		for (int sy = 0; sy < MCREGION_HEIGHT / 16; sy++) {
			byte[] sectionIds = new byte[4096];
			byte[] sectionData = new byte[2048];
			boolean any = false;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						int from = x << 11 | z << 7 | (sy * 16 + y);
						int to = y << 8 | z << 4 | x;
						sectionIds[to] = ids[from];
						any |= ids[from] != 0;
						int meta = data == null ? 0 : nibble(data, from);
						sectionData[to >> 1] |= (byte) (meta << ((to & 1) * 4));
					}
				}
			}
			if (any) {
				out.put(sy, numeric(sy, sectionIds, null, sectionData));
			}
		}
	}

	private static int nibble(byte[] array, int index) {
		return (array[index >> 1] >> ((index & 1) * 4)) & 0xF;
	}

	private static Section paletted(int y, Map<String, Object> s, boolean spanning) {
		Object palette;
		Object data;
		if (s.get("block_states") instanceof Map<?, ?> states) {
			palette = states.get("palette");
			data = states.get("data");
		} else {
			palette = s.get("Palette");
			data = s.get("BlockStates");
		}
		if (!(palette instanceof List<?> entries) || entries.isEmpty()) {
			return null;
		}
		String[] names = entries.stream().map(Chunk::state).toArray(String[]::new);
		short[] blocks = new short[4096];
		if (data instanceof long[] longs && names.length > 1) {
			int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(names.length - 1));
			unpack(longs, bits, spanning, blocks);
		}
		return new Section(y, names, blocks);
	}

	private static void unpack(long[] longs, int bits, boolean spanning, short[] out) {
		long mask = (1L << bits) - 1;
		if (spanning) {
			for (int i = 0; i < out.length; i++) {
				long bit = (long) i * bits;
				int word = (int) (bit >>> 6);
				int shift = (int) (bit & 63);
				long value = longs[word] >>> shift;
				if (shift + bits > 64) {
					value |= longs[word + 1] << (64 - shift);
				}
				out[i] = (short) (value & mask);
			}
		} else {
			int perLong = 64 / bits;
			for (int i = 0; i < out.length; i++) {
				out[i] = (short) ((longs[i / perLong] >>> ((i % perLong) * bits)) & mask);
			}
		}
	}

	/**
	 * A palette entry as one state string. Entries are {Name, Properties} until 26.x; 26.x writes
	 * {id, properties}, or a bare string when there are no properties, and a list mixing the two
	 * wraps every element as {"": element}.
	 */
	static String state(Object entry) {
		if (entry instanceof Map<?, ?> m && m.size() == 1 && m.containsKey("")) {
			entry = m.get("");
		}
		if (entry instanceof String s) {
			return canonical(s.replace("minecraft:", ""));
		}
		if (!(entry instanceof Map<?, ?> m)) {
			return "?";
		}
		Object name = m.containsKey("Name") ? m.get("Name") : m.get("id");
		Object props = m.containsKey("Properties") ? m.get("Properties") : m.get("properties");
		StringBuilder state = new StringBuilder(String.valueOf(name).replace("minecraft:", ""));
		if (props instanceof Map<?, ?> p && !p.isEmpty()) {
			state.append('[');
			new TreeMap<>(p).forEach((k, v) -> state.append(k).append('=').append(v).append(','));
			state.setCharAt(state.length() - 1, ']');
		}
		return canonical(state.toString());
	}

	/**
	 * Folds states that carry no build information: cave and void air are air, and leaves'
	 * distance is recomputed by the game on every neighbour update.
	 */
	private static String canonical(String state) {
		if (state.equals("cave_air") || state.equals("void_air")) {
			return AIR;
		}
		int bracket = state.indexOf('[');
		if (bracket > 0 && state.substring(0, bracket).endsWith("leaves") && state.contains("distance=")) {
			String props = state.substring(bracket + 1, state.length() - 1)
					.replaceAll("(^|,)distance=\\d+", "")
					.replaceFirst("^,", "");
			return props.isEmpty() ? state.substring(0, bracket) : state.substring(0, bracket) + "[" + props + "]";
		}
		return state;
	}
}
