package justfatlard.past_echos.preprocess.diff;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The blocks where a saved world differs from its regenerated reference, chunk by chunk, gzipped:
 * "PEDIFF" and a format byte, then per chunk its x and z, a palette of the states it uses, and each
 * differing block as y, packed x/z, reference state and saved state. Chunks that match are absent.
 */
public final class DiffFile {
	private static final byte[] MAGIC = {'P', 'E', 'D', 'I', 'F', 'F'};
	private static final int FORMAT = 1;

	/** One differing block, in world coordinates. */
	public record Block(int x, int y, int z, String reference, String saved) {
	}

	public record ChunkDiff(int x, int z, List<Block> blocks) {
	}

	private DiffFile() {
	}

	public static final class Writer implements AutoCloseable {
		private final DataOutputStream out;

		public Writer(Path file) throws IOException {
			out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file))));
			out.write(MAGIC);
			out.writeByte(FORMAT);
		}

		public void chunk(ChunkDiff chunk) throws IOException {
			Map<String, Integer> index = new HashMap<>();
			List<String> palette = new ArrayList<>();
			for (Block b : chunk.blocks) {
				for (String state : new String[] {b.reference, b.saved}) {
					index.computeIfAbsent(state, s -> {
						palette.add(s);
						return palette.size() - 1;
					});
				}
			}
			out.writeInt(chunk.x);
			out.writeInt(chunk.z);
			varint(palette.size());
			for (String s : palette) {
				out.writeUTF(s);
			}
			varint(chunk.blocks.size());
			for (Block b : chunk.blocks) {
				varint((b.y << 1) ^ (b.y >> 31));
				out.writeByte((b.x & 15) << 4 | (b.z & 15));
				varint(index.get(b.reference));
				varint(index.get(b.saved));
			}
		}

		private void varint(int v) throws IOException {
			while ((v & ~0x7F) != 0) {
				out.writeByte((v & 0x7F) | 0x80);
				v >>>= 7;
			}
			out.writeByte(v);
		}

		@Override
		public void close() throws IOException {
			out.close();
		}
	}

	public static List<ChunkDiff> read(Path file) throws IOException {
		List<ChunkDiff> chunks = new ArrayList<>();
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
			byte[] magic = in.readNBytes(MAGIC.length);
			if (!Arrays.equals(magic, MAGIC) || in.readUnsignedByte() != FORMAT) {
				throw new IOException(file + " is not a past-echos diff (format " + FORMAT + ")");
			}
			while (true) {
				int cx;
				try {
					cx = in.readInt();
				} catch (EOFException end) {
					return chunks;
				}
				int cz = in.readInt();
				String[] palette = new String[varint(in)];
				for (int i = 0; i < palette.length; i++) {
					palette[i] = in.readUTF();
				}
				int n = varint(in);
				List<Block> blocks = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					int zigzag = varint(in);
					int y = (zigzag >>> 1) ^ -(zigzag & 1);
					int xz = in.readUnsignedByte();
					blocks.add(new Block(cx * 16 + (xz >> 4), y, cz * 16 + (xz & 15), palette[varint(in)], palette[varint(in)]));
				}
				chunks.add(new ChunkDiff(cx, cz, blocks));
			}
		}
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
