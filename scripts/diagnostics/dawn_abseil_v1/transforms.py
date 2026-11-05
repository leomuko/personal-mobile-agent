"""Exact private root-patch and BUILD rewrite contracts; no vendor execution."""
import hashlib
import importlib.util
import os
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[3]
s=importlib.util.spec_from_file_location('abseil_transform_catalog',ROOT/'scripts/diagnostics/dawn_python_patch_v1/catalog.py')
catalog=importlib.util.module_from_spec(s);s.loader.exec_module(catalog)
require=catalog.require
PATCH_SHA='2d14482f889029bb6c3332ee925eb058e784ce35115b5e9222c43185c9d5dbc5'
TARGETS=('absl/base/BUILD.bazel','absl/crc/internal/crc32_x86_arm_combined_simd.h')
COMMAND="sed -i -e 's|@googletest|@com_google_googletest|g' absl/*/BUILD* absl/*/*/BUILD* absl/*/*/*/BUILD*"
OLD=b'@googletest';NEW=b'@com_google_googletest';BACKUP='-e';MIB=1024**2


def parse_patch(data,expected_sha=PATCH_SHA):
    require(hashlib.sha256(data).hexdigest()==expected_sha,'root patch SHA changed')
    lines=data.decode('utf-8',errors='strict').splitlines(keepends=True);hunks=[];index=0
    while index<len(lines):
        if lines[index]=='\n':index+=1;continue
        require(lines[index].startswith('--- '),'unsupported patch header')
        target=lines[index][4:].split('\t',1)[0].rstrip('\n');index+=1
        require(index<len(lines) and lines[index].startswith('+++ '),'missing patch destination')
        require(lines[index][4:].split('\t',1)[0].rstrip('\n')==target,'patch rename forbidden');index+=1
        require(index<len(lines),'missing patch hunk')
        match=re.fullmatch(r'@@ -([0-9]+),([0-9]+) \+([0-9]+),([0-9]+) @@\n',lines[index]);index+=1
        require(match is not None,'unsupported patch hunk header')
        old_line,old_count,new_line,new_count=map(int,match.groups());before=[];after=[]
        while len(before)<old_count or len(after)<new_count:
            require(index<len(lines) and lines[index][:1] in (' ','+','-'),'truncated/unsupported patch hunk')
            line=lines[index];index+=1
            if line[0] in ' -':before.append(line[1:])
            if line[0] in ' +':after.append(line[1:])
            require(len(before)<=old_count and len(after)<=new_count,'patch hunk count mismatch')
        hunks.append(dict(target=target,oldLine=old_line,newLine=new_line,oldCount=old_count,newCount=new_count,
                          before=''.join(before),after=''.join(after)))
    require(tuple(h['target'] for h in hunks)==TARGETS,'unexpected patch targets/hunks')
    return hunks


def bytes_for(root,name,row):
    require(row['kind']=='file' and row['bytes']<=8*MIB,'unsafe transformation target')
    p=Path(root)/name;require(catalog.file_row(p)['sha256']==row['sha256'],'transform input changed')
    with os.fdopen(os.open(p,os.O_RDONLY|os.O_NOFOLLOW),'rb') as source:data=source.read(8*MIB+1)
    require(len(data)==row['bytes'] and hashlib.sha256(data).hexdigest()==row['sha256'],'transform bytes changed')
    return data


def patch_plan(root,tree,hunks):
    changes=[]
    for h in hunks:
        name=h['target'];require(name in tree['files'],'patch target missing')
        data=bytes_for(root,name,tree['files'][name]);before=h['before'].encode();after=h['after'].encode()
        require(data.count(before)==1,'root patch context not unique/exact')
        offset=data.index(before);line=data[:offset].count(b'\n')+1
        require(line==h['oldLine'],'root patch line offset requires review')
        result=data[:offset]+after+data[offset+len(before):]
        require(len(result)<=8*MIB,'derived patch file ceiling')
        changes.append(dict(target=name,preimageSha256=hashlib.sha256(data).hexdigest(),postimageSha256=hashlib.sha256(result).hexdigest(),
                            beforeBytes=len(data),afterBytes=len(result),line=line,hunk=h))
    return changes


def apply_patch(root,tree,changes,budget,check):
    expected={name:dict(row) for name,row in tree['files'].items()}
    for change in changes:
        check();name=change['target'];data=bytes_for(root,name,tree['files'][name]);h=change['hunk']
        require(hashlib.sha256(data).hexdigest()==change['preimageSha256'],'patch plan preimage mismatch')
        result=data.replace(h['before'].encode(),h['after'].encode(),1)
        require(hashlib.sha256(result).hexdigest()==change['postimageSha256'],'patch plan postimage mismatch')
        budget.charge(len(result))
        with os.fdopen(os.open(Path(root)/name,os.O_RDWR|os.O_NOFOLLOW),'r+b') as out:
            require(hashlib.sha256(out.read(8*MIB+1)).hexdigest()==change['preimageSha256'],'patch input changed before write')
            out.seek(0);out.write(result);out.truncate();out.flush()
        expected[name].update(bytes=len(result),sha256=change['postimageSha256'])
    actual=catalog.tree(root,20000,256*MIB);require(actual['files']==expected,'unexpected patch effect/unchanged-file mismatch')
    return actual


def build_plan(root,tree):
    matches=[]
    for name,row in tree['files'].items():
        if row['kind']=='symlink' and name.startswith('absl/') and len(name.split('/'))<=4:
            raise ValueError('source link in BUILD glob ancestry requires review')
    for name,row in tree['files'].items():
        parts=name.split('/')
        if len(parts) not in (3,4,5) or parts[0]!='absl' or not parts[-1].startswith('BUILD'):continue
        require(not name.endswith(BACKUP),'existing sed backup is not a fresh source')
        for index in range(1,len(parts)):require(tree['files']['/'.join(parts[:index])]['kind']=='directory','BUILD glob traverses a link')
        before=bytes_for(root,name,row);after=before.replace(OLD,NEW)
        require(len(after)<=8*MIB,'derived BUILD file ceiling')
        require(name+BACKUP not in tree['files'],'sed backup collision')
        matches.append(dict(target=name,backup=name+BACKUP,preimageSha256=row['sha256'],postimageSha256=hashlib.sha256(after).hexdigest(),
                            beforeBytes=len(before),afterBytes=len(after),matches=before.count(OLD)))
    matches.sort(key=lambda row:row['target'])
    require(0<len(matches)<=2048,'BUILD rewrite target ceiling/empty scope')
    # Bash leaves unmatched patterns literal. This exact command may not be
    # rewritten to hide an absent depth; preparation must stop instead.
    require({len(row['target'].split('/')) for row in matches}=={3,4,5},'root BUILD glob depth is unmatched')
    return matches


def verify_rewrite(root,before,plan):
    expected={name:dict(row) for name,row in before['files'].items()}
    for row in plan:
        expected[row['backup']]=dict(expected[row['target']])
        expected[row['target']].update(bytes=row['afterBytes'],sha256=row['postimageSha256'])
    actual=catalog.tree(root,22048,384*MIB)
    require(actual['files']==expected,'BUILD rewrite/backup/unchanged-file mismatch')
    return actual
