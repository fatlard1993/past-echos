package justfatlard.past_echos.preprocess.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import justfatlard.past_echos.preprocess.diff.Diff;
import justfatlard.past_echos.preprocess.diff.DiffFile;
import justfatlard.past_echos.preprocess.diff.DiffTest;
import justfatlard.past_echos.preprocess.extract.EchoFile.Echo;
import justfatlard.past_echos.preprocess.regen.Json;
import justfatlard.past_echos.preprocess.world.Dimension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Extraction over real stage-one output (fixtures/diffs.py). diffs/VERSION-built is each built
 * test world diffed against its regeneration, so the hut and tunnel sit among real noise. The
 * -noise diffs are untouched worlds diffed against another generation of their seed, so nothing
 * but noise: the determinism runs, and fresh seeds that turned up an ancient city's sculk,
 * dungeons, lava meeting water, 26.x's sulfur caves, and an ocean ruin.
 */
class ExtractTest {
	/** fixtures/build.py digs its tunnel at y 24 and 25, well below the hut. */
	private static final int TUNNEL_TOP = 25;

	static Stream<String> noise() throws IOException {
		try (Stream<Path> files = Files.list(DiffTest.resource("diffs"))) {
			return files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith("-noise.diff.gz")).sorted().toList().stream();
		}
	}

	/** Each version with the data version its chunks are saved under, none before 1.9. */
	@ParameterizedTest(name = "{0}")
	@CsvSource({"1.2.5, -1", "1.6.4, -1", "1.8.9, -1", "1.12.2, 1343", "1.16.5, 2586", "1.18.2, 2975", "1.20.4, 3700", "26.3, 5023"})
	void theHutIsTheOneEcho(String version, int dataVersion) throws IOException {
		try (FileSystem built = FileSystems.newFileSystem(DiffTest.resource("worlds/" + version + "-built.zip"))) {
			Path regions = Dimension.overworldRegions(Diff.findWorld(built.getPath("/")));
			List<Echo> echoes = Extract.run(DiffFile.read(DiffTest.resource("diffs/" + version + "-built.diff.gz")), regions);
			// The tunnel is earthwork alone, which regeneration's lakes match in size, so it is no echo.
			assertEquals(1, echoes.size(), () -> "echoes: " + echoes.stream().map(e -> e.x() + "," + e.y() + "," + e.z()).toList());
			Echo hut = echoes.getFirst();
			assertEquals(dataVersion, hut.dataVersion());

			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
			for (String line : DiffTest.manifest(built)) {
				String[] at = line.substring(0, line.indexOf(' ')).split(",");
				int x = Integer.parseInt(at[0]);
				int y = Integer.parseInt(at[1]);
				int z = Integer.parseInt(at[2]);
				String after = line.substring(line.indexOf(" -> ") + 4);
				if (y <= TUNNEL_TOP) {
					continue;
				}
				assertTrue(hut.contains(x, y, z), line);
				assertEquals(after, hut.state(x - hut.x(), y - hut.y(), z - hut.z()), line);
				if (!after.equals("air")) {
					assertEquals(EchoFile.EVIDENCE, hut.mark(x - hut.x(), y - hut.y(), z - hut.z()), line);
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					minZ = Math.min(minZ, z);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
					maxZ = Math.max(maxZ, z);
				}
			}
			// The sign's block entity comes along, its position now from the echo's corner.
			@SuppressWarnings("unchecked")
			Map<String, Object> sign = (Map<String, Object>) ((Map<String, Object>) Json.parse(Files.readString(built.getPath("/build.json")))).get("sign");
			assertEquals(1, hut.blockEntities().size());
			Map<String, Object> entity = hut.blockEntities().getFirst();
			assertEquals(List.of(((Number) sign.get("x")).intValue() - hut.x(), ((Number) sign.get("y")).intValue() - hut.y(),
					((Number) sign.get("z")).intValue() - hut.z()), List.of(entity.get("x"), entity.get("y"), entity.get("z")));
			assertTrue(entity.toString().contains("past echoes"), entity.toString());

			int m = Extract.MARGIN;
			assertArrayEquals(new int[] {minX - m, minY - m, minZ - m, maxX - minX + 1 + 2 * m, maxY - minY + 1 + 2 * m, maxZ - minZ + 1 + 2 * m},
					new int[] {hut.x(), hut.y(), hut.z(), hut.sizeX(), hut.sizeY(), hut.sizeZ()}, "the hut and its margin, no more");
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("noise")
	void noiseAloneHasNoEchoes(String file, @TempDir Path noWorld) throws IOException {
		List<DiffFile.ChunkDiff> noise = DiffFile.read(DiffTest.resource("diffs/" + file));
		assertTrue(noise.stream().mapToInt(c -> c.blocks().size()).sum() > 0, "the fixture holds noise");
		assertEquals(List.of(), Extract.run(noise, noWorld));
	}

	/** Every noise cluster misses some threshold by a wide margin, not just barely. */
	@Test
	void noiseStaysWellShortOfAnEcho() throws IOException {
		for (String file : noise().toList()) {
			for (Clusters.Cluster c : Clusters.find(DiffFile.read(DiffTest.resource("diffs/" + file)))) {
				assertTrue(c.score() * 2 <= Extract.MIN_SCORE || c.placed() * 2 <= Extract.MIN_PLACED
						|| c.dominance() >= (1 + Extract.MAX_DOMINANCE) / 2,
						file + ": score " + c.score() + ", " + c.placed() + " placed, dominance " + c.dominance());
			}
		}
	}

	/** A torch-lit tunnel under stone is a mine and no echo yet; the same work in the open is. */
	@Test
	void minesAreUnderground() {
		// 3x3 columns, 3 high: y0 floor of stone, y1 dug air with a torch in the middle, y2 stone or air.
		String[] palette = {"stone", "air", "wall_torch[facing=north]"};
		int[] states = new int[27];
		byte[] marks = new byte[27];
		for (int i = 9; i < 18; i++) {
			states[i] = 1;
			marks[i] = EchoFile.EVIDENCE;
		}
		states[13] = 2;
		Echo mine = new Echo(0, -60, 0, 3, 3, 3, 12, 5023, palette, states, marks, List.of());
		assertEquals(1.0, Extract.covered(mine));
		assertTrue(!Extract.onSurface(mine));

		int[] open = states.clone();
		for (int i = 18; i < 27; i++) {
			open[i] = 1;
		}
		assertEquals(0.0, Extract.covered(new Echo(0, 70, 0, 3, 3, 3, 12, 5023, palette, open, marks, List.of())));
	}

	/** A mod's ship, generated in three seas, and one player's two houses a street apart and a third far off. */
	@Test
	void structuresWithCopiesFarApartAreGenerated() {
		Echo ship = boat(0, 0, 40, 30, 12);
		List<Echo> echoes = List.of(ship, boat(1000, 0, 41, 30, 12), boat(0, 2000, 40, 29, 11),
				boat(5000, 5000, 10, 60, 0), boat(5100, 5000, 10, 60, 0), boat(5000, 5200, 10, 60, 0));
		List<Echo> kept = Extract.unrepeated(echoes);
		assertEquals(echoes.subList(3, 6), kept, "the houses are nearer one another than copies need be");
		assertEquals(List.of(ship), Extract.unrepeated(List.of(ship)));
	}

	/** An echo of planks, wool and fences, one block of each in a row, all evidence. */
	private static Echo boat(int x, int z, int planks, int wool, int fences) {
		String[] palette = {"spruce_planks", "white_wool", "dark_oak_fence[north=true]"};
		int n = planks + wool + fences;
		int[] states = new int[n];
		byte[] marks = new byte[n];
		for (int i = 0; i < n; i++) {
			states[i] = i < planks ? 0 : i < planks + wool ? 1 : 2;
			marks[i] = EchoFile.EVIDENCE;
		}
		return new Echo(x, 62, z, n, 1, 1, n, 5023, palette, states, marks, List.of());
	}

	@Test
	void echoFileRoundTrips(@TempDir Path tmp) throws IOException {
		Echo echo = new Echo(-5, 60, 7, 2, 1, 2, 12.5, 3700, new String[] {"air", "oak_planks", "glass"}, new int[] {0, 1, 2, 1},
				new byte[] {EchoFile.TERRAIN, EchoFile.EVIDENCE, EchoFile.EVIDENCE, EchoFile.CHANGED},
				List.of(Map.of("id", "minecraft:sign", "x", 1, "y", 0, "z", 1, "front_text", Map.of("messages", List.of("hello", "", "", "")))));
		Path file = tmp.resolve("echoes.gz");
		EchoFile.write(file, "1.20.4", List.of(echo));
		EchoFile.Contents read = EchoFile.read(file);
		assertEquals("1.20.4", read.version());
		Echo back = read.echoes().getFirst();
		assertEquals(List.of(-5, 60, 7, 2, 1, 2), List.of(back.x(), back.y(), back.z(), back.sizeX(), back.sizeY(), back.sizeZ()));
		assertEquals(12.5, back.score());
		assertEquals(3700, back.dataVersion());
		assertArrayEquals(echo.palette(), back.palette());
		assertArrayEquals(echo.states(), back.states());
		assertArrayEquals(echo.marks(), back.marks());
		assertEquals("glass", back.state(0, 0, 1));
		assertEquals(echo.blockEntities(), back.blockEntities());
	}
}
