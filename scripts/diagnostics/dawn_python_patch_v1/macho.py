"""Bounded ARM64 Mach-O metadata decoder. Does not load or execute binaries."""
import hashlib
from pathlib import Path
import struct


def require(ok,message):
    if not ok:raise ValueError(message)


def inspect(path):
    path=Path(path);size=path.stat().st_size
    with path.open('rb') as source:
        prefix=source.read(8);require(len(prefix)==8,'short Mach-O header')
        offset=0;slice_size=size;magic=prefix[:4]
        fat={b'\xca\xfe\xba\xbe':('>',False),b'\xbe\xba\xfe\xca':('<',False),
             b'\xca\xfe\xba\xbf':('>',True),b'\xbf\xba\xfe\xca':('<',True)}
        if magic in fat:
            endian,wide=fat[magic];count=struct.unpack(endian+'I',prefix[4:])[0]
            require(0<count<=16,'fat slice ceiling')
            stride=32 if wide else 20;table=source.read(count*stride)
            require(len(table)==count*stride,'short fat table');regions=[];matches=[]
            for i in range(count):
                values=struct.unpack(endian+('iiQQII' if wide else 'iiIII'),table[i*stride:(i+1)*stride])
                cpu,subtype,off,length,align=values[:5]
                require(length>=32 and off>=8+count*stride and off+length<=size and align<=31 and off%(1<<align)==0,'invalid fat slice bounds')
                require(not any(off<end and off+length>start for start,end in regions),'overlapping fat slices')
                regions.append((off,off+length))
                if cpu==0x0100000c:matches.append((off,length))
            require(len(matches)==1,'missing/ambiguous ARM64 slice')
            offset,slice_size=matches[0]
        source.seek(offset);header=source.read(32)
        require(len(header)==32 and header[:4] in (b'\xcf\xfa\xed\xfe',b'\xfe\xed\xfa\xcf'),'unsupported native Mach-O width')
        endian='<' if header[:4]==b'\xcf\xfa\xed\xfe' else '>'
        _,cpu,subtype,filetype,ncmds,command_bytes,flags,reserved=struct.unpack(endian+'IiiIIIII',header)
        require(cpu==0x0100000c and subtype&0xffffff in (0,2),'wrong native architecture/subtype')
        require(filetype in (2,6,8) and reserved==0 and 0<ncmds<=4096 and
                ncmds*8<=command_bytes<=1024**2 and command_bytes<=slice_size-32,'invalid native command count/size/type')
        block=source.read(command_bytes);require(len(block)==command_bytes,'short native commands')
    deps=[];names=[];library_names=[];rpaths=[];identifier=None;position=0
    loads={0xc,0x80000018,0x8000001f,0x20,0x80000023}
    def string(command,minimum):
        require(len(command)>=minimum,'short load command')
        index=struct.unpack_from(endian+'I',command,8)[0]
        require(minimum<=index<len(command),'invalid load string offset')
        end=command.find(b'\0',index)
        require(end>index and end-index<=1024,'invalid/long native load string')
        value=command[index:end].decode('utf-8',errors='strict')
        require(all(32<=ord(c)<127 for c in value),'invalid native load string')
        return value
    for _ in range(ncmds):
        require(position+8<=len(block),'truncated load command')
        cmd,cmdsize=struct.unpack_from(endian+'II',block,position)
        require(cmdsize>=8 and cmdsize%8==0 and position+cmdsize<=len(block),'invalid load command bounds')
        command=block[position:position+cmdsize];position+=cmdsize
        if cmd in loads or cmd==0xd:
            value=string(command,24);names.append(value);library_names.append(value)
            if cmd==0xd:
                require(identifier is None,'duplicate native library ID');identifier=value
            else:deps.append(value)
        elif cmd==0x8000001c:rpaths.append(string(command,12))
        elif cmd==0xe:
            value=string(command,12);require(value=='/usr/lib/dyld','unknown native dynamic loader');names.append(value)
        elif cmd in (0x10,0x27):raise ValueError('unsupported dependency/environment load command')
    require(position==len(block),'native command-size mismatch')
    require(len(deps)<=256 and len(rpaths)<=32,'native dependency/rpath ceiling')
    return dict(cpu=cpu,subtype=subtype,filetype=filetype,commandCount=ncmds,
                commandBytes=command_bytes,sliceOffset=offset,sliceBytes=slice_size,
                dependencies=deps,rpaths=rpaths,identifier=identifier,loadNames=names,libraryNames=library_names,
                loadCommandSha256=hashlib.sha256(block).hexdigest())
