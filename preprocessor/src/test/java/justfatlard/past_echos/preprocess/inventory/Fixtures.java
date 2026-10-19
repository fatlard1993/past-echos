package justfatlard.past_echos.preprocess.inventory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Saves in each era's format, written by hand, small enough to build per test. */
final class Fixtures {
	private Fixtures() {
	}

	static Map<String, Object> compound(Object... keyValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put((String) keyValues[i], keyValues[i + 1]);
		}
		return map;
	}

	static byte[] nbt(Map<String, Object> root) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(10);
		out.writeUTF("");
		writeCompound(out, root);
		return bytes.toByteArray();
	}

	static byte[] gzip(byte[] bytes) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
			gz.write(bytes);
		}
		return out.toByteArray();
	}

	private static void writeCompound(DataOutputStream out, Map<String, Object> map) throws IOException {
		for (var entry : map.entrySet()) {
			out.writeByte(type(entry.getValue()));
			out.writeUTF(entry.getKey());
			writePayload(out, entry.getValue());
		}
		out.writeByte(0);
	}

	private static int type(Object value) {
		return switch (value) {
			case Byte b -> 1;
			case Short s -> 2;
			case Integer i -> 3;
			case Long l -> 4;
			case String s -> 8;
			case List<?> l -> 9;
			case Map<?, ?> m -> 10;
			case byte[] b -> 7;
			default -> throw new IllegalArgumentException(value.getClass().getName());
		};
	}

	@SuppressWarnings("unchecked")
	private static void writePayload(DataOutputStream out, Object value) throws IOException {
		switch (value) {
			case Byte b -> out.writeByte(b);
			case Short s -> out.writeShort(s);
			case Integer i -> out.writeInt(i);
			case Long l -> out.writeLong(l);
			case String s -> out.writeUTF(s);
			case byte[] b -> {
				out.writeInt(b.length);
				out.write(b);
			}
			case List<?> list -> {
				out.writeByte(list.isEmpty() ? 0 : type(list.getFirst()));
				out.writeInt(list.size());
				for (Object item : list) {
					writePayload(out, item);
				}
			}
			case Map<?, ?> m -> writeCompound(out, (Map<String, Object>) m);
			default -> throw new IllegalArgumentException(value.getClass().getName());
		}
	}

	static void levelDat(Path dir, Map<String, Object> data) throws IOException {
		Files.createDirectories(dir);
		Files.write(dir.resolve("level.dat"), gzip(nbt(compound("Data", data))));
	}

	/** A region file whose location table marks {@code chunks} slots as saved. */
	static void region(Path dir, String name, int chunks) throws IOException {
		Files.createDirectories(dir);
		byte[] file = new byte[8192];
		for (int i = 0; i < chunks; i++) {
			file[i * 4 + 3] = 2;
			file[i * 4 + 2] = (byte) (i + 2);
		}
		Files.write(dir.resolve(name), file);
	}

	static void alphaChunks(Path root, int count) throws IOException {
		for (int i = 0; i < count; i++) {
			Path folder = root.resolve(Integer.toString(i % 36, 36)).resolve(Integer.toString(i / 36 % 36, 36));
			Files.createDirectories(folder);
			Files.write(folder.resolve("c." + i + "." + Integer.toString(-i, 36) + ".dat"), new byte[]{1});
		}
	}

	static void zip(Path source, Path zipFile) throws IOException {
		try (OutputStream file = Files.newOutputStream(zipFile)) {
			zipTo(source, file);
		}
	}

	static byte[] zipBytes(Path source) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		zipTo(source, out);
		return out.toByteArray();
	}

	private static void zipTo(Path source, OutputStream sink) throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(sink); Stream<Path> files = Files.walk(source)) {
			for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
				zip.putNextEntry(new ZipEntry(source.getFileName() + "/" + source.relativize(file).toString().replace('\\', '/')));
				zip.write(Files.readAllBytes(file));
				zip.closeEntry();
			}
		}
	}

	/** A gzipped ustar archive holding the given entries, written the way GNU tar lays them out. */
	static void tarGz(Path tarFile, Map<String, byte[]> entries) throws IOException {
		try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tarFile))) {
			for (var entry : entries.entrySet()) {
				byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
				if (name.length > 99) {
					// GNU long name: a pseudo-entry carrying the real name, then the entry itself.
					writeTarHeader(out, "././@LongLink".getBytes(StandardCharsets.UTF_8), name.length + 1, 'L');
					out.write(name);
					out.write(0);
					pad(out, name.length + 1);
					name = "truncated".getBytes(StandardCharsets.UTF_8);
				}
				writeTarHeader(out, name, entry.getValue().length, '0');
				out.write(entry.getValue());
				pad(out, entry.getValue().length);
			}
			out.write(new byte[1024]);
		}
	}

	private static void writeTarHeader(OutputStream out, byte[] name, long size, char type) throws IOException {
		byte[] h = new byte[512];
		System.arraycopy(name, 0, h, 0, Math.min(name.length, 100));
		octal(h, 100, 8, 0644);
		octal(h, 108, 8, 0);
		octal(h, 116, 8, 0);
		octal(h, 124, 12, size);
		octal(h, 136, 12, 1_300_000_000L);
		h[156] = (byte) type;
		System.arraycopy("ustar\u000000".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 8);
		for (int i = 148; i < 156; i++) {
			h[i] = ' ';
		}
		long sum = 0;
		for (byte b : h) {
			sum += b & 0xFF;
		}
		octal(h, 148, 7, sum);
		out.write(h);
	}

	private static void octal(byte[] h, int at, int length, long value) {
		String s = String.format("%0" + (length - 1) + "o", value);
		System.arraycopy(s.getBytes(StandardCharsets.US_ASCII), 0, h, at, length - 1);
	}

	private static void pad(OutputStream out, long size) throws IOException {
		out.write(new byte[(int) ((512 - size % 512) % 512)]);
	}
}
