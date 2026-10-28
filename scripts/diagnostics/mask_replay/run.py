#!/usr/bin/env python3
"""Broker exact SentencePiece tokenization for a standalone host/emulator mask replay."""
import argparse, hashlib, json, os, selectors, signal, struct, subprocess, time, threading
from pathlib import Path
import sentencepiece as spm
ROOT=Path(__file__).resolve().parents[3]
def require(ok,msg):
 if not ok: raise ValueError(msg)
def sha(b):return hashlib.sha256(b).hexdigest()
def parse_lock(data):
 # Restricted Cargo.lock reader: dependency identities only; no TOML evaluation.
 import re
 result={}
 for block in data.split('[[package]]')[1:]:
  fields=dict(re.findall(r'^([a-z_]+) = "([^"\n]+)"$',block,re.M))
  if fields.get('source','').startswith('registry+'):
   key=(fields['name'],fields['version']);require(key not in result,'duplicate Cargo package');result[key]=fields.get('checksum')
 return result

def verify_workspace(root):
 prep=json.loads((root/'preparation.json').read_text())
 for name,h in prep['files'].items():
  # Cargo legitimately prunes the copied upstream lock; separately verify all
  # remaining registry packages against the upstream identities below.
  if name=='workspace/Cargo.lock':continue
  require(sha((root/name).read_bytes())==h,'prepared file changed: '+name)
 source=Path(__file__).parent
 for name in ['Cargo.toml','src/main.rs']:
  require((source/name).read_bytes()==(root/'workspace'/name).read_bytes(),'rebuild required: '+name)
 expected=parse_lock((root/'downloads/Cargo.lock').read_text())
 actual=parse_lock((root/'workspace/Cargo.lock').read_text())
 require(actual and all(expected.get(k)==v for k,v in actual.items()),'dependency identity drift')
 return prep

class DiagnosticInterrupted(RuntimeError):
 """Signal interruption must not be swallowed as selector EINTR."""

class NativeDiagnosticError(ValueError):
 """Explicit native error frame, distinct from timeout/transport/setup failure."""

def reap_owned(process):
 """Reap only this owned child, retaining uncertainty if a stop is denied."""
 if process.poll() is None:
  try:os.killpg(process.pid,signal.SIGKILL)
  except ProcessLookupError:pass
  except PermissionError as error:
   # A native error frame is emitted immediately before exit. macOS can deny
   # group signalling during that exit race; confirmed reaping resolves it.
   try:process.wait(timeout=.25)
   except subprocess.TimeoutExpired:raise RuntimeError('owned native cleanup uncertain') from error
 process.wait(timeout=5)

def run_probe(command,cfg,processor,folder,timeout=30):
 require(threading.current_thread() is threading.main_thread(),'broker requires main-thread signal ownership')
 folder.mkdir()
 transcript=[];transcript_bytes=0;started=time.monotonic();result=None
 # Establish interruption ownership before constructing any native child. While
 # synchronous spawn/setup runs, record signals rather than raising between
 # child creation and assignment of its Popen handle.
 process=None;selector=None;stderr=None;previous_signals={};pending_signal=None;phase='setup';failure=None
 def interrupt_owned(signum,frame):
  nonlocal pending_signal
  pending_signal=signum
  if phase=='active':raise DiagnosticInterrupted('diagnostic interrupted by signal '+str(signum))
 def check_interruption():
  if pending_signal is not None:raise DiagnosticInterrupted('diagnostic interrupted by signal '+str(pending_signal))
 buffer=bytearray()
 def send(v):
  payload=(json.dumps(v,separators=(',',':'))+'\n').encode()
  require(len(payload)<=1_048_576,'oversized broker reply/configuration')
  # A child that stops reading must not bypass the same monotonic watchdog.
  fd=process.stdin.fileno();os.set_blocking(fd,False)
  writer=selectors.DefaultSelector();writer.register(fd,selectors.EVENT_WRITE)
  offset=0
  try:
   while offset<len(payload):
    remaining=timeout-(time.monotonic()-started)
    if remaining<=0 or not writer.select(remaining):raise TimeoutError('diagnostic write timeout')
    try:offset+=os.write(fd,memoryview(payload)[offset:])
    except BlockingIOError:continue
  finally:writer.close()
 try:
  for sig in [signal.SIGINT,signal.SIGTERM]:
   previous_signals[sig]=signal.signal(sig,interrupt_owned)
  check_interruption()
  stderr=(folder/'stderr.txt').open('wb')
  check_interruption()
  process=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=stderr,start_new_session=True)
  selector=selectors.DefaultSelector();selector.register(process.stdout,selectors.EVENT_READ)
  phase='active';check_interruption()
  send(cfg)
  while result is None:
   remaining=timeout-(time.monotonic()-started)
   if remaining<=0:raise TimeoutError('diagnostic timeout')
   if not selector.select(remaining):raise TimeoutError('diagnostic timeout')
   part=os.read(process.stdout.fileno(),65536)
   require(part,'diagnostic ended before a result')
   buffer.extend(part);require(len(buffer)<=2*1024*1024,'oversized diagnostic output')
   while b'\n' in buffer:
    line,_,tail=buffer.partition(b'\n');buffer[:]=tail
    v=json.loads(line)
    if v.get('event')=='tokenize':
     require(isinstance(v.get('bytes'),list) and len(v['bytes'])<=1_048_576 and all(type(n) is int and 0<=n<=255 for n in v['bytes']),'invalid callback bytes')
     raw=bytes(v['bytes']);ids=processor.encode(raw)
     entry={'bytesHex':raw.hex(),'ids':ids}
     transcript_bytes+=len(json.dumps(entry,separators=(',',':')).encode())
     require(transcript_bytes<=4*1024*1024,'tokenizer transcript budget exceeded')
     transcript.append(entry);send({'tokens':ids})
     require(len(transcript)<=2048,'too many tokenizer callbacks')
    elif v.get('event')=='result': result=v;break
    elif v.get('event')=='error':raise NativeDiagnosticError('native diagnostic error: '+str(v.get('message')))
    else:raise ValueError('invalid diagnostic frame: '+str(v))
  process.stdin.close()
  remaining=timeout-(time.monotonic()-started)
  if remaining<=0:raise TimeoutError('diagnostic completion timeout')
  code=process.wait(timeout=remaining)
  require(code==0,'diagnostic failed: '+str(code))
  states=result.get('states',[])
  if 'mask' in result: states=[{'newlines':'rejected-prefix','mask':result['mask']}]
  for state in states:
   mask=state['mask'];words=mask.pop('maskWords')
   require(len(words)==8192 and all(isinstance(v,int) and 0<=v<=0xffffffff for v in words),'bad mask framing')
   data=struct.pack('<8192I',*words);name=f"mask-{state['newlines']}.bin";(folder/name).write_bytes(data)
   mask['sha256']=sha(data);mask['path']=name
  record={'config':cfg,'result':result,'elapsedMs':round((time.monotonic()-started)*1000),
   'tokenizerCalls':len(transcript),'tokenizerTranscriptSha256':sha(json.dumps(transcript,separators=(',',':')).encode())}
  (folder/'tokenizer-transcript.json').write_text(json.dumps(transcript,indent=2)+'\n')
  (folder/'result.json').write_text(json.dumps(record,indent=2)+'\n')
  return record
 except BaseException as error:
  phase='cleanup';cleanup_error=None
  if process is not None:
   try:reap_owned(process)
   except Exception as caught:cleanup_error=caught
  failure={'error':str(error),'tokenizerCalls':transcript,'childStarted':process is not None,'cleanup':('uncertain' if cleanup_error else 'confirmed') if process is not None else 'not-started'}
  if cleanup_error:failure['cleanupError']=str(cleanup_error)
  (folder/'failed.json').write_text(json.dumps(failure,indent=2)+'\n')
  if cleanup_error:raise RuntimeError('owned native cleanup uncertain') from error
  raise
 finally:
  phase='cleanup'
  if selector is not None:selector.close()
  if process is not None:
   process.stdin.close();process.stdout.close()
  if stderr is not None:stderr.close()
  for sig,handler in previous_signals.items():signal.signal(sig,handler)
  if pending_signal is not None and failure is None:
   # A late signal cannot silently admit the next campaign case. Keep any
   # completed native observation, but fail this interrupted broker invocation.
   (folder/'failed.json').write_text(json.dumps({'error':'late diagnostic interruption','cleanup':'confirmed','interruptedAfterNativeCompletion':True},indent=2)+'\n')
   check_interruption()


def execute(workspace,out,serial=None,adb='adb'):
 require(serial is None or serial.startswith('emulator-'),'only an emulator is authorized by this runner')
 prep=verify_workspace(workspace)
 require(not out.exists(),'report directory already exists; preserve prior attempts')
 out.mkdir(parents=True)
 assets=out/'inputs';assets.mkdir()
 for name in ['raw.vocab','decoded.vocab','tokenizer.bin','schema.json','prefix.txt']:
  data=(workspace/'assets'/name).read_bytes();require(sha(data)==prep['files']['assets/'+name],'input changed before staging')
  (assets/name).write_bytes(data)
 processor=spm.SentencePieceProcessor(model_file=str(assets/'tokenizer.bin'))
 schema=(assets/'schema.json').read_text();prefix=(assets/'prefix.txt').read_text()
 prefix_ids=processor.encode(prefix);require(processor.decode(prefix_ids)==prefix,'prefix reconstruction differs')
 manifest={'schema':1,'scope':'standalone-grammar-mask-replay','nativeGemmaExecution':False,'semanticEnablement':False,
  'preparedInputsSha256':sha((workspace/'preparation.json').read_bytes()),'eos':prep['eos'],
  'toolSources':{str(p.relative_to(ROOT)):sha(p.read_bytes()) for p in sorted(Path(__file__).parent.rglob('*')) if p.is_file() and p.suffix in ['.py','.rs','.toml','.lock']},
  'prefixTokenHistory':'reconstructed canonical IDs; actual sampled IDs unavailable','cases':[]}
 remote=None;cleanup_error=None
 binary_source=workspace/('workspace/target/aarch64-linux-android/release/edge-mask-replay' if serial else 'workspace/target/release/edge-mask-replay')
 binary=assets/'edge-mask-replay';binary.write_bytes(binary_source.read_bytes());binary.chmod(0o700)
 manifest['executableSha256']=sha(binary.read_bytes())
 manifest['inputHashes']={p.name:sha(p.read_bytes()) for p in assets.iterdir()}
 try:
  if serial:
   require(serial.startswith('emulator-'),'only an emulator is authorized by this runner')
   def call(*args):return subprocess.check_output([adb,'-s',serial,*args],timeout=20).decode().strip()
   require(call('shell','getprop','ro.kernel.qemu')=='1','target is not an emulator')
   require(call('shell','getprop','ro.product.cpu.abi')=='arm64-v8a','this build targets arm64 only')
   remote='/data/local/tmp/edge-mask-replay-'+os.urandom(8).hex()
   call('shell','mkdir',remote)
   for path in [binary,assets/'raw.vocab',assets/'decoded.vocab']:
    call('push',str(path),remote+'/'+path.name)
    require(call('shell','sha256sum',remote+'/'+path.name).split()[0]==manifest['inputHashes'][path.name],'emulator input hash mismatch')
   call('shell','chmod','700',remote+'/edge-mask-replay')
   command=[adb,'-s',serial,'shell','-T','timeout','30',remote+'/edge-mask-replay']
   manifest['environment']={'target':'android-emulator','serial':serial,'abi':'arm64-v8a','api':call('shell','getprop','ro.build.version.sdk')}
  else:
   command=[str(binary)]
   manifest['environment']={'target':'host','uname':list(os.uname())}
  manifest['executableSha256']=sha(binary.read_bytes())
  for mode in ['raw','decoded']:
   for kind,ids in [('canonical-prefix',prefix_ids),('literal-byte-prefix',[processor.piece_to_id(chr(b)) for b in prefix.encode()]),('zero-continuation',prefix_ids+[236771])]:
    # The second segmentation is a synthetic alternative, never an asserted native history.
    require(all(0<=i<262144 and processor.id_to_piece(i)==chr(b) for i,b in zip(ids,prefix.encode())) if kind=='literal-byte-prefix' else True,'missing literal byte token')
    vocabulary=remote+'/'+mode+'.vocab' if remote else str(assets/(mode+'.vocab'))
    cfg={'vocabulary':vocabulary,'eos':prep['eos'],'schema':schema,'prefix':ids,
      'watch':[107,108,236771,236770,236743,prep['eos']], 'newlineCounts':([0] if kind=='zero-continuation' else [0,1,2,16,187,228])}
    name=mode+'-'+kind
    record=run_probe(command,cfg,processor,out/name)
    manifest['cases'].append({'id':name,'status':record['result']['status'],'path':name+'/result.json'})
  manifest['status']='completed'
 except BaseException as error:
  manifest['status']='failed';manifest['error']=str(error);raise
 finally:
  if remote:
   try:subprocess.run([adb,'-s',serial,'shell','rm','-r',remote],check=True,timeout=15,capture_output=True);manifest['remoteCleanup']='confirmed'
   except Exception as error:
    manifest['remoteCleanup']='uncertain';manifest['cleanupError']=str(error);manifest['status']='failed';cleanup_error=error
  manifest['files']={str(p.relative_to(out)):sha(p.read_bytes()) for p in sorted(out.rglob('*')) if p.is_file()}
  (out/'report.json').write_text(json.dumps(manifest,indent=2)+'\n')
 if cleanup_error:raise RuntimeError('emulator cleanup unconfirmed') from cleanup_error
 return manifest
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--workspace',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--serial');p.add_argument('--adb',default='adb')
 a=p.parse_args();print(json.dumps(execute(a.workspace.resolve(),a.out.resolve(),a.serial,a.adb),indent=2))
