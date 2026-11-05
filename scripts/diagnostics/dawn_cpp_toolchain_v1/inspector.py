"""Bounded structural -l cross-check against the actual pinned load-command bytes.

All printed command/section fields are checked. Unprinted section reserved3 is
not claimed as an independent inspector comparison (the transport is still pinned).
"""
from pathlib import Path
import re
import struct
import uuid

def require(ok,message):
 if not ok:raise ValueError(message)

NAMES={2:'LC_SYMTAB',11:'LC_DYSYMTAB',12:'LC_LOAD_DYLIB',13:'LC_ID_DYLIB',14:'LC_LOAD_DYLINKER',20:'LC_SUB_CLIENT',25:'LC_SEGMENT_64',27:'LC_UUID',29:'LC_CODE_SIGNATURE',38:'LC_FUNCTION_STARTS',41:'LC_DATA_IN_CODE',42:'LC_SOURCE_VERSION',50:'LC_BUILD_VERSION',0x8000001c:'LC_RPATH',0x80000028:'LC_MAIN',0x80000033:'LC_DYLD_EXPORTS_TRIE',0x80000034:'LC_DYLD_CHAINED_FIXUPS',0x80000022:'LC_DYLD_INFO_ONLY',0x22:'LC_DYLD_INFO'}

def version32(n):return [n>>16,(n>>8)&255,n&255]
def source_version(n):return [n>>40,(n>>30)&1023,(n>>20)&1023,(n>>10)&1023,n&1023]
def contract(path,raw):
 with Path(path).open('rb') as source:
  source.seek(raw['sliceOffset']);header=source.read(32);require(header[:4]==b'\xcf\xfa\xed\xfe','unexpected structural byte order')
  data=source.read(raw['commandBytes'])
 require(len(data)==raw['commandBytes']<=1024**2,'structural byte ceiling');out=[];pos=0
 for i in range(raw['commandCount']):
  cmd,size=struct.unpack_from('<II',data,pos);block=data[pos:pos+size];pos+=size;require(cmd in NAMES and len(block)==size,'unsupported/malformed structural command')
  fields=[('cmd','text',NAMES[cmd]),('cmdsize','number',size)]
  def nums(keys,fmt='I',offset=8):
   values=struct.unpack_from('<'+fmt*len(keys),block,offset)
   fields.extend((key,'number',n) for key,n in zip(keys,values))
  def string(key):
   off=struct.unpack_from('<I',block,8)[0];end=block.find(b'\0',off);require(8<=off<end<len(block),'invalid structural string');fields.append((key,'offset-string',[block[off:end].decode(),off]))
  if cmd==25:
   require(size>=72,'short segment');seg=block[8:24].split(b'\0',1)[0].decode();fields.append(('segname','text',seg));nums(['vmaddr','vmsize','fileoff','filesize'],'Q',24);nums(['maxprot','initprot','nsects','flags'],'I',56)
   count=struct.unpack_from('<I',block,64)[0];require(count<=4096 and size==72+80*count,'segment section size mismatch')
   for j in range(count):
    section=block[72+j*80:152+j*80];fields.append(('Section','marker',None));fields.extend([('sectname','text',section[:16].split(b'\0',1)[0].decode()),('segname','text',section[16:32].split(b'\0',1)[0].decode())])
    values=struct.unpack_from('<QQIIIIIIII',section,32)
    for key,n in zip(['addr','size','offset','align','reloff','nreloc','flags','reserved1','reserved2','reserved3'],values):fields.append((key,'optional-number' if key=='reserved3' else ('align' if key=='align' else 'number'),n))
  elif cmd==2:nums(['symoff','nsyms','stroff','strsize'])
  elif cmd==11:nums(['ilocalsym','nlocalsym','iextdefsym','nextdefsym','iundefsym','nundefsym','tocoff','ntoc','modtaboff','nmodtab','extrefsymoff','nextrefsyms','indirectsymoff','nindirectsyms','extreloff','nextrel','locreloff','nlocrel'])
  elif cmd in (12,13):
   string('name');timestamp,current,compat=struct.unpack_from('<III',block,12);fields.extend([('time stamp','timestamp',timestamp),('current version','version',version32(current)),('compatibility version','version',version32(compat))])
  elif cmd in (14,20,0x8000001c):string({14:'name',20:'client',0x8000001c:'path'}[cmd])
  elif cmd==27:fields.append(('uuid','text',str(uuid.UUID(bytes=block[8:24])).upper()))
  elif cmd==50:
   platform,minimum,sdk,count=struct.unpack_from('<IIII',block,8);require(count<=32 and size==24+count*8,'build-version size mismatch');fields.extend([('platform','number',platform),('minos','version',version32(minimum)),('sdk','version',version32(sdk)),('ntools','number',count)])
   for j in range(count):
    tool,version=struct.unpack_from('<II',block,24+8*j);fields.extend([('tool','number',tool),('version','version',version32(version))])
  elif cmd==42:fields.append(('version','version',source_version(struct.unpack_from('<Q',block,8)[0])))
  elif cmd==0x80000028:nums(['entryoff','stacksize'],'Q')
  elif cmd in (0x22,0x80000022):nums(['rebase_off','rebase_size','bind_off','bind_size','weak_bind_off','weak_bind_size','lazy_bind_off','lazy_bind_size','export_off','export_size'])
  else:nums(['dataoff','datasize'])
  out.append(dict(index=i,fields=fields))
 require(pos==len(data),'structural command extent mismatch');return out


def validate(text,path,expected):
 lines=[x.strip() for x in text.splitlines() if x.strip()]
 require(lines and re.fullmatch(re.escape(str(path))+r'(?: \(architecture arm64e?\))?:',lines.pop(0)),'inspector header mismatch')
 offset=0
 for block in expected:
  require(offset<len(lines) and lines[offset]=='Load command '+str(block['index']),'missing/duplicate command index');offset+=1
  for key,kind,value in block['fields']:
   if kind=='optional-number' and (offset>=len(lines) or not lines[offset].startswith(key+' ')):continue
   require(offset<len(lines),'truncated inspector structure');line=lines[offset];offset+=1
   if kind=='marker':require(line==key,'missing inspector section marker');continue
   match=re.fullmatch(re.escape(key)+r'(?:\s+(.*))?',line);require(match is not None,'unexpected/duplicate inspector field: '+key);observed=match.group(1) or ''
   if kind=='text':require(observed==value,'inspector text mismatch: '+key)
   elif kind=='offset-string':require(observed==value[0]+' (offset '+str(value[1])+')','inspector offset string mismatch')
   elif kind=='version':
    require(re.fullmatch(r'[0-9]+(?:\.[0-9]+){0,4}',observed) is not None,'inspector version format');parts=list(map(int,observed.split('.')));require(len(parts)<=len(value) and parts+[0]*(len(value)-len(parts))==value,'inspector version mismatch')
   elif kind=='align':require(observed=='2^'+str(value)+' ('+str(1<<value)+')','inspector alignment mismatch')
   else:
    pattern=r'(0x[0-9a-fA-F]+|[0-9]+)(?:\s+.*)?' if kind=='timestamp' else r'(0x[0-9a-fA-F]+|[0-9]+)(?:\s+\([^\r\n]*\))?'
    m=re.fullmatch(pattern,observed);require(m is not None,'inspector numeric format');n=int(m.group(1),16 if m.group(1).startswith('0x') else 10);require(n==value,'inspector numeric mismatch: '+key)
 require(offset==len(lines),'unparsed trailing inspector structure');return True
