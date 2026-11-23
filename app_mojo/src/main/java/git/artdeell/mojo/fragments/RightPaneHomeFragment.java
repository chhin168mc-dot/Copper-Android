package git.artdeell.mojo.fragments;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.LauncherWallpaperView;
import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.AuthType;
import git.artdeell.mojo.authenticator.accounts.Account;
import git.artdeell.mojo.authenticator.accounts.Accounts;
import git.artdeell.mojo.progresskeeper.ProgressKeeper;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.skins.DefaultSkins;
import git.artdeell.mojo.skins.PlayerPreviewView;
import git.artdeell.mojo.skins.PlayerSkinLoader;
import git.artdeell.mojo.skins.SkinEditorFragment;

/**
 * Home screen of the main menu: the player of the selected account in the middle of the space.
 * Steve shows until the skin of a Microsoft or Ely.by account has loaded, and for accounts without a skin.
 */
public class RightPaneHomeFragment extends Fragment {
    public static final String TAG = "RightPaneHomeFragment";

    private PlayerPreviewView mPlayer;
    private TextView mName;
    private String mShownKey;
    private int mRequestId;

    /* Reloads the skin when another account is selected, the preference holds the selected account file */
    private final SharedPreferences.OnSharedPreferenceChangeListener mPreferenceListener =
            (preferences, key) -> reloadSkin();

    public RightPaneHomeFragment() {
        super(R.layout.fragment_right_pane_home);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // The wallpaper behind the player, only shown when it isn't fullscreen
        ((LauncherWallpaperView) view.findViewById(R.id.pane_wallpaper)).setPane(true);
        mPlayer = view.findViewById(R.id.home_player);
        mName = view.findViewById(R.id.home_player_name);
        mName.setText("Steve");
        DefaultSkins.init(requireContext());
        mPlayer.setSkin(DefaultSkins.steve(), false);
        // A tap on the player opens the skin editor on the capes tab
        mPlayer.setOnClickListener(v -> openCapePicker());
    }

    @Override
    public void onResume() {
        super.onResume();
        LauncherPreferences.DEFAULT_PREF.registerOnSharedPreferenceChangeListener(mPreferenceListener);
        reloadSkin();
    }

    @Override
    public void onPause() {
        LauncherPreferences.DEFAULT_PREF.unregisterOnSharedPreferenceChangeListener(mPreferenceListener);
        super.onPause();
    }

    /** Opens the skin editor of the selected account on the capes tab */
    private void openCapePicker() {
        Account account = Accounts.getCurrent();
        if (account == null || account.getSaveLocation() == null) {
            Toast.makeText(requireContext(), R.string.skin_editor_no_account, Toast.LENGTH_SHORT).show();
            return;
        }
        if (account.authType == AuthType.ELY_BY) {
            Tools.openURL(requireActivity(), "https://ely.by/skins");
            return;
        }
        if (ProgressKeeper.hasProgressKey(ProgressLayout.AUTHENTICATE)) {
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_SHORT).show();
            return;
        }
        Bundle args = new Bundle();
        args.putString(SkinEditorFragment.ARG_ACCOUNT_FILE, account.getSaveLocation().getName());
        args.putBoolean(SkinEditorFragment.ARG_OPEN_CAPES, true);
        Tools.swapFragmentFullScreen(requireActivity(), SkinEditorFragment.class, SkinEditorFragment.TAG, args);
    }

    private void reloadSkin() {
        final int request = ++mRequestId;
        PlayerSkinLoader.load(result -> {
            // A newer request is on its way, or the screen is gone
            if (request != mRequestId || mPlayer == null || !isAdded()) return;
            mName.setText(result.name);
            if (result.key.equals(mShownKey)) return;
            mShownKey = result.key;
            mPlayer.setSkin(result.skin, result.slim);
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mRequestId++;
        mPlayer = null;
        mName = null;
        mShownKey = null;
    }
}
