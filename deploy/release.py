#!/usr/bin/env python3
"""Prepare signed Android artifacts locally, then publish them on the Ubuntu host."""
import argparse
import hashlib
import html
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


ARTIFACT = re.compile(r"VibeChat-[A-Za-z0-9][A-Za-z0-9._-]{0,39}-[0-9]+\.apk")


def https_origin(value):
    origin = value.strip().removesuffix("/").lower()
    if not re.fullmatch(r"https://[a-z0-9-]+(?:\.[a-z0-9-]+)+", origin):
        raise ValueError("Expected an HTTPS origin such as https://vibe.example.com")
    return origin


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = tempfile.NamedTemporaryFile(dir=path.parent, delete=False)
    pending = Path(temp.name)
    try:
        with temp:
            temp.write(data)
        pending.chmod(0o644)
        pending.replace(path)
    finally:
        pending.unlink(missing_ok=True)


def write_apk(directory, name, data):
    path = directory / name
    if path.exists():
        if path.read_bytes() != data:
            raise ValueError(f"{name} already exists with different bytes. Increase the version code.")
    else:
        atomic_write(path, data)


def prepare(apk, origin, output, notes):
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        raise ValueError("Set ANDROID_SDK_ROOT to the Android SDK directory")
    tools = Path(sdk) / "build-tools/35.0.0"
    java = Path(os.environ["JAVA_HOME"]) / "bin/java" if os.getenv("JAVA_HOME") else "java"
    subprocess.run([java, "-jar", tools / "lib/apksigner.jar", "verify", apk], check=True)
    badging = subprocess.check_output([tools / "aapt2", "dump", "badging", apk], text=True)
    version = re.search(r"^package: name='chat\.vibe\.app' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    min_sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'([0-9]+)'", badging, re.M)
    resources = subprocess.check_output([tools / "aapt2", "dump", "resources", apk], text=True)
    configured_origin = re.search(r'string/vibe_origin\s*\n\s*\(\) "([^"\r\n]+)"', resources)
    if not version or not min_sdk:
        raise ValueError("Expected a signed Vibe Chat APK with version and minimum SDK metadata")
    if not configured_origin or configured_origin[1] != origin:
        raise ValueError("The APK's VIBE_ORIGIN must match the release origin. Rebuild the APK first.")
    code, name = int(version[1]), version[2]
    filename = f"VibeChat-{name}-{code}.apk"
    if not ARTIFACT.fullmatch(filename):
        raise ValueError("Invalid APK version name")
    data = apk.read_bytes()
    if not 10000 <= len(data) <= 64 * 1024 * 1024:
        raise ValueError("APK size outside the Android updater's allowed range")
    manifest = {
        "versionCode": code, "versionName": name, "minSdk": int(min_sdk[1]),
        "url": f"{origin}/downloads/{filename}", "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(), "notes": notes,
    }
    encoded = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode()
    if len(encoded) > 16384:
        raise ValueError("Update manifest is too large. Shorten the release notes.")
    write_apk(output, filename, data)
    atomic_write(output / "latest.json", encoded)
    print(f"Prepared {output / filename} and latest.json")


def publish(staged, origin, destination):
    encoded = (staged / "latest.json").read_bytes()
    if len(encoded) > 16384:
        raise ValueError("Update manifest is too large")
    manifest = json.loads(encoded)
    code, name = manifest["versionCode"], manifest["versionName"]
    filename = f"VibeChat-{name}-{code}.apk"
    if not ARTIFACT.fullmatch(filename) or type(code) is not int or code <= 0:
        raise ValueError("Invalid APK release name or version code")
    if manifest["url"] != f"{origin}/downloads/{filename}":
        raise ValueError("Update manifest URL must point to this deployment and APK")
    data = (staged / filename).read_bytes()
    if len(data) != manifest["size"] or hashlib.sha256(data).hexdigest() != manifest["sha256"]:
        raise ValueError("APK differs from update manifest")
    latest = destination / "latest.json"
    if latest.exists():
        current = json.loads(latest.read_bytes())
        if code < current["versionCode"] or (code == current["versionCode"] and manifest != current):
            raise ValueError("Use a higher version code for a new release")
    page = f'''<!doctype html><html lang="zh"><meta charset="utf-8">
<meta name="viewport" content="width=device-width"><title>下载 Vibe Chat</title>
<body style="font:18px system-ui;background:#f2f2f7;color:#1c1c1e;padding:8vw">
<h1>Vibe Chat</h1><p>Android {('8.0' if manifest['minSdk'] == 26 else 'API ' + str(int(manifest['minSdk'])))} 及以上</p>
<p><a href="{filename}">下载安装包 · {html.escape(name)}</a></p>
<p>首次安装后注册账号。已安装的用户可在 App 内检查更新。</p></body></html>'''
    write_apk(destination, filename, data)
    atomic_write(destination / "index.html", page.encode())
    atomic_write(latest, encoded)
    print(f"Published {name} / {code} at {manifest['url']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("prepare")
    build.add_argument("apk", type=Path)
    build.add_argument("origin", type=https_origin)
    build.add_argument("--out", type=Path, default=Path("releases/current"))
    build.add_argument("--notes", default="Vibe Chat 自托管版本")
    upload = commands.add_parser("publish")
    upload.add_argument("staged", type=Path)
    upload.add_argument("origin", type=https_origin)
    upload.add_argument("--destination", type=Path, default=Path("/srv/vibe-downloads"))
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            prepare(args.apk, args.origin, args.out, args.notes)
        else:
            publish(args.staged, args.origin, args.destination)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Release failed: {error}\n")


if __name__ == "__main__":
    main()
