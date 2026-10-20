package justfatlard.past_echos.preprocess.extract;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import justfatlard.past_echos.preprocess.world.Nbt;

/**
 * Echoes cut from one world, gzipped: "PEECHO" and a format byte, the version whose block states
 * they use, then per echo its corner, size, score, palette, each position's state and mark in YZX
 * order, and its block entities as NBT.
 */
public final class EchoFile {
	private static final byte[] MAGIC = {'P', 'E', 'E', 'C', 'H', 'O'};
	private static final int FORMAT = 1;

	/** The saved block matches the regenerated one: terrain the build stood on or in. */
	public static final byte TERRAIN = 0;
	/** The saved block differs, but only as regeneration noise would: a tree, a patch of ore. */
	public static final byte CHANGED = 1;
	/** The saved block is a player's work. */
	public static final byte EVIDENCE = 2;

	/**
	 * A box of the saved world, its corner at x, y, z. Positions index as (y * sizeZ + z) * sizeX + x.
	 * Block entities are as the version saved them, but with x, y and z from the corner.
	 */
	public record Echo(int x, int y, int z, int sizeX, int sizeY, int sizeZ, double score, String[] palette, int[] states, byte[] marks,
			List<Map<String, Object>> blockEntities) {
		public int index(int dx, int dy, int dz) {
			return (dy * sizeZ + dz) * sizeX + dx;
		}

		public String state(int dx, int dy, int dz) {
			return palette[states[index(dx, dy, dz)]];
		}

		public byte mark(int dx, int dy, int dz) {
			return marks[index(dx, dy, dz)];
		}

		public boolean contains(int wx, int wy, int wz) {
			return wx >= x && wx < x + sizeX && wy >= y && wy < y + sizeY && wz >= z && wz < z + sizeZ;
		}

		public int count(byte mark) {
			int n = 0;
			for (byte m : marks) {
				n += m == mark ? 1 : 0;
			}
			return n;
		}
	}

	public record Contents(String version, List<Echo> echoes) {
	}

	private EchoFile() {
	}

	public static void write(Path file, String version, List<Echo> echoes) throws IOException {
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file))))) {
			out.write(MAGIC);
			out.writeByte(FORMAT);
			out.writeUTF(version);
			varint(out, echoes.size());
			for (Echo e : echoes) {
				out.writeInt(e.x);
				out.writeInt(e.y);
				out.writeInt(e.z);
				varint(out, e.sizeX);
				varint(out, e.sizeY);
				varint(out, e.sizeZ);
				out.writeDouble(e.score);
				varint(out, e.palette.length);
				for (String s : e.palette) {
					out.writeUTF(s);
				}
				for (int s : e.states) {
					varint(out, s);
				}
				out.write(e.marks);
				varint(out, e.blockEntities.size());
				for (Map<String, Object> entity : e.blockEntities) {
					byte[] nbt = Nbt.write(entity);
					varint(out, nbt.length);
					out.write(nbt);
				}
			}
		}
	}

	public static Contents read(Path file) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
			if (!Arrays.equals(in.readNBytes(MAGIC.length), MAGIC) || in.readUnsignedByte() != FORMAT) {
				throw new IOException(file + " is not a past-echos echo file (format " + FORMAT + ")");
			}
			String version = in.readUTF();
			int n = varint(in);
			List<Echo> echoes = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				int x = in.readInt();
				int y = in.readInt();
				int z = in.readInt();
				int sx = varint(in);
				int sy = varint(in);
				int sz = varint(in);
				double score = in.readDouble();
				String[] palette = new String[varint(in)];
				for (int p = 0; p < palette.length; p++) {
					palette[p] = in.readUTF();
				}
				int[] states = new int[sx * sy * sz];
				for (int p = 0; p < states.length; p++) {
					states[p] = varint(in);
				}
				byte[] marks = in.readNBytes(states.length);
				List<Map<String, Object>> entities = new ArrayList<>();
				for (int b = varint(in); b > 0; b--) {
					entities.add(Nbt.read(in.readNBytes(varint(in)), ByteOrder.BIG_ENDIAN));
				}
				echoes.add(new Echo(x, y, z, sx, sy, sz, score, palette, states, marks, entities));
			}
			return new Contents(version, echoes);
		}
	}

	private static void varint(DataOutputStream out, int v) throws IOException {
		while ((v & ~0x7F) != 0) {
			out.writeByte((v & 0x7F) | 0x80);
			v >>>= 7;
		}
		out.writeByte(v);
	}

	private static int varint(DataInputStream in) throws IOException {
		int v = 0;
		for (int shift = 0; shift < 35; shift += 7) {
			int b = in.readUnsignedByte();
			v |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return v;
			}
		}
		throw new IOException("varint longer than 5 bytes");
	}
}
