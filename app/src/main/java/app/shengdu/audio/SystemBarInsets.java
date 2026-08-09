package app.shengdu.audio;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;

/** Adds status, navigation, and display-cutout insets without replacing layout spacing. */
final class SystemBarInsets {
    private SystemBarInsets() {
    }

    @SuppressWarnings("deprecation")
    static void enableEdgeToEdge(Activity activity) {
        Window window = activity.getWindow();
        window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setNavigationBarContrastEnforced(false);
    }

    static void applyPadding(View view) {
        int initialLeft = view.getPaddingLeft();
        int initialTop = view.getPaddingTop();
        int initialRight = view.getPaddingRight();
        int initialBottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((target, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            target.setPadding(
                    initialLeft + insets.left,
                    initialTop + insets.top,
                    initialRight + insets.right,
                    initialBottom + insets.bottom);
            return WindowInsets.CONSUMED;
        });
        view.post(view::requestApplyInsets);
    }
}
