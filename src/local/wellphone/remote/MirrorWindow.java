package local.wellphone.remote;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** 虚拟工作区的实时镜像窗：把 agent 在虚拟屏里看到的东西，原样投到用户屏幕的一小块地方。
 *  演示时同屏三件事：用户在正常用机 + 右上角 agent 工作区在动 + 思考流在跑。
 *  数据源是 DisplayHost 已经产出的帧，不额外抓屏，也不碰主屏内容（防漏底）。 */
public class MirrorWindow {
 private static MirrorWindow inst;
 public static MirrorWindow get(){ if(inst == null) inst = new MirrorWindow(); return inst; }
 private MirrorWindow(){}
 // 通知栏按钮触发时 Activity 可能已经不在了，所以自己记住屏号，别去问它
 private static volatile int lastDisplayId = -1;

 private WindowManager wm;
 private FrameLayout root;
 private ImageView img;
 private TextView tip;
 private WindowManager.LayoutParams params;
 private Context appCtx;
 private int displayId = -1;
 private volatile boolean shown = false, polling = false;
 private int corner = 0, sizeIdx = 1;

 // 镜像宽（像素）：720 宽的虚拟屏缩到 300 宽，720p 的内容大字按钮都还看得清
 private static final int MW = 300;
 private static final int MH = MW * 1280 / 720;

 public boolean isShown(){ return shown && root != null && root.isAttachedToWindow(); }

 /** 开/关切换，通知栏按钮直接用这个（不需要 Activity 实例） */
 public boolean toggle(Context ctx){
  if(isShown()){ hide(); return false; }
  return lastDisplayId > 0 && show(ctx, lastDisplayId);
 }
 public boolean show(Context ctx, int displayId){
  this.displayId = displayId;
  if(displayId > 0) lastDisplayId = displayId;
  if(isShown()){ startPoll(); return true; }
  appCtx = ctx.getApplicationContext();
  try{
   wm = (WindowManager)appCtx.getSystemService(Context.WINDOW_SERVICE);
   root = new FrameLayout(appCtx);
   root.setBackgroundColor(Color.parseColor("#000000"));

   img = new ImageView(appCtx);
   img.setScaleType(ImageView.ScaleType.FIT_XY);
   img.setLayoutParams(new FrameLayout.LayoutParams(MW, MH));
   root.addView(img);

   tip = new TextView(appCtx);
   tip.setText("AGENT 工作区");
   tip.setTextSize(9);
   tip.setTextColor(Color.parseColor("#8be9b8"));
   tip.setBackgroundColor(Color.parseColor("#cc000000"));
   tip.setPadding(dp(4), dp(1), dp(4), dp(1));
   FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
     FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
   tp.gravity = Gravity.TOP | Gravity.LEFT;
   tip.setLayoutParams(tp);
   root.addView(tip);

   params = new WindowManager.LayoutParams(
     MW, MH,
     WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
     WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
       | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
       | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
     PixelFormat.OPAQUE);
   params.gravity = Gravity.TOP | Gravity.LEFT;
   // 内缩 24dp：不压住左右两侧的"侧滑返回"手势区（悬浮窗盖住边缘会让手势失效）
   params.x = dp(24);
   params.y = dp(70);

   wm.addView(root, params);
   shown = true;
   startPoll();
   return true;
  }catch(Throwable t){
   log("mirror show fail " + t);
   return false;
  }
 }

 /** 帧轮询：3fps 足够看清"在动"，又不会拖慢主线程 */
 private void startPoll(){
  if(polling) return;
  polling = true;
  Thread t = new Thread(() -> {
   while(polling && displayId > 0){
    try{
     byte[] png = OwnedDisplay.peek(displayId);
     if(png != null && png.length > 0){
      Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length);
      if(b != null){
       final Bitmap finalB = b;
       new Handler(Looper.getMainLooper()).post(() -> {
        try{ img.setImageBitmap(finalB); }catch(Throwable ignore){}
       });
      }
     }
     Thread.sleep(330);
    }catch(Throwable e){
     try{ Thread.sleep(800); }catch(Throwable ignore){}
    }
   }
  }, "mirror-poll");
  t.setDaemon(true);
  t.start();
 }

 /** 四角循环：左上→右上→左下→右下（默认左上，像画中画） */
 public void cycleCorner(){
  if(!isShown()) return;
  corner = (corner + 1) & 3;
  int scw = appCtx.getResources().getDisplayMetrics().widthPixels;
  int sch = appCtx.getResources().getDisplayMetrics().heightPixels;
  int inset = dp(24);
  int g = ((corner & 1) == 1 ? (Gravity.TOP | Gravity.RIGHT) : (Gravity.TOP | Gravity.LEFT));
  if(corner >= 2) g = ((corner & 1) == 1 ? (Gravity.BOTTOM | Gravity.RIGHT) : (Gravity.BOTTOM | Gravity.LEFT));
  params.gravity = g;
  params.x = inset;
  params.y = (corner >= 2 ? dp(40) : dp(70));
  try{ wm.updateViewLayout(root, params); }catch(Throwable t){}
 }
 /** 大小切换：小窗(240) / 中窗(300) / 大窗(420) —— 演示时切到大窗，模型也看得更清 */
 public void cycleSize(){
  if(!isShown()) return;
  sizeIdx = (sizeIdx + 1) % 3;
  int w = new int[]{240, 300, 420}[sizeIdx];
  int h = w * 1280 / 720;
  params.width = w; params.height = h;
  img.setLayoutParams(new FrameLayout.LayoutParams(w, h));
  try{ wm.updateViewLayout(root, params); }catch(Throwable t){}
 }

 public void hide(){
  polling = false;
  if(!shown) return;
  try{ wm.removeView(root); }catch(Throwable t){}
  shown = false;
  log("mirror hidden");
 }

 private int dp(int v){ return (int)(v * appCtx.getResources().getDisplayMetrics().density); }
 private static void log(String s){
  try{
   java.io.File f = App.f("shot-debug.txt");
   java.io.FileWriter fw = new java.io.FileWriter(f, true);
   fw.write(System.currentTimeMillis() + " " + s + "\n");
   fw.close();
  }catch(Throwable t){}
 }
}
