package cn.local.screentime;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves package names to display labels, cached.
 *
 * The usage-event stream also mentions packages that are not really usable apps:
 * things uninstalled long ago, other users' / cloned profiles, and some system
 * internals. We need to tell those apart from real apps so the list does not show
 * blank rows - but the test has to be reliable.
 *
 * Note on package visibility: getApplicationInfo() is subject to the visibility
 * rules and throws NameNotFoundException for packages that are actually present.
 * So instead of probing per package we build one authoritative set up front from
 * getInstalledApplications() plus every activity that answers a launcher Intent.
 */
public class Labels {

    private final PackageManager pm;
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Set<String> known = new HashSet<>();
    private final Map<String, Boolean> extraProbe = new HashMap<>();

    public Labels(Context ctx) {
        this.pm = ctx.getPackageManager();

        try {
            List<ApplicationInfo> all = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : all) {
                known.add(ai.packageName);
                CharSequence l = pm.getApplicationLabel(ai);
                names.put(ai.packageName, l == null ? ai.packageName : l.toString());
                try {
                    icons.put(ai.packageName, pm.getApplicationIcon(ai));
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}

        // Anything with a launcher entry is definitely a real app the user can see.
        try {
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(launcher, 0);
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo == null) continue;
                String p = ri.activityInfo.packageName;
                known.add(p);
                if (!names.containsKey(p)) {
                    CharSequence l = ri.loadLabel(pm);
                    names.put(p, l == null ? p : l.toString());
                }
                if (!icons.containsKey(p)) {
                    try { icons.put(p, ri.loadIcon(pm)); } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable ignore) {}
    }

    public String name(String pkg) {
        String s = names.get(pkg);
        return s != null ? s : Fmt.pkgToName(pkg);
    }

    public Drawable icon(String pkg) {
        return icons.get(pkg);
    }

    /**
     * True for packages we are confident are real, installed apps.
     *
     * Tries three independent strategies, because package visibility can defeat
     * any single one of them.
     */
    public boolean isInstalled(String pkg) {
        if (pkg == null) return false;
        if (known.contains(pkg)) return true;
        Boolean cached = extraProbe.get(pkg);
        if (cached != null) return cached;

        boolean ok = false;

        // 1) enumerate installed packages and look for it
        try {
            java.util.List<android.content.pm.PackageInfo> pis =
                    pm.getInstalledPackages(0);
            for (android.content.pm.PackageInfo pi : pis) {
                if (pkg.equals(pi.packageName)) { ok = true; break; }
            }
        } catch (Throwable ignore) {}

        // 2) direct lookup
        if (!ok) {
            try {
                pm.getApplicationInfo(pkg, 0);
                ok = true;
            } catch (Throwable ignore) {}
        }

        // 3) resolve a launcher Intent aimed specifically at that package
        if (!ok) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                i.setPackage(pkg);
                List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
                if (ris != null && !ris.isEmpty()) ok = true;
            } catch (Throwable ignore) {}
        }

        if (ok) known.add(pkg);
        extraProbe.put(pkg, ok);
        return ok;
    }

    /**
     * Why a package failed to be recognised - for diagnosing false negatives.
     * Returns a compact "strategy=result" summary.
     */
    public String diagnose(String pkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("inKnownSet=").append(known.contains(pkg));
        sb.append(" inNameMap=").append(names.containsKey(pkg));
        sb.append(" inIconMap=").append(icons.containsKey(pkg));

        boolean inList = false;
        try {
            for (android.content.pm.PackageInfo pi : pm.getInstalledPackages(0)) {
                if (pkg.equals(pi.packageName)) { inList = true; break; }
            }
        } catch (Throwable t) { sb.append(" listErr=").append(t.getClass().getSimpleName()); }
        sb.append(" inInstalledList=").append(inList);

        try {
            pm.getApplicationInfo(pkg, 0);
            sb.append(" getApplicationInfo=ok");
        } catch (Throwable t) {
            sb.append(" getApplicationInfo=").append(t.getClass().getSimpleName());
        }

        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setPackage(pkg);
            List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
            sb.append(" resolveLauncher=").append(ris == null ? -1 : ris.size());
        } catch (Throwable t) {
            sb.append(" resolveLauncherErr=").append(t.getClass().getSimpleName());
        }

        try {
            sb.append(" label=").append(String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))));
        } catch (Throwable ignore) {}

        return sb.toString();
    }

    public int knownCount() {
        return known.size();
    }
}
