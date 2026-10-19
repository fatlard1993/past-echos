package justfatlard.past_echos.preprocess.inventory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

final class Bytes {
	private Bytes() {
	}

	/** At most {@code max} bytes from the stream; fewer if it ends first. */
	static byte[] readUpTo(InputStream in, int max) throws IOException {
		return in.readNBytes(max);
	}

	static boolean isGzip(byte[] bytes) {
		return bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
	}

	/** The inflated bytes, refused past {@code max} so a hostile or corrupt file can't exhaust memory. */
	static byte[] gunzip(byte[] bytes, int max) throws IOException {
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, bytes.length * 4 + 64));
			byte[] buffer = new byte[1 << 16];
			long total = 0;
			for (int n; (n = in.read(buffer)) > 0; ) {
				total += n;
				if (total > max) {
					throw new IOException("inflates past " + max + " bytes");
				}
				out.write(buffer, 0, n);
			}
			return out.toByteArray();
		}
	}

	static int bigEndianInt(byte[] bytes, int at) {
		return (bytes[at] & 0xFF) << 24 | (bytes[at + 1] & 0xFF) << 16 | (bytes[at + 2] & 0xFF) << 8 | bytes[at + 3] & 0xFF;
	}

	static int littleEndianInt(byte[] bytes, int at) {
		return Integer.reverseBytes(bigEndianInt(bytes, at));
	}

	/** A view that leaves the underlying stream open, for handing out one entry of a streamed archive. */
	static InputStream unclosable(InputStream in) {
		return new FilterInputStream(in) {
			@Override
			public void close() {
			}
		};
	}
}
