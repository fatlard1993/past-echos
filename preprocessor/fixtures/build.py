#!/usr/bin/env python3
"""Make a copy of each generated test world with a small build in it, and a manifest of every
block changed, so a diff of the two can be checked exactly.

The build sits in the middle chunk: a 7x7 hut on the median ground height (cobblestone floor,
plank walls with glass windows and a doorway, stone brick roof, a glowstone lamp in the floor, a
sign on the back wall inside) and a two-high tunnel dug through the stone below it. Reads
worlds/<version>.zip, writes worlds/<version>-built.zip holding the world and build.json.

usage: build.py [VERSION ...]    (no arguments: every world generate.py made)
"""

import io
import json
import re
import statistics
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import chunks  # noqa: E402

WORLDS = HERE.parent / "src/test/resources/worlds"
HUT_X, HUT_Z, HUT_SIZE, HUT_HEIGHT = 4, 2, 7, 4
TUNNEL_Z, TUNNEL_Y = 13, (24, 25)

SIGN_TEXT = ["past echoes", "test hut", "", ""]

# Wall sign meta 2 and facing=north both face into the hut from the south wall.
NUMERIC = {"floor": "4:0", "wall": "5:0", "window": "20:0", "lamp": "89:0", "roof": "98:0", "sign": "68:2", "air": "air"}
PALETTE = {"floor": "cobblestone", "wall": "oak_planks", "window": "glass", "lamp": "glowstone", "roof": "stone_bricks",
           "sign": "oak_wall_sign[facing=north,waterlogged=false]", "air": "air"}
# 26.x leaves default property values out, and north and dry are the defaults.
PALETTE_26 = dict(PALETTE, sign="oak_wall_sign")


def version_key(version):
	return tuple(int(p) for p in version.split("."))


def sign_entity(version, x, y, z):
	"""The sign's block entity as the version saves it: plain lines to 1.7, JSON lines from 1.8,
	front_text from 1.20, text components as NBT from 1.21.5."""
	v = version_key(version)
	tag = {"x": chunks.Tag(chunks.INT, x), "y": chunks.Tag(chunks.INT, y), "z": chunks.Tag(chunks.INT, z),
	       "id": chunks.Tag(chunks.STRING, "Sign" if v < (1, 11) else "minecraft:sign")}
	line = (lambda t: t) if v < (1, 8) or v >= (1, 21, 5) else (lambda t: json.dumps({"text": t}))
	if v < (1, 20):
		for i, text in enumerate(SIGN_TEXT, 1):
			tag[f"Text{i}"] = chunks.Tag(chunks.STRING, line(text))
	else:
		for side, lines in (("front_text", SIGN_TEXT), ("back_text", [""] * 4)):
			tag[side] = chunks.Tag(chunks.COMPOUND, {
				"messages": chunks.Tag(chunks.LIST, [chunks.Tag(chunks.STRING, line(t)) for t in lines], chunks.STRING),
				"color": chunks.Tag(chunks.STRING, "black"), "has_glowing_text": chunks.Tag(chunks.BYTE, 0)})
		tag["is_waxed"] = chunks.Tag(chunks.BYTE, 0)
	return chunks.Tag(chunks.COMPOUND, tag)


def canonical(state):
	"""States as the preprocessor compares them: cave and void air are air, leaves lose distance."""
	if state in ("cave_air", "void_air"):
		return "air"
	if "[" in state and state.split("[")[0].endswith("leaves"):
		name, props = state[:-1].split("[")
		props = ",".join(p for p in props.split(",") if not p.startswith("distance="))
		return f"{name}[{props}]" if props else name
	return state


def design(tops):
	"""{(local x, y, local z): part} for the hut and tunnel, the hut's floor at the median ground height."""
	floor = int(statistics.median(tops.values()))
	parts = {}
	last = HUT_SIZE - 1
	middle = HUT_SIZE // 2
	for dx in range(HUT_SIZE):
		for dz in range(HUT_SIZE):
			x, z = HUT_X + dx, HUT_Z + dz
			parts[x, floor, z] = "lamp" if dx == dz == middle else "floor"
			edge = dx in (0, last) or dz in (0, last)
			for dy in range(1, HUT_HEIGHT):
				if not edge:
					part = "air"
				elif dy == 2 and middle in (dx, dz):
					part = "window"
				else:
					part = "wall"
				# The doorway, in the middle of the north wall.
				if dz == 0 and dx == middle and dy in (1, 2):
					part = "air"
				parts[x, floor + dy, z] = part
			# The sign, inside against the back wall beside its window.
			if dx == middle + 1 and dz == last - 1:
				parts[x, floor + 2, z] = "sign"
			parts[x, floor + HUT_HEIGHT, z] = "roof"
	for x in range(16):
		for y in TUNNEL_Y:
			parts[x, y, TUNNEL_Z] = "air"
	return parts


def column_tops(sections, xs, zs):
	tops = {}
	for x in xs:
		for z in zs:
			for sy in sorted(sections, reverse=True):
				blocks = sections[sy]
				y = next((y for y in range(15, -1, -1) if blocks[y << 8 | z << 4 | x] != "air"), None)
				if y is not None:
					tops[x, z] = sy * 16 + y
					break
	return tops


def build(version):
	source = WORLDS / f"{version}.zip"
	with tempfile.TemporaryDirectory() as tmp:
		tmp = Path(tmp)
		with zipfile.ZipFile(source) as z:
			z.extractall(tmp)
		world = next(p.parent for p in tmp.rglob("level.dat"))
		regions = {f: list(chunks.region_chunks(f)) for f in chunks.overworld_regions(world)}
		every = sorted((cx, cz) for found in regions.values() for cx, cz, _ in found)
		cx = sorted(c[0] for c in every)[len(every) // 2]
		cz = sorted(c[1] for c in every)[len(every) // 2]
		region, root = next((f, r) for f, found in regions.items() for x, z, r in found if (x, z) == (cx, cz))

		before = chunks.sections(root)
		numeric = any(re.fullmatch(r"\d+:\d+", b) for blocks in before.values() for b in blocks[:16])
		names = NUMERIC if numeric else PALETTE_26 if version_key(version) >= (26,) else PALETTE
		tops = column_tops(before, range(HUT_X, HUT_X + HUT_SIZE), range(HUT_Z, HUT_Z + HUT_SIZE))
		changes, manifest = {}, []
		parts = design(tops)
		for (x, y, z), part in sorted(parts.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
			was = canonical(before[y >> 4][(y & 15) << 8 | z << 4 | x]) if y >> 4 in before else "air"
			now = names[part]
			if was == now:
				continue
			changes[x, y, z] = now
			manifest.append({"x": cx * 16 + x, "y": y, "z": cz * 16 + z, "before": was, "after": now})
		chunks.set_blocks(root, changes)
		sign = next((cx * 16 + x, y, cz * 16 + z) for (x, y, z), part in parts.items() if part == "sign")
		chunks.add_block_entity(root, sign_entity(version, *sign))
		chunks.write_region(region, regions[region])

		# Read back through the same decoder, so the manifest is what the edited file holds.
		edited = dict(((x, z), r) for x, z, r in chunks.region_chunks(region))[cx, cz]
		after = chunks.sections(edited)
		for m in manifest:
			x, y, z = m["x"] - cx * 16, m["y"], m["z"] - cz * 16
			got = canonical(after[y >> 4][(y & 15) << 8 | z << 4 | x])
			if got != m["after"]:
				sys.exit(f"{version}: wrote {m['after']} at {x},{y},{z} but read back {got}")

		target = WORLDS / f"{version}-built.zip"
		with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as out:
			for f in sorted(world.rglob("*")):
				if f.is_file():
					out.write(f, f.relative_to(world.parent))
			out.writestr("build.json", json.dumps({"chunk": [cx, cz], "blocks": manifest,
			                                       "sign": {"x": sign[0], "y": sign[1], "z": sign[2], "text": SIGN_TEXT}}, indent=1))
		print(f"{version}: {len(manifest)} blocks changed in chunk {cx},{cz} -> {target.name} ({target.stat().st_size:,} bytes)")


def main():
	versions = sys.argv[1:] or sorted(p.stem for p in WORLDS.glob("*.zip") if not p.stem.endswith("-built"))
	for version in versions:
		build(version)


if __name__ == "__main__":
	main()
