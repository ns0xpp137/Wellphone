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
 private Button btnWin, btnMirror, btnWatch, btnBrain;
 private TextView memTitle;
 private LinearLayout memList;
 private ScrollView pageScroll;

 private void showThink(){ showThink(false); }
 /** force=true 表示"用户刚发起任务，他想看过程"——这时即便之前被收起过也要重新显示。
  *  否则会出现：点了悬浮窗上的「收起悬浮窗」，再跑任务时 step 1..N 全都不显示
  *  （append/step 里都有 if(!shown) return，根本没地方画）。 */
 private void showThink(boolean force){
  // 全局单例：已显示则只重绑按钮回调（Activity 重建不加第二个窗）
  if(ThinkingWindow.get().isShown()){
   ThinkingWindow.get().show(this, v -> stopTask(), v -> takeover());
   return;
  }
  if(force) ThinkingWindow.get().clearUserHidden();
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
  try{ BuiltinShell.init(this); }catch(Throwable ig){}
  agent = new Agent();
  agent.maxSteps = stepsLimit();   // 必须在 new Agent() 之后，否则 NPE 会让 onCreate 直接崩（表现为"一个日志都没有"）
  LinearLayout root = new LinearLayout(this);
  root.setOrientation(LinearLayout.VERTICAL);
  root.setBackgroundColor(C_BG);
  int p = dp(14);
  root.setPadding(p,p,p,p);

  // 标题行：左边一个橙色小方块当品牌标记，呼应图标
  LinearLayout head = new LinearLayout(this);
  head.setOrientation(LinearLayout.HORIZONTAL);
  head.setPadding(0,0,0,dp(12));
  View dot = new View(this);
  dot.setBackground(round(C_ACCENT, dp(4)));
  dot.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
  head.addView(dot);
  LinearLayout headTxt = new LinearLayout(this);
  headTxt.setOrientation(LinearLayout.VERTICAL);
  headTxt.setPadding(dp(8),0,0,0);
  TextView title = new TextView(this);
  title.setText("机灵");
  title.setTextSize(20); title.setTextColor(C_TEXT);
  title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
  headTxt.addView(title);
  TextView sub = new TextView(this);
  sub.setText("手机里的 agent · 虚拟屏里办事 · 随时接管");
  sub.setTextSize(12); sub.setTextColor(C_SUB);
  headTxt.addView(sub);
  head.addView(headTxt);
  root.addView(head);

  input = new EditText(this);
  input.setHint("输入任务");
  input.setMinLines(2);
  input.setTextSize(14); input.setTextColor(C_TEXT);
  input.setHintTextColor(C_HINT);
  input.setBackground(roundEdge(C_CARD, C_EDGE, dp(14), dp(1)));
  input.setPadding(dp(14), dp(12), dp(14), dp(12));
  // 悬浮窗那个输入框会一直占着 IME 的 served view，主界面这个就被顶掉、点不出键盘。
  // 这里主动把 IME 服务抢回来；顺手在聚焦时把边框点亮，明确"现在在编辑这里"。
  input.setOnFocusChangeListener((v, hasFocus) -> {
   input.setBackground(roundEdge(C_CARD, hasFocus ? C_ACCENT : C_EDGE, dp(14), hasFocus ? dp(2) : dp(1)));
   if(hasFocus) showIme();
  });
  input.setOnClickListener(v -> showIme());
  root.addView(input);

  final LinearLayout cardTask = card("任务");
  LinearLayout row1 = new LinearLayout(this);
  row1.setOrientation(LinearLayout.HORIZONTAL);
  row1.addView(btn("发送任务", "#F97316", v -> startTask()));   // 主操作用图标橙
  row1.addView(btn("语音输入", "#0f6e56", v -> startSpeech()));
  cardTask.addView(row1);

  final LinearLayout cardCtl = card("接管");
  LinearLayout row2 = new LinearLayout(this);
  row2.setOrientation(LinearLayout.HORIZONTAL);
  row2.addView(btn("接管（跳过去）", "#ba7517", v -> takeover()));
  row2.addView(btn("返回后台", "#888888", v -> backToAgent()));
  row2.addView(btn("停止", "#a32d2d", v -> stopTask()));
  cardCtl.addView(row2);
  root.addView(cardCtl);

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
  cardTask.addView(rowSteps);
  root.addView(cardTask);

  final LinearLayout cardSet = card("设置");
  LinearLayout row25 = new LinearLayout(this);
  row25.setOrientation(LinearLayout.HORIZONTAL);
  btnWatch = btn("", "#712b13", v -> toggleMainWatch());
  row25.addView(btnWatch);
  row25.addView(btn("说明", "#5a5a6e", v -> explainMainWatch()));
  // 集成版专用：在本 App 内完成无线调试配对，不装 Shizuku、不用电脑
  row25.addView(btn("内置配对", "#085041", v -> showBuiltinPairDialog()));
  row25.addView(btn("API Key", "#5a2b0c", v -> showApiKeyDialog()));
  cardSet.addView(row25);

  LinearLayout row3 = new LinearLayout(this);
  row3.setOrientation(LinearLayout.HORIZONTAL);
  btnWin = btn("隐藏悬浮窗", "#3c3489", v -> toggleThinkWindow());
  row3.addView(btnWin);
  row3.addView(btn("开启服务", "#085041", v -> openA11ySettings()));
  row3.addView(btn("新手引导", "#5a5a6e", v -> showWelcome()));
  cardSet.addView(row3);

  LinearLayout rowBrain = new LinearLayout(this);
  rowBrain.setOrientation(LinearLayout.HORIZONTAL);
  btnBrain = btn("大脑", "#534AB7", v -> cycleBrain());
  rowBrain.addView(btnBrain);
  cardSet.addView(rowBrain);

  LinearLayout row4 = new LinearLayout(this);
  row4.setOrientation(LinearLayout.HORIZONTAL);
  row4.addView(btn("连接Shell通道", "#712b13", v -> connectShell()));
  row4.addView(btn("验证截图", "#4a1b0c", v -> verifyCapture()));
  cardSet.addView(row4);
  root.addView(cardSet);

  final LinearLayout cardWs = card("工作区");
  LinearLayout row5 = new LinearLayout(this);
  row5.setOrientation(LinearLayout.HORIZONTAL);
  btnMirror = btn("隐藏镜像窗", "#3c3489", v -> toggleMirror());
  row5.addView(btnMirror);
  row5.addView(btn("镜像窗 位置", "#3c3489", v -> MirrorWindow.get().cycleCorner()));
  row5.addView(btn("镜像窗 大小", "#3c3489", v -> MirrorWindow.get().cycleSize()));
  cardWs.addView(row5);

  // ★ overlay 副屏是系统级的：App 被划掉/强杀时 DisplayHost 的清理逻辑来不及跑，
  //   那块屏和它画在主屏上的预览窗就会一直留着，用户清后台也清不掉。
  //   所以必须给一个能立刻收掉它的入口。
  LinearLayout row5b = new LinearLayout(this);
  row5b.setOrientation(LinearLayout.HORIZONTAL);
  row5b.addView(btn("收起副屏", "#8a1f1f", v -> dismissWorkspace()));
  row5b.addView(btn("一键准备", "#085041", v -> prepareAll()));
  row5b.addView(btn("诊断", "#5a2b0c", v -> diagnose()));
  cardWs.addView(row5b);
  root.addView(cardWs);

  final LinearLayout cardDemo = card("演示");
  LinearLayout draftRow = new LinearLayout(this);
  draftRow.addView(btn("验证微信草稿（不发送）", "#085041", v -> {
   if(worker != null && worker.isAlive()){ toast("有任务在跑"); return; }
   input.setText("打开微信，找到联系人赵禹哲，进入与他的会话，在消息输入框填写123。不要发送，填写完成就停止。");
   startTask("123");
  }));
  cardDemo.addView(draftRow);
  root.addView(cardDemo);
  status = new TextView(this);
  showStatus("状态：空闲");
  status.setTextSize(13);
  status.setBackground(roundEdge(C_CARD, C_EDGE, dp(12), dp(1)));
  status.setPadding(dp(12), dp(10), dp(12), dp(10));
  root.addView(status);

  // ===== 记忆区 =====
  // 跑过一遍才确定下来的事实：应用别名、可直达页面、偏好、踩过的坑。
  // 下次同 App 同类型任务直接复用，也避免在同一个地方再翻车。
  final LinearLayout cardMem = card("");   // 标题用 memTitle（带条数），别重复
  LinearLayout memHead = new LinearLayout(this);
  memHead.setOrientation(LinearLayout.HORIZONTAL);
  memTitle = new TextView(this);
  memTitle.setTextSize(13); memTitle.setTextColor(C_TEXT);
  memTitle.setPadding(0, dp(2), 0, 0);
  memTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
  memHead.addView(memTitle);
  memHead.addView(smallBtn("编辑原文", v -> editMemory()));
  memHead.addView(smallBtn("清空", v -> { MemoryStore.clear(this); renderMemory(); log("记忆已清空"); }));
  cardMem.addView(memHead);

  final ScrollView memScroll = new ScrollView(this);
  // 嵌套在外层 ScrollView 里，不抢的话内层根本滚不动
  memScroll.setOnTouchListener((v, ev) -> {
   if(pageScroll != null) pageScroll.requestDisallowInterceptTouchEvent(true);
   return false;
  });
  memList = new LinearLayout(this);
  memList.setOrientation(LinearLayout.VERTICAL);
  memList.setBackground(roundEdge(0xFFFBF7F3, C_EDGE, dp(12), dp(1)));
  memList.setPadding(dp(8), dp(4), dp(8), dp(4));
  memScroll.addView(memList);
  memScroll.setLayoutParams(new LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, dp(84)));
  cardMem.addView(memScroll);
  root.addView(cardMem);
  // ===== 技能库（程序化记忆）：只有技能版显示 =====
  if(SkillStore.enabled(this)){
   final LinearLayout cardSkill = card("");
   LinearLayout skHead = new LinearLayout(this);
   skHead.setOrientation(LinearLayout.HORIZONTAL);
   skillTitle = new TextView(this);
   skillTitle.setTextSize(13); skillTitle.setTextColor(C_TEXT);
   skillTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
   skillTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
   skHead.addView(skillTitle);
   skHead.addView(smallBtn("清空", v -> {
    SkillStore.clear(this); renderSkills(); log("技能库已清空");
   }));
   skillList = new LinearLayout(this);
   skillList.setOrientation(LinearLayout.VERTICAL);
   skillList.setBackground(roundEdge(0xFFFBF7F3, C_EDGE, dp(12), dp(1)));
   skillList.setPadding(dp(8), dp(4), dp(8), dp(4));
   ScrollView skScroll = new ScrollView(this);
   skScroll.setOnTouchListener((v, ev) -> {
    if(pageScroll != null) pageScroll.requestDisallowInterceptTouchEvent(true);
    return false;
   });
   skScroll.addView(skillList);
   skScroll.setLayoutParams(new LinearLayout.LayoutParams(
     ViewGroup.LayoutParams.MATCH_PARENT, dp(96)));
   cardSkill.addView(skHead);
   cardSkill.addView(skScroll);
   root.addView(cardSkill);
   renderSkills();
  }
  renderMemory();
  refreshWatchBtn();
  refreshBrainBtn();

  logView = new TextView(this);
  logView.setTextSize(11); logView.setTextColor(Color.parseColor("#e8e2dc"));
  logView.setBackground(round(0xFF25211D, dp(12)));
  logView.setPadding(dp(12),dp(10),dp(12),dp(10));
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

  // ★ 悬浮窗/镜像窗要浮在别的 App 上，必须先有「显示在上层」授权。
  //   不主动要的话，用户只会看到"点了没反应"，永远想不到是权限没开。
  if(!canOverlay()){ askOverlay(); }

  // 第一次打开：先把要开什么、能干什么说清楚，再走自检。
  // 不然新用户面对一堆按钮根本不知道从哪开始。
  if(getSharedPreferences("wellphone", MODE_PRIVATE).getBoolean("first_run", true)){
   getSharedPreferences("wellphone", MODE_PRIVATE).edit().putBoolean("first_run", false).apply();
   showWelcome();
  }else{
   bootstrap();
  }

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
  String akExtra = it.getStringExtra("apikey");   // 调试/自动化用：直接注入 key
  if(akExtra != null && !akExtra.isEmpty()){
   getSharedPreferences("wellphone", MODE_PRIVATE).edit().putString("api_key", akExtra).apply();
   log("API key 已由参数注入");
  }
  final String shellCmd = it.getStringExtra("shellcmd");   // 调试：通过内置通道下发任意命令
  if(shellCmd != null && !shellCmd.isEmpty()){
   new Thread(() -> {
    BuiltinShell.runShellFromSaved(this, shellCmd, this::logSafe);
   }).start();
  }
  if(it.getBooleanExtra("pair", false)){          // 调试：直接拉起内置配对服务
   try{ PairShellService.start(this); log("已启动内置配对服务"); }catch(Throwable e2){ log("启动失败 "+e2); }
  }
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

 /** 切换主循环用哪个模型当大脑。实测对比 doubao / deepseek 谁更快更准，就靠这个按钮，
  *  不用每次改 llm.json 再重启。 */
 private void cycleBrain(){
  List<Agent.Provider> ps = Agent.PROVIDERS;
  int start = agent.curProvider;
  for(int k = 1; k <= ps.size(); k++){
   int i = (start + k) % ps.size();
   Agent.Provider p = ps.get(i);
   if(p.dead || p.key == null || p.key.isEmpty()) continue;
   agent.curProvider = i;
   getSharedPreferences("wellphone", MODE_PRIVATE).edit().putString("vision_name", p.name).apply();
   log("主循环大脑切换为 " + p.name + "（" + p.model + "）");
   refreshBrainBtn();
   return;
  }
  toast("没有其他可用模型，先在 llm.json 或 API Key 里配上");
 }
 private void refreshBrainBtn(){
  if(btnBrain == null) return;
  Agent.Provider p = Agent.PROVIDERS.get(Math.max(0, agent.curProvider));
  btnBrain.setText("大脑：" + p.name);
 }

 /** 「显示在上层」权限：悬浮窗和镜像窗都要浮在别的 App 上，没有它点了只会"没反应" */
 private boolean canOverlay(){
  try{ return android.provider.Settings.canDrawOverlays(this); }catch(Throwable t){ return true; }
 }
 private void askOverlay(){
  try{
   Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
     android.net.Uri.parse("package:" + getPackageName()));
   i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
   startActivity(i);
   log("需要「显示在上层」权限：悬浮窗和镜像窗要浮在其他应用上面，请在设置里打开它");
   toast("请开启「显示在上层」");
  }catch(Throwable t){ log("打不开悬浮窗权限设置: " + t); }
 }

 /** 第一次打开时说清楚：要开什么 + 能干什么。
  *  不然新用户看到一堆按钮，根本不知道从哪一步开始。 */
 private void showWelcome(){
  String msg =
    "机灵的做法是：在你的手机里再开一块屏，agent 在那块屏上跑真实 App。\n"
    + "你在用你的屏幕，它用它的，互不打扰——它碰不到你的屏，默认也看不到你的屏。\n\n"
    + "【开始前，需要你开三样】\n"
    + "1. 无障碍服务 —— agent 的眼睛和手（读界面结构、写文字）\n"
    + "2. 无线调试配对 —— 免 Root 拿到建屏权限（不用装 Shizuku、不用电脑）\n"
    + "3. API key —— 给 agent 一个会看画面的大脑\n\n"
    + "另外「显示在上层」要开，悬浮窗和镜像窗才能浮在其他应用上。\n\n"
    + "【它能做什么】\n"
    + "· 在后台打开真实 App 干多步任务（搜索、填表、下单）\n"
    + "· 碰到支付/登录会停下，把界面整个搬给你来\n"
    + "· 随时可以把工作区搬到你的屏幕上接着操作\n"
    + "· 同一类任务跑过两次后，它会记住怎么做，下次直接照做\n\n"
    + "下面每一项在界面上都有对应按钮；缺哪一步，运行日志会直接告诉你。";
  new android.app.AlertDialog.Builder(this)
    .setTitle("机灵 · 开始之前")
    .setMessage(msg)
    .setPositiveButton("开始设置", (d, w) -> bootstrap())
    .setNegativeButton("我自己看看", null)
    .setCancelable(false)
    .show();
 }

 /** 启动自检：Shizuku → 无障碍 → 虚拟屏。能自动的都自动，绕不过的那一步说清楚。 */
 private volatile boolean bootstrapping = false;
 void bootstrap(){
  if(bootstrapping) return;
  // 全就绪就别再跑一遍（onCreate 和 onResume 都会触发，否则日志会成对重复）
  if(AgentService.instance != null && agent.displayId > 0){ refreshControlNotification(); return; }
  bootstrapping = true;
  // ★ 自检一开始就把 llm.json 读进来：否则 PROVIDERS 的 key 全是空的，
  //   界面上那个「大脑」切换按钮会因为"没有可用模型"而点不动（要等跑过一次任务才生效）。
  agent.applyLlmConfig(this);
  refreshBrainBtn();
  new Thread(() -> {
   try{
    if(!ShellChannel.binderReady()){
     // 集成版：改用内置的无线调试通道（adb 协议实现取自 Shizuku，Apache-2.0）
     if(BuiltinShell.hasSaved(this) && BuiltinShell.savedPort(this) > 0){
      logSafe("① Shizuku 未运行 → 用内置通道");
      // 先看有没有已经在跑的 DisplayHost：有就复用，不重复启动
      if(OwnedDisplay.connectIfRunning()){
       logSafe("   已有工作区在跑，直接复用");
       h.post(() -> { try{ ensureDisplay(); }catch(Throwable t){} });
       finishBootstrapBuiltin();
       return;
      }
      logSafe("   复用已配对的连接 " + "…");
      final String cmd = "CLASSPATH="
        + quote(getApplicationInfo().sourceDir) + " WELLPHONE_SOCKET_PORT=" + OwnedDisplay.BUILTIN_PORT
        + " app_process /system/bin local.wellphone.remote.DisplayHost";
      // ★ runDaemon 会一直阻塞到宿主进程退出（正常运行时永远不返回），
      //   所以「连虚拟屏」必须另起一条线程并行做，否则宿主活着 = 永远连不上。
      new Thread(() -> { try{ BuiltinShell.startWorkspace(this, cmd, this::logSafe); }catch(Throwable t){} },
        "workspace-daemon").start();
      new Thread(() -> {
       try{ Thread.sleep(1500); }catch(Throwable t){}
       h.post(() -> { try{ ensureDisplay(); }catch(Throwable t){} });
      },"workspace-connect").start();
      finishBootstrapBuiltin();
      return;
     }
     logSafe("① 没有可用的 Shell 通道 —— 点『内置配对』，按提示在手机上做一次无线调试配对即可");
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
   // 内置通道下 ShellChannel 用不了，改走 adb shell
   if(!ShellChannel.binderReady()) return enableA11yByBuiltin();
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

 /** 一键准备：缺哪一步就补哪一步。
  *  集成版有内置配对通道时，压根不需要 Shizuku，别把用户往 Shizuku 界面上带。 */
 private void prepareAll(){
  if(!ShellChannel.binderReady() && !ShellChannel.builtinReady()){
   log("还没有 Shell 通道：先点『内置配对』用无线调试配对一次（不用装 Shizuku）");
   toast("先做一次内置配对");
   try{
    Intent i = new Intent();
    i.setClassName("moe.shizuku.privileged.api", "moe.shizuku.privileged.api.MainActivity");
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(i);
   }catch(Throwable t){ log("（Shizuku 也没装，用内置配对即可）"); }
   return;
  }
  log("开始一键准备：Shell 通道 → 无障碍 → 虚拟屏");
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
    }else log("⚠️ 虚拟屏创建失败：" + (ShellChannel.available()
       ? "Shell 通了但没建出屏，看上面 host> 开头的报错"
       : "还没有 Shell 通道：先做一次内置配对"));
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
  showThink(true);   // 跑任务 = 想看过程，把思考窗拉回来
  if(think != null){ think.setExpanded(true); think.reset("任务: " + t); }
  takeoverActive = false;
  takeoverTaskId = null;
  // ★ 意图交给模型判，不再靠关键词 if-else（"出现"那种词太容易误伤普通任务）。
  //   模型没说话（没 key / 超时 / 返回非法 JSON）时 routeIntent 内部回退到规则。
  final Agent.Route rt = Agent.routeIntent(t, this::logSafe);
  logSafe("意图路由：" + rt.type + (rt.byLLM ? "（模型判断）" : "（规则兜底）")
    + ("task".equals(rt.type) ? " · " + rt.mode : ""));
  // 只是提问：直接回答，不建屏、不截图、不调视觉模型
  if("question".equals(rt.type)){
   String ans = (rt.reply == null || rt.reply.isEmpty())
     ? "（模型没给出答案，试着问得具体一点）" : rt.reply;
   log("💬 问答：" + ans);
   if(think != null) think.append("\n» " + ans + "\n");
   showStatus("状态：已回答");
   return;
  }
  final boolean watchMode = "main".equals(rt.mode) || isWatchTask(t);
  if(watchMode && mainWatchOn() && think != null) think.setStepLabel("监控中 · 看你的屏幕");
  if(watchMode && !mainWatchOn()){
   // 别让用户自己去 UI 里找那个开关（悬浮窗常把它盖住，点了也没反应）。
   // 直接问一次：要不要现在打开。这是隐私开关，必须用户亲手点头。
   log("⚠️ 这个任务需要看你的屏幕。");
   new android.app.AlertDialog.Builder(this)
     .setTitle("需要看你的屏幕")
     .setMessage("模型判断「" + t + "」需要盯着你正在看的画面才能判断时机。\n\n"
       + "要现在打开主屏监控吗？\n"
       + "（这是隐私开关，随时可关；关着的时候 agent 绝不会看你的屏幕）")
     .setPositiveButton("打开并继续", (d, w) -> {
       if(!mainWatchOn()) toggleMainWatch();
       startTask(stopAfterText);
     })
     .setNegativeButton("取消", null)
     .show();
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
  // 集成版：内置配对通道（adb）也算"有 Shell"——别一头扎进 Shizuku 的提示里
  if(!ShellChannel.binderReady() && ShellChannel.builtinReady()){
   log("Shell 通道已就绪（内置配对通道 / adb shell）");
   toast("Shell 通道已就绪");
   return;
  }
  if(!ShellChannel.binderReady()){
   log("⚠️ 还没有 Shell 通道：点『内置配对』用无线调试配对一次（不用装 Shizuku）");
   toast("先做一次内置配对");
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
      (ShellChannel.available() ? "执行错误（看 DisplayHost 报错）" : "通道未就绪，先做一次内置配对"));
    toast(ok ? "Shell 点击 OK" : "Shell 点击失败");
   });
  }).start();
 }

 /** 立刻收掉后台工作区：撤销 overlay 副屏（系统那个预览窗随之消失）、关镜像窗、断宿主。
  *  什么时候用：App 被强杀过、预览窗赖着不走、或者你想彻底把工作区从机器上抹掉。 */
 private void dismissWorkspace(){
  new Thread(() -> {
   String out = ShellChannel.raw("settings delete global overlay_display_devices", 8000);
   logSafe("收起副屏: " + (out == null ? "（shell 通道不可用，改用 App 侧清理）" : out.trim()));
   h.post(() -> {
    try{ MirrorWindow.get().hide(); }catch(Throwable ig){}
    OwnedDisplay.close();
    OwnedDisplay.overlayMode = false;
    agent.displayId = -1;
    log("工作区已收起：overlay 副屏已撤销，那个「叠加视图」预览窗应该没了");
    toast("已收起副屏");
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
  // ★ 下面两步要走 shell（adb 网络连接），**绝不能在主线程跑**：
  //   一跑就抛 NetworkOnMainThreadException → execFast 返回 null → findTaskId 找不到任务
  //   → 每次都掉进"新开实例"兜底，用户看到的就是"接管又开了个新应用"。搬任务的失败
  //   一直是这个原因，不是 move-stack 不支持、也不是找错任务。
  new Thread(() -> {
   agent.preGrant(agent.currentPkg);
   // 首选：把 agent 正在用的那个任务整块搬到你屏幕上（同一个实例，界面状态不丢）
   final String tid = agent.takeoverMoveToMain();
   h.post(() -> {
    if(tid != null){
     takeoverTaskId = tid;
     log("🤝 已把工作区搬到你的屏幕（同一个实例，状态原样保留）");
     showStatus("状态：等你处理（手动接管）");
     toast("已接管，界面就是你交给 agent 的那个");
    }else{
     // 退路：搬不动才另开实例（那样会丢状态、多一个后台，实在没办法才用）
     // ★ openOnUserScreen 也要走 shell，别在 h.post 里调 —— 那是主线程，照样抛
     //   NetworkOnMainThreadException（之前就是漏了这一处，接管兜底也跟着废了）。
     new Thread(() -> {
      agent.openOnUserScreen(getApplicationContext());
     },"takeover-open").start();
     log("接管：任务搬移失败，改为在你屏幕新开一个实例（状态不连续）");
     showStatus("状态：等你处理（新建实例）");
    }
   });
  },"takeover").start();
 }
 public void backToAgent(){
  takeoverActive = false;
  log("返回后台：开始");
  // 任务搬回工作区。不依赖 takeoverTaskId 是否记得住——
  // 没记录就按当前应用现找它的任务，照样搬（悬浮窗按钮以前"点了没反应"就是因为
  // backToAgent 只认那两个标志，没接管过时它们都是空的，整段代码等于没跑）。
  // ★ findTaskId / moveTaskBack 都要走 shell（网络），同 takeover 一样不能在沾主线程。
  final String remembered = takeoverTaskId;
  new Thread(() -> {
   // 搬回去要找**主屏上**那个任务（接管时它被搬到了 display 0）
   String tid = remembered;
   if(tid == null && agent.currentPkg != null && !agent.currentPkg.isEmpty())
    tid = agent.findTaskId(agent.currentPkg, 0);
   final String found = tid;
   h.post(() -> {
    if(found != null){
     // moveTaskBack 也要走网络，再开一层
     new Thread(() -> {
      boolean ok = agent.moveTaskBack(found) != null;
      logSafe(ok ? "↩ 已把任务搬回后台工作区" : "⚠️ 搬回工作区失败");
     },"back-move").start();
    }else{
     log("（没有需要搬回的任务）");
   // 接管若走的是"主屏新开实例"兜底，就没记下 task id，那个实例搬不回副屏。
   // 别就这么悬着 —— 让 agent 在副屏重新起一个，至少它还有活干。
   if(agent.currentPkg != null && !agent.currentPkg.isEmpty() && agent.displayId > 0){
    log("主屏这个实例搬不回去，让 agent 在副屏重新开始");
    final String app = agent.currentPkg;
    new Thread(() -> {
     boolean ok = agent.launchOnVirtual(getApplicationContext(), app, this::logSafe);
     logSafe(ok ? "副屏已重新起好 " + app : "副屏重启失败：" + app);
    }).start();
   }
    }
   });
  },"back-to-agent").start();
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
  // 强信号：明确说要看用户的屏幕
  if(t.contains("监控") || t.contains("盯着") || t.contains("我的屏幕")
     || t.contains("你在看") || t.contains("主屏")) return true;
  // ★ 组合信号：必须"条件触发"和"就出手"同时成立，缺一不可。
  //   以前把"出现""就点"单独算命中，结果普通跨 App 任务被误判成监控：
  //   实测「打开微信…里面出现任意商品就去京东买」被直接拦下要求开主屏监控——
  //   它只是"找到东西然后去买"，根本不需要盯着用户的屏幕。
  String[] trigger = {"一旦","等到","的时候","持续","一直","飞过","喊到","轮到","说出","说到"};
  for(String k : trigger) if(t.contains(k) && t.contains("就")) return true;
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

 /** 配 API key：存 SharedPreferences，最可靠（不依赖外部存储文件） */
 /** 两个模型都要配：豆包跑主循环看画面，DeepSeek 跑意图路由和问答。
  *  ★ 都存 SharedPreferences，不靠 llm.json —— 本机 App 读自己 Android/data/<pkg>/files
  *    实测会失败，之前 llm.json 里的 DeepSeek key 根本没读进来，
  *    结果就是「大脑」按钮切换时找不到可用模型、点了没反应。 */
 private void showApiKeyDialog(){
  android.content.SharedPreferences sp = getSharedPreferences("wellphone", MODE_PRIVATE);
  LinearLayout box = new LinearLayout(this);
  box.setOrientation(LinearLayout.VERTICAL);
  box.setPadding(dp(16), dp(8), dp(16), 0);
  final EditText eDoubao = new EditText(this);
  eDoubao.setSingleLine(true);
  eDoubao.setHint("豆包（火山方舟）key，跑主循环看画面");
  eDoubao.setText(sp.getString("api_key", ""));
  final EditText eDeep = new EditText(this);
  eDeep.setSingleLine(true);
  eDeep.setHint("DeepSeek key，跑意图路由和问答");
  eDeep.setText(sp.getString("deepseek_key", ""));
  box.addView(eDoubao); box.addView(eDeep);
  new android.app.AlertDialog.Builder(this)
    .setTitle("配置 API Key")
    .setMessage("两个都存本机、不会上传。填哪个就用哪个，改完立即生效。")
    .setView(box)
    .setPositiveButton("保存", (d, w) -> {
      String kd = eDoubao.getText().toString().trim();
      String kp = eDeep.getText().toString().trim();
      sp.edit().putString("api_key", kd).putString("deepseek_key", kp).apply();
      agent.applyLlmConfig(this);
      refreshBrainBtn();
      log("豆包 key：" + (kd.isEmpty() ? "空" : kd.length() + " 字符")
        + " / DeepSeek key：" + (kp.isEmpty() ? "空" : kp.length() + " 字符"));
      toast("已保存");
    })
    .setNeutralButton("手动填端口", (d, w) -> showManualPairDialog())
    .setNegativeButton("取消", null)
    .show();
 }

 /** 兜底：某些 ROM 上 mDNS 不可靠，允许手填端口 */
 private void showManualPairDialog(){
  LinearLayout box = new LinearLayout(this);
  box.setOrientation(LinearLayout.VERTICAL);
  box.setPadding(dp(16), dp(10), dp(16), 0);
  final EditText ePair = new EditText(this); ePair.setHint("配对端口");
  final EditText eCode = new EditText(this); eCode.setHint("配对码");
  final EditText eConn = new EditText(this); eConn.setHint("连接端口");
  for(EditText e : new EditText[]{ePair, eCode, eConn}){ e.setSingleLine(true); box.addView(e); }
  TextView tip = new TextView(this); tip.setTextSize(11);
  tip.setText("配对端口/配对码来自「使用配对码配对设备」弹窗；\n连接端口来自同一页面的『IP 地址和端口』。");
  box.addView(tip);
  new android.app.AlertDialog.Builder(this)
    .setTitle("手动配对")
    .setView(box)
    .setPositiveButton("配对", (d, w) -> {
      int pp, cp;
      try{ pp = Integer.parseInt(ePair.getText().toString().trim()); }catch(Throwable t){ toast("配对端口不对"); return; }
      try{ cp = Integer.parseInt(eConn.getText().toString().trim()); }catch(Throwable t){ cp = 5555; }
      final int pairPort = pp, connPort = cp;
      final String code = eCode.getText().toString().trim();
      log("手动配对 127.0.0.1:" + pairPort + " conn " + connPort);
      new Thread(() -> {
        if(!BuiltinShell.pair("127.0.0.1", pairPort, code, this::logSafe)) return;
        BuiltinShell.saveConn(this, "127.0.0.1", connPort);
        String cmd = "CLASSPATH="
          + quote(getApplicationInfo().sourceDir) + " WELLPHONE_SOCKET_PORT=" + OwnedDisplay.BUILTIN_PORT
          + " app_process /system/bin local.wellphone.remote.DisplayHost";
        BuiltinShell.runDaemon("127.0.0.1", connPort, cmd, this::logSafe);
        h.post(() -> { try{ ensureDisplay(); }catch(Throwable t){} });
      }).start();
    })
    .setNegativeButton("取消", null)
    .show();
 }

 /** 内置配对：只在本 App 内完成无线调试配对，不装 Shizuku、不用电脑。
  *  adb 协议实现取自 RikkaApps/Shizuku（Apache-2.0）。 */
 private void showBuiltinPairDialog(){
  // 不用填端口：mDNS 自动发现。配对码在通知栏输入（切走系统设置页会刷新配对码）。
  new android.app.AlertDialog.Builder(this)
    .setTitle("内置配对（1 分钟）")
    .setMessage(
      "1. 打开 系统设置 → 开发者选项 → 无线调试，并打开它\n"
    + "2. 点「使用配对码配对设备」，屏幕上会出现 6 位配对码\n"
    + "3. 下拉通知栏，在「Wellphone 内置配对」那条通知里直接输入配对码\n\n"
    + "⚠️ 不要切回这个 App 输入——切走那个页面配对码会刷新。用通知栏输入就不会。")
    .setPositiveButton("开始搜索", (d, w) -> {
      log("开始内置配对：mDNS 搜索 _adb-tls-pairing._tcp …");
      try{ PairShellService.start(this); }catch(Throwable t){ log("启动配对服务失败：" + t); }
    })
    .setNeutralButton("手动填端口", (d, w) -> showManualPairDialog())
    .setNegativeButton("取消", null)
    .show();
 }

 
 private String quote(String s){ return "'" + s.replace("'", "'\\''") + "'"; }

 /** 内置通道版：用 adb shell 写 settings 打开无障碍（逻辑与原版一致，含 null 段清洗） */
 private boolean enableA11yByBuiltin(){
  try{
   String me = getPackageName() + "/" + AgentService.class.getName();
   String cur = BuiltinShell.execFromSaved(this, "settings get secure enabled_accessibility_services");
   java.util.LinkedHashSet<String> segs = new java.util.LinkedHashSet<String>();
   for(String seg : cur.split("[\\r\\n:]+")){
    String t = seg.trim();
    if(t.isEmpty() || !t.contains("/") || t.startsWith("null")) continue;
    segs.add(t);
   }
   if(segs.contains(me)){ logSafe("③ 无障碍已在列表中，等待绑定…"); }
   else {
    segs.add(me);
    StringBuilder j = new StringBuilder();
    for(String x : segs){ if(j.length()>0) j.append(':'); j.append(x); }
    BuiltinShell.runShellFromSaved(this,
      "settings put secure enabled_accessibility_services '" + j + "'; settings put secure accessibility_enabled 1",
      this::logSafe);
   }
   for(int i=0;i<10;i++){                       // 等绑定，最多 5 秒
    if(AgentService.instance != null) return true;
    Thread.sleep(500);
   }
   return AgentService.instance != null;
  }catch(Throwable t){ return false; }
 }

 /** 内置通道分支的收尾：无障碍必须开，否则任务跑不了。
  *  （原来这里直接 return，把 ③ 无障碍 和 ensureService 都跳过了，导致"虚拟屏就绪但任务不动"） */
 private void finishBootstrapBuiltin(){
  boolean a11y = enableA11yByShell();
  logSafe(a11y ? "③ 无障碍服务已自动开启（内置通道）" : "③ 无障碍没开成，请在 设置→无障碍→Wellphone 手动开一次");
  ensureService();
  h.post(() -> { if(agent.displayId > 0) refreshControlNotification(); });
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
  renderSkills();   // 任务跑完沉淀的新技能，回来就能在列表里看到
 }

 // ===== 技能库（程序化记忆）=====
 // 演示价值最大的一块：让用户亲眼看到"它学会了什么"。
 private LinearLayout skillList;
 private TextView skillTitle;
 private void renderSkills(){
  if(skillList == null) return;
  skillList.removeAllViews();
  List<SkillStore.Skill> ss = SkillStore.all(this);
  if(skillTitle != null) skillTitle.setText("程序化记忆 · " + ss.size() + " 条技能");
  if(ss.isEmpty()){
   TextView t = new TextView(this);
   t.setTextSize(11); t.setTextColor(C_HINT);
   t.setText("还没有技能。完成一次任务后，它走过的路会自动存成技能，下次同类任务直接复用、不再逐步推理。");
   skillList.addView(t);
   return;
  }
  for(SkillStore.Skill s : ss){
   TextView t = new TextView(this);
   t.setTextSize(11); t.setTextColor(C_TEXT);
   t.setPadding(0, dp(2), 0, dp(2));
   String state = s.ok >= 2 ? "已掌握" : "观察中（还差 " + (2 - s.ok) + " 次成功转正）";
   t.setText("· " + s.label + "\n   " + s.pkg + " · " + s.steps.size() + " 步 · 成功 "
     + s.ok + " 次 · " + state);
   skillList.addView(t);
  }
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
    .setNeutralButton("手动填端口", (d, w) -> showManualPairDialog())
    .setNegativeButton("取消", null)
    .show();
 }

 

 private Button smallBtn(String text, View.OnClickListener fn){
  Button b = new Button(this);
  b.setText(text); b.setTextSize(10); b.setTextColor(C_SUB);
  b.setAllCaps(false);
  b.setMinHeight(0); b.setMinimumHeight(0);
  b.setBackground(pill(0xFFF3E9DF, dp(8)));        // 浅色药丸，别再是块深色砖
  try{ b.setStateListAnimator(null); }catch(Throwable ig){}
  b.setPadding(dp(10), 0, dp(10), 0);
  LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
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

 // ===== 设计系统：延续图标（橙 + 白卡 + 大圆角）=====
 // 全部用代码构造 drawable，不引额外图片资源，也不碰任何业务逻辑。
 static final int C_BG     = 0xFFFFF7F0;   // 极浅暖橙底
 static final int C_CARD   = 0xFFFFFFFF;
 static final int C_EDGE   = 0xFFF2E3D3;   // 卡片描边
 static final int C_ACCENT = 0xFFF97316;   // 图标橙
 static final int C_TEXT   = 0xFF2B2520;
 static final int C_SUB    = 0xFF8B7F72;
 static final int C_HINT   = 0xFFB6A99B;

 /** 按下态：整体压暗一点，给个即时反馈（原来是一块死色，按下去毫无反应） */
 private static int darker(int c){
  int r=(int)(((c>>16)&0xff)*0.86), g=(int)(((c>>8)&0xff)*0.86), b=(int)((c&0xff)*0.86);
  return 0xFF000000 | (r<<16) | (g<<8) | b;
 }
 private static android.graphics.drawable.GradientDrawable round(int fill, float r){
  android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();
  d.setColor(fill); d.setCornerRadius(r); return d;
 }
 private static android.graphics.drawable.GradientDrawable roundEdge(int fill, int stroke, float r, int w){
  android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();
  d.setColor(fill); d.setCornerRadius(r); d.setStroke(w, stroke); return d;
 }
 /** 圆角按钮 + 按下变色 */
 private static android.graphics.drawable.StateListDrawable pill(int normal, float r){
  android.graphics.drawable.StateListDrawable s=new android.graphics.drawable.StateListDrawable();
  s.addState(new int[]{android.R.attr.state_pressed}, round(darker(normal), r));
  s.addState(new int[]{}, round(normal, r));
  return s;
 }
 /** 白色圆角卡片，带一个可选的小标题——把一堆按钮按用途分组，界面才有结构 */
 private LinearLayout card(String label){
  LinearLayout box=new LinearLayout(this);
  box.setOrientation(LinearLayout.VERTICAL);
  box.setBackground(roundEdge(C_CARD, C_EDGE, dp(16), dp(1)));
  int p=dp(10); box.setPadding(p,p,p,p);
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
  lp.bottomMargin=dp(10);
  box.setLayoutParams(lp);
  if(label != null && !label.isEmpty()){
   TextView t=new TextView(this);
   t.setText(label); t.setTextSize(12); t.setTextColor(C_SUB);
   t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
   t.setPadding(dp(4),0,0,dp(2));
   box.addView(t);
  }
  return box;
 }

 private Button btn(String text, String color, View.OnClickListener fn){
  Button b = new Button(this);
  b.setText(text); b.setTextColor(Color.WHITE);
  b.setTextSize(13);
  b.setAllCaps(false);
  b.setMinHeight(0); b.setMinimumHeight(0);
  b.setPadding(dp(6),0,dp(6),0);
  b.setBackground(pill(Color.parseColor(color), dp(12)));
  try{ b.setStateListAnimator(null); }catch(Throwable ig){}   // 去掉默认抬升阴影
  LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
  lp.setMargins(dp(3),dp(4),dp(3),dp(4));
  b.setLayoutParams(lp); b.setOnClickListener(fn);
  return b;
 }
 private int dp(int v){ return (int)(v * getResources().getDisplayMetrics().density); }
 private void toast(String s){ Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
