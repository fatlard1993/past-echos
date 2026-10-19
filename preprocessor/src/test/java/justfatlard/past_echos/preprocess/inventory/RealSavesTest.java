package justfatlard.past_echos.preprocess.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import justfatlard.past_echos.preprocess.inventory.WorldRecord.Era;
import justfatlard.past_echos.preprocess.inventory.WorldRecord.Pipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Saves made by the real game: worlds/ by Mojang's server jars (fixtures/generate.py), found/
 * from other open-source tools for the eras no server jar covers (fixtures/vendor.py). Expected
 * values come from how each was made or from reading it independently, never from this tool.
 */
class RealSavesTest {
	private static final long GENERATED_SEED = 8675309L;

	private static Collector found;
	private static List<WorldRecord> foundRecords;

	@BeforeAll
	static void scanFound() throws IOException {
		found = new Collector();
		new Walker(found, message -> {
		}).walk(resource("found"));
		foundRecords = Classifier.classify(found);
	}

	private static Path resource(String name) {
		try {
			return Path.of(RealSavesTest.class.getResource("/" + name).toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	private static WorldRecord foundAt(String suffix) {
		List<WorldRecord> matches = foundRecords.stream().filter(r -> r.path().endsWith(suffix)).toList();
		assertEquals(1, matches.size(), () -> "one world at *" + suffix + " among " + foundRecords.stream().map(WorldRecord::path).toList());
		return matches.getFirst();
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource({
			"1.2.5,  ANVIL_UNTAGGED, 1.2-1.8 (no version tag)",
			"1.6.4,  ANVIL_UNTAGGED, 1.2-1.8 (no version tag)",
			"1.8.9,  ANVIL_UNTAGGED, 1.2-1.8 (no version tag)",
			"1.12.2, PRE_FLATTENING, 1.12.2",
			"1.16.5, PRE_CAVES,      1.16.5",
			"1.18.2, MODERN,         1.18.2",
			"1.20.4, MODERN,         1.20.4",
			"26.3,   MODERN,         26.3",
	})
	void serverJarWorldReadsBackHowItWasMade(String made, Era era, String version) throws IOException {
		Collector collector = new Collector();
		new Walker(collector, message -> {
		}).walk(resource("worlds/" + made + ".zip"));
		assertEquals(List.of(), collector.warnings);

		List<WorldRecord> records = Classifier.classify(collector);
		assertEquals(1, records.size(), records::toString);
		WorldRecord r = records.getFirst();
		assertTrue(r.path().endsWith(made + ".zip!/world-" + made), r.path());
		assertEquals(era, r.era());
		assertEquals(version, r.version());
		assertEquals(GENERATED_SEED, r.seed());
		assertEquals(Map.of("overworld", 25L), r.chunks(), "trimmed to the 5x5 chunks around spawn");
		assertEquals(Pipeline.SERVER_JAR, r.pipeline());
		assertFalse(r.modded(), r.notes()::toString);
		assertTrue(r.notes().stream().noneMatch(n -> n.startsWith("generator:")), "a default world is not reported as a custom generator: " + r.notes());
	}

	@Test
	void foundSavesReadWithoutWarnings() {
		assertEquals(List.of(), found.warnings);
		assertEquals(List.of(), found.skipped.stream().map(s -> s[0] + ": " + s[1]).toList());
	}

	@Test
	void preClassicLevelIsTheBareBlockArray() {
		WorldRecord r = foundAt("rd-132211/level.dat");
		assertEquals("pre-classic raw", r.format());
		assertEquals(Pipeline.HEURISTIC, r.pipeline());
	}

	@Test
	void classicServerLevelGivesItsNameAndCreator() {
		WorldRecord r = foundAt("server_level(flatgrass256).dat");
		assertEquals("classic v2", r.format());
		assertEquals("Flatgrass", r.name(), "read past the empty entity list early Classic writes before the name");
		assertTrue(r.notes().contains("by PuyoDead"), r.notes()::toString);
	}

	@Test
	void classicLevelWithAnyNameIsFoundByItsBytes() {
		WorldRecord r = foundAt("Dojo_64_64_128.dat");
		assertEquals("classic v2", r.format());
		assertTrue(r.notes().contains("size 64x128x64"), "width x depth x height, depth being vertical: " + r.notes());
	}

	@Test
	void uncompressedClassicLevelIsFoundToo(@TempDir Path tmp) throws IOException {
		byte[] inflated = Bytes.gunzip(Files.readAllBytes(resource("found/classic/Dojo_64_64_128.dat")), 1 << 24);
		Files.write(tmp.resolve("dojo"), inflated);
		Collector collector = new Collector();
		new Walker(collector, message -> {
		}).walk(tmp);
		WorldRecord r = Classifier.classify(collector).getFirst();
		assertEquals("classic v2, uncompressed", r.format());
	}

	@Test
	void indevLevelGivesNameAndCreationTime() {
		WorldRecord r = foundAt("indev/hell.mclevel");
		assertEquals("asdf", r.name());
		assertEquals(1_272_597_177_000L, r.lastPlayed());
		assertEquals("indev", foundAt("indev/synthetic.mclevel").format());
	}

	@Test
	void alphaWorldInATarballCountsItsChunkFiles() {
		WorldRecord r = foundAt("alpha.tar.gz!/alpha");
		assertEquals(Era.ALPHA_FORMAT, r.era());
		assertEquals(30L, r.totalChunks());
		assertNull(r.seed(), "pymclevel's alpha level.dat is a stub holding only Time");
		assertEquals(Pipeline.NEEDS_SEED, r.pipeline());
	}

	@Test
	void betaWorldIsMcRegionWithItsSeed() {
		WorldRecord r = foundAt("found/PyTestWorld");
		assertEquals(Era.MCREGION, r.era());
		assertEquals(5_906_491_493_417_788_160L, r.seed());
		assertEquals(10L, r.totalChunks());
		assertEquals(Pipeline.CLIENT_JAR, r.pipeline());
	}

	@Test
	void earlyAnvilWorldHasNoVersionTag() {
		WorldRecord r = foundAt("found/AnvilWorld");
		assertEquals(Era.ANVIL_UNTAGGED, r.era());
		assertEquals(6_906_647_851_317_977_573L, r.seed());
		assertEquals(10L, r.totalChunks());
	}

	@Test
	void classicWorldFormatGivesItsSize() {
		WorldRecord r = foundAt("cw/test.cw");
		assertEquals("classic server (ClassicWorld .cw)", r.format());
		assertTrue(r.notes().contains("size 128x64x128"), r.notes()::toString);
	}

	@Test
	void bedrockWorldsOldAndNewAreRecognised() {
		WorldRecord pocket = foundAt("found/PocketWorld");
		assertEquals("pocket legacy", pocket.format());
		assertEquals(Pipeline.OUT_OF_SCOPE, pocket.pipeline());

		WorldRecord bedrock = foundAt("example3.mcworld");
		assertEquals("bedrock leveldb", bedrock.format());
		assertEquals("Example 3 World", bedrock.name());
	}
}
