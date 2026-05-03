#!/usr/bin/env python3
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


def run(cmd):
    print("+", " ".join(str(x) for x in cmd))
    subprocess.run(cmd, check=True)


def find_file(root: Path, relative: str) -> Path:
    path = root / relative
    if not path.is_file():
        raise FileNotFoundError(f"Required file not found: {path}")
    return path


def repack_apk(src_apk: Path, repacked_apk: Path):
    with zipfile.ZipFile(src_apk, "r") as zin, zipfile.ZipFile(repacked_apk, "w") as zout:
        for info in zin.infolist():
            name = info.filename
            if name.startswith("META-INF/"):
                continue
            data = zin.read(name)
            new_info = zipfile.ZipInfo(name)
            new_info.date_time = info.date_time
            new_info.comment = info.comment
            new_info.create_system = info.create_system
            new_info.external_attr = info.external_attr
            new_info.extra = b""
            if name == "resources.arsc":
                new_info.compress_type = zipfile.ZIP_STORED
            else:
                new_info.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(new_info, data)


def main():
    parser = argparse.ArgumentParser(description="Repair an Android APK so resources.arsc is stored, zipalign it, and sign it.")
    parser.add_argument("apk", help="Input APK path")
    parser.add_argument("--sdk", required=True, help="Android SDK root")
    parser.add_argument("--build-tools", default="30.0.3", help="Build tools version (default: 30.0.3)")
    parser.add_argument("--keystore", help="Optional custom keystore path. If omitted, debug.keystore is used.")
    parser.add_argument("--storepass", default="android", help="Keystore password (default: android)")
    parser.add_argument("--keypass", default="android", help="Key password (default: android)")
    parser.add_argument("--alias", default="androiddebugkey", help="Key alias (default: androiddebugkey)")
    args = parser.parse_args()

    src_apk = Path(args.apk).resolve()
    sdk_root = Path(args.sdk).resolve()
    build_tools = sdk_root / "build-tools" / args.build_tools
    zipalign = find_file(build_tools, "zipalign.exe")
    apksigner = find_file(build_tools, "apksigner.bat")

    keystore = Path(args.keystore).resolve() if args.keystore else Path.home() / ".android" / "debug.keystore"
    if not keystore.is_file():
        raise FileNotFoundError(f"Keystore not found: {keystore}")

    out_dir = src_apk.parent
    repacked_apk = out_dir / (src_apk.stem + "-repacked.apk")
    aligned_apk = out_dir / (src_apk.stem + "-aligned.apk")
    signed_apk = out_dir / (src_apk.stem + "-signed.apk")

    with tempfile.TemporaryDirectory(prefix="scarlet-apk-") as _tmp:
        repack_apk(src_apk, repacked_apk)

    run([str(zipalign), "-f", "-p", "-v", "4", str(repacked_apk), str(aligned_apk)])
    run([str(zipalign), "-c", "-p", "-v", "4", str(aligned_apk)])
    run([
        str(apksigner),
        "sign",
        "--ks", str(keystore),
        "--ks-pass", f"pass:{args.storepass}",
        "--key-pass", f"pass:{args.keypass}",
        "--ks-key-alias", args.alias,
        "--out", str(signed_apk),
        str(aligned_apk),
    ])
    run([str(apksigner), "verify", "-v", str(signed_apk)])

    print()
    print("Repaired APK written to:")
    print(signed_apk)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as exc:
        print(f"Command failed with exit code {exc.returncode}", file=sys.stderr)
        sys.exit(exc.returncode)
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
