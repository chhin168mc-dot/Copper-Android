package git.artdeell.mojo.authenticator.accounts;

import android.util.Log;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.authenticator.AuthType;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.JSONUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class Accounts {
	private static final String PROFILE_PREF_FILE = "selected_account_file";

	public final List<Account> accounts;
	public final int selectionIndex;

	private Accounts(List<Account> accounts, int selectionIndex) {
        this.accounts = accounts;
        this.selectionIndex = selectionIndex;
    }

	public static Accounts load() throws IOException {
		File accountsDir = new File(Tools.DIR_ACCOUNT_NEW);
        synchronized (Accounts.class) {
            FileUtils.ensureDirectory(accountsDir);
        }
		File[] accountFiles = accountsDir.listFiles();
		if(accountFiles == null) throw new IOException("Failed to create account directory");
		String selectedAccount = getSelectedAccount();
		ArrayList<Account> accounts = new ArrayList<>(accountFiles.length);
		int selectedAccountIdx = 0;
		for(File accFile : accountFiles) {
			Account account = loadAccount(accFile);
			if(account == null) continue;
			accounts.add(account);
			if(accFile.getName().equals(selectedAccount)) {
				selectedAccountIdx = accounts.size() - 1;
			}
		}
		accounts.trimToSize();
		return new Accounts(Collections.unmodifiableList(accounts), selectedAccountIdx);
	}

	private static Account loadAccount(File source) {
		Account acc;
		try {
			acc = JSONUtils.readFromFile(source, Account.class);
		}catch (Exception e) {
			Log.w("Accounts", "Failed to load account", e);
			return null;
		}
        if(acc == null) return null;
		acc.mSaveLocation = source;

		if (acc.accessToken == null) {
			acc.accessToken = "0";
		}
		if (acc.profileId == null) {
			acc.profileId = "00000000-0000-0000-0000-000000000000";
		}
		if (acc.username == null) {
			acc.username = "0";
		}
		if (acc.refreshToken == null) {
			acc.refreshToken = "0";
		}
		if(acc.authType == null) {
			acc.authType = acc.isMicrosoft ? AuthType.MICROSOFT : AuthType.LOCAL;
		}
		return acc;
	}

	private static String getSelectedAccount() {
		return LauncherPreferences.DEFAULT_PREF.getString(PROFILE_PREF_FILE, "");
	}

    public static Account getCurrent() {
		String selectedAccount = getSelectedAccount();
		return loadAccount(new File(Tools.DIR_ACCOUNT_NEW, selectedAccount));
    }

	private static File pickAccountPath() {
		File profilePath;
		do {
			String profileName = UUID.randomUUID().toString();
			profilePath = new File(Tools.DIR_ACCOUNT_NEW, profileName);
		} while(profilePath.exists());
		return profilePath;
	}

	public static Account create(Setter setter) throws IOException {
		Account account = new Account();
		setter.writeAccount(account);
		// Signing in again with an account that is already saved updates it. Otherwise every
		// re-login (for example after the session expired) left one more copy of it in the list.
		File existing = findSavedAccount(account);
		account.mSaveLocation = existing != null ? existing : pickAccountPath();
		account.save();
		return account;
	}

	private static File findSavedAccount(Account fresh) {
		if(fresh.authType == null || fresh.authType == AuthType.LOCAL || fresh.profileId == null) return null;
		File[] accountFiles = new File(Tools.DIR_ACCOUNT_NEW).listFiles();
		if(accountFiles == null) return null;
		for(File accFile : accountFiles) {
			Account saved = loadAccount(accFile);
			if(saved != null && saved.authType == fresh.authType && fresh.profileId.equals(saved.profileId)) {
				return accFile;
			}
		}
		return null;
	}

	public static void setCurrent(Account account) {
		LauncherPreferences.DEFAULT_PREF
				.edit().putString(PROFILE_PREF_FILE, account.mSaveLocation.getName())
				.apply();
	}

	public static void delete(Account account) {
		boolean ignored = account.mSaveLocation.delete();
	}

	public interface Setter {
		void writeAccount(Account account) throws IOException;
	}
}
