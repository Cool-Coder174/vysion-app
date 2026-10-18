"""YOLOv8n Open Images V7 → int8 ExecuTorch .pte for XNNPACK (PT2E, XNNPACKQuantizer, static symmetric 8a8w with
per-channel weights), calibrated on images preprocessed exactly as the app does (yolo_app.to_chw).

Only the backbone, the neck and the head's box/class convolutions are quantized. The head's decode stays float32:
the DFL softmax, the anchor/stride math, the sigmoid and the final concat of boxes (0..imgsz px) with scores (0..1).
Quantized together, that concat would share one int8 scale of about imgsz/255 px and every score would round to 0
or to that step. So the output is the FP32 model's layout, [1, 4 + 601, anchors] float, and the app decodes both
files with the same code (YoloPostProcessor, CXCYWH_SCORES_COLUMNS).

Usage (inside the vysion-ml image; export_detector_oiv7.sh --int8 runs it):
  python export_detector_int8.py --calib /data/calib --out /out/detector-int8.pte [--imgsz 320] [--n 300]
"""
import argparse
import glob
import hashlib
import os
import sys

import torch
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
from executorch.backends.xnnpack.quantizer.xnnpack_quantizer import (
    XNNPACKQuantizer,
    get_symmetric_quantization_config,
)
from executorch.exir import EdgeCompileConfig, ExecutorchBackendConfig, to_edge_transform_and_lower
from torchao.quantization.pt2e.quantize_pt2e import convert_pt2e, prepare_pt2e
from ultralytics import YOLO
from ultralytics.nn.modules import Detect

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import yolo_app  # noqa: E402


def export_ready(weights: str) -> torch.nn.Module:
    """The graph Ultralytics' `format=executorch` exports: fused Conv+BN, Detect in export mode (no NMS)."""
    model = YOLO(weights).model.float().eval()
    model = model.fuse()
    for m in model.modules():
        if isinstance(m, Detect):
            m.export = True
            m.format = "executorch"
            m.dynamic = False
    return model


class BodyOnlyQuantizer(XNNPACKQuantizer):
    """XNNPACKQuantizer with one global config, minus the head's decode: ops that run in Detect itself (the per-level
    view/cat of box and class logits, the anchor math, the sigmoid, the final concat) and in its DFL stay float32.
    `set_module_name` cannot express this: its filter must also match the conv's weight node, which carries no module
    stack in an exported graph, so no conv would be quantized at all."""

    def __init__(self, head: str):
        super().__init__()
        self.head = head

    def annotate(self, model: torch.fx.GraphModule) -> torch.fx.GraphModule:
        model = super().annotate(model)
        dfl = f"{self.head}.dfl"
        for n in model.graph.nodes:
            stack = [path.removeprefix("L['self'].") for path, _ in n.meta.get("nn_module_stack", {}).values()]
            if stack and (stack[-1] == self.head or any(s == dfl or s.startswith(dfl + ".") for s in stack)):
                n.meta.pop("quantization_annotation", None)
        return model


def head_name(model: torch.nn.Module) -> str:
    layers = model.model
    assert isinstance(layers[-1], Detect), "expected Detect as the last layer"
    return f"model.{len(layers) - 1}"


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--weights", default="yolov8n-oiv7.pt")
    p.add_argument("--calib", required=True, help="directory with images/*.jpg (oiv7_subset.py)")
    p.add_argument("--n", type=int, default=300, help="calibration images")
    p.add_argument("--imgsz", type=int, default=320)
    p.add_argument("--per-tensor", action="store_true", help="per-tensor weights (default: per-channel)")
    p.add_argument("--out", required=True)
    a = p.parse_args()

    model = export_ready(a.weights)
    example = (torch.zeros(1, 3, a.imgsz, a.imgsz),)
    with torch.no_grad():
        fp32_out = model(*example)
        ep = torch.export.export(model, example)
    print(f"fp32 graph output {list(fp32_out.shape)}")

    quantizer = BodyOnlyQuantizer(head_name(model))
    quantizer.set_global(get_symmetric_quantization_config(is_per_channel=not a.per_tensor, is_dynamic=False))
    prepared = prepare_pt2e(ep.module(), quantizer)

    images = sorted(glob.glob(os.path.join(a.calib, "images", "*.jpg")))[: a.n]
    if not images:
        raise SystemExit(f"no calibration images under {a.calib}/images")
    with torch.no_grad():
        for i, path in enumerate(images):
            chw, _ = yolo_app.to_chw(yolo_app.load_rgb(path), a.imgsz)
            prepared(torch.from_numpy(chw))
            if (i + 1) % 50 == 0:
                print(f"calibrated {i + 1}/{len(images)}")
    converted = convert_pt2e(prepared)

    convs = [n for n in converted.graph.nodes if n.op == "call_function" and "conv2d" in str(n.target)]
    int8 = [n for n in convs if "dequantize_per_channel" in str(n.args[1].target) or "dequantize_per_tensor" in str(n.args[1].target)]
    print(f"{len(int8)} of {len(convs)} convolutions have int8 weights (the float one is the DFL)")

    with torch.no_grad():
        q_ep = torch.export.export(converted, example)
    edge = to_edge_transform_and_lower(
        q_ep,
        partitioner=[XnnpackPartitioner()],
        compile_config=EdgeCompileConfig(_check_ir_validity=False, _skip_dim_order=True),
    )
    program = edge.to_executorch(config=ExecutorchBackendConfig(extract_delegate_segments=False))
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "wb") as f:
        program.write_to_file(f)

    data = open(a.out, "rb").read()
    print("--- paste into ml/models.lock (detector-int8 row) ---")
    print("bytes:", len(data))
    print("sha256:", hashlib.sha256(data).hexdigest())
    print("input:", a.imgsz, "x", a.imgsz)
    print("calibration images:", len(images))
    print("weights:", "per-tensor" if a.per_tensor else "per-channel")
    return 0


if __name__ == "__main__":
    sys.exit(main())
