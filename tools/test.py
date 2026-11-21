#!/usr/bin/env python3
"""Compile the plugin against the SDK and the Android platform, and check the manifest."""
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_sdk import android_platform
from plugin_manifest import build_manifest

root = Path(__file__).resolve().parents[1]
java_home = os.environ.get('JAVA_HOME')
def tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name
sdk_root = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk'))))
platform = str(android_platform(sdk_root))
manifest = json.loads(build_manifest(root / 'kiosk-satellite-plugin.json'))
assert manifest['license'] == 'MIT'
sources = [*sorted((root / 'sdk/src').rglob('*.java')), *sorted((root / 'src').rglob('*.java'))]
with tempfile.TemporaryDirectory(prefix='house-tools-test-') as directory:
    subprocess.run([tool('javac'), '--release', '8', '-Xlint:all', '-cp', platform, '-d', directory, *map(str, sources)], check=True)
    entry = Path(directory, *manifest['entryClass'].split('.')).with_suffix('.class')
    assert entry.exists(), f'entry class {manifest["entryClass"]} not compiled'
print('OK')
