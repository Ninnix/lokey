#!/usr/bin/env python3
import argparse
import csv
import json
import random
import statistics
import subprocess
import tarfile
import unicodedata
from collections import defaultdict
from pathlib import Path


def prepare(root, languages):
    output = root / "corpus"
    output.mkdir(exist_ok=True)
    fixtures = []
    for language in languages:
        name, locale = language.split("=", 1)
        source = root / locale
        with (source / "test.tsv").open() as stream:
            rows = list(csv.reader(stream, delimiter="\t", quoting=csv.QUOTE_NONE))
        genders = [gender for gender in ("FEMALE", "MALE") if any(row[6] == gender and 0 <= int(row[5]) < 15 * 16000 for row in rows)]
        rng = random.Random(181)
        picked = {}
        seen = set()
        for low, high in [(0, 7), (7, 10), (10, 15)]:
            for gender in genders:
                pool = [row for row in rows if low * 16000 <= int(row[5]) < high * 16000 and row[6] == gender]
                rng.shuffle(pool)
                distinct = (row for row in pool if row[0] not in seen)
                per_group = 8 // len(genders)
                for _ in range(per_group):
                    row = next(distinct, None)
                    if row is None:
                        raise ValueError(f"{locale}: fewer than {per_group} distinct {gender} clips between {low} and {high}s")
                    seen.add(row[0])
                    picked["test/" + row[1]] = {
                        "file": locale + "-" + row[1], "language": name, "locale": locale,
                        "reference": row[2], "source_id": row[0], "source_group": gender,
                    }
        if len(picked) != 24:
            raise ValueError(f"{locale}: expected 24 distinct transcripts, got {len(picked)}")
        extracted = set()
        with tarfile.open(source / "test.tar.gz", "r|gz") as archive:
            for entry in archive:
                if entry.name not in picked:
                    continue
                data = archive.extractfile(entry).read()
                subprocess.run(
                    ["ffmpeg", "-v", "error", "-y", "-i", "pipe:0", "-ac", "1", "-ar", "16000",
                     "-c:a", "pcm_s16le", str(output / picked[entry.name]["file"])],
                    input=data, check=True,
                )
                extracted.add(entry.name)
                if len(extracted) == len(picked):
                    break
        if extracted != picked.keys():
            raise ValueError(f"{locale}: missing selected audio in archive")
        fixtures.extend(picked.values())
    (output / "fixtures.json").write_text(json.dumps(fixtures, ensure_ascii=False, indent=2) + "\n")


def units(value, characters=False):
    text = unicodedata.normalize("NFC", value.casefold())
    normalized = "".join(c if unicodedata.category(c)[0] in "LMN" else " " for c in text)
    return list("".join(normalized.split())) if characters else normalized.split()


def edits(reference, actual):
    previous = list(range(len(actual) + 1))
    for i, expected in enumerate(reference, 1):
        row = [i]
        for j, received in enumerate(actual, 1):
            row.append(min(row[-1] + 1, previous[j] + 1, previous[j - 1] + (expected != received)))
        previous = row
    return previous[-1]


def score(root, paths):
    fixtures = {row["file"]: row for row in json.loads((root / "corpus/fixtures.json").read_text())}
    groups = defaultdict(dict)
    for path in paths:
        for row in json.loads(path.read_text()):
            fixture = fixtures[row["file"]]
            characters = fixture["locale"].startswith(("cmn_", "yue_", "ja_"))
            target = units(fixture["reference"], characters)
            actual = units(row["text"], characters)
            group = row["language"], row["backend"], row["model"], row["hint"], "CER" if characters else "WER"
            if row["file"] in groups[group]:
                raise ValueError(f"Duplicate recording in {group}: {row['file']}")
            groups[group][row["file"]] = edits(target, actual), len(target), row["ms"], row["seconds"]
    print("language\tbackend\tmodel\thint\tclips\texact\tmetric\terror_percent\tmedian_ms\tmedian_rtf")
    for (language, backend, model, hint, metric), files in sorted(groups.items()):
        expected = {name for name, fixture in fixtures.items() if fixture["language"] == language}
        if files.keys() != expected:
            raise ValueError(f"Incomplete results for {language} {backend} {model} {hint}")
        rows = list(files.values())
        error = 100 * sum(r[0] for r in rows) / sum(r[1] for r in rows)
        median_ms = statistics.median(r[2] for r in rows)
        median_rtf = statistics.median(r[2] / (1000 * r[3]) for r in rows)
        print(f"{language}\t{backend}\t{model}\t{hint or 'auto'}\t{len(rows)}\t"
              f"{sum(r[0] == 0 for r in rows)}\t{metric}\t{error:.2f}\t{median_ms:.0f}\t{median_rtf:.3f}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="action", required=True)
    sample = commands.add_parser("prepare")
    sample.add_argument("root", type=Path)
    sample.add_argument("language", nargs="+", help="LoKey language name and FLEURS locale, e.g. Spanish=es_419")
    report = commands.add_parser("score")
    report.add_argument("root", type=Path)
    report.add_argument("result", type=Path, nargs="+")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.root, args.language)
    else:
        score(args.root, args.result)
