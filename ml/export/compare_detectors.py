"""Compare two detector .pte files (FP32 and int8) on held-out Open Images images with ground truth, through the
ExecuTorch runtime and the app's own pre- and post-processing (yolo_app.py = YoloPostProcessor.kt).

Reports, per Navigation label: ground-truth boxes, recall and precision of each model at the app's threshold
(confidence 0.25, IoU >= 0.5 with a box of the same label), and how many of the FP32 detections the int8 model
reproduces. Door recall is the number that matters for "find the door" (FR-N3). Laptop accuracy only: latency is
measured on the headset (docs/benchmarks.md).

Usage (inside the vysion-ml image):
  python compare_detectors.py --a /out/detector.pte --b /out/detector-int8.pte --images /data/heldout \
      --lock /lock/models.lock [--conf 0.25,0.15] [--json /data/compare.json]

Several --conf values print one table each from the same forward passes (decoding is cheap, the forward is not).
--b equal to --a runs the model once per image: a single model's recall at one input size.
"""
import argparse
import glob
import json
import os
import sys
from collections import defaultdict

import numpy as np
import torch
from executorch.runtime import Runtime
from ultralytics import YOLO

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import yolo_app  # noqa: E402

MATCH_IOU = 0.5


def class_map_from_lock(path: str) -> dict[int, str]:
    rows = json.load(open(path))["models"]
    row = next(r for r in rows if r["name"] == "detector")
    return {int(k): v for k, v in row["classes"].items()}


def match(dets, truths) -> int:
    """Greedy by score: each truth box is used once; a detection counts if IoU >= 0.5 with an unused truth."""
    used = [False] * len(truths)
    hits = 0
    for _, _, box in sorted(dets, key=lambda d: -d[1]):
        best, best_iou = -1, MATCH_IOU
        for j, t in enumerate(truths):
            if not used[j]:
                v = yolo_app.iou(box, t)
                if v >= best_iou:
                    best, best_iou = j, v
        if best >= 0:
            used[best] = True
            hits += 1
    return hits


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--a", required=True, help="reference .pte (FP32)")
    p.add_argument("--b", required=True, help="candidate .pte (int8)")
    p.add_argument("--images", required=True, help="directory with images/ and labels.json (oiv7_subset.py)")
    p.add_argument("--lock", required=True, help="ml/models.lock, for the detector's class map")
    p.add_argument("--weights", default="yolov8n-oiv7.pt", help="for the Open Images name of each class id")
    p.add_argument("--imgsz", type=int, default=320)
    p.add_argument("--conf", default=str(yolo_app.CONF),
                   help="confidence floor(s), comma-separated (the app uses 0.25); one table each")
    p.add_argument("--json")
    a = p.parse_args()

    class_map = class_map_from_lock(a.lock)
    names = YOLO(a.weights).names
    oi_to_label = {names[i]: label for i, label in class_map.items()}
    labels = json.load(open(os.path.join(a.images, "labels.json")))

    confs = [float(c) for c in a.conf.split(",")]
    same = os.path.realpath(a.a) == os.path.realpath(a.b)
    rt = Runtime.get()
    methods = {"a": rt.load_program(a.a).load_method("forward")}
    if not same:
        methods["b"] = rt.load_program(a.b).load_method("forward")

    gt = defaultdict(int)
    hit = {c: {k: defaultdict(int) for k in ("a", "b")} for c in confs}
    found = {c: {k: defaultdict(int) for k in ("a", "b")} for c in confs}
    agree = {c: defaultdict(int) for c in confs}  # FP32 detections the int8 model reproduces
    door_score_err = []
    paths = sorted(glob.glob(os.path.join(a.images, "images", "*.jpg")))
    for n, path in enumerate(paths):
        image_id = os.path.splitext(os.path.basename(path))[0]
        rgb = yolo_app.load_rgb(path)
        chw, lb = yolo_app.to_chw(rgb, a.imgsz)
        truths = defaultdict(list)
        for name, *box in labels.get(image_id, []):
            if name in oi_to_label:
                truths[oi_to_label[name]].append(box)
        for label, boxes in truths.items():
            gt[label] += len(boxes)
        raw = {k: method.execute([torch.from_numpy(chw)])[0].numpy() for k, method in methods.items()}
        raw.setdefault("b", raw["a"])
        for c in confs:
            dets = {k: yolo_app.decode(raw[k], lb, class_map, c) for k in ("a", "b")}
            for k in ("a", "b"):
                for label in set(class_map.values()):
                    mine = [d for d in dets[k] if d[0] == label]
                    found[c][k][label] += len(mine)
                    hit[c][k][label] += match(mine, truths.get(label, []))
            for label in set(class_map.values()):
                ref = [d[2] for d in dets["a"] if d[0] == label]
                agree[c][label] += match([d for d in dets["b"] if d[0] == label], ref)
        door = [i for i, v in class_map.items() if v == "door"]
        if door:
            sa = raw["a"][0, 4 + door[0]]
            sb = raw["b"][0, 4 + door[0]]
            sel = sa >= 0.1
            if sel.any():
                door_score_err.extend(np.abs(sa[sel] - sb[sel]).tolist())
        if (n + 1) % 50 == 0:
            print(f"{n + 1}/{len(paths)} images")

    rows = []
    order = ["door"] + sorted(set(class_map.values()) - {"door"})

    def pct(x, y):
        return "—" if y == 0 else f"{100 * x / y:.1f}% ({x}/{y})"

    for c in confs:
        h, f, g = hit[c], found[c], agree[c]
        print(f"\n{len(paths)} held-out images; input {a.imgsz}; confidence {c}, match IoU {MATCH_IOU}; "
              f"a = {os.path.basename(a.a)}, b = {os.path.basename(a.b)}")
        print("| Label | GT boxes | a recall | b recall | a precision | b precision | a detections | b detections | b reproduces a |")
        print("|---|---|---|---|---|---|---|---|---|")
        for label in order:
            if gt[label] == 0 and f["a"][label] == 0 and f["b"][label] == 0:
                continue
            rows.append({
                "conf": c, "label": label, "gt": gt[label],
                "a_hits": h["a"][label], "b_hits": h["b"][label],
                "a_dets": f["a"][label], "b_dets": f["b"][label], "b_reproduces_a": g[label],
            })
            print(f"| {label} | {gt[label]} | {pct(h['a'][label], gt[label])} | {pct(h['b'][label], gt[label])} | "
                  f"{pct(h['a'][label], f['a'][label])} | {pct(h['b'][label], f['b'][label])} | "
                  f"{f['a'][label]} | {f['b'][label]} | {pct(g[label], f['a'][label])} |")
    if door_score_err:
        e = np.array(door_score_err)
        print(f"\ndoor score |a - b| on anchors where a >= 0.1: median {np.median(e):.3f}, p90 {np.percentile(e, 90):.3f}, n={len(e)}")
    if a.json:
        with open(a.json, "w") as f:
            json.dump({"images": len(paths), "imgsz": a.imgsz, "rows": rows, "door_score_abs_err": door_score_err}, f)
    return 0


if __name__ == "__main__":
    sys.exit(main())
