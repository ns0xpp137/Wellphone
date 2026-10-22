package local.wellphone.remote;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Wellphone —— 手机内的 agent 控制台 */
public class MainActivity extends Activity {
 static volatile MainActivity current;   // 通知栏按钮要回调到界面上的实例
 private static final int REQ_SPEECH = 1;
 private EditText input;
 private TextView status;
 private TextView logView;
 private final StringBuilder logBuf = new StringBuilder();
 private final Handler h = new Handler(Looper.getMainLooper());
 Agent agent;
 private Thread worker;
 private ThinkingWindow think;
 private Button btnWin, btnMirror, btnWatch;
 private TextView memTitle;
 private LinearLayout memList;
 private ScrollView pageScroll;

 private void showThink(){
  // 全局单例：已显示则只重绑按钮回调（Activity 重建不加第二个窗）
  if(ThinkingWindow.get().isShown()){
   ThinkingWindow.get().show(this, v -> stopTask(), v -> takeover());
   return;
  }
  // 用户主动隐藏过：Activity 重建不强行拉回
  if(ThinkingWindow.get().isUserHidden()){
   btnWin.setText("显示悬浮窗");
   return;
  }
  boolean ok = ThinkingWindow.get().show(this, v -> stopTask(), v -> takeover());
  if(ok){
   log("思考窗已显示（可拖拽/点击折叠）");
  }else{
   log("⚠️ 思考窗无法显示（需悬浮窗权限）");
  }
 }

 public void onCreate(Bundle b){
  super.onCreate(b);
  current = this; App.ctx = getApplicationContext();
  agent = new Agent();
  agent.maxSteps = stepsLimit();   // 必须在 new Agent() 之后，否则 NPE 会让 onCreate 直接崩（表现为"一个日志都没有"）
  LinearLayout root = new LinearLayout(this);
  root.setOrientation(LinearLayout.VERTICAL);
  root.setBackgroundColor(Color.parseColor("#f6f7f9"));
  int p = dp(16);
  root.setPadding(p,p,p,p);

  TextView title = new TextView(this);
  title.setText("Wellphone");
  title.setTextSize(22); title.setTextColor(Color.parseColor("#222222"));
  root.addView(title);

  TextView sub = new TextView(this);
  sub.setText("手机里的 agent · 虚拟屏幕后办事 · 随时接管");
  sub.setTextSize(12); sub.setTextColor(Color.parseColor("#888888"));
  sub.setPadding(0,0,0,dp(12));
  root.addView(sub);

  input = new EditText(this);
  input.setHint("输入任务，例如：在美团点一杯奶茶，停在结算页等我确认");
  input.setMinLines(2);
  // 悬浮窗那个输入框会一直占着 IME 的 served view，主界面这个就被顶掉、点不出键盘。
  // 这里主动把 IME 服务抢回来。
  View.OnFocusChangeListener imeGrab = (v, hasFocus) -> { if(hasFocus) showIme(); };
  input.setOnFocusChangeListener(imeGrab);
  input.setOnClickListener(v -> showIme());
  root.addView(input);

  LinearLayout row1 = new LinearLayout(this);
  row1.setOrientation(LinearLayout.HORIZONTAL);
  row1.addView(btn("发送任务", "#185fa5", v -> startTask()));
  row1.addView(btn("语音输入", "#0f6e56", v -> startSpeech()));
  root.addView(row1);

  LinearLayout row2 = new LinearLayout(this);
  row2.setOrientation(LinearLayout.HORIZONTAL);
  row2.addView(btn("接管（跳过去）", "#ba7517", v -> takeover()));
  row2.addView(btn("返回后台", "#888888", v -> backToAgent()));
  row2.addView(btn("停止", "#a32d2d", v -> stopTask()));
  root.addView(row2);

  // 步数上限：步数直接决定 token 消耗，所以让用户自己定（20~50）
  LinearLayout rowSteps = new LinearLayout(this);
  rowSteps.setOrientation(LinearLayout.HORIZONTAL);
  TextView stepLbl = new TextView(this);
  stepLbl.setText("步数上限"); stepLbl.setTextSize(11);
  stepLbl.setTextColor(Color.parseColor("#444444"));
  rowSteps.addView(stepLbl);
  final TextView stepVal = new TextView(this);
  stepVal.setText(stepsLimit() + " 步"); stepVal.setTextSize(11);
  stepVal.setTextColor(Color.parseColor("#2C2C2A"));
  stepVal.setPadding(dp(6), 0, dp(6), 0);
  SeekBar seek = new SeekBar(this);
  seek.setMax(50 - 20);
  seek.setProgress(stepsLimit() - 20);
  seek.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
  final int[] cur = { stepsLimit() };
  seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
   public void onProgressChanged(SeekBar sb, int prog, boolean fromUser){
    cur[0] = prog + 20;
    stepVal.setText(cur[0] + " 步");
    getSharedPreferences("wellphone", MODE_PRIVATE).edit().putInt("max_steps", cur[0]).apply();
    agent.maxSteps = cur[0];
    log("步数上限设为 " + cur[0] + "（步数越多，token 消耗越大）");
   }
   public void onStartTrackingTouch(SeekBar sb){}
   public void onStopTrackingTouch(SeekBar sb){}
  });
  rowSteps.addView(seek);
  rowSteps.addView(stepVal);
  root.addView(rowSteps);

  LinearLayout row25 = new LinearLayout(this);
  row25.setOrientation(LinearLayout.HORIZONTAL);
  btnWatch = btn("", "#712b13", v -> toggleMainWatch());
  row25.addView(btnWatch);
  row25.addView(btn("说明", "#5a5a6e", v -> explainMainWatch()));
  root.addView(row25);

  LinearLayout row3 = new LinearLayout(this);
  row3.setOrientation(LinearLayout.HORIZONTAL);
  btnWin = btn("隐藏悬浮窗", "#3c3489", v -> toggleThinkWindow());
  row3.addView(btnWin);
  row3.addView(btn("开启服务", "#085041", v -> openA11ySettings()));
  root.addView(row3);

  LinearLayout row4 = new LinearLayout(this);
  row4.setOrientation(LinearLayout.HORIZONTAL);
  row4.addView(btn("连接Shell通道", "#712b13", v -> connectShell()));
  row4.addView(btn("验证截图", "#4a1b0c", v -> verifyCapture()));
  root.addView(row4);

  LinearLayout row5 = new LinearLayout(this);
  row5.setOrientation(LinearLayout.HORIZONTAL);
  btnMirror = btn("隐藏镜像窗", "#3c3489", v -> toggleMirror());
  row5.addView(btnMirror);
  row5.addView(btn("镜像窗 位置", "#3c3489", v -> MirrorWindow.get().cycleCorner()));
  row5.addView(btn("镜像窗 大小", "#3c3489", v -> MirrorWindow.get().cycleSize()));
  row5.addView(btn("一键准备", "#085041", v -> prepareAll()));
  row5.addView(btn("诊断", "#5a2b0c", v -> diagnose()));
  root.addView(row5);

  LinearLayout draftRow = new LinearLayout(this);
  draftRow.addView(btn("验证微信草稿（不发送）", "#085041", v -> {
   if(worker != null && worker.isAlive()){ toast("有任务在跑"); return; }
   input.setText("打开微信，找到联系人赵禹哲，进入与他的会话，在消息输入框填写123。不要发送，填写完成就停止。");
   startTask("123");
  }));
  root.addView(draftRow);
  status = new TextView(this);
  showStatus("状态：空闲");
  status.setTextSize(14); status.setTextColor(Color.parseColor("#555555"));
  status.setPadding(0,dp(10),0,0);
  root.addView(status);

  // ===== 记忆区 =====
  // 跑过一遍才确定下来的事实：应用别名、可直达页面、偏好、踩过的坑。
  // 下次同 App 同类型任务直接复用，也避免在同一个地方再翻车。
  LinearLayout memHead = new LinearLayout(this);
  memHead.setOrientation(LinearLayout.HORIZONTAL);
  memTitle = new TextView(this);
  memTitle.setTextSize(13); memTitle.setTextColor(Color.parseColor("#222222"));
  memTitle.setPadding(0, dp(10), 0, 0);
  memTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
  memHead.addView(memTitle);
  memHead.addView(smallBtn("编辑原文", v -> editMemory()));
  memHead.addView(smallBtn("清空", v -> { MemoryStore.clear(this); renderMemory(); log("记忆已清空"); }));
  root.addView(memHead);

  final ScrollView memScroll = new ScrollView(this);
  // 嵌套在外层 ScrollView 里，不抢的话内层根本滚不动
  memScroll.setOnTouchListener((v, ev) -> {
   if(pageScroll != null) pageScroll.requestDisallowInterceptTouchEvent(true);
   return false;
  });
  memList = new LinearLayout(this);
  memList.setOrientation(LinearLayout.VERTICAL);
  memList.setBackgroundColor(Color.parseColor("#eef0f4"));
  memList.setPadding(dp(8), dp(4), dp(8), dp(4));
  memScroll.addView(memList);
  memScroll.setLayoutParams(new LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, dp(84)));
  root.addView(memScroll);
  renderMemory();
  refreshWatchBtn();

  logView = new TextView(this);
  logView.setTextSize(11); logView.setTextColor(Color.parseColor("#d8dee9"));
  logView.setBackgroundColor(Color.parseColor("#1e1e2e"));
  logView.setPadding(dp(10),dp(8),dp(10),dp(8));
  LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
  lp.topMargin = dp(10);
  logView.setLayoutParams(lp);
  ScrollView sv = new ScrollView(this);
  sv.addView(logView);
  root.addView(sv);
  // 整个页面套一层 ScrollView：按钮行+记忆区+日志已经超过一屏，不套就够不到底部
  pageScroll = new ScrollView(this);
  pageScroll.addView(root);
  setContentView(pageScroll);

  think = ThinkingWindow.get();
  if(think != null) think.setBackListener(v -> backToAgent());
  think.setOnSendListener(task -> {
   // 用户在这时候说的话，不一定是新任务——可能是在纠正我刚才点错了。
   // 分不清就把"记住"当任务执行，会把用户的指认当成操作去做，越搞越乱。
   if(looksLikeCorrection(task)){ rememberCorrection(task); return; }
   // "继续搜索"/"接着拍照"/"换个关键词" 是延续当前任务，不是新任务。
   // 这条在 App 里用规则判就够了，不用花一次模型调用：规则能覆盖绝大多数说法，
   // 只有"延续句里冒出新应用名"这种才需要模型帮忙——那种情况直接当新任务也不会出事。
   if(isContinuation(task) && worker != null && worker.isAlive()){
    if(agent.paused){ agent.resume(); log("▶ 已继续"); }
    agent.inject(task);
    log("» 延续当前任务：" + task);
    toast("接着刚才的任务");
    return;
   }
   input.setText(task);
   startTask();
  });
  showThink();
  agent.tokens = new Agent.TokenSink(){
   public void onToken(String s){ h.post(() -> think.append(s)); }
   public void onStep(String s){ h.post(() -> think.step(s)); }
  };

  refreshControlNotification();
  bootstrap();

  handleIntentExtras(getIntent());
 }

 protected void onNewIntent(Intent it){
  super.onNewIntent(it);
  setIntent(it);
  // App 在后台常驻时 adb 的 am start 走的是 onNewIntent，不是 onCreate——钩子两边都要挂，
  // 否则"明明下发了任务却没反应"，排查起来很费劲。
  handleIntentExtras(it);
 }

 /** 自动化钩子：adb shell am start -n local.wellphone.remote/.MainActivity
  *    --es task "百度地图导航到故宫" --ez autorun true   下发并立即执行
  *    --ez diag true                                    跑虚拟屏诊断
  *  省去手动输字；演示录像时也能一键起跑。 */
 private void handleIntentExtras(Intent it){
  if(it == null) return;
  final String t = it.getStringExtra("task");
  final boolean autorun = it.getBooleanExtra("autorun", false);
  final boolean diag = it.getBooleanExtra("diag", false);
  final String stopText = it.getStringExtra("stopText");   // 写到这句就停，绝不发送（安全阀）
  if(it.getBooleanExtra("watch", false)){                  // 一键开启主屏监控（演示/自测用）
   getSharedPreferences("wellphone", MODE_PRIVATE).edit().putBoolean("main_watch", true).apply();
   refreshWatchBtn();
  }
  if(t != null && !t.isEmpty()){
   input.setText(t);
   if(autorun) h.postDelayed(() -> {
    if(worker != null && worker.isAlive()){      // 上一条还在跑就先收掉，否则只会弹"有任务在跑"
     agent.stop();
     try{ worker.join(2000); }catch(Throwable ignored){}
    }
    startTask(stopText);
   }, 4000);   // 等虚拟屏与 Shizuku 就绪
  }
  if(diag) h.postDelayed(() -> diagnose(), 4000);
  // 自测开关：用与通知按钮完全相同的 PendingIntent 触发一遍，验证投递链路
  if(it.getBooleanExtra("testctl", false)) h.postDelayed(() -> selfTestControls(), 5000);
 }

 /** 启动自检：Shizuku → 无障碍 → 虚拟屏。能自动的都自动，绕不过的那一步说清楚。 */
 private volatile boolean bootstrapping = false;
 void bootstrap(){
  if(bootstrapping) return;
  // 全就绪就别再跑一遍（onCreate 和 onResume 都会触发，否则日志会成对重复）
  if(AgentService.instance != null && agent.displayId > 0){ refreshControlNotification(); return; }
  bootstrapping = true;
  new Thread(() -> {
   try{
    if(!ShellChannel.binderReady()){
     logSafe("① Shizuku 没在运行 —— 这一步必须你授权一次（安卓安全边界，App 自己绕不过）");
     logSafe("  点「一键准备」会打开 Shizuku，在里面点『通过无线调试启动』。之后开机会自动恢复");
     h.post(() -> showStatus("状态：等待 Shizuku 授权"));
     return;
    }
    logSafe("① Shizuku 已运行");
    if(!ShellChannel.permissionGranted()){
     logSafe("② 正在申请 Shizuku 权限，请在弹窗里点「允许」");
     ShellChannel.requestPermission();
     return;
    }
    logSafe("② Shizuku 权限已授予");
    boolean a11y = enableA11yByShell();
    logSafe(a11y ? "③ 无障碍服务已自动开启" : "③ 无障碍服务没开成，请在 设置→无障碍→Wellphone 手动开一次");
    ensureService();
    h.post(() -> { if(agent.displayId <= 0) ensureDisplay(); else refreshControlNotification(); });
   }catch(Throwable t){ logSafe("自检异常 " + t); }
   finally{ bootstrapping = false; }
  }).start();
 }

 /** 用 shell 自动绑定无障碍服务。本 ROM 必须"先清空再写回"才会真正绑定。 */
 private boolean enableA11yByShell(){
  try{
   if(AgentService.instance != null) return true;
   String me = getPackageName() + "/" + AgentService.class.getName();
   String cur = ShellChannel.raw("settings get secure enabled_accessibility_services", 6000);
   // ★ 必须清洗：settings put ... '' 实际会写成字面量 null，攒几轮就变成
   //   "null:pkg/Cls" —— 这个无效前缀会让系统解析整个列表失败，服务永远绑不上。
   //   （这条坑导致"无障碍服务开启失败"反复出现，且表现得像随机故障。）
   LinkedHashSet<String> segs = new LinkedHashSet<String>();
   if(cur != null){
    for(String seg : cur.split("[:\n]")){
     String t = seg.trim();
     if(t.isEmpty()) continue;
     if(!t.contains("/")) continue;      // 丢掉 null 这类不是 pkg/Cls 的段
     if(t.startsWith("null")) continue;
     segs.add(t);
    }
   }
   segs.add(me);
   StringBuilder sb = new StringBuilder();
   for(String t : segs){ if(sb.length() > 0) sb.append(":"); sb.append(t); }
   String val = sb.toString();
   // 绑定有竞争：实测有时一轮写回后要等 8 秒以上才 bind 上，所以重试三轮
   for(int round = 0; round < 3 && AgentService.instance == null; round++){
    ShellChannel.exec("settings delete secure enabled_accessibility_services", 6000);
    Thread.sleep(900);
    String r1 = ShellChannel.raw("settings put secure enabled_accessibility_services "
      + ShellChannel.quote(val) + " 2>&1", 6000);
    String r2 = ShellChannel.raw("settings put secure accessibility_enabled 1 2>&1", 6000);
    String now = ShellChannel.raw("settings get secure enabled_accessibility_services 2>&1", 6000);
    logSafe("③ 写回结果 put=" + r1 + " enabled=" + r2 + " 现值=" + now);
    for(int i = 0; i < 25 && AgentService.instance == null; i++) Thread.sleep(400);
   }
   return AgentService.instance != null;
  }catch(Throwable t){ return false; }
 }

 /** 一键准备：缺哪一步就补哪一步；Shizuku 没起来就把用户送过去 */
 private void prepareAll(){
  if(!ShellChannel.binderReady()){
   log("未检测到 Shizuku，正在打开…");
   toast("在 Shizuku 里点『通过无线调试启动』，完成后回来");
   try{
    Intent i = new Intent();
    i.setClassName("moe.shizuku.privileged.api", "moe.shizuku.privileged.api.MainActivity");
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(i);
   }catch(Throwable t){ log("打不开 Shizuku：装了没？" + t); }
   return;
  }
  log("开始一键准备：Shizuku → 无障碍 → 虚拟屏");
  bootstrap();
 }

 private void ensureService(){
  // 无障碍服务需在系统设置里开启一次
  boolean on = false;
  try{
   String s = Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
   on = s != null && s.contains(getPackageName());
  }catch(Exception e){}
  if(!on) log("⚠️ 无障碍服务未开启：设置→无障碍→Wellphone 服务→开启");
  else log("无障碍服务已开启");
 }

 private void ensureDisplay(){
  new Thread(() -> {
   int id = agent.ensureDisplay(this);
   agent.enableSecondaryLaunch();
   h.post(() -> {
    if(id > 0){
     log("虚拟屏就绪 display=" + id);
     boolean ok = MirrorWindow.get().show(this, id);
     log(ok ? "镜像窗已开启（左上角，可切位置/大小）" : "⚠️ 镜像窗无法显示（需悬浮窗权限）");
    }else log("⚠️ 虚拟屏创建失败：先点「连接Shell通道」把 Shizuku 跑起来");
   });
  }).start();
 }

 /** 诊断：把副屏开关、目标 App 组件、虚拟屏实况都落进日志，百度地图这类起不来的 App 用它定位 */
 private void diagnose(){
  String app = input.getText().toString().trim();
  if(app.isEmpty()) app = "百度地图";
  final String target = app;
  new Thread(() -> {
   logSafe("—— 诊断 " + target + " ——");
   logSafe("副屏开关: " + ShellChannel.raw("settings get global force_resizable_activities 2>&1", 5000));
   String pkgs = ShellChannel.raw("pm list packages 2>/dev/null | grep -i baidu | head -8", 8000);
   logSafe("手机里的地图包: " + (pkgs == null ? "shell 不可用" : pkgs.trim().replace("\n", " ")));
   logSafe("目标包名: " + agent.resolvePkg(this, target, s2 -> logSafe(s2)));
   AgentService svc = AgentService.instance;
   if(svc == null){ logSafe("无障碍服务未连接，无法读窗口"); return; }
   if(agent.ensureDisplay(this) <= 0){ logSafe("虚拟屏未就绪"); return; }
   logSafe("虚拟屏实况(尝试前): " + svc.dumpWindows(agent.displayId));
   int id = agent.displayId;
   String probe = ShellChannel.raw("am start --display " + id + " -f 0x18000000 -a android.intent.action.MAIN "
       + "-c android.intent.category.LAUNCHER -p " + ShellChannel.quote(agent.resolvePkg(this, target, s2 -> {}))
       + " 2>&1", 15000);
   logSafe("am start 原始输出: " + (probe == null ? "shell 不可用" : probe.trim().replace("\n", " ")));
   try{ Thread.sleep(6000); }catch(Throwable t){}
   logSafe("虚拟屏实况(尝试后): " + svc.dumpWindows(agent.displayId));
  }).start();
 }

 private void verifyCapture(){
  if(worker != null && worker.isAlive()){ toast("请先停止当前任务"); return; }
  worker = new Thread(() -> {
   try{
    AgentService svc = AgentService.instance;
    if(svc == null){ logSafe("验证失败：无障碍服务未连接"); return; }
    agent.stopped = false;
    if(agent.ensureDisplay(this) <= 0 || !agent.launchOnVirtual(this, "计算器", this::logSafe)){
     logSafe("验证失败：计算器未在虚拟屏启动"); return;
    }
    for(int n=0;n<3;n++){
     byte[] shot = agent.awaitShot(svc);
     logSafe(shot == null ? "验证失败：" + svc.captureError : "窗口截图验证成功：" + shot.length + " bytes");
     if(shot == null) return;
     Thread.sleep(1000);
    }
   }catch(Throwable e){ logSafe("验证异常：" + e); }
  });
  worker.start();
 }

 /** 这段话是"要我现在去做"，还是"在纠正我/教我"？
  *  判断依据：指认句（XX才是YY / 应该点ZZ / 不对 / 别点 / 记住 / 以后）都算纠正。 */
 static boolean looksLikeCorrection(String s){
  if(s == null || s.isEmpty()) return false;
  String[] keys = {"才是", "应该是", "应该点", "不对", "错了", "点错", "别点", "不要点",
                   "要选", "不是这个", "是这个", "记住", "记一下", "以后", "下次", "别选", "要点"};
  for(String k : keys) if(s.contains(k)) return true;
  return false;
 }

 /** 把用户的纠正写进记忆（guide 类型），下次到同一类页面就不会再点错 */
 private void rememberCorrection(String s){
  String pkg = agent.currentPkg == null || agent.currentPkg.isEmpty() ? "*" : agent.currentPkg;
  String key = pkg + "|" + Agent.pageKey(agent.latestUi);
  MemoryStore.add(this, MemoryStore.TYPE_GUIDE, key, s, "用户纠正");
  renderMemory();
  log("👂 听清了，这是纠正不是任务 → 已记进记忆区：\n  " + s);
  toast("记住了，下次照做");
  if(think != null) think.append("\n» 记住了（不是任务）：" + s + "\n");
  refreshControlNotification();
 }

 private void startTask(){ startTask(null); }
 private void startTask(String stopAfterText){
  String t = input.getText().toString().trim();
  if(t.isEmpty()){ toast("先输入任务"); return; }
  if(worker != null && worker.isAlive()){ toast("有任务在跑"); return; }
  if(AgentService.instance == null){ toast("请先开启无障碍服务"); return; }
  agent.diagnosticStopText = stopAfterText;
  log("▶ " + t);
  if(think != null){ think.setExpanded(true); think.reset("任务: " + t); }
  takeoverActive = false;
  takeoverTaskId = null;
  final boolean watchMode = isWatchTask(t);
  if(watchMode && mainWatchOn() && think != null) think.setStepLabel("监控中 · 看你的屏幕");
  if(watchMode && !mainWatchOn()){
   log("⚠️ 这是「等到条件就出手」的任务，需要看你的屏幕。请先打开「主屏监控」开关。");
   toast("请先打开「主屏监控」开关");
   return;
  }
  worker = new Thread(() -> { try{
    if(watchMode) agent.runWatch(getApplicationContext(), t, AgentService.instance, this::logSafe,
      (state, msg) -> h.post(() -> {
        if("review".equals(state)) showStatus("状态：已完成 ｜ " + msg);
        else if("err".equals(state)) showStatus("状态：出错 ｜ " + msg);
        else if("idle".equals(state)) showStatus("状态：空闲");
        else showStatus("状态：监控中");
      }));
    else agent.run(getApplicationContext(), t, AgentService.instance,
      this::logSafe, (state, msg) -> h.post(() -> {
        if("takeover".equals(state)){ showStatus("状态：等你处理 ｜ " + msg); toast("需要你处理：" + msg); alertFinish(true); }
        else if("limit".equals(state)) showStatus("状态：" + msg);
        else if("review".equals(state)){ showStatus("状态：模型判断完成，请核对结果"); alertFinish(false); }
        else if("finish".equals(state)) showStatus("状态：已完成");
        else if("err".equals(state)) showStatus("状态：出错 ｜ " + msg);
        else if("idle".equals(state)) showStatus("状态：空闲");
        else showStatus("状态：" + state);
      })); }catch(Throwable e){
       logSafe("任务异常，已停止: " + e);
       h.post(() -> showStatus("状态：执行异常，详情见日志"));
      }});
  worker.start();
 }
 private String status(String s){ return s; }

 public void stopTask(){
  agent.stop();
  log("⏹ 已停止任务");
  showStatus("状态：空闲");
  toast("已停止");
 }
 private void toggleThinkWindow(){
  if(ThinkingWindow.get().isShown()){
   think.hide();
   btnWin.setText("显示悬浮窗");
   log("悬浮窗已隐藏");
  }else{
   boolean ok = think.show(this, v -> stopTask(), v -> takeover());
   btnWin.setText(ok ? "隐藏悬浮窗" : "显示悬浮窗");
   log(ok ? "悬浮窗已显示" : "悬浮窗显示失败（可能被系统策略隐藏）");
  }
 }
 private void connectShell(){
  if(!ShellChannel.binderReady()){
   log("⚠️ Shizuku 未连接：请先打开 Shizuku 应用，用「无线调试」启动服务");
   toast("先打开 Shizuku 启动服务");
   try{
    Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
    if(i != null){ startActivity(i); log("已打开 Shizuku"); }
   }catch(Throwable t){}
   return;
  }
  if(ShellChannel.permissionGranted()){
   log("Shell 通道已就绪（藏树 App 走 shell 注入）");
   toast("Shell 通道已就绪");
   return;
  }
  ShellChannel.requestPermission();
  log("已请求 Shizuku 授权，请在弹窗中允许");
 }

 private void testShellTap(){
  new Thread(() -> {
   boolean ok = ShellChannel.tap(agent.displayId, agent.VW/2, agent.VH/2);
   h.post(() -> {
    log(ok ? "Shell 点击成功（虚拟屏中心）" : "Shell 点击失败：" +
      (ShellChannel.ready() ? "执行错误" : "通道未就绪，先点「连接Shell通道」"));
    toast(ok ? "Shell 点击 OK" : "Shell 点击失败");
   });
  }).start();
 }

 private void openA11ySettings(){
  try{
   Intent i = new Intent("android.settings.ACCESSIBILITY_SETTINGS");
   i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
   startActivity(i);
   log("已打开无障碍设置：请找到「Wellphone 服务」并开启");
   toast("找到「Wellphone 服务」并开启");
  }catch(Exception e){ log("打开设置失败: " + e); }
 }
 volatile boolean takeoverActive = false;
 volatile boolean mirrorWasOn = false;
 // 接管时被搬到主屏的那个任务 id，返回后台时要搬回工作区
 private volatile String takeoverTaskId = null;
 public void takeover(){
  // 已经交给你了就别再跳一次——主屏上现在就是目标 App，再跳只会又开一个没加载完的
  if(takeoverActive){
   log("已经交给你了，主屏上就是目标应用");
   toast("已经交给你了");
   return;
  }
  boolean busy = worker != null && worker.isAlive();
  agent.paused = true;   // 立即在当前步边界暂停（含思考结束时）
  takeoverActive = true;
  // 用户亲自上手了，工作区镜像就该收起来——不然交给你之后它还播着上一个任务的画面
  mirrorWasOn = MirrorWindow.get().isShown();
  if(mirrorWasOn) MirrorWindow.get().hide();
  agent.preGrant(agent.currentPkg);
  // 首选：把 agent 正在用的那个任务整块搬到你屏幕上（同一个实例，界面状态不丢）
  String tid = agent.takeoverMoveToMain();
  if(tid != null){
   takeoverTaskId = tid;
   log("🤝 已把工作区搬到你的屏幕（同一个实例，状态原样保留）");
   showStatus("状态：等你处理（手动接管）");
   toast("已接管，界面就是你交给 agent 的那个");
  }else{
   // 退路：搬不动才另开实例（那样会丢状态、多一个后台，实在没办法才用）
   agent.openOnUserScreen(getApplicationContext());
   log("接管：任务搬移失败，改为在你屏幕新开一个实例（状态不连续）");
   showStatus("状态：等你处理（新建实例）");
  }
 }
 public void backToAgent(){
  takeoverActive = false;
  log("返回后台：开始");
  // 任务搬回工作区。不依赖 takeoverTaskId 是否记得住——
  // 没记录就按当前应用现找它的任务，照样搬（悬浮窗按钮以前"点了没反应"就是因为
  // backToAgent 只认那两个标志，没接管过时它们都是空的，整段代码等于没跑）。
  String tid = takeoverTaskId;
  if(tid == null && agent.currentPkg != null && !agent.currentPkg.isEmpty())
   tid = agent.findTaskId(agent.currentPkg);
  if(tid != null){
   boolean ok = agent.moveTaskBack(tid) != null;
   log(ok ? "↩ 已把任务搬回后台工作区" : "⚠️ 搬回工作区失败");
  }else{
   log("（没有需要搬回的任务）");
  }
  takeoverTaskId = null;
  // 镜像窗：没开着就恢复，同样不依赖记忆标志
  if(agent.displayId > 0 && !MirrorWindow.get().isShown()){
   MirrorWindow.get().show(this, agent.displayId);
   log("镜像窗已恢复");
  }
  mirrorWasOn = false;
  agent.resume();
  showStatus("状态：已回到后台");
  toast("已回到后台");
  refreshControlNotification();
  boolean busy = worker != null && worker.isAlive();
  log(busy ? "▶ 已返回后台，agent 继续" : "返回后台（无进行中的任务）");
  showStatus(busy ? "状态：运行中" : "状态：空闲");
 }

 private void startSpeech(){
  try{
   Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
   i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
   i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
   i.putExtra(RecognizerIntent.EXTRA_PROMPT, "说出任务");
   startActivityForResult(i, REQ_SPEECH);
  }catch(Exception e){ toast("本机无语音识别，请直接打字"); }
 }
 protected void onActivityResult(int req,int res,Intent data){
  super.onActivityResult(req,res,data);
  if(req == REQ_SPEECH && res == RESULT_OK && data != null){
   ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
   if(r != null && !r.isEmpty()){ input.setText(r.get(0)); toast("已填入，点「发送任务」"); }
  }
 }

 private void logSafe(String s){ h.post(() -> log(s)); }
 private void log(String s){
  logBuf.append(s).append("\n");
  if(logBuf.length() > 12000) logBuf.delete(0, 6000);
  logView.setText(logBuf.toString());
  // 同步落盘（运行记录，可经 adb 读取）
  try{
   java.io.File f = new java.io.File(getExternalFilesDir(null), "run-log.txt");
   java.io.FileWriter fw = new java.io.FileWriter(f, true);
   fw.write(System.currentTimeMillis() + " " + s + "\n");
   fw.close();
  }catch(Throwable t){}
 }
 /** 常驻通知：悬浮窗/镜像窗开关 + 接管 + 停止。
  *  浮窗会盖住自己 App 的按钮行，下拉通知栏不受遮挡，四个常用开关放这里最顺手。 */
 void refreshControlNotification(){
  try{
   if(Build.VERSION.SDK_INT >= 33
      && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
         != android.content.pm.PackageManager.PERMISSION_GRANTED){
    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 77);
    return;
   }
   NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
   if(nm == null) return;
   if(Build.VERSION.SDK_INT >= 26){
    NotificationChannel ch = new NotificationChannel(
      "wellphone_ctl", "Wellphone 控制", NotificationManager.IMPORTANCE_DEFAULT);
    ch.setDescription("悬浮窗 / 镜像窗 开关，接管 与 停止");
    nm.createNotificationChannel(ch);
   }
   String sub = (worker != null && worker.isAlive()) ? "工作中" : "空闲";
   sub += " ｜ 悬浮窗" + (ThinkingWindow.get().isShown() ? "开" : "关")
       + " 镜像窗" + (MirrorWindow.get().isShown() ? "开" : "关");
   // 隐私指示：只要开关开着，通知里就一直显示，用户随时知道 agent 有能力看他的屏幕
   if(mainWatchOn()) sub += " ｜ ⚠ 主屏监控已开";
   Notification.Builder nb = new Notification.Builder(this)
     .setContentTitle("Wellphone · 后台工作区")
     .setContentText(sub)
     .setSmallIcon(android.R.drawable.ic_menu_view)
     .setOngoing(true)
     .setOnlyAlertOnce(true)
     .setPriority(Notification.PRIORITY_MAX)
     .setCategory(Notification.CATEGORY_SERVICE);
   if(Build.VERSION.SDK_INT >= 26) nb.setChannelId("wellphone_ctl");
   nb.addAction(btn("悬浮窗", "think"));
   nb.addAction(btn("镜像窗", "mirror"));
   nb.addAction(btn("接管", "takeover"));
   nb.addAction(btn("停止", "stop"));
   nm.notify(1001, nb.build());
  }catch(Throwable t){}
 }
 private Notification.Action btn(String label, String key){
  // 必须显式指定接收器类目：只用 action+package 是隐式广播，
  // Android 8+ 起 manifest 声明的接收器收不到隐式广播（所以 adb 用 -n 能成、通知栏按了没反应）
  // action 带上自己的包名：正式版和开发版同时装着时，通知栏广播不会串到对方那边去
  Intent i = new Intent(this, ControlReceiver.class)
    .setAction(getPackageName() + ".CONTROL")
    .putExtra(ControlReceiver.EXTRA, key);
  PendingIntent pi = PendingIntent.getBroadcast(this, key.hashCode(), i,
    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  return new Notification.Action.Builder(
    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_view),
    label, pi).build();
 }

 /** 自测：拿通知里那几个 PendingIntent 直接 send()，走的就是点按钮时那条投递路径 */
 void selfTestControls(){
  for(String key : new String[]{"think", "think", "mirror", "mirror"}){
   try{
    PendingIntent pi = PendingIntent.getBroadcast(this, key.hashCode(),
      new Intent(this, ControlReceiver.class).setAction(ControlReceiver.ACTION)
        .putExtra(ControlReceiver.EXTRA, key),
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    pi.send();
    log("自测：已投递 " + key);
   }catch(Throwable t){ log("自测失败 " + key + " " + t); }
   try{ Thread.sleep(1200); }catch(Throwable ig){}
  }
 }

 /** 是不是"接着刚才那件事继续"，而不是一个新任务 */
 static boolean isContinuation(String s){
  if(s == null || s.isEmpty()) return false;
  String[] keys = {"继续", "接着", "往下", "换一个", "换个", "重新", "再试", "试试", "再来"};
  for(String k : keys) if(s.contains(k)) return true;
  return s.startsWith("然后") || s.startsWith("再") || s.startsWith("接着");
 }

 /** 判断是不是"等到某个条件就出手"的监控型任务。
  *  这类任务不能套"走 N 步完成"的循环，得持续看屏幕等条件。 */
 static boolean isWatchTask(String t){
  if(t == null) return false;
  String[] keys = {"当", "一旦", "出现", "就拍", "飞过", "一直", "持续", "监控", "等到",
                   "的时候", "就发", "就点", "喊到", "说到", "说出"};
  for(String k : keys) if(t.contains(k)) return true;
  return false;
 }

 /** 主屏监控开关：默认关。用户自己打开后，agent 才能在"协同模式"下看用户屏幕。 */
 private boolean mainWatchOn(){
  return getSharedPreferences("wellphone", MODE_PRIVATE).getBoolean("main_watch", false);
 }
 private void toggleMainWatch(){
  boolean now = !mainWatchOn();
  getSharedPreferences("wellphone", MODE_PRIVATE).edit().putBoolean("main_watch", now).apply();
  refreshWatchBtn();
  log(now ? "主屏监控已开启 —— 之后「当…就…」这类任务，agent 会看你的屏幕"
          : "主屏监控已关闭 —— agent 只在自己的后台工作区里干活，看不到你的屏幕");
  refreshControlNotification();
 }
 private void refreshWatchBtn(){
  if(btnWatch == null) return;
  btnWatch.setText(mainWatchOn() ? "主屏监控：开" : "主屏监控：关");
 }
 private void explainMainWatch(){
  log("—— 两种工作模式 ——");
  log("工作区模式（默认）：agent 在后台虚拟屏自己干活，完全看不到你的屏幕，也不会动它。");
  log("协同模式（需你打开开关）：agent 看你正在看的画面，用来做「等到某个条件就出手」的任务——");
  log("  例如「打开相机，有鸟飞过就按快门」「看直播，主播说抠 234 就发弹幕」。");
  log("开关在你手上，关着的时候 agent 绝不看你的屏幕。");
 }

 /** 完成或需要接管时，震一下 + 响一声。
  *  agent 在后台干活，用户可能在看别的东西，不给个反馈就永远不知道它停在哪了。 */
 private void alertFinish(boolean urgent){
  try{
   android.os.Vibrator v = (android.os.Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
   if(v != null){
    if(android.os.Build.VERSION.SDK_INT >= 26){
     v.vibrate(android.os.VibrationEffect.createWaveform(
       new long[]{0, urgent ? 200 : 130, 90, urgent ? 200 : 130}, -1));
    }else{
     v.vibrate(urgent ? new long[]{0, 200, 90, 200} : new long[]{0, 130, 90, 130}, -1);
    }
   }
   final android.media.ToneGenerator tg = new android.media.ToneGenerator(
     android.media.AudioManager.STREAM_NOTIFICATION, 70);
   tg.startTone(urgent ? android.media.ToneGenerator.TONE_PROP_PROMPT
                       : android.media.ToneGenerator.TONE_PROP_ACK, 220);
   h.postDelayed(() -> { try{ tg.release(); }catch(Throwable ig){} }, 900);
  }catch(Throwable t){}
 }

 /** 状态同时写到 App 里那一行和悬浮窗上。
  *  以前出错只能切回 App 才能看到，悬浮窗那个状态行就是为这个加的。 */
 private void showStatus(String s){
  try{ if(status != null) status.setText(s); }catch(Throwable t){}
  try{ ThinkingWindow.get().setStatusText(s); }catch(Throwable t){}
 }

 /** 步数上限，默认 40，范围 20~50 */
 int stepsLimit(){
  int v = getSharedPreferences("wellphone", MODE_PRIVATE).getInt("max_steps", 40);
  return Math.max(20, Math.min(50, v));
 }

 private void toggleMirror(){
  if(MirrorWindow.get().isShown()){
   MirrorWindow.get().hide();
   btnMirror.setText("显示镜像窗");
   log("镜像窗已隐藏");
  }else{
   boolean ok = MirrorWindow.get().show(this, agent.displayId);
   btnMirror.setText(ok ? "隐藏镜像窗" : "显示镜像窗");
   log(ok ? "镜像窗已显示" : "镜像窗显示失败");
  }
 }
 /** 主界面输入框抢回输入法 */
 private void showIme(){
  ThinkingWindow.get().releaseInput();
  final android.view.inputmethod.InputMethodManager imm =
    (android.view.inputmethod.InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
  if(imm == null) return;
  // 虚拟屏上的 App 会把 App 级焦点（mFocusedApp）抢走，主界面这个 EditText 的
  // IME 请求就被系统丢给 DecorView 了，键盘弹不出来。这里主动把焦点和输入法一起抢回来。
  getWindow().getDecorView().requestFocus();
  input.requestFocus();
  try{ imm.restartInput(input); }catch(Throwable t){}
  input.post(() -> imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_FORCED));
  // IMM 可能还在处理焦点切换，再补一次
  input.postDelayed(() -> imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_FORCED), 350);
 }

 protected void onResume(){
  super.onResume();
  // 从 Shizuku 授权界面回来时自动接着往下走
  if(AgentService.instance == null || agent.displayId <= 0) h.postDelayed(() -> bootstrap(), 1200);
  // 回到前台时只收回 View 焦点，不主动弹键盘（那样太烦）
  try{ getWindow().getDecorView().requestFocus(); }catch(Throwable t){}
 }
 /** 记忆区渲染成条目列表：每行「类型 叫法 → 实际值（备注）」+ 一个 × */
 private void renderMemory(){
  if(memList == null) return;
  memList.removeAllViews();
  List<MemoryStore.Entry> es = MemoryStore.all(this);
  memTitle.setText("记忆区 · " + es.size() + " 条");
  if(es.isEmpty()){
   TextView t = new TextView(this);
   t.setTextSize(11); t.setTextColor(Color.parseColor("#888888"));
   t.setText("还没有记录。跑完一个任务后，这里会记下应用叫法、可直达页面和踩过的坑。");
   memList.addView(t);
   return;
  }
  for(int i = 0; i < es.size(); i++){
   final int idx = i;
   MemoryStore.Entry e = es.get(i);
   LinearLayout row = new LinearLayout(this);
   row.setOrientation(LinearLayout.HORIZONTAL);
   row.setPadding(0, dp(2), 0, dp(2));
   TextView t = new TextView(this);
   t.setTextSize(11);
   t.setTextColor(MemoryStore.TYPE_AVOID.equals(e.type)
     ? Color.parseColor("#a32d2d") : Color.parseColor("#333333"));
   String tag = MemoryStore.TYPE_AVOID.equals(e.type) ? "⚠ " :
                MemoryStore.TYPE_PAGE.equals(e.type) ? "↗ " :
                MemoryStore.TYPE_PREF.equals(e.type) ? "★ " : "";
   t.setText(tag + e.key + " → " + e.value + (e.note == null || e.note.isEmpty() ? "" : "（" + e.note + "）"));
   t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
   row.addView(t);
   Button x = new Button(this);
   x.setText("×"); x.setTextSize(11); x.setTextColor(Color.parseColor("#a32d2d"));
   x.setBackgroundColor(Color.TRANSPARENT);
   x.setPadding(0, 0, 0, 0);
   x.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(28)));
   x.setOnClickListener(v -> { MemoryStore.remove(this, idx); renderMemory(); });
   row.addView(x);
   memList.addView(row);
  }
 }

 /** 编辑原文：直接改 JSON，改坏了不会落盘 */
 private void editMemory(){
  final EditText et = new EditText(this);
  et.setTextSize(11);
  et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
  et.setMinLines(8);
  et.setText(MemoryStore.rawJson(this));
  new android.app.AlertDialog.Builder(this)
    .setTitle("记忆原文（JSON）")
    .setView(et)
    .setPositiveButton("保存", (d, w) -> {
      boolean ok = MemoryStore.replaceRaw(this, et.getText().toString());
      renderMemory();
      toast(ok ? "已保存" : "JSON 格式不对，没有保存");
    })
    .setNegativeButton("取消", null)
    .show();
 }

 private Button smallBtn(String text, View.OnClickListener fn){
  Button b = new Button(this);
  b.setText(text); b.setTextSize(10); b.setTextColor(Color.WHITE);
  b.setBackgroundColor(Color.parseColor("#5a5a6e"));
  b.setPadding(dp(6), 0, dp(6), 0);
  LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.WRAP_CONTENT, dp(26));
  lp.setMargins(dp(4), dp(4), 0, 0);
  b.setLayoutParams(lp);
  b.setOnClickListener(fn);
  return b;
 }

 private String loadMemory(){
  try{
   java.io.File f = new java.io.File(getExternalFilesDir(null), "memory.txt");
   if(!f.exists()) return "（暂未记录）跑完任务后会记下这个 App 的入口路径和关键控件，下次直接复用。";
   StringBuilder sb = new StringBuilder();
   java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
   String line; int n = 0;
   while((line = r.readLine()) != null && n++ < 40) sb.append(line).append("\n");
   r.close();
   return sb.length() == 0 ? "（暂无记录）" : sb.toString();
  }catch(Throwable t){ return "（读取记忆失败）"; }
 }
 /** 供后续记忆功能追加一条；现在先不写内容 */
 static void appendMemory(android.content.Context ctx, String line){
  if(line == null || line.isEmpty()) return;
  try{
   java.io.File f = new java.io.File(ctx.getExternalFilesDir(null), "memory.txt");
   java.io.FileWriter fw = new java.io.FileWriter(f, true);
   fw.write(line + "\n"); fw.close();
  }catch(Throwable t){}
 }

 private Button btn(String text, String color, View.OnClickListener fn){
  Button b = new Button(this);
  b.setText(text); b.setTextColor(Color.WHITE);
  b.setBackgroundColor(Color.parseColor(color));
  LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
  lp.setMargins(dp(4),dp(8),dp(4),dp(8));
  b.setLayoutParams(lp); b.setOnClickListener(fn);
  return b;
 }
 private int dp(int v){ return (int)(v * getResources().getDisplayMetrics().density); }
 private void toast(String s){ Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
