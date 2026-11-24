package git.artdeell.mojo.skins;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.AuthType;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.accounts.Accounts;

/**
 * Finds the skin of the selected account for the home screen: the skin picked in the skin editor,
 * else the one the Microsoft or Ely.by skin service has, else Steve.
 */
public final class PlayerSkinLoader {
    private static final String TAG = "PlayerSkinLoader";
    private static final int MAX_BYTES = 1024 * 1024;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PlayerSkinLoader() {}

    public interface Callback {
        void onLoaded(@NonNull Result result);
    }

    public static final class Result {
        @NonNull public final Bitmap skin;
        public final boolean slim;
        @NonNull public final String name;
        /** Identifies the account and the skin, so a screen can tell if it already shows this one */
        @NonNull public final String key;
        /** The cape picked in the skin editor, null for none */
        @Nullable public final Bitmap cape;

        Result(@NonNull Bitmap skin, boolean slim, @NonNull String name, @NonNull String key, @Nullable Bitmap cape) {
            this.skin = skin;
            this.slim = slim;
            this.name = name;
            this.key = key;
            this.cape = cape;
        }
    }

    /** Loads on a background thread and calls back on the main thread */
    public static void load(@NonNull Callback callback) {
        EXECUTOR.execute(() -> {
            Result result;
            try {
                result = loadBlocking();
            } catch (Throwable t) {
                Log.w(TAG, "Could not load the skin", t);
                result = steve("Steve", null, "");
            }
            Result done = result;
            MAIN.post(() -> callback.onLoaded(done));
        });
    }

    @NonNull
    private static Result loadBlocking() {
        Account account = Accounts.getCurrent();
        if (account == null) return steve("Steve", null, "");
        String name = account.username == null ? "Steve" : account.username;
        AuthType type = account.authType == null ? AuthType.LOCAL : account.authType;

        // The skin picked in the skin editor is the newest, the skin services lag behind an upload
        String libraryKey = type == AuthType.LOCAL ? SkinLibrary.localKey(name) : account.profileId;
        String capeId = libraryKey == null ? null : SkinLibrary.getSelectedCape(libraryKey);
        Bitmap cape = loadCape(capeId);
        // The key changes with the cape, so the home screen redraws when another one is picked
        String capeKey = cape == null ? "" : ":cape:" + capeId;
        if (libraryKey != null) {
            SkinLibrary.Entry entry = SkinLibrary.find(SkinLibrary.getSelected(libraryKey));
            if (entry != null) {
                Bitmap bitmap = BitmapFactory.decodeFile(SkinLibrary.fileOf(entry).getAbsolutePath());
                if (bitmap != null) return new Result(bitmap, entry.slim, name, type.name() + ":" + name + ":" + entry.id + capeKey, cape);
            }
        }

        if (type.skinUrl != null) {
            Bitmap bitmap = loadOnline(type, name);
            if (bitmap != null) {
                return new Result(bitmap, SkinTextures.looksSlim(bitmap), name, type.name() + ":" + name + ":online" + capeKey, cape);
            }
        }
        return steve(name, cape, capeKey);
    }

    @NonNull
    private static Result steve(@NonNull String name, @Nullable Bitmap cape, @NonNull String capeKey) {
        return new Result(DefaultSkins.steve(), false, name, "steve:" + name + capeKey, cape);
    }

    /** The texture of the cape with this id: official and custom capes of the library, or the Microsoft cape the editor cached */
    @Nullable
    private static Bitmap loadCape(@Nullable String capeId) {
        if (capeId == null) return null;
        Bitmap bitmap = null;
        try {
            byte[] data = CapeCatalog.loadAny(capeId);
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not read the cape " + capeId, e);
        }
        if (bitmap == null) {
            File cached = new File(new File(Tools.DIR_CACHE, "capes"), capeId + ".png");
            if (cached.exists()) bitmap = BitmapFactory.decodeFile(cached.getAbsolutePath());
        }
        return bitmap;
    }

    @Nullable
    private static Bitmap loadOnline(@NonNull AuthType type, @NonNull String username) {
        File cache = new File(Tools.DIR_CACHE, "skin-full-" + type.name() + "-" + username.replaceAll("[^A-Za-z0-9_.-]", "_") + ".png");
        try {
            String url = String.format(type.skinUrl, URLEncoder.encode(username, "UTF-8"));
            byte[] png = download(url);
            Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bitmap != null && isUsable(bitmap)) {
                try (FileOutputStream out = new FileOutputStream(cache)) {
                    out.write(png);
                } catch (IOException e) {
                    Log.w(TAG, "Could not cache the skin", e);
                }
                return bitmap;
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not download the skin of " + username, e);
        }
        // Offline or the service has no skin for this name: the last good download is better than nothing
        if (cache.exists()) {
            Bitmap cached = BitmapFactory.decodeFile(cache.getAbsolutePath());
            if (cached != null && isUsable(cached)) return cached;
        }
        return null;
    }

    /** 64x64 or 64x32 skins and their HD versions */
    private static boolean isUsable(@NonNull Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        return w >= 64 && w % 64 == 0 && (h == w || h * 2 == w);
    }

    @NonNull
    private static byte[] download(@NonNull String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(true);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_BYTES) throw new IOException("Skin is too big");
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }
}
