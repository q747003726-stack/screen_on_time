"""Regenerate AndroidManifest.xml as clean UTF-8.

MainActivity is the launcher again; DiagActivity stays in the package as an
internal probe reachable by adb (`am start -n cn.local.screentime/.DiagActivity`).

PowerShell's Set-Content writes the ANSI code page and corrupted the Chinese
labels, so the manifest is generated from Python instead.
"""
import io, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WANT_DIAG_LAUNCHER = '--diag-launcher' in sys.argv

manifest = '''<?xml version="1.0" encoding="utf-8"?>
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
        android:label="\u4eae\u5c4f\u65f6\u95f4"
        android:icon="@mipmap/ic_launcher"
        android:allowBackup="true"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden|uiMode">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Internal diagnostics probe. Not exported to the launcher, but
             reachable over adb for verification. -->
        <activity
            android:name=".DiagActivity"
            android:exported="true"
            android:label="\u4eae\u5c4f\u65f6\u95f4\u00b7\u8bca\u65ad"
            android:configChanges="orientation|screenSize|keyboardHidden|uiMode" />

        <activity
            android:name=".DayDetailActivity"
            android:exported="false"
            android:configChanges="orientation|screenSize|keyboardHidden|uiMode" />

        <receiver android:name=".BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>

        <!-- Daily background archival, so history survives days when the app is
             never opened. -->
        <receiver android:name=".ArchiveReceiver" android:exported="false" />
    </application>
</manifest>
'''

if WANT_DIAG_LAUNCHER:
    manifest = manifest.replace('''            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden|uiMode">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>''', '''            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden|uiMode" />''')
    manifest = manifest.replace('''            android:label="\u4eae\u5c4f\u65f6\u95f4\u00b7\u8bca\u65ad"
            android:configChanges="orientation|screenSize|keyboardHidden|uiMode" />''',
    '''            android:label="\u4eae\u5c4f\u65f6\u95f4\u00b7\u8bca\u65ad"
            android:configChanges="orientation|screenSize|keyboardHidden|uiMode">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>''')

out = os.path.join(ROOT, 'app', 'AndroidManifest.xml')
with io.open(out, 'w', encoding='utf-8', newline='\n') as f:
    f.write(manifest)
print('wrote %s (%d bytes), diag-as-launcher=%s' % (out, len(manifest.encode('utf-8')), WANT_DIAG_LAUNCHER))
