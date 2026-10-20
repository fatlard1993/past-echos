package justfatlard.past_echos.echo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import justfatlard.past_echos.preprocess.diff.Diff;
import justfatlard.past_echos.preprocess.diff.DiffFile;
import justfatlard.past_echos.preprocess.extract.EchoFile.Echo;
import justfatlard.past_echos.preprocess.extract.Extract;
import justfatlard.past_echos.preprocess.regen.Json;
import justfatlard.past_echos.preprocess.world.Dimension;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The preprocessor's test hut, extracted from each era's built world and made a ruin: whatever
 * version saved it, it comes out as the same modern blocks with its sign still legible.
 */
class RuinTest {
	private static final Path FIXTURES = Path.of(System.getProperty("pastechos.fixtures"));
	/** fixtures/build.py's parts, in each era's spelling, as the running version's blocks. */
	private static Map<String, Block> parts;

	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		parts = Map.ofEntries(Map.entry("4:0", Blocks.COBBLESTONE), Map.entry("cobblestone", Blocks.COBBLESTONE),
				Map.entry("5:0", Blocks.OAK_PLANKS), Map.entry("oak_planks", Blocks.OAK_PLANKS), Map.entry("20:0", Blocks.GLASS),
				Map.entry("glass", Blocks.GLASS), Map.entry("89:0", Blocks.GLOWSTONE), Map.entry("glowstone", Blocks.GLOWSTONE),
				Map.entry("98:0", Blocks.STONE_BRICKS), Map.entry("stone_bricks", Blocks.STONE_BRICKS), Map.entry("68:2", Blocks.OAK_WALL_SIGN),
				Map.entry("oak_wall_sign", Blocks.OAK_WALL_SIGN));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"1.2.5", "1.6.4", "1.8.9", "1.12.2", "1.16.5", "1.18.2", "1.20.4", "26.3"})
	@SuppressWarnings("unchecked")
	void theHutComesUpToDate(String version) throws IOException {
		try (FileSystem built = FileSystems.newFileSystem(FIXTURES.resolve("worlds/" + version + "-built.zip"))) {
			Path regions = Dimension.overworldRegions(Diff.findWorld(built.getPath("/")));
			Echo echo = Extract.run(DiffFile.read(FIXTURES.resolve("diffs/" + version + "-built.diff.gz")), regions).getFirst();
			Ruin ruin = Ruin.of("hut", echo).orElseThrow();

			Map<String, Object> build = (Map<String, Object>) Json.parse(Files.readString(built.getPath("/build.json")));
			Map<Block, Integer> expected = new HashMap<>();
			int floor = Integer.MAX_VALUE;
			for (Map<String, Object> block : (List<Map<String, Object>>) build.get("blocks")) {
				String after = (String) block.get("after");
				String name = after.contains("[") ? after.substring(0, after.indexOf('[')) : after;
				if (!name.equals("air")) {
					expected.merge(parts.get(name), 1, Integer::sum);
					floor = Math.min(floor, ((Number) block.get("y")).intValue());
				}
			}
			assertEquals(floor - echo.y(), ruin.ground(), "the hut's floor sits where the ground was");

			StructurePlaceSettings settings = new StructurePlaceSettings();
			for (Map.Entry<Block, Integer> part : expected.entrySet()) {
				assertEquals(part.getValue(), ruin.template().filterBlocks(BlockPos.ZERO, settings, part.getKey()).size(), part.getKey().toString());
			}

			List<StructureBlockInfo> signs = ruin.template().filterBlocks(BlockPos.ZERO, settings, Blocks.OAK_WALL_SIGN);
			StructureBlockInfo sign = signs.getFirst();
			assertEquals(Direction.NORTH, sign.state().getValue(WallSignBlock.FACING));
			assertTrue(sign.nbt() != null, "the sign keeps its block entity");
			Tag first = sign.nbt().getCompoundOrEmpty("front_text").getListOrEmpty("messages").get(0);
			assertEquals("past echoes", ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, first).getOrThrow().getString(), sign.nbt().toString());
		}
	}
}
