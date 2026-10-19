#!/usr/bin/env python3
"""Fetch real saves from open-source Minecraft tools for the eras no official server jar covers.

Each file comes from its repo's own URL, is trimmed where it is large (whole chunks dropped,
never chunk contents altered), and lands in src/test/resources/found/. NOTICE.md there carries
each repo's license text, fetched alongside.

usage: vendor.py
"""

import io
import json
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate import trim_region  # noqa: E402

HERE = Path(__file__).resolve().parent
OUT = HERE.parent / "src/test/resources/found"
PYMCLEVEL = "https://raw.githubusercontent.com/mcedit/pymclevel/master"

# R packages declare "MIT + file LICENSE" and keep only the year and holder in LICENSE;
# the MIT text itself is implied, so it is written out here for the notice.
MIT = """Copyright (c) {year} {holder}

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES
OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""

REPOS = {
    "mcedit/pymclevel": "Saves from pymclevel's test files (Classic, Indev, Alpha, Beta, early Anvil, Pocket).",
    "radmanplays/rd-132211": "A pre-Classic rd-132211 level.",
    "Voziv/MCSharp": "A Classic server level from MCSharp's level converter test maps.",
    "minecraft8997/CW2YLV": "A ClassicWorld level.",
    "reedacartwright/rbedrock": "A Bedrock world exported as .mcworld.",
}

# (destination under found/, source URL, how to keep it small)
FILES = [
    ("rd-132211/level.dat", "https://raw.githubusercontent.com/radmanplays/rd-132211/master/desktopRuntime/level.dat", None),
    ("classic/server_level(flatgrass256).dat", "https://raw.githubusercontent.com/Voziv/MCSharp/master/LevelConverter/TestMaps/server_level%28flatgrass256%29.dat", None),
    ("classic/Dojo_64_64_128.dat", f"{PYMCLEVEL}/testfiles/Dojo_64_64_128.dat", None),
    ("indev/hell.mclevel", f"{PYMCLEVEL}/testfiles/hell.mclevel", None),
    ("indev/synthetic.mclevel", f"{PYMCLEVEL}/testfiles/synthetic.mclevel", None),
    ("alpha.tar.gz", f"{PYMCLEVEL}/regression_test/alpha.tar.gz", ("alpha-subset", 30)),
    ("PyTestWorld/level.dat", f"{PYMCLEVEL}/testfiles/PyTestWorld/level.dat", None),
    ("PyTestWorld/region/r.-1.-1.mcr", f"{PYMCLEVEL}/testfiles/PyTestWorld/region/r.-1.-1.mcr", ("region", 10)),
    ("AnvilWorld/level.dat", f"{PYMCLEVEL}/testfiles/AnvilWorld/level.dat", None),
    ("AnvilWorld/region/r.-1.0.mca", f"{PYMCLEVEL}/testfiles/AnvilWorld/region/r.-1.0.mca", ("region", 10)),
    ("PocketWorld/level.dat", f"{PYMCLEVEL}/testfiles/PocketWorld/level.dat", None),
    ("cw/test.cw", "https://raw.githubusercontent.com/minecraft8997/CW2YLV/master/test.cw", None),
    ("bedrock/example3.mcworld", "https://raw.githubusercontent.com/reedacartwright/rbedrock/main/inst/extdata/example3.mcworld", None),
]


def fetch(url):
    with urllib.request.urlopen(url) as r:
        return r.read()


def first_chunks(n):
    seen = []

    def keep(cx, cz):
        if len(seen) < n:
            seen.append((cx, cz))
            return True
        return False

    return keep


def alpha_subset(data, n):
    """The tarball's level.dat and its first n chunk files, repacked; read in memory, never extracted."""
    src = tarfile.open(fileobj=io.BytesIO(data), mode="r:gz")
    members = [m for m in src.getmembers() if m.isfile()]
    chunks = sorted((m for m in members if m.name.split("/")[-1].startswith("c.")), key=lambda m: m.name)[:n]
    keep = [m for m in members if m.name.endswith("/level.dat")] + chunks
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode="w:gz") as dst:
        for m in keep:
            dst.addfile(m, src.extractfile(m))
    return out.getvalue()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for dest, url, shrink in FILES:
        target = OUT / dest
        target.parent.mkdir(parents=True, exist_ok=True)
        data = fetch(url)
        if shrink is None:
            target.write_bytes(data)
        elif shrink[0] == "alpha-subset":
            target.write_bytes(alpha_subset(data, shrink[1]))
        elif shrink[0] == "region":
            # trim_region reads the region's coordinates from its file name, so the download keeps it.
            with tempfile.TemporaryDirectory() as tmp:
                full = Path(tmp) / target.name
                full.write_bytes(data)
                trim_region(full, target, first_chunks(shrink[1]))
        print(f"{dest}: {target.stat().st_size:,} bytes")

    notice = ["# Third-party test saves", "",
              "Every file under this folder comes from the repository named beside it, under that",
              "repository's license, reproduced below. Large files were trimmed by dropping whole chunks.", ""]
    for repo, what in REPOS.items():
        info = json.loads(fetch(f"https://api.github.com/repos/{repo}/license"))
        text = fetch(info["download_url"]).decode("utf-8", "replace").strip()
        spdx = info["license"]["spdx_id"]
        if spdx == "NOASSERTION" and text.startswith("YEAR:"):
            stub = dict(line.split(": ", 1) for line in text.splitlines())
            spdx, text = "MIT", MIT.format(year=stub["YEAR"], holder=stub["COPYRIGHT HOLDER"])
        notice += [f"## {repo}", "", what, f"License: {spdx}", "", "```", text, "```", ""]
    (OUT / "NOTICE.md").write_text("\n".join(notice))
    print("NOTICE.md written")


if __name__ == "__main__":
    main()
