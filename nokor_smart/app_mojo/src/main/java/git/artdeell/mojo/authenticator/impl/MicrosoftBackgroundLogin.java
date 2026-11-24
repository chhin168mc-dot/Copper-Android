package git.artdeell.mojo.authenticator.impl;

import static git.artdeell.mojo.MojoApplication.sExecutorService;

import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;

import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.AuthType;
import git.artdeell.mojo.authenticator.BackgroundLogin;
import git.artdeell.mojo.authenticator.accounts.Accounts;
import git.artdeell.mojo.authenticator.listener.LoginListener;
import git.artdeell.mojo.authenticator.model.OAuthTokenResponse;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.utils.PresentableException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;

/** Allow to perform a background login on a given account */
public class MicrosoftBackgroundLogin implements BackgroundLogin{
    public static final BackgroundLogin.Creator CREATOR = MicrosoftBackgroundLogin::new;

    private static final String authTokenUrl = "https://login.live.com/oauth20_token.srf";
    private static final String xblAuthUrl = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String xstsAuthUrl = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String mcLoginUrl = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String mcProfileUrl = "https://api.minecraftservices.com/minecraft/profile";
    private static final String mcStoreUrl = "https://api.minecraftservices.com/entitlements/mcstore";

    /** A Minecraft access token lives for a day */
    private static final long MC_TOKEN_LIFETIME_MS = 86400000L;

    private static final Map<Long, Integer> XSTS_ERRORS;
    static {
        XSTS_ERRORS = new ArrayMap<>();
        XSTS_ERRORS.put(2148916233L, R.string.xerr_no_account);
        XSTS_ERRORS.put(2148916235L, R.string.xerr_not_available);
        XSTS_ERRORS.put(2148916236L ,R.string.xerr_adult_verification);
        XSTS_ERRORS.put(2148916237L ,R.string.xerr_adult_verification);
        XSTS_ERRORS.put(2148916238L ,R.string.xerr_child);
    }

    /* Fields used to fill the account  */
    public String msRefreshToken;
    public String mcName;
    public String mcToken;
    public String mcUuid;
    public String msXsts;
    public boolean doesOwnGame;
    public long expiresAt;

    private MicrosoftBackgroundLogin() {}

    private void acquireAccountDetails(
            @NonNull LoginListener loginListener, Callable<Void> continuation,
            String code, boolean isRefresh
    ) {
        ProgressLayout.setProgress(ProgressLayout.AUTHENTICATE, 0);
        sExecutorService.execute(() -> {
            loginListener.setMaxLoginProgress(5);
            try {
                notifyProgress(loginListener, 1);
                String accessToken = acquireAccessToken(isRefresh, code);
                notifyProgress(loginListener, 2);
                String xboxLiveToken = acquireXBLToken(accessToken);
                notifyProgress(loginListener, 3);
                String[] xsts = acquireXsts(xboxLiveToken);
                notifyProgress(loginListener, 4);
                String token = acquireToken(xsts[0], xsts[1]);
                notifyProgress(loginListener, 5);
                fetchOwnedItems(token);
                checkProfile(token);
                msXsts  = xsts[0];
                continuation.call();
            }catch (Exception e){
                Log.e("MicroAuth", "Exception thrown during authentication", e);
                Tools.runOnUiThread(()->loginListener.onLoginError(e));
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.AUTHENTICATE);
            }
        });
    }

    private void fillAccount(Account acc) {
        acc.xuid = msXsts;
        acc.accessToken = mcToken;
        acc.username = mcName;
        acc.profileId = mcUuid;
        acc.authType = AuthType.MICROSOFT;
        acc.refreshToken = msRefreshToken;
        acc.expiresAt = expiresAt;
        // The head is downloaded by the account list after the name is on the screen
    }

    @Override
    public void createAccount(@NonNull LoginListener loginListener, String code) {
        // The same code must not be exchanged twice, the second try fails with "Bad Request"
        if(!CommonLoginUtils.claimAuthCode(code)) {
            Log.w("MicrosoftLogin", "This authorization code was already used, ignoring it");
            return;
        }
        acquireAccountDetails(loginListener, ()->{
            Account account = Accounts.create(this::fillAccount);
            Tools.runOnUiThread(() -> loginListener.onLoginDone(account));
            return null;
        }, code, false);
    }

    @Override
    public void refreshAccount(@NonNull LoginListener loginListener, Account account) {
        acquireAccountDetails(loginListener, ()->{
            if(doesOwnGame) fillAccount(account);
            account.save();
            Tools.runOnUiThread(() -> loginListener.onLoginDone(account));
            return null;
        }, account.refreshToken, true);
    }

    private String acquireAccessToken(boolean isRefresh, String code) throws IOException, PresentableException {
        URL url = new URL(authTokenUrl);
        Log.i("MicrosoftLogin", "isRefresh=" + isRefresh);

        String formData = CommonLoginUtils.convertToFormData(
                "client_id", "00000000402b5328",
                isRefresh ? "refresh_token" : "code", code,
                "grant_type", isRefresh ? "refresh_token" : "authorization_code",
                "redirect_url", "https://login.live.com/oauth20_desktop.srf",
                "scope", "service::user.auth.xboxlive.com::MBI_SSL"
        );

        OAuthTokenResponse response = CommonLoginUtils.exchangeAuthCode(url, formData, isRefresh, AuthType.MICROSOFT);
        // If the server does not send a new refresh token, the old one stays valid. Dropping it
        // would make the next refresh fail with "Bad Request".
        if(response.refreshToken != null && !response.refreshToken.isEmpty()) {
            msRefreshToken = response.refreshToken;
        } else {
            msRefreshToken = isRefresh ? code : null;
        }
        return response.accessToken;
    }

    private String acquireXBLToken(String accessToken) throws IOException, JSONException, PresentableException {
        JSONObject data = new JSONObject();
        JSONObject properties = new JSONObject();
        properties.put("AuthMethod", "RPS");
        properties.put("SiteName", "user.auth.xboxlive.com");
        properties.put("RpsTicket", accessToken);
        data.put("Properties",properties);
        data.put("RelyingParty", "http://auth.xboxlive.com");
        data.put("TokenType", "JWT");

        CommonLoginUtils.HttpResult result = CommonLoginUtils.execute(
                new URL(xblAuthUrl), "POST", "application/json", data.toString(), null);
        if(!result.isSuccess()) throw CommonLoginUtils.failure(result);
        return new JSONObject(result.body).getString("Token");
    }

    /** @return [uhs, token]*/
    private @NonNull String[] acquireXsts(String xblToken) throws IOException, JSONException, PresentableException {
        JSONObject data = new JSONObject();
        JSONObject properties = new JSONObject();
        properties.put("SandboxId", "RETAIL");
        properties.put("UserTokens", new JSONArray(Collections.singleton(xblToken)));
        data.put("Properties", properties);
        data.put("RelyingParty", "rp://api.minecraftservices.com/");
        data.put("TokenType", "JWT");

        CommonLoginUtils.HttpResult result = CommonLoginUtils.execute(
                new URL(xstsAuthUrl), "POST", "application/json", data.toString(), null);

        if(result.isSuccess()) {
            JSONObject jo = new JSONObject(result.body);
            String uhs = jo.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs");
            String token = jo.getString("Token");
            return new String[]{uhs, token};
        }
        if(result.code == 401) {
            long xerr = -1;
            try {
                xerr = new JSONObject(result.body).optLong("XErr", -1);
            } catch (JSONException e) {
                // The body is not the usual error, it is reported as a plain HTTP error below
            }
            if(xerr != -1) {
                Integer locale_id = XSTS_ERRORS.get(xerr);
                if(locale_id != null) {
                    throw new PresentableException(new RuntimeException(result.body), locale_id);
                }
                throw new PresentableException(new RuntimeException(result.body), R.string.xerr_unknown, xerr);
            }
        }
        throw CommonLoginUtils.failure(result);
    }

    private String acquireToken(String xblUhs, String xblXsts) throws IOException, JSONException, PresentableException {
        JSONObject data = new JSONObject();
        data.put("identityToken", "XBL3.0 x=" + xblUhs + ";" + xblXsts);

        CommonLoginUtils.HttpResult result = CommonLoginUtils.execute(
                new URL(mcLoginUrl), "POST", "application/json", data.toString(), null);
        if(!result.isSuccess()) throw CommonLoginUtils.failure(result);

        mcToken = new JSONObject(result.body).getString("access_token");
        expiresAt = CommonLoginUtils.expiryTime(MC_TOKEN_LIFETIME_MS);
        return mcToken;
    }

    private void fetchOwnedItems(String mcAccessToken) throws IOException, PresentableException {
        CommonLoginUtils.HttpResult result = CommonLoginUtils.execute(
                new URL(mcStoreUrl), "GET", null, null, mcAccessToken);
        if(!result.isSuccess()) throw CommonLoginUtils.failure(result);
        // We don't need any data from this request, it just needs to happen in order for
        // the MS servers to work properly. The data from this is practically useless
        // as it does not indicate whether the user owns the game through Game Pass.
    }

    private void checkProfile(String mcAccessToken) throws IOException, JSONException, PresentableException {
        CommonLoginUtils.HttpResult result = CommonLoginUtils.execute(
                new URL(mcProfileUrl), "GET", null, null, mcAccessToken);

        if(result.isSuccess()) {
            JSONObject jsonObject = new JSONObject(result.body);
            String name = jsonObject.getString("name");
            String uuid = jsonObject.getString("id");
            String uuidDashes = uuid.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5"
            );
            doesOwnGame = true;
            mcName=name;
            mcUuid=uuidDashes;
        }else if(result.code == 404) {
            Log.i("MicrosoftLogin","It seems that this Microsoft Account does not own the game.");
            doesOwnGame = false;
            throw new PresentableException(new RuntimeException(result.message), R.string.mc_not_owned);
        }else {
            // Only a missing profile means that the game is not owned. A server that is busy or an
            // error of the connection must not be reported as that.
            throw CommonLoginUtils.failure(result);
        }
    }

    /** Wrapper to ease notifying the listener */
    private void notifyProgress(LoginListener listener, int step){
        Tools.runOnUiThread(() -> listener.onLoginProgress(step));
        ProgressLayout.setProgress(ProgressLayout.AUTHENTICATE, step*20);
    }
}
