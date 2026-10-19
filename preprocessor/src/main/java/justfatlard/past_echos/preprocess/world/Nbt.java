package justfatlard.past_echos.preprocess.world;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A reader for every NBT dialect a save has used: big-endian for Java Edition, little-endian for
 * Bedrock. Compounds become maps, lists become lists, arrays stay primitive arrays. Writes
 * big-endian back from the same shapes.
 */
public final class Nbt {
	private static final int MAX_DEPTH = 512;

	private Nbt() {
	}

	public static Map<String, Object> read(byte[] bytes, ByteOrder order) throws IOException {
		Reader reader = new Reader(bytes, order);
		int type = reader.u8();
		if (type != 10) {
			throw new IOException("root tag is type " + type + ", not a compound");
		}
		reader.string();
		return reader.compound(0);
	}

	/**
	 * Big-endian NBT for a root compound shaped as {@link #read} returns it. Tag types come from
	 * the Java types, so an empty list is written as a list of nothing, which the game accepts.
	 */
	public static byte[] write(Map<String, Object> root) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeByte(10);
			out.writeUTF("");
			writeCompound(out, root);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return bytes.toByteArray();
	}

	private static int type(Object value) {
		return switch (value) {
			case Byte b -> 1;
			case Short s -> 2;
			case Integer i -> 3;
			case Long l -> 4;
			case Float f -> 5;
			case Double d -> 6;
			case byte[] b -> 7;
			case String s -> 8;
			case List<?> l -> 9;
			case Map<?, ?> m -> 10;
			case int[] a -> 11;
			case long[] a -> 12;
			default -> throw new IllegalArgumentException("no NBT type for " + value.getClass());
		};
	}

	private static void writeCompound(DataOutputStream out, Map<?, ?> map) throws IOException {
		for (Map.Entry<?, ?> e : map.entrySet()) {
			out.writeByte(type(e.getValue()));
			out.writeUTF((String) e.getKey());
			writePayload(out, e.getValue());
		}
		out.writeByte(0);
	}

	private static void writePayload(DataOutputStream out, Object value) throws IOException {
		switch (value) {
			case Byte b -> out.writeByte(b);
			case Short s -> out.writeShort(s);
			case Integer i -> out.writeInt(i);
			case Long l -> out.writeLong(l);
			case Float f -> out.writeFloat(f);
			case Double d -> out.writeDouble(d);
			case byte[] b -> {
				out.writeInt(b.length);
				out.write(b);
			}
			case String s -> out.writeUTF(s);
			case List<?> list -> {
				out.writeByte(list.isEmpty() ? 0 : type(list.getFirst()));
				out.writeInt(list.size());
				for (Object item : list) {
					writePayload(out, item);
				}
			}
			case Map<?, ?> map -> writeCompound(out, map);
			case int[] a -> {
				out.writeInt(a.length);
				for (int v : a) {
					out.writeInt(v);
				}
			}
			case long[] a -> {
				out.writeInt(a.length);
				for (long v : a) {
					out.writeLong(v);
				}
			}
			default -> throw new IllegalArgumentException("no NBT type for " + value.getClass());
		}
	}

	@SuppressWarnings("unchecked")
	public static Optional<Map<String, Object>> compound(Map<String, Object> tag, String... path) {
		Object at = tag;
		for (String key : path) {
			if (!(at instanceof Map<?, ?> map)) {
				return Optional.empty();
			}
			at = map.get(key);
		}
		return at instanceof Map<?, ?> map ? Optional.of((Map<String, Object>) map) : Optional.empty();
	}

	public static Optional<Object> get(Map<String, Object> tag, String... path) {
		if (path.length == 0) {
			return Optional.of(tag);
		}
		String[] parents = java.util.Arrays.copyOf(path, path.length - 1);
		return compound(tag, parents).map(c -> c.get(path[path.length - 1]));
	}

	public static Optional<String> string(Map<String, Object> tag, String... path) {
		return get(tag, path).filter(String.class::isInstance).map(String.class::cast);
	}

	public static Optional<Long> number(Map<String, Object> tag, String... path) {
		return get(tag, path).filter(Number.class::isInstance).map(n -> ((Number) n).longValue());
	}

	private static final class Reader {
		private final DataInputStream in;
		private final boolean little;

		Reader(byte[] bytes, ByteOrder order) {
			this.in = new DataInputStream(new ByteArrayInputStream(bytes));
			this.little = order == ByteOrder.LITTLE_ENDIAN;
		}

		int u8() throws IOException {
			return in.readUnsignedByte();
		}

		short i16() throws IOException {
			short v = in.readShort();
			return little ? Short.reverseBytes(v) : v;
		}

		int i32() throws IOException {
			int v = in.readInt();
			return little ? Integer.reverseBytes(v) : v;
		}

		long i64() throws IOException {
			long v = in.readLong();
			return little ? Long.reverseBytes(v) : v;
		}

		String string() throws IOException {
			byte[] bytes = new byte[i16() & 0xFFFF];
			in.readFully(bytes);
			// Modified UTF-8 differs from UTF-8 only for NUL and astral characters, neither of
			// which carry meaning in any key or name this reads.
			return new String(bytes, StandardCharsets.UTF_8);
		}

		/** A count of elements at least {@code minBytes} wide each, refused when the data can't hold it. */
		int length(int minBytes) throws IOException {
			int n = i32();
			if (n < 0 || (long) n * minBytes > in.available()) {
				throw new EOFException("length " + n + " runs past the end of the data");
			}
			return n;
		}

		Map<String, Object> compound(int depth) throws IOException {
			Map<String, Object> map = new LinkedHashMap<>();
			while (true) {
				int type = u8();
				if (type == 0) {
					return map;
				}
				String name = string();
				map.put(name, payload(type, depth + 1));
			}
		}

		Object payload(int type, int depth) throws IOException {
			if (depth > MAX_DEPTH) {
				throw new IOException("nested deeper than " + MAX_DEPTH);
			}
			return switch (type) {
				case 1 -> in.readByte();
				case 2 -> i16();
				case 3 -> i32();
				case 4 -> i64();
				case 5 -> Float.intBitsToFloat(i32());
				case 6 -> Double.longBitsToDouble(i64());
				case 7 -> {
					byte[] bytes = new byte[length(1)];
					in.readFully(bytes);
					yield bytes;
				}
				case 8 -> string();
				case 9 -> {
					int elementType = u8();
					int n = length(elementType == 0 ? 0 : 1);
					List<Object> list = new ArrayList<>(Math.min(n, 1 << 16));
					for (int i = 0; i < n; i++) {
						list.add(payload(elementType, depth + 1));
					}
					yield list;
				}
				case 10 -> compound(depth);
				case 11 -> {
					int n = length(4);
					ByteBuffer buffer = buffer(n * 4L);
					int[] values = new int[n];
					buffer.asIntBuffer().get(values);
					yield values;
				}
				case 12 -> {
					int n = length(8);
					ByteBuffer buffer = buffer(n * 8L);
					long[] values = new long[n];
					buffer.asLongBuffer().get(values);
					yield values;
				}
				default -> throw new IOException("unknown tag type " + type);
			};
		}

		private ByteBuffer buffer(long size) throws IOException {
			if (size > Integer.MAX_VALUE) {
				throw new EOFException("array of " + size + " bytes");
			}
			byte[] bytes = new byte[(int) size];
			in.readFully(bytes);
			return ByteBuffer.wrap(bytes).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
		}
	}
}
