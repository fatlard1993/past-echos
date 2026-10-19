#!/usr/bin/env python3
"""Generate small real worlds with Mojang's official server jars, for the inventory tests.

Each version is downloaded from Mojang (SHA-1 checked against the version manifest), started
headless with a fixed seed until its spawn area exists, stopped, and trimmed to the chunks
around spawn. The result lands in src/test/resources/worlds/<version>.zip.

Versions from 1.7.10 on refuse to start without eula=true; running this writes it, which
accepts the Minecraft EULA (https://aka.ms/MinecraftEULA) for these throwaway servers.

usage: generate.py [VERSION ...]    (no arguments: every version in VERSIONS)
"""

import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path

VERSIONS = ["1.2.5", "1.6.4", "1.8.9", "1.12.2", "1.16.5", "1.18.2", "1.20.4", "26.3"]
SEED = "8675309"
KEEP_RADIUS = 2
# From 1.13.1 the spawn area is forceloaded too: 26.x keeps no spawn chunks, so would leave
# only the chunk under spawn finished. The console runs commands at the world spawn.
FORCELOAD = "forceload add ~-40 ~-40 ~40 ~40"
FORCELOAD_WAIT = 30
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

HERE = Path(__file__).resolve().parent
CACHE = HERE / ".cache"
OUT = HERE.parent / "src/test/resources/worlds"


def fetch_json(url):
    with urllib.request.urlopen(url) as r:
        return json.load(r)


def server_jar(version, manifest):
    entry = next((v for v in manifest["versions"] if v["id"] == version), None)
    if entry is None:
        sys.exit(f"{version}: not in Mojang's version manifest")
    server = fetch_json(entry["url"])["downloads"].get("server")
    if server is None:
        sys.exit(f"{version}: Mojang publishes no server jar for it")
    jar = CACHE / f"server-{version}.jar"
    if not jar.exists() or hashlib.sha1(jar.read_bytes()).hexdigest() != server["sha1"]:
        CACHE.mkdir(exist_ok=True)
        with urllib.request.urlopen(server["url"]) as r:
            data = r.read()
        if hashlib.sha1(data).hexdigest() != server["sha1"]:
            sys.exit(f"{version}: download does not match the manifest's SHA-1")
        jar.write_bytes(data)
    return jar


def has_forceload(version):
    parts = [int(p) for p in version.split(".")]
    return parts >= [1, 13, 1]


def run_server(jar, workdir, forceload=False):
    (workdir / "eula.txt").write_text("eula=true\n")
    (workdir / "server.properties").write_text(
        f"level-seed={SEED}\nonline-mode=false\nserver-port=0\nlevel-name=world\nspawn-protection=0\n"
    )
    proc = subprocess.Popen(
        ["java", "-Xmx2G", "-jar", str(jar), "nogui"],
        cwd=workdir, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    log = []
    for line in proc.stdout:
        log.append(line)
        if "Done (" in line:
            if forceload:
                proc.stdin.write(FORCELOAD + "\n")
                proc.stdin.flush()
                time.sleep(FORCELOAD_WAIT)
            proc.stdin.write("save-all\nstop\n")
            proc.stdin.flush()
    proc.wait(timeout=300)
    if not (workdir / "world/level.dat").exists():
        sys.exit("server produced no world:\n" + "".join(log[-30:]))


# -- region trimming ----------------------------------------------------------------------

def trim_region(src, dst, keep):
    """Rewrite a region file holding only the chunk slots for which keep(cx, cz) is true."""
    raw = src.read_bytes()
    # Servers leave 0-byte region files where a region was opened and nothing saved.
    if len(raw) < 8192:
        return 0
    rx, rz = (int(p) for p in src.name.split(".")[1:3])
    locations = bytearray(4096)
    timestamps = bytearray(4096)
    body = bytearray()
    kept = 0
    for slot in range(1024):
        (loc,) = struct.unpack_from(">I", raw, slot * 4)
        if loc == 0:
            continue
        cx, cz = rx * 32 + slot % 32, rz * 32 + slot // 32
        if not keep(cx, cz):
            continue
        offset, sectors = (loc >> 8) * 4096, loc & 0xFF
        chunk = raw[offset:offset + sectors * 4096]
        struct.pack_into(">I", locations, slot * 4, ((2 + len(body) // 4096) << 8) | sectors)
        timestamps[slot * 4:slot * 4 + 4] = raw[4096 + slot * 4:4096 + slot * 4 + 4]
        body += chunk
        kept += 1
    if kept:
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(bytes(locations) + bytes(timestamps) + bytes(body))
    return kept


def present_chunks(region_file):
    raw = region_file.read_bytes()
    if len(raw) < 8192:
        return []
    rx, rz = (int(p) for p in region_file.name.split(".")[1:3])
    return [(rx * 32 + slot % 32, rz * 32 + slot // 32)
            for slot in range(1024) if struct.unpack_from(">I", raw, slot * 4)[0]]


def trimmed_world(world, out):
    regions = [f for d in (world / "region", world / "dimensions/minecraft/overworld/region") if d.is_dir()
               for f in sorted(d.glob("r.*.mca"))]
    # The middle of what was generated, not level.dat's spawn: 1.2.5 saves 0,0 there while its
    # spawn area sits hundreds of blocks away.
    chunks = sorted(c for f in regions for c in present_chunks(f))
    if not chunks:
        sys.exit("no chunks generated")
    cx = sorted(c[0] for c in chunks)[len(chunks) // 2]
    cz = sorted(c[1] for c in chunks)[len(chunks) // 2]
    keep = lambda x, z: abs(x - cx) <= KEEP_RADIUS and abs(z - cz) <= KEEP_RADIUS
    shutil.copy2(world / "level.dat", out / "level.dat")
    settings = world / "data/minecraft/world_gen_settings.dat"
    if settings.exists():
        (out / settings.parent.relative_to(world)).mkdir(parents=True)
        shutil.copy2(settings, out / settings.relative_to(world))
    return sum(trim_region(f, out / f.relative_to(world), keep) for f in regions)


def main():
    versions = sys.argv[1:] or VERSIONS
    manifest = fetch_json(MANIFEST)
    OUT.mkdir(parents=True, exist_ok=True)
    for version in versions:
        jar = server_jar(version, manifest)
        with tempfile.TemporaryDirectory(prefix=f"fixture-{version}-") as tmp:
            tmp = Path(tmp)
            run_server(jar, tmp, has_forceload(version))
            trimmed = tmp / "trimmed" / f"world-{version}"
            trimmed.mkdir(parents=True)
            chunks = trimmed_world(tmp / "world", trimmed)
            target = OUT / f"{version}.zip"
            with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
                for f in sorted(trimmed.rglob("*")):
                    if f.is_file():
                        z.write(f, f.relative_to(trimmed.parent))
            print(f"{version}: {chunks} chunks, {target.stat().st_size:,} bytes -> {target.relative_to(HERE.parent)}")


if __name__ == "__main__":
    main()
