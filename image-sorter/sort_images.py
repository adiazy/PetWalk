#!/usr/bin/env python3
"""Sort a folder of images into subfolders by content or by location.

Usage:
    python3 sort_images.py location <src> [--dest sorted/] [--precision 2] [--move] [--dry-run]
    python3 sort_images.py content  <src> [--dest sorted/] [--labels dog cat ...] [--move] [--dry-run]

Modes:
  location  Reads the GPS coordinates from each photo's EXIF data. If the
            optional `reverse_geocoder` package is installed (offline, no API
            key), folders are named "<country>/<state>/<city>"; otherwise photos
            are grouped into a coordinate grid ("19.43_-99.13"). Photos without
            GPS data go to "unknown-location/".
  content   Zero-shot classification with OpenAI's CLIP model (via the
            `transformers` package). Each photo goes to the folder of the
            label it matches best; photos whose best score is below
            --min-score go to "uncategorized/".

Files are copied by default; pass --move to move them instead. A
`sort_report.csv` listing every file and where it went is written to --dest.
"""

import argparse
import csv
import shutil
import sys
from pathlib import Path

from PIL import ExifTags, Image, UnidentifiedImageError

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".tif", ".tiff", ".bmp", ".gif"}
DEFAULT_LABELS = [
    "dog", "cat", "bird", "person", "landscape", "city street",
    "food", "document or screenshot", "product photo",
]
GPS_IFD = 0x8825


def find_images(src: Path) -> list[Path]:
    return sorted(p for p in src.rglob("*") if p.is_file() and p.suffix.lower() in IMAGE_EXTS)


def safe_name(text: str) -> str:
    cleaned = "".join(c if c.isalnum() or c in " -_." else "_" for c in text).strip()
    return cleaned or "unknown"


# --- location -------------------------------------------------------------

def _to_degrees(dms, ref) -> float:
    deg, minutes, seconds = (float(x) for x in dms)
    value = deg + minutes / 60 + seconds / 3600
    return -value if ref in ("S", "W") else value


def read_gps(path: Path) -> tuple[float, float] | None:
    """Return (lat, lon) from EXIF, or None if the image has no GPS data."""
    try:
        with Image.open(path) as img:
            gps = img.getexif().get_ifd(GPS_IFD)
    except (UnidentifiedImageError, OSError):
        return None
    if not gps:
        return None
    tags = {ExifTags.GPSTAGS.get(k, k): v for k, v in gps.items()}
    try:
        lat = _to_degrees(tags["GPSLatitude"], tags.get("GPSLatitudeRef", "N"))
        lon = _to_degrees(tags["GPSLongitude"], tags.get("GPSLongitudeRef", "E"))
    except (KeyError, TypeError, ValueError, ZeroDivisionError):
        return None
    return lat, lon


def location_folders(images: list[Path], precision: int) -> dict[Path, str]:
    coords = {p: read_gps(p) for p in images}
    located = [p for p, c in coords.items() if c]

    names: dict[Path, str] = {p: "unknown-location" for p, c in coords.items() if not c}
    if not located:
        return names

    try:
        import reverse_geocoder as rg
    except ImportError:
        print("reverse_geocoder not installed; grouping by coordinate grid "
              "(pip install reverse_geocoder for place names).")
        for p in located:
            lat, lon = coords[p]
            names[p] = f"{lat:.{precision}f}_{lon:.{precision}f}"
        return names

    places = rg.search([coords[p] for p in located], verbose=False)
    for p, place in zip(located, places):
        parts = [place.get("cc"), place.get("admin1"), place.get("name")]
        names[p] = "/".join(safe_name(x) for x in parts if x)
    return names


# --- content --------------------------------------------------------------

def content_folders(images: list[Path], labels: list[str], min_score: float,
                    model_name: str, batch_size: int) -> tuple[dict[Path, str], dict[Path, float]]:
    try:
        import torch
        from transformers import CLIPModel, CLIPProcessor
    except ImportError:
        sys.exit("Content mode needs: pip install torch transformers")

    print(f"Loading {model_name} ...")
    model = CLIPModel.from_pretrained(model_name).eval()
    processor = CLIPProcessor.from_pretrained(model_name)
    prompts = [f"a photo of {label}" for label in labels]

    names: dict[Path, str] = {}
    scores: dict[Path, float] = {}
    for start in range(0, len(images), batch_size):
        batch, opened = [], []
        for p in images[start:start + batch_size]:
            try:
                with Image.open(p) as img:
                    opened.append(img.convert("RGB"))
                batch.append(p)
            except (UnidentifiedImageError, OSError):
                names[p], scores[p] = "unreadable", 0.0
        if not batch:
            continue
        inputs = processor(text=prompts, images=opened, return_tensors="pt", padding=True)
        with torch.no_grad():
            probs = model(**inputs).logits_per_image.softmax(dim=-1)
        for p, row in zip(batch, probs):
            score, idx = row.max(dim=0)
            scores[p] = float(score)
            names[p] = safe_name(labels[int(idx)]) if score >= min_score else "uncategorized"
        print(f"  classified {min(start + batch_size, len(images))}/{len(images)}")
    return names, scores


# --- main -----------------------------------------------------------------

def place_files(names: dict[Path, str], src: Path, dest: Path, move: bool, dry_run: bool,
                scores: dict[Path, float] | None = None) -> None:
    rows, taken = [], set()
    for path, folder in sorted(names.items()):
        target_dir = dest / folder
        target = target_dir / path.name
        n = 1
        while target in taken or target.exists():  # same-named files from different subfolders
            target = target_dir / f"{path.stem}_{n}{path.suffix}"
            n += 1
        taken.add(target)
        if not dry_run:
            target_dir.mkdir(parents=True, exist_ok=True)
            (shutil.move if move else shutil.copy2)(path, target)
        rows.append([str(path.relative_to(src)), folder, str(target.relative_to(dest)),
                     f"{scores[path]:.3f}" if scores else ""])

    counts: dict[str, int] = {}
    for _, folder, _, _ in rows:
        counts[folder] = counts.get(folder, 0) + 1
    for folder, count in sorted(counts.items(), key=lambda kv: -kv[1]):
        print(f"  {count:5d}  {folder}/")

    if dry_run:
        print("Dry run: no files were changed.")
        return
    with open(dest / "sort_report.csv", "w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(["source", "folder", "destination", "score"])
        writer.writerows(rows)
    print(f"{'Moved' if move else 'Copied'} {len(rows)} images into {dest}/ "
          f"(see sort_report.csv)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=["location", "content"])
    parser.add_argument("src", type=Path, help="folder of images (searched recursively)")
    parser.add_argument("--dest", type=Path, help="output folder (default: <src>_sorted)")
    parser.add_argument("--move", action="store_true", help="move files instead of copying")
    parser.add_argument("--dry-run", action="store_true", help="only print what would happen")
    loc = parser.add_argument_group("location mode")
    loc.add_argument("--precision", type=int, default=2,
                     help="decimal places for the coordinate grid fallback (2 ≈ 1 km)")
    con = parser.add_argument_group("content mode")
    con.add_argument("--labels", nargs="+", default=DEFAULT_LABELS,
                     help="categories to sort into (default: %(default)s)")
    con.add_argument("--min-score", type=float, default=0.3,
                     help="below this confidence a photo goes to uncategorized/")
    con.add_argument("--model", default="openai/clip-vit-base-patch32")
    con.add_argument("--batch-size", type=int, default=16)
    args = parser.parse_args()

    src = args.src.resolve()
    if not src.is_dir():
        sys.exit(f"{src} is not a folder")
    dest = (args.dest or src.with_name(src.name + "_sorted")).resolve()
    if dest == src or src in dest.parents:
        sys.exit("--dest must be outside the source folder")

    images = find_images(src)
    if not images:
        sys.exit(f"No images found in {src}")
    print(f"Found {len(images)} images in {src}")

    scores = None
    if args.mode == "location":
        names = location_folders(images, args.precision)
    else:
        names, scores = content_folders(images, args.labels, args.min_score,
                                        args.model, args.batch_size)
    place_files(names, src, dest, args.move, args.dry_run, scores)


if __name__ == "__main__":
    main()
