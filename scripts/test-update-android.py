#!/usr/bin/env python3
"""Run updater regression checks against a preview app on an existing Android emulator.
Requires the target APK version to exceed the installed preview version.
Usage: test-update-android.py older.apk newer.apk release.json
"""
from pathlib import Path
import os,sys,subprocess,zipfile,shutil
root=Path(__file__).resolve().parents[1];sdk=Path(os.environ['ANDROID_SDK_ROOT']);java=Path(os.environ['JAVA_HOME'])/'bin';bt=sdk/'build-tools/35.0.0';platform=sdk/'platforms/android-35/android.jar';out=root/'build/update-tests';out.mkdir(parents=True,exist_ok=True)
def run(*args):subprocess.run([str(x) for x in args],check=True)
for name in ['classes','dex']:
 p=out/name
 if p.exists():shutil.rmtree(p)
 p.mkdir()
manifest=out/'AndroidManifest.xml';manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="chat.vibe.app.updatetest"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:label="Vibe Update Test"/><instrumentation android:name="chat.vibe.app.AndroidUpdateTest" android:targetPackage="chat.vibe.app"/></manifest>')
unsigned=out/'unsigned.apk';run(bt/'aapt2','link','-o',unsigned,'-I',platform,'--manifest',manifest)
run(java/'javac','-encoding','UTF-8','--release','8','-classpath',os.pathsep.join(map(str,[platform,root/'build/manual/classes'])),'-d',out/'classes',root/'tests/AndroidUpdateTest.java')
run(java/'java','-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',platform,'--classpath',root/'build/manual/classes','--output',out/'dex',*list((out/'classes').rglob('*.class')))
with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:
 z.write(out/'dex/classes.dex','classes.dex')
 for p,n in zip(sys.argv[1:4],['old.apk','new.apk','release.json']):z.write(p,'assets/'+n)
run(bt/'zipalign','-f','4',unsigned,out/'aligned.apk')
run(java/'java','-jar',bt/'lib/apksigner.jar','sign','--ks',root/'build/debug.keystore','--ks-pass','pass:android','--out',out/'tests.apk',out/'aligned.apk')
run(sdk/'platform-tools/adb','install','-r',out/'tests.apk')
run(sdk/'platform-tools/adb','shell','am','force-stop','chat.vibe.app')
r=subprocess.check_output([str(sdk/'platform-tools/adb'),'shell','am','instrument','-w','chat.vibe.app.updatetest/chat.vibe.app.AndroidUpdateTest'],text=True);print(r);assert 'UPDATE_REGRESSION_PASS' in r and 'UPDATE_REGRESSION_FAIL' not in r
