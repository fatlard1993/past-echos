#!/usr/bin/env python3
"""How much does regenerating a seed differ from the original when nobody built anything?

Per version, three fresh worlds from one seed:
  A        the server's usual first start
  control  A again, same order, to catch plain non-determinism
  moved    the same chunks generated after a neighbouring area, so in a different order with
           different neighbours already present
and compares blocks in the chunks decorated in all three, neighbours included.

Before 1.13 the order is moved by rewriting level.dat's spawn (the server generates its spawn
area around it); from 1.13.1 on by /forceload, since newer servers keep few or no spawn chunks.

usage: determinism.py [--keep DIR] VERSION ...
"""

import json
import shutil
import subprocess
import sys
import tempfile
import time
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parent / "fixtures")]
import chunks  # noqa: E402
from generate import MANIFEST, SEED, fetch_json, present_chunks, server_jar  # noqa: E402

OFFSET = 6  # chunks between the two areas: enough to change neighbours, little enough to overlap
FORCELOAD_RADIUS = 7
FORCELOAD_WAIT = 90  # seconds; checked afterwards against the chunks actually finished
FORCELOAD_FROM = (1, 13, 1)


def version_key(v):
	return tuple(int(p) for p in v.split("."))


def server(jar, workdir, commands=(), level=None):
	"""Start a server in workdir, run each (command, seconds to wait after) once it is up, stop it."""
	workdir.mkdir(parents=True, exist_ok=True)
	(workdir / "eula.txt").write_text("eula=true\n")
	(workdir / "server.properties").write_text(
		f"level-seed={SEED}\nonline-mode=false\nserver-port=0\nlevel-name=world\nspawn-protection=0\n"
	)
	if level is not None:
		(workdir / "world").mkdir()
		chunks.write_level(workdir / "world/level.dat", level)
	proc = subprocess.Popen(
		["java", "-Xmx3G", "-jar", str(jar), "nogui"],
		cwd=workdir, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
	)
	log = []
	for line in proc.stdout:
		log.append(line)
		if "Done (" in line:
			for command, wait in commands:
				proc.stdin.write(command + "\n")
				proc.stdin.flush()
				time.sleep(wait)
			proc.stdin.write("save-all\nstop\n")
			proc.stdin.flush()
	proc.wait(timeout=600)
	if not (workdir / "world/level.dat").exists():
		sys.exit("server produced no world:\n" + "".join(log[-30:]))
	return workdir / "world"


def forceload(cx, cz):
	r = FORCELOAD_RADIUS
	return (f"forceload add {(cx - r) * 16} {(cz - r) * 16} {(cx + r) * 16 + 15} {(cz + r) * 16 + 15}", FORCELOAD_WAIT)


def generated_centre(world):
	found = sorted(c for f in chunks.overworld_regions(world) for c in present_chunks(f))
	return sorted(c[0] for c in found)[len(found) // 2], sorted(c[1] for c in found)[len(found) // 2]


def moved_spawn(level, dx, dz):
	data = level["Data"]
	if "spawn" in data:
		data["spawn"]["pos"].value[0] += dx
		data["spawn"]["pos"].value[2] += dz
	else:
		data["SpawnX"].value += dx
		data["SpawnZ"].value += dz
	return level


def load(world):
	return {(x, z): r for f in chunks.overworld_regions(world) for x, z, r in chunks.region_chunks(f)}


def comparable(*worlds):
	"""Chunks decorated in every world whose eight neighbours are too, so no pending spill-over remains."""
	done = set.intersection(*({c for c, r in w.items() if chunks.finished(r)} for w in worlds))
	return sorted(c for c in done if all((c[0] + dx, c[1] + dz) in done for dx in (-1, 0, 1) for dz in (-1, 0, 1)))


def diff(a, b, keys):
	pairs = Counter()
	per_chunk = {}
	heights = Counter()
	for key in keys:
		sa, sb = chunks.sections(a[key]), chunks.sections(b[key])
		n = 0
		for y in sa.keys() | sb.keys():
			ba = sa.get(y) or ["air"] * 4096
			bb = sb.get(y) or ["air"] * 4096
			for i in range(4096):
				if ba[i] != bb[i]:
					n += 1
					pairs[ba[i], bb[i]] += 1
					heights[(y * 16 + i // 256) // 8 * 8] += 1
		per_chunk[key] = n
	return {
		"chunks": len(keys),
		"chunks_differing": sum(1 for n in per_chunk.values() if n),
		"blocks_differing": sum(per_chunk.values()),
		"per_chunk": sorted(per_chunk.values()),
		"pairs": [[x, y, n] for (x, y), n in pairs.most_common(25)],
		"heights": dict(sorted(heights.items())),
	}


def run(version, jar, root):
	if version_key(version) >= FORCELOAD_FROM:
		a = server(jar, root / "a", [forceload(0, 0)])
		control = server(jar, root / "control", [forceload(0, 0)])
		moved = server(jar, root / "moved", [forceload(OFFSET, OFFSET), forceload(0, 0)])
	else:
		a = server(jar, root / "a")
		control = server(jar, root / "control")
		cx, cz = generated_centre(a)
		level = moved_spawn(chunks.read_level(a / "level.dat"), OFFSET * 16, OFFSET * 16)
		# 1.2.5 saves its spawn as 0,0 whatever it was, so place it from the chunks generated.
		data = level["Data"]
		if "SpawnX" in data:
			data["SpawnX"].value, data["SpawnZ"].value = cx * 16 + 8 + OFFSET * 16, cz * 16 + 8 + OFFSET * 16
		moved = server(jar, root / "moved", level=level)
	wa, wc, wm = load(a), load(control), load(moved)
	keys = comparable(wa, wc, wm)
	return {
		"version": version,
		"loaded": {"a": len(wa), "control": len(wc), "moved": len(wm)},
		"control": diff(wa, wc, keys),
		"moved": diff(wa, wm, keys),
		"moved_centre": generated_centre(moved),
		"a_centre": generated_centre(a),
	}


def summary(result):
	lines = [f"== {result['version']}  (loaded {result['loaded']}, centres a {result['a_centre']} moved {result['moved_centre']})"]
	for name in ("control", "moved"):
		d = result[name]
		per = d["per_chunk"]
		lines.append(
			f"  {name:8} {d['chunks_differing']}/{d['chunks']} chunks differ, {d['blocks_differing']} blocks"
			+ (f" (median {per[len(per) // 2]}, max {per[-1]} per chunk)" if per else "")
		)
		for x, y, n in d["pairs"][:12]:
			lines.append(f"      {n:7}  {x} -> {y}")
	return "\n".join(lines)


def main():
	args = sys.argv[1:]
	keep = None
	if args[:1] == ["--keep"]:
		keep, args = Path(args[1]), args[2:]
	manifest = fetch_json(MANIFEST)
	results = []
	for version in args:
		jar = server_jar(version, manifest)
		with tempfile.TemporaryDirectory(prefix=f"determinism-{version}-") as tmp:
			root = Path(tmp)
			result = run(version, jar, root)
			if keep:
				shutil.copytree(root, keep / version, dirs_exist_ok=True, ignore=shutil.ignore_patterns("*.jar", "libraries", "versions"))
		print(summary(result), flush=True)
		results.append(result)
	out = HERE / "determinism.json"
	out.write_text(json.dumps(results, indent=1))
	print(f"details: {out}")


if __name__ == "__main__":
	main()
