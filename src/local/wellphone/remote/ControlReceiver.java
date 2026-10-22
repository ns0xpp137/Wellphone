package local.wellphone.remote;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 常驻通知上四个按钮的接收口。
 *  为什么要有这个：思考窗和镜像窗会盖住 App 自己的按钮行，想切个开关得先挪开窗，
 *  很别扭。下拉通知栏不受浮窗遮挡，四个常用开关放那儿最顺手。 */
public class ControlReceiver extends BroadcastReceiver {
 public static final String ACTION = "local.wellphone.CONTROL";
 public static final String EXTRA = "act";

 public void onReceive(Context ctx, Intent it){
  if(it == null) return;
  String act = it.getStringExtra(EXTRA);
  if(act == null) return;
  log("收到按钮 " + act + " (ui=" + (MainActivity.current != null) + ")");
  MainActivity ui = MainActivity.current;
  if("think".equals(act)){
   ThinkingWindow w = ThinkingWindow.get();
   boolean was = w.isShown();
   if(was) w.hide(); else w.show(ctx, null, null);
   log("悬浮窗 " + (was ? "关" : "开"));
   if(!was && ui != null) w.setControlListeners(v -> ui.stopTask(), v -> ui.takeover());
  }else if("mirror".equals(act)){
   MirrorWindow m = MirrorWindow.get();
   boolean on = m.toggle(ctx);
   log("镜像窗 " + (on ? "开" : "关"));
  }else if("takeover".equals(act)){
   if(ui != null) ui.takeover(); else log("接管失败：Activity 已不在");
  }else if("back".equals(act)){
   if(ui != null) ui.backToAgent(); else log("返回后台失败：Activity 已不在");
  }else if("stop".equals(act)){
   if(ui != null) ui.stopTask(); else log("停止失败：Activity 已不在");
  }
  if(ui != null){
   try{ ui.refreshControlNotification(); }catch(Throwable t){ log("刷新通知失败 " + t); }
  }
 }
 private static void log(String s){
  try{
   java.io.File f = App.f("control-log.txt");
   java.io.FileWriter fw = new java.io.FileWriter(f, true);
   fw.write(System.currentTimeMillis() + " " + s + "\n"); fw.close();
  }catch(Throwable t){}
 }
}
