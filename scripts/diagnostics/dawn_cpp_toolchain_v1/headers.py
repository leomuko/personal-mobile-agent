"""Bounded selective copying from an already fully qualified immutable archive.

This does not replace complete source qualification. All 26 original header bytes
must match its retained manifest; no header projection, wrapper or repair occurs.
"""
import hashlib
import os
from pathlib import Path
import resource
import tarfile
import time


def require(ok,message):
 if not ok:raise ValueError(message)


def copy_headers(archive,manifest,output,deadline,clock=lambda:time.clock_gettime(time.CLOCK_MONOTONIC)):
 archive=Path(archive);output=Path(output);names=manifest['headers']
 require(0<len(names)<=128 and manifest['regularBytes']<=2*1024**2,'checked-in header count/byte ceiling')
 require(not output.exists(),'preserve existing header root')
 require(archive.is_file() and not archive.is_symlink() and archive.stat().st_size<=512*1024**2,'qualified transport type/size')
 def check():
  require(clock()<deadline,'header preparation deadline')
  require(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss<=512*1024**2,'header reader RSS ceiling')
 def digest():
  h=hashlib.sha256()
  with archive.open('rb') as source:
   for block in iter(lambda:source.read(1024**2),b''):check();h.update(block)
  return h.hexdigest()
 for name,row in names.items():
  require(name.startswith('include/') and '\\' not in name and all(part not in ('','..','.') for part in name.split('/')),'unsafe declared include path')
  require(0<row['bytes']<=1024**2,'header payload ceiling')
 require(digest()==manifest['sourceSha256'],'qualified archive changed')
 output.mkdir(mode=0o700,parents=True);found={};seen=0;root=None;written=0
 # The immutable transport has prior complete validation. We copy selected bytes
 # only and intentionally do not assert a fresh whole-archive EOF/CRC proof here.
 with tarfile.open(archive,'r|gz') as source:
  for member in source:
   check();seen+=1;require(seen<=105000 and len(member.name.encode())<=2048,'selected-copy parser ceiling')
   parts=member.name.split('/',1)
   if root is None:root=parts[0]
   require(parts[0]==root,'archive root drift')
   if len(parts)==2 and parts[1] in names:
    name=parts[1];require(name not in found and member.isfile() and member.size==names[name]['bytes'],'header type/size/duplicate changed')
    stream=source.extractfile(member);require(stream is not None,'missing header payload')
    with stream:payload=stream.read(1024**2+1)
    require(len(payload)==member.size and hashlib.sha256(payload).hexdigest()==names[name]['sha256'],'header content changed')
    target=output/Path(name).relative_to('include');target.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
    with os.fdopen(os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600),'wb') as sink:sink.write(payload)
    written+=len(payload);require(written<=manifest['regularBytes'],'header write ceiling');found[name]=dict(bytes=len(payload),sha256=hashlib.sha256(payload).hexdigest())
   source.members.clear()
   if len(found)==len(names):break
 require(set(found)==set(names) and written==manifest['regularBytes'],'incomplete original header set')
 require(digest()==manifest['sourceSha256'],'transport changed during header copy');check()
 return dict(protocol='qualified-dawn-header-copy-v1',sourceSha256=manifest['sourceSha256'],headers=found,regularBytes=written,tarMembersVisited=seen,sourceExecuted=False,completeArchiveProofReused=True,newWholeGzipEOFProof=False)
