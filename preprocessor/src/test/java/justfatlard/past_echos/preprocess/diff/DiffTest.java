package justfatlard.past_echos.preprocess.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import justfatlard.past_echos.preprocess.diff.DiffFile.Block;
import justfatlard.past_echos.preprocess.diff.DiffFile.ChunkDiff;
import justfatlard.past_echos.preprocess.regen.Json;
import justfatlard.past_echos.preprocess.world.Dimension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Each generated test world against its copy with a build placed in it (fixtures/build.py). Both
 * come from one generation, so the diff must be the build exactly, as build.json lists it.
 */
public class DiffTest {
	public static Path resource(String name) {
		try {
			return Path.of(DiffTest.class.getResource("/" + name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The build as "x,y,z reference -> saved" lines. */
	@SuppressWarnings("unchecked")
	public static Set<String> manifest(FileSystem built) throws IOException {
		Map<String, Object> json = (Map<String, Object>) Json.parse(Files.readString(built.getPath("/build.json")));
		return ((List<Map<String, Object>>) json.get("blocks")).stream()
				.map(b -> b.get("x") + "," + b.get("y") + "," + b.get("z") + " " + b.get("before") + " -> " + b.get("after"))
				.collect(Collectors.toCollection(TreeSet::new));
	}

	static Set<String> lines(List<ChunkDiff> diff) {
		return diff.stream().flatMap(c -> c.blocks().stream())
				.map(b -> b.x() + "," + b.y() + "," + b.z() + " " + b.reference() + " -> " + b.saved())
				.collect(Collectors.toCollection(TreeSet::new));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"1.2.5", "1.6.4", "1.8.9", "1.12.2", "1.16.5", "1.18.2", "1.20.4", "26.3"})
	void diffOfABuildIsTheBuild(String version, @TempDir Path tmp) throws IOException {
		try (FileSystem plain = FileSystems.newFileSystem(resource("worlds/" + version + ".zip"));
				FileSystem built = FileSystems.newFileSystem(resource("worlds/" + version + "-built.zip"))) {
			Path savedRegions = Dimension.overworldRegions(Diff.findWorld(built.getPath("/")));
			Path referenceRegions = Dimension.overworldRegions(Diff.findWorld(plain.getPath("/")));
			Path file = tmp.resolve("d.gz");
			WorldDiff.Stats stats;
			try (DiffFile.Writer writer = new DiffFile.Writer(file)) {
				stats = WorldDiff.diff(Dimension.read(savedRegions), Dimension.read(referenceRegions), referenceRegions, writer);
			}
			Set<String> expected = manifest(built);
			assertEquals(expected, lines(DiffFile.read(file)));
			assertEquals(1, stats.differing());
			assertEquals(expected.size(), stats.blocks());
			assertEquals(List.of(), stats.unreadable());
			assertTrue(stats.compared() > 1, "more than the build's chunk compared: " + stats);
		}
	}

	@Test
	void anEarlierCopyStandsInForTheRegeneration(@TempDir Path tmp) throws IOException {
		try (FileSystem plain = FileSystems.newFileSystem(resource("worlds/1.12.2.zip"));
				FileSystem built = FileSystems.newFileSystem(resource("worlds/1.12.2-built.zip"))) {
			Diff.Outcome o = Diff.against(Diff.findWorld(built.getPath("/")), Diff.findWorld(plain.getPath("/")), "1.12.2", tmp);
			assertEquals(manifest(built), lines(DiffFile.read(o.diffFile())));
			assertEquals(0, o.stats().unsettledReference());
			assertEquals(0, o.reference().serverRuns());
		}
	}

	@Test
	void worldAgainstItselfIsEmpty(@TempDir Path tmp) throws IOException {
		try (FileSystem plain = FileSystems.newFileSystem(resource("worlds/1.20.4.zip"))) {
			Path regions = Dimension.overworldRegions(Diff.findWorld(plain.getPath("/")));
			Dimension d = Dimension.read(regions);
			Path file = tmp.resolve("d.gz");
			try (DiffFile.Writer writer = new DiffFile.Writer(file)) {
				WorldDiff.Stats stats = WorldDiff.diff(d, d, regions, writer);
				assertEquals(25, stats.compared(), "a 1.20.4 chunk with status full stands alone");
			}
			assertEquals(List.of(), DiffFile.read(file));
		}
	}

	@Test
	void numericWorldComparesOnlyChunksWhoseNeighboursDecoratedThem(@TempDir Path tmp) throws IOException {
		try (FileSystem plain = FileSystems.newFileSystem(resource("worlds/1.12.2.zip"))) {
			Path regions = Dimension.overworldRegions(Diff.findWorld(plain.getPath("/")));
			Dimension d = Dimension.read(regions);
			try (DiffFile.Writer writer = new DiffFile.Writer(tmp.resolve("d.gz"))) {
				WorldDiff.Stats stats = WorldDiff.diff(d, d, regions, writer);
				// 5x5 populated chunks; the west and north edges lack a neighbour whose decoration reaches in.
				assertEquals(16, stats.compared());
				assertEquals(9, stats.unsettledSaved());
			}
		}
	}

	@Test
	void diffFileKeepsNegativeCoordinatesAndRepeatedStates(@TempDir Path tmp) throws IOException {
		List<Block> blocks = List.of(
				new Block(-17, -64, -1, "deepslate", "air"),
				new Block(-32, 319, -16, "air", "oak_planks"),
				new Block(-20, 0, -5, "oak_leaves[persistent=false,waterlogged=false]", "air"));
		Path file = tmp.resolve("d.gz");
		try (DiffFile.Writer writer = new DiffFile.Writer(file)) {
			writer.chunk(new ChunkDiff(-2, -1, blocks));
		}
		assertEquals(List.of(new ChunkDiff(-2, -1, blocks)), DiffFile.read(file));
	}
}
