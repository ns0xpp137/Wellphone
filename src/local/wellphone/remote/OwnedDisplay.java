package local.wellphone.remote;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.util.concurrent.*;
import moe.shizuku.server.*;
import rikka.shizuku.Shizuku;

/** One app-owned connection to a shell display host; EOF destroys its display. */
public final class OwnedDisplay {
 private static IRemoteProcess process;
 private static DataInputStream input;
 private static DataOutputStream output;
 private static int id=-1;
 // 虚拟屏的真实像素尺寸（归一化坐标要用它，不能再用死值）
 public static volatile int realW=0, realH=0;
 private static final ExecutorService io=Executors.newSingleThreadExecutor();
 public static synchronized int ensure(Context ctx){
  if(id>0 && process != null){ try{ if(process.alive()) return id; }catch(Throwable ignored){} }
  close();
  if(!ShellChannel.ready()) return -1;
  try{
   IShizukuService svc=IShizukuService.Stub.asInterface(Shizuku.getBinder());
   process=svc.newProcess(new String[]{"sh","-c","CLASSPATH="+ShellChannel.quote(ctx.getApplicationInfo().sourceDir)+" app_process /system/bin local.wellphone.remote.DisplayHost"},null,null);
   final IRemoteProcess p=process;
   new Thread(() -> { try(BufferedReader r=new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(p.getErrorStream())))){
    String line; while((line=r.readLine())!=null) AgentService.shotDebug("DisplayHost "+line);
   }catch(Exception ignored){} },"display-errors").start();
   input=new DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(process.getInputStream()));
   output=new DataOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(process.getOutputStream()));
   final DataInputStream source=input;
   Future<int[]> ready=io.submit(() -> new int[]{ source.readInt(), source.readInt(), source.readInt() });
   int[] meta=ready.get(8,TimeUnit.SECONDS);
   id=meta[0]; realW=meta[1]; realH=meta[2];
   if(id<=0) throw new IOException("Invalid display ID");
   return id;
  }catch(Throwable e){ AgentService.shotDebug("OwnedDisplay start: "+e); close(); return -1; }
 }
 public static synchronized byte[] capture(int displayId){
  if(id<=0 || displayId!=id || input==null) return null;
  try{
   final DataInputStream source=input; final DataOutputStream sink=output;
   Future<byte[]> result=io.submit(() -> {
    sink.writeInt(1); sink.flush(); int n=source.readInt();
    if(n==0) return null;
    if(n<0 || n>16000000) throw new IOException("Invalid image size");
    byte[] bytes=new byte[n]; source.readFully(bytes); return bytes;
   });
   return result.get(5,TimeUnit.SECONDS);
  }catch(Throwable e){ AgentService.shotDebug("OwnedDisplay capture: "+e); close(); return null; }
 }
 /** 镜像窗用的只读取帧：出错也不关屏——它是次要消费者，不能把工作区搞没 */
 public static synchronized byte[] peek(int displayId){
  if(id<=0 || displayId!=id || input==null) return null;
  try{
   final DataInputStream source=input; final DataOutputStream sink=output;
   Future<byte[]> result=io.submit(() -> {
    sink.writeInt(1); sink.flush(); int n=source.readInt();
    if(n==0) return null;
    if(n<0 || n>16000000) throw new IOException("Invalid image size");
    byte[] bytes=new byte[n]; source.readFully(bytes); return bytes;
   });
   return result.get(5,TimeUnit.SECONDS);
  }catch(Throwable e){ return null; }
 }
 public static synchronized void close(){
  id=-1;
  try{ if(process!=null) process.destroy(); }catch(Throwable ignored){}
  try{ if(input!=null) input.close(); }catch(Throwable ignored){}
  try{ if(output!=null) output.close(); }catch(Throwable ignored){}
  process=null;input=null;output=null;
 }
}
