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

  int flags=1|2|8|(1<<6)|(1<<8)|(1<<10)|(1<<11)|(1<<12)|(1<<13)|(1<<14)|(1<<15);
  VirtualDisplay vd=dm.createVirtualDisplay("Wellphone-Owned",width,height,dpi,reader.getSurface(),flags);
  if(vd == null) throw new IllegalStateException("Display creation failed");
  final int id=vd.getDisplay().getDisplayId();
  // Do not display an IME on this display or redirect it to the primary display.
  try{
   Object wm=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
   Class.forName("android.view.IWindowManager").getMethod("setDisplayImePolicy",int.class,int.class).invoke(wm,id,Class.forName("android.view.WindowManager").getField("DISPLAY_IME_POLICY_HIDE").getInt(null));
  }catch(Throwable e){ vd.release(); throw new IllegalStateException("Cannot isolate IME",e); }
  DataOutputStream out=new DataOutputStream(System.out);
  out.writeInt(id); out.writeInt(width); out.writeInt(height); out.flush();
  new Thread(() -> {
   try(DataInputStream in=new DataInputStream(System.in)){
    while(in.readInt()==1){
     byte[] frame=latest;
     out.writeInt(frame == null ? 0 : frame.length);
     if(frame != null) out.write(frame);
     out.flush();
    }
   }catch(Throwable e){ System.err.println("display connection closed: "+e); }
   finally{ new Handler(Looper.getMainLooper()).post(() -> { vd.release(); reader.close(); Looper.getMainLooper().quitSafely(); }); }
  },"display-requests").start();
  Looper.loop();
 }
}
