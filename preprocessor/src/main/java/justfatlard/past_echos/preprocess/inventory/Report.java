package justfatlard.past_echos.preprocess.inventory;

import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import justfatlard.past_echos.preprocess.inventory.WorldRecord.Era;
import justfatlard.past_echos.preprocess.inventory.WorldRecord.Pipeline;

/**
 * Groups the copies of each world and writes the inventory, as a table for reading or as JSON
 * for the preprocessor to pick up.
 */
public final class Report {
	private static final int LIST_LIMIT = 20;

	private Report() {
	}

	/**
	 * Copies of one world: same seed and name, or byte-identical for the seedless eras. Each copy
	 * stays its own record, since a backup taken before a build was torn down still holds the build.
	 */
	public record Group(String key, List<WorldRecord> copies) {
		WorldRecord oldest() {
			return copies.getFirst();
		}

		WorldRecord newest() {
			return copies.getLast();
		}

		long maxChunks() {
			return copies.stream().mapToLong(WorldRecord::totalChunks).max().orElse(0);
		}
	}

	public static List<Group> group(List<WorldRecord> records) {
		Map<String, List<WorldRecord>> byKey = new LinkedHashMap<>();
		for (WorldRecord r : records) {
			byKey.computeIfAbsent(key(r), k -> new ArrayList<>()).add(r);
		}
		Comparator<WorldRecord> chronological = Comparator.comparing(WorldRecord::era)
				.thenComparing(r -> r.dataVersion() != null ? r.dataVersion() : 0)
				.thenComparing(r -> r.lastPlayed() != null ? r.lastPlayed() : 0L)
				.thenComparing(WorldRecord::path);
		List<Group> groups = new ArrayList<>();
		for (var entry : byKey.entrySet()) {
			List<WorldRecord> copies = new ArrayList<>(entry.getValue());
			copies.sort(chronological);
			groups.add(new Group(entry.getKey(), List.copyOf(copies)));
		}
		groups.sort(Comparator.comparing(Group::oldest, chronological.thenComparing(r -> r.name().toLowerCase(Locale.ROOT))));
		return groups;
	}

	private static String key(WorldRecord r) {
		if (r.contentHash() != null) {
			return "content:" + r.contentHash();
		}
		if (r.seed() != null) {
			return "seed:" + r.seed() + ":" + r.name().toLowerCase(Locale.ROOT);
		}
		return "path:" + r.path();
	}

	public static void text(PrintStream out, Collector facts, List<Group> groups, List<String> scanned, boolean showCopies) {
		long copies = groups.stream().mapToLong(g -> g.copies().size()).sum();
		out.printf("past-echos inventory: %s scanned, %,d files, %s%n", String.join(", ", scanned), facts.filesSeen, size(facts.bytesSeen));
		out.printf("%,d world%s in %,d cop%s%n%n", groups.size(), groups.size() == 1 ? "" : "s", copies, copies == 1 ? "y" : "ies");
		if (groups.isEmpty()) {
			out.println("No worlds found.");
		} else {
			summary(out, groups);
			table(out, groups, showCopies);
		}
		listed(out, "Skipped", facts.skipped.stream().map(s -> s[0] + ": " + s[1]).toList());
		listed(out, "Warnings", facts.warnings);
	}

	private static void summary(PrintStream out, List<Group> groups) {
		Map<Era, Integer> eras = new EnumMap<>(Era.class);
		Map<Pipeline, Integer> pipelines = new EnumMap<>(Pipeline.class);
		for (Group g : groups) {
			eras.merge(g.oldest().era(), 1, Integer::sum);
			pipelines.merge(g.oldest().pipeline(), 1, Integer::sum);
		}
		out.println("Worlds by era, from the oldest copy of each");
		eras.forEach((era, n) -> out.printf("  %-32s %5d%n", era.label, n));
		out.println();
		out.println("Worlds by how their terrain regenerates, from the oldest copy");
		pipelines.forEach((pipeline, n) -> out.printf("  %-32s %5d%n", pipeline.label, n));
		out.println();
	}

	private static void table(PrintStream out, List<Group> groups, boolean showCopies) {
		String row = "%-24s %-30s %-21s %6s %9s %-11s %s%n";
		out.printf(row, "World", "Era / version", "Seed", "Copies", "Chunks", "Last played", "Regenerates via");
		for (Group g : groups) {
			WorldRecord oldest = g.oldest();
			WorldRecord newest = g.newest();
			String era = versionLabel(oldest);
			if (!versionLabel(newest).equals(era)) {
				era += " → " + versionLabel(newest);
			}
			out.printf(row, clip(oldest.name(), 24), clip(era, 30), oldest.seed() != null ? oldest.seed() : "-",
					g.copies().size(), String.format("%,d", g.maxChunks()), date(newest.lastPlayed()), oldest.pipeline().label);
			Set<String> notes = new LinkedHashSet<>();
			g.copies().forEach(c -> notes.addAll(c.notes()));
			for (String note : notes) {
				out.println("    " + note);
			}
			if (showCopies || g.copies().size() == 1) {
				for (WorldRecord c : g.copies()) {
					out.printf("    @ %s  (%s, %,d chunks, %s)%n", c.path(), versionLabel(c), c.totalChunks(), date(c.lastPlayed()));
				}
			} else {
				out.printf("    @ %s  (+%d older cop%s, --copies lists them)%n", newest.path(), g.copies().size() - 1, g.copies().size() == 2 ? "y" : "ies");
			}
		}
	}

	private static String versionLabel(WorldRecord r) {
		if (r.era() == Era.FINITE) {
			return r.format();
		}
		return r.version() != null ? r.version() : r.era().label;
	}

	private static void listed(PrintStream out, String title, List<String> lines) {
		if (lines.isEmpty()) {
			return;
		}
		out.printf("%n%s (%d)%n", title, lines.size());
		lines.stream().limit(LIST_LIMIT).forEach(line -> out.println("  " + line));
		if (lines.size() > LIST_LIMIT) {
			out.printf("  ... and %d more; --json has them all%n", lines.size() - LIST_LIMIT);
		}
	}

	public static String json(Collector facts, List<Group> groups, List<String> scanned) {
		Json j = new Json();
		j.open('{');
		j.key("scanned").value(scanned);
		j.key("files").value(facts.filesSeen);
		j.key("bytes").value(facts.bytesSeen);
		j.key("worlds").open('[');
		for (Group g : groups) {
			j.open('{');
			j.key("key").value(g.key());
			j.key("name").value(g.oldest().name());
			j.key("copies").open('[');
			for (WorldRecord r : g.copies()) {
				j.open('{');
				j.key("path").value(r.path());
				j.key("name").value(r.name());
				j.key("era").value(r.era().name());
				j.key("format").value(r.format());
				j.key("version").value(r.version());
				j.key("dataVersion").value(r.dataVersion());
				j.key("versionHistory").value(r.versionHistory());
				// Seeds are full 64-bit values; as strings they survive JSON readers that parse doubles.
				j.key("seed").value(r.seed() != null ? String.valueOf(r.seed()) : null);
				j.key("generator").value(r.generator());
				j.key("lastPlayed").value(r.lastPlayed());
				j.key("chunks").open('{');
				r.chunks().forEach((dim, n) -> j.key(dim).value(n));
				j.close('}');
				j.key("bytes").value(r.bytes());
				j.key("modded").value(r.modded());
				j.key("pipeline").value(r.pipeline().name());
				j.key("notes").value(r.notes());
				j.key("contentHash").value(r.contentHash());
				j.close('}');
			}
			j.close(']');
			j.close('}');
		}
		j.close(']');
		j.key("skipped").open('[');
		for (String[] s : facts.skipped) {
			j.open('{');
			j.key("path").value(s[0]);
			j.key("reason").value(s[1]);
			j.close('}');
		}
		j.close(']');
		j.key("warnings").value(facts.warnings);
		j.close('}');
		return j.toString();
	}

	private static String date(Long millis) {
		if (millis == null || millis <= 0) {
			return "-";
		}
		return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString();
	}

	private static String clip(String s, int width) {
		return s.length() <= width ? s : s.substring(0, width - 1) + "~";
	}

	static String size(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		String[] units = {"KiB", "MiB", "GiB", "TiB"};
		double v = bytes;
		int u = -1;
		while (v >= 1024 && u < units.length - 1) {
			v /= 1024;
			u++;
		}
		return String.format("%.1f %s", v, units[u]);
	}

	/** Just enough JSON writing for the inventory, so the preprocessor carries no dependencies. */
	private static final class Json {
		private final StringBuilder out = new StringBuilder();
		private final List<Boolean> first = new ArrayList<>();
		private boolean afterKey;

		Json open(char bracket) {
			separate();
			out.append(bracket);
			first.add(true);
			return this;
		}

		Json close(char bracket) {
			first.removeLast();
			out.append(bracket);
			return this;
		}

		Json key(String key) {
			separate();
			string(key);
			out.append(':');
			afterKey = true;
			return this;
		}

		Json value(Object value) {
			separate();
			switch (value) {
				case null -> out.append("null");
				case String s -> string(s);
				case Number n -> out.append(n);
				case Boolean b -> out.append(b);
				case List<?> list -> {
					out.append('[');
					for (int i = 0; i < list.size(); i++) {
						if (i > 0) {
							out.append(',');
						}
						Object item = list.get(i);
						if (item instanceof Number n) {
							out.append(n);
						} else {
							string(String.valueOf(item));
						}
					}
					out.append(']');
				}
				default -> string(value.toString());
			}
			return this;
		}

		private void separate() {
			if (afterKey) {
				afterKey = false;
				return;
			}
			if (!first.isEmpty()) {
				if (!first.getLast()) {
					out.append(',');
				}
				first.set(first.size() - 1, false);
			}
		}

		private void string(String s) {
			out.append('"');
			for (int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);
				switch (c) {
					case '"' -> out.append("\\\"");
					case '\\' -> out.append("\\\\");
					case '\n' -> out.append("\\n");
					case '\r' -> out.append("\\r");
					case '\t' -> out.append("\\t");
					default -> {
						if (c < 0x20) {
							out.append(String.format("\\u%04x", (int) c));
						} else {
							out.append(c);
						}
					}
				}
			}
			out.append('"');
		}

		@Override
		public String toString() {
			return out.toString();
		}
	}
}
