package justfatlard.past_echos.preprocess.inventory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads what a version-2 Classic level says about itself. These are a Java-serialized
 * {@code com.mojang.minecraft.level.Level}: the class descriptor lists every field, the primitive
 * values follow in that order, then the objects. The block map before the blocks is an arbitrary
 * object graph, so rather than deserialize it, this finds the block array by its known length and
 * reads the string fields after it.
 */
final class ClassicLevel {
	private static final int TC_NULL = 0x70;
	private static final int TC_REFERENCE = 0x71;
	private static final int TC_CLASSDESC = 0x72;
	private static final int TC_OBJECT = 0x73;
	private static final int TC_STRING = 0x74;
	private static final int TC_ENDBLOCKDATA = 0x78;

	final Map<String, Number> primitives = new LinkedHashMap<>();
	final Map<String, String> strings = new LinkedHashMap<>();

	private ClassicLevel() {
	}

	/** What could be read from the inflated level, starting at its 0x271BB788 magic; empty where it couldn't. */
	static ClassicLevel read(byte[] level) {
		ClassicLevel result = new ClassicLevel();
		try {
			result.parse(ByteBuffer.wrap(level));
		} catch (RuntimeException e) {
			// A truncated or unusual stream just means fewer facts; the level is still a Classic level.
		}
		return result;
	}

	Integer width() {
		return intField("width");
	}

	/** Classic's vertical axis. */
	Integer depth() {
		return intField("depth");
	}

	Integer height() {
		return intField("height");
	}

	private Integer intField(String name) {
		Number n = primitives.get(name);
		return n == null ? null : n.intValue();
	}

	private record Field(char type, String name) {
	}

	private void parse(ByteBuffer in) {
		in.position(5);
		if ((in.getShort() & 0xFFFF) != 0xACED || in.getShort() != 5 || in.get() != TC_OBJECT || in.get() != TC_CLASSDESC) {
			return;
		}
		utf(in);
		in.getLong();
		in.get();
		List<Field> fields = new ArrayList<>();
		int count = in.getShort() & 0xFFFF;
		for (int i = 0; i < count; i++) {
			char type = (char) in.get();
			String name = utf(in);
			if (type == 'L' || type == '[') {
				int tag = in.get();
				if (tag == TC_STRING) {
					utf(in);
				} else if (tag == TC_REFERENCE) {
					in.getInt();
				}
			}
			fields.add(new Field(type, name));
		}
		if (in.get() != TC_ENDBLOCKDATA || in.get() != TC_NULL) {
			return;
		}
		List<Field> objects = new ArrayList<>();
		for (Field f : fields) {
			switch (f.type) {
				case 'B', 'Z' -> primitives.put(f.name, in.get());
				case 'C', 'S' -> primitives.put(f.name, in.getShort());
				case 'I' -> primitives.put(f.name, in.getInt());
				case 'F' -> primitives.put(f.name, in.getFloat());
				case 'J' -> primitives.put(f.name, in.getLong());
				case 'D' -> primitives.put(f.name, in.getDouble());
				default -> objects.add(f);
			}
		}

		Integer w = width();
		Integer d = depth();
		Integer h = height();
		int blocksAt = indexOf(objects, "blocks");
		if (w == null || d == null || h == null || blocksAt < 0) {
			return;
		}
		int end = findArrayEnd(in, (long) w * d * h);
		if (end < 0) {
			return;
		}
		in.position(end);
		for (int i = blocksAt + 1; i < objects.size() && objects.get(i).type == 'L'; i++) {
			int tag = in.get();
			if (tag == TC_STRING) {
				strings.put(objects.get(i).name, utf(in));
			} else if (tag == TC_OBJECT && skipEmptyArrayList(in)) {
				continue;
			} else if (tag != TC_NULL) {
				// A back-reference or a populated object: past what this reads without a handle table.
				return;
			}
		}
	}

	/**
	 * Steps over an empty {@code java.util.ArrayList}, as early Classic writes its entity list
	 * between the creator and the name. A list with anything in it stops the read.
	 */
	private static boolean skipEmptyArrayList(ByteBuffer in) {
		int tag = in.get();
		if (tag == TC_CLASSDESC) {
			if (!utf(in).equals("java.util.ArrayList")) {
				return false;
			}
			in.getLong();
			in.get();
			if (in.getShort() != 1 || in.get() != 'I' || !utf(in).equals("size") || in.get() != TC_ENDBLOCKDATA || in.get() != TC_NULL) {
				return false;
			}
		} else if (tag == TC_REFERENCE) {
			in.getInt();
		} else {
			return false;
		}
		if (in.getInt() != 0) {
			return false;
		}
		// writeObject's block data: the capacity int, then the end marker.
		if (in.get() != 0x77) {
			return false;
		}
		int length = in.get() & 0xFF;
		in.position(in.position() + length);
		return in.get() == TC_ENDBLOCKDATA;
	}

	private static int indexOf(List<Field> fields, String name) {
		for (int i = 0; i < fields.size(); i++) {
			if (fields.get(i).name.equals(name)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Where the byte array of {@code length} ends. Its length int follows either a fresh {@code [B}
	 * class descriptor (which ends {@code 78 70}) or a back-reference to one ({@code 71} and a handle).
	 */
	private static int findArrayEnd(ByteBuffer in, long length) {
		if (length <= 0 || length > Integer.MAX_VALUE) {
			return -1;
		}
		byte[] b = in.array();
		for (int i = Math.max(in.position(), 5); i + 4 <= b.length; i++) {
			if (Bytes.bigEndianInt(b, i) != (int) length) {
				continue;
			}
			boolean afterDescriptor = (b[i - 2] & 0xFF) == TC_ENDBLOCKDATA && (b[i - 1] & 0xFF) == TC_NULL;
			boolean afterReference = (b[i - 5] & 0xFF) == TC_REFERENCE;
			long end = i + 4L + length;
			if ((afterDescriptor || afterReference) && end <= b.length) {
				return (int) end;
			}
		}
		return -1;
	}

	private static String utf(ByteBuffer in) {
		byte[] bytes = new byte[in.getShort() & 0xFFFF];
		in.get(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
