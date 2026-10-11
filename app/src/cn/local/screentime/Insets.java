package cn.local.screentime;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * Keeps content clear of the status bar, the camera cut-out and the navigation
 * bar.
 *
 * On newer Android the window is laid out edge-to-edge regardless of the theme,
 * so on a device with a punch-hole camera the first row of content ends up
 * underneath it - which is exactly what "the button is covered" means.
 */
public final class Insets {

    private Insets() {}

    public static void apply(final Activity activity, final View root) {
        final int basePadLeft = root.getPaddingLeft();
        final int basePadTop = root.getPaddingTop();
        final int basePadRight = root.getPaddingRight();
        final int basePadBottom = root.getPaddingBottom();

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top = 0, bottom = 0, left = 0, right = 0;

                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars =
                            insets.getInsets(WindowInsets.Type.systemBars());
                    top = bars.top;
                    bottom = bars.bottom;
                    left = bars.left;
                    right = bars.right;
                    android.graphics.Insets cut =
                            insets.getInsets(WindowInsets.Type.displayCutout());
                    top = Math.max(top, cut.top);
                    bottom = Math.max(bottom, cut.bottom);
                    left = Math.max(left, cut.left);
                    right = Math.max(right, cut.right);
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                    left = insets.getSystemWindowInsetLeft();
                    right = insets.getSystemWindowInsetRight();
                    if (Build.VERSION.SDK_INT >= 28) {
                        android.view.DisplayCutout dc = insets.getDisplayCutout();
                        if (dc != null) {
                            top = Math.max(top, dc.getSafeInsetTop());
                            bottom = Math.max(bottom, dc.getSafeInsetBottom());
                            left = Math.max(left, dc.getSafeInsetLeft());
                            right = Math.max(right, dc.getSafeInsetRight());
                        }
                    }
                }

                v.setPadding(basePadLeft + left, basePadTop + top,
                        basePadRight + right, basePadBottom + bottom);
                return insets;
            }
        });
        root.requestApplyInsets();
    }
}
