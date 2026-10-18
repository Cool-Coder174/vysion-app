"""Print every method of an ExecuTorch .pte with its input and output tensor shapes and dtypes (ExecuTorch runtime API).
Methods that return a constant (export metadata such as get_max_seq_len) are executed and their value printed.

Usage: python inspect_pte.py model.pte
"""
import sys

from executorch.runtime import Runtime

program = Runtime.get().load_program(sys.argv[1])


def describe(get, count, label):
    for i in range(count):
        try:
            t = get(i)
            print(f"  {label}[{i}] dtype={t.dtype()} {list(t.sizes())}")
        except Exception:
            print(f"  {label}[{i}] (not a tensor)")


for name in sorted(program.method_names):
    meta = program.metadata(name)
    print(f"== {name}")
    describe(meta.input_tensor_meta, meta.num_inputs(), "in")
    describe(meta.output_tensor_meta, meta.num_outputs(), "out")
    if meta.num_inputs() == 0:
        try:
            print(f"  value = {program.load_method(name).execute([])}")
        except Exception as e:
            print(f"  value = <{e}>")
