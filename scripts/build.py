#!/usr/bin/env python3
"""Build a debug-signed installable APK with JDK 17+ and Android SDK 35; no Gradle download."""
import os
import pathlib
import re
import shutil
import subprocess
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SDK = pathlib.Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", ""))).expanduser().resolve()
JAVA = pathlib.Path(os.environ["JAVA_HOME"]) / "bin" if "JAVA_HOME" in os.environ else None
BUILD = ROOT / "build" / "manual"
VERSION_CODE = os.environ.get("VIBE_VERSION_CODE", "30")
VERSION_NAME = os.environ.get("VIBE_VERSION_NAME", "0.6.8")
ORIGIN = os.environ.get("VIBE_ORIGIN", "https://vibe.example.com").strip().removesuffix("/").lower()

def java(name):
    return str(JAVA / name) if JAVA else name

def run(*args):
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT)

def main():
    if not re.fullmatch(r"https://[a-z0-9-]+(?:\.[a-z0-9-]+)+", ORIGIN):
        raise SystemExit("VIBE_ORIGIN must be an HTTPS domain without a port or path")
    if not re.fullmatch(r"[0-9]+", VERSION_CODE) or not 0 < int(VERSION_CODE) <= 2100000000:
        raise SystemExit("VIBE_VERSION_CODE must be a positive Android version code")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,39}", VERSION_NAME):
        raise SystemExit("VIBE_VERSION_NAME must be 1–40 letters, digits, dots, underscores or hyphens")
    signing_key = os.environ.get("VIBE_SIGNING_KEYSTORE")
    key = pathlib.Path(signing_key).expanduser().resolve() if signing_key else ROOT / "build/debug.keystore"
    if signing_key and not (key.is_file() and os.environ.get("VIBE_STORE_PASSWORD")):
        raise SystemExit("An existing VIBE_SIGNING_KEYSTORE and VIBE_STORE_PASSWORD are required for release signing")
    platform = SDK / "platforms" / "android-35" / "android.jar"
    bt = SDK / "build-tools" / "35.0.0"
    if not platform.exists() or not bt.exists():
        raise SystemExit("Set ANDROID_SDK_ROOT to an SDK containing platforms;android-35 and build-tools;35.0.0")
    BUILD.mkdir(parents=True, exist_ok=True)
    for name in ["gen", "classes", "dex", "res"]:
        directory = BUILD / name
        if directory.exists(): shutil.rmtree(directory)
        directory.mkdir()
    resources = BUILD / "resources.zip"
    if resources.exists(): resources.unlink()
    res_dir = BUILD / "res"
    shutil.copytree(ROOT / "app/src/main/res", res_dir, dirs_exist_ok=True)
    (res_dir / "values/vibe_origin.xml").write_text(f'<resources><string name="vibe_origin" translatable="false">{ORIGIN}</string></resources>')
    run(bt / "aapt2", "compile", "--dir", res_dir, "-o", resources)
    unsigned = BUILD / "unsigned.apk"
    manifest = BUILD / "AndroidManifest.xml"
    manifest.write_text((ROOT / "app/src/main/AndroidManifest.xml").read_text().replace(
        "<manifest ", '<manifest package="chat.vibe.app" ', 1))
    run(bt / "aapt2", "link", "-o", unsigned, "-I", platform, "--manifest",
        manifest, "--java", BUILD / "gen",
        "--version-code", VERSION_CODE, "--version-name", VERSION_NAME, resources)
    sources = list((ROOT / "app/src/main/java").rglob("*.java")) + list((BUILD / "gen").rglob("*.java"))
    libs = list((ROOT / "app/libs").glob("*.jar"))
    classpath = os.pathsep.join(str(p) for p in [platform, *libs])
    run(java("javac"), "-encoding", "UTF-8", "--release", "8", "-classpath", classpath,
        "-d", BUILD / "classes", *sources)
    classes = list((BUILD / "classes").rglob("*.class"))
    run(java("java"), "-cp", bt / "lib/d8.jar", "com.android.tools.r8.D8",
        "--min-api", "26", "--lib", platform, "--output", BUILD / "dex", *classes, *libs)
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as apk:
        for dex in (BUILD / "dex").glob("*.dex"): apk.write(dex, dex.name)
    aligned = BUILD / "aligned.apk"
    run(bt / "zipalign", "-f", "-p", "4", unsigned, aligned)
    if not key.exists():
        run(java("keytool"), "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android",
            "-alias", "androiddebugkey", "-dname", "CN=Vibe Chat Development,O=Vibe Chat,C=CN",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000")
    apk = ROOT / "build" / f"VibeChat-{VERSION_NAME}.apk"
    alias = os.environ.get("VIBE_KEY_ALIAS", "vibe-release") if signing_key else "androiddebugkey"
    store_pass = "env:VIBE_STORE_PASSWORD" if signing_key else "pass:android"
    key_pass = "env:VIBE_KEY_PASSWORD" if signing_key and os.environ.get("VIBE_KEY_PASSWORD") else store_pass
    run(java("java"), "-jar", bt / "lib/apksigner.jar", "sign", "--ks", key,
        "--ks-key-alias", alias, "--ks-pass", store_pass, "--key-pass", key_pass, "--out", apk, aligned)
    run(java("java"), "-jar", bt / "lib/apksigner.jar", "verify", "--verbose", apk)
    print("APK:", apk)

if __name__ == "__main__":
    main()
