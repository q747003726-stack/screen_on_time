package cn.local.screentime;

/** Tiny shared helper for building the diagnostics text. */
public final class Utils {
    private Utils() {}

    public static void append(StringBuilder sb, String s) {
        sb.append(s).append('\n');
    }
}
