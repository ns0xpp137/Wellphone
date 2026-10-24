package local.wellphone.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 内置配对服务：全程在手机上完成无线调试配对，不装 Shizuku、不用电脑。
 *
 * 关键设计（照 Shizuku 的做法，解决一个很容易踩的坑）：
 *   系统的"使用配对码配对设备"页面一离开就会刷新配对码，所以**不能让用户切 App 去输码**。
 *   做法是把输入框放到**通知栏**（RemoteInput），用户下拉通知栏直接输入，
 *   系统设置那个页面保持在前台不动，配对码就不会变。
 *
 * 端口也不用用户填：用 mDNS 自动发现
 *   _adb-tls-pairing._tcp  → 配对端口
 *   _adb-tls-connect._tcp  → 连接端口
 */
public class PairShellService extends Service {

    static final String CHANNEL = "wellphone_pair";
    static final int NOTI_ID = 2001;
    static final String KEY_CODE = "pair_code";

    private static volatile PairShellService inst;
    private NsdManager nsd;
    private volatile String host;
    private volatile int pairPort = -1, connPort = -1;
    private volatile String state = "正在搜索配对服务…";
    // ★ 配对时记住"刚才配对的是哪台设备"（服务名 + IP）。
    //   局域网里只要还有第二台手机开着无线调试，mDNS 就会把它的 _adb-tls-connect 也报回来，
    //   连上去必然是 SSLV3_ALERT_CERTIFICATE_UNKNOWN（连错机器了，密钥不是同一台）。
    private volatile String pairSvcName, pairHost;

    public void onCreate() {
        super.onCreate();
        inst = this;
        App.ctx = getApplicationContext();
    }

    public void onDestroy() {
        inst = null;
        stopMdns();
        super.onDestroy();
    }

    public IBinder onBind(Intent i) { return null; }

    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTI_ID, buildNoti());
        discover("_adb-tls-pairing._tcp", true);
        post("请在通知栏输入配对码");   // 引导文案写进 run-log
        return START_NOT_STICKY;
    }

    // ---------- 通知（带输入框） ----------
    private Notification buildNoti() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "内置配对", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("无线调试配对：在通知栏输入配对码");
            nm.createNotificationChannel(ch);
        }
        Intent codeIntent = new Intent(this, PairCodeReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, 1, codeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        RemoteInput ri = new RemoteInput.Builder(KEY_CODE).setLabel("输入 6 位配对码").build();
        Notification.Action act = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_edit), "输入配对码", pi)
                .addRemoteInput(ri).build();

        Notification.Builder b = new Notification.Builder(this)
                .setContentTitle("Wellphone 内置配对")
                .setContentText(state)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .addAction(act);
        if (Build.VERSION.SDK_INT >= 26) b.setChannelId(CHANNEL);
        return b.build();
    }

    private void refresh(String s) {
        state = s;
        post(s);
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTI_ID, buildNoti());
        } catch (Throwable t) {}
    }

    private void post(String s) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(App.f("run-log.txt"), true);
            w.write(System.currentTimeMillis() + " " + s + "\n");
            w.close();
        } catch (Throwable t) {}
    }

    // ---------- mDNS 发现 ----------
    private android.net.wifi.WifiManager.MulticastLock mcast;
    private NsdManager.DiscoveryListener listener;

    private void lockMulticast(boolean on) {
        try {
            if (on) {
                if (mcast != null) return;
                android.net.wifi.WifiManager wm =
                        (android.net.wifi.WifiManager) getApplicationContext()
                                .getSystemService(Context.WIFI_SERVICE);
                if (wm == null) return;
                mcast = wm.createMulticastLock("wellphone-mdns");
                mcast.setReferenceCounted(false);
                mcast.acquire();
                post("已获取 mDNS 多播锁");
            } else {
                if (mcast != null && mcast.isHeld()) mcast.release();
                mcast = null;
            }
        } catch (Throwable t) { post("多播锁异常：" + t); }
    }

    private void discover(final String type, final boolean pairing) {
        stopMdns();
        lockMulticast(true);   // 部分设备上没这个锁收不到 mDNS 广播
        try {
            final NsdManager mgr = (NsdManager) getSystemService(Context.NSD_SERVICE);
            if (mgr == null) { post("NsdManager 不可用"); return; }
            nsd = mgr;
            listener = new NsdManager.DiscoveryListener() {
                public void onDiscoveryStarted(String t) { post("搜索已启动: " + t); }
                public void onStartDiscoveryFailed(String t, int e) {
                    post("搜索启动失败 " + t + " code=" + e);
                    refresh("mDNS 启动失败（code " + e + "），可能被系统限制");
                }
                public void onStopDiscoveryFailed(String t, int e) {}
                public void onDiscoveryStopped(String t) { post("搜索已停止"); }
                public void onServiceFound(NsdServiceInfo info) {
                    // 不比对 serviceType：Android 返回的常带尾点（_xxx._tcp.），一比就漏
                    post("发现服务: " + info.getServiceName() + " type=" + info.getServiceType());
                    try {
                        mgr.resolveService(info, new NsdManager.ResolveListener() {
                            public void onResolveFailed(NsdServiceInfo i, int e) { post("解析失败 code=" + e); }
                            public void onServiceResolved(NsdServiceInfo i) {
                                String h = i.getHost() == null ? "127.0.0.1" : i.getHost().getHostAddress();
                                post("解析成功 " + i.getServiceName() + " -> " + h + ":" + i.getPort());
                                if (pairing) {
                                    host = h;
                                    pairSvcName = i.getServiceName();
                                    pairHost = h;
                                    pairPort = i.getPort();
                                    refresh("已找到配对服务，请在通知栏输入配对码");
                                } else {
                                    // 只认刚才配对过的那台：服务名一致，或 IP 一致
                                    boolean same = (pairSvcName != null && pairSvcName.equals(i.getServiceName()))
                                            || (pairHost != null && pairHost.equals(h));
                                    if (!same) {
                                        post("跳过别的设备的连接服务 " + i.getServiceName() + " -> " + h
                                                + ":" + i.getPort() + "（本机配对的是 " + pairHost + "）");
                                        return;
                                    }
                                    if (connPort > 0) return;   // 已经拿到了，别被后来的覆盖
                                    host = h;
                                    connPort = i.getPort();
                                    refresh("已找到连接服务 " + h + ":" + connPort);
                                    startWorkspace();
                                }
                            }
                        });
                    } catch (Throwable t) { post("resolve 异常：" + t); }
                }
                public void onServiceLost(NsdServiceInfo info) {}
            };
            mgr.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Throwable t) {
            post("mDNS 异常：" + t);
            refresh("mDNS 异常：" + t);
        }
    }

    private void stopMdns() {
        // 必须传真实 listener：传 null 会抛 IllegalArgumentException
        try { if (nsd != null && listener != null) nsd.stopServiceDiscovery(listener); } catch (Throwable t) {}
        nsd = null; listener = null;
        lockMulticast(false);
    }

    // ---------- 收到配对码 ----------
    static void onCode(final String code) {
        final PairShellService s = inst;
        if (s == null) return;
        s.refresh("正在配对…");
        new Thread(() -> {
            if (s.pairPort <= 0 || s.host == null) { s.refresh("还没找到配对服务，请确认已开启无线调试"); return; }
            boolean ok = BuiltinShell.pair(s.host, s.pairPort, code.trim(), s::post);
            if (!ok) { s.refresh("配对失败（配对码可能已过期，请重新点系统的配对）"); return; }
            s.refresh("配对成功，正在连接…");
            BuiltinShell.saveConn(s, s.host, 0);   // 先记住 host，端口等 mDNS 给
            s.discover("_adb-tls-connect._tcp", false);
            // ★ 配对成功但一直搜不到连接端口，说明 mDNS 没广播出来 —— 最常见的就是
            //   这会儿插着 USB 数据线（部分 ROM 插线就不再广播无线调试）。别让用户干等。
            new Thread(() -> {
                try { Thread.sleep(15000); } catch (Throwable t) {}
                if (s.connPort <= 0) {
                    s.refresh("⚠️ 搜不到无线调试的连接端口：如果正插着数据线，拔掉再重配一次");
                    s.post("⚠️ 15 秒没搜到 _adb-tls-connect 服务（插着 USB 线时常见），拔线重试");
                }
            }, "conn-watch").start();
        }).start();
    }

    private void startWorkspace() {
        if (connPort <= 0) return;
        BuiltinShell.saveConn(this, host, connPort);
        // ★ 结尾那个多余的 \" 会让整条命令变成 "no closing quote"，宿主进程起不来。
        String cmd = ""
                + "CLASSPATH='" + getApplicationInfo().sourceDir
                + "' WELLPHONE_SOCKET_PORT=" + OwnedDisplay.BUILTIN_PORT
                + " app_process /system/bin local.wellphone.remote.DisplayHost";
        refresh("正在启动工作区…");
        // ★ runDaemon 会一直阻塞到宿主进程退出（正常运行时永不返回），
        //   所以"起来了没有"必须另起一条线程去查，否则通知会永远停在「正在启动工作区」。
        new Thread(() -> { BuiltinShell.runDaemon(host, connPort, cmd, this::post); },
                "workspace-daemon").start();
        new Thread(() -> {
            for (int i = 0; i < 24; i++) {
                try { Thread.sleep(700); } catch (Throwable t) {}
                if (OwnedDisplay.connectIfRunning()) {
                    refresh("完成：工作区已启动（display " + OwnedDisplay.id + "），可以开始用任务了");
                    try { stopForeground(true); } catch (Throwable t) {}
                    stopSelf();
                    return;
                }
            }
            post("⚠️ 工作区没起来：请看上面 host> 的报错");
            refresh("工作区启动失败，看 run-log.txt 里 host> 的报错");
            try { stopForeground(true); } catch (Throwable t) {}
            stopSelf();
        }, "workspace-watch").start();
    }

    static void start(Context ctx) {
        Intent i = new Intent(ctx, PairShellService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }
}
