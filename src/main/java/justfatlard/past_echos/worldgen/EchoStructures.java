package justfatlard.past_echos.worldgen;

import justfatlard.past_echos.PastEchos;
import net.fabricmc.fabric.api.event.registry.RegistryAttribute;
import net.fabricmc.fabric.api.event.registry.RegistryAttributeHolder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;

/**
 * The structure and piece types. Where echoes go, how often, and how they weather are datapack
 * files under {@code data/past-echos-justfatlard/worldgen/}.
 */
public final class EchoStructures {
	public static StructureType<EchoStructure> ECHO;
	public static StructurePieceType ECHO_PIECE;

	private EchoStructures() {
	}

	public static void register() {
		ECHO_PIECE = Registry.register(BuiltInRegistries.STRUCTURE_PIECE, Identifier.fromNamespaceAndPath(PastEchos.MOD_ID, "echo"),
				(context, tag) -> new EchoPiece(tag));
		ECHO = Registry.register(BuiltInRegistries.STRUCTURE_TYPE, Identifier.fromNamespaceAndPath(PastEchos.MOD_ID, "echo"),
				() -> EchoStructure.CODEC);

		// Optional, so a client without the mod is not dropped at registry sync: ruins are vanilla blocks.
		RegistryAttributeHolder.get(BuiltInRegistries.STRUCTURE_TYPE).addAttribute(RegistryAttribute.OPTIONAL);
		RegistryAttributeHolder.get(BuiltInRegistries.STRUCTURE_PIECE).addAttribute(RegistryAttribute.OPTIONAL);
	}
}
