package justfatlard.past_echos.preprocess.inventory;

import static justfatlard.past_echos.preprocess.inventory.Fixtures.compound;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import justfatlard.past_echos.preprocess.inventory.WorldRecord.Era;
import justfatlard.past_echos.preprocess.inventory.WorldRecord.Pipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InventoryTest {
	@TempDir
	Path tmp;

	private List<WorldRecord> scan(Path... paths) throws IOException {
		Collector collector = new Collector();
		Walker walker = new Walker(collector, message -> {
		});
		for (Path path : paths) {
			walker.walk(path);
		}
		assertEquals(List.of(), collector.warnings, "no warnings from well-formed fixtures");
		return Classifier.classify(collector);
	}

	private static WorldRecord only(List<WorldRecord> records) {
		assertEquals(1, records.size(), () -> "expected one world, got " + records);
		return records.getFirst();
	}

	@Test
	void alphaFormatWorldIsSeededAndNeedsTheClientJar() throws IOException {
		Path world = tmp.resolve("World1");
		Fixtures.levelDat(world, compound("RandomSeed", 42L, "LastPlayed", 1_280_000_000_000L, "SizeOnDisk", 0L));
		Fixtures.alphaChunks(world, 50);
		Fixtures.alphaChunks(world.resolve("DIM-1"), 7);

		WorldRecord r = only(scan(tmp));
		assertEquals(Era.ALPHA_FORMAT, r.era());
		assertEquals("alpha", r.format());
		assertEquals(42L, r.seed());
		assertEquals(Map.of("overworld", 50L, "the_nether", 7L), r.chunks());
		assertEquals(Pipeline.CLIENT_JAR, r.pipeline());
		assertEquals("World1", r.name(), "Alpha level.dat has no LevelName; the folder names it");
	}

	@Test
	void mcregionWorldCountsChunksFromTheLocationTable() throws IOException {
		Path world = tmp.resolve("beta");
		Fixtures.levelDat(world, compound("RandomSeed", -7L, "LevelName", "Beta Base", "version", 19132));
		Fixtures.region(world.resolve("region"), "r.0.0.mcr", 300);
		Fixtures.region(world.resolve("region"), "r.-1.0.mcr", 24);

		WorldRecord r = only(scan(tmp));
		assertEquals(Era.MCREGION, r.era());
		assertEquals(324L, r.totalChunks());
		assertEquals(Pipeline.CLIENT_JAR, r.pipeline());
	}

	@Test
	void convertedWorldCountsAnvilOnceAndSaysItsChunksAreOlder() throws IOException {
		Path world = tmp.resolve("long-lived");
		Fixtures.levelDat(world, compound("RandomSeed", 5L, "LevelName", "Home", "version", 19133));
		Fixtures.region(world.resolve("region"), "r.0.0.mcr", 100);
		Fixtures.region(world.resolve("region"), "r.0.0.mca", 120);

		WorldRecord r = only(scan(tmp));
		assertEquals(Era.ANVIL_UNTAGGED, r.era());
		assertEquals(120L, r.totalChunks());
		assertEquals(Pipeline.SERVER_JAR, r.pipeline());
		assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("converted from McRegion")), r.notes()::toString);
	}

	@Test
	void modernLayoutFindsTheSeedOutsideLevelDatAndIgnoresEntityAndPoiFiles() throws IOException {
		Path world = tmp.resolve("modern");
		Fixtures.levelDat(world, compound("LevelName", "Now", "DataVersion", 5023, "Version", compound("Name", "26.3"),
				"version_history", List.of(4189, 5023)));
		Path settings = world.resolve("data/minecraft");
		Files.createDirectories(settings);
		Files.write(settings.resolve("world_gen_settings.dat"), Fixtures.gzip(Fixtures.nbt(compound("data", compound("seed", 123456789L,
				"dimensions", compound("minecraft:overworld", compound("generator", compound("type", "minecraft:noise", "settings", "minecraft:amplified"))))))));
		Path overworld = world.resolve("dimensions/minecraft/overworld");
		Fixtures.region(overworld.resolve("region"), "r.0.0.mca", 10);
		Fixtures.region(overworld.resolve("entities"), "r.0.0.mca", 999);
		Fixtures.region(overworld.resolve("poi"), "r.0.0.mca", 999);
		Fixtures.region(world.resolve("dimensions/minecraft/the_nether/region"), "r.0.0.mca", 3);

		WorldRecord r = only(scan(tmp));
		assertEquals(Era.MODERN, r.era());
		assertEquals(123456789L, r.seed());
		assertEquals(Map.of("overworld", 10L, "the_nether", 3L), r.chunks());
		assertEquals("minecraft:amplified", r.generator());
		assertEquals(List.of(4189, 5023), r.versionHistory());
		assertFalse(r.modded());
	}

	@Test
	void sixteenToTwentyOneKeepsTheSeedInWorldGenSettings() throws IOException {
		Path world = tmp.resolve("w");
		Fixtures.levelDat(world, compound("DataVersion", 2586, "Version", compound("Name", "1.16.5"),
				"WorldGenSettings", compound("seed", 99L)));
		Fixtures.region(world.resolve("region"), "r.0.0.mca", 1);

		WorldRecord r = only(scan(tmp));
		assertEquals(99L, r.seed());
		assertEquals(Era.PRE_CAVES, r.era());
	}

	@Test
	void forgeWorldIsFlaggedModded() throws IOException {
		Path world = tmp.resolve("modpack");
		Files.createDirectories(world);
		Files.write(world.resolve("level.dat"), Fixtures.gzip(Fixtures.nbt(compound(
				"Data", compound("RandomSeed", 1L, "DataVersion", 1343, "Version", compound("Name", "1.12.2")),
				"FML", compound("ModList", List.of())))));
		Fixtures.region(world.resolve("region"), "r.0.0.mca", 1);
		Fixtures.region(world.resolve("DIM7/region"), "r.0.0.mca", 1);

		WorldRecord r = only(scan(tmp));
		assertTrue(r.modded());
		assertTrue(r.notes().contains("extra dimension: DIM7"), r.notes()::toString);
	}

	@Test
	void classicAndPreClassicLevelsAreSeedlessAndKnownByTheirBytes() throws IOException {
		ByteArrayOutputStream classic = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(classic);
		out.writeInt(Collector.CLASSIC_MAGIC);
		out.writeByte(2);
		out.write(new byte[100]);
		Files.write(tmp.resolve("level.dat"), Fixtures.gzip(classic.toByteArray()));
		Path rd = tmp.resolve("rd");
		Files.createDirectories(rd);
		Files.write(rd.resolve("level.dat"), Fixtures.gzip(new byte[Collector.RD_RAW_SIZE]));

		List<WorldRecord> records = scan(tmp);
		assertEquals(2, records.size());
		for (WorldRecord r : records) {
			assertEquals(Era.FINITE, r.era());
			assertEquals(Pipeline.HEURISTIC, r.pipeline());
			assertNull(r.seed());
		}
		assertEquals(List.of("classic v2", "pre-classic raw"), records.stream().map(WorldRecord::format).sorted().toList());
	}

	@Test
	void indevLevelReadsNameAuthorAndSize() throws IOException {
		Files.write(tmp.resolve("island.mclevel"), Fixtures.gzip(Fixtures.nbt(compound(
				"About", compound("Name", "A Nice World", "Author", "fatlard", "CreatedOn", 1_266_000_000_000L),
				"Map", compound("Width", (short) 256, "Length", (short) 256, "Height", (short) 64)))));

		WorldRecord r = only(scan(tmp));
		assertEquals("indev", r.format());
		assertEquals("A Nice World", r.name());
		assertTrue(r.notes().contains("size 256x64x256"), r.notes()::toString);
	}

	@Test
	void bedrockWorldIsReportedAndLeftOutOfScope() throws IOException {
		Path world = tmp.resolve("mcpe");
		Files.createDirectories(world.resolve("db"));
		Files.write(world.resolve("db/000005.ldb"), new byte[]{0});
		Files.writeString(world.resolve("levelname.txt"), "Phone World\n");
		// An empty compound is the same bytes in either byte order, so the big-endian writer serves.
		byte[] be = Fixtures.nbt(compound());
		byte[] file = new byte[8 + be.length];
		file[0] = 10;
		file[4] = (byte) be.length;
		file[5] = (byte) (be.length >> 8);
		System.arraycopy(be, 0, file, 8, be.length);
		Files.write(world.resolve("level.dat"), file);

		List<WorldRecord> records = scan(tmp);
		WorldRecord r = only(records);
		assertEquals(Era.BEDROCK, r.era());
		assertEquals("Phone World", r.name());
		assertEquals(Pipeline.OUT_OF_SCOPE, r.pipeline());
	}

	@Test
	void regionFolderWithoutLevelDatIsAnOrphanNeedingASeed() throws IOException {
		Fixtures.region(tmp.resolve("just-regions/region"), "r.0.0.mca", 5);

		WorldRecord r = only(scan(tmp));
		assertEquals(Pipeline.NEEDS_SEED, r.pipeline());
		assertEquals("just-regions", r.name());
	}

	@Test
	void worldBackedUpInsideAnotherWorldKeepsItsOwnChunks() throws IOException {
		Path outer = tmp.resolve("outer");
		Fixtures.levelDat(outer, compound("RandomSeed", 1L, "LevelName", "Outer"));
		Fixtures.region(outer.resolve("region"), "r.0.0.mca", 4);
		Path inner = outer.resolve("backups/inner");
		Fixtures.levelDat(inner, compound("RandomSeed", 2L, "LevelName", "Inner"));
		Fixtures.region(inner.resolve("region"), "r.0.0.mca", 9);

		Map<String, Long> chunks = new LinkedHashMap<>();
		scan(tmp).forEach(r -> chunks.put(r.name(), r.totalChunks()));
		assertEquals(Map.of("Outer", 4L, "Inner", 9L), chunks);
	}

	@Test
	void sameWorldOnDiskZippedAndNestedInATarballIsOneWorldInThreeCopies() throws IOException {
		Path source = tmp.resolve("src/Survival");
		Fixtures.levelDat(source, compound("RandomSeed", 777L, "LevelName", "Survival", "version", 19132));
		Fixtures.region(source.resolve("region"), "r.0.0.mcr", 12);

		Path scanRoot = tmp.resolve("backups");
		Files.createDirectories(scanRoot);
		Fixtures.zip(source, scanRoot.resolve("2011-05-01.zip"));
		Map<String, byte[]> tar = new LinkedHashMap<>();
		tar.put("old-drive/" + "deep/".repeat(25) + "Survival-2010.zip", Fixtures.zipBytes(source));
		Fixtures.tarGz(scanRoot.resolve("drive.tar.gz"), tar);
		Files.createDirectories(scanRoot.resolve("loose"));
		Files.copy(source.resolve("level.dat"), Files.createDirectories(scanRoot.resolve("loose/Survival")).resolve("level.dat"));
		Fixtures.region(scanRoot.resolve("loose/Survival/region"), "r.0.0.mcr", 12);

		List<Report.Group> groups = Report.group(scan(scanRoot));
		assertEquals(1, groups.size(), groups::toString);
		assertEquals(3, groups.getFirst().copies().size());
		groups.getFirst().copies().forEach(c -> assertEquals(12L, c.totalChunks(), c.path()));
		assertTrue(groups.getFirst().copies().stream().anyMatch(c -> c.path().contains("drive.tar.gz!/old-drive/deep/")),
				"the GNU long name inside the tar survives");
	}

	@Test
	void unreadableArchivesAreSkippedNotFatal() throws IOException {
		Files.write(tmp.resolve("broken.zip"), new byte[]{'P', 'K', 3, 4, 0, 0});
		Files.write(tmp.resolve("worlds.7z"), new byte[]{1});
		Fixtures.levelDat(tmp.resolve("fine"), compound("RandomSeed", 3L));
		Fixtures.region(tmp.resolve("fine/region"), "r.0.0.mca", 1);

		Collector collector = new Collector();
		Walker walker = new Walker(collector, message -> {
		});
		walker.walk(tmp);
		assertEquals(2, collector.skipped.size());
		assertEquals(1, Classifier.classify(collector).size());
	}

	@Test
	void jsonKeepsSeedsAsExactStrings() throws IOException {
		Fixtures.levelDat(tmp.resolve("w"), compound("RandomSeed", Long.MIN_VALUE + 1, "LevelName", "Big \"Seed\""));
		Fixtures.region(tmp.resolve("w/region"), "r.0.0.mca", 1);
		Collector collector = new Collector();
		new Walker(collector, message -> {
		}).walk(tmp);
		String json = Report.json(collector, Report.group(Classifier.classify(collector)), List.of(tmp.toString()));
		assertTrue(json.contains("\"seed\":\"" + (Long.MIN_VALUE + 1) + "\""), json);
		assertTrue(json.contains("Big \\\"Seed\\\""), json);
	}
}
