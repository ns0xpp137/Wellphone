from pathlib import Path
import subprocess,zipfile,os,sys
r=Path(__file__).parent

# ===== 工具链定位：优先环境变量，其次常见默认位置 =====
def _first_dir(cands):
    for c in cands:
        try:
            if c and Path(c).is_dir(): return Path(c)
        except Exception: pass
    return None

def _sdk_root():
    cands=[os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT'),
           Path(os.environ.get('LOCALAPPDATA',''))/'Android'/'Sdk',
           Path.home()/'AppData'/'Local'/'Android'/'Sdk',
           Path('/usr/local/share/android-sdk')]
    d=_first_dir(cands)
    if d is None: raise SystemExit('找不到 Android SDK：请设置环境变量 ANDROID_HOME')
    return d

def _jdk_root():
    cands=[]
    for k in ('JAVA_HOME','JDK_HOME'):
        v=os.environ.get(k)
        if v: cands.append(Path(v))
    cands += [Path('D:/MyProjects/JDK'), Path('C:/Program Files/Java/jdk-17'),
              Path('/usr/lib/jvm/default-java')]
    for c in cands:
        try:
            if c and ((c/'bin'/'javac.exe').exists() or (c/'bin'/'javac').exists()): return c
        except Exception: pass
    raise SystemExit('找不到 JDK（需要 javac）：请设置环境变量 JAVA_HOME')

sdk=_sdk_root(); bt=sdk/'build-tools/33.0.0'; jar=sdk/'platforms/android-33/android.jar'
jdkroot=_jdk_root(); jdk=jdkroot/'bin'
os.environ['JAVA_HOME']=str(jdkroot)
os.environ['PATH']=str(jdk)+os.pathsep+os.environ['PATH']

def run(args):
    p=subprocess.run([str(x) for x in args],cwd=r,capture_output=True)
    if p.returncode!=0:
        out=(p.stdout or b'')+(p.stderr or b'')
        print('FAILED:', ' '.join(str(x) for x in args)[:200])
        print(out.decode('utf-8','replace')[:3000])
        raise SystemExit(1)

# ===== 两个变体 =====
# 正式版：冻结不动，只用来演示。
# 开发版：后续所有改动都在这上面做。
# 两者 applicationId 不同，所以能同时装在同一台手机上互不干扰。
VARIANTS = {
 'release': dict(pkg='local.wellphone.remote', label='Wellphone 手机助手',
                 ver='1.2', code='3', out='Wellphone-1.2.apk'),
 'dev':     dict(pkg='local.wellphone.dev',    label='Wellphone 手机助手·开发版',
                 ver='0.14', code='5', out='Wellphone-Dev-0.1.apk'),
 # 集成版：内置 Shizuku 的 adb 配对能力（Apache-2.0），不装第二个 App，
 # 全程在手机上完成一次无线调试配对即可拿到 shell 权限。
 # 0.2 起改名叫「机灵」，图标换成蓝色 S 丝带（res/mipmap-*/ic_launcher.png）。
 # applicationId 不变，所以能覆盖升级，配对信息和无障碍授权都保留。
 # code 递增到 3：换图标时靠它让桌面强制刷新图标缓存
 'builtin': dict(pkg='local.wellphone.builtin', label='机灵',
                 ver='0.5', code='5', out='Jiling-0.5.apk'),
 # 技能版：与「机灵」并存安装（applicationId 不同），用来开发程序化记忆。
 # ★ 未确认前不要重新编译/安装 builtin，机灵这个能演示的版本保持不动。
 'skill': dict(pkg='local.wellphone.skill', label='机灵·技能版',
               ver='0.5', code='3', out='Jiling-Skill-0.5.apk'),
}
variant = 'dev'
for a in sys.argv[1:]:
    if a in VARIANTS: variant = a
V = VARIANTS[variant]
print('=== 构建变体: %s (pkg=%s, v%s) ===' % (variant, V['pkg'], V['ver']))

# 生成变体 manifest：换 package / provider authority / 应用名 / 版本号
mf = (r/'AndroidManifest.xml').read_text(encoding='utf-8')
mf = mf.replace('package="local.wellphone.remote"',
                'package="%s" android:versionCode="%s" android:versionName="%s"' % (V['pkg'], V['code'], V['ver']))
mf = mf.replace('android:authorities="local.wellphone.remote.shizuku"',
                'android:authorities="%s.shizuku"' % V['pkg'])
mf = mf.replace('android:label="Wellphone"', 'android:label="%s"' % V['label'])
mf = mf.replace('android:label="Wellphone 服务"', 'android:label="%s 服务"' % V['label'])
# aapt 只认文件名叫 AndroidManifest.xml，所以按变体放到不同子目录里
mdir = r/'variant'/variant; mdir.mkdir(parents=True, exist_ok=True)
tmp_manifest = mdir/'AndroidManifest.xml'
tmp_manifest.write_text(mf, encoding='utf-8')
MANIFEST_ARG = str(Path('variant')/variant/'AndroidManifest.xml')

CLS='classes_'+variant;DEX='dex_'+variant
(r/CLS).mkdir(exist_ok=True);(r/DEX).mkdir(exist_ok=True)
run([bt/'aapt.exe','package','-f','-M',MANIFEST_ARG,'-S','res','-I',jar,'-F','unsigned.apk'])
extra_cp=[str(jar),str(r/'libs/shizuku-api-classes.jar'),str(r/'libs/shizuku-aidl-classes.jar')]
# adb 配对模块（取自 Shizuku，Apache-2.0）+ Kotlin 运行时。
# 三个变体统一带上：代码里 BuiltinShell 引用它，不同步会编译失败；
# 而且正式版有这个能力也不是坏事（无 Shizuku 时仍可跑）。
extra_cp.append(str(r/'libs/adbmod.jar'))
extra_cp.append(str(r/'libs/kotlin-stdlib.jar'))
szcp=os.pathsep.join(extra_cp)
run([jdk/'javac.exe','-encoding','UTF-8','-nowarn','-source','8','-target','8','-classpath',szcp,'-d',CLS,*r.glob('src/**/*.java')])
run([jdk/'java.exe','-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--min-api','30','--lib',jar,'--output',DEX,*r.glob(CLS+'/**/*.class'),str(r/'libs/shizuku-api-classes.jar'),str(r/'libs/shizuku-aidl-classes.jar')])
with zipfile.ZipFile(r/'unsigned.apk','a') as z:
    z.write(r/(DEX+'/classes.dex'),'classes.dex')
    z.write(r/'libs/adbdex/classes.dex','classes2.dex')
    z.write(r/'libs/libadb-arm64.so','lib/arm64-v8a/libadb.so')
    # Conscrypt（独立版，公开 API）：配对要用到 exportKeyingMaterial，不能依赖系统 hidden API
    z.write(r/'libs/libconscrypt-arm64.so','lib/arm64-v8a/libconscrypt_jni.so')
    z.write(r/'libs/libconscrypt-armv7.so','lib/armeabi-v7a/libconscrypt_jni.so')
    print('  已内置 adb 配对模块(classes2.dex) + libadb.so')
run([bt/'zipalign.exe','-f','4','unsigned.apk','aligned_%s.apk'%variant])
if not (r/'probe.jks').exists():run([jdk/'keytool.exe','-genkeypair','-keystore','probe.jks','-storepass','android','-keypass','android','-alias','probe','-dname','CN=Local Probe','-keyalg','RSA','-validity','365'])
run([jdk/'java.exe','-jar',bt/'lib/apksigner.jar','sign','--ks','probe.jks','--ks-pass','pass:android','--out',V['out'],'aligned_%s.apk'%variant])
run([jdk/'java.exe','-jar',bt/'lib/apksigner.jar','verify',V['out']])
print('Built',r/V['out'])
