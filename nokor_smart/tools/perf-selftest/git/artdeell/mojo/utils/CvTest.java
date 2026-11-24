package git.artdeell.mojo.utils;
import git.artdeell.mojo.modding.modpacks.models.ContentType;
import java.io.*; import java.util.zip.*; import java.nio.file.*;
public class CvTest {
  static int fails=0;
  static void check(String n, boolean ok){ System.out.println((ok?"PASS ":"FAIL ")+n); if(!ok) fails++; }
  static File zip(String name, String... entries) throws Exception { File f=File.createTempFile(name,".zip"); try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(f))){ for(String e:entries){ z.putNextEntry(new ZipEntry(e)); z.write(1); z.closeEntry(); } } return f; }
  public static void main(String[] a) throws Exception {
    File empty=File.createTempFile("empty",".jar");
    check("empty file rejected", ContentValidator.validate(empty,ContentType.MOD,null).reason==1);
    File junk=File.createTempFile("junk",".jar"); Files.write(junk.toPath(),"hello not a zip".getBytes());
    check("not a zip rejected", ContentValidator.validate(junk,ContentType.MOD,null).reason==2);
    File mod=zip("mod","fabric.mod.json","a/B.class");
    check("valid mod accepted", ContentValidator.validate(mod,ContentType.MOD,null).ok);
    File oldMod=zip("old","com/x/Y.class");
    check("old forge mod without descriptor accepted", ContentValidator.validate(oldMod,ContentType.MOD,null).ok);
    String sha=HashUtils.sha1Hex(mod);
    check("matching sha1 accepted", ContentValidator.validate(mod,ContentType.MOD,sha).ok);
    check("wrong sha1 rejected", ContentValidator.validate(mod,ContentType.MOD,"0000000000000000000000000000000000000000").reason==3);
    check("non sha1 hash ignored", ContentValidator.validate(mod,ContentType.MOD,"12345").ok);
    check("upper case sha1 accepted", ContentValidator.validate(mod,ContentType.MOD,sha.toUpperCase()).ok);
    check("resource pack with pack.mcmeta", ContentValidator.validate(zip("rpk","pack.mcmeta","assets/x.png"),ContentType.RESOURCE_PACK,null).ok);
    check("resource pack wrapped in a folder rejected", ContentValidator.validate(zip("rpw","Pack/pack.mcmeta"),ContentType.RESOURCE_PACK,null).reason==4);
    check("shader with shaders/", ContentValidator.validate(zip("sh1","shaders/final.fsh"),ContentType.SHADER_PACK,null).ok);
    check("shader wrapped in one folder", ContentValidator.validate(zip("sh2","Pack/shaders/final.fsh"),ContentType.SHADER_PACK,null).ok);
    check("shader without shaders folder rejected", ContentValidator.validate(zip("sh3","readme.txt"),ContentType.SHADER_PACK,null).reason==5);
    check("datapack zip only checked as zip", ContentValidator.validate(zip("dpk","data/x.json"),ContentType.DATAPACK,null).ok);
    System.out.println(fails==0?"ALL VALIDATOR TESTS PASSED":("FAILED: "+fails)); if(fails>0) System.exit(1);
  }
}
