#!/usr/bin/env python3
"""Prepare an isolated, patched LLGuidance replay. No native code or device is executed."""
import argparse, hashlib, io, json, shutil, struct, subprocess, tarfile, urllib.request
from pathlib import Path
import sentencepiece as spm
ROOT=Path(__file__).resolve().parents[3]
UPSTREAM='https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/'
PINS={
 'Cargo.lock':'b95103f9b316784814f1e39cc0124cbc134e61d3f0f27c7ef514408e65b7916a',
 'PATCH.llguidance_regexvec':'086d5c4a6b059d2a81b15a176898bde062c50c456d4960bbc55b1fcac9e2378d',
 'PATCH.llguidance_numeric':'375c7355b153b02d21923305018a5ced8719f407cbf7a348e5396b75119fa204',
 'PATCH.llguidance_grammar':'4744373878bdffcec12d466e08ac9a27b61ef4546485e2ac81dc5212532b50e4',
 'PATCH.llguidance_parser':'8cbac036cb9f0f49b0a815fc6d0a59d07ca9fd3e77fd4454b884e95066ceab8d',
 'PATCH.llguidance_perf':'f28d127e7dd28073c81570f5db9410b0c0860665ea3ce0877ae0d73b7aeb4daf',
 'PATCH.toktrie':'3f0ebc16a6280f4490d20018df9eba5153149b033897533831d57dd2b0bd21a3'}
ARCHIVES={
 'llguidance-1.3.0':'614b6ece5bc57641b9b727f8a79d338a46584efb943017d0a7454442c0b947fb',
 'toktrie-1.5.0':'de81c795b1f2e5b7e531fbb587e541e124b47f434af2c427a4ae73ea0d4eca6c'}
SCHEMA_SHA='e10adc86c84b6615df190205cf94b1baae0c0d1ef228dda2b5c5fe999ca5be53'
TOKENIZER_SHA='e594c8a90eb08d8bda498ff4747977dc827ae0c3c56b5c0d41a605a22d02ef03'
def require(ok,msg):
 if not ok: raise ValueError(msg)
def sha(b): return hashlib.sha256(b).hexdigest()
def pinned(cache,name,url,digest):
 p=cache/name
 if not p.exists():
  data=urllib.request.urlopen(url,timeout=60).read()
  require(sha(data)==digest,'download checksum mismatch: '+name);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
 data=p.read_bytes();require(sha(data)==digest,'cached checksum mismatch: '+name);return data

def apply_exact_patch(base,data):
 lines=data.decode().splitlines(keepends=True)
 require(len(lines)>=4 and lines[0].startswith('--- ') and lines[1].startswith('+++ '),'bad patch header')
 relative=Path(lines[1][4:].strip())
 require(not relative.is_absolute() and '..' not in relative.parts,'unsafe patch path')
 require(lines[0][4:].strip()==str(relative),'patch rename not allowed')
 require(lines[2].startswith('@@ ') and not any(x.startswith('@@ ') for x in lines[3:]),'expected one hunk')
 # Upstream PERF's declared hunk counts disagree with its body. Preserve all
 # context lines and require exactly one byte-for-byte match; never fuzzy patch.
 old=[];new=[]
 for line in lines[3:]:
  require(line[:1] in [' ','+','-'],'unsupported patch line')
  if line[0] in ' -':old.append(line[1:])
  if line[0] in ' +':new.append(line[1:])
 target=base/relative;data=target.read_text();before=''.join(old)
 require(data.count(before)==1,'patch context is missing or ambiguous: '+str(relative))
 target.write_text(data.replace(before,''.join(new),1))

def prepare(out,model):
 cache=out/'downloads';cache.mkdir(parents=True,exist_ok=True)
 work=out/'workspace';require(not work.exists(),'workspace already exists; use a fresh --out to preserve evidence')
 files={n:pinned(cache,n,UPSTREAM+n,h) for n,h in PINS.items()}
 work.mkdir()
 for name,h in ARCHIVES.items():
  package,version=name.rsplit('-',1)
  data=pinned(cache,name+'.crate',f'https://static.crates.io/crates/{package}/{name}.crate',h)
  with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz') as archive:
   for member in archive.getmembers():
    rel=Path(member.name);require(not rel.is_absolute() and '..' not in rel.parts,'unsafe archive path')
    if member.isfile():
     target=work/'vendor'/rel;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.extractfile(member).read())
 for n,data in files.items():
  if n.startswith('PATCH.'):
   package='toktrie-1.5.0' if n=='PATCH.toktrie' else 'llguidance-1.3.0'
   apply_exact_patch(work/'vendor'/package,data)
 source=Path(__file__).parent
 shutil.copyfile(source/'Cargo.toml',work/'Cargo.toml');shutil.copytree(source/'src',work/'src')
 lock=source/'Cargo.lock'
 (work/'Cargo.lock').write_bytes(lock.read_bytes() if lock.exists() else files['Cargo.lock'])
 with model.open('rb') as f:
  digest=hashlib.sha256()
  for chunk in iter(lambda:f.read(1024*1024),b''):digest.update(chunk)
  require(digest.hexdigest()=='181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c','model mismatch')
  f.seek(16384);metadata_prefix=f.read(28)
  # LlmMetadata.start_token=TokenIds[2], then stop_tokens=[1],[50],[106].
  require(metadata_prefix.hex()=='0a050a030a010212050a030a010112050a030a013212050a030a016a','stop-token metadata changed')
  f.seek(32768);tokenizer=f.read(4689013)
 require(sha(tokenizer)==TOKENIZER_SHA,'tokenizer mismatch')
 processor=spm.SentencePieceProcessor(model_proto=tokenizer)
 require(processor.get_piece_size()==262144,'vocabulary mismatch')
 assets=out/'assets';assets.mkdir();(assets/'tokenizer.bin').write_bytes(tokenizer)
 schema=(ROOT/'planning/fixtures/constrained-decoding-v2/keyword-sentinel.schema.json').read_bytes()
 require(sha(schema)==SCHEMA_SHA,'schema mismatch');(assets/'schema.json').write_bytes(schema)
 for mode in ['raw','decoded']:
  pieces=[(processor.id_to_piece(i) if mode=='raw' else processor.decode([i])).encode('utf-8') for i in range(262144)]
  (assets/(mode+'.vocab')).write_bytes(struct.pack('<262144I',*(len(p) for p in pieces))+b''.join(pieces))
 # Exact exposed text prefix is synthetic diagnostic input, not the unavailable sampled-token history.
 raw_paths=['p2-constrained-decoding-gate-qHeLdZ','p2-constrained-decoding-gate-VFYDVv']
 hashes=['3d96e874505dc7fca68472a3003b61fbbebcc95a09cf14518902b5602054a67b','fc67a0c0c860736c338a09c90e64e4085ed75dc38a9ca5122efba5f4bd96eb79']
 prefixes=[]
 for directory,digest in zip(raw_paths,hashes):
  raw=(ROOT/'benchmarks/results'/directory/'cd01-model-output.raw.txt').read_bytes()
  require(sha(raw)==digest,'saved raw changed');prefixes.append(raw.rstrip(b'\n'))
 require(prefixes[0]==prefixes[1] and prefixes[0].endswith(b'"b":'),'prefix mismatch')
 (assets/'prefix.txt').write_bytes(prefixes[0])
 manifest={'schema':1,'scope':'synthetic-pinned-source-mask-replay','nativeGemmaExecution':False,'eos':1,
  'stopTokenIds':[[1],[50],[106]],'eosPolicy':'first single-token stop, pinned provider factory',
  'metadataPrefixHex':metadata_prefix.hex(),'vocabEntries':262144,'rawOutputHashes':hashes,
  'patchOrder':list(n for n in PINS if n.startswith('PATCH.')),
  'prefixTokenHistory':'canonical SentencePiece reconstruction; observed IDs unavailable',
  'files':{str(p.relative_to(out)):sha(p.read_bytes()) for base in [cache,work,assets] for p in sorted(base.rglob('*')) if p.is_file()}}
 (out/'preparation.json').write_text(json.dumps(manifest,indent=2)+'\n')
 return manifest
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,required=True);p.add_argument('--model',type=Path,default=ROOT/'models/gemma-4-E2B-it.litertlm')
 a=p.parse_args();print(json.dumps(prepare(a.out.resolve(),a.model)['files'],indent=2))
