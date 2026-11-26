package git.artdeell.mojo.update;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import git.artdeell.mojo.BuildConfig;
import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;

/**
 * Shows the latest announcement of the backend in a banner on the home screen.
 * While the screen is on, the server is asked every {@link #POLL_MS} milliseconds, so an announcement
 * published in the admin page shows up on every phone by itself, and one that is deleted goes away.
 * The close button hides the announcement that is shown; a new one (other updated_at) shows again.
 */
public class AnnouncementBanner {
    private static final long POLL_MS = 10_000L;
    private static final String PREF_DISMISSED = "announcementDismissed";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Context context;
    private final View banner;
    private final TextView text;
    private final SharedPreferences prefs;

    private boolean running;
    private boolean allowed = true;
    private boolean expanded;
    @Nullable private String content;
    @Nullable private String stamp;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            fetch();
            handler.postDelayed(this, POLL_MS);
        }
    };

    public AnnouncementBanner(@NonNull View banner) {
        this.context = banner.getContext().getApplicationContext();
        this.banner = banner;
        this.text = banner.findViewById(R.id.announcement_text);
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
        View close = banner.findViewById(R.id.announcement_close);
        if (close != null) close.setOnClickListener(v -> {
            if (stamp != null) prefs.edit().putString(PREF_DISMISSED, stamp).apply();
            render();
        });
        banner.setOnClickListener(v -> {
            expanded = !expanded;
            render();
        });
    }

    public void start() {
        if (running) return;
        running = true;
        handler.removeCallbacks(pollTask);
        handler.post(pollTask);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(pollTask);
    }

    /** The banner belongs to the home screen only, other screens hide it */
    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
        render();
    }

    private void fetch() {
        final String apiUrl = context.getString(R.string.update_api_url).trim().replaceAll("/+$", "");
        if (!apiUrl.startsWith("https://")) return;
        MojoApplication.sExecutorService.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(apiUrl + "/api/announcement").openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent", "NokorLauncher/" + BuildConfig.VERSION_NAME);
                if (connection.getResponseCode() != 200) return;
                String body = org.apache.commons.io.IOUtils.toString(connection.getInputStream(), StandardCharsets.UTF_8).trim();
                String newContent = null;
                String newStamp = null;
                if (!body.isEmpty() && !body.equals("null")) {
                    @SuppressWarnings("deprecation")
                    JsonElement root = new JsonParser().parse(body);
                    if (root.isJsonObject()) {
                        JsonObject o = root.getAsJsonObject();
                        newContent = o.has("content") && !o.get("content").isJsonNull() ? o.get("content").getAsString().trim() : null;
                        if (o.has("updated_at") && !o.get("updated_at").isJsonNull()) newStamp = o.get("updated_at").getAsString();
                        else if (o.has("published_at") && !o.get("published_at").isJsonNull()) newStamp = o.get("published_at").getAsString();
                        if (newContent != null && newContent.isEmpty()) newContent = null;
                    }
                }
                final String c = newContent;
                final String s = newStamp;
                handler.post(() -> {
                    if (!running) return;
                    boolean changed = c == null ? content != null : !c.equals(content);
                    content = c;
                    stamp = s;
                    if (changed) expanded = false;
                    render();
                });
            } catch (Exception ignored) {
                // No network: the banner stays as it is, the next poll tries again
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void render() {
        boolean dismissed = stamp != null && stamp.equals(prefs.getString(PREF_DISMISSED, null));
        if (!allowed || content == null || dismissed) {
            banner.setVisibility(View.GONE);
            return;
        }
        text.setText(content);
        text.setMaxLines(expanded ? 12 : 3);
        banner.setVisibility(View.VISIBLE);
    }
}
