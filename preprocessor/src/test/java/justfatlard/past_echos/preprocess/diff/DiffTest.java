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
import justfatlard.past_echos.preprocess.extract.Vocabulary;
import justfatlard.past_echos.preprocess.extract.Vocabulary.Kind;
import justfatlard.past_echos.preprocess.regen.Json;
import justfatlard.past_echos.preprocess.world.Chunk;
import justfatlard.past_echos.preprocess.world.Dimension;
import justfatlard.past_echos.preprocess.world.RegionFile;
import justfatlard.past_echos.preprocess.world.Structures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
			Diff.Outcome o = Diff.against(Diff.findWorld(built.getPath("/")), Diff.findWorld(plain.getPath("/")), "1.12.2", tmp, 0);
			assertEquals(manifest(built), lines(DiffFile.read(o.diffFile())));
			assertEquals(0, o.stats().unsettledReference());
			assertEquals(0, o.reference().serverRuns());
		}
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource({"1.12.2, true", "1.2.5, false"})
	void chunksNoPlayerSpentTimeInAreSkipped(String version, boolean recordsTime, @TempDir Path tmp) throws IOException {
		try (FileSystem plain = FileSystems.newFileSystem(resource("worlds/" + version + ".zip"));
				FileSystem built = FileSystems.newFileSystem(resource("worlds/" + version + "-built.zip"))) {
			Path world = Diff.findWorld(built.getPath("/"));
			Path reference = Diff.findWorld(plain.getPath("/"));
			WorldDiff.Stats all = Diff.against(world, reference, version, tmp.resolve("all"), 0).stats();
			Diff.Outcome o = Diff.against(world, reference, version, tmp.resolve("visited"), Diff.MIN_INHABITED);
			WorldDiff.Stats visited = o.stats();
			// Generated with no player about: 1.12.2 records no time in any chunk, 1.2.5 records none at all.
			assertTrue(all.compared() > 0, all.toString());
			assertEquals(recordsTime ? 0 : all.compared(), visited.compared(), visited.toString());
			assertEquals(recordsTime ? all.compared() : 0, visited.excluded(), visited.toString());
			// So few chunks that the sample takes them all.
			assertEquals(recordsTime ? all.compared() : 0, o.unvisitedCompared());
			assertEquals(recordsTime, Files.exists(tmp.resolve("visited").resolve(Diff.UNVISITED_FILE)));
		}
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"1.2.5", "1.6.4", "1.8.9", "1.12.2", "1.16.5", "1.18.2", "1.20.4", "26.3"})
	void withoutARegenerationABuildIsWhatIsntNatural(String version, @TempDir Path tmp) throws IOException {
		try (FileSystem plain = FileSystems.newFileSystem(resource("worlds/" + version + ".zip"));
				FileSystem built = FileSystems.newFileSystem(resource("worlds/" + version + "-built.zip"))) {
			Set<String> before = lines(DiffFile.read(Diff.savedOnly(Diff.findWorld(plain.getPath("/")), version, tmp.resolve("plain"), 0).diffFile()));
			Set<String> after = new TreeSet<>(lines(DiffFile.read(Diff.savedOnly(Diff.findWorld(built.getPath("/")), version, tmp.resolve("built"), 0).diffFile())));
			after.removeAll(before);
			Set<String> placed = new TreeSet<>();
			for (String line : manifest(built)) {
				String block = line.substring(line.indexOf(" -> ") + 4);
				Kind kind = Vocabulary.kind(block);
				if (kind == Kind.BUILT || kind == Kind.STRUCTURE) {
					placed.add(line.substring(0, line.indexOf(' ')) + " air -> " + block);
				}
			}
			assertTrue(placed.size() > 10, placed.toString());
			assertEquals(placed, after);
		}
	}

	/** The 1.20.4 world's mineshaft runs under the hut: its rails are in its recorded pieces. */
	@Test
	void withoutARegenerationRecordedStructuresAreLeftOut(@TempDir Path tmp) throws IOException {
		try (FileSystem plain = FileSystems.newFileSystem(resource("worlds/1.20.4.zip"))) {
			Path world = Diff.findWorld(plain.getPath("/"));
			long rails = 0;
			for (Path file : Dimension.read(Dimension.overworldRegions(world)).regionFiles) {
				for (Chunk c : RegionFile.read(file).chunks()) {
					for (Chunk.Section section : c.sections().values()) {
						for (int i = 0; i < 4096; i++) {
							rails += section.block(i).startsWith("rail") ? 1 : 0;
						}
					}
				}
			}
			assertTrue(rails > 0);
			Set<String> diff = lines(DiffFile.read(Diff.savedOnly(world, "1.20.4", tmp, 0).diffFile()));
			assertEquals(Set.of(), diff.stream().filter(l -> l.contains("-> rail")).collect(Collectors.toSet()));
			Structures written = Structures.read(tmp.resolve(Diff.STRUCTURES_FILE));
			assertEquals(Dimension.read(Dimension.overworldRegions(world)).structurePieces().size(), written.size());
			assertTrue(written.size() > 0);
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
