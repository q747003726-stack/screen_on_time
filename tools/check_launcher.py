"""Verify which activity carries the launcher entry, from both the manifest and
the built APK (aapt2 badging is the authority)."""
import io, os, re, subprocess, sys

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST = os.path.join(ROOT, 'app', 'AndroidManifest.xml')
APK = os.path.join(ROOT, 'app', 'build', 'screentime.apk')
AAPT2 = os.path.join(ROOT, 'work', 'tools', 'build-tools', 'aapt2.exe')

names = ['MainActivity', 'DiagActivity', 'BatteryProbeActivity', 'DayDetailActivity']

print('=== per-activity launcher check (parsed from the manifest) ===')
m = io.open(MANIFEST, encoding='utf-8').read()
for name in names:
    marker = 'android:name=".%s"' % name
    i = m.find(marker)
    if i < 0:
        print('  %-24s (not in manifest)' % name)
        continue
    # end of this element: either "/>" or "</activity>"
    seg = m[i:]
    a = seg.find('/>')
    b = seg.find('</activity>')
    end = min(x for x in (a, b, len(seg)) if x >= 0)
    body = seg[:end]
    # the launcher filter may follow the "/>" for self-closing forms, so also
    # inspect up to the next activity boundary
    nxt = seg.find('<activity')
    if nxt > 0:
        body = seg[:nxt]
    has = 'android.intent.category.LAUNCHER' in body
    print('  %-24s launcher=%s' % (name, has))

print()
print('=== authoritative: aapt2 badging on the built APK ===')
if not os.path.exists(APK):
    print('  APK missing:', APK)
else:
    r = subprocess.run([AAPT2, 'dump', 'badging', APK],
                       capture_output=True, timeout=180)
    out = (r.stdout or b'').decode('utf-8', 'replace')
    for line in out.splitlines():
        if line.startswith(('package:', 'launchable-activity:', 'application-label:',
                            'application-icon-')):
            print('  ' + line)
