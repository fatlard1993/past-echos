package justfatlard.past_echos.preprocess.inventory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A streaming tar reader: ustar, GNU long names and pax path overrides, which covers what
 * {@code tar} on Linux and macOS writes. Entries are handed out in order, one pass, no seeking.
 */
final class Tar {
	private static final int BLOCK = 512;

	interface EntryHandler {
		void entry(String name, long size, long mtime, InputStream content) throws IOException;
	}

	private Tar() {
	}

	static void read(InputStream in, EntryHandler handler) throws IOException {
		String longName = null;
		byte[] header = new byte[BLOCK];
		while (true) {
			if (in.readNBytes(header, 0, BLOCK) < BLOCK || isZero(header)) {
				return;
			}
			if (!checksumMatches(header)) {
				throw new IOException("tar header checksum mismatch: not a tar, or corrupt");
			}
			String name = string(header, 0, 100);
			String prefix = string(header, 345, 155);
			if (!prefix.isEmpty() && longName == null) {
				name = prefix + "/" + name;
			}
			long size = octal(header, 124, 12);
			long mtime = octal(header, 136, 12) * 1000L;
			char type = (char) header[156];

			Bounded content = new Bounded(in, size);
			switch (type) {
				case 'L' -> longName = trimNul(new String(content.readAllBytes(), StandardCharsets.UTF_8));
				case 'x' -> {
					String path = paxPath(new String(content.readAllBytes(), StandardCharsets.UTF_8));
					if (path != null) {
						longName = path;
					}
				}
				case '0', '\0', '7' -> {
					handler.entry(longName != null ? longName : name, size, mtime, content);
					longName = null;
				}
				default -> longName = null;
			}
			content.skipRest();
			skipFully(in, (BLOCK - size % BLOCK) % BLOCK);
		}
	}

	private static String paxPath(String records) {
		for (String record : records.split("\n")) {
			int space = record.indexOf(' ');
			if (space > 0 && record.startsWith("path=", space + 1)) {
				return record.substring(space + 1 + "path=".length());
			}
		}
		return null;
	}

	private static boolean isZero(byte[] block) {
		for (byte b : block) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}

	private static boolean checksumMatches(byte[] header) {
		long stored = octal(header, 148, 8);
		long sum = 0;
		for (int i = 0; i < BLOCK; i++) {
			sum += i >= 148 && i < 156 ? ' ' : header[i] & 0xFF;
		}
		return stored == sum;
	}

	private static String string(byte[] bytes, int at, int length) {
		return trimNul(new String(bytes, at, length, StandardCharsets.UTF_8));
	}

	private static String trimNul(String s) {
		int nul = s.indexOf('\0');
		return nul < 0 ? s : s.substring(0, nul);
	}

	private static long octal(byte[] bytes, int at, int length) {
		if ((bytes[at] & 0x80) != 0) {
			// GNU base-256 for sizes past 8 GiB.
			long value = bytes[at] & 0x7F;
			for (int i = 1; i < length; i++) {
				value = value << 8 | bytes[at + i] & 0xFF;
			}
			return value;
		}
		long value = 0;
		boolean started = false;
		for (int i = at; i < at + length; i++) {
			int c = bytes[i];
			if (c >= '0' && c <= '7') {
				value = value * 8 + (c - '0');
				started = true;
			} else if (started || c == 0) {
				break;
			}
		}
		return value;
	}

	private static void skipFully(InputStream in, long n) throws IOException {
		while (n > 0) {
			long skipped = in.skip(n);
			if (skipped <= 0) {
				if (in.read() < 0) {
					throw new EOFException("tar ends mid-entry");
				}
				skipped = 1;
			}
			n -= skipped;
		}
	}

	/** One entry's bytes, without letting a reader run into the next header. */
	private static final class Bounded extends InputStream {
		private final InputStream in;
		private long remaining;

		Bounded(InputStream in, long size) {
			this.in = in;
			this.remaining = size;
		}

		@Override
		public int read() throws IOException {
			if (remaining <= 0) {
				return -1;
			}
			int b = in.read();
			if (b >= 0) {
				remaining--;
			}
			return b;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			if (remaining <= 0) {
				return -1;
			}
			int n = in.read(buffer, offset, (int) Math.min(length, remaining));
			if (n > 0) {
				remaining -= n;
			}
			return n;
		}

		@Override
		public void close() {
		}

		void skipRest() throws IOException {
			skipFully(in, remaining);
			remaining = 0;
		}
	}
}
