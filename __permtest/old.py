"""
Build the app without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.

Important: aapt2 cannot read a resource directory whose path contains non-ASCII
characters (it fails with "failed to open directory"). This workspace path is
Chinese, so the whole build is staged into an ASCII scratch directory on the same
drive and only the finished APK is copied back.
"""
import os, shutil, subprocess, sys, time, zipfile

# The console here is a GBK code page. Tool command lines can contain arbitrary
# Unicode (e.g. translated strings inside AndroidX resources), so never let a
# print() abort the build.
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, 'app')
# Third-party tools live here; tools/setup_toolchain.py populates it on demand.
TOOLS = os.path.join(ROOT, 'tools', 'toolchain')

SRC = os.path.join(APP, 'src')
RES = os.path.join(APP, 'res')
MANIFEST = os.path.join(APP, 'AndroidManifest.xml')
OUT = os.path.join(APP, 'build')


def _first_dir(parent, prefix):
    """Pick a versioned directory such as jdk-21.0.12.1+1 or android-34."""
    if not os.path.isdir(parent):
        return None
    for name in sorted(os.listdir(parent)):
        if name.startswith(prefix) and os.path.isdir(os.path.join(parent, name)):
            return os.path.join(parent, name)
    return None


def _paths():
    """Resolve tool paths. Called after setup_toolchain has had a chance to run."""
    jdk = _first_dir(TOOLS, 'jdk-')
    platform = _first_dir(os.path.join(TOOLS, 'platforms'), 'android-')
    p = dict(
        JDK=jdk,
        JAVA=os.path.join(jdk, 'bin', 'java.exe') if jdk else None,
        JAVAC=os.path.join(jdk, 'bin', 'javac.exe') if jdk else None,
        KEYTOOL=os.path.join(jdk, 'bin', 'keytool.exe') if jdk else None,
        BT=os.path.join(TOOLS, 'build-tools'),
        ANDROID_JAR=os.path.join(platform, 'android.jar') if platform else None,
    )
    p['AAPT2'] = os.path.join(p['BT'], 'aapt2.exe')
    p['D8'] = os.path.join(p['BT'], 'd8.bat')
    p['ZIPALIGN'] = os.path.join(p['BT'], 'zipalign.exe')
    p['APKSIGNER'] = os.path.join(p['BT'], 'apksigner.bat')
    return p


P = _paths()
JDK = P['JDK']
JAVA = P['JAVA']
JAVAC = P['JAVAC']
KEYTOOL = P['KEYTOOL']
BT = P['BT']
ANDROID_JAR = P['ANDROID_JAR']
AAPT2 = P['AAPT2']
D8 = P['D8']
ZIPALIGN = P['ZIPALIGN']
APKSIGNER = P['APKSIGNER']

# A local signing key is generated on first run; it is intentionally not committed.
KS = os.path.join(TOOLS, 'signing.keystore')
VER_FILE = os.path.join(TOOLS, 'versioncode.txt')

# ASCII-only scratch area, because aapt2 cannot read a resource directory whose
# path contains non-ASCII characters.
STAGE_ROOT = os.path.join(os.environ.get('TEMP', r'C:\Windows\Temp'), 'st_build')
STAGE = os.path.join(STAGE_ROOT, 'proj')
BUILD = os.path.join(STAGE, 'build')


def run(args, env=None, timeout=1800, cwd=None):
    print('>>>', ' '.join(os.path.basename(str(a)) if i == 0 else str(a) for i, a in enumerate(args)), flush=True)
    e = dict(os.environ)
    e['JAVA_HOME'] = JDK
    e['TEMP'] = STAGE_ROOT
    e['TMP'] = STAGE_ROOT
    if env:
        e.update(env)
    # Decode bytes ourselves: text=True would use the console code page (GBK)
    # and die on output that legitimately contains UTF-8.
    r = subprocess.run(args, capture_output=True, env=e, timeout=timeout, cwd=cwd)
    out = (r.stdout or b'').decode('utf-8', 'replace')
    err = (r.stderr or b'').decode('utf-8', 'replace')
    if out.strip():
        print(out.strip()[-4000:], flush=True)
    if err.strip():
        print(err.strip()[-4000:], flush=True)
    return r


def stage_inputs():
    if os.path.isdir(STAGE):
        shutil.rmtree(STAGE, ignore_errors=True)
    os.makedirs(BUILD, exist_ok=True)
    shutil.copytree(SRC, os.path.join(STAGE, 'src'))
    shutil.copy2(MANIFEST, os.path.join(STAGE, 'AndroidManifest.xml'))
    if os.path.isdir(RES):
        shutil.copytree(RES, os.path.join(STAGE, 'res'))
    print('staged into %s' % STAGE, flush=True)


def ensure_toolchain():
    """Download the SDK/JDK pieces on first use, so a fresh clone can build."""
    needed = [ANDROID_JAR, AAPT2, D8, ZIPALIGN, APKSIGNER, JAVAC]
    if all(p and os.path.exists(p) for p in needed):
        return True
    setup = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'setup_toolchain.py')
    if not os.path.exists(setup):
        return False
    print('toolchain incomplete - running setup_toolchain.py (large download)', flush=True)
    r = subprocess.run([sys.executable, setup], cwd=ROOT)
    if r.returncode != 0:
        return False
    globals().update({k: v for k, v in _paths().items()})
    return True


def main():
    if not ensure_toolchain():
        for p in (ANDROID_JAR, AAPT2, D8, ZIPALIGN, APKSIGNER, JAVAC):
            print('  missing: %s' % p, flush=True)
        sys.exit('toolchain not available; run tools/setup_toolchain.py')
    for p in (ANDROID_JAR, AAPT2, D8, ZIPALIGN, APKSIGNER, JAVAC):
        if not os.path.exists(p):
            sys.exit('missing tool: %s' % p)

    stage_inputs()
    manifest = os.path.join(STAGE, 'AndroidManifest.xml')
    res = os.path.join(STAGE, 'res')

    # ---------- 1. compile resources ----------
    res_zip = os.path.join(BUILD, 'res.zip')
    have_res = os.path.isdir(res)
    if have_res:
        r = run([AAPT2, 'compile', '--dir', res, '-o', res_zip])
        if r.returncode != 0:
            sys.exit('aapt2 compile failed')
    else:
        print('no res/ directory, skipping resource compile', flush=True)

    # ---------- 2. link ----------
    # Auto-incrementing versionCode makes each build a distinct update.
    ver_file = VER_FILE
    try:
        code = int(open(ver_file).read().strip()) + 1
    except Exception:
        code = 2
    open(ver_file, 'w').write(str(code))
    print('build versionCode=%d' % code, flush=True)

    apk_unsigned = os.path.join(BUILD, 'unsigned.apk')
    gen = os.path.join(BUILD, 'gen')
    os.makedirs(gen, exist_ok=True)
    args = [AAPT2, 'link', '-o', apk_unsigned, '-I', ANDROID_JAR,
            '--manifest', manifest, '--java', gen,
            '--min-sdk-version', '24', '--target-sdk-version', '35',
            '--version-code', str(code), '--version-name', '1.%d' % code]
    if os.path.exists(res_zip):
        args.append(res_zip)
    r = run(args)
    if r.returncode != 0:
        sys.exit('aapt2 link failed')
    print('linked -> %d bytes' % os.path.getsize(apk_unsigned), flush=True)

    # ---------- 3. javac ----------
    classes = os.path.join(BUILD, 'classes')
    os.makedirs(classes, exist_ok=True)
    sources = []
    for base, _d, files in os.walk(os.path.join(STAGE, 'src')):
        for f in files:
            if f.endswith('.java'):
                sources.append(os.path.join(base, f))
    for base, _d, files in os.walk(gen):
        for f in files:
            if f.endswith('.java'):
                sources.append(os.path.join(base, f))
    print('java sources: %d' % len(sources), flush=True)
    r = run([JAVAC, '-source', '8', '-target', '8', '-nowarn',
             '-bootclasspath', ANDROID_JAR, '-classpath', ANDROID_JAR,
             '-d', classes] + sources)
    if r.returncode != 0:
        sys.exit('javac failed')

    # ---------- 4. d8 ----------
    dexdir = os.path.join(BUILD, 'dex')
    os.makedirs(dexdir, exist_ok=True)
    clazz = []
    for base, _d, files in os.walk(classes):
        for f in files:
            if f.endswith('.class'):
                clazz.append(os.path.join(base, f))
    r = run([D8, '--min-api', '24', '--output', dexdir] + clazz)
    if r.returncode != 0:
        sys.exit('d8 failed')

    # ---------- 5. assemble ----------
    apk_dex = os.path.join(BUILD, 'withdex.apk')
    z = zipfile.ZipFile(apk_unsigned, 'r')
    out = zipfile.ZipFile(apk_dex, 'w', zipfile.ZIP_DEFLATED)
    for info in z.infolist():
        out.writestr(info, z.read(info.filename))
    z.close()
    for f in sorted(os.listdir(dexdir)):
        if f.endswith('.dex'):
            out.write(os.path.join(dexdir, f), f)
            print('added %s' % f, flush=True)
    out.close()

    # ---------- 6. align + sign ----------
    aligned = os.path.join(BUILD, 'aligned.apk')
    if os.path.exists(aligned):
        os.remove(aligned)
    r = run([ZIPALIGN, '-f', '-p', '4', apk_dex, aligned])
    if r.returncode != 0:
        sys.exit('zipalign failed')

    if not os.path.exists(KS):
        run([KEYTOOL, '-genkeypair', '-keystore', KS, '-alias', 'applimit',
             '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
             '-storepass', 'android', '-keypass', 'android',
             '-dname', 'CN=screentime, OU=dev, O=dev, L=dev, ST=dev, C=CN'])

    signed_stage = os.path.join(BUILD, 'signed.apk')
    if os.path.exists(signed_stage):
        os.remove(signed_stage)
    r = run([APKSIGNER, 'sign', '--ks', KS, '--ks-pass', 'pass:android',
             '--key-pass', 'pass:android', '--ks-key-alias', 'applimit',
             '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
             '--v3-signing-enabled', 'true', '--out', signed_stage, aligned])
    if r.returncode != 0:
        sys.exit('apksigner failed')

    run([APKSIGNER, 'verify', '--print-certs', signed_stage])

    # ---------- 7. copy the finished APK back into the workspace ----------
    os.makedirs(OUT, exist_ok=True)
    final = os.path.join(OUT, 'screentime.apk')
    shutil.copy2(signed_stage, final)
    print()
    print('APK: %s  (%.2f MB)' % (final, os.path.getsize(final) / 2 ** 20), flush=True)
    print('BUILD OK', flush=True)


if __name__ == '__main__':
    main()
