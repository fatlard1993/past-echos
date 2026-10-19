package justfatlard.past_echos.preprocess.inventory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists every Minecraft world under the given folders and archives: what era it is from, its seed,
 * how many chunks it holds, and how its untouched terrain can be regenerated. Read-only.
 */
public final class Inventory {
	private static final long PROGRESS_INTERVAL_MS = 5000;

	private Inventory() {
	}

	public static void main(String[] args) throws IOException {
		List<Path> paths = new ArrayList<>();
		Path json = null;
		boolean copies = false;
		boolean quiet = false;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--json" -> {
					if (++i >= args.length) {
						usage("--json needs a file to write");
						return;
					}
					json = Path.of(args[i]);
				}
				case "--copies" -> copies = true;
				case "--quiet", "-q" -> quiet = true;
				case "--help", "-h" -> {
					usage(null);
					return;
				}
				default -> {
					if (args[i].startsWith("-")) {
						usage("unknown option " + args[i]);
						return;
					}
					paths.add(Path.of(args[i]));
				}
			}
		}
		if (paths.isEmpty()) {
			usage("give at least one folder or archive to scan");
			return;
		}

		boolean showProgress = !quiet;
		Collector collector = new Collector();
		long[] lastProgress = {System.currentTimeMillis()};
		EntrySink sink = new EntrySink() {
			@Override
			public void file(String path, long size, long mtime, Opener content) throws IOException {
				collector.file(path, size, mtime, content);
				long now = System.currentTimeMillis();
				if (showProgress && now - lastProgress[0] > PROGRESS_INTERVAL_MS) {
					lastProgress[0] = now;
					System.err.printf("  ... %,d files, %s%n", collector.filesSeen, Report.size(collector.bytesSeen));
				}
			}

			@Override
			public void skipped(String path, String reason) {
				collector.skipped(path, reason);
			}
		};
		Walker walker = new Walker(sink, message -> {
			if (showProgress) {
				System.err.println(message);
			}
		});

		List<String> scanned = new ArrayList<>();
		for (Path path : paths) {
			if (!Files.exists(path)) {
				collector.skipped(path.toString(), "does not exist");
				continue;
			}
			scanned.add(path.toString());
			walker.walk(path);
		}

		List<Report.Group> groups = Report.group(Classifier.classify(collector));
		Report.text(System.out, collector, groups, scanned, copies);
		if (json != null) {
			Files.writeString(json, Report.json(collector, groups, scanned), StandardCharsets.UTF_8);
			System.out.println();
			System.out.println("JSON written to " + json.toAbsolutePath());
		}
	}

	private static void usage(String problem) {
		if (problem != null) {
			System.err.println(problem);
			System.err.println();
		}
		System.err.println("usage: past-echos inventory [--json FILE] [--copies] [--quiet] PATH...");
		System.err.println();
		System.err.println("  PATH      folders, zips, tars or tar.gz files; scanned recursively, archives inside archives too");
		System.err.println("  --json    also write the full inventory as JSON, for the preprocessor");
		System.err.println("  --copies  list every copy of each world, not just the newest");
		System.err.println("  --quiet   no progress on stderr");
		if (problem != null) {
			System.exit(2);
		}
	}
}
