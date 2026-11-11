#!/usr/bin/env python3
"""Validate built Debug APK identity and merged variant manifest isolation."""
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
PRODUCTION = "com.ounan.secondbrain"
DEBUG = PRODUCTION + ".debug"


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def manifest(variant):
    path = ROOT / f"app/build/intermediates/merged_manifests/{variant}/process{variant.title()}Manifest/AndroidManifest.xml"
    tree = ET.parse(path).getroot()
    require(ANDROID + "sharedUserId" not in tree.attrib, "Shared UID is forbidden")
    app = tree.find("application")
    require(app.get(ANDROID + "allowBackup") == "false", "Backup must remain disabled")
    return tree, app


def authorities(app):
    result = set()
    for provider in app.findall("provider"):
        require(provider.get(ANDROID + "exported") == "false", "Provider must remain private")
        values = provider.get(ANDROID + "authorities", "").split(";")
        require(all(values), "Provider must have an authority")
        require(not result.intersection(values), "Duplicate provider authority")
        result.update(values)
    return result


def main():
    debug, debug_app = manifest("debug")
    release, release_app = manifest("release")
    require(debug.get("package") == DEBUG, "Wrong Debug package")
    require(release.get("package") == PRODUCTION, "Release package changed")
    debug_authorities = authorities(debug_app)
    release_authorities = authorities(release_app)
    require(not debug_authorities.intersection(release_authorities), "Provider authority collision")
    debug_permissions = {p.get(ANDROID + "name") for p in debug.findall("permission")}
    release_permissions = {p.get(ANDROID + "name") for p in release.findall("permission")}
    require(not debug_permissions.intersection(release_permissions), "Custom permission collision")
    for variant, package, app, tree in [
        ("debug", DEBUG, debug_app, debug), ("release", PRODUCTION, release_app, release)
    ]:
        for authority in authorities(app):
            require(authority.startswith(package + "."), f"{variant}: authority not scoped to package")
        for permission in tree.findall("permission"):
            require(permission.get(ANDROID + "name", "").startswith(package + "."),
                    f"{variant}: custom permission not scoped to package")
        names = {c.get(ANDROID + "name") for c in app if c.tag in {"activity", "service"}}
        for name in ["MainActivity", "CaptureService", "WeChatOcrService", "WeChatReaderService"]:
            require("com.ounan.knowledge." + name in names, f"{variant}: missing original component {name}")

    sdk = Path(os.environ["ANDROID_HOME"])
    aapt = sdk / "build-tools/35.0.0/aapt"
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
    require(f"package: name='{DEBUG}'" in badging, "APK package differs from merged manifest")
    require("application-label:'欧南知识中枢·测试版'" in badging, "Wrong packaged application label")
    require("targetSdkVersion:'35'" in badging, "Target SDK changed")
    print("PASS: Debug APK package/label, Release package, original components, private providers, custom permissions, no shared UID")
    print("Debug provider authorities:", ", ".join(sorted(debug_authorities)))
    print("Release provider authorities:", ", ".join(sorted(release_authorities)))


if __name__ == "__main__":
    main()
