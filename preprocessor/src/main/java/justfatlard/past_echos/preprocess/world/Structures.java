package justfatlard.past_echos.preprocess.world;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The structure pieces a world records ({@link Chunk#structurePieces}), looked up by position:
 * where a block is the generator's however it looks.
 */
public final class Structures {
	public static final Structures NONE = new Structures(List.of());

	/**
	 * One piece of a structure, its box as minX, minY, minZ, maxX, maxY, maxZ.
	 *
	 * @param structure the structure's id: "minecraft:village_plains", "Mineshaft" before 1.14
	 */
	public record Piece(String structure, int[] box) {
		/** Whether the game's own generator places it, and so a regeneration places it again; a mod's it doesn't. */
		public boolean regenerates() {
			return !structure.contains(":") || structure.startsWith("minecraft:");
		}

		boolean contains(int x, int y, int z) {
			return x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4] && z >= box[2] && z <= box[5];
		}
	}

	private final List<Piece> pieces;
	private final Map<Long, List<Piece>> byChunk = new HashMap<>();

	public Structures(List<Piece> pieces) {
		this.pieces = pieces;
		for (Piece p : pieces) {
			for (int cx = p.box[0] >> 4; cx <= p.box[3] >> 4; cx++) {
				for (int cz = p.box[2] >> 4; cz <= p.box[5] >> 4; cz++) {
					byChunk.computeIfAbsent(Dimension.key(cx, cz), k -> new ArrayList<>()).add(p);
				}
			}
		}
	}

	/** Whether any recorded piece holds the block. */
	public boolean contains(int x, int y, int z) {
		return any(x, y, z, p -> true);
	}

	/** Whether a piece of a structure the regeneration places too holds the block. */
	public boolean regenerated(int x, int y, int z) {
		return any(x, y, z, Piece::regenerates);
	}

	private boolean any(int x, int y, int z, Predicate<Piece> which) {
		for (Piece p : byChunk.getOrDefault(Dimension.key(x >> 4, z >> 4), List.of())) {
			if (which.test(p) && p.contains(x, y, z)) {
				return true;
			}
		}
		return false;
	}

	public int size() {
		return pieces.size();
	}

	public void write(Path file) throws IOException {
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file))))) {
			out.writeInt(pieces.size());
			for (Piece p : pieces) {
				out.writeUTF(p.structure);
				for (int v : p.box) {
					out.writeInt(v);
				}
			}
		}
	}

	/** The pieces a {@link #write} left, none when there is no file. */
	public static Structures read(Path file) throws IOException {
		if (!Files.exists(file)) {
			return NONE;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
			int n = in.readInt();
			List<Piece> pieces = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				String structure = in.readUTF();
				int[] box = new int[6];
				for (int j = 0; j < 6; j++) {
					box[j] = in.readInt();
				}
				pieces.add(new Piece(structure, box));
			}
			return new Structures(pieces);
		}
	}
}
