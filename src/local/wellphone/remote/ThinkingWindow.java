package local.wellphone.remote;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 思考窗：顶部任务输入框+发送 / 可拖拽 / 可折叠 / 带停止与接管按钮。
 *  全局单例——Activity 重建只重绑回调，绝不重复 addView（双悬浮窗 bug 的根源）。
 *  视图持 ApplicationContext，长存不泄漏 Activity。 */
public class ThinkingWindow {
 private static ThinkingWindow inst;
 public static ThinkingWindow get(){
  if(inst == null) inst = new ThinkingWindow();
  return inst;
 }
 private ThinkingWindow(){}

 public interface OnSendListener{ void onSend(String task); }

 private WindowManager wm;
 private LinearLayout root, inputRow, btnRow;
 private TextView header, body, statusLine;
 private ScrollView scroll;
 private EditText input;
 private Button stopBtn, takeBtn, backBtn, sendBtn;
 private Context appCtx;
 private OnSendListener sendListener;
 private View.OnClickListener backListener;
 public void setBackListener(View.OnClickListener l){ backListener = l; if(backBtn != null) backBtn.setOnClickListener(l); }
 private final StringBuilder buf = new StringBuilder();
 // 默认折叠：空闲时只留一条标题，别把 App 自己的按钮和记忆区盖住；开任务再自动展开
 private boolean shown = false, expanded = false;
 private static boolean userHidden = false;
 private WindowManager.LayoutParams params;
 private int startX, startY;
 private float downX, downY;
 private boolean dragging = false;

 public boolean isShown(){ return shown && root != null && root.isAttachedToWindow(); }
 public boolean isUserHidden(){ return userHidden; }
 /** 用户再次发起任务时调用：清掉"被收起"状态，让思考窗能重新显示 */
 public void clearUserHidden(){ userHidden = false; }

 public void setOnSendListener(OnSendListener l){ sendListener = l; }
 /** 通知栏把窗口拉起来时不知道按钮回调，事后补上 */
 /** 由外部补回调（通知栏拉起时用）。注意 stop/take 在 show() 里已按参数绑过。 */
 public void setControlListeners(View.OnClickListener onStop, View.OnClickListener onTake){
  try{
   if(stopBtn != null) stopBtn.setOnClickListener(onStop);
   if(takeBtn != null) takeBtn.setOnClickListener(onTake);
   if(backBtn != null) backBtn.setOnClickListener(backListener);
  }catch(Throwable t){}
 }

 /** 让出输入法：悬浮窗的 EditText 会一直占着 IME 的 served view，
  *  导致主界面输入框点不出键盘。主界面要输入前先调这个把它放开。 */
 public void releaseInput(){
  if(input == null) return;
  try{
   input.clearFocus();
   android.view.inputmethod.InputMethodManager imm =
     (android.view.inputmethod.InputMethodManager)appCtx.getSystemService(Context.INPUT_METHOD_SERVICE);
   if(imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
   if(wm != null && params != null){
    params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    wm.updateViewLayout(root, params);
   }
  }catch(Throwable t){}
 }

 public boolean show(Context ctx, View.OnClickListener onStop, View.OnClickListener onTake){
  appCtx = ctx.getApplicationContext();
  userHidden = false;
  // 已在显示：只刷新按钮回调（Activity 重建后旧回调指向死对象），不加第二个窗
  if(isShown()){
   try{
    if(stopBtn != null) stopBtn.setOnClickListener(onStop);
    if(takeBtn != null) takeBtn.setOnClickListener(onTake);
   if(backBtn != null) backBtn.setOnClickListener(backListener);
   }catch(Throwable t){}
   return true;
  }
  try{
   wm = (WindowManager)appCtx.getSystemService(Context.WINDOW_SERVICE);
   root = new LinearLayout(appCtx);
   root.setOrientation(LinearLayout.VERTICAL);
   // 圆角靠背景 drawable 画：WindowManager 给的窗口是矩形，圆角外保持透明就行
   root.setBackground(Ui.roundEdge(Ui.W_BG, Ui.W_EDGE, dp(16), dp(1)));
   int p = dp(6);
   root.setPadding(p, p, p, p);

   // ── 顶部：任务输入框 + 发送按钮 ──
   inputRow = new LinearLayout(appCtx);
   inputRow.setOrientation(LinearLayout.HORIZONTAL);
   input = new EditText(appCtx);
   input.setHint("输入任务…");
   input.setTextSize(11);
   input.setTextColor(Ui.W_TEXT);
   input.setHintTextColor(Ui.W_HINT);
   input.setBackground(Ui.roundEdge(Ui.W_PANEL, Ui.W_EDGE, dp(10), dp(1)));
   input.setPadding(dp(8), dp(2), dp(8), dp(2));
   input.setSingleLine(true);
   LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, dp(38), 1);
   input.setLayoutParams(ilp);
   sendBtn = new Button(appCtx);
   sendBtn.setText("发送");
   sendBtn.setTextSize(11);
   Ui.styleButton(sendBtn, Ui.ACCENT, Color.WHITE, dp(10));   // 主操作用图标橙
   LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(52), dp(38));
   slp.setMargins(dp(4), 0, 0, 0);
   sendBtn.setLayoutParams(slp);
   sendBtn.setOnClickListener(v -> doSend());
   inputRow.addView(input);
   inputRow.addView(sendBtn);
   root.addView(inputRow);

   // ── 头部：标题 + 折叠切换 + 拖拽 ──
   header = new TextView(appCtx);
   header.setText("≡ 思考流");
   header.setTextSize(11);
   header.setTextColor(Ui.ACCENT);
   header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
   header.setBackground(Ui.round(Ui.W_PANEL, dp(8)));
   header.setPadding(dp(8), dp(6), dp(8), dp(6));
   // 标题栏是主要拖拽把手：不能只 setOnClickListener（那样会吞掉触摸事件，整窗拖不动）。
   // 这里让它走和根视图同一套 drag 逻辑，松手时若没发生位移才当点击 → 折叠/展开。
   header.setOnTouchListener((v, ev) -> handleDrag(ev, true));
   root.addView(header);
   // 状态行：App 里那个"状态：…"同步到这里，免得出错还得切回 App 看
   statusLine = new TextView(appCtx);
   statusLine.setTextSize(11);
   statusLine.setTextColor(Ui.W_TEXT);
   statusLine.setBackground(Ui.round(Ui.W_PANEL, dp(8)));
   statusLine.setPadding(dp(8), dp(3), dp(8), dp(3));
   statusLine.setSingleLine(true);
   statusLine.setText("待机");
   root.addView(statusLine);

   body = new TextView(appCtx);
   body.setTextSize(10);
   body.setTextColor(Ui.W_TEXT);
   body.setLineSpacing(2, 1);
   body.setText("等待任务…");
   body.setPadding(dp(4), dp(4), dp(4), 0);
   scroll = new ScrollView(appCtx);
   scroll.addView(body);
   LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(220), dp(120));
   scroll.setLayoutParams(lp);
   root.addView(scroll);

   // ── 按钮行（展开时显示）──
   btnRow = new LinearLayout(appCtx);
   btnRow.setOrientation(LinearLayout.HORIZONTAL);
   stopBtn = new Button(appCtx);
   stopBtn.setText("停止"); stopBtn.setTextSize(11);
   Ui.styleButton(stopBtn, 0xFFB03A3A, Color.WHITE, dp(10));
   LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, dp(34), 1);
   b1.setMargins(0, dp(4), dp(4), 0);
   stopBtn.setLayoutParams(b1);
   stopBtn.setOnClickListener(onStop);
   takeBtn = new Button(appCtx);
   takeBtn.setText("接管"); takeBtn.setTextSize(11);
   Ui.styleButton(takeBtn, Ui.ACCENT_D, Color.WHITE, dp(10));
   LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, dp(34), 1);
   b2.setMargins(dp(4), dp(4), 0, 0);
   takeBtn.setLayoutParams(b2);
   takeBtn.setOnClickListener(onTake);
   backBtn = new Button(appCtx);
   backBtn.setText("返回后台"); backBtn.setTextSize(11);
   Ui.styleButton(backBtn, 0xFF0F6E56, Color.WHITE, dp(10));
   LinearLayout.LayoutParams b3 = new LinearLayout.LayoutParams(0, dp(34), 1);
   b3.setMargins(dp(4), dp(4), 0, 0);
   backBtn.setLayoutParams(b3);
   // ★ 这里必须立刻绑：之前只在 setControlListeners() 里绑，而那个方法只有
   //   "从通知栏拉起悬浮窗"时才调用 → 悬浮窗自己创建的返回后台按钮一直是死的。
   backBtn.setOnClickListener(v -> {
    View.OnClickListener l = backListener;
    if(l != null) l.onClick(v);
   });
   btnRow.addView(stopBtn); btnRow.addView(takeBtn); btnRow.addView(backBtn);
   root.addView(btnRow);

   // ── 小控制行：两个"先收起来"的开关 ──
   // 这两个窗的开关原本只在 App 里，但演示时经常要临时收掉某一个（挡住画面了、
   // 或者要让观众看一眼主屏），切回 App 再点太笨——直接放悬浮窗上。
   LinearLayout hideRow = new LinearLayout(appCtx);
   hideRow.setOrientation(LinearLayout.HORIZONTAL);
   final Button btnHideMirror = new Button(appCtx);
   btnHideMirror.setTextSize(10);
   Ui.styleButton(btnHideMirror, Ui.W_PANEL, Ui.W_SUB, dp(8));
   LinearLayout.LayoutParams h1 = new LinearLayout.LayoutParams(0, dp(28), 1);
   h1.setMargins(0, dp(4), dp(4), 0);
   btnHideMirror.setLayoutParams(h1);
   btnHideMirror.setText(MirrorWindow.get().isShown() ? "隐藏镜像窗" : "显示镜像窗");
   btnHideMirror.setOnClickListener(v -> {
    boolean on = MirrorWindow.get().toggle(appCtx);
    btnHideMirror.setText(on ? "隐藏镜像窗" : "显示镜像窗");
   });
   Button btnHideSelf = new Button(appCtx);
   btnHideSelf.setText("收起悬浮窗"); btnHideSelf.setTextSize(10);
   Ui.styleButton(btnHideSelf, Ui.W_PANEL, Ui.W_SUB, dp(8));
   LinearLayout.LayoutParams h2 = new LinearLayout.LayoutParams(0, dp(28), 1);
   h2.setMargins(dp(4), dp(4), 0, 0);
   btnHideSelf.setLayoutParams(h2);
   btnHideSelf.setOnClickListener(v -> hide());
   hideRow.addView(btnHideMirror); hideRow.addView(btnHideSelf);
   root.addView(hideRow);

   params = new WindowManager.LayoutParams(
     dp(232), WindowManager.LayoutParams.WRAP_CONTENT,
     WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
     WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
       | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
     PixelFormat.TRANSLUCENT);
   params.gravity = Gravity.TOP | Gravity.LEFT;
   // 内缩 28dp：悬浮窗压住屏幕左右沿会让"侧滑返回"手势失效（手势区被窗口吃掉了）
   params.x = dp(28);
   // 贴底放：中部会盖住自己 App 的按钮行。按屏高算，离底部留 60dp 给导航条
   int sh = appCtx.getResources().getDisplayMetrics().heightPixels;
   params.y = Math.max(dp(120), sh - dp(230) - dp(60));

   // 拖拽 + 点击折叠/展开（输入框/按钮区域不触发）
   root.setOnTouchListener((v, ev) -> handleDrag(ev, false));

   // 输入框获得焦点时让窗口可获焦（否则 IME 不弹）
   input.setOnFocusChangeListener((v, hasFocus) -> {
    try{
     if(hasFocus){
      params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
     }else{
      params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
     }
     wm.updateViewLayout(root, params);
    }catch(Throwable t){}
   });

   wm.addView(root, params);
   shown = true;
   applyLayout();
   startWatchdog();
   return true;
  }catch(Throwable t){ tlog("show fail " + t); return false; }
 }

 /** 自愈：不管被谁摘掉（Activity 重建/系统策略/任务切前台），只要用户没主动隐藏就贴回来 */
 private void startWatchdog(){
  if(watchdog != null && watchdog.isAlive()) return;
  watchdog = new Thread(() -> {
   while(!userHidden){
    try{
     Thread.sleep(2000);
     if(userHidden) break;
     if(root == null || wm == null) continue;
     if(!root.isAttachedToWindow()){
      tlog("watchdog 重新贴窗");
      new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
       try{
        if(userHidden) return;
        wm.addView(root, params);
        shown = true;
       }catch(Throwable t){ tlog("watchdog add fail " + t); }
      });
     }
    }catch(Throwable t){}
   }
  }, "think-watchdog");
  watchdog.setDaemon(true);
  watchdog.start();
 }
 private Thread watchdog;
 private static void tlog(String s){
  try{
   java.io.File f = App.f("think-log.txt");
   java.io.FileWriter fw = new java.io.FileWriter(f, true);
   fw.write(System.currentTimeMillis() + " " + s + "\n");
   fw.close();
  }catch(Throwable t){}
 }

 private void doSend(){
  tlog("doSend x=" + params.x + " y=" + params.y + " flags=" + params.flags);
  String t = input.getText().toString().trim();
  if(t.isEmpty()) return;
  if(sendListener != null) sendListener.onSend(t);
  input.setText("");
  input.clearFocus();
  android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)appCtx.getSystemService(Context.INPUT_METHOD_SERVICE);
  if(imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
  params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
  try{ wm.updateViewLayout(root, params); }catch(Throwable error){ tlog("doSend update fail " + error); }
  tlog("doSend done attached=" + (root != null && root.isAttachedToWindow()));
 }
 /** 被系统摘掉时留痕，方便定位元凶 */
 private void noteDetach(){ tlog("detached x=" + params.x + " y=" + params.y); }

 /** 外部控制展开/折叠（任务开始时展开，回到空闲被记忆区让路时折叠） */
 public void setExpanded(boolean v){
  if(expanded == v) return;
  expanded = v;
  applyLayout();
 }
 /** 拖拽 + （可选）点击判定。tapToToggle=true 时，松手且没位移就当一次点击。 */
 private boolean handleDrag(MotionEvent ev, boolean tapToToggle){
  switch(ev.getAction()){
   case MotionEvent.ACTION_DOWN:
    downX = ev.getRawX(); downY = ev.getRawY();
    startX = params.x; startY = params.y;
    dragging = false;
    return true;
   case MotionEvent.ACTION_MOVE:
    float dx = ev.getRawX() - downX, dy = ev.getRawY() - downY;
    if(Math.abs(dx) > 12 || Math.abs(dy) > 12) dragging = true;
    if(dragging){
     // 拖动期间停掉镜像窗的帧刷新（两个 overlay 同时更新会被合成抖成闪烁）
     MirrorWindow.get().setPollPaused(true);
     // 别拖出屏幕外，否则用户找不回来
     int sw = appCtx.getResources().getDisplayMetrics().widthPixels;
     int sh = appCtx.getResources().getDisplayMetrics().heightPixels;
     params.x = Math.max(-dp(40), Math.min(sw - dp(60), startX + (int)dx));
     params.y = Math.max(0, Math.min(sh - dp(60), startY + (int)dy));
     try{ wm.updateViewLayout(root, params); }catch(Throwable t){}
    }
    return true;
   case MotionEvent.ACTION_UP:
   case MotionEvent.ACTION_CANCEL:
    if(tapToToggle && !dragging) toggle();
    dragging = false;
    MirrorWindow.get().setPollPaused(false);   // 松手立刻恢复
    return true;
  }
  return false;
 }
 private void toggle(){
  expanded = !expanded;
  applyLayout();
 }
 private void applyLayout(){
  int vis = expanded ? View.VISIBLE : View.GONE;
  scroll.setVisibility(vis);
  btnRow.setVisibility(vis);
  header.setText(expanded ? "≡ 思考流（点击折叠）" : "▶ 思考流（点击展开）");
  // 高度交给内容：写死高度会在"停止/接管"下面留一块空白，点它还会误触折叠
  params.height = WindowManager.LayoutParams.WRAP_CONTENT;
  try{ wm.updateViewLayout(root, params); }catch(Throwable t){}
 }

 public void hide(){
  userHidden = true;
  if(!shown) return;
  try{ wm.removeView(root); }catch(Throwable t){}
  shown = false;
 }

 // Window-specific screenshots do not detach this overlay.
 public void setCaptureMode(boolean capture){}
 /** 换个标题标签（比如"监控中 · 看你的屏幕"） */
 public void setStepLabel(String s){
  if(!shown) return;
  try{ header.setText(s); }catch(Throwable t){}
 }
 /** 把 App 的状态文字同步显示到悬浮窗（思考流上面） */
 public void setStatusText(String s){
  if(s == null) return;
  try{
   statusLine.setText(s);
   statusLine.setTextColor(s.contains("出错") || s.contains("失败") || s.contains("需要你")
     ? Color.parseColor("#ff9c9c") : Color.parseColor("#dddddd"));
   if(shown) try{ body.requestLayout(); }catch(Throwable ig){}
  }catch(Throwable t){}
 }
 public void step(String s){
  if(!shown) return;
  try{ header.setText("≡ " + s); }catch(Throwable t){}
 }
 public void append(String s){
  buf.append(s);
  if(buf.length() > 8000) buf.delete(0, 5000);
  final String t = render(buf.toString());
  if(!shown) return;
  try{
   body.setText(t);
   scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
  }catch(Throwable t2){}
 }

 /** 把模型的原始 token 流渲染成人能看的样子。
  *  原始流是这样的：`<answer>do(action="Tap", element=[318,154])</answer>`，
  *  直接把 XML 摊在用户面前既难看也没信息量 —— 动作日志在下面的「动作:」行里已经有了。
  *  这里把 answer 块压成一句「▸ 执行动作」，只保留模型真正说的话。
  *  每次都从累积原文整体重算（文本很短，比处理跨 chunk 的标签边界可靠得多）。 */
 static String render(String rawText){
  StringBuilder out = new StringBuilder();
  int i = 0;
  while(i < rawText.length()){
   int a = rawText.indexOf("<answer>", i);
   if(a < 0){ out.append(stripTags(rawText.substring(i))); break; }
   out.append(stripTags(rawText.substring(i, a)));
   int b = rawText.indexOf("</answer>", a);
   if(b < 0) break;                                  // 动作还在流式输出中，先不占位
   out.append("\n");                                 // 动作本身由 Agent 以人话另行推入
   i = b + "</answer>".length();
  }
  // 连续空行压成一行，避免流式每步堆出一堆空白
  return out.toString().replaceAll("\n{3,}", "\n\n");
 }
 private static String stripTags(String s){
  return s.replace("<think>","").replace("</think>","").replace("\r","");
 }
 public void reset(String h){
  buf.setLength(0);
  append(h);
 }
 private int dp(int v){ return (int)(v * appCtx.getResources().getDisplayMetrics().density); }
}
