#!/usr/bin/env python3
"""Builds the index the app's online model browser searches.

The index lists the names of the .model and .subckt definitions in the community
KiCad-Spice-Library (https://github.com/kicad-spice-library/KiCad-Spice-Library),
with each one's type, pin count and the file it lives in. It contains no model
data: the app downloads a file from GitHub only when the user picks a model, so
each file reaches the user under its maker's own license.

    git clone --depth 1 https://github.com/kicad-spice-library/KiCad-Spice-Library /tmp/ksl
    scripts/build-model-index.py /tmp/ksl app/src/main/assets/online-models.tsv.gz [failing.tsv]

To see how many of the files ngspice loads once the app has tidied them, write
test decks with OnlineModelsTest.writeSurveyDecks (PEPPERJACK_ONLINE_LIBRARY=/tmp/ksl
PEPPERJACK_SURVEY_OUT=<folder> ./gradlew testDebugUnitTest --tests '*writeSurveyDecks')
and run each through `ngspice -b` with `set ngbehavior=ltpsa` in that folder's .spiceinit.
List the models ngspice could not load as "<path>\t<name>" lines in failing.tsv and
pass it as the third argument; the app marks those models.

Output (gzip, tab-separated, one record per line):
    S <repository> <commit>
    F <path> <bytes>
    M <name> <type> <pins> <file number> [1 if ngspice could not load it]
"""
import gzip
import os
import re
import subprocess
import sys

REPOSITORY = "kicad-spice-library/KiCad-Spice-Library"
MODEL = re.compile(r"^\s*\.model\s+(\S+)\s+([A-Za-z_]+)", re.IGNORECASE)
SUBCKT = re.compile(r"^\s*\.subckt\s+(\S+)(.*)$", re.IGNORECASE)
ENDS = re.compile(r"^\s*\.ends\b", re.IGNORECASE)


def logical_lines(text):
    """SPICE lines with '+' continuations folded in, as ngspice reads them: a
    continuation belongs to the last line that is not a comment or blank.
    Comments are dropped. Matches ModelLibrary.parse in the app."""
    current = None
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("*"):
            continue
        if line.startswith("+") and current is not None:
            current += " " + line[1:].strip()
            continue
        if current is not None:
            yield current
        current = line
    if current is not None:
        yield current


def definitions(text):
    """Top-level definitions only: models inside a .subckt are local to it. As
    ModelLibrary.clean, a file with fewer .ends than .subckt lines is read flat,
    each subcircuit ending where the next begins."""
    lines = list(logical_lines(text))
    unclosed = sum(1 for l in lines if SUBCKT.match(l)) > sum(1 for l in lines if ENDS.match(l))
    depth = 0
    for line in lines:
        if ENDS.match(line):
            depth = max(0, depth - 1)
            continue
        m = MODEL.match(line)
        if m:
            if depth == 0 and m.group(2).upper() != "AKO":
                yield m.group(1), m.group(2).upper(), 0
            continue
        s = SUBCKT.match(line)
        if s:
            if depth == 0 or not unclosed:
                depth += 1
            if depth > 1:
                continue
            pins = []
            for token in s.group(2).split():
                if "=" in token or token.lower().startswith("params:"):
                    break
                pins.append(token)
            yield s.group(1), "SUBCKT", len(pins)


def main(clone, output, failing_list=None):
    failing = set()
    if failing_list:
        with open(failing_list, encoding="utf-8") as handle:
            failing = {tuple(line.rstrip("\n").split("\t")) for line in handle if "\t" in line}
        failing = {(path, name.upper()) for path, name in failing}
    commit = subprocess.check_output(["git", "-C", clone, "rev-parse", "HEAD"], text=True).strip()
    files = []
    models = []
    root = os.path.join(clone, "Models")
    for directory, _, names in sorted(os.walk(root)):
        for name in sorted(names):
            path = os.path.join(directory, name)
            with open(path, "rb") as handle:
                # As ModelLibrary.clean: drop NULs, DOS end-of-file marks and byte-order marks.
                text = re.sub("[\x00\x1a\ufeff]", "", handle.read().decode("utf-8", errors="replace"))
            found = {}
            for model, kind, pins in definitions(text):
                if any(c in model for c in "\t\n{}"):
                    continue
                found.setdefault(model.upper(), (model, kind, pins))
            if not found:
                continue
            index = len(files)
            files.append((os.path.relpath(path, clone), os.path.getsize(path)))
            models.extend((model, kind, pins, index) for model, kind, pins in found.values())
    with gzip.open(output, "wt", encoding="utf-8", compresslevel=9) as out:
        out.write(f"S\t{REPOSITORY}\t{commit}\n")
        for path, size in files:
            out.write(f"F\t{path}\t{size}\n")
        flagged = 0
        for model, kind, pins, index in sorted(models, key=lambda m: m[0].upper()):
            bad = (files[index][0], model.upper()) in failing
            flagged += bad
            out.write(f"M\t{model}\t{kind}\t{pins}\t{index}" + ("\t1" if bad else "") + "\n")
    print(f"{len(models)} models ({flagged} marked as not loading) in {len(files)} files at {commit[:10]}"
          f" -> {output} ({os.path.getsize(output)} bytes)")


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    main(*sys.argv[1:])
