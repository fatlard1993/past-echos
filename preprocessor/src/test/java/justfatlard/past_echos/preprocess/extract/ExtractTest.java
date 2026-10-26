package justfatlard.past_echos.preprocess.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import justfatlard.past_echos.preprocess.diff.Diff;
import justfatlard.past_echos.preprocess.diff.DiffFile;
import justfatlard.past_echos.preprocess.diff.DiffFile.Block;
import justfatlard.past_echos.preprocess.diff.DiffFile.ChunkDiff;
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

	/** A mod's village house where the regeneration put its own, and a player's farm on what was grass. */
	@Test
	void clustersWhereTheRegenerationBuiltTooAreGenerated(@TempDir Path noWorld) throws IOException {
		List<Block> blocks = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			blocks.add(new Block(i, 64, 0, "grass_block", "mud_bricks"));
			blocks.add(new Block(i, 64, 1, "grass_block", "acacia_planks"));
			blocks.add(new Block(i, 65, 0, "glass_pane", "air"));
			blocks.add(new Block(i, 65, 1, "oak_stairs[facing=east]", "air"));
			blocks.add(new Block(i, 66, 1, "spruce_planks", "air"));
			blocks.add(new Block(200 + i, 64, 0, "grass_block", "farmland[moisture=7]"));
			blocks.add(new Block(200 + i, 64, 2, "grass_block", "oak_fence"));
		}
		List<Echo> echoes = Extract.cut(byChunk(blocks), Evidence.plain(), noWorld).echoes();
		assertEquals(List.of(199), echoes.stream().map(Echo::x).toList());
	}

	/** A mod's desert house beside one the regeneration built, and a player's spruce hut beside the same. */
	@Test
	void clustersOfWhatTheRegenerationBuiltBesideThemAreGenerated(@TempDir Path noWorld) throws IOException {
		List<Block> blocks = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			for (int y = 64; y < 69; y++) {
				blocks.add(new Block(40 + i, y, 0, y % 2 == 0 ? "smooth_sandstone" : "cut_sandstone", "air"));
				blocks.add(new Block(40 + i, y, 8, y % 2 == 0 ? "smooth_sandstone" : "cut_sandstone", "air"));
			}
			blocks.add(new Block(i, 64, 0, "sand", "smooth_sandstone"));
			blocks.add(new Block(i, 65, 0, "air", "cut_sandstone"));
			blocks.add(new Block(80 + i, 64, 0, "sand", "spruce_planks"));
			blocks.add(new Block(80 + i, 65, 0, "air", "glass"));
		}
		List<Echo> echoes = Extract.cut(byChunk(blocks), Evidence.plain(), noWorld).echoes();
		assertEquals(List.of(79), echoes.stream().map(Echo::x).toList());
		List<Block> alone = blocks.stream().filter(b -> b.x() < 40).toList();
		assertEquals(List.of(-1), Extract.cut(byChunk(alone), Evidence.plain(), noWorld).echoes().stream().map(Echo::x).toList(),
				"with no village near, sandstone is a player's");
	}

	/** Noise reaching across a region beside a hut: the hut is still placeable alone. */
	@Test
	void aClusterTooWideToPlaceTakesNoNeighbourWithIt(@TempDir Path noWorld) throws IOException {
		List<Block> blocks = new ArrayList<>();
		for (int x = 0; x < Extract.MAX_SPAN + 60; x++) {
			blocks.add(new Block(x, 40, 0, "stone", x % 2 == 0 ? "glass" : "oak_planks"));
		}
		for (int i = 0; i < 6; i++) {
			blocks.add(new Block(100 + i, 42, 3, "air", "spruce_planks"));
			blocks.add(new Block(100 + i, 43, 3, "air", "glass"));
		}
		Extract.Cut cut = Extract.cut(byChunk(blocks), Evidence.plain(), noWorld);
		assertEquals(List.of(99), cut.echoes().stream().map(Echo::x).toList());
		assertEquals(1, cut.tooWide());
	}

	private static List<ChunkDiff> byChunk(List<Block> blocks) {
		Map<List<Integer>, List<Block>> chunks = new LinkedHashMap<>();
		blocks.forEach(b -> chunks.computeIfAbsent(List.of(b.x() >> 4, b.z() >> 4), k -> new ArrayList<>()).add(b));
		return chunks.entrySet().stream().map(e -> new ChunkDiff(e.getKey().get(0), e.getKey().get(1), e.getValue())).toList();
	}

	/** A mod's sky ruin twice, turned and crumbled, and two torch-lit shafts of one palette but not one layout. */
	@Test
	void oneCopyFarOffInTheSameLayoutIsEnough() {
		Map<List<Integer>, String> ruin = new LinkedHashMap<>();
		for (int x = 0; x < 6; x++) {
			ruin.put(List.of(x, 0, 0), "purpur_block");
			ruin.put(List.of(x, 0, 1), "purpur_block");
		}
		for (int y = 1; y < 5; y++) {
			ruin.put(List.of(0, y, 0), "purpur_pillar[axis=y]");
		}
		ruin.put(List.of(5, 1, 0), "purpur_stairs[facing=east]");
		ruin.put(List.of(5, 1, 1), "purpur_stairs[facing=east]");
		Map<List<Integer>, String> turned = new LinkedHashMap<>();
		ruin.forEach((c, block) -> turned.put(List.of(1 - c.get(2), c.get(1), c.get(0)), block));
		turned.remove(List.of(1, 0, 3));
		turned.remove(List.of(1, 4, 0));
		Echo shaft = echo(-2000, -1200, column("cobbled_deepslate", 8, "torch"));
		Map<List<Integer>, String> floor = new LinkedHashMap<>();
		for (int x = 0; x < 8; x++) {
			floor.put(List.of(x, 0, 0), "cobbled_deepslate");
		}
		floor.put(List.of(2, 1, 0), "torch");
		floor.put(List.of(5, 1, 0), "torch");
		List<Echo> echoes = List.of(echo(150, -70, ruin), echo(700, -2800, turned), shaft, echo(-40, -100, floor));
		assertEquals(echoes.subList(2, 4), Extract.unrepeated(echoes));
	}

	/** A player's torch-lit cobbled deepslate tower, and three mines of the same palette far off under stone. */
	@Test
	void minesMakeNoCopiesOfABuildAboveGround() {
		Echo tower = echo(0, 0, column("cobbled_deepslate", 8, "torch"));
		List<Echo> echoes = new ArrayList<>(List.of(tower));
		for (int i = 1; i <= 3; i++) {
			Map<List<Integer>, String> mine = new LinkedHashMap<>();
			for (int x = 0; x < 3 + i; x++) {
				mine.put(List.of(x, 0, 0), "cobbled_deepslate");
				mine.put(List.of(x, 2, 0), "stone");
			}
			mine.put(List.of(1, 1, 0), "torch");
			echoes.add(echo(i * 1000, 0, mine, "stone"));
		}
		assertEquals(3, echoes.stream().filter(e -> !Extract.onSurface(e)).count());
		assertEquals(List.of(tower), Extract.placeable(echoes));
	}

	private static Map<List<Integer>, String> column(String block, int height, String torch) {
		Map<List<Integer>, String> cells = new LinkedHashMap<>();
		for (int y = 0; y < height; y++) {
			cells.put(List.of(0, y, 0), block);
		}
		cells.put(List.of(1, 3, 0), torch);
		cells.put(List.of(1, 6, 0), torch);
		return cells;
	}

	/** An echo holding these blocks, at positions from its corner, all evidence. */
	private static Echo echo(int x, int z, Map<List<Integer>, String> cells) {
		return echo(x, z, cells, null);
	}

	/** An echo holding these blocks, all evidence but those of the unchanged block. */
	private static Echo echo(int x, int z, Map<List<Integer>, String> cells, String unchanged) {
		int sx = cells.keySet().stream().mapToInt(c -> c.get(0)).max().orElse(0) + 1;
		int sy = cells.keySet().stream().mapToInt(c -> c.get(1)).max().orElse(0) + 1;
		int sz = cells.keySet().stream().mapToInt(c -> c.get(2)).max().orElse(0) + 1;
		List<String> palette = new ArrayList<>(List.of("air"));
		int[] states = new int[sx * sy * sz];
		byte[] marks = new byte[states.length];
		cells.forEach((c, block) -> {
			if (!palette.contains(block)) {
				palette.add(block);
			}
			int i = (c.get(1) * sz + c.get(2)) * sx + c.get(0);
			states[i] = palette.indexOf(block);
			marks[i] = block.equals(unchanged) ? EchoFile.TERRAIN : EchoFile.EVIDENCE;
		});
		return new Echo(x, 200, z, sx, sy, sz, cells.size(), 5023, palette.toArray(String[]::new), states, marks, List.of());
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
