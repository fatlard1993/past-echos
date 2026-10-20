package justfatlard.past_echos.echo;

import com.mojang.datafixers.DSL;
import com.mojang.serialization.Dynamic;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import justfatlard.past_echos.preprocess.extract.EchoFile;
import justfatlard.past_echos.preprocess.world.Nbt;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Brings an echo's blocks from the version that saved them to the running one, through the
 * game's own data fixers: the same upgrade an old world gets when it is opened.
 */
public final class Upgrade {
	private static final Pattern NUMERIC = Pattern.compile("(\\d+):(\\d+)");
	/** The data version {@link BlockStateData} flattens numeric ids into, 17w47a's. */
	private static final int FLATTENED = 1451;
	/** The data version the fixers start from, standing in for saves from before 1.9 that record none. */
	private static final int UNVERSIONED = 99;
	/** A state's Name and Properties became id and properties here, in 26.1's snapshots. */
	private static final int LOWERCASE_FIELDS = 5006;

	private Upgrade() {
	}

	/**
	 * A state as the preprocessor reads it: "id:meta" to 1.12, "name[key=value,...]" after, the
	 * minecraft: namespace left off.
	 */
	public static BlockState state(String saved, int dataVersion) {
		Matcher numeric = NUMERIC.matcher(saved);
		Dynamic<Tag> tag;
		int from;
		if (numeric.matches()) {
			tag = BlockStateData.getTag(Integer.parseInt(numeric.group(1)) << 4 | Integer.parseInt(numeric.group(2))).convert(NbtOps.INSTANCE);
			from = FLATTENED;
		} else {
			from = Math.max(dataVersion, FLATTENED);
			tag = new Dynamic<>(NbtOps.INSTANCE, named(saved, from >= LOWERCASE_FIELDS));
		}
		return NbtUtils.readBlockState(BuiltInRegistries.BLOCK, (CompoundTag) fix(References.BLOCK_STATE, tag, from).getValue());
	}

	/** A block entity as its chunk saved it, upgraded. */
	public static CompoundTag blockEntity(Map<String, Object> saved, int dataVersion) {
		CompoundTag tag;
		try {
			tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(Nbt.write(saved))));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		int from = dataVersion == EchoFile.NO_DATA_VERSION ? UNVERSIONED : dataVersion;
		return (CompoundTag) fix(References.BLOCK_ENTITY, new Dynamic<>(NbtOps.INSTANCE, tag), from).getValue();
	}

	private static Dynamic<Tag> fix(DSL.TypeReference type, Dynamic<Tag> input, int from) {
		int to = SharedConstants.getCurrentVersion().dataVersion().version();
		return from >= to ? input : DataFixers.getDataFixer().update(type, input, from, to);
	}

	private static CompoundTag named(String state, boolean lowercase) {
		int bracket = state.indexOf('[');
		String name = bracket < 0 ? state : state.substring(0, bracket);
		CompoundTag tag = new CompoundTag();
		tag.putString(lowercase ? "id" : "Name", name.contains(":") ? name : "minecraft:" + name);
		if (bracket >= 0) {
			CompoundTag properties = new CompoundTag();
			for (String pair : state.substring(bracket + 1, state.length() - 1).split(",")) {
				int eq = pair.indexOf('=');
				if (eq > 0) {
					properties.putString(pair.substring(0, eq), pair.substring(eq + 1));
				}
			}
			tag.put(lowercase ? "properties" : "Properties", properties);
		}
		return tag;
	}
}
