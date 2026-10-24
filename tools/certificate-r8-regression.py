"""Reproduce provider removal and test the production certificate factory after R8 shrinking.

Runs on a desktop JDK, without an emulator. Does not test AndroidKeyStore or actual wireless pairing.
"""
from pathlib import Path
import argparse, json, os, subprocess, zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--java-home',default=os.environ.get('JAVA_HOME'))
parser.add_argument('--gradle-cache',default=os.environ.get('GRADLE_USER_HOME'))
parser.add_argument('--output',default=str(Path(__file__).resolve().parents[1]/'build/certificate-r8-regression'))
args=parser.parse_args()
assert args.java_home and args.gradle_cache, 'Set JAVA_HOME and GRADLE_USER_HOME, or pass both paths'
jdk=Path(args.java_home).resolve()
cache=Path(args.gradle_cache).resolve()/'caches/modules-2/files-2.1'
project=Path(__file__).resolve().parents[1]
out=Path(args.output).resolve(); out.mkdir(parents=True,exist_ok=True)
classes=out/'classes'; classes.mkdir(exist_ok=True)
def one(pattern):
    files=list(cache.glob(pattern)); assert len(files)==1,files
    return files[0]
builder=one('com.android.tools.build/builder/8.9.2/*/builder-8.9.2.jar')
deps=[one(f'org.bouncycastle/{name}-jdk15to18/1.81/*/{name}-jdk15to18-1.81.jar') for name in ['bcprov','bcpkix','bcutil']]
source=out/'CertificateRegression.java'
source.write_text('''
import dev.shizzi.adb.AdbCertificate;
import java.security.*;
import java.security.cert.X509Certificate;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
public class CertificateRegression {
  public static void main(String[] args) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    if (args.length > 0 && args[0].equals("old")) {
      try {
        new JcaContentSignerBuilder("SHA256withRSA").setProvider(new BouncyCastleProvider()).build(pair.getPrivate());
        throw new AssertionError("Expected the old reflection-based provider to fail after shrinking");
      } catch (OperatorCreationException expected) {
        if (!(expected.getCause() instanceof NoSuchAlgorithmException)) throw expected;
        System.out.println("PASS: old path reproduces OperatorCreationException -> NoSuchAlgorithmException");
      }
      return;
    }
    X509Certificate certificate = AdbCertificate.create(pair, System.currentTimeMillis());
    certificate.checkValidity(); certificate.verify(pair.getPublic());
    if (!certificate.getSigAlgOID().equals("1.2.840.113549.1.1.11")) throw new AssertionError();
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(pair.getPrivate()); signer.update(new byte[]{1,2,3});
    byte[] proof = signer.sign();
    signer.initVerify(certificate.getPublicKey()); signer.update(new byte[]{1,2,3});
    if (!signer.verify(proof)) throw new AssertionError();
    System.out.println("PASS: production certificate factory signs and verifies after R8 shrinking");
  }
}
''',encoding='utf-8')
def run(command,log):
    result=subprocess.run([str(s) for s in command],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
    (out/log).write_text(result.stdout,encoding='utf-8')
    if result.returncode: raise RuntimeError(f'{log}: {result.stdout[-6000:]}')
    return result.stdout.strip()
run([jdk/'bin/javac.exe','--release','8','-cp',os.pathsep.join(map(str,deps)),'-d',classes,
     project/'app/src/main/java/dev/shizzi/adb/AdbCertificate.java',source],'javac.log')
program=out/'program.jar'
with zipfile.ZipFile(program,'w') as z:
    for file in classes.rglob('*.class'): z.write(file,file.relative_to(classes).as_posix())
rules=out/'rules.pro'
rules.write_text('-keep public class CertificateRegression { public static void main(java.lang.String[]); }\n-dontwarn **\n-printusage '+(out/'usage.txt').as_posix()+'\n',encoding='utf-8')
shrunk=out/'shrunk.jar'
run([jdk/'bin/java.exe','-Xmx1536m','-cp',builder,'com.android.tools.r8.R8','--release','--classfile','--no-desugaring',
     '--lib',jdk,'--pg-conf',rules,'--output',shrunk,program,*deps],'r8.log')
usage=(out/'usage.txt').read_text(encoding='utf-8')
assert 'org.bouncycastle.jcajce.provider.asymmetric.RSA$Mappings' in usage
old=run([jdk/'bin/java.exe','-cp',shrunk,'CertificateRegression','old'],'old-path.log')
new=run([jdk/'bin/java.exe','-cp',shrunk,'CertificateRegression','new'],'fixed-path.log')
record={'old_failure_reproduced':True,'fixed_factory_after_r8':True,'old_result':old,'fixed_result':new,
        'android_keystore_tested':False,'physical_pairing_tested':False}
(out/'result.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(record,ensure_ascii=False))
