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
  // ★ lastDisplayId 是静态的，App 一重启就归零 → 关掉之后就再也点不开了。
  //   开的时候回退到"当前副屏号"，拿不到才用记忆值。
  int did = lastDisplayId;
  if(did <= 0) did = OwnedDisplay.id;
  return did > 0 && show(ctx, did);
 }
 public boolean show(Context ctx, int displayId){
  this.displayId = displayId;
  if(displayId > 0) lastDisplayId = displayId;
  if(isShown()){ startPoll(); return true; }
  appCtx = ctx.getApplicationContext();
  // ★ 防御双窗口：Activity 重建时会再次走到 show()。如果上一轮的窗口还挂在屏幕上，
  //   这里不先摘掉就会 addView 出第二个镜像窗，两个窗口叠在一起互相抢合成 ——
  //   用户看到的"一直在闪"很可能就是这个（而不是刷新频率的问题）。
  try{
   if(root != null && root.isAttachedToWindow()){
    ((WindowManager)appCtx.getSystemService(Context.WINDOW_SERVICE)).removeView(root);
   }
  }catch(Throwable ig){}
  root = null;
  // ★ overlay 系统副屏时，Android 会在主屏右上角画一个关不掉的「叠加视图 #1」预览窗
  //   （TYPE_DISPLAY_OVERLAY，层级 2026；我们的 APPLICATION_OVERLAY 是 2038，压得住它）。
  //   默认它和镜像窗并排 = 同一个画面看两遍。这里把镜像窗放到右上角、用大窗并贴边，
  //   让它**整个罩住**系统预览窗 —— 视觉上就只剩我们这个能开关、能拖、能缩放的窗。
  if(OwnedDisplay.overlayMode){ corner = 1; sizeIdx = 2; }
  try{
   wm = (WindowManager)appCtx.getSystemService(Context.WINDOW_SERVICE);
   root = new FrameLayout(appCtx);
   // 背景保持透明：圆角由图片自己裁（否则整块黑底会盖掉圆角，看着还是个方块）
   root.setBackgroundColor(Color.TRANSPARENT);

   img = new ImageView(appCtx);
   img.setScaleType(ImageView.ScaleType.FIT_XY);
   img.setLayoutParams(new FrameLayout.LayoutParams(MW, MH));
   final float imgR = dp(14);
   img.setOutlineProvider(new android.view.ViewOutlineProvider(){
    public void getOutline(android.view.View v, android.graphics.Outline o){
     o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), imgR);
    }
   });
   img.setClipToOutline(true);
   root.addView(img);

   tip = new TextView(appCtx);
   tip.setText("AGENT 工作区");
   tip.setTextSize(9);
   tip.setTextColor(Ui.ACCENT);
   tip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
   tip.setBackground(Ui.round(0xCC25211D, dp(6)));
   tip.setPadding(dp(6), dp(2), dp(6), dp(2));
   FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
     FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
   tp.gravity = Gravity.TOP | Gravity.LEFT;
   tp.setMargins(dp(4), dp(4), 0, 0);
   tip.setLayoutParams(tp);
   root.addView(tip);

   // ★ 不要再加一层描边 View：它是 MATCH_PARENT 的透明层，每次图片重绘都要跟着重绘一遍，
   //   实测就是它把窗口抖成闪烁。圆角已经由图片的 clipToOutline 提供，够了。

   int w0 = new int[]{240, 300, 420}[sizeIdx];
   int h0 = w0 * 1280 / 720;
   img.setLayoutParams(new FrameLayout.LayoutParams(w0, h0));
   params = new WindowManager.LayoutParams(
     w0, h0,
     WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
     WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
       | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
       | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
     PixelFormat.OPAQUE);
   // 内缩 24dp：不压住左右两侧的"侧滑返回"手势区（悬浮窗盖住边缘会让手势失效）。
   // overlay 模式例外：必须贴到右上角才能把系统那个预览窗整个盖住（dp(2) 几乎不留缝）。
   int g = ((corner & 1) == 1 ? (Gravity.TOP | Gravity.RIGHT) : (Gravity.TOP | Gravity.LEFT));
   if(corner >= 2) g = ((corner & 1) == 1 ? (Gravity.BOTTOM | Gravity.RIGHT) : (Gravity.BOTTOM | Gravity.LEFT));
   params.gravity = g;
   if(OwnedDisplay.overlayMode){ params.x = dp(2); params.y = dp(2); }
   else { params.x = dp(24); params.y = (corner >= 2 ? dp(40) : dp(70)); }

   wm.addView(root, params);
   shown = true;
   coverSystemPreview();   // overlay 副屏：挪过去把 Android 那个预览窗盖住
   startPoll();
   return true;
  }catch(Throwable t){
   log("mirror show fail " + t);
   return false;
  }
 }

 /** ★ 拖悬浮窗时要临时停帧：两个 overlay 窗口同时高频更新，系统合成会把镜像窗抖成闪烁。
  *  松手即恢复——少刷几帧没人看得出来，闪才看得出来。 */
 public volatile boolean pollPaused = false;
 public void setPollPaused(boolean p){ pollPaused = p; }

 private byte[] lastShown = null;
 /** 两帧是不是同一张：先比长度，再抽头尾各若干字节。
  *  screencap + JPEG 是确定性编码，画面没动时字节完全一致，靠这个能省掉绝大多数重绘。 */
 private static boolean sameFrame(byte[] a, byte[] b){
  if(a == null || b == null) return false;
  if(a.length != b.length) return false;
  int n = Math.min(48, a.length);
  for(int i = 0; i < n; i++) if(a[i] != b[i]) return false;
  for(int i = 1; i <= n; i++) if(a[a.length - i] != b[b.length - i]) return false;
  return true;
 }

 /** 帧轮询：3fps 足够看清"在动"，又不会拖慢主线程 */
 private void startPoll(){
  if(polling) return;
  polling = true;
  Thread t = new Thread(() -> {
   while(polling && displayId > 0){
    try{
     if(pollPaused){ Thread.sleep(120); continue; }
     byte[] png = OwnedDisplay.peek(displayId);
     // ★ 画面没变就别刷。每帧都 setImageBitmap 会让 ImageView 反复重绘，
     //   连续重绘在悬浮窗上就是肉眼可见的闪烁（不动手机也闪，就是这个原因）。
     if(png != null && png.length > 0 && !sameFrame(png, lastShown)){
      lastShown = png;
      Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length);
      if(b != null){
       final Bitmap finalB = b;
       new Handler(Looper.getMainLooper()).post(() -> {
        try{ img.setImageBitmap(finalB); }catch(Throwable ignore){}
       });
      }
     }
     Thread.sleep(500);   // 2fps：再快就是白烧 CPU，还更容易和别的 overlay 抢合成
    }catch(Throwable e){
     try{ Thread.sleep(800); }catch(Throwable ignore){}
    }
   }
  }, "mirror-poll");
  t.setDaemon(true);
  t.start();
 }

 /** overlay 副屏时，Android 会在主屏画一个关不掉的「叠加视图 #N」预览窗（ty=DISPLAY_OVERLAY，
  *  层级 2026，比我们的 APPLICATION_OVERLAY=2038 低）。它的位置还不固定（实测左上/右上都出现过）。
  *  所以直接去 dumpsys 里问出它在哪，把镜像窗挪过去、放大到能整个罩住它 —— 视觉上只剩一个窗。 */
 private void coverSystemPreview(){
  if(!OwnedDisplay.overlayMode) return;
  new Thread(() -> {
   int[] p = findOverlayPreview();
   if(p == null) return;
   int w = Math.max(420, p[2]);
   int h = w * 1280 / 720;
   if(h < p[3]){ h = p[3]; w = h * 720 / 1280; }   // 高度也要够，别露出下半截
   final int fw = w, fh = h;
   final int fx = Math.max(0, p[0] - dp(2)), fy = Math.max(0, p[1] - dp(2));
   new Handler(Looper.getMainLooper()).post(() -> {
    try{
     params.gravity = Gravity.TOP | Gravity.LEFT;
     params.x = fx; params.y = fy;
     params.width = fw; params.height = fh;
     img.setLayoutParams(new FrameLayout.LayoutParams(fw, fh));
     wm.updateViewLayout(root, params);
    }catch(Throwable t){}
   });
  },"mirror-cover").start();
 }
 /** 从 dumpsys window 里解出系统预览窗的 {x, y, w, h} */
 private static int[] findOverlayPreview(){
  try{
   String out = ShellChannel.raw("dumpsys window windows 2>/dev/null", 10000);
   if(out == null) return null;
   for(String line : out.split("\n")){
    if(!line.contains("ty=DISPLAY_OVERLAY") || !line.contains("alpha=0.8")) continue;
    int a = line.indexOf("mAttrs={(");
    if(a < 0) continue;
    int b = line.indexOf(')', a + 9), c = b < 0 ? -1 : line.indexOf(')', b + 1);
    if(b < 0 || c < 0) continue;
    String[] xy = line.substring(a + 9, b).split(",");
    String[] wh = line.substring(b + 2, c).split("x");
    return new int[]{ Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim()),
                      Integer.parseInt(wh[0].trim()), Integer.parseInt(wh[1].trim()) };
   }
  }catch(Throwable t){}
  return null;
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
