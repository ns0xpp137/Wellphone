package local.wellphone.remote;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import moe.shizuku.api.BinderContainer;
import rikka.shizuku.Shizuku;

/** 接收 Shizuku 服务端推来的 shell binder（协议同官方 ShizukuProvider，做最小实现）。
 *  Shizuku 服务端按 authority ${applicationId}.shizuku 找到本 Provider 并 call("sendBinder")。 */
public class ShizukuBridgeProvider extends ContentProvider {
 private static final String TAG = "WellphoneShell";
 public static final String METHOD_SEND_BINDER = "sendBinder";
 private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

 @Override public boolean onCreate(){ return true; }

 @Override public Bundle call(String method, String arg, Bundle extras){
  if(METHOD_SEND_BINDER.equals(method) && extras != null){
   try{
    extras.setClassLoader(BinderContainer.class.getClassLoader());
    BinderContainer c = extras.getParcelable(EXTRA_BINDER);
    if(c != null && c.binder != null){
     Shizuku.onBinderReceived(c.binder, getContext().getPackageName());
     Log.i(TAG, "shizuku binder received");
    }
   }catch(Throwable t){ Log.i(TAG, "sendBinder fail " + t); }
   return new Bundle();
  }
  return null;
 }

 // ---- ContentProvider 必须实现的空壳 ----
 public Cursor query(Uri uri, String[] p, String s, String[] a, String o){ return null; }
 public String getType(Uri uri){ return null; }
 public Uri insert(Uri uri, ContentValues v){ return null; }
 public int delete(Uri uri, String s, String[] a){ return 0; }
 public int update(Uri uri, ContentValues v, String s, String[] a){ return 0; }
}
