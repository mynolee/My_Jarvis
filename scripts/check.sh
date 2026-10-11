#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/verify_wrapper.py
python3 -m unittest discover -s scripts -p 'test_*.py' -v
python3 -m unittest discover -s evals/tests -v
python3 scripts/publication_guard.py --manifest artifacts/publication-manifest.json
(cd packages/contracts && uv lock --check && uv run --frozen python -m unittest discover -s tests -v)
./gradlew --no-daemon :apps:core:test :apps:core:bootJar
(cd apps/intelligence && uv lock --check && PYTHONPATH=src uv run --frozen python -m unittest discover -s tests -v)
(cd apps/web && npm ci --ignore-scripts && npm test && npm run build)
