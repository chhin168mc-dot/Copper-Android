package git.artdeell.mojo.utils;
import java.io.*; import java.security.*;
public class HashUtils { public static String sha1Hex(File f) throws IOException { try { MessageDigest d=MessageDigest.getInstance("SHA-1"); try(FileInputStream in=new FileInputStream(f)){ byte[] b=new byte[8192]; int r; while((r=in.read(b))!=-1) d.update(b,0,r);} StringBuilder sb=new StringBuilder(); for(byte x:d.digest()) sb.append(String.format("%02x",x)); return sb.toString(); } catch(NoSuchAlgorithmException e){ throw new RuntimeException(e);} } }
