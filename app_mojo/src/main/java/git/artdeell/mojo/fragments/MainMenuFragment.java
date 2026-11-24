package git.artdeell.mojo.fragments;

import git.artdeell.mojo.utils.AnimationManager;
import static git.artdeell.mojo.Tools.openPath;
import static git.artdeell.mojo.Tools.shareLog;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.constraintlayout.widget.Guideline;
import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.kdt.mcgui.ProgressLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kdt.mcgui.mcVersionSpinner;

import git.artdeell.mojo.CustomControlsActivity;
import git.artdeell.mojo.R;

import git.artdeell.mojo.LauncherActivity;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.home.HomeTileBoard;
import git.artdeell.mojo.contracts.OpenDocumentWithExtension;
import git.artdeell.mojo.extra.ExtraConstants;
import git.artdeell.mojo.extra.ExtraCore;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.modding.modpacks.models.ContentType;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.LauncherBackground;
import git.artdeell.mojo.utils.ThemeColors;
import git.artdeell.mojo.utils.WorldUtils;

import java.util.List;
import java.io.File;

/**
 * The main menu, landscape only. The home screen shows the player of the selected account in the
 * middle of the space, the Quick Actions and Instance Tools are in a menu that slides in from the left.
 * Every screen opened with {@link Tools#swapFragment} shows up in the pane above the home screen, which has its own back stack.
 */
public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private mcVersionSpinner mVersionSpinner;
    /* The two-pane views, null in portrait */
    private FrameLayout mRightPane;
    private View mBottomBar;
    /* The sidebar and the pane that replaces it while the content picker is open, null in portrait */
    private View mLeftSidebar;
    private FrameLayout mLeftPaneContainer;
    /* The tiles of the sidebar and of the home screen, null in portrait */
    private HomeTilesController mHomeTiles;
    /* The buttons that replace the version bar while the tiles are edited */
    private View mEditControls;
    private View mAddRightButton;
    private View[] mVersionBarViews = new View[0];
    private boolean mEditing;
    /* The menu that slides in from the left, and what opens it */
    private Guideline mPaneGuideline;
    private View mMenuPanel;
    private View mMenuScrim;
    private View mMenuButton;
    private View mMenuEdge;
    private boolean mMenuOpen;
    private OnBackPressedCallback mMenuBackCallback;
    private OnBackPressedCallback mEditBackCallback;
    /* A custom background is set: it shows through the main menu, which then is partly see-through */
    private boolean mHasWallpaper;
    /* The other screens show the wallpaper too */
    private boolean mShowOthers = true;
    /* Intercepts back when the right pane shows something above the home screen */
    private OnBackPressedCallback mRightPaneBackCallback;

    private final ActivityResultLauncher<String[]> mLayoutImportLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (mHomeTiles != null) mHomeTiles.importLayout(uri);
            });

    private final ActivityResultLauncher<Object> mModInstallerLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("jar"), (data)->{
                if(data != null) Tools.launchModInstaller(requireContext(), data);
            });

    private final FragmentManager.OnBackStackChangedListener mBackStackListener = () -> {
        mRightPaneBackCallback.setEnabled(isRightPaneActive());
        updateBottomBar();
        updatePaneBackground();
        updateLeftPaneVisibility();
        // Back at the home screen: instances may have been created, renamed or deleted
        if (mVersionSpinner != null && getChildFragmentManager().getBackStackEntryCount() == 0) {
            mVersionSpinner.reloadProfiles();
        }
    };

    public MainMenuFragment(){
        super(R.layout.fragment_launcher);
    }

    // ─── Two-pane helpers ────────────────────────────────────────────────────

    /** @return whether the two-pane landscape layout is active */
    public boolean isTwoPane() {
        return mRightPane != null;
    }

    /** @return whether the right pane shows a screen above its home screen */
    public boolean isRightPaneActive() {
        return isTwoPane() && getChildFragmentManager().getBackStackEntryCount() > 0;
    }

    /**
     * Opens a screen in the right pane. Does nothing in portrait.
     * @return whether the screen was opened, false means the caller has to show it another way
     */
    public boolean openInPane(@NonNull Class<? extends Fragment> fragmentClass,
                              @Nullable String tag, @Nullable Bundle args) {
        if (!isTwoPane()) return false;
        String entryName = tag != null ? tag : fragmentClass.getName();
        FragmentManager manager = getChildFragmentManager();
        int count = manager.getBackStackEntryCount();
        // Already showing it, ignore the double tap
        if (count > 0 && entryName.equals(manager.getBackStackEntryAt(count - 1).getName())) return true;
        manager.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.right_pane_container, fragmentClass, args, tag)
                .addToBackStack(entryName)
                .commit();
        return true;
    }

    /** Pops one screen off the right pane */
    public void popRightPane() {
        if (isRightPaneActive()) getChildFragmentManager().popBackStack();
    }

    /** Pops every screen off the right pane, so its home screen shows again */
    public void clearRightPane() {
        FragmentManager manager = getChildFragmentManager();
        if (manager.getBackStackEntryCount() == 0) return;
        manager.popBackStack(manager.getBackStackEntryAt(0).getId(), FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    /** The bottom bar (instance and play) only belongs to the home screen, the other screens get the full height */
    private void updateBottomBar() {
        if (mBottomBar == null) return;
        boolean atHome = getChildFragmentManager().getBackStackEntryCount() == 0;
        // While the tiles are edited the bar holds the edit buttons, wherever the right pane is
        mBottomBar.setVisibility(atHome || mEditing ? View.VISIBLE : View.GONE);
        // The menu belongs to the home screen as well
        if (mMenuButton != null) mMenuButton.setVisibility(atHome ? View.VISIBLE : View.GONE);
        if (mMenuEdge != null) mMenuEdge.setVisibility(atHome ? View.VISIBLE : View.GONE);
        if (!atHome) closeQuickMenu();
    }

    /** Slides the Quick Actions / Instance Tools menu in from the left */
    public void openQuickMenu() {
        if (mMenuPanel == null || mMenuScrim == null || mMenuOpen) return;
        mMenuOpen = true;
        mMenuBackCallback.setEnabled(true);
        mMenuScrim.animate().cancel();
        mMenuScrim.setVisibility(View.VISIBLE);
        mMenuScrim.setAlpha(0f);
        mMenuScrim.animate().alpha(1f).setDuration(200L).start();
        mMenuPanel.animate().cancel();
        mMenuPanel.setVisibility(View.VISIBLE);
        mMenuPanel.setTranslationX(menuHiddenX());
        mMenuPanel.animate().translationX(0f).setDuration(260L)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    public void closeQuickMenu() {
        if (mMenuPanel == null || mMenuScrim == null || !mMenuOpen) return;
        mMenuOpen = false;
        mMenuBackCallback.setEnabled(false);
        final View panel = mMenuPanel;
        final View scrim = mMenuScrim;
        scrim.animate().cancel();
        scrim.animate().alpha(0f).setDuration(180L).withEndAction(() -> {
            if (!mMenuOpen) scrim.setVisibility(View.GONE);
        }).start();
        panel.animate().cancel();
        panel.animate().translationX(menuHiddenX()).setDuration(220L).withEndAction(() -> {
            if (!mMenuOpen) panel.setVisibility(View.INVISIBLE);
        }).start();
    }

    private float menuHiddenX() {
        int width = mMenuPanel.getWidth();
        if (width == 0) width = Math.round(300f * getResources().getDisplayMetrics().density);
        return -width;
    }

    /** The edit buttons take the place of the version bar and the play button while the tiles are edited */
    void setHomeEditing(boolean editing, boolean rightAvailable) {
        mEditing = editing;
        if (mEditControls != null) mEditControls.setVisibility(editing ? View.VISIBLE : View.GONE);
        if (mAddRightButton != null) mAddRightButton.setVisibility(rightAvailable ? View.VISIBLE : View.GONE);
        for (View view : mVersionBarViews) {
            if (view != null) view.setVisibility(editing ? View.GONE : View.VISIBLE);
        }
        if (mEditBackCallback != null) mEditBackCallback.setEnabled(editing);
        updateBottomBar();
    }

    /** @return the controller of the tiles, null in portrait */
    @Nullable
    public HomeTilesController getHomeTiles() {
        return mHomeTiles;
    }

    void launchLayoutImport() {
        mLayoutImportLauncher.launch(new String[]{"application/json", "text/plain", "*/*"});
    }

    /**
     * With a fullscreen wallpaper the home screen shows it through, and so do the sidebar and the
     * bottom bar, as translucent panels. The other screens show it as well unless that is turned off,
     * with a cover over it that is as strong as Launcher appearance says, none by default.
     */
    private void updatePaneBackground() {
        if (mRightPane == null) return;
        boolean atHome = getChildFragmentManager().getBackStackEntryCount() == 0;
        boolean wallpaperHere = mHasWallpaper && (atHome || mShowOthers);
        View root = getView();
        // The space shows through the home screen, the other screens cover it
        if (root != null) {
            root.setBackgroundColor(atHome || wallpaperHere ? Color.TRANSPARENT : ThemeColors.surfaceContainer(requireContext()));
        }
        if (atHome) {
            mRightPane.setBackground(null);
        } else if (!mHasWallpaper || !wallpaperHere) {
            mRightPane.setBackgroundResource(R.drawable.bg_right_pane);
        } else {
            int overlay = LauncherBackground.getOverlay(requireContext());
            if (overlay == 0) {
                mRightPane.setBackground(null);
            } else {
                GradientDrawable panel = new GradientDrawable();
                panel.setColor(ColorUtils.setAlphaComponent(ThemeColors.surface(requireContext()), Math.round(overlay * 2.55f)));
                panel.setCornerRadius(24f * getResources().getDisplayMetrics().density);
                mRightPane.setBackground(panel);
            }
        }
        // The wallpaper is only as sharp as the setting says on the home screen, the other screens get it blurred
        if (getActivity() instanceof LauncherActivity) ((LauncherActivity) getActivity()).refreshWallpaperFocus();
    }

    private void applyWallpaperBackdrop(@NonNull View root) {
        mHasWallpaper = LauncherBackground.isFullscreenActive(requireContext());
        mShowOthers = LauncherBackground.showOnOtherScreens(requireContext());
        if (!mHasWallpaper) return;
        root.setBackgroundColor(Color.TRANSPARENT);
    }

    // ─── Browse / Manage Content ─────────────────────────────────────────────

    /** Opens a screen in the right pane, or full screen in portrait */
    private void openPane(@NonNull Class<? extends Fragment> fragmentClass, @NonNull String tag,
                          @Nullable Bundle args) {
        if (!openInPane(fragmentClass, tag, args)) {
            Tools.swapFragment(requireActivity(), fragmentClass, tag, args);
        }
    }

    /**
     * Shows the Browse Content / Manage Content picker: mods, resource packs or shader packs.
     * The manage variant also exposes the per-instance version/loader filter.
     * Landscape docks it in the left pane, portrait shows it as a dialog.
     */
    public void showContentPicker(boolean manage) {
        if (isTwoPane()) openContentPickerPane(manage);
        else showContentPickerDialog(manage);
    }

    /**
     * Landscape: replaces the sidebar with ContentPickerFragment inside left_pane_container and
     * loads the Mods section into the right pane, all in a single back stack entry, so one Back
     * press undoes the whole action.
     */
    private void openContentPickerPane(boolean manage) {
        if (isTagAlreadyOnTop(ContentPickerFragment.TAG)) return;

        Bundle pickerArgs = new Bundle();
        pickerArgs.putBoolean(ContentPickerFragment.ARG_MANAGE, manage);

        Class<? extends Fragment> defaultFragmentClass;
        Bundle defaultArgs = new Bundle();
        String defaultTag;
        if (manage) {
            defaultFragmentClass = ManageModsFragment.class;
            defaultArgs.putString(ManageModsFragment.ARG_CONTENT_TYPE, ContentType.MOD.name());
            defaultTag = ManageModsFragment.TAG + ":" + ContentType.MOD.name();
        } else {
            defaultFragmentClass = ModsSearchFragment.class;
            populateModStoreArgs(defaultArgs, ContentType.MOD);
            defaultTag = ModsSearchFragment.TAG + ":" + ContentType.MOD.name();
        }

        getChildFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.left_pane_container, ContentPickerFragment.class, pickerArgs, ContentPickerFragment.TAG)
                .replace(R.id.right_pane_container, defaultFragmentClass, defaultArgs, defaultTag)
                .addToBackStack(ContentPickerFragment.TAG)
                .commit();
        // Visibility is updated by mBackStackListener once the transaction lands
    }

    /** Name of the back stack entry created by {@link #openBrowseFromManage} */
    private static final String BACK_BROWSE_FROM_MANAGE = "browse_from_manage";

    /**
     * Back button of a browse picker that was opened from Manage Content: goes back to the manage
     * screen, however many content types were browsed in between, instead of all the way home.
     */
    public void leaveBrowseFromManage() {
        getChildFragmentManager().popBackStack(BACK_BROWSE_FROM_MANAGE, FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    /**
     * Landscape: Manage Content's add / browse button. The left pane switches from the manage
     * picker to the browse picker (with the filter cards) and the right pane to the store, in one
     * back stack entry, so Back returns to the manage screen it came from.
     * @return false when this isn't the two-pane layout, the caller then navigates on its own
     */
    public boolean openBrowseFromManage(@NonNull Bundle searchArgs, @NonNull String tag) {
        if (!isTwoPane()) return false;
        Bundle pickerArgs = new Bundle();
        pickerArgs.putBoolean(ContentPickerFragment.ARG_MANAGE, false);
        pickerArgs.putBoolean(ContentPickerFragment.ARG_FROM_MANAGE, true);
        getChildFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.left_pane_container, ContentPickerFragment.class, pickerArgs, ContentPickerFragment.TAG)
                .replace(R.id.right_pane_container, ModsSearchFragment.class, searchArgs, tag)
                .addToBackStack(BACK_BROWSE_FROM_MANAGE)
                .commit();
        return true;
    }

    /**
     * Called by {@link ContentPickerFragment} when one of its mods / resource packs / shader packs
     * buttons is tapped. The picker itself stays in the left pane.
     */
    public void selectContentType(boolean manage, ContentType contentType) {
        onContentTypeChosen(manage, contentType);
    }

    /**
     * Landscape: opens the content that was just put into the ContentSession. The description
     * side (logo, back button and the Description / Gallery / Changelog / Versions buttons) takes
     * the left pane and the section picked with those buttons takes the right pane, Versions first.
     * Whatever was showing in the two panes is hidden, not replaced, so Back brings the search results back exactly as they were, scroll position
     * and query included. Both panes are in a single back stack entry.
     */
    public void openContentDetail() {
        if (!isTwoPane()) return;
        if (isTagAlreadyOnTop(ContentDetailFragment.TAG)) return;

        FragmentManager manager = getChildFragmentManager();
        FragmentTransaction transaction = manager.beginTransaction().setReorderingAllowed(true);
        Fragment left = manager.findFragmentById(R.id.left_pane_container);
        Fragment right = manager.findFragmentById(R.id.right_pane_container);
        if (left != null) transaction.hide(left);
        if (right != null) transaction.hide(right);
        transaction
                .add(R.id.left_pane_container, ContentDetailFragment.class,
                        ContentDetailFragment.args(ContentDetailFragment.MODE_LEFT), ContentDetailFragment.TAG)
                .add(R.id.right_pane_container, ContentDetailFragment.class,
                        ContentDetailFragment.args(ContentDetailFragment.MODE_RIGHT), ContentDetailFragment.TAG_RIGHT)
                .addToBackStack(ContentDetailFragment.TAG)
                .commit();
        // Visibility is updated by mBackStackListener once the transaction lands
    }

    /** Shows the sidebar or the content picker / content detail in the left pane, depending on which is active */
    private void updateLeftPaneVisibility() {
        if (!isTwoPane() || mLeftSidebar == null || mLeftPaneContainer == null) return;
        Fragment leftFragment = getChildFragmentManager().findFragmentById(R.id.left_pane_container);
        boolean paneActive = leftFragment instanceof ContentPickerFragment
                || leftFragment instanceof ContentDetailFragment;
        // The sidebar view is a stand-in, the picker takes a third of the screen and the pane gets the rest
        mLeftSidebar.setVisibility(View.GONE);
        mLeftPaneContainer.setVisibility(paneActive ? View.VISIBLE : View.GONE);
        if (mPaneGuideline != null) mPaneGuideline.setGuidelinePercent(paneActive ? 0.33f : 0f);
    }

    private void showContentPickerDialog(boolean manage) {
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(R.layout.dialog_content_picker)
                .create();

        dialog.setOnShowListener(di -> {
            TextView title = dialog.findViewById(R.id.content_picker_title);
            ImageButton filterButton = dialog.findViewById(R.id.content_picker_filter);
            View modsButton = dialog.findViewById(R.id.content_picker_mods);
            View resourcepacksButton = dialog.findViewById(R.id.content_picker_resourcepacks);
            View shaderpacksButton = dialog.findViewById(R.id.content_picker_shaderpacks);
            View worldsButton = dialog.findViewById(R.id.content_picker_worlds);
            View datapacksButton = dialog.findViewById(R.id.content_picker_datapacks);
            View screenshotsButton = dialog.findViewById(R.id.content_picker_screenshots);

            if (title != null) {
                title.setText(manage ? R.string.content_picker_title_manage
                                     : R.string.content_picker_title_browse);
            }

            if (filterButton != null) {
                if (manage) {
                    filterButton.setVisibility(View.VISIBLE);
                    filterButton.setOnClickListener(v ->
                            ContentFilterDialog.show(requireContext(), Instances.getSelectedInstanceKey(),
                                    (version, loader) -> Toast.makeText(requireContext(),
                                            getString(R.string.manage_mods_filter_active, version, loader),
                                            Toast.LENGTH_SHORT).show()));
                } else {
                    filterButton.setVisibility(View.GONE);
                }
            }

            if (modsButton != null) modsButton.setOnClickListener(v -> {
                dialog.dismiss();
                onContentTypeChosen(manage, ContentType.MOD);
            });
            if (resourcepacksButton != null) resourcepacksButton.setOnClickListener(v -> {
                dialog.dismiss();
                onContentTypeChosen(manage, ContentType.RESOURCE_PACK);
            });
            if (shaderpacksButton != null) shaderpacksButton.setOnClickListener(v -> {
                dialog.dismiss();
                onContentTypeChosen(manage, ContentType.SHADER_PACK);
            });
            if (worldsButton != null) worldsButton.setOnClickListener(v -> {
                dialog.dismiss();
                onContentTypeChosen(manage, ContentType.WORLD);
            });
            if (datapacksButton != null) datapacksButton.setOnClickListener(v -> {
                dialog.dismiss();
                onContentTypeChosen(manage, ContentType.DATAPACK);
            });
            // Screenshots can only be managed
            if (screenshotsButton != null) {
                screenshotsButton.setVisibility(manage ? View.VISIBLE : View.GONE);
                screenshotsButton.setOnClickListener(v -> {
                    dialog.dismiss();
                    onContentTypeChosen(true, ContentType.SCREENSHOT);
                });
            }
        });

        dialog.show();
    }

    private void onContentTypeChosen(boolean manage, ContentType contentType) {
        if (manage && contentType == ContentType.WORLD) {
            openPane(ManageWorldsFragment.class, ManageWorldsFragment.TAG, new Bundle());
        } else if (manage && contentType == ContentType.SCREENSHOT) {
            openPane(ManageScreenshotsFragment.class, ManageScreenshotsFragment.TAG, new Bundle());
        } else if (manage && contentType == ContentType.DATAPACK) {
            // Datapacks live inside a world, so the world comes first
            WorldUtils.pickWorld(requireContext(), R.string.datapack_manage_pick_world, world ->
                    openPane(ManageModsFragment.class, ManageModsFragment.datapackTag(world),
                            ManageModsFragment.datapackArgs(world)));
        } else if (manage) {
            Bundle args = new Bundle();
            args.putString(ManageModsFragment.ARG_CONTENT_TYPE, contentType.name());
            openPane(ManageModsFragment.class, ManageModsFragment.TAG + ":" + contentType.name(), args);
        } else {
            Bundle args = new Bundle();
            populateModStoreArgs(args, contentType);
            openPane(ModsSearchFragment.class, ModsSearchFragment.TAG + ":" + contentType.name(), args);
        }
    }

    /**
     * Builds the arguments for ModsSearchFragment with the saved per-instance filter pre-seeded.
     * The loader filter is only passed for MOD, resource packs and shader packs aren't loader specific.
     */
    private void populateModStoreArgs(Bundle args, ContentType contentType) {
        String instanceKey = Instances.getSelectedInstanceKey();
        SharedPreferences prefs = requireContext()
                .getSharedPreferences("mod_filters", Context.MODE_PRIVATE);

        String version = prefs.getString("mc_version_" + instanceKey, "");
        String loader  = prefs.getString("loader_" + instanceKey, "");

        // Nothing saved for this instance yet: default to the version/loader it runs, so the
        // results are already relevant without a filter-then-apply step
        if (version.isEmpty() && loader.isEmpty()) {
            InstanceVersionResolver.Info info = InstanceVersionResolver.resolve(instanceKey);
            if (info.mcVersion != null) version = info.mcVersion;
            loader = info.loader;
        }

        args.putString(ModsSearchFragment.ARG_CONTENT_TYPE, contentType.name());
        if (!version.isEmpty()) args.putString(ModsSearchFragment.ARG_PRESET_MC_VERSION, version);
        if (contentType == ContentType.MOD && !loader.isEmpty()) {
            args.putString(ModsSearchFragment.ARG_PRESET_LOADER, loader);
        }
    }

    /** @return whether the tag is the top entry of the right pane back stack, ie already showing */
    private boolean isTagAlreadyOnTop(String tag) {
        if (!isTwoPane()) return false;
        FragmentManager manager = getChildFragmentManager();
        int count = manager.getBackStackEntryCount();
        if (count == 0) return false;
        return tag.equals(manager.getBackStackEntryAt(count - 1).getName());
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The owner is this fragment, so the callback is removed with it
        mRightPaneBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (isRightPaneActive()) getChildFragmentManager().popBackStackImmediate();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, mRightPaneBackCallback);
        // Added last, so back leaves the edit mode before it does anything else
        mEditBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (mHomeTiles != null) mHomeTiles.exitEditMode();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, mEditBackCallback);
        // Closes the menu first when it is open
        mMenuBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                closeQuickMenu();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, mMenuBackCallback);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Quick Actions
        Button mNewsButton = view.findViewById(R.id.news_button);
        Button mDiscordButton = view.findViewById(R.id.social_media_button);
        // The menu paints every icon white, the Telegram icon has its own colors
        if (mDiscordButton != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) mDiscordButton.setCompoundDrawableTintList(null);
        Button mCustomControlButton = view.findViewById(R.id.custom_control_button);
        Button mInstallJarButton = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton = view.findViewById(R.id.share_logs_button);
        Button mManageContentButton = view.findViewById(R.id.open_files_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_directory_button);
        Button mModStoreButton = view.findViewById(R.id.mod_store_button);

        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        mRightPane = view.findViewById(R.id.right_pane_container);
        mBottomBar = view.findViewById(R.id.bottom_bar);
        mLeftSidebar = view.findViewById(R.id.left_sidebar);
        mLeftPaneContainer = view.findViewById(R.id.left_pane_container);
        mPaneGuideline = view.findViewById(R.id.pane_guideline);
        mMenuPanel = view.findViewById(R.id.quick_menu_panel);
        mMenuScrim = view.findViewById(R.id.quick_menu_scrim);
        // The button sits in the top bar of the activity, next to the settings button
        mMenuButton = requireActivity().findViewById(R.id.quick_menu_button);
        mMenuEdge = view.findViewById(R.id.quick_menu_edge);
        mMenuOpen = false;
        mVersionBarViews = new View[]{mVersionSpinner, mEditProfileButton, mPlayButton};
        getChildFragmentManager().addOnBackStackChangedListener(mBackStackListener);
        if (isTwoPane()) {
            applyWallpaperBackdrop(view);
            setupQuickMenu();
        }

        FragmentManager childManager = getChildFragmentManager();
        if (isTwoPane()) {
            // Checked by presence and not by savedInstanceState, so a rotation keeps working
            if (childManager.findFragmentById(R.id.right_pane_container) == null) {
                childManager.beginTransaction()
                        .setReorderingAllowed(true)
                        // Not on the back stack, the home screen is the base and not a destination
                        .replace(R.id.right_pane_container, RightPaneHomeFragment.class, null,
                                RightPaneHomeFragment.TAG)
                        .commit();
            }
            mBackStackListener.onBackStackChanged();
        } else if (childManager.getBackStackEntryCount() > 0) {
            // The pane the screens were in is gone after rotating to portrait
            childManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        // Wiki and Telegram are Quick Actions, the long press on Wiki keeps opening the gamepad mapper
        bindMenuAction(mNewsButton, () -> Tools.openURL(requireActivity(), Tools.URL_HOME));
        if (mNewsButton != null) {
            mNewsButton.setOnLongClickListener((v)->{
                closeQuickMenu();
                Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
                return true;
            });
        }
        bindMenuAction(mDiscordButton, () -> Tools.openURL(requireActivity(), getString(R.string.social_media_invite)));
        bindMenuAction(mModStoreButton, () -> showContentPicker(false));
        bindMenuAction(mCustomControlButton, () -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));

        // Instance Tools
        bindMenuAction(mManageContentButton, () -> showContentPicker(true));
        bindMenuAction(mOpenDirectoryButton, () -> openGameDirectory(requireContext()));
        bindMenuAction(mInstallJarButton, this::runInstallerWithConfirmation);
        bindMenuAction(mShareLogsButton, () -> shareLog(requireContext()));

        mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));
        mPlayButton.setOnClickListener(v -> ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));
    }

    /** A button of the menu: the menu closes, then the action runs */
    private void bindMenuAction(@Nullable View button, @NonNull Runnable action) {
        if (button == null) return;
        button.setOnClickListener(v -> {
            closeQuickMenu();
            action.run();
        });
    }

    /** The button, the swipe from the left edge and the dimmed area that open and close the menu */
    private void setupQuickMenu() {
        if (mMenuButton != null) mMenuButton.setOnClickListener(v -> {
            // One tap opens the menu, the next one closes it
            if (mMenuOpen) closeQuickMenu();
            else openQuickMenu();
        });
        if (mMenuScrim != null) mMenuScrim.setOnClickListener(v -> closeQuickMenu());
        if (mMenuEdge != null) {
            final float threshold = 32f * getResources().getDisplayMetrics().density;
            final float[] downX = new float[1];
            mMenuEdge.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX[0] = event.getRawX();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (event.getRawX() - downX[0] > threshold) openQuickMenu();
                        break;
                    default:
                        break;
                }
                return true;
            });
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        getChildFragmentManager().removeOnBackStackChangedListener(mBackStackListener);
        mRightPane = null;
        mBottomBar = null;
        mLeftSidebar = null;
        mLeftPaneContainer = null;
        if (mHomeTiles != null) mHomeTiles.detachAll();
        mHomeTiles = null;
        mEditControls = null;
        mAddRightButton = null;
        mVersionBarViews = new View[0];
        mEditing = false;
        mVersionSpinner = null;
        mPaneGuideline = null;
        mMenuPanel = null;
        mMenuScrim = null;
        if (mMenuButton != null) {
            mMenuButton.setOnClickListener(null);
            mMenuButton.setVisibility(View.GONE);
        }
        mMenuButton = null;
        mMenuEdge = null;
        mMenuOpen = false;
        if (mMenuBackCallback != null) mMenuBackCallback.setEnabled(false);
    }

    public void openGameDirectory(Context context) {
        Instance instance = Instances.loadSelectedInstance();
        if(instance == null) {
            Toast.makeText(context, R.string.no_instance, Toast.LENGTH_LONG).show();
            return;
        }
        File gameDirectory = instance.getGameDirectory();
        if(FileUtils.ensureDirectorySilently(gameDirectory)) {
            openPath(context, gameDirectory, false);
        }else {
            Toast.makeText(context, R.string.gamedir_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mHomeTiles != null) mHomeTiles.onResume();
        ExtraCore.setValue(ExtraConstants.REFRESH_ACCOUNT_SPINNER, true);
        // Runs after the task listeners, which could have changed the bar
        if (mBottomBar != null) mBottomBar.post(this::updateBottomBar);
    }

    public void runInstallerWithConfirmation() {
        if(!ProgressLayout.ensureNoOngoingTasks(requireContext())) return;
        mModInstallerLauncher.launch(null);
    }
}
