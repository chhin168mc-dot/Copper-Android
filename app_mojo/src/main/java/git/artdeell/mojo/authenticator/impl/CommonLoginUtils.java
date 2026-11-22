package git.artdeell.mojo.authenticator.impl;

import android.util.Log;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.AuthType;
import git.artdeell.mojo.authenticator.SessionExpiredException;
import git.artdeell.mojo.authenticator.model.OAuthTokenResponse;
import git.artdeell.mojo.utils.PresentableException;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Shared HTTP code of the Microsoft and Ely.by logins */
public class CommonLoginUtils {
    private static final String TAG = "CommonLogin";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_RETRY_DELAY_MS = 5000;
    /** Tokens are treated as expired this long before they really expire */
    private static final long EXPIRY_MARGIN_MS = 5 * 60 * 1000;
    private static final long DEFAULT_LIFETIME_MS = 24 * 60 * 60 * 1000;
    private static final String[] ERROR_TEXT_KEYS = {
            "error_description", "errorMessage", "message", "Message", "error_message", "error"
    };

    private static final ArrayList<String> sUsedCodes = new ArrayList<>();

    /** The result of a HTTP request. Errors of the server are results too, they are not thrown. */
    public static final class HttpResult {
        public final int code;
        public final String message;
        public final String body;

        HttpResult(int code, String message, String body) {
            this.code = code;
            this.message = message;
            this.body = body;
        }

        public boolean isSuccess() {
            return code >= 200 && code < 300;
        }
    }

    /**
     * An authorization code can be used only once. The web page of the login may report the same code
     * twice (for example after the screen is rotated), the second exchange would fail with "Bad Request"
     * and show an error although the login works.
     * @return true if the code was not used yet
     */
    public static synchronized boolean claimAuthCode(String code) {
        if (code == null) return true;
        if (sUsedCodes.contains(code)) return false;
        if (sUsedCodes.size() >= 16) sUsedCodes.remove(0);
        sUsedCodes.add(code);
        return true;
    }

    /**
     * Calculates when a token that lives for lifetimeMs milliseconds has to be refreshed.
     * The time is a bit shorter, so the token never expires in the middle of a game launch.
     */
    public static long expiryTime(long lifetimeMs) {
        if (lifetimeMs <= 0) lifetimeMs = DEFAULT_LIFETIME_MS;
        long margin = Math.min(EXPIRY_MARGIN_MS, lifetimeMs / 10);
        return System.currentTimeMillis() + lifetimeMs - margin;
    }

    /**
     * Exchanges an authorization code (or a refresh token) for the tokens.
     * @param isRefresh true if formData carries a refresh token
     * @param authType  the kind of the account, used if the session turns out to be expired
     */
    public static OAuthTokenResponse exchangeAuthCode(URL url, String formData, boolean isRefresh, AuthType authType) throws IOException, PresentableException {
        HttpResult result = execute(url, "POST", "application/x-www-form-urlencoded", formData, null);
        if (!result.isSuccess()) {
            Log.w(TAG, "Token request failed: HTTP " + result.code + " " + result.body);
            throw tokenFailure(result, authType, isRefresh);
        }
        OAuthTokenResponse response = Tools.GLOBAL_GSON.fromJson(result.body, OAuthTokenResponse.class);
        if (response == null || response.accessToken == null || response.accessToken.isEmpty()) {
            throw new PresentableException(R.string.global_error, R.string.microsoft_login_generic, "empty response");
        }
        return response;
    }

    /**
     * Sends a request. Network errors, timeouts, "too many requests" and server errors are tried again
     * a few times with a delay, they are usually gone after a moment (mobile data, WiFi that
     * switches, a busy server) and there is no reason to show an error for them.
     */
    public static HttpResult execute(URL url, String method, String contentType, String body, String bearerToken) throws IOException {
        for (int attempt = 1; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            long delay = 1000L * attempt;
            try {
                HttpResult result = executeOnce(url, method, contentType, body, bearerToken);
                if (!isTransient(result.code) || attempt >= MAX_ATTEMPTS) return result;
                Log.w(TAG, "HTTP " + result.code + " from " + url.getHost() + ", trying again");
            } catch (IOException e) {
                boolean cancelled = e instanceof InterruptedIOException && !(e instanceof SocketTimeoutException);
                if (cancelled || attempt >= MAX_ATTEMPTS) throw e;
                Log.w(TAG, "Request to " + url.getHost() + " failed, trying again: " + e);
            }
            try {
                Thread.sleep(Math.min(delay, MAX_RETRY_DELAY_MS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
        }
    }

    private static boolean isTransient(int code) {
        return code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    private static HttpResult executeOnce(URL url, String method, String contentType, String body, String bearerToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            if (bearerToken != null) conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            if (body != null) {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Type", contentType);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(data);
                }
            }
            int code = conn.getResponseCode();
            String message = conn.getResponseMessage();
            // The error stream is null if the server did not send a body
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new HttpResult(code, message, stream == null ? "" : readAll(stream));
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * @param data A series a strings: key1, value1, key2, value2...
     * @return the data converted as a form string for a POST request
     */
    public static String convertToFormData(String... data) throws UnsupportedEncodingException {
        StringBuilder builder = new StringBuilder();
        for(int i=0; i<data.length; i+=2){
            if (builder.length() > 0) builder.append("&");
            builder.append(URLEncoder.encode(data[i], "UTF-8"))
                    .append("=")
                    .append(URLEncoder.encode(data[i+1], "UTF-8"));
        }
        return builder.toString();
    }

    /** The error for a failed request of the token endpoint */
    public static PresentableException tokenFailure(HttpResult result, AuthType authType, boolean isRefresh) {
        // The refresh token is rejected: it expired or was revoked. Signing in again is the only fix.
        if (isRefresh && (result.code == 400 || result.code == 401)) {
            return new SessionExpiredException(authType);
        }
        return failure(result);
    }

    /** The error for any other failed request */
    public static PresentableException failure(HttpResult result) {
        Log.i(TAG, "Error code: " + result.code + ": " + result.message);
        if (result.code == 429) {
            return new PresentableException(R.string.microsoft_login_retry_later);
        }
        if (result.code >= 500) {
            return new PresentableException(R.string.global_error, R.string.login_server_unavailable, result.code);
        }
        return new PresentableException(R.string.global_error, R.string.microsoft_login_generic, describe(result));
    }

    /** Puts the reason that the server gave into the message, "Bad Request" alone says nothing */
    private static String describe(HttpResult result) {
        String text = extractErrorText(result.body);
        String status = result.message == null || result.message.isEmpty()
                ? Integer.toString(result.code) : result.code + " " + result.message;
        return text == null ? status : status + ": " + text;
    }

    private static String extractErrorText(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(body);
            for (String key : ERROR_TEXT_KEYS) {
                if (json.isNull(key)) continue;
                String value = json.optString(key, "");
                if (!value.isEmpty()) return value;
            }
        } catch (Exception e) {
            // Not a JSON body, there is no reason to show it
        }
        return null;
    }
}
