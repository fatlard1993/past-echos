"""Just enough of the Java save formats to compare two worlds block for block: NBT, region
files, and the three ways chunk sections have stored blocks (numeric ids to 1.12, spanning
palettes 1.13-1.15, packed palettes from 1.16)."""

import gzip
import math
import struct
import zlib
from pathlib import Path

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTES, STRING, LIST, COMPOUND, INTS, LONGS = range(13)


class Tag:
	"""A typed value, so a read tag writes back as the same type."""

	def __init__(self, kind, value, item=END):
		self.kind, self.value, self.item = kind, value, item

	def __getitem__(self, key):
		return self.value[key]

	def get(self, key, default=None):
		return self.value.get(key, default) if self.kind == COMPOUND else default

	def __contains__(self, key):
		return key in self.value


def _read(buf, pos, kind):
	if kind == BYTE:
		return Tag(kind, struct.unpack_from(">b", buf, pos)[0]), pos + 1
	if kind == SHORT:
		return Tag(kind, struct.unpack_from(">h", buf, pos)[0]), pos + 2
	if kind == INT:
		return Tag(kind, struct.unpack_from(">i", buf, pos)[0]), pos + 4
	if kind == LONG:
		return Tag(kind, struct.unpack_from(">q", buf, pos)[0]), pos + 8
	if kind == FLOAT:
		return Tag(kind, struct.unpack_from(">f", buf, pos)[0]), pos + 4
	if kind == DOUBLE:
		return Tag(kind, struct.unpack_from(">d", buf, pos)[0]), pos + 8
	if kind == BYTES:
		(n,) = struct.unpack_from(">i", buf, pos)
		return Tag(kind, bytes(buf[pos + 4:pos + 4 + n])), pos + 4 + n
	if kind == STRING:
		(n,) = struct.unpack_from(">H", buf, pos)
		return Tag(kind, bytes(buf[pos + 2:pos + 2 + n]).decode("utf-8", "replace")), pos + 2 + n
	if kind == LIST:
		item, n = struct.unpack_from(">bi", buf, pos)
		pos += 5
		items = []
		for _ in range(n):
			t, pos = _read(buf, pos, item)
			items.append(t)
		return Tag(kind, items, item), pos
	if kind == COMPOUND:
		out = {}
		while True:
			child = buf[pos]
			pos += 1
			if child == END:
				return Tag(kind, out), pos
			(n,) = struct.unpack_from(">H", buf, pos)
			name = bytes(buf[pos + 2:pos + 2 + n]).decode("utf-8", "replace")
			out[name], pos = _read(buf, pos + 2 + n, child)
	if kind == INTS:
		(n,) = struct.unpack_from(">i", buf, pos)
		return Tag(kind, list(struct.unpack_from(f">{n}i", buf, pos + 4))), pos + 4 + 4 * n
	if kind == LONGS:
		(n,) = struct.unpack_from(">i", buf, pos)
		return Tag(kind, list(struct.unpack_from(f">{n}q", buf, pos + 4))), pos + 4 + 8 * n
	raise ValueError(f"tag type {kind}")


def _write(tag, out):
	k, v = tag.kind, tag.value
	if k in (BYTE, SHORT, INT, LONG, FLOAT, DOUBLE):
		out += struct.pack(">" + "xbhiqfd"[k], v)
	elif k == BYTES:
		out += struct.pack(">i", len(v)) + v
	elif k == STRING:
		b = v.encode("utf-8")
		out += struct.pack(">H", len(b)) + b
	elif k == LIST:
		out += struct.pack(">bi", tag.item, len(v))
		for t in v:
			_write(t, out)
	elif k == COMPOUND:
		for name, t in v.items():
			b = name.encode("utf-8")
			out += struct.pack(">bH", t.kind, len(b)) + b
			_write(t, out)
		out.append(END)
	elif k in (INTS, LONGS):
		out += struct.pack(f">i{len(v)}{'i' if k == INTS else 'q'}", len(v), *v)


def parse(raw):
	"""The root compound of uncompressed NBT."""
	if raw[0] != COMPOUND:
		raise ValueError("root is not a compound")
	(n,) = struct.unpack_from(">H", raw, 1)
	return _read(raw, 3 + n, COMPOUND)[0]


def read_level(path):
	return parse(gzip.decompress(Path(path).read_bytes()))


def write_level(path, root):
	out = bytearray([COMPOUND, 0, 0])
	_write(root, out)
	Path(path).write_bytes(gzip.compress(bytes(out)))


def region_chunks(path):
	"""(cx, cz, root) for each chunk saved in a region file."""
	raw = Path(path).read_bytes()
	if len(raw) < 8192:
		return
	rx, rz = (int(p) for p in Path(path).name.split(".")[1:3])
	for slot in range(1024):
		(loc,) = struct.unpack_from(">I", raw, slot * 4)
		if loc == 0:
			continue
		offset = (loc >> 8) * 4096
		length, compression = struct.unpack_from(">ib", raw, offset)
		data = raw[offset + 5:offset + 4 + length]
		if compression == 1:
			data = gzip.decompress(data)
		elif compression == 2:
			data = zlib.decompress(data)
		elif compression != 3:
			raise ValueError(f"{path}: chunk compression {compression} (oversized .mcc chunks unsupported)")
		yield rx * 32 + slot % 32, rz * 32 + slot // 32, parse(data)


def overworld_regions(world):
	for d in (world / "region", world / "dimensions/minecraft/overworld/region"):
		if d.is_dir():
			yield from sorted(d.glob("r.*.mca"))


def chunk_level(root):
	"""The compound holding sections: under Level until 1.18, the root itself after."""
	return root.get("Level") or root


def finished(root):
	"""Whether the chunk's decoration ran: TerrainPopulated in old worlds, full status in new."""
	level = chunk_level(root)
	if "Status" in level:
		return level["Status"].value.removeprefix("minecraft:") == "full"
	populated = level.get("TerrainPopulated")
	return populated is not None and populated.value != 0


def _state(entry):
	# A list mixing tag types wraps each element as {"": element}.
	if entry.kind == COMPOUND and list(entry.value) == [""]:
		entry = entry.value[""]
	# 26.x writes a state without properties as a bare string.
	if entry.kind == STRING:
		return entry.value.removeprefix("minecraft:")
	# Name/Properties until 26.x, id/properties after.
	props = entry.get("Properties") or entry.get("properties")
	name = (entry.get("Name") or entry["id"]).value.removeprefix("minecraft:")
	if not props:
		return name
	return name + "[" + ",".join(f"{k}={v.value}" for k, v in sorted(props.value.items())) + "]"


def _unpack(longs, bits, count, spanning):
	mask = (1 << bits) - 1
	out = []
	if spanning:
		for i in range(count):
			bit = i * bits
			word, shift = divmod(bit, 64)
			value = (longs[word] & 0xFFFFFFFFFFFFFFFF) >> shift
			if shift + bits > 64:
				value |= (longs[word + 1] & 0xFFFFFFFFFFFFFFFF) << (64 - shift)
			out.append(value & mask)
	else:
		per = 64 // bits
		for i in range(count):
			word, slot = divmod(i, per)
			out.append(((longs[word] & 0xFFFFFFFFFFFFFFFF) >> (slot * bits)) & mask)
	return out


def sections(root):
	"""{section y: 4096 block names in YZX order}, leaving out sections that are only air."""
	level = chunk_level(root)
	data_version = root.get("DataVersion")
	spanning = data_version is not None and data_version.value < 2529
	out = {}
	for s in (level.get("Sections") or level.get("sections") or Tag(LIST, [])).value:
		y = s["Y"].value
		if "Blocks" in s:
			ids, meta = s["Blocks"].value, s["Data"].value
			add = s.get("Add")
			blocks = []
			for i in range(4096):
				block = ids[i] | (((add.value[i >> 1] >> ((i & 1) * 4)) & 0xF) << 8 if add else 0)
				# Air is air whatever its data nibble, and matches the filler for missing sections.
				blocks.append("air" if block == 0 else f"{block}:{(meta[i >> 1] >> ((i & 1) * 4)) & 0xF}")
		else:
			states = s.get("block_states")
			palette = (states.get("palette") if states else s.get("Palette"))
			if palette is None:
				continue
			names = [_state(p) for p in palette.value]
			longs = states.get("data") if states else s.get("BlockStates")
			if longs is None:
				blocks = [names[0]] * 4096
			else:
				bits = max(4, math.ceil(math.log2(len(names))))
				blocks = [names[i] for i in _unpack(longs.value, bits, 4096, spanning)]
		if all(b == "air" for b in blocks):
			continue
		out[y] = blocks
	return out


# -- writing ------------------------------------------------------------------------------

def serialize(root):
	out = bytearray([COMPOUND, 0, 0])
	_write(root, out)
	return bytes(out)


def write_region(path, chunks):
	"""A region file holding (cx, cz, root) chunks, zlib-compressed, save times left at zero."""
	locations = bytearray(4096)
	body = bytearray()
	for cx, cz, root in chunks:
		data = zlib.compress(serialize(root))
		record = struct.pack(">ib", len(data) + 1, 2) + data
		record += bytes(-len(record) % 4096)
		slot = (cz & 31) * 32 + (cx & 31)
		struct.pack_into(">I", locations, slot * 4, ((2 + len(body) // 4096) << 8) | (len(record) // 4096))
		body += record
	Path(path).write_bytes(bytes(locations) + bytes(4096) + bytes(body))


def _pack(values, bits, spanning):
	n = len(values)
	if spanning:
		longs = [0] * math.ceil(n * bits / 64)
		for i, v in enumerate(values):
			word, shift = divmod(i * bits, 64)
			longs[word] |= (v << shift) & 0xFFFFFFFFFFFFFFFF
			if shift + bits > 64:
				longs[word + 1] |= v >> (64 - shift)
	else:
		per = 64 // bits
		longs = [0] * math.ceil(n / per)
		for i, v in enumerate(values):
			word, slot = divmod(i, per)
			longs[word] |= v << (slot * bits)
	return [v - (1 << 64) if v >= 1 << 63 else v for v in longs]


def _palette_entry(palette, name):
	"""A new palette entry for a property-less block, written the way the palette's others are."""
	full = "minecraft:" + name
	if palette.item == STRING:
		return Tag(STRING, full)
	if palette.value and list(palette.value[0].value) == [""]:
		return Tag(COMPOUND, {"": Tag(STRING, full)})
	if palette.value and "id" in palette.value[0]:
		return Tag(COMPOUND, {"id": Tag(STRING, full)})
	return Tag(COMPOUND, {"Name": Tag(STRING, full)})


def set_blocks(root, changes):
	"""Sets blocks in a chunk: changes maps (local x, y, local z) to a name as sections() gives it.
	Numeric names are "id:meta"; palette names must be blocks without properties."""
	level = chunk_level(root)
	key = "Sections" if "Sections" in level else "sections"
	data_version = root.get("DataVersion")
	spanning = data_version is not None and data_version.value < 2529
	by_section = {}
	for (x, y, z), name in changes.items():
		by_section.setdefault(y >> 4, {})[(y & 15) << 8 | z << 4 | x] = name
	for sy, edits in by_section.items():
		section = next((s for s in level[key].value if s["Y"].value == sy), None)
		numeric = any("Blocks" in s for s in level[key].value)
		if section is None:
			if numeric:
				section = Tag(COMPOUND, {"Y": Tag(BYTE, sy), "Blocks": Tag(BYTES, bytes(4096)), "Data": Tag(BYTES, bytes(2048)),
				                         "SkyLight": Tag(BYTES, b"\xff" * 2048), "BlockLight": Tag(BYTES, bytes(2048))})
			else:
				section = Tag(COMPOUND, {"Y": Tag(BYTE, sy), "Palette": Tag(LIST, [Tag(COMPOUND, {"Name": Tag(STRING, "minecraft:air")})], COMPOUND),
				                         "BlockStates": Tag(LONGS, [0] * 256)})
			level[key].value.append(section)
		if numeric:
			ids, meta = bytearray(section["Blocks"].value), bytearray(section["Data"].value)
			for i, name in edits.items():
				block, m = (0, 0) if name == "air" else (int(p) for p in name.split(":"))
				ids[i] = block
				meta[i >> 1] = (meta[i >> 1] & (0xF0 if i & 1 == 0 else 0x0F)) | (m << ((i & 1) * 4))
			section["Blocks"].value, section["Data"].value = bytes(ids), bytes(meta)
			continue
		states = section.get("block_states")
		holder, pkey, dkey = (states, "palette", "data") if states else (section, "Palette", "BlockStates")
		palette = holder[pkey]
		names = [_state(p) for p in palette.value]
		if dkey in holder:
			bits = max(4, math.ceil(math.log2(len(names))))
			values = _unpack(holder[dkey].value, bits, 4096, spanning)
		else:
			values = [0] * 4096
		for i, name in edits.items():
			if name not in names:
				palette.value.append(_palette_entry(palette, name))
				names.append(name)
			values[i] = names.index(name)
		bits = max(4, math.ceil(math.log2(len(names))))
		holder.value[dkey] = Tag(LONGS, _pack(values, bits, spanning))
