package justfatlard.past_echos.echo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import justfatlard.past_echos.preprocess.extract.EchoFile;
import justfatlard.past_echos.preprocess.extract.EchoFile.Echo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * An echo made placeable: the player's work alone as a structure template, upgraded to the
 * running version, and the height in it where the build met the ground.
 *
 * @param ground the template y that goes where the new ground's top block is
 */
public record Ruin(String id, StructureTemplate template, int ground, int blocks) {
	/** An echo with nothing placed in it was dug, not built, and has no ruin yet. */
	public static Optional<Ruin> of(String id, Echo echo) {
		BlockState[] palette = Arrays.stream(echo.palette()).map(s -> Upgrade.state(s, echo.dataVersion())).toArray(BlockState[]::new);
		Integer ground = ground(echo, palette);
		if (ground == null) {
			return Optional.empty();
		}

		Map<Integer, CompoundTag> entities = new HashMap<>();
		for (Map<String, Object> saved : echo.blockEntities()) {
			CompoundTag tag = Upgrade.blockEntity(saved, echo.dataVersion());
			int x = tag.getIntOr("x", 0);
			int y = tag.getIntOr("y", 0);
			int z = tag.getIntOr("z", 0);
			tag.remove("x");
			tag.remove("y");
			tag.remove("z");
			// A player's chest is theirs, not loot for whoever finds the ruin.
			tag.remove("Items");
			entities.put(echo.index(x, y, z), tag);
		}

		Map<BlockState, Integer> used = new LinkedHashMap<>();
		ListTag blocks = new ListTag();
		for (int y = 0; y < echo.sizeY(); y++) {
			for (int z = 0; z < echo.sizeZ(); z++) {
				for (int x = 0; x < echo.sizeX(); x++) {
					int i = echo.index(x, y, z);
					if (echo.marks()[i] != EchoFile.EVIDENCE) {
						continue;
					}
					BlockState state = palette[echo.states()[i]];
					CompoundTag block = new CompoundTag();
					block.put("pos", position(x, y, z));
					block.putInt("state", used.computeIfAbsent(state, s -> used.size()));
					CompoundTag entity = entities.get(i);
					if (entity != null && state.hasBlockEntity()) {
						block.put("nbt", entity);
					}
					blocks.add(block);
				}
			}
		}
		ListTag states = new ListTag();
		used.keySet().forEach(s -> states.add(NbtUtils.writeBlockState(s)));
		CompoundTag structure = new CompoundTag();
		structure.put("size", position(echo.sizeX(), echo.sizeY(), echo.sizeZ()));
		structure.put("palette", states);
		structure.put("blocks", blocks);
		structure.put("entities", new ListTag());
		StructureTemplate template = new StructureTemplate();
		template.load(BuiltInRegistries.BLOCK, structure);
		return Optional.of(new Ruin(id, template, ground, blocks.size()));
	}

	/**
	 * Where the build meets the ground: the median, over the columns the player built in, of the
	 * lowest block they placed there. A hut's floor; a cellar under part of a house doesn't move it.
	 * Null when nothing was placed, only dug.
	 */
	private static Integer ground(Echo echo, BlockState[] palette) {
		List<Integer> bottoms = new ArrayList<>();
		for (int z = 0; z < echo.sizeZ(); z++) {
			for (int x = 0; x < echo.sizeX(); x++) {
				for (int y = 0; y < echo.sizeY(); y++) {
					int i = echo.index(x, y, z);
					if (echo.marks()[i] == EchoFile.EVIDENCE && !palette[echo.states()[i]].isAir()) {
						bottoms.add(y);
						break;
					}
				}
			}
		}
		return bottoms.isEmpty() ? null : bottoms.stream().sorted().toList().get(bottoms.size() / 2);
	}

	private static ListTag position(int x, int y, int z) {
		ListTag list = new ListTag();
		list.add(IntTag.valueOf(x));
		list.add(IntTag.valueOf(y));
		list.add(IntTag.valueOf(z));
		return list;
	}
}
