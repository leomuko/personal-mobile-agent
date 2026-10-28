#!/usr/bin/env python3
"""One frozen host-only x-guidance candidate. No ADB, model inference, or output repair."""
import argparse, copy, hashlib, json, shutil, sys
from pathlib import Path
import sentencepiece as spm
from jsonschema import Draft7Validator
sys.path.insert(0,str(Path(__file__).parent))
import run as broker
ROOT=Path(__file__).resolve().parents[3]
FIXTURES=ROOT/'planning/fixtures/grammar-progress-v1'
LOCK_SHA='ac08cdab90f90b959681002270114f35d4e5bfdfa94b89aa167d5b334a9a6749'
def require(ok,msg):
 if not ok: raise ValueError(msg)
def sha(data):return hashlib.sha256(data).hexdigest()
def verify_fixtures(folder=FIXTURES):
 data=(folder/'preparation-lock.json').read_bytes();require(sha(data)==LOCK_SHA,'candidate lock identity changed')
 lock=json.loads(data)
 for name,h in lock['files'].items():require(sha((folder/name).read_bytes())==h,'candidate input changed: '+name)
 for name,h in lock['baselineSchemas'].items():require(sha((ROOT/'planning/fixtures/constrained-decoding-v2'/name).read_bytes())==h,'V2 schema changed')
 validator=ROOT/'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/EvidenceOutputValidator.kt'
 protocol=json.loads((folder/'protocol.json').read_text());require(sha(validator.read_bytes())==protocol['historicalStrictValidatorSha256'],'strict validator changed')
 require(protocol['candidate']=={'x-guidance':{'whitespace_flexible':False}},'not the single approved host candidate')
 for name in ['keyword-sentinel','evidence-output']:
  baseline=json.loads((ROOT/f'planning/fixtures/constrained-decoding-v2/{name}.schema.json').read_text())
  candidate=json.loads((folder/(name+'.no-whitespace.schema.json')).read_text())
  require(candidate.pop('x-guidance')=={'whitespace_flexible':False} and candidate==baseline,'candidate changes more than whitespace')
 return protocol

def mask_allowed(record,id,state=0):
 return next(x['allowed'] for x in record['result']['states'][state]['mask']['watch'] if x['id']==id)

def verify_prepared_metadata(prep,protocol):
 require(type(prep.get('eos')) is int and type(protocol['eos']) is int and prep['eos']==protocol['eos']==1,'prepared EOS identity changed')
 stops=prep.get('stopTokenIds')
 require(type(stops) is list and len(stops)==3 and all(type(seq) is list and len(seq)==1 and type(seq[0]) is int for seq in stops) and stops==[[1],[50],[106]],'prepared stop-token identity changed')
 require(type(prep.get('vocabEntries')) is int and prep['vocabEntries']==262144 and prep.get('nativeGemmaExecution') is False,'prepared runtime scope changed')

def campaign(workspace,out,baseline_reference):
 protocol=verify_fixtures();prep=broker.verify_workspace(workspace);verify_prepared_metadata(prep,protocol)
 require(not out.exists(),'retain existing attempt; choose a new output directory')
 out.mkdir(parents=True)
 report={'schema':1,'protocolId':protocol['protocolId'],'candidateLockSha256':LOCK_SHA,'status':'running',
  'nativeModelExecution':False,'deviceExecution':False,'semanticEnablement':False,'cases':[]}
 try:
  # Baseline is a prerequisite: never credit a different grammar/tokenizer build.
  baseline=out/'baseline';broker.execute(workspace,baseline)
  old=json.loads((baseline_reference/'report.json').read_text());new=json.loads((baseline/'report.json').read_text())
  require(len(old['cases'])==len(new['cases'])==6,'incomplete baseline')
  comparisons=[]
  for a,b in zip(old['cases'],new['cases']):
   require(a['id']==b['id'] and a['status']==b['status'],'baseline case mismatch')
   ar=json.loads((baseline_reference/a['path']).read_text());br=json.loads((baseline/b['path']).read_text())
   require(ar['tokenizerTranscriptSha256']==br['tokenizerTranscriptSha256'],'baseline tokenizer transcript differs')
   old_masks=sorted((baseline_reference/a['id']).glob('mask-*.bin'));new_masks=sorted((baseline/b['id']).glob('mask-*.bin'))
   require([x.name for x in old_masks]==[x.name for x in new_masks],'baseline mask set differs')
   for x,y in zip(old_masks,new_masks):require(x.read_bytes()==y.read_bytes(),'baseline mask differs');comparisons.append(sha(y.read_bytes()))
  require(len(comparisons)==16,'missing baseline masks')
  report['baseline']={'masksMatched':16,'transcriptsMatched':6,'referenceReportSha256':sha((baseline_reference/'report.json').read_bytes())}
  inputs=out/'inputs';inputs.mkdir()
  for p in FIXTURES.iterdir():
   if p.is_file():shutil.copyfile(p,inputs/p.name)
  processor=spm.SentencePieceProcessor(model_file=str(baseline/'inputs/tokenizer.bin'))
  binary=baseline/'inputs/edge-mask-replay';command=[str(binary)]
  report['binarySha256']=sha(binary.read_bytes())
  report['preparedInputsSha256']=sha((workspace/'preparation.json').read_bytes())
  report['toolSources']={str(p.relative_to(ROOT)):sha(p.read_bytes()) for p in sorted(Path(__file__).parent.rglob('*')) if p.is_file() and p.suffix in ['.py','.rs','.toml','.lock']}
  def probe(id,schema,text,mode='raw',counts=None,watch=None):
   ids=processor.encode(text);require(processor.decode(ids)==text,'reconstructed prefix does not roundtrip')
   cfg={'vocabulary':str(baseline/'inputs'/(mode+'.vocab')),'eos':prep['eos'],'schema':schema,'prefix':ids,
     'watch':watch or protocol['numericBoundaryWatch'],'newlineCounts':[0] if counts is None else counts}
   return broker.run_probe(command,cfg,processor,out/id)
  def record(id,raw,accepted):
   report['cases'].append({'id':id,'status':raw['result']['status'],'accepted':bool(accepted)})
  sentinel_base=(ROOT/'planning/fixtures/constrained-decoding-v2/keyword-sentinel.schema.json').read_text()
  sentinel_candidate=(inputs/'keyword-sentinel.no-whitespace.schema.json').read_text()
  compact=(inputs/'compact-prefix.txt').read_text()
  original=(baseline/'inputs/prefix.txt').read_text()
  for mode in ['raw','decoded']:
   r=probe(mode+'-baseline-compact',sentinel_base,compact,mode,protocol['newlineCheckpoints'])
   record(mode+'-baseline-compact',r,r['result']['status']=='replayed' and all(mask_allowed(r,107,i) and mask_allowed(r,236771,i) for i in range(6)))
   r=probe(mode+'-candidate-compact',sentinel_candidate,compact,mode)
   record(mode+'-candidate-compact',r,r['result']['status']=='replayed' and mask_allowed(r,236771) and all(not mask_allowed(r,n) for n in [107,108,236743,236770,1]))
   r=probe(mode+'-candidate-zero',sentinel_candidate,compact+'0',mode)
   record(mode+'-candidate-zero',r,r['result']['status']=='replayed' and not mask_allowed(r,236771))
   r=probe(mode+'-candidate-newline-consumption',sentinel_candidate,compact,mode,[0,1])
   record(mode+'-candidate-newline-consumption',r,r['result']['status']=='newline-rejected' and r['result']['after']==0)
   r=probe(mode+'-candidate-original-prefix',sentinel_candidate,original,mode)
   record(mode+'-candidate-original-prefix',r,r['result']['status']=='prefix-rejected')
  # Strict-context rejection is checked independently in Kotlin, not inferred
  # from JSON Schema. The grammar may admit structurally valid fabricated spans.
  full=(inputs/'evidence-output.no-whitespace.schema.json').read_text()
  witnesses=json.loads((inputs/'witnesses.json').read_text())['witnesses']
  for witness in witnesses:
   Draft7Validator(json.loads(full)).validate(json.loads(witness['output']))
   for mode in ['raw','decoded']:
    id=mode+'-witness-'+witness['id'];r=probe(id,full,witness['output'],mode,watch=[1])
    record(id,r,r['result']['status']=='replayed' and mask_allowed(r,1))
  # Distinguish identified schema-option failures from infra/transport/deadline.
  for bad in protocol['malformedOptionChecks']:
   id='negative-'+bad['id'];schema=json.loads(sentinel_candidate);schema['x-guidance']=bad['value']
   try:probe(id,json.dumps(schema,separators=(',',':')),compact)
   except broker.NativeDiagnosticError as error:
    require(bad['errorContains'] in str(error),'unexpected native rejection')
    report['cases'].append({'id':id,'status':'identified-native-option-rejection','accepted':True,'error':str(error)})
   else:raise ValueError('unsupported option was accepted: '+id)
  report['status']='passed-tested-host-states' if all(c['accepted'] for c in report['cases']) else 'failed-host-acceptance'
  report['failedCases']=[c['id'] for c in report['cases'] if not c['accepted']]
  report['strictValidatorCoverage']='separate unchanged-validator Kotlin host test required'
  return report
 except BaseException as error:
  report['status']='failed';report['error']=str(error);raise
 finally:
  report['files']={str(p.relative_to(out)):sha(p.read_bytes()) for p in sorted(out.rglob('*')) if p.is_file()}
  (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--workspace',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--baseline-reference',type=Path,default=ROOT/'build/llguidance-mask-replay/host-final-locked')
 a=p.parse_args();result=campaign(a.workspace.resolve(),a.out.resolve(),a.baseline_reference.resolve());print(json.dumps(result,indent=2));sys.exit(0 if result['status']=='passed-tested-host-states' else 2)
