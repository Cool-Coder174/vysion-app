"""The app's detector pre- and post-processing in numpy, line for line with inference/YoloPostProcessor.kt, so the int8
calibration and the int8-vs-FP32 check see exactly what the headset feeds and decodes:
nearest-neighbor letterbox with Ultralytics' gray pad (114/255), RGB /255, CHW float32; output [1, 4 + nc, N]
columns (cx, cy, w, h in input pixels, then sigmoid scores), best class among the lockfile ids, confidence 0.25,
class-wise NMS at IoU 0.45 with "Person"/"Man"/"Woman" merged, at most 50 boxes, mapped back to the frame (0..1).
"""
import math

import cv2
import numpy as np

PAD_VALUE = np.float32(114.0 / 255.0)
CONF = 0.25
IOU = 0.45
MAX_DET = 50


def load_rgb(path: str) -> np.ndarray:
    bgr = cv2.imread(path, cv2.IMREAD_COLOR)
    if bgr is None:
        raise ValueError(f"cannot decode {path}")
    return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)


def letterbox(src_w: int, src_h: int, dst_w: int, dst_h: int):
    """YoloPostProcessor.Letterbox.compute: (scale, scaled_w, scaled_h, pad_x, pad_y)."""
    scale = np.float32(min(np.float32(dst_w) / np.float32(src_w), np.float32(dst_h) / np.float32(src_h)))
    sw = min(dst_w, max(1, math.floor(float(np.float32(src_w * scale)) + 0.5)))  # Kotlin roundToInt
    sh = min(dst_h, max(1, math.floor(float(np.float32(src_h * scale)) + 0.5)))
    return scale, sw, sh, (dst_w - sw) // 2, (dst_h - sh) // 2


def to_chw(rgb: np.ndarray, dst: int) -> tuple[np.ndarray, tuple]:
    """YoloPostProcessor.letterboxToChw: [1, 3, dst, dst] float32 plus the letterbox for un-padding."""
    h, w = rgb.shape[:2]
    lb = letterbox(w, h, dst, dst)
    scale, sw, sh, px, py = lb
    inv = np.float32(1.0) / scale
    sy = np.minimum(h - 1, (np.arange(sh, dtype=np.float32) * inv).astype(np.int64))
    sx = np.minimum(w - 1, (np.arange(sw, dtype=np.float32) * inv).astype(np.int64))
    out = np.full((3, dst, dst), PAD_VALUE, dtype=np.float32)
    out[:, py:py + sh, px:px + sw] = rgb[sy][:, sx].transpose(2, 0, 1).astype(np.float32) / np.float32(255.0)
    return out[None], (w, h) + lb


def _iou(a, b) -> float:
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return 0.0 if union <= 0 else inter / union


def decode(out: np.ndarray, lb: tuple, class_map: dict[int, str], conf: float = CONF) -> list[tuple[str, float, list[float]]]:
    """YoloPostProcessor.decode(CXCYWH_SCORES_COLUMNS, onlyClasses = class_map): [(label, score, [l, t, r, b])]."""
    src_w, src_h, scale, _, _, px, py = lb
    data = out.reshape(out.shape[-2], out.shape[-1])
    ids = np.array(sorted(class_map), dtype=np.int64)
    scores = data[4 + ids]  # [k, N]
    best = scores.argmax(0)
    best_score = scores[best, np.arange(data.shape[1])]
    keep = np.nonzero(best_score >= conf)[0]
    canonical = {}
    for label in set(class_map.values()):
        group = [i for i, v in class_map.items() if v == label]
        canonical.update({i: min(group) for i in group})
    cand = []
    for i in keep:
        cx, cy, w, h = (float(v) for v in data[:4, i])
        cand.append(([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2], float(best_score[i]), canonical[int(ids[best[i]])]))
    cand.sort(key=lambda c: -c[1])
    kept, dead = [], [False] * len(cand)
    for i, a in enumerate(cand):
        if dead[i]:
            continue
        kept.append(a)
        for j in range(i + 1, len(cand)):
            if not dead[j] and cand[j][2] == a[2] and _iou(a[0], cand[j][0]) > IOU:
                dead[j] = True
    dets = []
    for box, score, cls in kept[:MAX_DET]:
        l = min(max((box[0] - px) / scale / src_w, 0.0), 1.0)
        r = min(max((box[2] - px) / scale / src_w, 0.0), 1.0)
        t = min(max((box[1] - py) / scale / src_h, 0.0), 1.0)
        b = min(max((box[3] - py) / scale / src_h, 0.0), 1.0)
        if r > l and b > t:
            dets.append((class_map[cls], min(max(score, 0.0), 1.0), [l, t, r, b]))
    return dets


def iou(a: list[float], b: list[float]) -> float:
    return _iou(a, b)
