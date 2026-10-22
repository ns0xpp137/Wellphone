package local.wellphone.remote;
import android.content.Context;
import java.io.File;

/** 全局 application context。
 *  为什么要这个：同一台手机上会同时装"正式版"和"开发版"，两者 applicationId 不同，
 *  各自的外部文件目录(/sdcard/Android/data/<pkg>/files)也不同。源码里若硬编码
 *  local.wellphone.remote，开发版就会往正式版的目录里写（还写不进去），日志全乱。 */
public class App {
 public static volatile Context ctx;
 public static File f(String name){
  if(ctx == null) return new File("/sdcard/" + name);
  File d = ctx.getExternalFilesDir(null);
  return d == null ? new File("/sdcard/" + name) : new File(d, name);
 }
 /** 调试日志统一走这里；返回 null 表示拿不到目录 */
 public static String path(String name){
  File f = f(name);
  return f == null ? null : f.getAbsolutePath();
 }
}
