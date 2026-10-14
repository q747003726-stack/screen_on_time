"""
Fetch the toolchain needed to build the APK without Gradle.

Everything is placed under tools/toolchain/ so the repository itself only carries
source code:

    tools/toolchain/
        jdk-*/                  Temurin JDK 21      (javac, keytool)
        build-tools/            aapt2, d8, zipalign, apksigner
        platform-tools/         adb
        platforms/android-*/    android.jar

Re-running is cheap: already-present files are skipped.

Usage:  python tools/setup_toolchain.py
"""
import io, json, os, re, shutil, sys, time, urllib.request, zipfile

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEST = os.path.join(ROOT, 'tools', 'toolchain')
UA = {'User-Agent': 'Mozilla/5.0'}
GOOGLE = 'https://dl.google.com/android/repository/'


def log(msg):
    print(msg, flush=True)


def get(url, timeout=300):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout)


def download(url, dest, note=''):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        log('  SKIP  %s' % os.path.basename(dest))
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    t = time.time()
    r = get(url)
    total = int(r.headers.get('Content-Length') or 0)
    tmp = dest + '.part'
    n = 0
    last = 0
    with open(tmp, 'wb') as f:
        while True:
            c = r.read(1 << 20)
            if not c:
                break
            f.write(c)
            n += len(c)
            if n - last > (150 << 20):
                log('        %.0f / %.0f MB' % (n / 2 ** 20, total / 2 ** 20))
                last = n
    os.replace(tmp, dest)
    log('  OK    %-44s %7.1f MB %4.0fs %s' % (os.path.basename(dest), n / 2 ** 20, time.time() - t, note))
    return dest


def extract(zip_path, target, strip_single_root=True, move_up=False):
    """Extract a zip, optionally removing a single wrapper directory."""
    os.makedirs(target, exist_ok=True)
    zipfile.ZipFile(zip_path).extractall(target)
    inner = [d for d in os.listdir(target) if os.path.isdir(os.path.join(target, d))]
    files = [f for f in os.listdir(target) if os.path.isfile(os.path.join(target, f))]
    if strip_single_root and len(inner) == 1 and not files:
        src = os.path.join(target, inner[0])
        for item in os.listdir(src):
            shutil.move(os.path.join(src, item), os.path.join(target, item))
        os.rmdir(src)


def main():
    os.makedirs(DEST, exist_ok=True)
    log('toolchain directory: %s' % DEST)

    # ---------------------------------------------------------------- JDK 21
    jdk_present = any(d.startswith('jdk-') for d in os.listdir(DEST)) if os.path.isdir(DEST) else False
    if not jdk_present:
        log('\n[1/4] Temurin JDK 21')
        try:
            with get('https://api.adoptium.net/v3/assets/latest/21/hotspot'
                     '?architecture=x64&image_type=jdk&os=windows&vendor=eclipse') as r:
                arr = json.load(r)
            pkg = arr[0]['binary']['package']
            z = download(pkg['link'], os.path.join(DEST, pkg['name']))
            extract(z, DEST)
            log('  jdk ready')
        except Exception as e:
            log('  FAILED: %s' % e)
    else:
        log('\n[1/4] JDK already present')

    # -------------------------------------------------------- build-tools
    if not os.path.exists(os.path.join(DEST, 'build-tools', 'aapt2.exe')):
        log('\n[2/4] Android build-tools')
        try:
            xml = get(GOOGLE + 'repository2-3.xml').read().decode('utf-8', 'replace')
            zips = re.findall(r'<url>([^<]*build-tools[^<]*windows\.zip)</url>', xml)

            def ver(u):
                m = re.search(r'build-tools_r([0-9.]+(?:-rc\d+)?)-', u)
                return tuple(int(x) for x in re.findall(r'\d+', m.group(1))) if m else (0,)

            zips.sort(key=ver)
            pick = zips[-1]
            z = download(GOOGLE + pick, os.path.join(DEST, pick.split('/')[-1]))
            extract(z, os.path.join(DEST, 'build-tools'))
            log('  build-tools ready (%s)' % pick)
        except Exception as e:
            log('  FAILED: %s' % e)
    else:
        log('\n[2/4] build-tools already present')

    # ------------------------------------------------------- platform-tools
    if not os.path.exists(os.path.join(DEST, 'platform-tools', 'adb.exe')):
        log('\n[3/4] Android platform-tools (adb)')
        try:
            z = download(GOOGLE + 'platform-tools-latest-windows.zip',
                         os.path.join(DEST, 'platform-tools.zip'))
            extract(z, os.path.join(DEST, 'platform-tools'))
            log('  adb ready')
        except Exception as e:
            log('  FAILED: %s' % e)
    else:
        log('\n[3/4] platform-tools already present')

    # ------------------------------------------------------- android.jar
    plat_dir = os.path.join(DEST, 'platforms')
    have = os.path.isdir(plat_dir) and any(
        os.path.exists(os.path.join(plat_dir, d, 'android.jar')) for d in os.listdir(plat_dir))
    if not have:
        log('\n[4/4] Android platform (android.jar)')
        try:
            xml = get(GOOGLE + 'repository2-3.xml').read().decode('utf-8', 'replace')
            cands = re.findall(r'<url>([^<]*platform-3[0-9][^<]*\.zip)</url>', xml)
            pick = sorted(cands)[-1]
            z = download(GOOGLE + pick, os.path.join(DEST, pick.split('/')[-1]))
            extract(z, plat_dir)
            log('  android.jar ready (%s)' % pick)
        except Exception as e:
            log('  FAILED: %s' % e)
    else:
        log('\n[4/4] android.jar already present')

    log('\n=== summary ===')
    for sub in ('build-tools', 'platform-tools', 'platforms'):
        p = os.path.join(DEST, sub)
        if os.path.isdir(p):
            log('  %-16s %s' % (sub, ', '.join(sorted(os.listdir(p))[:4])))
    jdks = [d for d in os.listdir(DEST) if d.startswith('jdk-')]
    if jdks:
        log('  %-16s %s' % ('jdk', ', '.join(jdks)))
    log('\nnow run:  python tools/build_app.py')


if __name__ == '__main__':
    main()
