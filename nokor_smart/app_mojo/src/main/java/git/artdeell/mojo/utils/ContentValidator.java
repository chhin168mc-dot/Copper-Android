package git.artdeell.mojo.utils;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import git.artdeell.mojo.R;
import git.artdeell.mojo.modding.modpacks.models.ContentType;

/**
 * Checks a downloaded mod, resource pack or shader pack before it is moved into the game folder.
 * A broken or half downloaded file in the folder is a common reason for a game that crashes at
 * start or a pack that is silently missing from the list.
 *
 * <p>The checks are the ones that are certain: the file is not empty, its checksum matches the one
 * of the download when it is known, it is a readable ZIP, and a resource pack has the
 * {@code pack.mcmeta} that Minecraft looks for in the root of the ZIP. A shader pack needs a
 * {@code shaders} folder in its root, or in the one folder that wraps it. Mods are not checked for
 * a loader descriptor, old Forge mods have none.
 */
public final class ContentValidator {
    private static final String TAG = "ContentValidator";
    private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");

    private ContentValidator() {}

    public static final class Result {
        public final boolean ok;
        /** The reason as a string resource, 0 when the file is fine */
        @StringRes public final int reason;

        private Result(boolean ok, @StringRes int reason) {
            this.ok = ok;
            this.reason = reason;
        }
    }

    private static final Result OK = new Result(true, 0);

    private static Result fail(@StringRes int reason) {
        return new Result(false, reason);
    }

    /**
     * @param expectedSha1 the checksum of the download, anything that is not a SHA-1 is ignored
     */
    @NonNull
    public static Result validate(@NonNull File file, @NonNull ContentType type, @Nullable String expectedSha1) {
        if (!file.isFile() || file.length() == 0) return fail(R.string.content_check_empty);

        if (expectedSha1 != null && SHA1.matcher(expectedSha1).matches()) {
            try {
                if (!expectedSha1.equalsIgnoreCase(HashUtils.sha1Hex(file))) return fail(R.string.content_check_hash);
            } catch (IOException e) {
                Log.w(TAG, "Could not hash " + file, e);
                return fail(R.string.content_check_not_zip);
            }
        }

        // Screenshots and any other kind of file are not archives
        if (type == ContentType.SCREENSHOT) return OK;

        try (ZipFile zip = new ZipFile(file)) {
            switch (type) {
                case RESOURCE_PACK:
                    return zip.getEntry("pack.mcmeta") != null ? OK : fail(R.string.content_check_no_pack_mcmeta);
                case SHADER_PACK:
                    return hasShadersFolder(zip) ? OK : fail(R.string.content_check_no_shaders_folder);
                default:
                    return OK;
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, file.getName() + " is not a readable archive", e);
            return fail(R.string.content_check_not_zip);
        }
    }

    /** "shaders/..." or "<one folder>/shaders/..." */
    private static boolean hasShadersFolder(ZipFile zip) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName().toLowerCase(Locale.ROOT);
            if (name.startsWith("shaders/")) return true;
            int slash = name.indexOf('/');
            if (slash > 0 && name.startsWith("shaders/", slash + 1)) return true;
        }
        return false;
    }
}
