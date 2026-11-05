"""Bounded synthetic source archives only; never executes source or downloads."""
import gzip,hashlib,importlib.util,io,json,os
from pathlib import Path
import tarfile,tempfile,unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
s=importlib.util.spec_from_file_location('test_abseil_archive',ROOT/'scripts/diagnostics/dawn_abseil_v1/archive.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)

class AbseilArchiveTests(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.root=Path(t.name);self.index=0
    def fixture(self,extras=(),required=True):
        self.index+=1;p=self.root/('source'+str(self.index)+'.tar.gz')
        rows=[(n,('synthetic '+n).encode(),tarfile.REGTYPE,'') for n in m.REQUIRED] if required else []
        rows+=list(extras);dirs={m.PREFIX}
        for name,data,kind,link in rows:
            for parent in Path(name).parents:
                if str(parent)!='.':dirs.add(m.PREFIX+'/'+str(parent))
        with tarfile.open(p,'w:gz',format=tarfile.USTAR_FORMAT) as out:
            for name in sorted(dirs,key=lambda x:(x.count('/'),x)):
                member=tarfile.TarInfo(name+'/');member.type=tarfile.DIRTYPE;member.mode=0o755;out.addfile(member)
            for name,data,kind,link in rows:
                member=tarfile.TarInfo(m.PREFIX+'/'+name);member.type=kind;member.linkname=link;member.mode=0o644;member.size=len(data)
                out.addfile(member,io.BytesIO(data))
        return p
    def scan(self,p,**kwargs):return m.scan(p,expected_sha=hashlib.sha256(p.read_bytes()).hexdigest(),**kwargs)
    def test_complete_inventory_and_materialization_do_not_execute_source(self):
        p=self.fixture([('test.py',b'raise RuntimeError("never execute")',tarfile.REGTYPE,'')]);r=self.scan(p)
        self.assertTrue(r['completeSourceValidated']);self.assertTrue(r['gzipEOFValidated']);self.assertFalse(r['sourceExecuted'])
        self.assertIn('test.py',r['entries']);dest=self.root/'staged'
        m.materialize(p,r,dest,m.primitives.Budget())
        self.assertEqual((dest/'test.py').read_bytes(),b'raise RuntimeError("never execute")')
        self.assertEqual((dest/'test.py').stat().st_mode & 0o777,0o600)
    def test_digest_matches_replay_and_changes_with_content(self):
        p=self.fixture();a=self.scan(p);self.assertEqual(a['inventorySha256'],self.scan(p)['inventorySha256'])
        other=self.fixture([('extra',b'changed',tarfile.REGTYPE,'')]);self.assertNotEqual(a['inventorySha256'],self.scan(other)['inventorySha256'])
    def test_wrong_sha_rejects_before_decode(self):
        with self.assertRaisesRegex(ValueError,'SHA'):m.scan(self.fixture(),expected_sha='0'*64)
    def test_corrupt_crc_and_truncated_gzip_reject_even_with_matching_transport_hash(self):
        p=self.fixture();data=p.read_bytes()
        for candidate in [data[:-4],data[:-8]+bytes([data[-8]^1])+data[-7:]]:
            p.write_bytes(candidate)
            with self.assertRaises((ValueError,OSError,EOFError)):self.scan(p)
    def test_corrupt_tar_header_and_missing_end_reject(self):
        p=self.fixture();data=gzip.decompress(p.read_bytes());mutated=bytearray(data);mutated[0]^=1
        p.write_bytes(gzip.compress(mutated))
        with self.assertRaisesRegex(ValueError,'checksum'):self.scan(p)
        p.write_bytes(gzip.compress(data[:512]))
        with self.assertRaisesRegex(ValueError,'truncated'):self.scan(p)
    def test_unsafe_duplicate_case_collision_and_hardlinks_reject(self):
        for name,kind,link in [('../outside',tarfile.REGTYPE,''),('LICENSE',tarfile.REGTYPE,''),('license',tarfile.REGTYPE,''),('hard',tarfile.LNKTYPE,m.PREFIX+'/LICENSE')]:
            with self.subTest(name=name):
                p=self.fixture([(name,b'',kind,link)])
                with self.assertRaises(ValueError):self.scan(p)
    def test_internal_links_are_validated_and_escaping_dangling_cycles_reject(self):
        p=self.fixture([('alias',b'',tarfile.SYMTYPE,'LICENSE')]);r=self.scan(p);dest=self.root/'linked'
        m.materialize(p,r,dest,m.primitives.Budget());self.assertEqual(os.readlink(dest/'alias'),'LICENSE')
        for link in ['/tmp/outside','../outside','missing','cycle']:
            p=self.fixture([('cycle',b'',tarfile.SYMTYPE,link)])
            with self.subTest(link=link),self.assertRaises(ValueError):self.scan(p)
    def test_missing_required_source_and_resource_limits_reject(self):
        with self.assertRaisesRegex(ValueError,'required'):self.scan(self.fixture(required=False))
        p=self.fixture()
        for key,value in [('decoded',511),('headers',1),('file',1),('names',1),('frames',1)]:
            with self.subTest(key=key),patch.dict(m.LIMITS,{key:value}),self.assertRaises(ValueError):self.scan(p)
    def test_interruption_and_write_budget_leave_no_acceptance(self):
        p=self.fixture();r=self.scan(p)
        with self.assertRaisesRegex(ValueError,'output ceiling'):m.materialize(p,r,self.root/'partial',m.primitives.Budget(1))
        with self.assertRaises(KeyboardInterrupt):self.scan(p,check=lambda:(_ for _ in ()).throw(KeyboardInterrupt()))
        self.assertFalse((self.root/'accepted-preparation.json').exists())
    def test_changed_archive_cannot_be_staged_from_old_receipt(self):
        p=self.fixture();r=self.scan(p);p.write_bytes(p.read_bytes()+b'changed')
        with self.assertRaisesRegex(ValueError,'SHA'):m.materialize(p,r,self.root/'changed',m.primitives.Budget())

    def test_pax_effective_paths_are_inventoried_and_unsafe_overrides_reject(self):
        def pax_field(key,value):
            body=(key+'='+value+'\n').encode();n=len(body)+2
            while n!=len(body)+len(str(n))+1:n=len(body)+len(str(n))+1
            return str(n).encode()+b' '+body
        good=pax_field('path',m.PREFIX+'/absl/base/renamed.h')
        p=self.fixture([('metadata',good,tarfile.XHDTYPE,''),('raw-name',b'content',tarfile.REGTYPE,'')])
        r=self.scan(p);self.assertIn('absl/base/renamed.h',r['entries']);self.assertEqual(r['types']['pax-local'],1)
        for data in [pax_field('path',m.PREFIX+'/../outside'),b'999 path=bad\n',pax_field('linkpath','LICENSE')]:
            p=self.fixture([('metadata',data,tarfile.XHDTYPE,''),('raw-name',b'content',tarfile.REGTYPE,'')])
            with self.subTest(data=data),self.assertRaises(ValueError):self.scan(p)
    def test_interruption_during_materialization_retains_partial_without_acceptance(self):
        p=self.fixture();r=self.scan(p);original=m.FileSink.feed
        def interrupt(sink,data):
            original(sink,data);raise KeyboardInterrupt()
        dest=self.root/'interrupted'
        with patch.object(m.FileSink,'feed',new=interrupt),self.assertRaises(KeyboardInterrupt):m.materialize(p,r,dest,m.primitives.Budget())
        self.assertGreater((dest/'LICENSE').stat().st_size,0)
        self.assertFalse((self.root/'accepted-preparation.json').exists())
    def test_seeded_payload_roundtrip_and_compression_invariance_keep_transport_pin_strict(self):
        import random,string
        rng=random.Random(20261105);rows=[]
        for index in range(32):
            name='absl/base/case_'+str(index)+'_'+''.join(rng.choice(string.ascii_letters+string.digits+'_') for _ in range(12))
            payload=bytes(rng.randrange(256) for _ in range(rng.randrange(0,257)))
            rows.append((name,payload,tarfile.REGTYPE,''))
        p=self.fixture(rows);compressed=p.read_bytes();first=self.scan(p)
        materialized=self.root/'roundtrip';m.materialize(p,first,materialized,m.primitives.Budget())
        for name,payload,kind,link in rows:self.assertEqual((materialized/name).read_bytes(),payload)
        alternate=self.root/'recompressed.gz';alternate.write_bytes(gzip.compress(gzip.decompress(compressed),mtime=0))
        self.assertNotEqual(hashlib.sha256(alternate.read_bytes()).hexdigest(),first['archiveSha256'])
        second=self.scan(alternate)
        self.assertEqual(first['entries'],second['entries']);self.assertEqual(first['inventorySha256'],second['inventorySha256'])
        with self.assertRaisesRegex(ValueError,'SHA'):m.scan(alternate,expected_sha=first['archiveSha256'])
