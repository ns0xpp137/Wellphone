package local.wellphone.remote;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

/** 悬浮窗/镜像窗共用的样式工具。
 *
 *  为什么单独一个文件：这些窗口跑在 WindowManager 里，没有 Activity 也没法用 XML 布局，
 *  样式只能靠代码画。两个窗都要用同一套，放一起才不会各画各的。
 *
 *  设计延续 App 图标：橙 + 深灰卡 + 大圆角。全部代码构造 drawable，不引任何图片资源。 */
public class Ui {
 /** 窗口是深色的（浮在别人 App 上面，浅色会晃眼），所以另配一套深色常量 */
 public static final int W_BG     = 0xFF25211D;   // 窗底
 public static final int W_PANEL  = 0xFF322C27;   // 面板/输入框
 public static final int W_EDGE   = 0xFF453C34;   // 描边
 public static final int W_TEXT   = 0xFFF5EFE8;
 public static final int W_SUB    = 0xFFB5A798;
 public static final int W_HINT   = 0xFF8A7F72;
 /** 图标橙，只用在"主操作"和标题上，别到处抹 */
 public static final int ACCENT   = 0xFFF97316;
 public static final int ACCENT_D = 0xFFC2410C;

 public static int dp(android.content.Context c, int v){
  return (int)(v * c.getResources().getDisplayMetrics().density + 0.5f);
 }
 private static int darker(int c){
  int r=(int)(((c>>16)&0xff)*0.86), g=(int)(((c>>8)&0xff)*0.86), b=(int)((c&0xff)*0.86);
  return 0xFF000000 | (r<<16) | (g<<8) | b;
 }
 public static GradientDrawable round(int fill, float r){
  GradientDrawable d = new GradientDrawable();
  d.setColor(fill); d.setCornerRadius(r); return d;
 }
 public static GradientDrawable roundEdge(int fill, int stroke, float r, int w){
  GradientDrawable d = new GradientDrawable();
  d.setColor(fill); d.setCornerRadius(r); d.setStroke(w, stroke); return d;
 }
 /** 圆角按钮 + 按下压暗。悬浮窗上按下反馈尤其重要——手指点下去必须立刻有反应。 */
 public static StateListDrawable pill(int normal, float r){
  StateListDrawable s = new StateListDrawable();
  s.addState(new int[]{android.R.attr.state_pressed}, round(darker(normal), r));
  s.addState(new int[]{}, round(normal, r));
  return s;
 }
 /** 把 Button 收拾成统一的圆角样式（去掉默认的方块背景和抬升阴影） */
 public static void styleButton(android.widget.Button b, int bg, int textColor, float radius){
  b.setBackground(pill(bg, radius));
  b.setTextColor(textColor);
  b.setAllCaps(false);
  try{ b.setMinHeight(0); b.setMinimumHeight(0); }catch(Throwable ig){}
  try{ b.setStateListAnimator(null); }catch(Throwable ig){}   // 去默认抬升阴影
  b.setPadding(4, 0, 4, 0);
 }
}
