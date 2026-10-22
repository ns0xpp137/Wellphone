from pathlib import Path
import subprocess,zipfile,os,sys
r=Path(__file__).parent;sdk=Path('C:/Users/26790/AppData/Local/Android/Sdk');bt=sdk/'build-tools/33.0.0';jar=sdk/'platforms/android-33/android.jar';jdk=Path('D:/MyProjects/JDK/bin')
os.environ['JAVA_HOME']='D:/MyProjects/JDK'
os.environ['PATH']=str(jdk)+os.pathsep+os.environ['PATH']
def run(args):subprocess.run([str(x) for x in args],cwd=r,check=True)

# ===== 两个变体 =====
# 正式版：冻结不动，只用来演示。
# 开发版：后续所有改动都在这上面做。
# 两者 applicationId 不同，所以能同时装在同一台手机上互不干扰。
VARIANTS = {
 'release': dict(pkg='local.wellphone.remote', label='Wellphone 手机助手',
                 ver='1.2', code='3', out='Wellphone-1.2.apk'),
 'dev':     dict(pkg='local.wellphone.dev',    label='Wellphone 手机助手·开发版',
                 ver='0.14', code='5', out='Wellphone-Dev-0.1.apk'),
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
szcp=os.pathsep.join([str(jar),str(r/'libs/shizuku-api-classes.jar'),str(r/'libs/shizuku-aidl-classes.jar')])
run([jdk/'javac.exe','-encoding','UTF-8','-source','8','-target','8','-classpath',szcp,'-d',CLS,*r.glob('src/**/*.java')])
run([jdk/'java.exe','-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--min-api','30','--lib',jar,'--output',DEX,*r.glob(CLS+'/**/*.class'),str(r/'libs/shizuku-api-classes.jar'),str(r/'libs/shizuku-aidl-classes.jar')])
with zipfile.ZipFile(r/'unsigned.apk','a') as z:z.write(r/(DEX+'/classes.dex'),'classes.dex')
run([bt/'zipalign.exe','-f','4','unsigned.apk','aligned_%s.apk'%variant])
if not (r/'probe.jks').exists():run([jdk/'keytool.exe','-genkeypair','-keystore','probe.jks','-storepass','android','-keypass','android','-alias','probe','-dname','CN=Local Probe','-keyalg','RSA','-validity','365'])
run([jdk/'java.exe','-jar',bt/'lib/apksigner.jar','sign','--ks','probe.jks','--ks-pass','pass:android','--out',V['out'],'aligned_%s.apk'%variant])
run([jdk/'java.exe','-jar',bt/'lib/apksigner.jar','verify',V['out']])
print('Built',r/V['out'])
