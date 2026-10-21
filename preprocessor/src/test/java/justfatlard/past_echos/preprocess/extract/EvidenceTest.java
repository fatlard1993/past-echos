package justfatlard.past_echos.preprocess.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import justfatlard.past_echos.preprocess.diff.DiffFile.Block;
import justfatlard.past_echos.preprocess.diff.DiffFile.ChunkDiff;
import justfatlard.past_echos.preprocess.extract.Vocabulary.Kind;
import org.junit.jupiter.api.Test;

class EvidenceTest {
	@Test
	void numericIdsAndNamesSortAlike() {
		assertEquals(Kind.GROUND, Vocabulary.kind("1:5"), "andesite");
		assertEquals(Kind.BUILT, Vocabulary.kind("1:6"), "polished andesite");
		assertEquals(Kind.GROUND, Vocabulary.kind("andesite"));
		assertEquals(Kind.BUILT, Vocabulary.kind("polished_andesite"));
		assertEquals(Kind.GROWTH, Vocabulary.kind("18:8"), "oak leaves, decay check pending");
		assertEquals(Kind.BUILT, Vocabulary.kind("18:4"), "oak leaves a player placed");
		assertEquals(Kind.GROWTH, Vocabulary.kind("oak_leaves[persistent=false,waterlogged=false]"));
		assertEquals(Kind.BUILT, Vocabulary.kind("oak_leaves[persistent=true,waterlogged=false]"));
		assertEquals(Kind.GROWTH, Vocabulary.kind("better-trees-justfatlard:pale_oak_leaf_stairs[distance=3,facing=west,persistent=false]"));
		assertEquals(Kind.BUILT, Vocabulary.kind("better-trees-justfatlard:pale_oak_leaf_stairs[distance=3,facing=west,persistent=true]"));
		assertEquals(Kind.GROUND, Vocabulary.kind("deepslate_diamond_ore"));
		assertEquals(Kind.GROWTH, Vocabulary.kind("birch_log[axis=y]"));
		assertEquals(Kind.STRUCTURE, Vocabulary.kind("66:1"), "rail");
		assertEquals(Kind.STRUCTURE, Vocabulary.kind("rail[shape=east_west,waterlogged=false]"));
		assertEquals(Kind.FLUID, Vocabulary.kind("9:3"));
		assertEquals(Kind.BUILT, Vocabulary.kind("159:3"), "light blue terracotta comes only from dye");
		assertEquals(Kind.GROUND, Vocabulary.kind("orange_terracotta"));
		assertEquals(Kind.BUILT, Vocabulary.kind("glowstone"));
	}

	@Test
	void regenerationNoiseWeighsNothing() {
		assertEquals(0, Evidence.plain().weight("oak_leaves[persistent=false]", "air"));
		assertEquals(0, Evidence.plain().weight("diorite", "andesite"));
		assertEquals(0, Evidence.plain().weight("1:0", "3:0"), "a dirt blob in stone");
		assertEquals(0, Evidence.plain().weight("stone", "water"), "a spring");
		assertEquals(0, Evidence.plain().weight("gravel", "air"), "gravel falls where caves cut");
		assertEquals(0, Evidence.plain().weight("air", "13:0"));
		assertEquals(0, Evidence.plain().weight("oak_fence[east=true]", "oak_fence[east=false]"));
		assertEquals(0, Evidence.plain().weight("sculk", "deepslate_bricks"), "sculk spread over an ancient city");
		assertEquals(0, Evidence.plain().weight("cracked_deepslate_tiles", "sculk_sensor[power=0,sculk_sensor_phase=inactive,waterlogged=false]"));
		assertEquals(0, Evidence.plain().weight("mossy_cobblestone", "deepslate[axis=y]"), "a dungeon only one generation placed");
	}

	/** 26.x's sulfur caves, unknown to the vocabulary, beside a player's bricks. */
	@Test
	void aDiffTeachesWhichUnknownBlocksItsGeneratorMakes() {
		List<Block> blocks = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			blocks.add(new Block(i, 0, 0, "sulfur", i % 2 == 0 ? "water" : "cinnabar"));
			blocks.add(new Block(i, 1, 0, i % 2 == 0 ? "air" : "cinnabar", "sulfur"));
			blocks.add(new Block(i, 2, 0, "air", "stone_bricks"));
		}
		// A player broke a few of a village's doors and glass, and hung more of their own.
		for (int i = 0; i < 20; i++) {
			blocks.add(new Block(i, 3, 0, "glass_pane", "air"));
			blocks.add(new Block(i, 4, 0, "air", "glass_pane"));
			blocks.add(new Block(i, 5, 0, "air", "glass_pane"));
			blocks.add(new Block(i, 6, 0, "air", "glass_pane"));
			blocks.add(new Block(i, 7, 0, "air", "glass_pane"));
			blocks.add(new Block(i, 8, 0, "air", "glass_pane"));
		}
		Evidence evidence = Evidence.of(List.of(new ChunkDiff(0, 0, blocks)));
		assertEquals(Set.of("sulfur", "cinnabar"), evidence.generated());
		assertEquals(0, evidence.weight("water", "sulfur"));
		assertEquals(Evidence.PLACED, evidence.weight("air", "stone_bricks"));
		assertEquals(Evidence.PLACED, evidence.weight("air", "glass_pane"), "a quarter or fewer on the reference side is the player's");
		assertEquals(Evidence.PLACED, Evidence.plain().weight("water", "sulfur"), "unknown blocks default to built");
	}

	/**
	 * A mod's worldgen block the vanilla regeneration never makes is on the saved side only, so
	 * looks placed; it is in about as many unvisited chunks as visited ones, and a player's block not.
	 */
	@Test
	void blocksUnvisitedChunksGainAreGenerated() {
		List<ChunkDiff> visited = new ArrayList<>();
		for (int c = 0; c < 10; c++) {
			List<Block> blocks = new ArrayList<>();
			blocks.add(new Block(c * 16, 70, 0, "oak_leaves", "mod:grass_slab"));
			for (int i = 0; i < 8; i++) {
				blocks.add(new Block(c * 16 + i, 64, 0, "grass_block", "ladder"));
			}
			visited.add(new ChunkDiff(c, 0, blocks));
		}
		// Of 100 unvisited chunks, 60 have the mod's slabs, and a village's ladders are in 30.
		List<ChunkDiff> unvisited = new ArrayList<>();
		for (int c = 0; c < 60; c++) {
			List<Block> blocks = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				blocks.add(new Block(c * 16 + i, 70, 0, "grass_block", "mod:grass_slab"));
			}
			if (c < 30) {
				for (int i = 0; i < 40; i++) {
					blocks.add(new Block(c * 16 + i % 16, 64 + i / 16, 0, "air", "ladder"));
				}
			}
			unvisited.add(new ChunkDiff(c, 1, blocks));
		}
		Evidence learned = Evidence.of(visited, 10, new Evidence.Unvisited(unvisited, 100));
		assertEquals(Set.of("mod:grass_slab"), learned.generated());
		assertEquals(0, learned.weight("grass_block", "mod:grass_slab"));
		assertEquals(Evidence.PLACED, learned.weight("air", "ladder"), "in 30% of unvisited chunks against all visited ones, however many");
		assertEquals(Evidence.PLACED, Evidence.of(visited).weight("grass_block", "mod:grass_slab"), "without the sample it looks placed");
	}

	/** Where the regenerating generator's villages stand in for a mod's, farmland is on both sides. */
	@Test
	void blocksCrowdedWhereVisitedOverruleTheReferenceSide() {
		List<ChunkDiff> visited = new ArrayList<>();
		for (int c = 0; c < 20; c++) {
			List<Block> blocks = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				blocks.add(new Block(c * 16 + i, 64, 0, "farmland[moisture=7]", "grass_block"));
				blocks.add(new Block(c * 16 + i, 64, 1, "grass_block", "farmland[moisture=7]"));
				blocks.add(new Block(c * 16 + i, 64, 2, "glass", "grass_block"));
				blocks.add(new Block(c * 16 + i, 64, 3, "grass_block", "glass"));
			}
			visited.add(new ChunkDiff(c, 0, blocks));
		}
		// Of 100 unvisited chunks, against 20 of 20 visited ones, farmland is in 2 and glass in 60.
		List<ChunkDiff> unvisited = new ArrayList<>();
		for (int c = 0; c < 60; c++) {
			List<Block> blocks = new ArrayList<>();
			blocks.add(new Block(c * 16, 64, 0, "grass_block", "glass"));
			if (c < 2) {
				blocks.add(new Block(c * 16, 64, 1, "grass_block", "farmland[moisture=7]"));
			}
			unvisited.add(new ChunkDiff(c, 1, blocks));
		}
		assertEquals(Set.of("farmland", "glass"), Evidence.of(visited).generated(), "both sides alike");
		Evidence learned = Evidence.of(visited, 20, new Evidence.Unvisited(unvisited, 100));
		assertEquals(Set.of("glass"), learned.generated());
		assertEquals(Evidence.PLACED, learned.weight("grass_block", "farmland[moisture=7]"));
		List<ChunkDiff> small = unvisited.subList(0, 10);
		assertEquals(Set.of("farmland", "glass"), Evidence.of(visited, 20, new Evidence.Unvisited(small, 10)).generated(), "too few sampled to judge");
	}

	/** An ocean ruin crumbles at random, so regeneration loses some bricks and gains others. */
	@Test
	void blocksAClusterBothLosesAndGainsAreNoEvidence() {
		List<Block> blocks = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			blocks.add(new Block(i, 0, 0, "water[level=0]", i % 2 == 0 ? "mossy_stone_bricks" : "cracked_stone_bricks"));
			blocks.add(new Block(i, 1, 0, i % 2 == 0 ? "cracked_stone_bricks" : "mossy_stone_bricks", "water[level=0]"));
			blocks.add(new Block(i, 2, 0, "water[level=0]", "glass"));
		}
		Clusters.Cluster c = Clusters.find(List.of(new ChunkDiff(0, 0, blocks))).getFirst();
		assertEquals(18, c.blocks().size(), "all of it is the cluster's extent");
		assertEquals(6, c.placed(), "only the glass counts");
		assertEquals(6.0, c.score());
	}

	@Test
	void playersLeaveEvidence() {
		assertEquals(Evidence.PLACED, Evidence.plain().weight("air", "stone_bricks"));
		assertEquals(Evidence.PLACED, Evidence.plain().weight("18:0", "20:0"), "glass where leaves were");
		assertEquals(Evidence.STRUCTURE, Evidence.plain().weight("2:0", "4:0"), "cobblestone over grass");
		assertEquals(Evidence.REMOVED, Evidence.plain().weight("chiseled_sandstone", "air"), "a desert temple dug into");
		assertEquals(Evidence.EARTHWORK, Evidence.plain().weight("stone", "air"));
		assertEquals(Evidence.EARTHWORK, Evidence.plain().weight("air", "3:0"));
		assertEquals(Evidence.RESTATED, Evidence.plain().weight("oak_door[open=false]", "oak_door[open=true]"));
	}
}
