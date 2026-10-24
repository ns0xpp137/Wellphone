package local.wellphone.remote;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** 接收通知栏里输入的配对码。
 *  为什么走通知栏：系统的"使用配对码配对设备"页面一离开就会刷新配对码，
 *  所以不能让用户切回 App 输码——下拉通知栏直接输入，那个页面就不会被切走。 */
public class PairCodeReceiver extends BroadcastReceiver {
    public void onReceive(Context ctx, Intent it) {
        try {
            Bundle b = RemoteInput.getResultsFromIntent(it);
            CharSequence cs = b == null ? null : b.getCharSequence(PairShellService.KEY_CODE);
            if (cs != null && cs.length() > 0) PairShellService.onCode(cs.toString());
        } catch (Throwable t) {}
    }
}
