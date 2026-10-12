# Image sorter

Sorts a folder of photos into subfolders **by location** (EXIF GPS) or
**by content** (CLIP zero-shot classification). It copies files by default
and writes a `sort_report.csv` saying where each file went.

## By location

```bash
pip install pillow reverse_geocoder        # reverse_geocoder is optional
python3 sort_images.py location ~/Pictures/walks --dest ~/Pictures/by-place
```

```
by-place/
├── MX/Mexico City/Mexico City/
├── FR/Ile-de-France/Paris/
└── unknown-location/        # no GPS in EXIF (screenshots, stripped uploads)
```

Without `reverse_geocoder` (it works offline, with no API key), photos are
grouped by a coordinate grid instead, e.g. `19.43_-99.13/`. Use
`--precision 2` (≈1 km cells, the default) or `--precision 1` (≈10 km cells).

## By content

```bash
pip install pillow torch transformers
python3 sort_images.py content ~/Pictures/walks --labels dog cat park beach "dog on a leash"
```

Each photo goes to the folder of the label it matches best. If the best
match is below `--min-score` (default 0.3), the photo goes to
`uncategorized/`. Files that can't be opened go to `unreadable/`. Write
labels the way you'd describe the photo. The model sees each one as
"a photo of <label>". The first run downloads the CLIP model
(`openai/clip-vit-base-patch32`, ~600 MB) from Hugging Face. Pass `--model`
to use a different one.

## Common options

| Flag | Effect |
|---|---|
| `--dest DIR` | Output folder (default `<src>_sorted`, must be outside `src`) |
| `--move` | Move files instead of copying |
| `--dry-run` | Print the folder counts without touching anything |

The source folder is searched recursively. Files with the same name in
different subfolders get a `_1`, `_2` suffix instead of overwriting each other.
