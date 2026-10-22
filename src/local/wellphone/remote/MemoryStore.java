package local.wellphone.remote;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** 记忆区：把"跑过一遍才确定下来的事实"存下来，下次同 App 同类型任务直接复用。
 *
 *  只存四类，全部是确定性事实，不存模型的自由文本：
 *   app   —— 用户怎么叫 → 真实包名（"相册" → com.android.gallery3d）
 *   page  —— App 里能一步直达的页面（记事本 → NotePadEditorActivity）
 *   pref  —— 用户偏好（下载用哪个商店）
 *   avoid —— 踩过的坑（"记事本加号点不到，别试，用直达页"）—— 这条是防事故复发的关键
 *
 *  存成 memory.json，界面里既能看条目列表，也能直接编辑原文。 */
public class MemoryStore {
 public static final String TYPE_APP = "app", TYPE_PAGE = "page", TYPE_PREF = "pref",
   TYPE_AVOID = "avoid", TYPE_GUIDE = "guide";

 public static class Entry {
  public String type = "", key = "", value = "", note = "";
 }

 private static final List<Entry> entries = new ArrayList<Entry>();
 private static boolean loaded = false;
 private static String lastError = "";

 private static File file(Context ctx){ return new File(ctx.getExternalFilesDir(null), "memory.json"); }

 public static synchronized void load(Context ctx){
  if(loaded) return;
  loaded = true;
  entries.clear();
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
   JSONArray arr = o.optJSONArray("entries");
   if(arr == null) return;
   for(int i = 0; i < arr.length(); i++){
    JSONObject e = arr.optJSONObject(i);
    if(e == null) continue;
    Entry en = new Entry();
    en.type = e.optString("type",""); en.key = e.optString("key","");
    en.value = e.optString("value",""); en.note = e.optString("note","");
    if(!en.key.isEmpty()) entries.add(en);
   }
  }catch(Throwable t){ lastError = "解析失败 " + t; }
 }
 public static synchronized String rawJson(Context ctx){
  load(ctx);
  try{
   JSONObject o = new JSONObject();
   JSONArray arr = new JSONArray();
   for(Entry e : entries){
    JSONObject j = new JSONObject();
    j.put("type", e.type); j.put("key", e.key); j.put("value", e.value); j.put("note", e.note);
    arr.put(j);
   }
   o.put("entries", arr);
   return o.toString(2);
  }catch(Throwable t){ return "{\"entries\":[]}"; }
 }
 /** 直接改原文：解析通过才落盘，改坏了不会把记忆搞没 */
 public static synchronized boolean replaceRaw(Context ctx, String json){
  try{
   JSONObject o = new JSONObject(json);
   if(o.optJSONArray("entries") == null) return false;
   List<Entry> backup = new ArrayList<Entry>(entries);
   entries.clear();
   parse(json);
   save(ctx);
   if(entries.isEmpty() && !backup.isEmpty()){
    entries.clear(); entries.addAll(backup); return false;   // 解析出空表，认为改坏了
   }
   return true;
  }catch(Throwable t){ return false; }
 }

 public static synchronized List<Entry> all(Context ctx){ load(ctx); return new ArrayList<Entry>(entries); }

 public static synchronized void add(Context ctx, String type, String key, String value, String note){
  if(key == null || key.isEmpty() || value == null) return;
  load(ctx);
  // 去重按「类型+key+值」：同一个 App 可以有多个可直达页面，不能因为 key 相同就互相覆盖。
  // 但 app 别名是"一种叫法对应一个包名"，同一叫法换了包名要覆盖旧的。
  for(Entry e : entries){
   if(!e.type.equals(type)) continue;
   if(TYPE_APP.equals(type) && e.key.equalsIgnoreCase(key)){
    if(e.value.equals(value)) return;
    e.value = value; e.note = note == null ? "" : note; save(ctx); return;
   }
   if(e.type.equals(type) && e.key.equalsIgnoreCase(key) && e.value.equals(value)){
    if(String.valueOf(e.note).equals(String.valueOf(note))) return;
    e.note = note == null ? "" : note; save(ctx); return;
   }
  }
  Entry e = new Entry();
  e.type = type; e.key = key; e.value = value; e.note = note == null ? "" : note;
  entries.add(e);
  save(ctx);
 }
 public static synchronized void remove(Context ctx, int index){
  load(ctx);
  if(index < 0 || index >= entries.size()) return;
  entries.remove(index);
  save(ctx);
 }
 public static synchronized void clear(Context ctx){
  entries.clear(); loaded = true;
  try{ file(ctx).delete(); }catch(Throwable t){}
 }

 /** 查别名：任务里出现了记忆里的叫法 → 直接给包名 */
 public static synchronized String lookupApp(Context ctx, String task){
  load(ctx);
  String low = task == null ? "" : task.toLowerCase();
  String best = null, bestKey = null;
  for(Entry e : entries){
   if(!TYPE_APP.equals(e.type) || e.value.isEmpty()) continue;
   if(low.contains(e.key.toLowerCase()) && (bestKey == null || e.key.length() > bestKey.length())){
    best = e.value; bestKey = e.key;
   }
  }
  return best;
 }
 public static synchronized String pref(Context ctx, String name){
  load(ctx);
  for(Entry e : entries) if(TYPE_PREF.equals(e.type) && e.key.equals(name)) return e.value;
  return null;
 }
 /** 这些坑要提醒模型别踩 */
 public static synchronized List<Entry> avoidFor(Context ctx, String pkg){
  load(ctx);
  List<Entry> out = new ArrayList<Entry>();
  for(Entry e : entries) if(TYPE_AVOID.equals(e.type) && (e.key.equals(pkg) || e.key.equals("*"))) out.add(e);
  return out;
 }
 /** 用户教过我的操作（"点'全部'才是我的订单"）。key 是 "包名|页面特征"。 */
 public static synchronized List<Entry> guideFor(Context ctx, String pkg, String page){
  load(ctx);
  List<Entry> out = new ArrayList<Entry>();
  for(Entry e : entries){
   if(!TYPE_GUIDE.equals(e.type)) continue;
   String[] kv = e.key.split("\\|", 2);
   if(kv.length == 0) continue;
   if(!kv[0].equals(pkg) && !kv[0].equals("*")) continue;
   // 页面特征对得上才提示，避免无关页面的噪音
   // 页面指纹对不上就跳过，避免无关页面的噪音；
   // 但如果当时没记下指纹（空），只要包名对也得提示——用户的纠正很值钱，宁可多提一次。
   if(kv.length == 2 && !kv[1].isEmpty() && page != null && !page.isEmpty()){
    boolean hit = false;
    for(String tok : kv[1].split("[、,，/ ]")){
     if(tok.length() >= 2 && page.contains(tok)){ hit = true; break; }
    }
    if(!hit) continue;
   }
   out.add(e);
  }
  return out;
 }

 private static void save(Context ctx){
  try{
   FileWriter w = new FileWriter(file(ctx));
   w.write(rawJsonNoLoad());
   w.close();
   lastError = "";
  }catch(Throwable t){ lastError = "写入失败 " + t; }
 }
 private static String rawJsonNoLoad(){
  try{
   JSONObject o = new JSONObject();
   JSONArray arr = new JSONArray();
   for(Entry e : entries){
    JSONObject j = new JSONObject();
    j.put("type", e.type); j.put("key", e.key); j.put("value", e.value); j.put("note", e.note);
    arr.put(j);
   }
   o.put("entries", arr);
   return o.toString(2);
  }catch(Throwable t){ return "{\"entries\":[]}"; }
 }
 public static String lastError(){ return lastError; }
}
