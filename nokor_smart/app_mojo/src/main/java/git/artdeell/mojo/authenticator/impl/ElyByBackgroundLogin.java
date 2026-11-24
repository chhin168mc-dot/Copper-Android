package git.artdeell.mojo.authenticator.impl;

import static git.artdeell.mojo.MojoApplication.sExecutorService;

import android.util.Log;

import androidx.annotation.NonNull;

import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.AuthType;
import git.artdeell.mojo.authenticator.BackgroundLogin;
import git.artdeell.mojo.authenticator.accounts.Accounts;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.listener.LoginListener;
import git.artdeell.mojo.authenticator.model.OAuthTokenResponse;
import git.artdeell.mojo.utils.PresentableException;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.Callable;

public class ElyByBackgroundLogin implements BackgroundLogin {
    public static final BackgroundLogin.Creator CREATOR = ElyByBackgroundLogin::new;

    private static final String authTokenUrl = "https://account.ely.by/api/oauth2/v1/token";
    private static final String accountInfoUrl = "https://account.ely.by/api/account/v1/info";

    private OAuthTokenResponse mOAuthData;
    private ElyAccountInfo mAccountInfo;
    private long mExpiresAt;

    private ElyByBackgroundLogin() {}

    private void acquireAccountDetails(
            @NonNull LoginListener loginListener, Callable<Void> continuation,
            String code, boolean isRefresh
    ) {
        ProgressLayout.setProgress(ProgressLayout.AUTHENTICATE, 0);
        sExecutorService.execute(() -> {
            loginListener.setMaxLoginProgress(2);
            try {
                notifyProgress(loginListener, 1);
                acquireTokens(isRefresh, code);
                notifyProgress(loginListener, 2);
                mAccountInfo = acquireAccountData(mOAuthData.accessToken);
                continuation.call();
            }catch (Exception e){
                Log.e("ElyByAuth", "Exception thrown during authentication", e);
                Tools.runOnUiThread(()->loginListener.onLoginError(e));
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.AUTHENTICATE);
            }
        });
    }

    private void fillAccount(Account acc) {
        acc.expiresAt = mExpiresAt;
        acc.authType = AuthType.ELY_BY;
        acc.accessToken = mOAuthData.accessToken;
        acc.refreshToken = mOAuthData.refreshToken;
        acc.username = mAccountInfo.username;
        acc.profileId = mAccountInfo.uuid;
        acc.xuid = null;
        // The head is downloaded by the account list after the name is on the screen
    }

    @Override
    public void createAccount(@NonNull LoginListener loginListener, String code) {
        // The same code must not be exchanged twice, the second try fails with "Bad Request"
        if(!CommonLoginUtils.claimAuthCode(code)) {
            Log.w("ElyByAuth", "This authorization code was already used, ignoring it");
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
            fillAccount(account);
            account.save();
            Tools.runOnUiThread(() -> loginListener.onLoginDone(account));
            return null;
        }, account.refreshToken, true);
    }

    private void acquireTokens(boolean isRefresh, String code) throws IOException, PresentableException {
        URL url = new URL(authTokenUrl);
        Log.i("ElyByAuth", "isRefresh=" + isRefresh);

        String formData = CommonLoginUtils.convertToFormData(
                "client_id", "mojolauncher2",
                "client_secret", "o14Zb2Zzj0_k6o4kN0t1mIEhoQxeayn8hYi5VSX2q3NXrdQm5T2Q6wqsCfpv1vhu",
                "redirect_uri", "internalredirect://complete",
                isRefresh ? "refresh_token" : "code", code,
                "grant_type", isRefresh ? "refresh_token" : "authorization_code"
        );
        mOAuthData = CommonLoginUtils.exchangeAuthCode(url, formData, isRefresh, AuthType.ELY_BY);
        // A refresh answer may not carry a new refresh token. The old one stays valid then, and it
        // has to be kept: with the token lost, every next refresh failed with "Bad Request".
        if(isRefresh && (mOAuthData.refreshToken == null || mOAuthData.refreshToken.isEmpty())) {
            mOAuthData.refreshToken = code;
        }
        mExpiresAt = CommonLoginUtils.expiryTime(mOAuthData.expiresIn * 1000);
    }

    private ElyAccountInfo acquireAccountData(String accessToken) throws IOException, PresentableException {
        CommonLoginUtils.HttpResult result = CommonLoginUtils.execute(
                new URL(accountInfoUrl), "GET", null, null, accessToken);
        if(!result.isSuccess()) throw CommonLoginUtils.failure(result);

        ElyAccountInfo info = Tools.GLOBAL_GSON.fromJson(result.body, ElyAccountInfo.class);
        if(info == null || info.uuid == null || info.username == null) {
            throw new PresentableException(R.string.global_error, R.string.microsoft_login_generic, "empty account info");
        }
        return info;
    }

    private void notifyProgress(LoginListener listener, int step) {
        Tools.runOnUiThread(() -> listener.onLoginProgress(step));
        ProgressLayout.setProgress(ProgressLayout.AUTHENTICATE, step*50);
    }

    private static class ElyAccountInfo {
        public String uuid;
        public String username;
    }
}
