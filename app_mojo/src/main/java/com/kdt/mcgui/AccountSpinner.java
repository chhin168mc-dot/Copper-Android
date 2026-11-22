package com.kdt.mcgui;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import git.artdeell.mojo.utils.ThemeColors;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.AuthType;
import git.artdeell.mojo.authenticator.BackgroundLogin;
import git.artdeell.mojo.authenticator.SessionExpiredException;
import git.artdeell.mojo.authenticator.accounts.Accounts;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.listener.LoginListener;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.extra.ExtraListener;
import git.artdeell.mojo.fragments.ElyByLoginFragment;
import git.artdeell.mojo.fragments.MicrosoftLoginFragment;
import git.artdeell.mojo.progresskeeper.ProgressKeeper;
import git.artdeell.mojo.skins.SkinEditorFragment;

import java.io.IOException;
import java.util.HashMap;
import java.util.Objects;

import fr.spse.extended_view.ExtendedTextView;
import git.artdeell.mojo.R;

public class AccountSpinner extends AppCompatSpinner implements LoginListener, AdapterView.OnItemSelectedListener, ValueAnimator.AnimatorUpdateListener {
    private Adapter mAdapter;
    /* Login progress bar stuff */
    private int mMaxSteps = 5;
    private final ValueAnimator mLoginStepAnimator = ValueAnimator.ofFloat(mMaxSteps);
    private final Paint mLoginBarPaint = new Paint();
    private float mLoginStep;
    /* Tap-only touch handling: swipes must not open the account popup */
    private float mTouchDownX, mTouchDownY;
    private boolean mTouchMoved;

    class LoginExtraListener implements ExtraListener<String> {
        private final AuthType mAuthType;

        LoginExtraListener(AuthType mAuthType) {
            this.mAuthType = mAuthType;
        }

        @Override
        public boolean onValueSet(String key, @NonNull String value) {
            mLoginBarPaint.setColor(ThemeColors.primary(getContext()));
            BackgroundLogin backgroundLogin = mAuthType.createAuth();
            backgroundLogin.createAccount(AccountSpinner.this, value);
            return false;
        }
    }

    /* Login listeners */
    private final ExtraListener<String> mMicrosoftLoginListener = new LoginExtraListener(AuthType.MICROSOFT);
    private final ExtraListener<String> mElyByLoginListener = new LoginExtraListener(AuthType.ELY_BY);
    private final ExtraListener<String[]> mMojangLoginListener = (key, value) -> {
        try {
            Account account = Accounts.create(acc-> acc.username = value[0]);
            onLoginDone(account);
        }catch (IOException e) {
            onLoginError(e);
        }
        return false;
    };

    /* Account main menu refresh listener */
    private final ExtraListener<Boolean> mRefreshAccountsListener = (k,v)->{
        reload();
        return false;
    };

    public AccountSpinner(@NonNull Context context, int mode) {
        super(context, mode);
        init();
    }

    public AccountSpinner(@NonNull Context context) {
        super(context);
        init();
    }

    public AccountSpinner(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AccountSpinner(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public AccountSpinner(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int mode) {
        super(context, attrs, defStyleAttr, mode);
        init();
    }

    public AccountSpinner(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int mode, Resources.Theme popupTheme) {
        super(context, attrs, defStyleAttr, mode, popupTheme);
        init();
    }



    private void init() {
        mAdapter = new Adapter(getContext());
        setAdapter(mAdapter);
        setOnItemSelectedListener(this);
        reload();

        setBackgroundColor(ThemeColors.surfaceContainer(getContext()));
        mLoginBarPaint.setColor(ThemeColors.primary(getContext()));
        mLoginBarPaint.setStrokeWidth(getResources().getDimensionPixelOffset(R.dimen._2sdp));
        mLoginStepAnimator.addUpdateListener(this);
        mLoginStep = mMaxSteps;

        ExtraCore.addExtraListener(ExtraConstants.MOJANG_LOGIN_TODO, mMojangLoginListener);
        ExtraCore.addExtraListener(ExtraConstants.MICROSOFT_LOGIN_TODO, mMicrosoftLoginListener);
        ExtraCore.addExtraListener(ExtraConstants.ELYBY_LOGIN_TODO, mElyByLoginListener);
        ExtraCore.addExtraListener(ExtraConstants.REFRESH_ACCOUNT_SPINNER, mRefreshAccountsListener);
    }

    private void reload() {
        MojoApplication.sExecutorService.execute(()->{
            try {
                Accounts accounts = Accounts.load();
                Tools.runOnUiThread(()->refresh(accounts));
            } catch (IOException e) {
                 throw new RuntimeException(e);
            }
        });
    }

    private void refresh(Accounts accounts) {
        mAdapter.setNotifyOnChange(false);
        mAdapter.clear();
        mAdapter.add(null);
        mAdapter.setNotifyOnChange(true);
        mAdapter.addAll(accounts.accounts);

        if(accounts.accounts.isEmpty()) {
            setSelection(0);
        } else {
            setSelection(accounts.selectionIndex + 1);
            refreshAccount(Objects.requireNonNull((Account) getSelectedItem()));
        }
    }

    private void refreshAccount(Account account) {
        // Wait until all tasks (including other possible login tasks) are done before
        // attempting to refresh the account.
        ProgressKeeper.waitUntilDone(()->{
            // Reload the account data before attempting to refresh (what if it was already refreshed in the background?)
            Account refreshAccount = account.reload();
            if(refreshAccount == null) return;
            AuthType authType = refreshAccount.authType;
            if(authType.requiresLogin() && System.currentTimeMillis() > refreshAccount.expiresAt) {
                authType.createAuth().refreshAccount(this, refreshAccount);
            }
        });
    }

    private void dismissPopup() {
        onDetachedFromWindow();
        onAttachedToWindow();
    }

    /** Opens the skin editor, or the Ely.by skin site in the browser for Ely.by accounts */
    private void openSkinEditor(@NonNull Account account) {
        FragmentActivity activity = findActivity(getContext());
        if(activity == null || account.getSaveLocation() == null) return;
        FragmentManager manager = activity.getSupportFragmentManager();
        if(manager.isStateSaved()) return;
        if(ProgressKeeper.hasProgressKey(ProgressLayout.AUTHENTICATE)) {
            // The session is being refreshed, the editor would fight it for the tokens
            Toast.makeText(getContext(), R.string.tasks_ongoing, Toast.LENGTH_SHORT).show();
            return;
        }
        dismissPopup();
        if(account.authType == AuthType.ELY_BY) {
            // Ely.by has no skin API, so its own site is opened in the browser
            Tools.openURL(activity, "https://ely.by/skins");
            return;
        }
        Bundle args = new Bundle();
        args.putString(SkinEditorFragment.ARG_ACCOUNT_FILE, account.getSaveLocation().getName());
        Tools.swapFragmentFullScreen(activity, SkinEditorFragment.class, SkinEditorFragment.TAG, args);
    }

    @Nullable
    private static FragmentActivity findActivity(Context context) {
        while(context instanceof ContextWrapper) {
            if(context instanceof FragmentActivity) return (FragmentActivity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private void createAccount() {
        setSelection(0);
        dismissPopup();
        ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
    }

    /**
     * Only open the account popup on a real tap. The default Spinner opens it as soon as the
     * finger has been down for a moment, which made swiping down to open the notification
     * panel pop up the account list instead.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if(!isEnabled()) return super.onTouchEvent(event);
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTouchDownX = event.getX();
                mTouchDownY = event.getY();
                mTouchMoved = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if(!mTouchMoved) {
                    int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if(Math.abs(event.getX() - mTouchDownX) > slop
                            || Math.abs(event.getY() - mTouchDownY) > slop) {
                        mTouchMoved = true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if(!mTouchMoved) performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
                mTouchMoved = true;
                break;
        }
        return true;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float bottom = getHeight() - mLoginBarPaint.getStrokeWidth()/2;
        float lineFillPercent = (mLoginStep / mMaxSteps);
        canvas.drawLine(0, bottom, lineFillPercent * getWidth(), bottom, mLoginBarPaint);
    }

    @Override
    public void onLoginDone(Account account) {
        mLoginStep = mMaxSteps;
        // Cancel any running progress animation so the bar fills and the name appears at once
        if (mLoginStepAnimator.isRunning()) {
            mLoginStepAnimator.cancel();
        }
        invalidate();

        Toast.makeText(getContext(), R.string.main_login_done, Toast.LENGTH_SHORT).show();
        Accounts.setCurrent(account);
        // Put the name on the screen right now, reading every account file again can wait
        showAccountNow(account);
        // Force the view to redraw the selected name immediately (avoids a long blank wait)
        post(() -> {
            requestLayout();
            invalidate();
        });
        refreshSkinFace(account);
    }

    /** Adds the account to the list (or swaps its entry) and selects it without a trip to the disk */
    private void showAccountNow(@NonNull Account account) {
        int index = mAdapter.indexOfSaved(account);
        mAdapter.setNotifyOnChange(false);
        if(index < 0) {
            mAdapter.add(account);
            index = mAdapter.getCount() - 1;
        } else {
            Account old = mAdapter.getItem(index);
            mAdapter.remove(old);
            mAdapter.insert(account, index);
        }
        mAdapter.notifyDataSetChanged();
        setSelection(index, false); // false = no animation, name appears instantly
        // Some devices keep the old spinner text until the next layout pass
        if (getSelectedView() != null) {
            getSelectedView().invalidate();
        }
    }

    /** Downloads the head of the account in the background and shows it when it is ready */
    private void refreshSkinFace(@NonNull Account account) {
        MojoApplication.sExecutorService.execute(() -> {
            account.updateSkinFace();
            Tools.runOnUiThread(mAdapter::invalidateHeads);
        });
    }

    /* The popup list is its own window. When it takes the focus the system bars slide back in, so it hides them again */
    private ViewGroup mPopupList;

    private void keepPopupImmersive(@Nullable ViewGroup list) {
        // The parent is null while the popup measures its width, and it is the spinner itself in some calls
        if(list == null || list == this || mPopupList == list) return;
        mPopupList = list;
        list.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
                hideSystemBarsOf(v);
                v.post(() -> hideSystemBarsOf(v));
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {}
        });
        if(list.isAttachedToWindow()) hideSystemBarsOf(list);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBarsOf(@NonNull View view) {
        if(!view.isAttachedToWindow()) return;
        FragmentActivity activity = findActivity(getContext());
        // In a window the bars belong to the window, they are not hidden there
        if(activity == null || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode())) return;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = view.getWindowInsetsController();
            if(controller == null) return;
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

    /** Shows a dialog without the system bars coming back: it takes the focus only after they are hidden */
    private void showDialogImmersive(@NonNull MaterialAlertDialogBuilder builder) {
        AlertDialog dialog = builder.create();
        Window window = dialog.getWindow();
        if(window != null) window.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        dialog.show();
        if(window != null) {
            hideSystemBarsOf(window.getDecorView());
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }


    @Override
    public void onLoginError(Throwable errorMessage) {
        mLoginBarPaint.setColor(ThemeColors.error(getContext()));
        invalidate();

        Context context = getContext();
        if(!(context instanceof Activity)) return;
        if(context instanceof LifecycleOwner) {
            LifecycleOwner lifecycleOwner = (LifecycleOwner) context;
            Lifecycle.State state = lifecycleOwner.getLifecycle().getCurrentState();
            if(state != Lifecycle.State.RESUMED) return;
        }

        if(errorMessage instanceof SessionExpiredException) {
            showSessionExpired(context, ((SessionExpiredException) errorMessage).getAuthType());
            return;
        }
        Tools.showErrorRemote(errorMessage);
    }

    /**
     * The saved login was rejected by the server. Instead of an error that the player cannot do
     * anything about, offer to sign in again right away.
     */
    private void showSessionExpired(Context context, AuthType authType) {
        showDialogImmersive(new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.login_session_expired_title)
                .setMessage(R.string.login_session_expired)
                .setPositiveButton(R.string.login_session_expired_action, (dialog, which) -> startRelogin(authType))
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void startRelogin(AuthType authType) {
        FragmentActivity activity = findActivity(getContext());
        if(activity == null || activity.getSupportFragmentManager().isStateSaved()) return;
        if(!ProgressLayout.ensureNoOngoingTasks(activity, ProgressLayout.AUTHENTICATE)) return;
        // Open the login page of this kind of account directly, there is no need to ask for the method again
        if(authType == AuthType.MICROSOFT) {
            Tools.swapFragment(activity, MicrosoftLoginFragment.class, MicrosoftLoginFragment.TAG, null);
        } else if(authType == AuthType.ELY_BY) {
            Tools.swapFragment(activity, ElyByLoginFragment.class, ElyByLoginFragment.TAG, null);
        } else {
            createAccount();
        }
    }

    @Override
    public void onLoginProgress(int step) {
        mLoginStepAnimator.cancel();
        mLoginStepAnimator.setFloatValues(mLoginStep, step);
        mLoginStepAnimator.start();
    }

    @Override
    public void setMaxLoginProgress(int max) {
        mMaxSteps = max;
    }

    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
        Account account = mAdapter.getItem(i);
        if(account == null) {
            if(i == 0) {
                createAccount();
            }else {
                Tools.showError(adapterView.getContext(), new NullPointerException());
            }
            return;
        }
        Accounts.setCurrent(account);
        refreshAccount(account);
        dismissPopup();
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {}

    @Override
    public void onAnimationUpdate(@NonNull ValueAnimator valueAnimator) {
        mLoginStep = (float)valueAnimator.getAnimatedValue();
        invalidate();
    }

    private class Adapter extends ArrayAdapter<Account> {
        private final HashMap<Integer, BitmapDrawable> mSkinHeadCache = new HashMap<>();
        private final LayoutInflater mInflater;


        public Adapter(@NonNull Context context) {
            super(context, R.layout.item_account);
            mInflater = LayoutInflater.from(context);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if(convertView == null) {
                convertView = mInflater.inflate(R.layout.item_account, parent, false);
            }
            populateView(convertView, position, false);
            return convertView;
        }

        @Override
        public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            keepPopupImmersive(parent);
            if(convertView == null) {
                convertView = mInflater.inflate(R.layout.item_account, parent, false);
            }
            populateView(convertView, position, true);
            return convertView;
        }

        /** @return the position of the entry that is stored in the same file as the account, or -1 */
        int indexOfSaved(@NonNull Account account) {
            for(int i = 1; i < getCount(); i++) {
                Account item = getItem(i);
                if(item != null && item.getSaveLocation() != null
                        && item.getSaveLocation().equals(account.getSaveLocation())) return i;
            }
            return -1;
        }

        /** Forgets the heads that were drawn, they are read from the disk again */
        void invalidateHeads() {
            mSkinHeadCache.clear();
            notifyDataSetChanged();
        }

        private void populateView(View view, int position, boolean isDropDown) {
            Resources resources = getResources();
            Resources.Theme theme = getContext().getTheme();

            ExtendedTextView textview = view.findViewById(R.id.account_item);
            ImageView deleteButton = view.findViewById(R.id.delete_account_button);
            ImageView editButton = view.findViewById(R.id.edit_account_button);

            if(position == 0) {
                // "Add account" button
                Drawable plusDrawable = ResourcesCompat.getDrawable(resources, R.drawable.ic_add, theme);
                textview.setCompoundDrawables(plusDrawable, null, null, null);
                textview.setText(R.string.main_add_account);
                deleteButton.setVisibility(View.GONE);
                editButton.setVisibility(View.GONE);
                // Only activate the listener behaviour when in drop-down mode
                // or when there's no accounts
                if(isDropDown || getCount() == 1) view.setOnClickListener(v-> createAccount());
                return;
            }

            if(isDropDown) {
                deleteButton.setVisibility(View.VISIBLE);
                deleteButton.setOnClickListener((v)->showDeleteDialog(v.getContext(), position));
            }else {
                deleteButton.setVisibility(View.GONE);
            }


            Account account = Objects.requireNonNull(getItem(position));

            // Microsoft and local accounts use the built-in editor, Ely.by accounts the Ely.by site
            if(account.authType == AuthType.MICROSOFT || account.authType == AuthType.LOCAL
                    || account.authType == AuthType.ELY_BY) {
                editButton.setVisibility(View.VISIBLE);
                editButton.setOnClickListener(v -> openSkinEditor(account));
            }else {
                editButton.setVisibility(View.GONE);
                editButton.setOnClickListener(null);
            }

            int authTypeResource = account.authType.iconResource;

            Drawable authType = null;
            if(authTypeResource != 0) {
                authType = ResourcesCompat.getDrawable(resources, authTypeResource, theme);
            }

            int headCacheHash = System.identityHashCode(account);
            BitmapDrawable accountHead = mSkinHeadCache.get(headCacheHash);
            if (accountHead == null){
                Bitmap accountSkinFace = account.getSkinFace();
                accountHead = new BitmapDrawable(resources, accountSkinFace);
                mSkinHeadCache.put(headCacheHash, accountHead);
            }

            textview.setText(account.username);
            textview.setCompoundDrawablesRelative(accountHead, null, authType, null);
        }

        private void showDeleteDialog(Context context, int position) {
            showDialogImmersive(new MaterialAlertDialogBuilder(context)
                    .setMessage(R.string.warning_remove_account)
                    .setPositiveButton(android.R.string.cancel, null)
                    .setNeutralButton(R.string.global_delete, (dialog, which) -> {
                        Account account = getItem(position);
                        Accounts.delete(account);
                        reload();
                    }));
        }
    }
}
