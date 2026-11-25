package git.artdeell.mojo.space;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;

/** Opening animation for the launcher. It lasts {@link #TOTAL_MS} from the start to the end of the fade out. */
public final class IntroAnimator {
    public static final long TOTAL_MS = 5000L;

    private static final long FADE_OUT_MS = 700L;
    private static final long FADE_START_MS = TOTAL_MS - FADE_OUT_MS;
    private static final long WARP_MS = 3800L;

    private IntroAnimator() {}

    public static void play(@NonNull View overlay, @NonNull SpaceBackgroundView space,
                            @NonNull View logo, @NonNull View title) {
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(1f);
        overlay.setClickable(true);
        float offset = 24f * overlay.getResources().getDisplayMetrics().density;
        logo.setAlpha(0f);
        logo.setScaleX(0.55f);
        logo.setScaleY(0.55f);
        title.setAlpha(0f);
        title.setTranslationY(offset);
        space.setWarp(1f);

        ValueAnimator warp = ValueAnimator.ofFloat(1f, 0f);
        warp.setDuration(WARP_MS);
        warp.setInterpolator(new DecelerateInterpolator(1.6f));
        warp.addUpdateListener(a -> space.setWarp((float) a.getAnimatedValue()));
        warp.start();

        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(1800L).setStartDelay(400L)
                .setInterpolator(new OvershootInterpolator(1.15f)).start();
        title.animate().alpha(1f).translationY(0f)
                .setDuration(1500L).setStartDelay(1300L)
                .setInterpolator(new DecelerateInterpolator()).start();

        overlay.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f)
                .setStartDelay(FADE_START_MS)
                .setDuration(FADE_OUT_MS)
                .setInterpolator(new AccelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        overlay.setVisibility(View.GONE);
                        overlay.setClickable(false);
                        overlay.setScaleX(1f);
                        overlay.setScaleY(1f);
                        overlay.animate().setListener(null);
                    }
                }).start();
    }
}
