package justfatlard.past_echos.echo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import justfatlard.past_echos.PastEchos;
import justfatlard.past_echos.preprocess.extract.EchoFile;
import justfatlard.past_echos.preprocess.extract.EchoFile.Echo;
import net.minecraft.util.RandomSource;

/**
 * Every ruin the server can place, read once at start from the echoes.gz files the preprocessor
 * wrote anywhere under the library folder.
 *
 * <p>A ruin's id names its file and its corner in the old world, so a structure already started
 * finds the same ruin after a restart, and one whose echo has since been removed places nothing.
 */
public final class Library {
	public static final String ECHO_FILE = "echoes.gz";
	private static volatile Library current = new Library(List.of());

	private final List<Ruin> ruins;
	private final Map<String, Ruin> byId;

	private Library(List<Ruin> ruins) {
		this.ruins = ruins.stream().sorted(Comparator.comparing(Ruin::id)).toList();
		this.byId = this.ruins.stream().collect(Collectors.toUnmodifiableMap(Ruin::id, Function.identity()));
	}

	public static Library current() {
		return current;
	}

	public static void use(Library library) {
		current = library;
	}

	public static Library load(Path folder) throws IOException {
		List<Ruin> ruins = new ArrayList<>();
		if (!Files.isDirectory(folder)) {
			return new Library(ruins);
		}
		List<Path> files;
		try (Stream<Path> walk = Files.walk(folder)) {
			files = walk.filter(f -> f.getFileName().toString().equals(ECHO_FILE)).sorted().toList();
		}
		for (Path file : files) {
			String source = folder.relativize(file.getParent()).toString().replace('\\', '/');
			EchoFile.Contents contents;
			try {
				contents = EchoFile.read(file);
			} catch (IOException e) {
				PastEchos.LOGGER.warn("Skipping {}: {}", file, e.getMessage());
				continue;
			}
			int placeable = 0;
			int dug = 0;
			for (Echo echo : contents.echoes()) {
				String id = source + "@" + echo.x() + "," + echo.y() + "," + echo.z();
				try {
					Optional<Ruin> ruin = Ruin.of(id, echo);
					ruin.ifPresent(ruins::add);
					placeable += ruin.isPresent() ? 1 : 0;
					dug += ruin.isEmpty() ? 1 : 0;
				} catch (RuntimeException e) {
					PastEchos.LOGGER.warn("Skipping echo {}: {}", id, e.toString());
				}
			}
			PastEchos.LOGGER.info("{} ({}): {} of {} echoes placeable{}", source, contents.version(), placeable, contents.echoes().size(),
					dug > 0 ? ", " + dug + " dug rather than built" : "");
		}
		return new Library(ruins);
	}

	public boolean isEmpty() {
		return ruins.isEmpty();
	}

	public List<Ruin> ruins() {
		return ruins;
	}

	public Ruin pick(RandomSource random) {
		return ruins.get(random.nextInt(ruins.size()));
	}

	public Optional<Ruin> get(String id) {
		return Optional.ofNullable(byId.get(id));
	}
}
