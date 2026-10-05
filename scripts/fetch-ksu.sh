#!/usr/bin/env bash
# Fetch the signature-paired KernelSU artifact set from a tiann/KernelSU
# main-branch CI run. Main-branch builds share one stable keystore, so the
# manager APK and every aarch64-{kmi}-lkm from the SAME run are mutually
# paired. Downloads go through nightly.link (anonymous artifact mirror).
#
# Usage: fetch-ksu.sh [run_id] [out_dir]
#   run_id  - tiann/KernelSU CI run id (default: latest successful build-manager run)
#   out_dir - where to place: manager.apk, ksu-daemon, kernelsu-{kmi}.ko x7
set -euo pipefail

REPO="tiann/KernelSU"
RUN="${1:-}"
OUT="${2:-app/src/main/assets}"

KMIS=(android12-5.10 android13-5.10 android13-5.15 android14-5.15 android15-6.6 android16-6.12 android17-6.18)

AUTH=()
if [ -n "${GH_TOKEN:-}${KSU_POLL_TOKEN:-}" ]; then
  TOKEN="${GH_TOKEN:-${KSU_POLL_TOKEN:-}}"
  AUTH=(-H "Authorization: Bearer $TOKEN")
fi
API() { curl -sf "${AUTH[@]}" "$@"; }

if [[ -z "$RUN" ]]; then
  # Walk recent successful runs newest-first and pick the first one that
  # actually carries the full paired artifact set. Some upstream runs exit
  # green with zero artifacts (mid-refactor), so success status alone is not
  # enough.
  RUN=$(API "https://api.github.com/repos/$REPO/actions/workflows/build-manager.yml/runs?status=success&per_page=15" |
    KSU_TOKEN="${GH_TOKEN:-${KSU_POLL_TOKEN:-}}" python3 -c '
import json, sys, os, urllib.request
def artifacts(rid):
    req = urllib.request.Request(
        f"https://api.github.com/repos/tiann/KernelSU/actions/runs/{rid}/artifacts?per_page=100")
    tok = os.environ.get("KSU_TOKEN")
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    with urllib.request.urlopen(req) as r:
        return [a["name"] for a in json.load(r)["artifacts"]]
for run in json.load(sys.stdin)["workflow_runs"]:
    names = artifacts(run["id"])
    manager = "manager-gradle" in names or "manager" in names
    probe = "aarch64-android16-6.12-lkm" in names
    daemon = "ksud-aarch64-linux-android" in names
    if manager and probe and daemon:
        print(run["id"]); break
else:
    sys.exit("no upstream run carries a complete paired artifact set")')
fi
echo "[fetch-ksu] source run: https://github.com/$REPO/actions/runs/$RUN"
mkdir -p "$OUT"
echo "$RUN" > "$(dirname "$0")/../.ksu-run-id"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

fetch() { # fetch <artifact-name> <dest-file> [strip]
  local name="$1" dest="$2"
  curl -sfL -o "$TMP/$name.zip" "https://nightly.link/$REPO/actions/runs/$RUN/$name.zip"
  rm -rf "$TMP/x" && mkdir -p "$TMP/x"
  unzip -qo "$TMP/$name.zip" -d "$TMP/x"
  local f
  f=$(find "$TMP/x" -type f | head -1)
  cp "$f" "$dest"
  echo "[fetch-ksu] $name -> $dest ($(stat -c%s "$dest") bytes)"
}

if curl -sfL -o /dev/null "https://nightly.link/$REPO/actions/runs/$RUN/manager-gradle.zip"; then
  fetch "manager-gradle" "$(dirname "$OUT")/../manager.apk"
else
  fetch "manager" "$(dirname "$OUT")/../manager.apk"
fi
fetch "ksud-aarch64-linux-android" "$OUT/ksu-daemon"
for kmi in "${KMIS[@]}"; do
  fetch "aarch64-$kmi-lkm" "$OUT/kernelsu-$kmi.ko"
done
echo "[fetch-ksu] done"
