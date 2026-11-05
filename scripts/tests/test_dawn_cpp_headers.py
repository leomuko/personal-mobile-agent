import hashlib,importlib.util,io,tarfile,tempfile,time,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
s=importlib.util.spec_from_file_location('cpp_headers',ROOT/'scripts/diagnostics/dawn_cpp_toolchain_v1/headers.py');h=importlib.util.module_from_spec(s);s.loader.exec_module(h)
class HeaderCopyTests(unittest.TestCase):
 def source(self,unsafe=False):
  t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);r=Path(t.name);p=r/'source.tar.gz';payload=b'#include <tuple>\n'
  with tarfile.open(p,'w:gz') as tar:
   name='include/../bad.h' if unsafe else 'include/a.h';i=tarfile.TarInfo('dawn-pinned/'+name);i.size=len(payload);tar.addfile(i,io.BytesIO(payload))
  return r,p,dict(headers={name:dict(bytes=len(payload),sha256=hashlib.sha256(payload).hexdigest())},regularBytes=len(payload),sourceSha256=hashlib.sha256(p.read_bytes()).hexdigest())
 def test_complete_original_bytes_copied_without_execution(self):
  r,p,m=self.source();v=h.copy_headers(p,m,r/'headers',100,lambda:0);self.assertEqual((r/'headers/a.h').read_bytes(),b'#include <tuple>\n');self.assertTrue(v['completeArchiveProofReused']);self.assertFalse(v['newWholeGzipEOFProof']);self.assertFalse(v['sourceExecuted'])
 def test_wrong_transport_or_header_hash_blocks(self):
  for field in ('source','header'):
   r,p,m=self.source()
   if field=='source':m['sourceSha256']='0'*64
   else:m['headers']['include/a.h']['sha256']='0'*64
   with self.subTest(field=field),self.assertRaises(ValueError):h.copy_headers(p,m,r/'headers',100,lambda:0)
 def test_missing_or_unsafe_header_rejected(self):
  r,p,m=self.source(True)
  with self.assertRaises(ValueError):h.copy_headers(p,m,r/'headers',100,lambda:0)
  r,p,m=self.source();m['headers']['include/missing.h']=m['headers']['include/a.h']
  with self.assertRaises(ValueError):h.copy_headers(p,m,r/'headers',100,lambda:0)
 def test_deadline_and_existing_root_preserve_evidence(self):
  r,p,m=self.source()
  with self.assertRaises(ValueError):h.copy_headers(p,m,r/'headers',1,lambda:1)
  (r/'headers').mkdir();(r/'headers/foreign').write_text('keep')
  with self.assertRaises(ValueError):h.copy_headers(p,m,r/'headers',100,lambda:0)
  self.assertEqual((r/'headers/foreign').read_text(),'keep')
if __name__=='__main__':unittest.main()
