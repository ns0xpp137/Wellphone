package local.wellphone.remote;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.*;
import java.util.concurrent.*;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** shell 注入通道：经 Shizuku（shell uid）执行 input 命令做原始触摸注入。
 *  与 whale 同思路（它内嵌的就是 Shizuku 开源配对代码）——
 *  不读无障碍节点树，藏树 App（微信/系统记事本）也能点。
 *  input -d <displayId> 精确落到虚拟屏，绝不碰用户主屏。 */
public class ShellChannel {
 private static final String TAG = "WellphoneShell";

 public static boolean binderReady(){
  try{ return Shizuku.pingBinder(); }catch(Throwable t){ return false; }
 }
 public static boolean permissionGranted(){
  try{ return Shizuku.checkSelfPermission() == 0; }catch(Throwable t){ return false; }
 }
 public static boolean ready(){ return binderReady() && permissionGranted(); }

 /** Shizuku 没起但内置通道（adb 配对）已就绪 —— 这两条通道对上层是等价的。
  *  ★ 以前所有调用点只写 ShellChannel.ready()，内置通道下的点击/输入/am start 全被判"不可用"，
  *    旧机日志里"树被藏且 shell 通道不可用"就是这么来的。 */
 public static boolean builtinReady(){
  try{
   android.content.Context c = App.ctx;
   return c != null && BuiltinShell.hasSaved(c) && BuiltinShell.savedPort(c) > 0;
  }catch(Throwable t){ return false; }
 }
 /** 任一条 shell 通道可用 */
 public static boolean available(){ return ready() || builtinReady(); }

 public static void requestPermission(){
  try{ Shizuku.requestPermission(1); }catch(Throwable t){ Log.i(TAG, "requestPermission " + t); }
 }

 /** 在指定 display 上点击（坐标为该 display 的像素坐标） */
 public static boolean tap(int displayId, int x, int y){
  return displayId > 0 && execOk("input -d " + displayId + " tap " + x + " " + y);
 }
 /** 在指定 display 上滑动 */
 public static boolean swipe(int displayId, int x1, int y1, int x2, int y2, int ms){
  return displayId > 0 && execOk("input -d " + displayId + " swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms);
 }
 /** 在指定 display 上按键 */
 public static boolean key(int displayId, int keyCode){
  return displayId > 0 && execOk("input -d " + displayId + " keyevent " + keyCode);
 }
 /** 在指定 display 当前焦点框输入文本（空格转 %s，不支持的字符过滤） */
 public static boolean text(int displayId, String s){
  if(displayId <= 0 || s == null || s.isEmpty() || !s.matches("[\\x20-\\x7E]+")) return false;
  return displayId > 0 && execOk("input -d " + displayId + " text " + quote(s.replace(" ", "%s")));
 }

 public static String quote(String s){ return "'" + s.replace("'", "'\"'\"'") + "'"; }

 /** 原样取回 stdout+stderr，不看退出码（诊断 am start / pm grant 这类"结果写在输出里"的命令） */
 public static String raw(String cmd, long timeoutMs){
  if(!ready()) return builtinExec(cmd);
  IRemoteProcess p = null;
  try{
   IShizukuService svc = IShizukuService.Stub.asInterface(Shizuku.getBinder());
   if(svc == null) return null;
   p = svc.newProcess(new String[]{"sh", "-c", cmd}, null, null);
   final IRemoteProcess proc = p;
   StringBuilder out = new StringBuilder(), err = new StringBuilder();
   Thread tOut = drain(proc.getInputStream(), out, false);
   Thread tErr = drain(proc.getErrorStream(), err, false);
   final int[] code = {-1};
   Thread tWait = new Thread(() -> { try{ code[0] = proc.waitFor(); }catch(Throwable t){} });
   tWait.start();
   tWait.join(timeoutMs);
   if(tWait.isAlive()){
    try{ proc.destroy(); }catch(Throwable t){}
    return (out.toString() + "\n" + err.toString()).trim() + " [TIMEOUT]";
   }
   tOut.join(1000); tErr.join(1000);
   String o = out.toString().trim(), e = err.toString().trim();
   return (o.isEmpty() ? "" : o) + (e.isEmpty() ? "" : (o.isEmpty() ? "" : " | ") + e)
       + " [exit=" + code[0] + "]";
  }catch(Throwable t){
   return null;
  }finally{ if(p != null){ try{ p.destroy(); }catch(Throwable t){} } }
 }
 // ---------- 基础执行 ----------
 static boolean execOk(String cmd){
  String r = exec(cmd, 8000);
  boolean ok = r != null;
  Log.i(TAG, "exec[" + ok + "] " + cmd + (ok ? "" : " (fail)"));
  return ok;
 }

 /** 经 Shizuku 以 shell 身份执行命令；返回 stdout（含空串），失败返回 null */
 public static String exec(String cmd, long timeoutMs){
  if(!ready()) return builtinExec(cmd);
  IRemoteProcess p = null;
  try{
   IShizukuService svc = IShizukuService.Stub.asInterface(Shizuku.getBinder());
   if(svc == null) return null;
   p = svc.newProcess(new String[]{"sh", "-c", cmd}, null, null);
   final IRemoteProcess proc = p;
   final StringBuilder out = new StringBuilder();
   Thread tOut = drain(proc.getInputStream(), out, false);
   StringBuilder err = new StringBuilder();
   Thread tErr = drain(proc.getErrorStream(), err, false);
   final int[] code = {-1};
   Thread tWait = new Thread(() -> {
    try{ code[0] = proc.waitFor(); }catch(Throwable t){}
   });
   tWait.start();
   tWait.join(timeoutMs);
   if(tWait.isAlive()){
    try{ proc.destroy(); }catch(Throwable t){}
    Log.i(TAG, "exec timeout: " + cmd);
    return null;
   }
   tOut.join(1000); tErr.join(1000);
   if(code[0] != 0){ Log.w(TAG, "process failed exit=" + code[0] + " " + err); return null; }
   if(out.toString().contains("Error:") || err.toString().contains("Error:")) return null;
   return out.toString();
  }catch(Throwable t){
   Log.i(TAG, "exec err " + t);
   return null;
  }finally{
   if(p != null){ try{ p.destroy(); }catch(Throwable t){} }
  }
 }

 /** 内置通道（无 Shizuku）：把同一条命令经 adb shell 下发。
  *  每次新建一条 adb 连接，约 200~300ms，够用；命令本身的行为与 Shizuku 通道完全一致。 */
 private static String builtinExec(String cmd){
  try{
   android.content.Context c = App.ctx;
   if(c == null){ AgentService.shotDebug("builtinExec: App.ctx 为空"); return null; }
   // execFast 复用长连接；execFromSaved 每条命令都重新握手，大输出会丢
   if(!BuiltinShell.hasSaved(c) || BuiltinShell.savedPort(c) <= 0){
    AgentService.shotDebug("builtinExec: 还没有可用配对 port=" + BuiltinShell.savedPort(c));
    return null;
   }
   String out = BuiltinShell.execFast(c, cmd);
   if(out == null) AgentService.shotDebug("builtinExec: execFast 返回 null（连接异常）cmd=" + cmd);
   return out;
  }catch(Throwable t){
   Log.i(TAG, "builtinExec err " + t);
   return null;
  }
 }

 /** ParcelFileDescriptor → 后台线程读成字符串 */
 private static Thread drain(final ParcelFileDescriptor pfd, final StringBuilder sink, final boolean discard){
  Thread t = new Thread(() -> {
   if(pfd == null) return;
   try(InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)){
    byte[] buf = new byte[512];
    int n;
    while((n = in.read(buf)) > 0){
     if(!discard && sink != null) sink.append(new String(buf, 0, n, "UTF-8"));
    }
   }catch(Throwable t2){}
  });
  t.start();
  return t;
 }
}
