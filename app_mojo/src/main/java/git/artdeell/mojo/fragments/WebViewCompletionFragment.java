package git.artdeell.mojo.fragments;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import git.artdeell.mojo.R;
import git.artdeell.mojo.utils.ThemeColors;

public abstract class WebViewCompletionFragment extends Fragment {
    private final String mTrackedUrl;
    private final String mAuthUrl;
    private WebView mWebview;
    // Technically the client is blank (or there is none) when the fragment is initialized
    private boolean mBlankClient = true;
    private boolean mIsCompleted = false;
    private boolean mRevealed = false;
    /** The page is shown after this time even if the WebView never reported that it has something to show */
    private static final long REVEAL_TIMEOUT_MS = 5000L;
    private static final long REVEAL_FADE_MS = 150L;
    private final Runnable mRevealRunnable = this::reveal;

    protected WebViewCompletionFragment(String mTrackedUrl, String mAuthUrl) {
        this.mTrackedUrl = mTrackedUrl;
        this.mAuthUrl = mAuthUrl;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mWebview = (WebView) inflater.inflate(R.layout.fragment_microsoft_login, container, false);
        // A WebView with nothing loaded yet is white. It takes the color of the launcher instead,
        // and stays invisible until the first page has something to show, then it fades in.
        mWebview.setBackgroundColor(ThemeColors.surface(inflater.getContext()));
        mWebview.setAlpha(0f);
        mRevealed = false;
        mWebview.postDelayed(mRevealRunnable, REVEAL_TIMEOUT_MS);
        mWebview.requestFocus();
        mWebview.setOnFocusChangeListener((v,f)->{
            if(!f) mWebview.requestFocus();
        });
        setWebViewSettings();
        if(savedInstanceState == null) startNewSession();
        else restoreWebViewState(savedInstanceState);
        return mWebview;
    }

    // WebView.restoreState() does not restore the WebSettings or the client, so set them there
    // separately. Note that general state should not be altered here (aka no loading pages, no manipulating back/front lists),
    // to avoid "undesirable side-effects"
    @SuppressLint("SetJavaScriptEnabled")
    private void setWebViewSettings() {
        WebSettings settings = mWebview.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        mWebview.setWebViewClient(new WebViewTrackClient());
        mBlankClient = false;
    }

    private void startNewSession() {
        CookieManager.getInstance().removeAllCookies((b)->{
            // The disk cache only holds public page files, clearing it on the main thread made the
            // login page wait for it. The cookies and the stored data are what keeps a session.
            mWebview.clearHistory();
            mWebview.clearFormData();
            WebStorage.getInstance().deleteAllData();
            mWebview.loadUrl(mAuthUrl);
        });
    }

    /** Fades the page in. Safe to call more than once. */
    private void reveal() {
        if(mRevealed || mWebview == null) return;
        mRevealed = true;
        mWebview.removeCallbacks(mRevealRunnable);
        mWebview.animate().alpha(1f).setDuration(REVEAL_FADE_MS).start();
    }

    @Override
    public void onDestroyView() {
        if(mWebview != null) {
            mWebview.removeCallbacks(mRevealRunnable);
            mWebview.animate().cancel();
        }
        super.onDestroyView();
    }

    private void restoreWebViewState(Bundle savedInstanceState) {
        Log.i("MSAuthFragment","Restoring state...");
        if(mWebview.restoreState(savedInstanceState) == null) {
            Log.w("MSAuthFragment", "Failed to restore state, starting afresh");
            // if, for some reason, we failed to restore our session,
            // just start afresh
            startNewSession();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // If we have switched to a blank client and haven't fully gone though the lifecycle callbacks to restore it,
        // restore it here.
        if(mBlankClient) mWebview.setWebViewClient(new WebViewTrackClient());
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        // Since the value cannot be null, just create a "blank" client. This is done to not let Android
        // kill us if something happens after the state gets saved, when we can't do fragment transitions
        mWebview.setWebViewClient(new WebViewClient());
        // For some dumb reason state is saved even when Android won't actually destroy the activity.
        // Let the fragment know that the client is blank so that we can restore it in onStart()
        // (it was the earliest lifecycle call actually invoked in this case)
        mBlankClient = true;
        super.onSaveInstanceState(outState);
        mWebview.saveState(outState);
    }

    /* Expose webview actions to others */
    public boolean canGoBack(){ return mWebview.canGoBack();}
    public void goBack(){ mWebview.goBack();}

    /** Client to track when to sent the data to the launcher */
    class WebViewTrackClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if(url.startsWith(mTrackedUrl)) {
                internalSignalCompletion(url);
                return true;
            }

            return super.shouldOverrideUrlLoading(view, url);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {}

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            if(!url.startsWith(mTrackedUrl)) reveal();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if(url.startsWith(mTrackedUrl)) {
                internalSignalCompletion(url);
            } else {
                reveal();
            }
        }

        @Override
        public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                    android.webkit.WebResourceError error) {
            super.onReceivedError(view, request, error);
            // The error page of the WebView has to be seen, there is nothing else to wait for
            if(request != null && request.isForMainFrame()) reveal();
        }
    }

    private void internalSignalCompletion(String fullUrl) {
        if(mIsCompleted) return;
        mIsCompleted = true;
        signalCompletion(fullUrl);
    }

    protected abstract void signalCompletion(String fullUrl);
}
