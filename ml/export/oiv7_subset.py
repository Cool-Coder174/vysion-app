"""Download a small Open Images V7 subset with ground-truth boxes for the Navigation classes (Door first).

Two uses, kept on separate splits so the check never sees a calibration image:
  calibration (int8 export):  --split validation
  held-out check (recall):    --split test

The images are CC BY 2.0 (Open Images, Google); the box annotations CC BY 4.0. Nothing here is a camera frame and
nothing is committed: the subset lands in a cache directory (ml/.cache/, gitignored).

Usage (inside the vysion-ml image, see export_detector_oiv7.sh --int8):
  python oiv7_subset.py --split validation --n 300 --out /data/calib
  python oiv7_subset.py --split test --n 100000 --door-share 1.0 --out /data/heldout-doors   # every test door image

Output: <out>/images/<ImageID>.jpg and <out>/labels.json, {ImageID: [[class_name, xmin, ymin, xmax, ymax], ...]}
with normalized coordinates, for every Navigation class in the image (not only Door).
"""
import argparse
import csv
import io
import json
import os
import random
import sys
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

BASE = "https://storage.googleapis.com/openimages"
CLASSES_URL = f"{BASE}/v7/oidv7-class-descriptions-boxable.csv"
BOXES_URL = {"validation": f"{BASE}/v5/validation-annotations-bbox.csv", "test": f"{BASE}/v5/test-annotations-bbox.csv"}
IMAGE_URL = "https://open-images-dataset.s3.amazonaws.com/{split}/{image_id}.jpg"

# The detector's Navigation classes (ml/models.lock "classes"), by Open Images display name.
NAV = ["Door", "Stairs", "Person", "Man", "Woman", "Wheelchair", "Chair", "Table", "Desk", "Couch", "Bench",
       "Coffee table", "Waste container", "Box", "Window", "Toilet"]


def fetch(url: str, dest: str) -> str:
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    tmp = dest + ".part"
    with urllib.request.urlopen(url, timeout=60) as r, open(tmp, "wb") as f:
        f.write(r.read())
    os.replace(tmp, dest)
    return dest


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--split", choices=sorted(BOXES_URL), required=True)
    p.add_argument("--n", type=int, default=300, help="images to keep")
    p.add_argument("--door-share", type=float, default=0.7,
                   help="share of images that contain a Door box; 1.0 = every image with a Door (up to n), nothing else")
    p.add_argument("--out", required=True)
    p.add_argument("--cache", default="/data/csv", help="where the annotation CSVs are kept between runs")
    p.add_argument("--seed", type=int, default=0)
    a = p.parse_args()

    os.makedirs(a.cache, exist_ok=True)
    os.makedirs(os.path.join(a.out, "images"), exist_ok=True)
    classes_csv = fetch(CLASSES_URL, os.path.join(a.cache, "classes.csv"))
    boxes_csv = fetch(BOXES_URL[a.split], os.path.join(a.cache, f"{a.split}-boxes.csv"))

    mid_to_name = {}
    with open(classes_csv, newline="") as f:
        for row in csv.reader(f):
            if len(row) >= 2 and row[1] in NAV:
                mid_to_name[row[0]] = row[1]
    missing = set(NAV) - set(mid_to_name.values())
    if missing:
        print(f"warning: no Open Images MID for {sorted(missing)}", file=sys.stderr)

    boxes = defaultdict(list)
    with open(boxes_csv, newline="") as f:
        for row in csv.DictReader(f):
            name = mid_to_name.get(row["LabelName"])
            if name is None:
                continue
            boxes[row["ImageID"]].append(
                [name, float(row["XMin"]), float(row["YMin"]), float(row["XMax"]), float(row["YMax"])]
            )

    rng = random.Random(a.seed)
    with_door = sorted(i for i, b in boxes.items() if any(c == "Door" for c, *_ in b))
    others = sorted(i for i, b in boxes.items() if not any(c == "Door" for c, *_ in b))
    rng.shuffle(with_door)
    rng.shuffle(others)
    n_door = min(len(with_door), round(a.n * a.door_share))
    n_other = a.n - n_door if a.door_share < 1 else 0  # fill up to n, unless only doors were asked for
    chosen = with_door[:n_door] + others[:n_other]
    print(f"{a.split}: {len(with_door)} images with Door, {len(others)} with other Navigation classes; "
          f"keeping {n_door} + {len(chosen) - n_door}")

    def download(image_id: str):
        dest = os.path.join(a.out, "images", f"{image_id}.jpg")
        try:
            fetch(IMAGE_URL.format(split=a.split, image_id=image_id), dest)
            return image_id
        except Exception as e:  # one dead URL must not end the run
            print(f"skip {image_id}: {e}", file=sys.stderr)
            return None

    with ThreadPoolExecutor(max_workers=16) as pool:
        kept = [i for i in pool.map(download, chosen) if i]
    labels = {i: boxes[i] for i in kept}
    with open(os.path.join(a.out, "labels.json"), "w") as f:
        json.dump(labels, f)
    doors = sum(1 for b in labels.values() for c, *_ in b if c == "Door")
    print(f"wrote {len(kept)} images ({doors} Door boxes) to {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
