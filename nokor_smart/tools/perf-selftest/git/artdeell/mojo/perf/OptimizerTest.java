package git.artdeell.mojo.perf;
import android.content.SharedPreferences;
import java.util.*;
public class OptimizerTest {
  static int fails=0;
  static void check(String n, boolean ok){ System.out.println((ok?"PASS ":"FAIL ")+n); if(!ok) fails++; }
  /** Map backed implementation, values are copied like the real one does */
  static class Fake implements SharedPreferences {
    final Map<String,Object> m=new HashMap<>();
    public boolean contains(String k){return m.containsKey(k);}
    public int getInt(String k,int d){return m.containsKey(k)?(Integer)m.get(k):d;}
    public boolean getBoolean(String k,boolean d){return m.containsKey(k)?(Boolean)m.get(k):d;}
    public String getString(String k,String d){return m.containsKey(k)?(String)m.get(k):d;}
    @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String> d){return m.containsKey(k)?new HashSet<>((Set<String>)m.get(k)):d;}
    public Editor edit(){ return new Editor(){
      final Map<String,Object> put=new HashMap<>(); final Set<String> rm=new HashSet<>();
      public Editor putInt(String k,int v){put.put(k,v);return this;}
      public Editor putBoolean(String k,boolean v){put.put(k,v);return this;}
      public Editor putString(String k,String v){put.put(k,v);return this;}
      public Editor putStringSet(String k,Set<String> v){put.put(k,new HashSet<>(v));return this;}
      public Editor remove(String k){rm.add(k);return this;}
      public boolean commit(){ for(String k:rm) m.remove(k); m.putAll(put); return true; } }; }
  }
  static GameOptimizer.Inputs in(int ram,int cores,boolean b32,int rec,int heap,int res,boolean sus,String sel,boolean pre){
    return new GameOptimizer.Inputs(ram,cores,b32,rec,heap,res,sus,sel,pre); }
  static GameOptimizer.Change find(List<GameOptimizer.Change> l,String k){ for(GameOptimizer.Change c:l) if(c.key.equals(k)) return c; return null; }
  public static void main(String[] a){
    // strong phone, everything fine: nothing to change
    check("nothing changes on a device that is already set up", GameOptimizer.plan(in(8192,8,false,3072,2048,100,true,"auto",true)).isEmpty());
    // heap too big for a 4 GB phone
    List<GameOptimizer.Change> p=GameOptimizer.plan(in(4096,8,false,1792,3072,100,true,"auto",true));
    check("heap above 125% of recommended is lowered to recommended", find(p,"allocation")!=null && (Integer)find(p,"allocation").after==1792 && (Integer)find(p,"allocation").before==3072);
    // heap too small
    p=GameOptimizer.plan(in(8192,8,false,3072,512,100,true,"auto",true));
    check("small heap is raised to at most 2048", (Integer)find(p,"allocation").after==2048);
    // inside the tolerance: not touched
    p=GameOptimizer.plan(in(8192,8,false,3072,2048,100,true,"auto",true));
    check("heap inside tolerance is kept", find(p,"allocation")==null);
    // weak device
    p=GameOptimizer.plan(in(2048,4,false,640,640,100,false,"auto",true));
    check("weak device gets 75% resolution", find(p,"resolutionRatio")!=null && (Integer)find(p,"resolutionRatio").after==75);
    check("weak device with 4 cores gets no sustained mode", find(p,"sustainedPerformance")==null);
    p=GameOptimizer.plan(in(2048,4,false,640,640,60,false,"auto",true));
    check("resolution already below 75% is kept", find(p,"resolutionRatio")==null);
    p=GameOptimizer.plan(in(8192,8,false,3072,2048,100,false,"auto",true));
    check("8 cores device gets sustained mode", find(p,"sustainedPerformance")!=null);
    p=GameOptimizer.plan(in(8192,8,true,1024,1024,100,false,"auto",true));
    check("32 bit device gets no sustained mode", find(p,"sustainedPerformance")==null);
    p=GameOptimizer.plan(in(8192,8,false,3072,2048,100,true,"manual",false));
    check("runtime options go to auto and warm-up on", find(p,"runtimeSelection")!=null && find(p,"runtimePreload")!=null);
    check("strong device keeps its resolution", find(GameOptimizer.plan(in(8192,8,false,3072,2048,100,true,"auto",true)),"resolutionRatio")==null);

    // apply and restore
    Fake f=new Fake(); f.m.put("allocation",3072); f.m.put("runtimeSelection","manual");
    p=GameOptimizer.plan(in(4096,8,false,1792,3072,100,false,"manual",true));
    GameOptimizer.apply(f,p);
    check("apply writes the new heap", f.getInt("allocation",0)==1792);
    check("apply writes sustained mode", f.getBoolean("sustainedPerformance",false));
    check("apply writes auto selection", "auto".equals(f.getString("runtimeSelection","")));
    check("backup exists", GameOptimizer.hasBackup(f));
    // a second optimize with new values must keep the first backup
    GameOptimizer.apply(f,Arrays.asList(new GameOptimizer.Change("allocation",GameOptimizer.INT,1792,1024)));
    GameOptimizer.restore(f);
    check("restore brings back the original heap", f.getInt("allocation",0)==3072);
    check("restore brings back the original selection", "manual".equals(f.getString("runtimeSelection","")));
    check("restore removes a value that was never set", !f.contains("sustainedPerformance"));
    check("backup is gone after restore", !GameOptimizer.hasBackup(f));
    System.out.println(fails==0?"ALL OPTIMIZER TESTS PASSED":("FAILED: "+fails)); if(fails>0) System.exit(1);
  }
}
