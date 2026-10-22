package local.wellphone.remote;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** 虚拟屏直采（SurfaceControl/ScreenCapture 隐藏 API，whale CaptureOps 同款）。
 *  两种运行方式：
 *   1) App 进程内直接 capture()（需 hidden_api_policy 放开）
 *   2) app_process 以 shell 身份运行 main()（shell 无隐藏 API 限制）
 *      用法: CLASSPATH=<apk> app_process /system/bin local.wellphone.remote.CaptureMain <displayId> <out.png> */
public class CaptureMain {

 public static void main(String[] args){
  try{
   if(args.length > 0 && "probe".equals(args[0])){ probe(); return; }
   if(args.length > 0 && "tok".equals(args[0])){ tokTest(); return; }
   int displayId = Integer.parseInt(args[0]);
   String outPath = args[1];
   Bitmap bmp = capture(displayId);
   if(bmp == null){ System.err.println("CAPTURE_NULL"); System.exit(2); return; }
   FileOutputStream fos = new FileOutputStream(outPath);
   bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
   fos.close();
   System.out.println("OK " + bmp.getWidth() + "x" + bmp.getHeight());
  }catch(Throwable t){
   System.err.println("CAPTURE_FAIL " + t);
   System.exit(3);
  }
 }

 /** 决定性试验：SurfaceControl.createDisplay 自建虚拟屏 → 拿 token → captureDisplay 直采 */
 static void tokTest(){
  try{
   Class<?> sc = Class.forName("android.view.SurfaceControl");
   // createDisplay(String name, boolean secure) → IBinder token
   Method cd = null;
   for(Method m : sc.getDeclaredMethods())
    if(m.getName().equals("createDisplay")) cd = m;
   if(cd == null){ System.out.println("NO createDisplay"); return; }
   cd.setAccessible(true);
   Object token = cd.invoke(null, "wp-probe", false);
   System.out.println("createDisplay token = " + token);
   if(token == null) return;
   try{
    // ScreenCapture.captureDisplay(DisplayCaptureArgs.Builder(token).setSize(w,h).build())
    Class<?> builderCls = Class.forName("android.window.ScreenCapture$DisplayCaptureArgs$Builder");
    Object builder = builderCls.getConstructor(android.os.IBinder.class).newInstance((android.os.IBinder)token);
    builderCls.getMethod("setSize", int.class, int.class).invoke(builder, 480, 854);
    Object argsObj = builderCls.getMethod("build").invoke(builder);
    Object result = Class.forName("android.window.ScreenCapture")
      .getMethod("captureDisplay", Class.forName("android.window.ScreenCapture$DisplayCaptureArgs"))
      .invoke(null, argsObj);
    Bitmap bmp = toBitmap(result);
    if(bmp != null){
     FileOutputStream fos = new FileOutputStream("/sdcard/Android/data/local.wellphone.remote/files/tok.png");
     bmp.compress(Bitmap.CompressFormat.PNG, 100, fos); fos.close();
     System.out.println("TOKEN_CAPTURE_OK " + bmp.getWidth() + "x" + bmp.getHeight());
    }else System.out.println("TOKEN_CAPTURE_NULL");
   }finally{
    // destroyDisplay(token)
    try{ sc.getMethod("destroyDisplay", android.os.IBinder.class).invoke(null, (android.os.IBinder)token); }catch(Throwable t){}
   }
  }catch(Throwable t){ System.out.println("TOK_FAIL " + t); }
 }
 static void probe(){
  try{
   Class<?> sc = Class.forName("android.view.SurfaceControl");
   java.util.TreeSet<String> names = new java.util.TreeSet<>();
   for(Method m : sc.getDeclaredMethods()) names.add(m.getName());
   System.out.println("SCnames: " + names);
  }catch(Throwable t){ System.out.println("SC fail " + t); }
  // screenshotWithUid 带 uid/displayId 试探
  try{
   Method m = Class.forName("android.window.ScreenCapture").getMethod(
     "screenshotWithUid", android.os.IBinder.class, android.graphics.Rect.class, int.class, long.class);
   Object r = m.invoke(null, null, new android.graphics.Rect(0,0,720,1280), 2000, 41L);
   System.out.println("swU(null,rect,2000,41L) = " + r);
  }catch(Throwable t){ System.out.println("swU fail: " + t); }
 }

 /** 直采指定 display，返回 Bitmap（失败返回 null） */
 public static Bitmap capture(int displayId){
  // 路线1：SurfaceControl.mirrorDisplay + ScreenCapture.captureLayers
  try{
   Class<?> scClass = Class.forName("android.view.SurfaceControl");
   Method mirror = scClass.getMethod("mirrorDisplay", int.class);
   final Object sc = mirror.invoke(null, displayId);
   if(sc != null){
    try{
     Class<?> lcaClass = Class.forName("android.window.ScreenCapture$LayerCaptureArgs");
     Class<?> builderClass = Class.forName("android.window.ScreenCapture$LayerCaptureArgs$Builder");
     Object builder = builderClass.getConstructor(scClass).newInstance(sc);
     Object args = builderClass.getMethod("build").invoke(builder);
     Class<?> scrClass = Class.forName("android.window.ScreenCapture");
     Method cap = scrClass.getMethod("captureLayers", lcaClass);
     Object result = cap.invoke(null, args);
     Bitmap bmp = toBitmap(result);
     if(bmp != null) return bmp;
    }finally{
     try{ sc.getClass().getMethod("release").invoke(sc); }catch(Throwable t){}
    }
   }
  }catch(Throwable t){ debug("mirrorDisplay fail: " + t); }

  // 路线2：SurfaceControl.captureDisplay(DisplayCaptureArgs)——部分版本入口在此
  try{
   Class<?> scrClass = Class.forName("android.window.ScreenCapture");
   Class<?> argsClass = Class.forName("android.window.ScreenCapture$DisplayCaptureArgs");
   Class<?> builderClass = Class.forName("android.window.ScreenCapture$DisplayCaptureArgs$Builder");
   // Builder 有两种签名：(IBinder,int,int) / (int)，逐一尝试
   Object builder = null;
   for(Constructor<?> c : builderClass.getConstructors()){
    try{
     Class<?>[] p = c.getParameterTypes();
     if(p.length == 3) builder = c.newInstance(null, 2048, 2048);
     else if(p.length == 1 && p[0] == int.class) builder = c.newInstance(displayId);
    }catch(Throwable t2){}
    if(builder != null) break;
   }
   if(builder != null){
    Object args = builderClass.getMethod("build").invoke(builder);
    Method cap = scrClass.getMethod("captureDisplay", argsClass);
    Object result = cap.invoke(null, args);
    Bitmap bmp = toBitmap(result);
    if(bmp != null) return bmp;
   }
  }catch(Throwable t){ debug("captureDisplay fail: " + t); }
  return null;
 }

 private static Bitmap toBitmap(Object result) throws Exception {
  if(result == null) return null;
  Method ghb = result.getClass().getMethod("getHardwareBuffer");
  HardwareBuffer hb = (HardwareBuffer)ghb.invoke(result);
  if(hb == null) return null;
  ColorSpace cs = null;
  try{ cs = (ColorSpace)result.getClass().getMethod("getColorSpace").invoke(result); }catch(Throwable t){}
  Bitmap bmp = Bitmap.wrapHardwareBuffer(hb, cs);
  hb.close();
  return bmp;
 }

 private static void debug(String s){
  try{ AgentService.shotDebug("CaptureMain " + s); }catch(Throwable t){}
 }
}
