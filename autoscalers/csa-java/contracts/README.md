# CSA Java Contract Fixtures

This directory freezes the stdin and stdout contracts used by the current
Python implementation under `autoscalers/csa/scripts`.

Goal:

- keep migration incremental
- protect behavior while Java modules are implemented one by one
- provide fixed examples for local tests without a running cluster

How to use:

1. Pick a case from `catalog.json`.
2. Feed `stdin` to the Java command mode (`-m metric`, `-m evaluate`, etc).
3. Compare stdout with the `stdout` fixture.
4. Respect `implicitInput` when behavior depends on Kubernetes state.

Notes:

- `implicitInput` documents dependencies that are not present on stdin, like
  rollout status, current tag, current CPU limit, and patch success.
- Fixtures are intentionally JSON only. They are easy to diff and automate.
- Values are aligned with the current Python logic and config.
