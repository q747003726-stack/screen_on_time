"""Prove what is actually inside the built APK.

The user reports the detail screen still shows whole-day usage, which is the old
behaviour. Before changing more code, confirm whether the artifact on disk really
carries the new methods.
"""
import os, re, sys, zipfile

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK = os.path.join(ROOT, 'app', 'build', 'screentime.apk')


def dex_strings(blob):
    """Return the string pool of a DEX file as a set of decoded strings."""
    if blob[:4] != b'dex\n':
        return set()
    string_ids_size = int.from_bytes(blob[56:60], 'little')
    string_ids_off = int.from_bytes(blob[60:64], 'little')
    out = set()
    for i in range(string_ids_size):
        off = int.from_bytes(blob[string_ids_off + i * 4:string_ids_off + i * 4 + 4], 'little')
        if off <= 0 or off >= len(blob):
            continue
        # uleb128 length, then MUTF-8 bytes terminated by NUL
        p = off
        shift = 0
        n = 0
        while True:
            b = blob[p]
            p += 1
            n |= (b & 0x7F) << shift
            shift += 7
            if not (b & 0x80):
                break
        raw = blob[p:p + n]
        try:
            out.add(raw.decode('utf-8', 'replace'))
        except Exception:
            pass
    return out


def main():
    if not os.path.exists(APK):
        sys.exit('APK missing: %s' % APK)
    size = os.path.getsize(APK)
    print('APK: %s' % APK)
    print('size: %d bytes (%s)' % (size, __import__('time').ctime(os.path.getmtime(APK))))
    import hashlib
    print('sha256: %s' % hashlib.sha256(open(APK, 'rb').read()).hexdigest())
    print()

    z = zipfile.ZipFile(APK)
    dexes = [n for n in z.namelist() if n.endswith('.dex')]
    print('dex entries: %s' % ', '.join(dexes))
    pool = set()
    for n in dexes:
        pool |= dex_strings(z.read(n))
    print('string pool size: %d' % len(pool))
    print()

    # Markers introduced by the newest changes.
    checks = [
        ('UsageRepo.appsInRange', 'appsInRange'),
        ('UsageRepo.screenOnInRange', 'screenOnInRange'),
        ('DayDetailActivity range extras', 'from'),
        ('DayDetailActivity range extras', 'to'),
        ('DayDetailActivity range title', 'title'),
        ('ChargeRepo.Cycle', 'Cycle'),
        ('PowerReceiver', 'PowerReceiver'),
        ('ChargeHistoryActivity', 'ChargeHistoryActivity'),
        ('BatteryCalibActivity', 'BatteryCalibActivity'),
    ]
    for label, needle in checks:
        print('  %-32s %s' % (label, 'PRESENT' if needle in pool else 'absent'))

    print()
    # Chinese labels survive as string constants.
    for label in ('本次充电后', '充电记录', '电池校准', '按日'):
        print('  label %-14s %s' % (label, 'PRESENT' if label in pool else 'absent'))


if __name__ == '__main__':
    main()
