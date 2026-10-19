package justfatlard.past_echos.preprocess.world;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * The chunks in one McRegion (.mcr) or Anvil (.mca) file. A 4096-byte table of sector offsets,
 * a 4096-byte table of save times, then each chunk as a length, a compression byte and its NBT.
 */
public final class RegionFile {
	private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.(mca|mcr)");
	private static final int SECTOR = 4096;
	/** Set on the compression byte when the chunk was too big for the region and sits in c.X.Z.mcc. */
	private static final int EXTERNAL = 0x80;

	/** A chunk that could not be read, and why. */
	public record Unreadable(int x, int z, String reason) {
	}

	public record Contents(List<Chunk> chunks, List<Unreadable> unreadable) {
	}

	private RegionFile() {
	}

	public static boolean isRegion(Path file) {
		return NAME.matcher(file.getFileName().toString()).matches();
	}

	public static Contents read(Path file) throws IOException {
		Matcher m = NAME.matcher(file.getFileName().toString());
		if (!m.matches()) {
			throw new IOException(file + " is not named like a region file");
		}
		int rx = Integer.parseInt(m.group(1));
		int rz = Integer.parseInt(m.group(2));
		byte[] raw = Files.readAllBytes(file);
		List<Chunk> chunks = new ArrayList<>();
		List<Unreadable> unreadable = new ArrayList<>();
		// Servers leave region files shorter than the two tables where a region was opened and nothing saved.
		if (raw.length < 2 * SECTOR) {
			return new Contents(chunks, unreadable);
		}
		ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
		for (int slot = 0; slot < 1024; slot++) {
			int location = buffer.getInt(slot * 4);
			if (location == 0) {
				continue;
			}
			int x = rx * 32 + (slot & 31);
			int z = rz * 32 + (slot >> 5);
			try {
				chunks.add(Chunk.of(x, z, chunkNbt(file, raw, (location >>> 8) * SECTOR, x, z)));
			} catch (IOException | RuntimeException e) {
				unreadable.add(new Unreadable(x, z, e.getMessage() == null ? e.toString() : e.getMessage()));
			}
		}
		return new Contents(chunks, unreadable);
	}

	private static Map<String, Object> chunkNbt(Path file, byte[] raw, int offset, int x, int z) throws IOException {
		if (offset + 5 > raw.length) {
			throw new IOException("chunk offset past the end of the file");
		}
		int length = ByteBuffer.wrap(raw, offset, 4).getInt();
		int compression = raw[offset + 4] & 0xFF;
		byte[] payload;
		if ((compression & EXTERNAL) != 0) {
			payload = Files.readAllBytes(file.resolveSibling("c." + x + "." + z + ".mcc"));
			compression &= ~EXTERNAL;
		} else {
			if (length < 1 || offset + 4 + length > raw.length) {
				throw new IOException("chunk length " + length + " runs past the end of the file");
			}
			payload = Arrays.copyOfRange(raw, offset + 5, offset + 4 + length);
		}
		InputStream bytes = new ByteArrayInputStream(payload);
		InputStream in = switch (compression) {
			case 1 -> new GZIPInputStream(bytes);
			case 2 -> new InflaterInputStream(bytes);
			case 3 -> bytes;
			// LZ4 is a server option since 1.20.5, off by default.
			case 4 -> throw new IOException("LZ4-compressed chunk (region-file-compression=lz4) is not supported yet");
			default -> throw new IOException("unknown chunk compression " + compression);
		};
		try (in) {
			return Nbt.read(in.readAllBytes(), ByteOrder.BIG_ENDIAN);
		}
	}
}
