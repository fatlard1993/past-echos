package justfatlard.past_echos.preprocess.extract;

import java.util.Set;

/**
 * What kind of thing a block state is, for telling the generator's work from a player's. Takes
 * states as {@link justfatlard.past_echos.preprocess.world.Chunk} reads them: "id:meta" up to
 * 1.12, names without the minecraft: prefix after.
 */
public final class Vocabulary {
	public enum Kind {
		AIR,
		/** Water and lava, which flow and settle differently on every generation. */
		FLUID,
		/** Rock, soil, sand and ores: terrain and the blobs and veins inside it. */
		GROUND,
		/** Trees, plants, snow cover and anything else that grows or settles on the terrain. */
		GROWTH,
		/** Blocks generated structures place that players use too: mineshafts, dungeons. */
		STRUCTURE,
		/** Everything the generator never places loose in the overworld. */
		BUILT
	}

	private static final Set<Integer> NUMERIC_FLUID = Set.of(8, 9, 10, 11);
	private static final Set<Integer> NUMERIC_GROUND = Set.of(
			2, 7, 13, 14, 15, 16, 21, 49, 56, 73, 74, 79, 80, 82, 87, 88, 97, 110, 121, 129, 153, 172, 174, 213);
	private static final Set<Integer> NUMERIC_GROWTH = Set.of(
			6, 17, 31, 32, 37, 38, 39, 40, 51, 59, 78, 81, 83, 86, 99, 100, 103, 104, 105, 106, 111, 115, 127, 141, 142, 162, 175, 207);
	private static final Set<Integer> NUMERIC_STRUCTURE = Set.of(4, 5, 30, 48, 50, 52, 54, 66, 85);
	private static final int STONE = 1;
	private static final int DIRT = 3;
	private static final int SAND = 12;
	private static final int LEAVES = 18;
	private static final int LEAVES2 = 161;
	private static final int SANDSTONE = 24;
	private static final int RED_SANDSTONE = 179;
	private static final int STAINED_CLAY = 159;
	/** Leaves meta bit for "never decays", set on leaves a player placed. */
	private static final int NO_DECAY = 4;
	/** Badlands terracotta colours; the other colours only come from dye. */
	private static final Set<Integer> BADLANDS_CLAY = Set.of(0, 1, 4, 8, 12, 14);

	private static final Set<String> FLUID = Set.of("water", "lava", "bubble_column");
	private static final Set<String> GROUND = Set.of(
			"stone", "granite", "diorite", "andesite", "deepslate", "tuff", "calcite", "dripstone_block", "smooth_basalt",
			"bedrock", "dirt", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "grass_block", "mud", "clay", "gravel",
			"sand", "red_sand", "suspicious_sand", "suspicious_gravel", "sandstone", "red_sandstone", "terracotta",
			"white_terracotta", "orange_terracotta", "yellow_terracotta", "brown_terracotta", "red_terracotta",
			"light_gray_terracotta", "snow_block", "powder_snow", "ice", "packed_ice", "blue_ice", "obsidian", "magma_block",
			"moss_block", "pale_moss_block", "amethyst_block", "budding_amethyst", "raw_iron_block", "raw_copper_block",
			"netherrack", "soul_sand", "soul_soil", "basalt", "blackstone", "crimson_nylium", "warped_nylium", "end_stone");
	private static final Set<String> GROWTH = Set.of(
			"vine", "cave_vines", "cave_vines_plant", "weeping_vines", "weeping_vines_plant", "twisting_vines",
			"twisting_vines_plant", "glow_lichen", "grass", "short_grass", "tall_grass", "fern", "large_fern", "dead_bush",
			"dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip",
			"pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley", "torchflower", "sunflower", "lilac", "rose_bush",
			"peony", "pink_petals", "wildflowers", "leaf_litter", "bush", "firefly_bush", "cactus_flower", "short_dry_grass",
			"tall_dry_grass", "sugar_cane", "cactus", "bamboo", "bamboo_sapling", "kelp", "kelp_plant", "seagrass",
			"tall_seagrass", "sea_pickle", "lily_pad", "cocoa", "pumpkin", "melon", "sweet_berry_bush", "azalea",
			"flowering_azalea", "moss_carpet", "pale_moss_carpet", "pale_hanging_moss", "open_eyeblossom", "closed_eyeblossom",
			"creaking_heart", "resin_clump", "big_dripleaf", "big_dripleaf_stem", "small_dripleaf", "spore_blossom",
			"hanging_roots", "pointed_dripstone", "small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud",
			"amethyst_cluster", "snow", "brown_mushroom", "red_mushroom", "brown_mushroom_block", "red_mushroom_block",
			"mushroom_stem", "bee_nest", "mangrove_propagule", "mangrove_roots", "muddy_mangrove_roots", "fire", "wheat", "carrots", "potatoes", "beetroots", "pumpkin_stem", "melon_stem", "attached_pumpkin_stem",
			"attached_melon_stem", "frogspawn", "crimson_fungus", "warped_fungus", "crimson_roots", "warped_roots",
			"nether_sprouts", "shroomlight", "nether_wart", "nether_wart_block", "warped_wart_block");
	private static final Set<String> STRUCTURE = Set.of(
			"cobblestone", "mossy_cobblestone", "oak_planks", "oak_fence", "rail", "cobweb", "torch", "wall_torch", "spawner",
			"chest");

	/** The deep dark's sculk, which spreads over whatever is there, ancient city floors included. */
	private static final Set<String> SPREADING = Set.of("sculk", "sculk_vein", "sculk_sensor", "sculk_catalyst", "sculk_shrieker");
	private static final Set<Integer> NUMERIC_FALLING = Set.of(12, 13);
	private static final Set<String> FALLING = Set.of("sand", "red_sand", "gravel", "suspicious_sand", "suspicious_gravel");

	private Vocabulary() {
	}

	/** Growth that covers other blocks where it spreads: every generation spreads it differently. */
	public static boolean spreads(String state) {
		int bracket = state.indexOf('[');
		return SPREADING.contains(bracket < 0 ? state : state.substring(0, bracket));
	}

	/** Ground that falls when undermined, so moves on its own wherever caves cut differently. */
	public static boolean falls(String state) {
		int colon = state.indexOf(':');
		if (colon > 0 && Character.isDigit(state.charAt(0))) {
			return NUMERIC_FALLING.contains(Integer.parseInt(state.substring(0, colon)));
		}
		int bracket = state.indexOf('[');
		return FALLING.contains(bracket < 0 ? state : state.substring(0, bracket));
	}

	public static Kind kind(String state) {
		int colon = state.indexOf(':');
		if (colon > 0 && Character.isDigit(state.charAt(0))) {
			return numeric(Integer.parseInt(state.substring(0, colon)), Integer.parseInt(state.substring(colon + 1)));
		}
		int bracket = state.indexOf('[');
		String name = bracket < 0 ? state : state.substring(0, bracket);
		if (name.equals("air")) {
			return Kind.AIR;
		}
		if (FLUID.contains(name)) {
			return Kind.FLUID;
		}
		if (GROUND.contains(name) || name.endsWith("_ore") || name.startsWith("infested_")) {
			return Kind.GROUND;
		}
		if (name.endsWith("_leaves")) {
			return state.contains("persistent=true") ? Kind.BUILT : Kind.GROWTH;
		}
		if (GROWTH.contains(name) || SPREADING.contains(name) || name.endsWith("_log") || name.endsWith("_sapling") || name.endsWith("_stem")
				|| name.contains("coral")) {
			return Kind.GROWTH;
		}
		return STRUCTURE.contains(name) ? Kind.STRUCTURE : Kind.BUILT;
	}

	private static Kind numeric(int id, int meta) {
		if (id == 0) {
			return Kind.AIR;
		}
		if (NUMERIC_FLUID.contains(id)) {
			return Kind.FLUID;
		}
		if (id == LEAVES || id == LEAVES2) {
			return (meta & NO_DECAY) != 0 ? Kind.BUILT : Kind.GROWTH;
		}
		boolean ground = switch (id) {
			// Granite, diorite and andesite are natural; their polished forms (metas 2, 4, 6) are not.
			case STONE -> meta % 2 == 1 || meta == 0;
			case DIRT -> meta <= 2;
			case SAND -> true;
			case SANDSTONE, RED_SANDSTONE -> meta == 0;
			case STAINED_CLAY -> BADLANDS_CLAY.contains(meta);
			default -> NUMERIC_GROUND.contains(id);
		};
		if (ground) {
			return Kind.GROUND;
		}
		if (NUMERIC_GROWTH.contains(id)) {
			return Kind.GROWTH;
		}
		return NUMERIC_STRUCTURE.contains(id) ? Kind.STRUCTURE : Kind.BUILT;
	}
}
