#!/usr/bin/env python3
"""Make the stage-one diffs the extraction tests read, with the past-echos command line.

  diffs/<version>-built.diff.gz  each built test world (build.py) regenerated and diffed: the hut
                                 and tunnel among real regeneration noise
  diffs/<version>-noise.diff.gz  one generation of a seed diffed against another made in a different
                                 order (experiments/determinism.py --keep): noise and nothing else
  diffs/<version>-seed<N>-noise.diff.gz
                                 a fresh untouched world on seed N regenerated and diffed: noise from
                                 terrain the others lack (an ancient city, dungeons, lava meeting water)

Needs ./gradlew :preprocessor:installDist first. Regenerating runs Mojang's server jars, which
accepts the Minecraft EULA (https://aka.ms/MinecraftEULA) for those servers.

usage: diffs.py [--noise KEPT] [VERSION ...]    (KEPT: the folder determinism.py --keep wrote;
                                                 without it only the built diffs are made)
       diffs.py --seeded VERSION:SEED ...
"""

import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESOURCES = HERE.parent / "src/test/resources"
CLI = HERE.parent / "build/install/past-echos/bin/past-echos"
SEEDED_RADIUS = 112  # blocks: forceloads 15x15 chunks, as the spawn area gives older versions
SEEDED_WAIT = 120

sys.path.insert(0, str(HERE))
from generate import MANIFEST, fetch_json, has_forceload, server_jar  # noqa: E402


def seeded(version, seed, manifest):
	"""Generates an untouched world on the seed, then regenerates and diffs it."""
	with tempfile.TemporaryDirectory() as tmp:
		work = Path(tmp)
		(work / "eula.txt").write_text("eula=true\n")
		(work / "server.properties").write_text(
			f"level-seed={seed}\nonline-mode=false\nserver-ip=127.0.0.1\nserver-port=0\nlevel-name=world\nwhite-list=true\n")
		proc = subprocess.Popen(["java", "-Xmx3G", "-Dlog4j2.formatMsgNoLookups=true", "-jar", str(server_jar(version, manifest)), "nogui"],
		                        cwd=work, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
		for line in proc.stdout:
			if "Done (" in line:
				if has_forceload(version):
					r = SEEDED_RADIUS
					proc.stdin.write(f"forceload add ~-{r} ~-{r} ~{r} ~{r}\n")
					proc.stdin.flush()
					time.sleep(SEEDED_WAIT)
				proc.stdin.write("stop\n")
				proc.stdin.flush()
		proc.wait()
		diff(RESOURCES / f"diffs/{version}-seed{seed}-noise.diff.gz", "--version", version, str(work / "world"))


def diff(out, *args):
	with tempfile.TemporaryDirectory() as tmp:
		subprocess.run([str(CLI), "diff", "--out", tmp, *args], check=True)
		shutil.copyfile(Path(tmp) / "overworld.diff.gz", out)
	print(f"-> {out.relative_to(RESOURCES)}")


def main():
	args = sys.argv[1:]
	kept = None
	if args[:1] == ["--seeded"]:
		manifest = fetch_json(MANIFEST)
		for spec in args[1:]:
			version, seed = spec.split(":")
			seeded(version, seed, manifest)
		return
	if args[:1] == ["--noise"]:
		kept, args = Path(args[1]), args[2:]
	if not CLI.exists():
		sys.exit(f"no {CLI}; run ./gradlew :preprocessor:installDist")
	versions = args or sorted(p.name.removesuffix("-built.zip") for p in (RESOURCES / "worlds").glob("*-built.zip"))
	(RESOURCES / "diffs").mkdir(exist_ok=True)
	for version in versions:
		diff(RESOURCES / f"diffs/{version}-built.diff.gz", "--version", version, str(RESOURCES / f"worlds/{version}-built.zip"))
		if kept is not None:
			diff(RESOURCES / f"diffs/{version}-noise.diff.gz", "--version", version,
			     "--reference", str(kept / version / "moved/world"), str(kept / version / "a/world"))


if __name__ == "__main__":
	main()
