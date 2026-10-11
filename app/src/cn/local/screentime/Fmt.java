package cn.local.screentime;

import java.util.Locale;

/** Formatting helpers shared by the views. */
public final class Fmt {

    private Fmt() {}

    /** "2小时35分" / "48分" / "35秒" */
    public static String dur(long ms) {
        if (ms <= 0) return "0分";
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        if (h > 0) return h + "小时" + m + "分";
        if (m > 0) return m + "分";
        return s + "秒";
    }

    /** Compact form for chart labels: "2h35" / "48m" */
    public static String durShort(long ms) {
        if (ms <= 0) return "0";
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        if (h > 0) return h + "h" + (m > 0 ? m : "");
        return m + "m";
    }

    public static String hhmm(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        return String.format(Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    /** "10月11日 周六" */
    public static String dayLabel(String day) {
        try {
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            java.util.Date d = in.parse(day);
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTime(d);
            String[] w = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
            return String.format(Locale.US, "%d月%d日 %s",
                    c.get(java.util.Calendar.MONTH) + 1,
                    c.get(java.util.Calendar.DAY_OF_MONTH),
                    w[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]);
        } catch (Exception e) {
            return day;
        }
    }

    /** Human-readable app label fallback from a package name. */
    public static String pkgToName(String pkg) {
        if (pkg == null) return "?";
        int i = pkg.lastIndexOf('.');
        String s = i >= 0 && i + 1 < pkg.length() ? pkg.substring(i + 1) : pkg;
        if (s.isEmpty()) return pkg;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
