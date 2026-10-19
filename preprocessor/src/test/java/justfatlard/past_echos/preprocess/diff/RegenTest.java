package justfatlard.past_echos.preprocess.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import justfatlard.past_echos.preprocess.world.Chunk;
import justfatlard.past_echos.preprocess.world.Dimension;
import justfatlard.past_echos.preprocess.world.RegionFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The whole of stage one against the real server jars: each built test world regenerated from
 * its seed and diffed. Regeneration noise comes on top, so the build must be inside the diff
 * rather than equal to it. A built block can be missing only where the regeneration already has
 * it, such as air inside the hut where the original grew a tree and the regeneration did not.
 * Downloads jars and runs servers: ./gradlew regenTest.
 */
@Tag("regen")
class RegenTest {
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"1.2.5", "1.6.4", "1.8.9", "1.12.2", "1.16.5", "1.18.2", "1.20.4", "26.3"})
	void regeneratedDiffHoldsTheBuild(String version, @TempDir Path tmp) throws IOException {
		try (FileSystem built = FileSystems.newFileSystem(DiffTest.resource("worlds/" + version + "-built.zip"))) {
			Diff.Outcome o = Diff.run(Diff.findWorld(built.getPath("/")), version, tmp, "2G", message -> {
			});
			assertEquals(0, o.stats().unsettledReference(), "every settled saved chunk regenerated");
			assertEquals(List.of(), o.reference().unsettled());

			// The reference's block can differ from the original's, so match on position and built block.
			Set<String> diff = DiffTest.lines(DiffFile.read(o.diffFile())).stream().map(RegenTest::placed).collect(Collectors.toSet());
			Set<String> missing = new TreeSet<>();
			for (String line : DiffTest.manifest(built)) {
				if (!diff.contains(placed(line))) {
					missing.add(line);
				}
			}
			Path regions = Dimension.overworldRegions(o.reference().world());
			for (String line : missing) {
				String[] parts = line.split("[, ]");
				int x = Integer.parseInt(parts[0]);
				int y = Integer.parseInt(parts[1]);
				int z = Integer.parseInt(parts[2]);
				String after = line.substring(line.indexOf(" -> ") + 4);
				assertEquals(after, referenceBlock(regions, x, y, z), "built block absent from the diff: " + line);
			}
			assertTrue(o.stats().compared() > 1, o.stats().toString());
		}
	}

	/** "x,y,z before -> after" as "x,y,z after". */
	private static String placed(String line) {
		return line.substring(0, line.indexOf(' ')) + " " + line.substring(line.indexOf(" -> ") + 4);
	}

	private static String referenceBlock(Path regions, int x, int y, int z) throws IOException {
		Path file = regions.resolve("r." + (x >> 9) + "." + (z >> 9) + ".mca");
		Chunk chunk = RegionFile.read(file).chunks().stream()
				.filter(c -> c.x == x >> 4 && c.z == z >> 4).findFirst().orElseThrow();
		Chunk.Section section = chunk.sections().get(y >> 4);
		return section == null ? Chunk.AIR : section.block((y & 15) << 8 | (z & 15) << 4 | (x & 15));
	}
}
