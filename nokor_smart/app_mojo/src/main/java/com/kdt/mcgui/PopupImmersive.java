package com.kdt.mcgui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;

/**
 * A popup is its own window. When it takes the focus the system bars slide back in,
 * so this hides them again every time the popup is shown.
 */
final class PopupImmersive {
    private PopupImmersive() {}

    /** @param content the content view of the popup window */
    static void keep(@NonNull View content) {
        content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
                hide(v);
                v.post(() -> hide(v));
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {}
        });
        if (content.isAttachedToWindow()) hide(content);
    }

    @SuppressWarnings("deprecation")
    private static void hide(@NonNull View view) {
        if (!view.isAttachedToWindow()) return;
        Activity activity = findActivity(view.getContext());
        // In a window the bars belong to the window, they are not hidden there
        if (activity == null || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode())) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = view.getWindowInsetsController();
            if (controller == null) return;
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.systemBars());
        } else {
            view.getRootView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
