# executor.md — per-phase implementation specs

This file is the **specification authority** for the engineer-workflow pipeline (`plan.md` is the roadmap authority; the code is the truth). It is empty on purpose: a phase spec is written here only when someone runs `detail F<N>` against [`plan.md`](plan.md), and verification reports are appended under the same phase heading after `verify F<N>`.

Until then, the per-role task lists in [`docs/modules/`](docs/modules/) are the working specs for the hackathon.

## How a phase spec gets here

```
detail F1   → writes "## F1 — <title>" below: files to touch, failure modes, invariants carried
              from plan.md ##Design and ##Security, verification commands
implement F1 → code; deviations logged under "### F1 deviations"
verify F1    → "### F1 verification" with evidence (device logcat lines, numbers)
```

<!-- phase specs are appended below this line -->
