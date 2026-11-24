package git.artdeell.mojo.utils.jre;
import java.util.*;
public class SanTest {
  static int fails=0;
  static void check(String name, boolean ok){ System.out.println((ok?"PASS ":"FAIL ")+name); if(!ok) fails++; }
  static List<String> L(String... s){ return new ArrayList<>(Arrays.asList(s)); }
  public static void main(String[] x){
    List<String> a=L("-Xmx2G","--add-opens","java.base/java.lang=ALL-UNNAMED","-XX:+UseG1GC","-Xlog:gc*","--add-exports=java.base/sun.nio.ch=ALL-UNNAMED");
    JvmArgSanitizer.sanitize(a,8);
    check("java 8 drops module options and -Xlog", a.equals(L("-Xmx2G","-XX:+UseG1GC")));
    a=L("-Xmx2G","--add-opens","java.base/java.lang=ALL-UNNAMED","-Xlog:gc*");
    JvmArgSanitizer.sanitize(a,17);
    check("java 17 keeps module options and -Xlog", a.equals(L("-Xmx2G","--add-opens","java.base/java.lang=ALL-UNNAMED","-Xlog:gc*")));
    a=L("-XX:+UseConcMarkSweepGC","-XX:CMSInitiatingOccupancyFraction=70","-XX:+UseG1GC");
    JvmArgSanitizer.sanitize(a,17);
    check("java 17 drops CMS", a.equals(L("-XX:+UseG1GC")));
    a=L("-XX:+UseConcMarkSweepGC");
    JvmArgSanitizer.sanitize(a,8);
    check("java 8 keeps CMS", a.equals(L("-XX:+UseConcMarkSweepGC")));
    a=L("-XX:+AggressiveOpts","-XX:+UseBiasedLocking","-XX:MaxPermSize=256m");
    JvmArgSanitizer.sanitize(a,21);
    check("java 21 drops AggressiveOpts, BiasedLocking, MaxPermSize", a.isEmpty());
    a=L("-XX:+AggressiveOpts","-XX:+UseBiasedLocking");
    JvmArgSanitizer.sanitize(a,11);
    check("java 11 keeps AggressiveOpts and BiasedLocking", a.size()==2);
    a=L("-XX:+ZGenerational","--illegal-access=permit");
    JvmArgSanitizer.sanitize(a,17);
    check("java 17 drops ZGenerational and --illegal-access", a.isEmpty());
    a=L("-XX:+ZGenerational");
    JvmArgSanitizer.sanitize(a,21);
    check("java 21 keeps ZGenerational", a.size()==1);
    a=L("-Xmx1G","--add-opens");
    JvmArgSanitizer.sanitize(a,8);
    check("lone option at the end does not crash", a.equals(L("-Xmx1G")));
    a=L("--add-opens","-Xmx1G");
    JvmArgSanitizer.sanitize(a,8);
    check("value is not taken when next is an option", a.equals(L("-Xmx1G")));
    a=L("-Dfoo=bar"); JvmArgSanitizer.sanitize(a,0);
    check("unknown java version changes nothing", a.size()==1);
    System.out.println(fails==0?"ALL SANITIZER TESTS PASSED":("FAILED: "+fails)); if(fails>0) System.exit(1);
  }
}
