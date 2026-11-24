package git.artdeell.mojo.perf;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import git.artdeell.mojo.game.renderer.GameRenderer;
import git.artdeell.mojo.game.renderer.RenderSpec;
import git.artdeell.mojo.game.renderer.def.Renderers;

/**
 * Suggests a renderer for the device. It never changes the setting by itself: the launcher
 * already switches renderers when a version needs it, and a renderer that is changed behind the
 * player's back is a common reason for a game that does not start.
 */
public final class RendererAdvisor {
    private RendererAdvisor() {}

    public static final class Advice {
        public final String rendererId;
        public final String reason;

        Advice(String rendererId, String reason) {
            this.rendererId = rendererId;
            this.reason = reason;
        }
    }

    /**
     * @param coreContext true for Minecraft 1.17 and newer (core OpenGL context), false for the
     *                    older versions that use the fixed-function pipeline
     */
    @NonNull
    public static Advice advise(@NonNull Context context, @NonNull DeviceProfile device, boolean coreContext) {
        if (!coreContext) {
            return new Advice(Renderers.GL4ES_RENDERER,
                    "GL4ES gives the fixed-function pipeline that Minecraft before 1.17 needs.");
        }
        if (device.is32Bit || (device.glesMajor > 0 && device.glesMajor < 3)) {
            return new Advice(Renderers.GL4ES_RENDERER,
                    "This device has no OpenGL ES 3 or runs a 32 bit system, the core renderers need it.");
        }
        // The first renderer of the list that this device can use
        String[] candidates = {Renderers.LTW_RENDERER, Renderers.MOBILEGLUES_RENDERER};
        for (String id : candidates) {
            if (isUsable(context, id)) {
                String reason = id.equals(Renderers.LTW_RENDERER)
                        ? "OpenLTW runs core OpenGL contexts on top of OpenGL ES 3."
                        : "MobileGlues runs core OpenGL contexts on top of OpenGL ES 3.";
                if (device.isOldAdreno()) {
                    reason += " Old Adreno GPUs can show glitches with it, switch back if you see any.";
                }
                return new Advice(id, reason);
            }
        }
        return new Advice(Renderers.GL4ES_RENDERER, "No other renderer reports that it works on this device.");
    }

    private static boolean isUsable(Context context, @Nullable String id) {
        if (id == null) return false;
        try {
            RenderSpec spec = GameRenderer.getKnownRenderer(id);
            return spec != null && spec.compatibleDevice(context);
        } catch (Throwable t) {
            return false;
        }
    }
}
