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
 public static volatile int id=-1;
 // 虚拟屏的真实像素尺寸（归一化坐标要用它，不能再用死值）
 public static volatile int realW=0, realH=0;
 /** 副屏是系统 overlay 屏：主屏上会多一个 Android 自带的「叠加视图」预览窗（关不掉，只能盖住） */
 public static volatile boolean overlayMode=false;
 private static final ExecutorService io=Executors.newSingleThreadExecutor();
 public static synchronized int ensure(Context ctx){
  // ★ 已连着就直接复用。
  //   原来这里只认 Shizuku 的 process 句柄，内置通道（socket）下 process 恒为 null，
  //   于是每次 ensure 都 close() 把刚建好的连接断掉 → DisplayHost 收到 EOF 就退出
  //   （日志里表现为 "display connection closed: EOFException" + "Killed"）。
  if(id>0 && input != null){
   if(process != null){ try{ if(process.alive()) return id; }catch(Throwable ignored){} }
   else return id;                     // 内置 socket 通道：只要流还在就一直复用
  }
  close();
  // 没有 Shizuku 时：内置通道已用 adb shell 把 DisplayHost 启动起来了，改走本地 socket
  if(!ShellChannel.ready()) return connectBuiltin();

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
   Future<int[]> ready=io.submit(() -> new int[]{ source.readInt(), source.readInt(), source.readInt(), source.readInt() });
   int[] meta=ready.get(8,TimeUnit.SECONDS);
   id=meta[0]; realW=meta[1]; realH=meta[2];
   overlayMode = meta.length > 3 && meta[3] == 1;
   if(id<=0) throw new IOException("Invalid display ID");
   return id;
  }catch(Throwable e){ AgentService.shotDebug("OwnedDisplay start: "+e); close(); return -1; }
 }
 public static final int BUILTIN_PORT = 18765;

 /** 连接由内置通道（adb shell）启动的 DisplayHost。它会先建屏再监听，
  *  所以我们重试一段时间，直到连上并读到 display id。 */
 /** 快速探测：已有 DisplayHost 在跑就直接复用（不需要 kill 旧进程，
  *  也就避免了 pkill -f 把执行它自己的 shell 一起杀掉那个坑） */
 public static synchronized boolean connectIfRunning(){
  if(id>0 && input!=null) return true;
  closeForReconnect();
  return connectBuiltin(2) > 0;
 }
 private static void closeForReconnect(){
  try{ if(input!=null) input.close(); }catch(Throwable ig){}
  input=null; output=null; id=-1;
 }

 private static int connectBuiltin(){ return connectBuiltin(24); }
 private static int connectBuiltin(int tries){
  for(int i=0;i<tries;i++){
   java.net.Socket s=null;
   try{
    s=new java.net.Socket();
    s.connect(new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), BUILTIN_PORT), 700);
    s.setTcpNoDelay(true);
    DataInputStream in=new DataInputStream(s.getInputStream());
    DataOutputStream out=new DataOutputStream(s.getOutputStream());
    int did=in.readInt(), w=in.readInt(), h=in.readInt();
    int mode=0; try{ mode=in.readInt(); }catch(Throwable ig){}   // 第 4 个 int：是否 overlay 副屏
    if(did<=0) throw new IOException("Invalid display ID from builtin host");
    input=in; output=out; id=did; realW=w; realH=h; overlayMode = (mode == 1);
    AgentService.shotDebug("BuiltinDisplay connected id="+did+" "+w+"x"+h);
    return did;
   }catch(Throwable e){
    if(i<2) AgentService.shotDebug("BuiltinDisplay connect #"+(i+1)+" failed: "+e);
    try{ if(s!=null) s.close(); }catch(Throwable ig){}
    try{ Thread.sleep(500); }catch(Throwable ig){}
   }
  }
  AgentService.shotDebug("BuiltinDisplay connect failed (24 次重试)");
  return -1;
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
