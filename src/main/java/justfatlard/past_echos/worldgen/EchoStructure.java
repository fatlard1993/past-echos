package justfatlard.past_echos.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.OptionalInt;
import justfatlard.past_echos.echo.Library;
import justfatlard.past_echos.echo.Ruin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;

/**
 * A ruin from the library, on dry ground that is near enough level under it (see {@link Siting}). The ruin is chosen
 * and turned by the structure's own random, so a seed puts the same ruins in the same places for
 * as long as the library holds them.
 */
public class EchoStructure extends Structure {
	public static final MapCodec<EchoStructure> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Structure.settingsCodec(instance),
			StructureProcessorType.LIST_CODEC.fieldOf("processors").forGetter(s -> s.processors),
			Codec.intRange(0, 64).optionalFieldOf("max_slope", 6).forGetter(s -> s.maxSlope),
			Codec.intRange(0, 16).optionalFieldOf("slope_per_chunk", 2).forGetter(s -> s.slopePerChunk)
	).apply(instance, EchoStructure::new));

	/**
	 * How far a ruin may reach from its start chunk: chunks look 8 chunks out for the structures
	 * they hold a part of, and a ruin is centred on its start.
	 */
	public static final int MAX_SPAN = 8 * 16 * 2 - 16;

	private final Holder<StructureProcessorList> processors;
	private final int maxSlope;
	private final int slopePerChunk;

	public EchoStructure(StructureSettings settings, Holder<StructureProcessorList> processors, int maxSlope, int slopePerChunk) {
		super(settings);
		this.processors = processors;
		this.maxSlope = maxSlope;
		this.slopePerChunk = slopePerChunk;
	}

	@Override
	protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
		Library library = Library.current();
		if (library.isEmpty()) {
			return Optional.empty();
		}
		Ruin ruin = library.pick(context.random());
		Rotation rotation = Rotation.getRandom(context.random());
		Vec3i size = ruin.template().getSize(rotation);
		if (size.getX() > MAX_SPAN || size.getZ() > MAX_SPAN) {
			return Optional.empty();
		}

		int x0 = context.chunkPos().getMiddleBlockX() - size.getX() / 2;
		int z0 = context.chunkPos().getMiddleBlockZ() - size.getZ() / 2;
		int x1 = x0 + size.getX() - 1;
		int z1 = z0 + size.getZ() - 1;
		int cx = (x0 + x1) / 2;
		int cz = (z0 + z1) / 2;
		OptionalInt ground = Siting.ground(x0, z0, x1, z1, new Siting.Ground() {
			@Override
			public int top(int x, int z) {
				return EchoStructure.top(context, x, z);
			}

			@Override
			public boolean wet(int x, int z) {
				return !context.chunkGenerator().getBaseColumn(x, z, context.heightAccessor(), context.randomState())
						.getBlock(top(x, z)).getFluidState().isEmpty();
			}
		}, maxSlope, slopePerChunk);
		if (ground.isEmpty()) {
			return Optional.empty();
		}

		// The template's corner, turned about itself, lands at (x0, z0) and its old ground on the new.
		BoundingBox turned = ruin.template().getBoundingBox(BlockPos.ZERO, rotation, BlockPos.ZERO, Mirror.NONE);
		BlockPos position = new BlockPos(x0 - turned.minX(), ground.getAsInt() - ruin.ground(), z0 - turned.minZ());
		String weathering = processors.unwrapKey().map(k -> k.identifier().toString()).orElse("");
		return Optional.of(new GenerationStub(new BlockPos(cx, ground.getAsInt(), cz),
				builder -> builder.addPiece(new EchoPiece(ruin, position, rotation, weathering))));
	}

	private static int top(GenerationContext context, int x, int z) {
		return context.chunkGenerator().getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(),
				context.randomState());
	}

	@Override
	public StructureType<?> type() {
		return EchoStructures.ECHO;
	}
}
