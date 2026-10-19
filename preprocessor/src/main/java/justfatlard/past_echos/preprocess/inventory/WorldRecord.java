package justfatlard.past_echos.preprocess.inventory;

import java.util.List;
import java.util.Map;

/**
 * One copy of one world, as found. The same world backed up five times is five records; grouping
 * them is the report's job, because each copy is its own moment of the world's history.
 */
public record WorldRecord(
		String path,
		String name,
		Era era,
		String format,
		String version,
		Integer dataVersion,
		List<Integer> versionHistory,
		Long seed,
		String generator,
		Long lastPlayed,
		Map<String, Long> chunks,
		long bytes,
		boolean modded,
		Pipeline pipeline,
		List<String> notes,
		String contentHash) {

	public long totalChunks() {
		return chunks.values().stream().mapToLong(Long::longValue).sum();
	}

	/** Where in Minecraft's history a save comes from, oldest first. Sorting by this is chronological. */
	public enum Era {
		FINITE("Pre-Classic / Classic / Indev"),
		ALPHA_FORMAT("Infdev - Beta 1.2"),
		MCREGION("Beta 1.3 - 1.1"),
		ANVIL_UNTAGGED("1.2 - 1.8"),
		PRE_FLATTENING("1.9 - 1.12"),
		PRE_CAVES("1.13 - 1.17"),
		MODERN("1.18+"),
		BEDROCK("Bedrock / Pocket"),
		UNKNOWN("unknown");

		public final String label;

		Era(String label) {
			this.label = label;
		}

		/** Split at the first snapshots of the flattening (17w47a) and of 1.18's new world height (21w37a). */
		static Era ofDataVersion(int dataVersion) {
			if (dataVersion < 1451) {
				return PRE_FLATTENING;
			}
			if (dataVersion < 2834) {
				return PRE_CAVES;
			}
			return MODERN;
		}
	}

	/** How the preprocessor will get a world's untouched terrain to diff against. */
	public enum Pipeline {
		SERVER_JAR("official server jar"),
		CLIENT_JAR("generator from client jar"),
		HEURISTIC("heuristics only (no seed)"),
		NEEDS_SEED("needs seed"),
		EMPTY("no chunks"),
		OUT_OF_SCOPE("out of scope");

		public final String label;

		Pipeline(String label) {
			this.label = label;
		}
	}
}
