package justfatlard.past_echos.worldgen;

import java.util.Optional;
import justfatlard.past_echos.PastEchos;
import justfatlard.past_echos.echo.Library;
import justfatlard.past_echos.echo.Ruin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

/**
 * One ruin, written a chunk at a time. The bounding box starts at the old ground, so terrain
 * adaptation levels the land to it; anything the player dug below still goes in.
 */
public class EchoPiece extends StructurePiece {
	private static final String TAG_ECHO = "Echo";
	private static final String TAG_X = "X";
	private static final String TAG_Y = "Y";
	private static final String TAG_Z = "Z";
	private static final String TAG_ROTATION = "Rotation";
	private static final String TAG_WEATHERING = "Weathering";

	private final String echo;
	private final BlockPos position;
	private final Rotation rotation;
	private final String weathering;

	public EchoPiece(Ruin ruin, BlockPos position, Rotation rotation, String weathering) {
		super(EchoStructures.ECHO_PIECE, 0, bounds(ruin, position, rotation));
		this.echo = ruin.id();
		this.position = position;
		this.rotation = rotation;
		this.weathering = weathering;
	}

	public EchoPiece(CompoundTag tag) {
		super(EchoStructures.ECHO_PIECE, tag);
		this.echo = tag.getStringOr(TAG_ECHO, "");
		this.position = new BlockPos(tag.getIntOr(TAG_X, 0), tag.getIntOr(TAG_Y, 0), tag.getIntOr(TAG_Z, 0));
		this.rotation = Rotation.values()[Math.floorMod(tag.getIntOr(TAG_ROTATION, 0), 4)];
		this.weathering = tag.getStringOr(TAG_WEATHERING, "");
	}

	private static BoundingBox bounds(Ruin ruin, BlockPos position, Rotation rotation) {
		BoundingBox box = ruin.template().getBoundingBox(position, rotation, BlockPos.ZERO, Mirror.NONE);
		return new BoundingBox(box.minX(), position.getY() + ruin.ground(), box.minZ(), box.maxX(), box.maxY(),
				box.maxZ());
	}

	@Override
	protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
		tag.putString(TAG_ECHO, echo);
		tag.putInt(TAG_X, position.getX());
		tag.putInt(TAG_Y, position.getY());
		tag.putInt(TAG_Z, position.getZ());
		tag.putInt(TAG_ROTATION, rotation.ordinal());
		tag.putString(TAG_WEATHERING, weathering);
	}

	@Override
	public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
			BoundingBox chunkBox, ChunkPos chunkPos, BlockPos pivot) {
		Optional<Ruin> ruin = Library.current().get(echo);
		if (ruin.isEmpty()) {
			return;
		}
		StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation).setBoundingBox(chunkBox);
		Identifier id = Identifier.tryParse(weathering);
		if (id != null) {
			level.registryAccess().lookupOrThrow(Registries.PROCESSOR_LIST).get(ResourceKey.create(Registries.PROCESSOR_LIST, id))
					.map(h -> h.value().list()).ifPresentOrElse(list -> list.forEach(settings::addProcessor),
							() -> PastEchos.LOGGER.warn("No processor list {} to weather echo {} with", weathering, echo));
		}
		ruin.get().template().placeInWorld(level, position, pivot, settings, random, Block.UPDATE_CLIENTS);
	}
}
