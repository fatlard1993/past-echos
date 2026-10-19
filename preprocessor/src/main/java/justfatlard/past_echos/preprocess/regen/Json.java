package justfatlard.past_echos.preprocess.regen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Enough JSON to read Mojang's version manifests: objects, arrays, strings, numbers, literals. */
public final class Json {
	private final String text;
	private int pos;

	private Json(String text) {
		this.text = text;
	}

	public static Object parse(String text) {
		Json json = new Json(text);
		Object value = json.value();
		json.space();
		if (json.pos != text.length()) {
			throw json.error("trailing content");
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> object(Object value, String key) {
		Object o = ((Map<String, Object>) value).get(key);
		if (!(o instanceof Map<?, ?>)) {
			throw new IllegalArgumentException("no object at " + key);
		}
		return (Map<String, Object>) o;
	}

	private Object value() {
		space();
		if (pos >= text.length()) {
			throw error("unexpected end");
		}
		char c = text.charAt(pos);
		return switch (c) {
			case '{' -> object();
			case '[' -> array();
			case '"' -> string();
			case 't' -> literal("true", Boolean.TRUE);
			case 'f' -> literal("false", Boolean.FALSE);
			case 'n' -> literal("null", null);
			default -> number();
		};
	}

	private Map<String, Object> object() {
		Map<String, Object> map = new LinkedHashMap<>();
		pos++;
		space();
		if (peek('}')) {
			return map;
		}
		do {
			space();
			String key = string();
			space();
			expect(':');
			map.put(key, value());
			space();
		} while (peek(','));
		expect('}');
		return map;
	}

	private List<Object> array() {
		List<Object> list = new ArrayList<>();
		pos++;
		space();
		if (peek(']')) {
			return list;
		}
		do {
			list.add(value());
			space();
		} while (peek(','));
		expect(']');
		return list;
	}

	private String string() {
		expect('"');
		StringBuilder s = new StringBuilder();
		while (true) {
			if (pos >= text.length()) {
				throw error("unterminated string");
			}
			char c = text.charAt(pos++);
			if (c == '"') {
				return s.toString();
			}
			if (c != '\\') {
				s.append(c);
				continue;
			}
			char e = text.charAt(pos++);
			switch (e) {
				case 'n' -> s.append('\n');
				case 't' -> s.append('\t');
				case 'r' -> s.append('\r');
				case 'b' -> s.append('\b');
				case 'f' -> s.append('\f');
				case 'u' -> {
					s.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
					pos += 4;
				}
				default -> s.append(e);
			}
		}
	}

	private Object number() {
		int start = pos;
		while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
			pos++;
		}
		String n = text.substring(start, pos);
		if (n.isEmpty()) {
			throw error("unexpected character");
		}
		return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
	}

	private Object literal(String word, Object value) {
		if (!text.startsWith(word, pos)) {
			throw error("unexpected character");
		}
		pos += word.length();
		return value;
	}

	private void space() {
		while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
			pos++;
		}
	}

	private boolean peek(char c) {
		if (pos < text.length() && text.charAt(pos) == c) {
			pos++;
			return true;
		}
		return false;
	}

	private void expect(char c) {
		if (!peek(c)) {
			throw error("expected '" + c + "'");
		}
	}

	private IllegalArgumentException error(String what) {
		return new IllegalArgumentException(what + " at offset " + pos);
	}
}
