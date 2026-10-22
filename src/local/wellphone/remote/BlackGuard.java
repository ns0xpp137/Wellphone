package local.wellphone.remote;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** 防漏底黑幕：垫在系统镜像窗区域下方（或上方）的纯黑窗口。
 *  镜像窗 alpha=0.8，主屏内容会透进截图——黑幕把透出的内容换成纯黑，
 *  模型只看到虚拟屏内容（80% 亮度，可接受）。 */
public class BlackGuard {
 private static View0 win;
 private static class View0 extends FrameLayout {
  View0(Context c){ super(c); setBackgroundColor(Color.BLACK); }
 }
 private static WindowManager wm;
 private static View0 view;

 public static synchronized void show(Context ctx, int w, int h){
  try{
   if(view != null) return;
   wm = (WindowManager)ctx.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
   view = new View0(ctx.getApplicationContext());
   WindowManager.LayoutParams p = new WindowManager.LayoutParams(
     w, h,
     WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
     WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
       | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
       | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
     PixelFormat.OPAQUE);
   p.gravity = Gravity.TOP | Gravity.LEFT;
   p.x = 0; p.y = 0;
   wm.addView(view, p);
  }catch(Throwable t){}
 }

 public static synchronized void hide(){
  try{
   if(view != null && wm != null){ wm.removeView(view); }
  }catch(Throwable t){}
  view = null;
 }
}
