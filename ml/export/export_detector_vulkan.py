"""YOLOv8n Open Images V7 → ExecuTorch .pte for the Adreno GPU (Vulkan delegate, fp16), for the GPU vs CPU
measurement (docs/benchmarks.md "Adreno GPU"; ADR-0002 Update). Same graph as the shipping exports
(export_detector_int8.export_ready: fused Conv+BN, Detect in export mode, no NMS), so the output is the FP32 layout,
[1, 4 + 601, anchors] float, and the app decodes it with the same code (YoloPostProcessor, CXCYWH_SCORES_COLUMNS).

The Vulkan partitioner takes what it supports; XnnpackPartitioner then takes the rest, so nothing falls to the
portable CPU kernels. fp16 (`force_fp16`) as Software Mansion's Vulkan exports; inputs and outputs stay float32.

Usage (inside the vysion-ml image; export_detector_oiv7.sh --vulkan runs it):
  python export_detector_vulkan.py --out /out/detector-vulkan.pte [--imgsz 320] [--fp32]
"""
import argparse
import collections
import hashlib
import os
import sys

import torch
from executorch.backends.vulkan.partitioner.vulkan_partitioner import VulkanPartitioner
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
from executorch.exir import EdgeCompileConfig, ExecutorchBackendConfig, to_edge_transform_and_lower

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from export_detector_int8 import export_ready  # noqa: E402


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--weights", default="yolov8n-oiv7.pt")
    p.add_argument("--imgsz", type=int, default=320)
    p.add_argument("--fp32", action="store_true", help="keep fp32 on the GPU (default: force_fp16)")
    p.add_argument("--out", required=True)
    a = p.parse_args()

    model = export_ready(a.weights)
    example = (torch.zeros(1, 3, a.imgsz, a.imgsz),)
    with torch.no_grad():
        out = model(*example)
        ep = torch.export.export(model, example)
    print(f"graph output {list(out.shape)}")

    edge = to_edge_transform_and_lower(
        ep,
        partitioner=[VulkanPartitioner({"force_fp16": not a.fp32}), XnnpackPartitioner()],
        compile_config=EdgeCompileConfig(_check_ir_validity=False),
    )
    graph = edge.exported_program().graph_module.graph
    delegates = collections.Counter()
    leftover = collections.Counter()
    for n in graph.nodes:
        if n.op != "call_function":
            continue
        name = str(n.target)
        if "executorch_call_delegate" in name:
            lowered = getattr(edge.exported_program().graph_module, n.args[0].target)
            delegates[lowered.backend_id] += 1
        elif "getitem" not in name:
            leftover[name] += 1
    print("delegate calls:", dict(delegates))
    print("ops outside any delegate:", dict(leftover) or "none")

    program = edge.to_executorch(config=ExecutorchBackendConfig(extract_delegate_segments=False))
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "wb") as f:
        program.write_to_file(f)

    data = open(a.out, "rb").read()
    print("--- paste into ml/models.lock (detector-vulkan row) ---")
    print("bytes:", len(data))
    print("sha256:", hashlib.sha256(data).hexdigest())
    print("input:", a.imgsz, "x", a.imgsz)
    print("precision:", "fp32" if a.fp32 else "fp16")
    return 0


if __name__ == "__main__":
    sys.exit(main())
