#!/usr/bin/env python3
"""Build a checked, signed updater APK with the existing Silora release key."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent


def build():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--signing-dir", type=Path, default=ROOT.parent / "silora-signing",
                        help="directory containing Silora's release.jks and signing.json (default: ../silora-signing)")
    parser.add_argument("--version", help="updater version, e.g. 1.0.1 (default: APP_VERSION_NAME or Gradle's local version)")
    parser.add_argument("--device-check", action="store_true", help="also build the signed, opt-in Shizuku device-check APK")
    args = parser.parse_args()
    signing_dir = args.signing_dir.expanduser().resolve()
    keystore = signing_dir / "release.jks"
    if not keystore.is_file():
        raise ValueError(f"Missing Silora signing key: {keystore}")
    try:
        config = json.loads((signing_dir / "signing.json").read_text())
    except (OSError, ValueError):
        raise ValueError(f"Cannot read {signing_dir / 'signing.json'}") from None
    if not isinstance(config, dict):
        raise ValueError("signing.json must be an object containing password and alias")
    for key in ("password", "alias"):
        if not isinstance(config.get(key), str) or not config[key]:
            raise ValueError(f"signing.json needs a nonempty {key} string")
    key_password = config.get("key_password", config["password"])
    if not isinstance(key_password, str) or not key_password:
        raise ValueError("signing.json key_password must be a nonempty string")

    env = os.environ.copy()
    java_home = env.get("JAVA_HOME")
    if not java_home:
        java_home = next((path for path in (
            "/usr/lib/jvm/java-21-openjdk-amd64",
            "/usr/lib/jvm/java-17-openjdk-amd64",
        ) if (Path(path) / "bin/javac").is_file()), None)
        if not java_home and shutil.which("javac"):
            java_home = str(Path(shutil.which("javac")).resolve().parent.parent)
    if not java_home or not (Path(java_home) / "bin/javac").is_file():
        raise ValueError("Set JAVA_HOME to a full JDK 17 or newer (including javac)")
    sdk = Path(env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT") or Path.home() / "Android").expanduser()
    if not (sdk / "platforms/android-35").is_dir():
        raise ValueError("Set ANDROID_HOME to an Android SDK with platform 35 installed")
    apksigner = sdk / "build-tools/35.0.0/apksigner"
    apkanalyzer = sdk / "cmdline-tools/latest/bin/apkanalyzer"
    if not apksigner.is_file() or not apkanalyzer.is_file():
        raise ValueError("Install Android build-tools 35.0.0 and cmdline-tools/latest to verify the release")
    env.update({
        "JAVA_HOME": java_home,
        "ANDROID_HOME": str(sdk),
        "PATH": str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", ""),
        "SIGNING_KEYSTORE_FILE": str(keystore),
        "SIGNING_KEYSTORE_PASSWORD": config["password"],
        "SIGNING_KEY_ALIAS": config["alias"],
        "SIGNING_KEY_PASSWORD": key_password,
    })
    if args.version:
        if not re.fullmatch(r"\d+\.\d{1,2}\.\d{1,2}", args.version):
            raise ValueError("--version must look like 1.0.1 (minor and patch below 100)")
        env["APP_VERSION_NAME"] = args.version

    def run(command, **kwargs):
        return subprocess.run([str(arg) for arg in command], cwd=ROOT, env=env, check=True, **kwargs)

    certificate = run([
        "keytool", "-exportcert", "-keystore", keystore,
        "-storepass:env", "SIGNING_KEYSTORE_PASSWORD", "-alias", config["alias"],
    ], capture_output=True).stdout
    expected = hashlib.sha256(certificate).hexdigest()
    print("Building release APK with the existing Silora signing key…", flush=True)
    tasks = ["testReleaseUnitTest", "lintRelease", "assembleRelease"]
    if args.device_check:
        tasks.append("assembleReleaseAndroidTest")
    run([ROOT / "gradlew", "--no-daemon", *tasks])
    apk = ROOT / "app/build/outputs/apk/release/app-release.apk"
    verified = run([apksigner, "verify", "--print-certs", apk], capture_output=True, text=True).stdout
    actual = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", verified)
    if actual != [expected]:
        raise ValueError("APK signing certificate does not match the Silora release key")
    if run([apkanalyzer, "manifest", "debuggable", apk], capture_output=True, text=True).stdout.strip() != "false":
        raise ValueError("Refusing to distribute a debuggable APK")
    if run([apkanalyzer, "manifest", "application-id", apk], capture_output=True, text=True).stdout.strip() != "org.silora.updater":
        raise ValueError("Unexpected APK application ID")

    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    output = dist / "SiloraUpdater.apk"
    shutil.copy2(apk, output)
    if args.device_check:
        test_apk = ROOT / "app/build/outputs/apk/androidTest/release/app-release-androidTest.apk"
        test_certs = run([apksigner, "verify", "--print-certs", test_apk], capture_output=True, text=True).stdout
        if re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", test_certs) != [expected]:
            raise ValueError("Device-check APK must use the same signing certificate as the updater")
        shutil.copy2(test_apk, dist / "SiloraUpdater-device-check.apk")
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    (dist / "SHA256SUMS").write_text(f"{digest}  {output.name}\n")
    print(f"\nSigned release APK: {output}\nSHA-256: {digest}")


if __name__ == "__main__":
    try:
        build()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Build failed: {error}", file=sys.stderr)
        sys.exit(1)
