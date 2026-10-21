#!/usr/bin/env python3
"""Generate Past Echos' mod menu icon: a weathered wall, and the wall it used to be.

What still stands is stone brick on grass, cracked and mossy and holed the way the mod's
own weathering leaves a ruin. What is gone is drawn as a pale outline over it: the build
the ruin is an echo of. Block textures are the vanilla jar's own, one block to sixteen
pixels, eight blocks across.

Pure stdlib PNG reader and writer (zlib + struct) so it runs without Pillow, the same
script generated art approach as the rest of the suite. Deterministic: re-running
produces identical bytes.

Usage: python3 generate_icon.py [path/to/minecraft.jar]
"""

import glob
import os
import struct
import sys
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "src/main/resources/assets/past-echos-justfatlard/icon.png")

CLEAR = (0, 0, 0, 0)
_JAR = None


def minecraft_version():
    """The version this mod targets, so the sprite is cut from the same jar the
    mod is built against rather than whatever happens to be cached."""
    path = os.path.join(HERE, "gradle.properties")
    if not os.path.exists(path):
        return None
    for line in open(path):
        key, sep, value = line.partition("=")
        if sep and key.strip() == "minecraft_version":
            return value.strip()
    return None


def find_jar():
    """Loom caches the remapped Minecraft jars after a build; that is where the
    vanilla art comes from. Override with an argument or $MINECRAFT_JAR."""
    global _JAR
    if _JAR:
        return _JAR
    if len(sys.argv) > 1:
        _JAR = sys.argv[1]
        return _JAR
    if os.environ.get("MINECRAFT_JAR"):
        _JAR = os.environ["MINECRAFT_JAR"]
        return _JAR
    cache = os.path.expanduser("~/.gradle/caches/fabric-loom")
    names = ("minecraft-merged.jar", "minecraft-client.jar")
    found = []
    version = minecraft_version()
    if version:
        for name in names:
            found += glob.glob(os.path.join(cache, version, name))
    if not found:
        for name in names:
            found += glob.glob(os.path.join(cache, "*", name))
    if not found:
        sys.exit("no cached Minecraft jar found: build the mod once, "
                 "or pass a jar path as the first argument")
    _JAR = max(found, key=os.path.getmtime)
    return _JAR


def vanilla(name):
    """Read assets/minecraft/textures/<name> out of the vanilla jar."""
    with zipfile.ZipFile(find_jar()) as jar:
        return decode_png(jar.read("assets/minecraft/textures/" + name))


def decode_png(data):
    """Minimal PNG reader: no interlacing, every colour type and bit depth
    vanilla actually ships. Returns rows of RGBA tuples."""
    pos = 8
    idat = b""
    width = height = depth = ctype = None
    palette = trns = None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        tag = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if tag == b"IHDR":
            width, height, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert interlace == 0, "interlaced PNG not supported"
        elif tag == b"PLTE":
            palette = body
        elif tag == b"tRNS":
            trns = body
        elif tag == b"IDAT":
            idat += body
        elif tag == b"IEND":
            break

    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    stride = (width * channels * depth + 7) // 8
    step = max(1, (channels * depth) // 8)
    raw = zlib.decompress(idat)
    out = bytearray(stride * height)
    prev = bytearray(stride)
    p = 0
    for y in range(height):
        filt = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if filt == 1:
            for i in range(step, stride):
                line[i] = (line[i] + line[i - step]) & 0xFF
        elif filt == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif filt == 3:
            for i in range(stride):
                a = line[i - step] if i >= step else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif filt == 4:
            for i in range(stride):
                a = line[i - step] if i >= step else 0
                b = prev[i]
                c = prev[i - step] if i >= step else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line

    pixels = []
    if depth < 8:
        per = 8 // depth
        mask = (1 << depth) - 1
        for y in range(height):
            base = y * stride
            row = []
            for x in range(width):
                i = x * channels
                value = (out[base + i // per] >> (8 - depth * (i % per + 1))) & mask
                if ctype == 3:
                    r, g, b = palette[value * 3:value * 3 + 3]
                    a = trns[value] if trns and value < len(trns) else 255
                    row.append((r, g, b, a))
                else:
                    v = value * 255 // mask
                    row.append((v, v, v, 255))
            pixels.append(row)
        return pixels

    for y in range(height):
        base = y * stride
        row = []
        for x in range(width):
            i = base + x * channels
            if ctype == 6:
                row.append(tuple(out[i:i + 4]))
            elif ctype == 2:
                row.append((out[i], out[i + 1], out[i + 2], 255))
            elif ctype == 4:
                row.append((out[i], out[i], out[i], out[i + 1]))
            elif ctype == 0:
                row.append((out[i], out[i], out[i], 255))
            else:
                r, g, b = palette[out[i] * 3:out[i] * 3 + 3]
                a = trns[out[i]] if trns and out[i] < len(trns) else 255
                row.append((r, g, b, a))
        pixels.append(row)
    return pixels


def write_png(path, pixels):
    """pixels: rows of RGBA tuples."""
    height = len(pixels)
    width = len(pixels[0])
    raw = b"".join(b"\x00" + b"".join(bytes(px) for px in row) for row in pixels)

    def chunk(tag, body):
        c = tag + body
        return struct.pack(">I", len(body)) + c + struct.pack(">I", zlib.crc32(c))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)
    print("wrote %s (%dx%d)" % (path, width, height))


def shade(px, factor):
    return tuple(min(255, int(c * factor)) for c in px[:3]) + (px[3],)


# The wall as it was built, rows top down, one character a block: B stone bricks, W window.
BUILT = [
    "........",
    ".BBBBBB.",
    ".BBBBBB.",
    ".BWWBBB.",
    ".BWWBBB.",
    ".BBBBBB.",
    ".BBBBBB.",
    "GGGGGGGG",
]

# What still stands: the top broken off unevenly, the window knocked wider. Letters pick the
# weathered block, as the mod's processor list does: c cracked, m mossy, b as laid.
STANDING = [
    "........",
    "........",
    ".m......",
    ".b...c..",
    ".c...bm.",
    ".bmbcmb.",
    ".mbcbbc.",
    "GGGGGGGG",
]

BLOCKS = {
    "b": "block/stone_bricks.png",
    "c": "block/cracked_stone_bricks.png",
    "m": "block/mossy_stone_bricks.png",
}

# The echo of what is gone: the same bricks, washed pale and seen through, outlined where the
# lost wall met open air.
ECHO_TINT = (190, 225, 255)
ECHO_ALPHA = 110
ECHO_EDGE = (215, 240, 255, 230)


def ghost(texture):
    """A texture as the echo draws it: its light and dark kept, its colour washed to one pale tint."""
    out = []
    for row in texture:
        line = []
        for r, g, b, a in row:
            lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255
            k = 0.55 + 0.45 * lum
            line.append(tuple(int(c * k) for c in ECHO_TINT) + (ECHO_ALPHA,))
        out.append(line)
    return out


def build_icon():
    canvas = [[CLEAR] * 128 for _ in range(128)]
    textures = {key: vanilla(path)[:16] for key, path in BLOCKS.items()}
    # 26.x bakes the green into the side texture, so it needs no tint.
    textures["G"] = vanilla("block/grass_block_side.png")[:16]
    echo = ghost(textures["b"])

    def standing(bx, by):
        return STANDING[by][bx] != "."

    def gone(bx, by):
        return 0 <= by < 8 and 0 <= bx < 8 and BUILT[by][bx] == "B" and not standing(bx, by)

    def was_built(bx, by):
        return 0 <= by < 8 and 0 <= bx < 8 and BUILT[by][bx] in "BW"

    for by in range(8):
        for bx in range(8):
            key = STANDING[by][bx]
            if key != ".":
                tex = textures[key]
                for y in range(16):
                    for x in range(16):
                        canvas[by * 16 + y][bx * 16 + x] = tex[y][x]
            elif gone(bx, by):
                # A block's outline only where it meets something that was not built, or that
                # still stands: the echo is the lost wall's silhouette, not a grid of squares.
                for y in range(16):
                    for x in range(16):
                        edge = False
                        if y == 0 and not gone(bx, by - 1):
                            edge = True
                        if y == 15 and not gone(bx, by + 1) and not standing(bx, by + 1):
                            edge = True
                        if x == 0 and not gone(bx - 1, by):
                            edge = True
                        if x == 15 and not gone(bx + 1, by):
                            edge = True
                        canvas[by * 16 + y][bx * 16 + x] = ECHO_EDGE if edge else echo[y][x]
    return canvas


if __name__ == "__main__":
    icon = build_icon()
    assert len(icon) == 128 and len(icon[0]) == 128, "mod menu icons are 128x128"
    write_png(OUT, icon)
