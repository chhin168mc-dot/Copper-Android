package git.artdeell.mojo.multirt;
import java.util.*;
public class SelectorTest {
  static int fails=0;
  static void check(String name, boolean ok){ System.out.println((ok?"PASS ":"FAIL ")+name); if(!ok) fails++; }
  static Runtime rt(String n,int v){ return new Runtime(n, v==8?"1.8.0_402":v+".0.1","aarch64",v); }
  static String pick(List<Runtime> l, Set<String> failed, String inst, String def, int req, boolean auto){
    RuntimeSelector.Resolution r=RuntimeSelector.resolveFrom(l,failed,inst,def,req,auto); return r==null?null:r.runtime.name; }
  public static void main(String[] a){
    List<Runtime> all=Arrays.asList(rt("jre8",8),rt("jre17",17),rt("jre21",21));
    Set<String> none=new HashSet<>();
    check("auto: java 8 game -> jre8", "jre8".equals(pick(all,none,null,"jre21",8,true)));
    check("auto: ignores default runtime", "jre17".equals(pick(all,none,null,"jre21",17,true)));
    check("manual: default used when new enough", "jre21".equals(pick(all,none,null,"jre21",17,false)));
    check("manual: default too old -> exact", "jre17".equals(pick(all,none,null,"jre8",17,false)));
    check("instance runtime wins in auto", "jre21".equals(pick(all,none,"jre21",null,17,true)));
    check("instance runtime too old ignored", "jre17".equals(pick(all,none,"jre8",null,17,true)));
    check("no 16 installed -> lowest above (17)", "jre17".equals(pick(all,none,null,null,16,true)));
    check("nothing new enough -> null", pick(Arrays.asList(rt("jre8",8)),none,null,null,17,true)==null);
    check("failed runtime skipped when another can run", "jre21".equals(pick(all,new HashSet<>(Arrays.asList("jre17")),null,null,17,true)));
    check("failed runtime kept when it is the only one", "jre8".equals(pick(Arrays.asList(rt("jre8",8)),new HashSet<>(Arrays.asList("jre8")),null,null,8,true)));
    RuntimeSelector.Resolution r=RuntimeSelector.resolveFrom(all,none,null,null,17,true);
    check("fallbacks hold the others, chosen not included", r.fallbacks.size()==1 && r.fallbacks.get(0).name.equals("jre21"));
    String[][] map={{"1.8.9","8"},{"1.12.2","8"},{"1.16.5","8"},{"1.17.1","16"},{"1.18.2","17"},{"1.19.4","17"},{"1.20.1","17"},{"1.20.4","17"},{"1.20.5","21"},{"1.20.6","21"},{"1.21.1","21"},{"1.21","21"},
      {"fabric-loader-0.15.11-1.20.1","17"},{"fabric-loader-0.16.5-1.21.1","21"},{"1.20.1-forge-47.2.0","17"},{"26.1","25"},{"foo","8"}};
    for(String[] m:map) check("map "+m[0]+" -> "+m[1], RuntimeSelector.javaForMinecraft(m[0])==Integer.parseInt(m[1]));
    check("map null -> 8", RuntimeSelector.javaForMinecraft(null)==8);
    System.out.println(fails==0?"ALL SELECTOR TESTS PASSED":("FAILED: "+fails)); if(fails>0) System.exit(1);
  }
}
