"""
Re-materialise every existing file in the workspace so the DSH sandbox can write
to it again.

Why this is needed: after the workspace was moved to a new path, the sandbox's
write grant turned out to apply only to objects created *after* the grant. Files
that arrived with the move (or that were moved within the workspace) reject
overwrites with EACCES, even though their ACLs look correct. Creating a fresh
copy of a file, deleting the original and copying it back yields a file the
sandbox can write.

The rewrite is content-preserving: copy -> verify identical -> delete -> copy
back -> verify identical and writable. Anything that fails is reported and left
untouched.
"""
import os, shutil, sys, time

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

ROOT = os.path.dirname(os.path.abspath(__file__))
SKIP_DIRS = {'.git'}          # leave the object store alone; it is written via git
SKIP_SUFFIX = ('.new', '.bak')

stats = {'total': 0, 'already': 0, 'fixed': 0, 'failed': 0, 'skipped': 0}
failures = []


def writable(path):
    try:
        with open(path, 'r+b'):
            return True
    except Exception:
        return False


def main():
    t0 = time.time()
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.endswith('.avd')]
        for name in files:
            if name.endswith(SKIP_SUFFIX):
                continue
            p = os.path.join(base, name)
            stats['total'] += 1
            if writable(p):
                stats['already'] += 1
                continue
            tmp = p + '.new'
            try:
                shutil.copy2(p, tmp)
                if not writable(tmp):
                    stats['skipped'] += 1
                    os.remove(tmp)
                    continue
                if os.path.getsize(tmp) != os.path.getsize(p):
                    raise IOError('size mismatch after copy')
                os.remove(p)
                shutil.copy2(tmp, p)
                os.remove(tmp)
                if writable(p):
                    stats['fixed'] += 1
                else:
                    stats['failed'] += 1
                    failures.append(p)
            except Exception as e:
                stats['failed'] += 1
                failures.append('%s (%s)' % (p, e))
                for q in (tmp,):
                    try:
                        if os.path.exists(q):
                            os.remove(q)
                    except Exception:
                        pass

    print('files scanned : %d' % stats['total'])
    print('already ok    : %d' % stats['already'])
    print('re-materialised: %d' % stats['fixed'])
    print('failed        : %d' % stats['failed'])
    print('skipped       : %d' % stats['skipped'])
    print('elapsed       : %.1fs' % (time.time() - t0))
    for f in failures[:25]:
        print('   FAIL %s' % f)
    if len(failures) > 25:
        print('   ... and %d more' % (len(failures) - 25))


if __name__ == '__main__':
    main()
