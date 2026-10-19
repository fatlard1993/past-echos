package justfatlard.past_echos.preprocess.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChunkTest {
	@Test
	void paletteEntriesOfEveryEraReadAsOneStateString() {
		assertEquals("stone", Chunk.state(Map.of("Name", "minecraft:stone")));
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("half", "top");
		props.put("facing", "east");
		assertEquals("oak_stairs[facing=east,half=top]", Chunk.state(Map.of("Name", "minecraft:oak_stairs", "Properties", props)));
		assertEquals("deepslate", Chunk.state("minecraft:deepslate"), "26.x bare string");
		assertEquals("leaf_litter[facing=south,segment_amount=2]",
				Chunk.state(Map.of("id", "minecraft:leaf_litter", "properties", Map.of("segment_amount", "2", "facing", "south"))));
		assertEquals("water", Chunk.state(Map.of("", "minecraft:water")), "element of a list mixing tag types");
	}

	@Test
	void statesWithoutBuildMeaningFold() {
		assertEquals("air", Chunk.state(Map.of("Name", "minecraft:cave_air")));
		assertEquals("oak_leaves[persistent=false,waterlogged=false]",
				Chunk.state(Map.of("Name", "minecraft:oak_leaves", "Properties", Map.of("distance", "3", "persistent", "false", "waterlogged", "false"))));
		assertEquals("birch_leaves", Chunk.state(Map.of("Name", "minecraft:birch_leaves", "Properties", Map.of("distance", "7"))));
	}

	@Test
	void mcRegionColumnsReadAsSections() throws IOException, URISyntaxException {
		Path regions = Path.of(ChunkTest.class.getResource("/found/PyTestWorld/region").toURI());
		Dimension d = Dimension.read(regions);
		assertEquals(10, d.size());
		Chunk chunk = RegionFile.read(regions.resolve("r.-1.-1.mcr")).chunks().getFirst();
		Map<Integer, Chunk.Section> sections = chunk.sections();
		assertTrue(sections.keySet().stream().allMatch(y -> y >= 0 && y < 8), "128 high: " + sections.keySet());
		// Every Beta chunk has bedrock at y 0.
		Chunk.Section bottom = sections.get(0);
		assertEquals(List.of("7:0"), java.util.stream.IntStream.range(0, 256).mapToObj(bottom::block).distinct().toList());
	}
}
