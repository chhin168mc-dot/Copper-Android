package android.content;
import java.util.Set;
/** The part of the Android interface that the code under test uses */
public interface SharedPreferences {
    boolean contains(String key);
    int getInt(String key, int def);
    boolean getBoolean(String key, boolean def);
    String getString(String key, String def);
    Set<String> getStringSet(String key, Set<String> def);
    Editor edit();
    interface Editor {
        Editor putInt(String k, int v);
        Editor putBoolean(String k, boolean v);
        Editor putString(String k, String v);
        Editor putStringSet(String k, Set<String> v);
        Editor remove(String k);
        boolean commit();
    }
}
