package local.wellphone.remote;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import android.accessibilityservice.GestureDescription;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseArray;
import android.view.accessibility.*;
import java.io.*;
import java.util.*;

/** 虚拟屏的眼睛与手：截图 / 节点点击 / 文本写入 / 剪贴板。全部手机内完成。 */
public class AgentService extends AccessibilityService {
 private static final String TAG = "WellphoneAgent";
 public static AgentService instance;

 public void onServiceConnected(){
  instance = this; App.ctx = getApplicationContext();
  Log.i(TAG,"service ready");
 }
 public void onDestroy(){ instance = null; super.onDestroy(); }
 public void onAccessibilityEvent(AccessibilityEvent e){
  if(App.ctx == null) App.ctx = getApplicationContext();
 // 用户一旦点到悬浮窗以外的地方，悬浮窗必须立刻交出输入法——
  // 否则它一直占着 IME 的 served view，主屏上别的输入框全都点不出键盘。
  int t = e.getEventType();
  if(t != AccessibilityEvent.TYPE_VIEW_FOCUSED && t != AccessibilityEvent.TYPE_VIEW_CLICKED) return;
  String pkg = e.getPackageName() == null ? "" : e.getPackageName().toString();
  if(!pkg.isEmpty() && !pkg.startsWith("local.wellphone")) ThinkingWindow.get().releaseInput();
  // 示教：用户点了哪个元素，把它的文字/资源 id/坐标记下来，之后写进记忆
  if(demoCapture && t == AccessibilityEvent.TYPE_VIEW_CLICKED){
   AccessibilityNodeInfo src = e.getSource();
   if(src != null){
    String txt = src.getText() == null ? "" : src.getText().toString();
    String dsc = src.getContentDescription() == null ? "" : src.getContentDescription().toString();
    String rid = src.getViewIdResourceName();
    Rect bb = new Rect(); src.getBoundsInScreen(bb);
    StringBuilder sb = new StringBuilder();
    if(!txt.isEmpty()) sb.append("\"").append(txt).append("\"");
    else if(!dsc.isEmpty()) sb.append("[").append(dsc).append("]");
    if(rid != null) sb.append(" (id=").append(rid.substring(rid.lastIndexOf('/') + 1)).append(")");
    sb.append(" @").append(bb.centerX()).append(",").append(bb.centerY());
    demoResult = sb.toString();
   }else{
    demoResult = "(没读到具体控件)";
   }
   demoPage = pkg;
   demoCapture = false;
   Log.i(TAG, "示范已记录 " + demoResult);
  }
 }
 public void onInterrupt(){}

 public interface Callback<T>{ void onResult(T v); }

 // Only capture a specific application window on the requested secondary display.
 public volatile String captureError = "";
 private volatile String targetPackage = "";
 public void setTargetPackage(String pkg){ targetPackage = pkg == null ? "" : pkg; }
 public boolean hasTargetWindow(int displayId, String pkg){ return targetWindow(displayId, pkg) != null; }
 private AccessibilityWindowInfo targetWindow(int displayId, String pkg){
  if(displayId <= 0 || pkg == null || pkg.isEmpty()) return null;
  AccessibilityWindowInfo best = null;
  for(AccessibilityWindowInfo w : windowsOn(displayId)){
   if(w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
   AccessibilityNodeInfo r = w.getRoot();
   shotDebug("target-window display="+displayId+" id="+w.getId()+" root="+(r==null?"null":r.getPackageName()));
   if(r != null && pkg.contentEquals(r.getPackageName() == null ? "" : r.getPackageName())
      && (best == null || w.getLayer() > best.getLayer())) best = w;
  }
  return best;
 }
 public void screenshot(final int displayId, final int width, final int height, final Callback<byte[]> cb){
  new Thread(() -> {
   byte[] bytes = OwnedDisplay.capture(displayId);
   if(bytes==null) captureError="无法读取自建虚拟屏画面，请检查 Shell 通道";
   else{
    captureError="";
    try(FileOutputStream out=new FileOutputStream(new File(getExternalFilesDir(null),"verified-window.jpg"))){out.write(bytes);}catch(Exception ignored){}
    shotDebug("owned-surface OK display="+displayId+" bytes="+bytes.length);
   }
   cb.onResult(bytes);
  },"capture-owned").start();
 }

 private byte[] encodeShot(Bitmap out){
  try{
   ByteArrayOutputStream bos = new ByteArrayOutputStream();
   out.compress(Bitmap.CompressFormat.PNG, 100, bos);
   return bos.toByteArray();
  }catch(Throwable t){ return null; }
 }
 /** 调试：截图路线/尺寸落盘（logcat 在本 ROM 读不到） */
 static void shotDebug(String s){
  try{
   java.io.File f = App.f("shot-debug.txt");
   java.io.FileWriter fw = new java.io.FileWriter(f, true);
   fw.write(System.currentTimeMillis() + " " + s + "\n");
   fw.close();
  }catch(Throwable t){}
 }

 // ---------- 手：手势注入（藏树 App 的点击/长按兜底）----------
 public boolean gestureTap(int x, int y){
  return gesture(x, y, 60);
 }
 public boolean gestureLongPress(int x, int y){
  return gesture(x, y, 600);
 }
 private boolean gesture(int x, int y, long durMs){
  try{
   android.graphics.Path p = new android.graphics.Path();
   p.moveTo(x, y);
   GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, durMs);
   GestureDescription g = new GestureDescription.Builder().addStroke(s).build();
   return dispatchGesture(g, null, null);
  }catch(Throwable t){ return false; }
 }

 // ---------- 窗口枚举 ----------
 private List<AccessibilityWindowInfo> windowsOn(int displayId){
  if(displayId <= 0) return new ArrayList<AccessibilityWindowInfo>();
  SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
  List<AccessibilityWindowInfo> w = all == null ? null : all.get(displayId);
  return w == null ? new ArrayList<AccessibilityWindowInfo>() : w;
 }

 /** 虚拟屏上当前最前面的应用是哪个（按窗口层级取最高的那个）。
  *  跨应用任务靠它自动切换上下文：模型打开京东后，节点清单/标注/直达页自动全变成京东的，
  *  不需要额外的切换代码，也不再依赖写死的 currentPkg。 */
 /** 抓用户主屏（display 0）。
  *  注意：这是"协同模式"才会用到的能力——有些需求（相机取景、看直播）必须看用户正在看的画面，
  *  虚拟屏里没有。只在用户自己打开开关、并且任务明确需要时才调用，并且界面上要有明显提示。 */
 public void captureMain(final Callback<byte[]> cb){
  try{
   final Executor ex = Executors.newSingleThreadExecutor();
   takeScreenshot(0, ex, new TakeScreenshotCallback(){
    public void onSuccess(ScreenshotResult r){
     HardwareBuffer hb = null;
     try{
      hb = r.getHardwareBuffer();
      if(hb == null){ captureError = "主屏截图：拿不到画面缓冲"; cb.onResult(null); return; }
      Bitmap bmp = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
      if(bmp == null){ captureError = "主屏截图：解码失败"; cb.onResult(null); return; }
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      bmp.compress(Bitmap.CompressFormat.JPEG, 82, o);
      byte[] out = o.toByteArray();
      try(java.io.FileOutputStream f = new FileOutputStream(
          new File(getExternalFilesDir(null), "main-screen.jpg"))){ f.write(out); }catch(Throwable ig){}
      captureError = "";
      cb.onResult(out);
     }catch(Throwable t){
      captureError = "主屏截图失败 " + t;
      cb.onResult(null);
     }finally{
      try{ if(hb != null) hb.close(); }catch(Throwable ig){}
     }
    }
    public void onFailure(int code){
     captureError = "主屏截图失败 code=" + code;
     cb.onResult(null);
    }
   });
  }catch(Throwable t){
   captureError = "主屏截图不可用 " + t;
   cb.onResult(null);
  }
 }

 public String focusedPackage(int displayId){
  AccessibilityWindowInfo best = null;
  for(AccessibilityWindowInfo w : windowsOn(displayId)){
   if(w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
   AccessibilityNodeInfo r = w.getRoot();
   if(r == null || r.getPackageName() == null) continue;
   if(best == null || w.getLayer() > best.getLayer()) best = w;
  }
  if(best == null) return "";
  AccessibilityNodeInfo r = best.getRoot();
  return String.valueOf(r.getPackageName());
 }

 public String describeUi(int displayId, int width, int height){
  StringBuilder out=new StringBuilder();
  for(AccessibilityWindowInfo w:windowsOn(displayId)){
   AccessibilityNodeInfo root=w.getRoot();
   if(root!=null && targetPackage.contentEquals(root.getPackageName()==null?"":root.getPackageName())) describeNode(root,out,width,height,0);
  }
  return out.toString();
 }
 /** 收集图上可交互元素。只收可点/可编辑的，且过滤掉过大或互相重叠的，
  *  否则聊天列表里每一行都能点，画出来就是一屏乱框。
  *  返回 String[]{label, cx, cy, left, top, right, bottom} */
 // 缩略图宽度固定 720；虚拟屏真实宽度由调用方通过 Agent 的 VW 传进来
 public static float thumbScale = 1f;
 public List<String[]> collectTargets(int displayId, int width, int height){
  List<String[]> out = new ArrayList<String[]>();
  StringBuilder all = new StringBuilder();
  for(AccessibilityWindowInfo w : windowsOn(displayId)){
   AccessibilityNodeInfo root = w.getRoot();
   if(root != null && targetPackage.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())){
    collectTarget(root, out, width, height, 0);
    dumpAll(root, all, 0);   // 全量转储，用来查"某个按钮为什么没被收录"
   }
  }
  try{
   java.io.File f = new java.io.File(getExternalFilesDir(null), "ui-all.txt");
   java.io.FileWriter fw = new java.io.FileWriter(f);
   fw.write(all.toString()); fw.close();
  }catch(Throwable t){}
  return out;
 }
 private void dumpAll(AccessibilityNodeInfo n, StringBuilder sb, int depth){
  if(depth > 30 || sb.length() > 30000) return;
  String text = n.getText() == null ? "" : n.getText().toString();
  String desc = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
  String rid = n.getViewIdResourceName();
  Rect b = new Rect(); n.getBoundsInScreen(b);
  if(n.isVisibleToUser())
   for(int k = 0; k < depth && k < 20; k++) sb.append(' ');
   sb.append(n.getClassName())
     .append(" t=[").append(text.replace("\n"," ")).append("] d=[").append(desc).append("]")
     .append(" id=").append(rid).append(" b=").append(b.toShortString())
     .append(" click=").append(n.isClickable()).append(" vis=").append(n.isVisibleToUser()).append("\n");
  for(int i = 0; i < n.getChildCount(); i++){
   AccessibilityNodeInfo c = n.getChild(i);
   if(c != null) dumpAll(c, sb, depth + 1);
  }
 }
 private void collectTarget(AccessibilityNodeInfo n, List<String[]> out, int width, int height, int depth){
  if(depth > 30 || out.size() >= 22) return;
  String text = n.getText() == null ? "" : n.getText().toString();
  String desc = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
  Rect b = new Rect(); n.getBoundsInScreen(b);
  String label = (text.isEmpty() ? desc : text).replace("\n", " ");
  if(label.length() > 40) label = label.substring(0, 40);
  boolean leaf = n.getChildCount() == 0;
  // 纯图标按钮（加号/返回/搜索）既没有 text 也没有 contentDescription，
  // 只按"有文字"筛的话它们全被漏掉，模型就只能猜坐标 → 点歪。
  // 所以：可点/可编辑/有文字的叶子节点，三类都收。
  if(n.isVisibleToUser() && !b.isEmpty()
     && (n.isClickable() || n.isEditable() || (leaf && !label.isEmpty()))){
   // 超过半个屏幕的元素大概率是容器，画框没意义
   if((long)b.width() * b.height() * 4 < (long)width * height * 3
      && !overlapsExisting(out, b)){
    if(label.trim().isEmpty()){
     // 没文字就用资源 id 的短名（fab_add / iv_back），总比让模型瞎猜强
     String rid = n.getViewIdResourceName();
     int slash = rid == null ? -1 : rid.lastIndexOf('/');
     label = rid == null ? "按钮" : (slash >= 0 ? rid.substring(slash + 1) : rid);
    }
    out.add(new String[]{ label,
      String.valueOf(b.centerX() * 1000 / width), String.valueOf(b.centerY() * 1000 / height),
      String.valueOf(b.left), String.valueOf(b.top), String.valueOf(b.right), String.valueOf(b.bottom) });
   }
  }
  for(int i = 0; i < n.getChildCount(); i++){
   AccessibilityNodeInfo c = n.getChild(i);
   if(c != null) collectTarget(c, out, width, height, depth + 1);
  }
 }
 private boolean overlapsExisting(List<String[]> out, Rect b){
  long area = (long)b.width() * b.height();
  if(area <= 0) return true;
  for(String[] e : out){
   try{
    int l = Integer.parseInt(e[3]), t = Integer.parseInt(e[4]);
    int r = Integer.parseInt(e[5]), bm = Integer.parseInt(e[6]);
    int ow = Math.min(b.right, r) - Math.max(b.left, l);
    int oh = Math.min(b.bottom, bm) - Math.max(b.top, t);
    if(ow > 0 && oh > 0 && (long)ow * oh * 3 > area * 2) return true;
   }catch(Throwable ig){}
  }
  return false;
 }

 private void describeNode(AccessibilityNodeInfo n,StringBuilder out,int width,int height,int depth){
  if(depth>35 || out.length()>6500) return;
  String text=n.getText()==null?"":n.getText().toString();
  String desc=n.getContentDescription()==null?"":n.getContentDescription().toString();
  android.graphics.Rect bounds=new android.graphics.Rect(); n.getBoundsInScreen(bounds);
  if(n.isVisibleToUser() && !bounds.isEmpty() && (!text.isEmpty() || !desc.isEmpty() || n.isEditable())){
   String label=(text.isEmpty()?desc:text).replace("\n"," ");
   if(label.length()>100) label=label.substring(0,100);
   out.append("label=").append(label).append(" center=[").append(bounds.centerX()*1000/width).append(",").append(bounds.centerY()*1000/height).append("] editable=").append(n.isEditable()).append("\n");
  }
  for(int i=0;i<n.getChildCount();i++){ AccessibilityNodeInfo child=n.getChild(i); if(child!=null) describeNode(child,out,width,height,depth+1); }
 }

 // ---------- 手：写文本（找可编辑节点，优先焦点） ----------
 public boolean setText(int displayId, String text){
  for(AccessibilityWindowInfo w : windowsOn(displayId)){
   AccessibilityNodeInfo root = w.getRoot();
   if(root == null || !targetPackage.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
   AccessibilityNodeInfo target = findEditable(root, true);
   if(target == null) target = findEditable(root, false);
   if(target != null){
    Bundle b = new Bundle();
    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
    boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    Log.i(TAG, "setText="+ok+" to "+target.getViewIdResourceName());
    if(!ok) return false;
    target.refresh();
    return text.contentEquals(target.getText() == null ? "" : target.getText());
   }
  }
  return false;
 }
 private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n, boolean requireFocus){
  if(n.isEditable() && !n.isPassword() && (!requireFocus || n.isFocused())) return n;
  for(int j=0;j<n.getChildCount();j++){
   AccessibilityNodeInfo c = n.getChild(j);
   if(c != null){
    AccessibilityNodeInfo r = findEditable(c, requireFocus);
    if(r != null) return r;
   }
  }
  return null;
 }

 // ---------- 手：坐标点击（坐标→命中节点→可点祖先→shell注入兜底） ----------
 public boolean clickAt(int displayId, int x, int y){
  for(AccessibilityWindowInfo w : windowsOn(displayId)){
   AccessibilityNodeInfo root = w.getRoot();
   if(root == null || !targetPackage.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
   List<AccessibilityNodeInfo> chain = hitChain(root, x, y, null);
   if(chain == null) continue;
   // 从最深往外找可点的
   for(int i = chain.size()-1; i >= 0; i--){
    AccessibilityNodeInfo n = chain.get(i);
    if(n.isClickable()){
     boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
     Log.i(TAG, "clickAt "+x+","+y+" -> "+ok);
     if(ok) return true;
    }
   }
   AccessibilityNodeInfo last = chain.get(chain.size()-1);
   boolean ok = last.performAction(AccessibilityNodeInfo.ACTION_CLICK);
   Log.i(TAG, "clickAt(unchecked) "+x+","+y+" -> "+ok);
   if(ok) return true;
  }
  // 树被藏/读不到 → shell 原始触摸注入（whale 同款，不依赖节点树）
  // input -d 本身就是按屏寻址的，不会溢出到用户主屏，所以不必再卡 targetPackage
  if(displayId > 0 && ShellChannel.ready()){
   boolean ok = ShellChannel.tap(displayId, x, y);
   Log.i(TAG, "clickAt(shell) "+x+","+y+" -> "+ok);
   return ok;
  }
  Log.i(TAG, "clickAt fail: 树被藏且 shell 通道不可用 "+x+","+y);
  return false;
 }
 /** DFS 找包含 (x,y) 的最深节点，返回 root→命中 的链 */
 private List<AccessibilityNodeInfo> hitChain(AccessibilityNodeInfo n, int x, int y, List<AccessibilityNodeInfo> prefix){
  android.graphics.Rect r = new android.graphics.Rect();
  n.getBoundsInScreen(r);
  if(!r.contains(x, y)) return null;
  List<AccessibilityNodeInfo> chain = prefix == null ? new ArrayList<AccessibilityNodeInfo>() : prefix;
  chain.add(n);
  for(int j=0;j<n.getChildCount();j++){
   AccessibilityNodeInfo c = n.getChild(j);
   if(c != null){
    List<AccessibilityNodeInfo> sub = hitChain(c, x, y, new ArrayList<AccessibilityNodeInfo>(chain));
    if(sub != null) return sub;
   }
  }
  return chain;
 }

 // ---------- 手：按文字/描述点击（双开弹窗、返回键） ----------
 public boolean clickByText(int displayId, String sub){
  for(AccessibilityWindowInfo w : windowsOn(displayId)){
   AccessibilityNodeInfo root = w.getRoot();
   if(root == null || !targetPackage.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
   AccessibilityNodeInfo hit = findNodeByText(root, sub, null);
   if(hit == null) continue;
   AccessibilityNodeInfo n = hit;
   while(n != null && !n.isClickable()){
    AccessibilityNodeInfo p = n.getParent();
    n = p;
   }
   if(n != null){
    boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    Log.i(TAG, "clickByText["+sub+"] -> "+ok);
    return ok;
   }
  }
  return false;
 }
 private AccessibilityNodeInfo findNodeByText(AccessibilityNodeInfo n, String sub, Boolean firstOnly){
  CharSequence t = n.getText(), d = n.getContentDescription();
  String ts = t == null ? "" : t.toString(), ds = d == null ? "" : d.toString();
  if(ts.equals(sub) || ds.equals(sub)) return n;
  for(int j=0;j<n.getChildCount();j++){
   AccessibilityNodeInfo c = n.getChild(j);
   if(c != null){
    AccessibilityNodeInfo r = findNodeByText(c, sub, firstOnly);
    if(r != null) return r;
   }
  }
  return null;
 }

 // ---------- 手：滚动 ----------
 public boolean scroll(int displayId, boolean forward){
  for(AccessibilityWindowInfo w : windowsOn(displayId)){
   AccessibilityNodeInfo root = w.getRoot();
   if(root == null || !targetPackage.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
   AccessibilityNodeInfo sc = findScrollable(root);
   if(sc != null){
    boolean ok = sc.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                          : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    Log.i(TAG, "scroll "+forward+" -> "+ok);
    return ok;
   }
  }
  return false;
 }
 private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo n){
  if(n.isScrollable()) return n;
  for(int j=0;j<n.getChildCount();j++){
   AccessibilityNodeInfo c = n.getChild(j);
   if(c != null){
    AccessibilityNodeInfo r = findScrollable(c);
    if(r != null) return r;
   }
  }
  return null;
 }

 // ---------- 遮挡窗口自动处理 ----------
 // 后台工作区是块独立虚拟屏，冷启动的 App 会被三样东西挡住：双开选择器、系统权限弹窗、App 自己的隐私协议。
 // 这些窗口不属于目标包，原来的 hasTargetWindow 会直接判死。这里统一清障。
 private static final String[][] PERM_ALLOW = {
   {"仅在使用中允许","仅在使用该应用时允许","仅使用期间允许","仅在使用期间允许","使用时允许","始终允许","总是允许"},
   {"允许","确定","继续","OK","同意","接受","我知道了","知道了","好的"}
 };
 private static final String[] NEGATIVES = {"不允许","拒绝","取消","以后再说","暂不","禁止","忽略"};

 /** 在用户主屏上点击。
  *  为什么不能只用 shell 的 input tap：相机、游戏这类 App 常常收不到注入的触摸事件
  *  （实测相机"点了快门"却没真拍照）。无障碍手势是系统级派发，更可靠。
  *  顺序：节点命中 → ACTION_CLICK（最准）→ 无障碍手势 → shell 兜底。 */
 public boolean clickMainAt(int x, int y){
  for(AccessibilityWindowInfo w : windowsOn(0)){
   AccessibilityNodeInfo root = w.getRoot();
   if(root == null) continue;
   List<AccessibilityNodeInfo> chain = hitChain(root, x, y, null);
   if(chain == null) continue;
   for(int i = chain.size() - 1; i >= 0; i--){
    AccessibilityNodeInfo n = chain.get(i);
    if(n != null && n.isClickable()){
     try{ if(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; }catch(Throwable ig){}
    }
  }
  }
  if(gestureTap(x, y)) return true;   // 无障碍手势：相机快门靠这个才拍得下去
  return ShellChannel.tap(0, x, y);
 }

 /** 在主屏上按文字/描述点击。
  *  相机快门这类控件靠坐标点不稳（模型给的归一化坐标换算后老差一截），
  *  但它在无障碍树里是有名字的（contentDescription 常是"快门"/"拍照"），按名字点最准。 */
 public boolean clickByTextMain(int displayId, String sub, String pkg){
  for(AccessibilityWindowInfo w : windowsOn(displayId)){
   AccessibilityNodeInfo root = w.getRoot();
   if(root == null) continue;
   String rp = String.valueOf(root.getPackageName());
   if(pkg != null && !pkg.isEmpty() && !pkg.equals(rp)) continue;
   AccessibilityNodeInfo hit = findNodeByText(root, sub, null);
   if(hit == null) hit = findNodeContains(root, sub, null);
   if(hit == null) continue;
   AccessibilityNodeInfo n = hit;
   while(n != null && !n.isClickable()) n = n.getParent();
   if(n != null){
    try{ if(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; }catch(Throwable ig){}
   }
   Rect r = new Rect(); hit.getBoundsInScreen(r);
   if(!r.isEmpty() && clickMainAt(r.centerX(), r.centerY())) return true;
  }
  return false;
 }

 /** 主屏长按（监控模式用）。相机/游戏这类 App 收不到注入触摸，得走无障碍手势派发。 */
 public boolean longPressMain(int x, int y){
  try{
   android.graphics.Path path = new android.graphics.Path();
   path.moveTo(x, y);
   android.accessibilityservice.GestureDescription.StrokeDescription sd =
     new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 650);
   android.accessibilityservice.GestureDescription gd =
     new android.accessibilityservice.GestureDescription.Builder().addStroke(sd).build();
   final Object lock = new Object();
   final boolean[] ok = { false };
   dispatchGesture(gd, new android.accessibilityservice.AccessibilityService.GestureResultCallback(){
    public void onCompleted(android.accessibilityservice.GestureDescription g){
     ok[0] = true; synchronized(lock){ lock.notifyAll(); }
    }
    public void onCancelled(android.accessibilityservice.GestureDescription g){
     synchronized(lock){ lock.notifyAll(); }
    }
   }, null);
   synchronized(lock){ lock.wait(2500); }
   return ok[0];
  }catch(Throwable t){ return false; }
 }

 /** 示教模式：请用户点一下他要的那个元素，我们把它记下来 */
 public static volatile boolean demoCapture = false;
 public static volatile String demoResult = null;
 public static volatile String demoPage = null;

 public boolean hasAnyAppWindow(int displayId){
  // 抖音这类 App 会把节点树藏起来（getRoot() 为 null），但窗口本身还在。
  // 这里只判断窗口存在，不要求读得到内容，否则藏树 App 会被误判成"工作区空了"。
  for(AccessibilityWindowInfo w : windowsOn(displayId))
   if(w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) return true;
  return false;
 }

 /** 依次收拾挡路窗口：双开选择器 → 系统权限弹窗 → App 隐私协议。返回是否处理了点什么 */
 public boolean resolveBlocker(int displayId, String targetPkg, String appLabel, Agent.Logger log){
  if(resolveDualApp(displayId, appLabel)) return true;
  for(AccessibilityWindowInfo w : topDown(displayId)){
   AccessibilityNodeInfo root = w.getRoot();
   if(root == null) continue;
   String pkg = String.valueOf(root.getPackageName());
   boolean isTarget = targetPkg != null && targetPkg.contentEquals(pkg);
   if(isTarget){
    // 目标 App 自己的启动弹窗（隐私协议/升级提示）：只认明确的"同意"类按钮，绝不乱关主界面
    for(String word : new String[]{"同意并继续","同意","接受","我知道了","知道了","跳过","进入地图","稍后再说"}){
     if(clickWord(w, word, new String[]{"不同意","不同意并退出"})){
      if(log != null) log.log("已处理目标应用弹窗：" + word);
      return true;
     }
    }
    continue;
   }
   // 非目标窗口（权限控制器 / 系统升级提示 / 悬浮公告）
   for(String[] tier : PERM_ALLOW){
    for(String word : tier){
     if(clickWord(w, word, NEGATIVES)){
      if(log != null) log.log("已自动放行遮挡窗口[" + pkg + "]：" + word);
      return true;
     }
    }
   }
   if(clickWord(w, "关闭", new String[]{"不允许"} )){ if(log != null) log.log("已关闭遮挡窗口[" + pkg + "]"); return true; }
  }
  return false;
 }

 // ---------- 双开弹窗自动选主号（努比亚 / 中兴系 ROM 会把微信先弹成"双开"选择器）----------
 public boolean resolveDualApp(int displayId){ return resolveDualApp(displayId, "微信"); }
 public boolean resolveDualApp(int displayId, String appLabel){
  for(AccessibilityWindowInfo w : windowsOn(displayId)){
   AccessibilityNodeInfo root = w.getRoot();
   if(root == null) continue;
   String pkg = String.valueOf(root.getPackageName());
   if(!pkg.contains("doubleapp")) continue;
   String want = (appLabel == null || appLabel.isEmpty()) ? "微信" : appLabel;
   for(String cand : new String[]{want, "微信", "主应用", "本机"}){
    AccessibilityNodeInfo hit = findNodeContains(root, cand, null);
    if(hit != null && clickNodeOrShell(w, hit)) return true;
   }
   // 名字匹配不上就点列表第一项（通常是主号）
   for(int i=0;i<root.getChildCount();i++){
    AccessibilityNodeInfo c = root.getChild(i);
    if(c != null && c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
   }
  }
  return false;
 }

 // ---------- 窗口枚举辅助 ----------
 /** 按层级从高到低（遮挡关系：最上面的先处理） */
 private List<AccessibilityWindowInfo> topDown(int displayId){
  List<AccessibilityWindowInfo> ws = new ArrayList<AccessibilityWindowInfo>(windowsOn(displayId));
  Collections.sort(ws, new Comparator<AccessibilityWindowInfo>(){
   public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b){ return b.getLayer() - a.getLayer(); }
  });
  return ws;
 }

 private boolean clickWord(AccessibilityWindowInfo w, String word, String[] negatives){
  AccessibilityNodeInfo root = w.getRoot();
  if(root == null || word == null || word.isEmpty()) return false;
  AccessibilityNodeInfo hit = findNodeByText(root, word, null);
  if(hit == null) hit = findNodeContains(root, word, negatives);
  if(hit == null) return false;
  return clickNodeOrShell(w, hit);
 }
 /** 节点点击优先（不打扰输入法），节点不可点/树被藏 → 用 shell 原始触摸落到指定虚拟屏 */
 private boolean clickNodeOrShell(AccessibilityWindowInfo w, AccessibilityNodeInfo hit){
  AccessibilityNodeInfo n = hit;
  while(n != null && !n.isClickable()) n = n.getParent();
  if(n != null){ try{ if(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; }catch(Throwable t){} }
  android.graphics.Rect r = new android.graphics.Rect();
  try{ hit.getBoundsInScreen(r); }catch(Throwable t){ return false; }
  if(r.isEmpty()) return false;
  try{
   if(ShellChannel.tap(w.getDisplayId(), r.centerX(), r.centerY())) return true;
  }catch(Throwable t){}
  return false;
 }
 private AccessibilityNodeInfo findNodeContains(AccessibilityNodeInfo n, String sub, String[] negatives){
  if(n == null) return null;
  String t = n.getText()==null?"":n.getText().toString();
  String d = n.getContentDescription()==null?"":n.getContentDescription().toString();
  for(String s : new String[]{t, d}){
   if(s.contains(sub)){
    boolean bad = false;
    if(negatives != null) for(String g : negatives) if(s.contains(g)) bad = true;
    if(!bad) return n;
   }
  }
  for(int i=0;i<n.getChildCount();i++){
   AccessibilityNodeInfo c = n.getChild(i);
   if(c != null){
    AccessibilityNodeInfo r = findNodeContains(c, sub, negatives);
    if(r != null) return r;
   }
  }
  return null;
 }

 /** 诊断：把虚拟屏上真实存在的窗口列出来（本 ROM 读不到 logcat，只能落盘） */
 public String dumpWindows(int displayId){
  StringBuilder sb = new StringBuilder();
  List<AccessibilityWindowInfo> ws = windowsOn(displayId);
  if(ws.isEmpty()) return "<虚拟屏上没有任何窗口>";
  for(AccessibilityWindowInfo w : ws){
   AccessibilityNodeInfo root = w.getRoot();
   String pkg = root == null ? "null" : String.valueOf(root.getPackageName());
   StringBuilder tx = new StringBuilder();
   if(root != null) collectText(root, tx, 0, 90);
   sb.append("[id=").append(w.getId()).append(" type=").append(w.getType())
     .append(" layer=").append(w.getLayer()).append(" pkg=").append(pkg)
     .append(" text=").append(tx.length() == 0 ? "-" : tx.toString()).append("] ");
  }
  return sb.toString();
 }
 private void collectText(AccessibilityNodeInfo n, StringBuilder sb, int depth, int cap){
  if(depth > 10 || sb.length() > cap) return;
  String t = n.getText()==null?"":n.getText().toString();
  String d = n.getContentDescription()==null?"":n.getContentDescription().toString();
  String s = t.isEmpty() ? d : t;
  if(!s.isEmpty() && sb.indexOf(s) < 0){
   if(sb.length() > 0) sb.append("/");
   sb.append(s);
  }
  for(int i=0;i<n.getChildCount() && sb.length() < cap;i++){
   AccessibilityNodeInfo c = n.getChild(i);
   if(c != null) collectText(c, sb, depth+1, cap);
  }
 }

 // ---------- 剪贴板 ----------
 public void clip(String text){
  ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
  cm.setPrimaryClip(ClipData.newPlainText("wellphone", text));
 }

 // ---------- 全局返回（仅在节点找不到返回键时兜底） ----------
 public boolean globalBack(){ return performGlobalAction(GLOBAL_ACTION_BACK); }
}
