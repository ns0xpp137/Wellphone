package local.wellphone.remote;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** 程序化记忆（技能库）：把"做成一件事"的动作序列存成技能，下次同类任务直接复用。
 *
 *  站在谁肩膀上（答辩要能说出来）：
 *   · Voyager（Wang et al., NeurIPS'23 / TMLR'24）：技能 = 可执行单元，按描述索引，
 *     新任务先检索 top-N 再动手；成功后才写入；技能之间可组合。
 *     它证明了"能力存在记忆里、而不是权重里"这条路走得通（科技树里程碑最高快 15.3×）。
 *   · OpenAdapt：录一次 → 编译成确定性重放 → **只有界面漂移时才叫醒模型**。
 *     原话是"最便宜的 grounding，是你不必重做的 grounding"。
 *   · Agent S4「Evolving Agent Interfaces」（2026.10）：让 agent 从任务经验里
 *     **演化它自己使用的接口**。我们沉淀出的技能，本质就是给模型新增了一个高层动作。
 *
 *  两条必须遵守的工程教训（技能库搞不好就是事故源）：
 *   1. 写入慢、成功门控。Voyager 在 Minecraft 里一次成功就写，因为环境确定；
 *      手机 App 会改版、会弹权限框，一次成功可能只是运气。所以这里要求
 *      **成功两次才"转正"进入可检索层**。
 *   2. 优雅降级（对应缓存失效）。技能是"上次成功的快照"，App 一改版就失效。
 *      命中也要每步比对画面指纹，偏离立刻放弃、交还模型，成功后用新序列覆盖旧的。
 *
 *  还有一条是 keying 的教训：索引键要捕获**任务形状**而不是表面词。
 *  "搜火锅" 和 "搜烤肉" 必须命中同一条技能，否则技能库永远只有一条用一次。
 *
 *  存 skills.json，界面里可见、可删。 */
public class SkillStore {

 /** 一条技能。steps 里的 {q} 是参数占位符，执行时用本次任务提取到的词替换。 */
 public static class Skill {
  public String pkg = "";        // 应用包名
  public String verb = "";       // 归一化动词：search / open / play ...
  public String startPage = "";  // 起始页面指纹（可空）
  public String label = "";      // 人话，给用户看："在美团里搜索"
  public List<String> steps = new ArrayList<String>();
  public int ok = 0, fail = 0;
  public long ts = 0;
 }

 private static final List<Skill> skills = new ArrayList<Skill>();
 private static boolean loaded = false;
 private static String lastError = "";

 private static File file(Context ctx){ return new File(ctx.getExternalFilesDir(null), "skills.json"); }

 /** 只有这个包下才启用技能库 —— 「机灵」装的是别的包名，即便同一份源码也不会受影响 */
 public static boolean enabled(Context ctx){
  return ctx != null && ctx.getPackageName().endsWith(".skill");
 }

 // ===== 归一化：把任务文本压成"动词 + 参数" =====
 // 关键：捕获任务形状，不是表面词。"在美团搜火锅"/"美团上找烤肉" → 同一个 verb。
 public static String[] normalize(String task){
  String t = task == null ? "" : task.trim();
  String verb = "";
  if(t.contains("搜索") || t.contains("搜") || t.contains("查找") || t.contains("查一下") || t.contains("找")) verb = "search";
  else if(t.contains("打开") || t.contains("进入") || t.contains("启动")) verb = "open";
  else if(t.contains("播放") || t.contains("看") || t.contains("刷")) verb = "play";
  else if(t.contains("下单") || t.contains("买") || t.contains("购买")) verb = "order";
  return new String[]{verb, t};
 }
 /** 抽出查询词：去掉动词和常见虚词后剩下的部分 */
 public static String queryOf(String task){
  String t = task == null ? "" : task.trim();
  t = t.replaceAll("搜索|搜一下|搜|查找|查一下|找|打开|进入|启动|播放|看一下|刷|下单|买|购买", " ")
       .replaceAll("在|里|上|的|一下|帮我|请|给|我", " ")
       .replaceAll("\\s+", " ").trim();
  return t;
 }

 // ===== 读写 =====
 public static synchronized void load(Context ctx){
  if(loaded) return;
  loaded = true;
  skills.clear();
  File f = file(ctx);
  if(!f.exists()) return;
  try{
   BufferedReader r = new BufferedReader(new FileReader(f));
   StringBuilder sb = new StringBuilder(); String line;
   while((line = r.readLine()) != null) sb.append(line);
   r.close();
   parse(sb.toString());
  }catch(Throwable t){ lastError = "读取失败 " + t; }
 }
 private static void parse(String json){
  try{
   JSONObject o = new JSONObject(json);
   JSONArray arr = o.optJSONArray("skills");
   if(arr == null) return;
   for(int i = 0; i < arr.length(); i++){
    JSONObject j = arr.optJSONObject(i);
    if(j == null) continue;
    Skill s = new Skill();
    s.pkg = j.optString("pkg",""); s.verb = j.optString("verb","");
    s.startPage = j.optString("startPage",""); s.label = j.optString("label","");
    JSONArray st = j.optJSONArray("steps");
    if(st != null) for(int k = 0; k < st.length(); k++) s.steps.add(st.optString(k,""));
    s.ok = j.optInt("ok",0); s.fail = j.optInt("fail",0); s.ts = j.optLong("ts",0);
    if(!s.pkg.isEmpty() && !s.verb.isEmpty() && !s.steps.isEmpty()) skills.add(s);
   }
  }catch(Throwable t){ lastError = "解析失败 " + t; }
 }

 /** 检索：同 App + 同动词，且已经"转正"（成功过两次）。命中多条时取成功次数最多的。 */
 public static synchronized Skill find(Context ctx, String pkg, String verb){
  load(ctx);
  if(pkg == null || verb == null || verb.isEmpty()) return null;
  Skill best = null;
  for(Skill s : skills){
   if(!s.pkg.equals(pkg) || !s.verb.equals(verb)) continue;
   if(s.ok < 2) continue;                       // 没转正：还在观察期，别拿来直行
   if(best == null || s.ok > best.ok) best = s;
  }
  return best;
 }

 /** 记一次结果。成功两次转正；失败两次就删（App 改版了，它自己会忘）。 */
 public static synchronized void record(Context ctx, String pkg, String verb, String startPage,
                                        String label, List<String> steps, boolean success){
  if(pkg == null || verb == null || verb.isEmpty() || steps == null || steps.isEmpty()) return;
  load(ctx);
  Skill s = null;
  for(Skill k : skills) if(k.pkg.equals(pkg) && k.verb.equals(verb)){ s = k; break; }
  if(s == null){
   s = new Skill();
   s.pkg = pkg; s.verb = verb; s.startPage = startPage == null ? "" : startPage;
   s.label = label == null ? "" : label;
   skills.add(s);
  }
  if(success){
   s.ok++;
   s.steps = new ArrayList<String>(steps);      // 用最新的成功序列覆盖
   s.label = label == null ? s.label : label;
   s.fail = 0;
  }else{
   s.fail++;
   if(s.fail >= 2 && s.ok < 2){
    skills.remove(s);                            // 从来没转正过、又连着错两次 → 丢掉
   }
  }
  s.ts = System.currentTimeMillis();
  save(ctx);
 }

 public static synchronized List<Skill> all(Context ctx){ load(ctx); return new ArrayList<Skill>(skills); }
 public static synchronized void remove(Context ctx, String pkg, String verb){
  load(ctx);
  Iterator<Skill> it = skills.iterator();
  while(it.hasNext()){
   Skill s = it.next();
   if(s.pkg.equals(pkg) && s.verb.equals(verb)){ it.remove(); }
  }
  save(ctx);
 }
 public static synchronized void clear(Context ctx){
  skills.clear(); loaded = true;
  try{ file(ctx).delete(); }catch(Throwable t){}
 }

 /** 把技能里的 {q} 换成这次任务真正要输入的内容 */
 public static List<String> bind(List<String> steps, String query){
  List<String> out = new ArrayList<String>();
  for(String s : steps){
   out.add(s == null ? "" : s.replace("{q}", query == null ? "" : query));
  }
  return out;
 }

 private static void save(Context ctx){
  try{
   JSONObject o = new JSONObject();
   JSONArray arr = new JSONArray();
   for(Skill s : skills){
    JSONObject j = new JSONObject();
    j.put("pkg", s.pkg); j.put("verb", s.verb); j.put("startPage", s.startPage);
    j.put("label", s.label);
    JSONArray st = new JSONArray();
    for(String t : s.steps) st.put(t);
    j.put("steps", st);
    j.put("ok", s.ok); j.put("fail", s.fail); j.put("ts", s.ts);
    arr.put(j);
   }
   o.put("skills", arr);
   FileWriter w = new FileWriter(file(ctx));
   w.write(o.toString(2));
   w.close();
   lastError = "";
  }catch(Throwable t){ lastError = "写入失败 " + t; }
 }
 public static String lastError(){ return lastError; }
}
