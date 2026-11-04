"""Synthetic metadata only; none of these object bytes are executable probes."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
s=importlib.util.spec_from_file_location('dawn_macho_test',ROOT/'scripts/diagnostics/dawn_python_patch_v1/macho.py')
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)


def command(kind,text):
    start=24 if kind in (0xc,0xd) else 12
    size=(start+len(text.encode())+1+7)//8*8
    data=struct.pack('<III',kind,size,start)+b'\0'*(start-12)+text.encode()+b'\0'
    return data.ljust(size,b'\0')


def thin(cpu=0x0100000c,commands=None):
    commands=commands or [command(0xc,'/usr/lib/libSystem.B.dylib')]
    payload=b''.join(commands)
    return struct.pack('<IiiIIIII',0xfeedfacf,cpu,0,2,len(commands),len(payload),0,0)+payload


class MachoTests(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.p=Path(t.name)/'object'
    def read(self,data):self.p.write_bytes(data);return m.inspect(self.p)
    def test_arm64_dependencies_and_rpaths_are_parsed_from_bytes(self):
        r=self.read(thin(commands=[command(0xe,'/usr/lib/dyld'),command(0xc,'/usr/lib/libSystem.B.dylib'),command(0x8000001c,'@loader_path/lib')]))
        self.assertEqual(r['commandCount'],3)
        self.assertEqual(r['dependencies'],['/usr/lib/libSystem.B.dylib'])
        self.assertEqual(r['rpaths'],['@loader_path/lib'])
    def test_wrong_architecture_and_short_headers_rejected(self):
        for data in (thin(cpu=0x01000007),b'\xcf\xfa\xed\xfe'+b'\0'*28,b'not a native object'):
            with self.assertRaises(ValueError):self.read(data)
    def test_fat_arm64_selection_and_bounds(self):
        body=thin();header=struct.pack('>IIiiIII',0xcafebabe,1,0x0100000c,0,32,len(body),0)
        self.assertEqual(self.read(header.ljust(32,b'\0')+body)['sliceOffset'],32)
        with self.assertRaises(ValueError):self.read(header+body[:8])
    def test_ambiguous_arm64_slices_reject(self):
        body=thin();table=struct.pack('>II',0xcafebabe,2)
        table+=struct.pack('>iiIII',0x0100000c,0,48,len(body),0)
        table+=struct.pack('>iiIII',0x0100000c,2,48+len(body),len(body),0)
        with self.assertRaisesRegex(ValueError,'ambiguous'):self.read(table+body+body)
    def test_invalid_commands_offsets_and_environment_reject(self):
        variants=[struct.pack('<II',0xc,7),command(0x27,'DYLD_INSERT_LIBRARIES=x'),command(0xe,'/unknown/loader')]
        for payload in variants:
            with self.assertRaises(ValueError):self.read(thin(commands=[payload]))
        data=bytearray(thin());struct.pack_into('<I',data,32+8,4)
        with self.assertRaisesRegex(ValueError,'offset'):self.read(data)
    def test_library_identifier_is_not_dependency(self):
        data=bytearray(thin(commands=[command(0xd,'@rpath/example.dylib'),command(0xc,'/usr/lib/libSystem.B.dylib')]))
        struct.pack_into('<I',data,12,6)
        r=self.read(data);self.assertEqual(r['identifier'],'@rpath/example.dylib')
        self.assertEqual(r['dependencies'],['/usr/lib/libSystem.B.dylib'])


if __name__=='__main__':unittest.main()
