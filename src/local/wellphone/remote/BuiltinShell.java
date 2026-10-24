package local.wellphone.remote;

import android.content.Context;
import android.content.SharedPreferences;

import moe.shizuku.manager.adb.AdbClient;
import moe.shizuku.manager.adb.AdbKey;
import moe.shizuku.manager.adb.AdbPairingClient;
import moe.shizuku.manager.adb.PreferenceAdbKeyStore;

/**
 * 内置 Shell 通道：不装 Shizuku App，在本 App 内完成无线调试配对，拿到 shell(2000) 权限。
 *
 * 原理（和 Shizuku / 同类产品走的是同一条 Android 11+ 机制）：
 *   1. AdbPairingClient 作为 adb 客户端，用配对码与 adbd 完成 adb pair（SPAKE2 + TLS）
 *   2. 配对后 AdbClient.connect() 连上 adbd
 *   3. AdbClient.shellCommand("...") 通过 adbd 执行命令 —— 该命令以 shell(2000) 权限运行
 *
 * 这里的 adb 协议实现来自 RikkaApps/Shizuku（Apache-2.0），我们只取其中的
 * AdbPairingClient / AdbClient / AdbKey 等 6 个源文件，不依赖 Shizuku server。
 *
 * 注意：无线调试的“首次配对”必须在手机上完成一次，这是 Android 的安全边界，
 * 无法绕过——但整个过程都在本 App 内，用户不需要装第二个 App，也不需要电脑。
 */
public final class BuiltinShell {

    /** AdbKey 需要一个 AdbKeyStore 存放密钥；Shizuku 自带了基于 SharedPreferences 的实现 */
    private static AdbKey key(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("adbkey", Context.MODE_PRIVATE);
        return new AdbKey(new PreferenceAdbKeyStore(p), "wellphone");
    }

    public interface Log { void on(String s); }

    /** 第一步：配对（只需一次）。pairPort/parCode 来自系统「无线调试 → 使用配对码配对设备」 */
    public static boolean pair(String host, int pairPort, String pairCode, Log log) {
        AdbPairingClient c = null;
        try {
            log.on("开始配对 " + host + ":" + pairPort);
            c = new AdbPairingClient(host, pairPort, pairCode, key(appCtx));
            boolean ok = c.start();
            log.on(ok ? "配对成功" : "配对失败（配对码可能过期）");
            return ok;
        } catch (Throwable t) {
            log.on("配对异常：" + t);
            return false;
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ig) {}
        }
    }

    /** 启动常驻进程：命令**不**后台化，adb 通道保持打开，
     *  这样宿主进程活着，且它的 stdout/stderr 能通过回调实时拿到。
     *  （后台化 + setsid 在这条通道上会让 app_process 静默死掉，且看不到原因） */
    public static void runDaemon(String host, int port, String command, Log log) {
        AdbClient c = null;
        try {
            c = new AdbClient(host, port, key(appCtx));
            c.connect();
            log.on("daemon 已连接，执行：" + command);
            c.shellCommand(command, new kotlin.jvm.functions.Function1<byte[], kotlin.Unit>() {
                public kotlin.Unit invoke(byte[] out) {
                    String t = new String(out).trim();
                    if (!t.isEmpty()) for (String line : t.split("\n")) log.on("  host> " + line);
                    return kotlin.Unit.INSTANCE;
                }
            });
            log.on("daemon 通道结束（宿主进程退出了）");
        } catch (Throwable t) {
            log.on("daemon 失败：" + t);
        }
        // 故意不 close：连接一断，宿主进程可能被带走
    }

    /** 第二步：连上 adbd 并执行一条 shell 命令（该命令以 shell(2000) 权限运行） */
    public static void runShell(String host, int port, String command, Log log) {
        AdbClient c = null;
        try {
            log.on("连接 " + host + ":" + port);
            c = new AdbClient(host, port, key(appCtx));
            c.connect();
            log.on("已连接，执行：" + command);
            // Kotlin 的 lambda 参数在 Java 侧是 Function1，必须显式给类型（不能传裸 null）
            c.shellCommand(command, new kotlin.jvm.functions.Function1<byte[], kotlin.Unit>() {
                public kotlin.Unit invoke(byte[] out) {
                    log.on("  out: " + new String(out).trim());
                    return kotlin.Unit.INSTANCE;
                }
            });
            log.on("已下发");
        } catch (Throwable t) {
            log.on("执行失败：" + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ig) {}
        }
    }

    /** 配对成功后记住连接信息：adb 授权是持久的，之后每次打开 App 可直接复用，不用重新配对 */
    public static void saveConn(Context ctx, String host, int connPort) {
        ctx.getSharedPreferences("builtinshell", Context.MODE_PRIVATE).edit()
           .putString("host", host).putInt("port", connPort).putBoolean("paired", true).apply();
    }
    public static boolean hasSaved(Context ctx) {
        return ctx.getSharedPreferences("builtinshell", Context.MODE_PRIVATE).getBoolean("paired", false);
    }
    /** 配对成功但 mDNS 没拿到连接端口时会存成 0 —— 这时候"已配对"是假象，
     *  每次都拿 port=0 去连必然 ECONNREFUSED，必须让用户重新配对。 */
    public static int savedPort(Context ctx) {
        return ctx.getSharedPreferences("builtinshell", Context.MODE_PRIVATE).getInt("port", 0);
    }
    /** 用已保存的连接启动工作区（无需重新配对） */
    public static void startWorkspace(Context ctx, String command, Log log) {
        SharedPreferences p = ctx.getSharedPreferences("builtinshell", Context.MODE_PRIVATE);
        String host = p.getString("host", "127.0.0.1");
        int port = p.getInt("port", 5555);
        log.on("复用已配对的连接 " + host + ":" + port);
        runDaemon(host, port, command, log);   // 常驻：通道不断，宿主进程才活得住
    }

    /** 同步执行并收集输出（内置通道下的 ShellChannel.raw 等价物） */
    public static String execFromSaved(Context ctx, String command) {
        SharedPreferences p = ctx.getSharedPreferences("builtinshell", Context.MODE_PRIVATE);
        final StringBuilder sb = new StringBuilder();
        runShell(p.getString("host", "127.0.0.1"), p.getInt("port", 5555), command,
                s -> {
                    String t = s.trim();
                    if (t.startsWith("out:")) sb.append(t.substring(4).trim()).append("\n");
                });
        return sb.toString();
    }

    /** 用已保存的连接下发任意命令（调试/诊断用） */
    public static void runShellFromSaved(Context ctx, String command, Log log) {
        SharedPreferences p = ctx.getSharedPreferences("builtinshell", Context.MODE_PRIVATE);
        String host = p.getString("host", "127.0.0.1");
        int port = p.getInt("port", 5555);
        log.on("下发到 " + host + ":" + port + " → " + command);
        runShell(host, port, command, log);
    }

    private static volatile Context appCtx;
    public static void init(Context ctx) { appCtx = ctx.getApplicationContext(); }

    // ===== 复用一条长连接，别每条命令都重新握手 =====
    // ★ 原来 execFromSaved() 每次都 new AdbClient → connect → 下发 → finally 里 close()。
    //   对于 dumpsys 这种输出大的命令，数据还没回来连接就断了，拿到的是空串
    //   （旧机日志：findTaskId ... len=0，接管因此永远找不到任务）。
    //   am start 输出小、回得快，偶尔能抢到，所以这个 bug 时好时坏，特别难查。
    private static volatile AdbClient shared;

    /** 用共享连接执行一条命令并返回输出；失败自动重连一次。 */
    public static synchronized String execFast(Context ctx, String command) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                // ★ 每次都新建连接，绝不复用：DisplayHost 的常驻进程已经占着一条连接的
                //   shell 流，复用会让它协议错乱（not A_WRTE / EOFException），所有后续命令
                //   全部失败 —— 表现为"虚拟屏上启动不了任何应用"、"接管搬不动"。
                //   这是我加"复用共享连接"优化时引入的回归，宁可多花一次握手也不能省。
                AdbClient c;
                SharedPreferences p = ctx.getSharedPreferences("builtinshell", Context.MODE_PRIVATE);
                c = new AdbClient(p.getString("host", "127.0.0.1"), p.getInt("port", 5555), key(ctx));
                c.connect();
                final StringBuilder sb = new StringBuilder();
                try {
                    c.shellCommand(command, out -> { sb.append(new String(out)); return null; });
                } finally {
                    try { c.close(); } catch (Throwable ig) {}
                }
                return sb.toString();
            } catch (Throwable t) {
                // ★ 以前只吞掉异常，外面只能看到"返回 null"，查了四轮都查不出原因。
                //   把异常本身写进日志，一眼就能看出是连不上、密钥不对还是流断了。
                AgentService.shotDebug("execFast 异常(第" + (attempt + 1) + "次): " + t);
                try { if (shared != null) shared.close(); } catch (Throwable ig) {}
                shared = null;   // 下次重连
            }
        }
        return null;
    }
}
