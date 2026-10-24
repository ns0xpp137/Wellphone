package local.wellphone.remote;
import android.content.*;
import android.hardware.display.*;
import android.media.*;
import android.graphics.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;

/** Runs under Shizuku shell. Owns the display surface; never captures the primary screen. */
public final class DisplayHost {
 private static volatile byte[] latest;
 public static void main(String[] args) throws Exception {
  Looper.prepareMainLooper();
  Class<?> at = Class.forName("android.app.ActivityThread");
  Object thread = at.getMethod("systemMain").invoke(null);
  Context base = (Context)at.getMethod("getSystemContext").invoke(thread);
  Context ctx = new ContextWrapper(base){
   public String getPackageName(){ return "com.android.shell"; }
   public String getOpPackageName(){ return "com.android.shell"; }
   public AttributionSource getAttributionSource(){ return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build(); }
  };
  // 虚拟屏跟主屏用**完全一样的像素尺寸和密度**，这样任务在虚拟屏↔主屏之间搬动时
  // 不会发生配置变化（density/size），App 就不会重新布局、字体也不会忽大忽小。
  // 代价是帧变大了，所以下面抓完帧会再缩回 720x1280 给模型，上传体积不变。
  Constructor<DisplayManager> ctor=DisplayManager.class.getDeclaredConstructor(Context.class); ctor.setAccessible(true);
  DisplayManager dm=ctor.newInstance(ctx);
  int width=720, height=1280, dpi=360;
  try{
   android.view.Display disp = dm.getDisplay(0);
   if(disp != null){
    android.util.DisplayMetrics m = new android.util.DisplayMetrics();
    disp.getRealMetrics(m);
    if(m.widthPixels > 0 && m.heightPixels > 0 && m.densityDpi > 0){
     width = m.widthPixels; height = m.heightPixels; dpi = m.densityDpi;
    }
   }
  }catch(Throwable e){ System.err.println("metric: "+e); }
  System.err.println("display "+width+"x"+height+"@"+dpi+" (与主屏一致，输出缩到 720x1280)");
  // 缩略必须等比：虚拟屏跟主屏同尺寸(1216x2688，比例 0.45)，
  // 若硬缩成 720x1280(比例 0.56) 画面会被拉变形，模型看到的就是错位界面 → 点击全歪。
  final int W=width, H=height, outW=720;
  final int outH=Math.max(1, Math.round(outW * (float) H / (float) W));
  System.err.println("out "+outW+"x"+outH);
  ImageReader reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3);
  reader.setOnImageAvailableListener(r -> {
   try(Image img=r.acquireLatestImage()){
    if(img == null) return;
    Image.Plane plane=img.getPlanes()[0];
    int stride=plane.getRowStride()/plane.getPixelStride();
    Bitmap padded=Bitmap.createBitmap(stride,H,Bitmap.Config.ARGB_8888);
    padded.copyPixelsFromBuffer(plane.getBuffer());
    Bitmap bitmap=Bitmap.createBitmap(padded,0,0,W,H);
    Bitmap small=Bitmap.createScaledBitmap(bitmap,outW,outH,true);   // 给模型的图仍保持小体积
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    small.compress(Bitmap.CompressFormat.JPEG,82,out); latest=out.toByteArray();
    small.recycle();
    if(bitmap != padded) bitmap.recycle(); padded.recycle();
   }catch(Throwable e){ System.err.println("frame: "+e); }
  },new Handler(Looper.getMainLooper()));

  // ★ 建屏标志位必须"逐级降级"，不能写死一套。
  //   Android 15 起 VIRTUAL_DISPLAY_FLAG_TRUSTED(1<<6) 需要签名级权限 ADD_TRUSTED_DISPLAY，
  //   shell(uid 2000) 在绝大多数 ROM 上都没有 → 直接 SecurityException，屏压根建不出来，
  //   App 侧的表现就是「虚拟屏未就绪」。（努比亚那台 ROM 给 shell 放行了，所以之前一直没暴露。）
  //   scrcpy 在 Android 15 上踩的是同一个坑。所以我们以"能建出来"为准，逐级去掉可疑位。
  final int FULL=1|2|8|(1<<6)|(1<<8)|(1<<10)|(1<<11)|(1<<12)|(1<<13)|(1<<14)|(1<<15);
  // 调试旋钮：WELLPHONE_VD_FLAGS=0x... 强制只用这一套标志位（排查某个 ROM 到底认哪套）
  int[] TRY_FLAGS={
   FULL,
   FULL & ~(1<<6),
   FULL & ~((1<<6)|(1<<7)),
   FULL & ~((1<<6)|(1<<12)|(1<<13)|(1<<14)|(1<<15)),
   1|2|8|(1<<8)|(1<<10)|(1<<11),
   8
  };
  String[] TRY_NAME={"full","no-TRUSTED","no-TRUSTED/UNTRUSTED","no-exotic","minimal","public-only"};
  String forcedFlags=System.getenv("WELLPHONE_VD_FLAGS");
  if(forcedFlags != null && !forcedFlags.isEmpty()){
   try{
    TRY_FLAGS=new int[]{(int)Long.parseLong(forcedFlags.trim().replace("0x","").replace("0X",""),16)};
    TRY_NAME=new String[]{"forced"};
   }catch(Throwable e){ System.err.println("WELLPHONE_VD_FLAGS 解析失败: "+forcedFlags); }
  }
  boolean hold=false;
  for(String a : args) if("--hold".equals(a)) hold=true;
  VirtualDisplay vd=null; int usedFlags=0; String usedTag=null;
  for(int k=0;k<TRY_FLAGS.length;k++){
   try{
    VirtualDisplay t=dm.createVirtualDisplay("Wellphone-Owned",width,height,dpi,reader.getSurface(),TRY_FLAGS[k]);
    if(t != null){ vd=t; usedFlags=TRY_FLAGS[k]; usedTag=TRY_NAME[k]; break; }
    System.err.println("createVirtualDisplay 返回 null (0x"+Integer.toHexString(TRY_FLAGS[k])+" "+TRY_NAME[k]+")");
   }catch(Throwable e){
    System.err.println("createVirtualDisplay 被拒 (0x"+Integer.toHexString(TRY_FLAGS[k])+" "+TRY_NAME[k]+"): "+e);
   }
  }
  if(vd == null) throw new IllegalStateException("Display creation failed (所有标志位组合都被拒)");
  final VirtualDisplay fvd = vd;
  System.err.println("display ok flags=0x"+Integer.toHexString(usedFlags)+" ("+usedTag+")");
  int id=vd.getDisplay().getDisplayId();
  int dflags=0;
  try{ dflags=vd.getDisplay().getFlags(); }catch(Throwable ig){}
  System.err.println("display id="+id+" displayFlags=0x"+Integer.toHexString(dflags)
    +" trusted="+((dflags & (1<<8)) != 0));
  // ★★ 建出来 ≠ 能用。
  //   Android 12 这类拿不到 ADD_TRUSTED_DISPLAY 的 ROM 上，系统会把自建虚拟屏降级成
  //   FLAG_PRIVATE + FLAG_OWN_CONTENT_ONLY（私有 + 只能显示自己的内容）：
  //     · am start --display N 不报错但被静默忽略，应用落回主屏
  //     · am display move-stack 直接抛 "to its current taskDisplayArea"
  //   → 永远「目标应用未在虚拟屏启动」。这种屏必须扔掉，改走系统所有的 overlay 副屏。
  final int FLAG_PRIVATE = 1 << 2, FLAG_OWN_CONTENT_ONLY = 1 << 12;
  boolean usable = (dflags & FLAG_PRIVATE) == 0 && (dflags & FLAG_OWN_CONTENT_ONLY) == 0;
  boolean forceOverlay = "1".equals(System.getenv("WELLPHONE_FORCE_OVERLAY"));
  boolean forceSelf = "1".equals(System.getenv("WELLPHONE_FORCE_SELF"));  // 调试：不让切 overlay，看看自建屏到底是什么样
  boolean overlayMode = false;
  if((!usable || forceOverlay) && !forceSelf){
   System.err.println("自建屏不可用（flags=0x"+Integer.toHexString(dflags)
     +(forceOverlay ? "，WELLPHONE_FORCE_OVERLAY=1" : "：私有/仅供自己内容")+"）→ 改用 overlay 系统副屏");
   try{ vd.release(); }catch(Throwable ig){}
   // ★ 副屏必须用**主屏的像素尺寸和 dpi**，不能为了省一次缩放开成 720x1600：
   //   720x1600@480 = 240x533dp，主屏是 360x800dp —— App 会切到更窄的布局、按钮位置全变，
   //   模型看到的画面和真机不是一回事。dp 一致优先，缩放那点开销不值得拿正确性换。
   int oid=setupOverlay(dm,width,height,dpi);
   if(oid > 0){ id=oid; overlayMode=true; }
   else System.err.println("overlay 副屏也没建出来，只能退回自建屏（大概率放不进应用）");
  }
  final boolean fOverlay = overlayMode;
  final int fid = id;
  // Do not display an IME on this display or redirect it to the primary display.
  // 这一步失败只是"输入法可能弹到用户屏"，比"整块屏没了"轻得多，所以不再抛异常。
  try{
   Object wm=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
   Class.forName("android.view.IWindowManager").getMethod("setDisplayImePolicy",int.class,int.class).invoke(wm,id,Class.forName("android.view.WindowManager").getField("DISPLAY_IME_POLICY_HIDE").getInt(null));
   System.err.println("ime policy = HIDE");
  }catch(Throwable e){ System.err.println("ime policy 设置失败（输入法可能弹到主屏）: "+e); }
  // 通道：默认用 stdin/stdout（Shizuku newProcess 启动时用管道）；
  // 若指定了 WELLPHONE_SOCKET_PORT，则监听本地端口等 App 来连
  // ——内置通道是用 adb shell 启动的，拿不到管道，只能走 socket。
  java.io.InputStream rawIn=System.in;
  java.io.OutputStream rawOut=System.out;
  String sockPort=System.getenv("WELLPHONE_SOCKET_PORT");
  if(sockPort != null && !sockPort.isEmpty()){
   try{
    java.net.ServerSocket ss=new java.net.ServerSocket();
    ss.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"),
            Integer.parseInt(sockPort.trim())), 1);
    ss.setSoTimeout(45000);
    System.err.println("waiting app on 127.0.0.1:"+sockPort);
    java.net.Socket sk=ss.accept();
    sk.setTcpNoDelay(true);
    rawIn=sk.getInputStream(); rawOut=sk.getOutputStream();
    System.err.println("app connected");
    ss.close();
   }catch(Throwable e){ System.err.println("socket channel: "+e); }
  }
  final java.io.InputStream floorIn=rawIn;
  DataOutputStream out=new DataOutputStream(rawOut);
  // 第 4 个 int 是"副屏类型"：1=overlay（主屏上会多一个关不掉的系统预览窗）
  out.writeInt(id); out.writeInt(width); out.writeInt(height);
  out.writeInt(fOverlay ? 1 : 0); out.flush();
  // --hold：只建屏并保持（stdin 关掉也不会立刻退出），供 adb 手工验证"这块屏能不能承载 App"
  if(hold){ System.err.println("hold: display "+id+" flags=0x"+Integer.toHexString(usedFlags)); Looper.loop(); return; }
  new Thread(() -> {
   try(DataInputStream in=new DataInputStream(floorIn)){
    while(in.readInt()==1){
     byte[] frame = fOverlay ? grabFrame(fid,outW,outH) : latest;
     out.writeInt(frame == null ? 0 : frame.length);
     if(frame != null) out.write(frame);
     out.flush();
    }
   }catch(Throwable e){ System.err.println("display connection closed: "+e); }
   finally{ new Handler(Looper.getMainLooper()).post(() -> {
     if(!fOverlay){ try{ fvd.release(); }catch(Throwable ig){} }
     try{ reader.close(); }catch(Throwable ig){}
     // 我们开的副屏要收回去，别在用户机器上留一块看不见的显示器
     if(fOverlay){ shell("settings delete global overlay_display_devices"); System.err.println("overlay 副屏已撤销"); }
     Looper.getMainLooper().quitSafely(); }); }
  },"display-requests").start();
  Looper.loop();
 }

 // ===== overlay 系统副屏（拿不到 ADD_TRUSTED_DISPLAY 的 ROM 的唯一出路）=====
 // 系统所有、FLAG_PRESENTATION + FLAG_TRUSTED，第三方 App 能落上去，主屏毫无感知。
 static int setupOverlay(DisplayManager dm, int w, int h, int dpi){
  try{
   // 先撤再建：App 被强杀时清理逻辑来不及跑，上次的 overlay 屏会赖着不走，
   //   不先删就会一块一块往上叠（用户看到的就是"预览窗越来越多/关不掉"）。
   shell("settings delete global overlay_display_devices");
   shell("settings put global overlay_display_devices \"" + w + "x" + h + "/" + dpi + "\"");
   for(int i=0;i<48;i++){
    for(android.view.Display d : dm.getDisplays()){
     int f=d.getFlags();
     if(d.getDisplayId() != 0 && (f & android.view.Display.FLAG_PRESENTATION) != 0){
      int oid=d.getDisplayId();
      System.err.println("overlay 副屏 id="+oid+" name="+d.getName()+" flags=0x"+Integer.toHexString(f));
      // 副屏自带锁屏。趁它上面还没有任何应用，先上滑解锁——
      // 这时划到的是空屏，不会误触任何按钮（有应用之后再划就会乱按）。
      shell("input -d "+oid+" swipe "+(w/2)+" "+(h*5/6)+" "+(w/2)+" "+(h/6)+" 300");
      try{ Thread.sleep(900); }catch(Throwable ig){}
      return oid;
     }
    }
    try{ Thread.sleep(250); }catch(Throwable ig){}
   }
   System.err.println("等了 12 秒 overlay 副屏还没出现");
  }catch(Throwable e){ System.err.println("setupOverlay: "+e); }
  return -1;
 }
 /** overlay 屏不是我们建的，拿不到 ImageReader 帧，只能 screencap -d N 抓。
  *  加个 120ms 节流：镜像窗 3fps + agent 每步一张，不至于把 CPU 打满。 */
 private static volatile byte[] overlayFrame; private static volatile long overlayFrameMs;
 static byte[] grabFrame(int id, int outW, int outH){
  long now=System.currentTimeMillis();
  if(overlayFrame != null && now - overlayFrameMs < 120) return overlayFrame;
  try{
   java.lang.Process p=new ProcessBuilder("/system/bin/screencap","-d",""+id,"-p").start();
   ByteArrayOutputStream buf=new ByteArrayOutputStream();
   java.io.InputStream is=p.getInputStream();
   byte[] t=new byte[65536]; int n;
   while((n=is.read(t))>0) buf.write(t,0,n);
   is.close(); p.destroy();
   byte[] data=buf.toByteArray();
   if(data.length < 200){ System.err.println("grabFrame: screencap 没吐出东西 len="+data.length); return overlayFrame; }
   // 1080x2400 的 ARGB_8888 解码要 10MB+，shell 进程堆里容易 OOM → 用 RGB_565 先省一半；
   // 还失败就降采样再来一次，别让镜像窗一片黑。
   BitmapFactory.Options opt=new BitmapFactory.Options();
   opt.inPreferredConfig=Bitmap.Config.RGB_565;
   Bitmap bmp=BitmapFactory.decodeByteArray(data,0,data.length,opt);
   if(bmp == null){
    BitmapFactory.Options o2=new BitmapFactory.Options();
    o2.inPreferredConfig=Bitmap.Config.RGB_565; o2.inSampleSize=2;
    bmp=BitmapFactory.decodeByteArray(data,0,data.length,o2);
    System.err.println("grabFrame: 首次解码失败(len="+data.length+")，降采样重试="+(bmp != null));
   }
   if(bmp == null){ System.err.println("grabFrame: 解码彻底失败 len="+data.length); return overlayFrame; }
   Bitmap small=Bitmap.createScaledBitmap(bmp,outW,outH,true);
   ByteArrayOutputStream o=new ByteArrayOutputStream();
   small.compress(Bitmap.CompressFormat.JPEG,82,o);
   overlayFrame=o.toByteArray(); overlayFrameMs=now;
   bmp.recycle(); small.recycle();
  }catch(Throwable e){ System.err.println("grabFrame: "+e); }
  return overlayFrame;
 }
 static void shell(String cmd){
  try{
   java.lang.Process p=Runtime.getRuntime().exec(new String[]{"sh","-c",cmd});
   try{ p.getInputStream().close(); p.getErrorStream().close(); }catch(Throwable ig){}
   p.waitFor();
  }catch(Throwable e){ System.err.println("shell["+cmd+"]: "+e); }
 }
}
