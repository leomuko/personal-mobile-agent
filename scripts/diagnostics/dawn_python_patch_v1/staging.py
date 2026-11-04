"""Verified private materialization. Never downloads or executes candidate code."""
import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import stat
import zipfile

s=importlib.util.spec_from_file_location('dawn_staging_catalog',Path(__file__).with_name('catalog.py'))
catalog=importlib.util.module_from_spec(s);s.loader.exec_module(catalog)
require=catalog.require
MIB=1024**2


def put(path,data,mode=0o600):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    require(mode & ~0o777 == 0,'unsafe materialized permissions')
    with os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,mode),'wb') as sink:
        os.fchmod(sink.fileno(),mode);sink.write(data)


def verified_bytes(path,row,maximum=8*MIB):
    require(row['bytes']<=maximum,'materialization file ceiling')
    require(catalog.file_row(path)['sha256']==row['sha256'],'materialization input changed')
    with os.fdopen(os.open(path,os.O_RDONLY|os.O_NOFOLLOW),'rb') as source:data=source.read(maximum+1)
    require(len(data)==row['bytes'] and hashlib.sha256(data).hexdigest()==row['sha256'],'materialization byte mismatch')
    return data


def runtime(spec,destination):
    root=Path(spec['sourceRoot']);destination=Path(destination);destination.mkdir()
    require(catalog.tree(root,10000,128*MIB)['files']==spec['files'],'runtime source drift')
    changes={row['path']:row for row in spec['privateCopyTransformations']}
    for name,row in sorted(spec['files'].items(),key=lambda item:(len(Path(item[0]).parts),item[0])):
        p=destination/name;p.parent.mkdir(parents=True,exist_ok=True)
        if row['kind']=='directory':
            p.mkdir(exist_ok=True);os.chmod(p,row['mode'])
        elif row['kind']=='file':put(p,verified_bytes(root/name,row,128*MIB),row['mode'])
        elif row['kind']=='symlink':
            if name in changes:
                change=changes[name]
                if change['action']=='replace-with-private-empty-directory':
                    p.mkdir();os.chmod(p,0o755)
                elif change['action']=='relative-symlink':p.symlink_to(change['target'])
                else:raise ValueError('unsupported private-runtime transformation')
            else:
                require((root/name).resolve().is_relative_to(root.resolve()),'external runtime link')
                p.symlink_to(row['target'])
        else:raise ValueError('runtime member type')
    expected={name:dict(row) for name,row in spec['files'].items()}
    for name,change in changes.items():
        if change['action']=='replace-with-private-empty-directory':expected[name]={'kind':'directory','mode':0o755}
        else:expected[name]['target']=change['target']
    actual=catalog.tree(destination,10000,128*MIB)
    require(actual['files']==expected,'private runtime inventory mismatch')
    for name,row in actual['files'].items():
        if row['kind']=='symlink':require((destination/name).resolve().is_relative_to(destination.resolve()),'private runtime link escape')
    return actual


def packages(artifacts,receipts,destination):
    destination=Path(destination);destination.mkdir();names=set();folded=set();total=0;entries=0
    for path,receipt in zip(artifacts,receipts):
        require(catalog.file_row(path)['sha256']==receipt['artifact']['sha256'],'wheel changed before staging')
        entries+=receipt['entries'];total+=receipt['decodedBytes']
        require(entries<=512 and total<=16*MIB,'combined wheel ceiling')
        with zipfile.ZipFile(path) as z:
            for name,row in receipt['files'].items():
                require(name not in names and name.casefold() not in folded,'wheel namespace collision')
                names.add(name);folded.add(name.casefold())
                data=z.read(name)
                require(len(data)==row['bytes'] and hashlib.sha256(data).hexdigest()==row['sha256'],'wheel payload changed')
                put(destination/name,data)
    return catalog.tree(destination,1024,16*MIB)


def sources(root,rows,destination):
    destination=Path(destination);destination.mkdir()
    require(len(rows)==105 and sum(row['bytes'] for row in rows.values())<=8*MIB,'qualified source manifest changed')
    for name,row in rows.items():put(destination/name,verified_bytes(Path(root)/name,row))
    return catalog.tree(destination,1024,8*MIB)


def clone(root,destination,expected):
    require(catalog.tree(root,10000,128*MIB)==expected,'clone source drift')
    shutil.copytree(root,destination,symlinks=True)
    result=catalog.tree(destination,10000,128*MIB)
    require(result['files']==expected['files'] and result['regularBytes']==expected['regularBytes'],'clone mismatch')
    return result


def verify_tree(receipt):
    require(catalog.tree(receipt['root'],10000,128*MIB)==receipt,'private input drift')


def limits(root):
    # Bounded walk includes every attempt file, without following links.
    current=catalog.tree(root,25000,512*MIB)
    return current['regularBytes']
