#!/usr/bin/env python3
"""Keep vendor-only build graph cleanup from leaving dangling project dependencies."""
from itertools import chain
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
REMOVED = {
    "example-feature-api", "example-feature-api-android",
    "example-feature-impl", "example-feature-internal", "macrobenchmark",
}
BROWSER_CORE = {
    "browser-ui", "downloads-impl", "history-impl", "autofill-impl",
    "saved-sites-impl", "js-messaging-impl", "site-permissions-impl",
}
REF = re.compile(r"""\bproject\s*\(\s*(?:path\s*:\s*)?['"]:([\w.-]+)['"]""")
REMOVED_DIRS = (
    "example-feature", "macrobenchmark", "build-benchmarks",
    "perf-benchmarks", "fastlane", ".claude", ".context",
    "PixelDefinitions", ".maestro", ".idea", ".githooks",
    "scripts/release", "scripts/pir-broker-update", "scripts/ps-analysis",
)

def project_references(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = "\n".join(line.partition("//")[0] for line in text.splitlines())
    return set(REF.findall(text))

def main():
    errors = []
    app = project_references((ROOT / "app/build.gradle").read_text(encoding="utf-8"))
    for name in sorted(BROWSER_CORE - app):
        errors.append(f"Critical browser dependency missing from app: {name}")
    paths = chain(ROOT.glob("build.gradle"), ROOT.glob("*/build.gradle"),
                  ROOT.glob("*/*/build.gradle"))
    for path in paths:
        if "buildSrc" in path.parts or "node_modules" in path.parts:
            continue
        refs = project_references(path.read_text(encoding="utf-8"))
        for name in sorted(refs & REMOVED):
            errors.append(f"Stale project reference :{name} in {path.relative_to(ROOT)}")
    for task_name in ("fastlaneVersionCode", "releaseTestLocal", "releaseTestCloud",
                      "installPlayReleaseDefaultVariant", "assemblePlayReleaseDefaultVariant"):
        if f"tasks.register('{task_name}'" in (ROOT / "app/build.gradle").read_text(encoding="utf-8"):
            errors.append(f"Obsolete upstream task remains: {task_name}")
    for name in REMOVED_DIRS:
        if (ROOT / name).exists():
            errors.append(f"Vendor-only directory remains: {name}")
    if errors:
        print("V2 pruning verification FAILED:\n" + "\n".join(errors), file=sys.stderr)
        return 1
    print("V2 pruning verification passed; browser core dependencies retained")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
