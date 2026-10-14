"""Regenerate AndroidManifest.xml as clean UTF-8.

Activities are declared in one table so a new probe cannot silently lose its
launcher entry. Exactly one activity carries the launcher intent-filter; the
generator asserts that and refuses to write otherwise.

    python tools/gen_manifest.py                  # MainActivity is the launcher
    python tools/gen_manifest.py --diag-launcher  # DiagActivity
    python tools/gen_manifest.py --battery-probe  # BatteryProbeActivity
    python tools/gen_manifest.py --battery-calib  # BatteryCalibActivity

PowerShell's Set-Content writes the console code page and once corrupted the
Chinese labels, so the manifest is produced here instead. Labels use real Chinese
text, never \\uXXXX escapes: an earlier version matched on escaped text, silently
failed, and shipped an APK with no launcher entry at all.
"""
import io, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

LAUNCHER_TARGETS = {
    '--diag-launcher': 'DiagActivity',
    '--battery-probe': 'BatteryProbeActivity',
    '--battery-calib': 'BatteryCalibActivity',
}

launcher_activity = 'MainActivity'
for flag, name in LAUNCHER_TARGETS.items():
    if flag in sys.argv:
        launcher_activity = name

# name, label (None = no android:label attribute), comment
ACTIVITIES = [
    ('MainActivity', None, 'Main screen.'),
    ('DiagActivity', '亮屏时间·诊断',
     'Internal diagnostics probe: adb-reachable, off the launcher by default.'),
    ('BatteryProbeActivity', '亮屏时间·电池探针',
     'Read-only battery probe: which battery data an unrooted app can reach.'),
    ('BatteryCalibActivity', '亮屏时间·电池校准',
     'Charge-counter calibration: measures resolution and drain rate so we can\n'
     '             judge whether unplug time can be back-estimated.'),
    ('DayDetailActivity', None, 'Per-app detail for one day.'),
    ('ChargeHistoryActivity', '充电记录',
     'One row per discharge cycle: screen time between unplug and the next plug.'),
]

LAUNCHER_FILTER = '''
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>'''

HEAD = '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="cn.local.screentime">

    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="35" />

    <!-- Reading usage history needs the special "usage access" permission, which
         the user (or adb) must grant explicitly. It is not a runtime permission
         and cannot be requested with requestPermissions(). -->
    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <!-- Android 11+ package visibility. Without this the PackageManager only
         reveals a handful of system packages, so third-party apps that clearly
         appear in usage events cannot be resolved to a name or icon and end up
         lumped together as "other". QUERY_ALL_PACKAGES alone is not enough on
         this ROM; the <queries> element is what actually widens visibility. -->
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <category android:name="android.intent.category.BROWSABLE" />
            <data android:scheme="https" />
        </intent>
        <intent>
            <action android:name="android.intent.action.MAIN" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
        </intent>
    </queries>

    <application
        android:label="亮屏时间"
        android:icon="@mipmap/ic_launcher"
        android:allowBackup="true"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
'''

TAIL = '''
        <receiver android:name=".BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>

        <!-- Charger plug/unplug recorder. A statically registered receiver means
             the system starts this process only when a transition happens: no
             service, no notification, no alarm, nothing resident in between. -->
        <receiver android:name=".PowerReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.ACTION_POWER_CONNECTED" />
                <action android:name="android.intent.action.ACTION_POWER_DISCONNECTED" />
            </intent-filter>
        </receiver>

        <!-- Daily background archival, so history survives days when the app is
             never opened. -->
        <receiver android:name=".ArchiveReceiver" android:exported="false" />
    </application>
</manifest>
'''


def block(name, label, comment, launcher):
    lines = ['        <!-- %s -->' % comment.replace('\n', '\n        ')]
    lines.append('        <activity')
    lines.append('            android:name=".%s"' % name)
    lines.append('            android:exported="%s"' % ('true' if launcher or name != 'DayDetailActivity' else 'false'))
    if label:
        lines.append('            android:label="%s"' % label)
    lines.append('            android:configChanges="orientation|screenSize|keyboardHidden|uiMode"')
    body = '\n'.join(lines)
    if launcher:
        return body + '>' + LAUNCHER_FILTER + '\n        </activity>\n'
    return body + ' />\n'


parts = [HEAD]
for name, label, comment in ACTIVITIES:
    launcher = (name == launcher_activity)
    # DayDetailActivity is internal; everything else is exported so adb can start it.
    if name == 'DayDetailActivity':
        b = block(name, label, comment, False).replace('android:exported="true"',
                                                       'android:exported="false"')
        parts.append(b)
    else:
        parts.append(block(name, label, comment, launcher))
parts.append(TAIL)
manifest = ''.join(parts)

# --- assertions: never ship a manifest without exactly one launcher ---
n_launcher = manifest.count('android.intent.category.LAUNCHER')
# The <queries> block names LAUNCHER once; the launcher activity adds one more.
expected = 1 + 1
if n_launcher != expected:
    sys.exit('expected %d LAUNCHER entries (%d in <queries> + 1 activity), found %d'
             % (expected, 1, n_launcher))

declared = [n for n, _l, _c in ACTIVITIES]
for n in declared:
    if ('android:name=".%s"' % n) not in manifest:
        sys.exit('activity missing from manifest: %s' % n)

# Which activity actually got the filter?
holder = None
for name in declared:
    i = manifest.find('android:name=".%s"' % name)
    nxt = manifest.find('<activity', i + 1)
    seg = manifest[i:nxt if nxt > 0 else len(manifest)]
    if 'android.intent.category.LAUNCHER' in seg:
        holder = name
if holder != launcher_activity:
    sys.exit('launcher filter landed on %r, expected %r' % (holder, launcher_activity))

out = os.path.join(ROOT, 'app', 'AndroidManifest.xml')
with io.open(out, 'w', encoding='utf-8', newline='\n') as f:
    f.write(manifest)
print('wrote %s (%d bytes); launcher = %s' % (out, len(manifest.encode('utf-8')), launcher_activity))
