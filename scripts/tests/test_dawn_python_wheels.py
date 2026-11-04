"""Synthetic wheel checks; no real acquisition/import/package installation."""
import base64
import hashlib
import importlib.util
import io
from pathlib import Path
import stat
import tempfile
import unittest
import zipfile
import zlib
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
s=importlib.util.spec_from_file_location('dawn_wheel_checks',ROOT/'scripts/diagnostics/dawn_python_patch_v1/wheels.py')
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)


class DawnWheelTests(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.root=Path(t.name)
    def wheel(self,extras=None,bad_record=False,tag='py3-none-any',requires='MarkupSafe>=2.0',extra_declaration=''):
        dist='jinja2-3.1.6.dist-info'
        files={'jinja2/__init__.py':b'raise RuntimeError("must not execute")',
               dist+'/METADATA':('Metadata-Version: 2.4\nName: Jinja2\nVersion: 3.1.6\nRequires-Python: >=3.7\nRequires-Dist: '+requires+'\n'+extra_declaration+'\n').encode(),
               dist+'/WHEEL':('Wheel-Version: 1.0\nRoot-Is-Purelib: true\nTag: '+tag+'\n\n').encode()}
        files.update(extras or {})
        rows=[]
        for name,data in files.items():
            h=base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b'=').decode()
            if bad_record and name=='jinja2/__init__.py':h='0'*43
            rows.append(name+',sha256='+h+','+str(len(data)))
        rows.append(dist+'/RECORD,,');files[dist+'/RECORD']=('\n'.join(rows)+'\n').encode()
        p=self.root/'candidate.whl'
        with zipfile.ZipFile(p,'w',compression=zipfile.ZIP_DEFLATED) as z:
            for name,data in files.items():z.writestr(name,data)
        expected=dict(bytes=p.stat().st_size,sha256=m.digest_file(p),distribution='jinja2',package='Jinja2',version='3.1.6',
                      purelib='true',tag='py3-none-any',requiresPython='>=3.7',requiresDist=['MarkupSafe>=2.0'],providesExtra=[])
        return p,expected
    def test_complete_record_is_data_only(self):
        p,e=self.wheel();r=m.inspect(p,e)
        self.assertTrue(r['byteVerified']);self.assertTrue(r['recordVerified'])
        self.assertFalse(r['executed']);self.assertFalse(r['installed'])
        self.assertEqual(len(r['files']),4)
    def test_hash_or_size_failure_precedes_zip_inspection(self):
        for key,value in [('sha256','0'*64),('bytes',1)]:
            p,e=self.wheel();e[key]=value
            with patch.object(m.zipfile,'ZipFile') as unzip:
                with self.assertRaisesRegex(ValueError,'mismatch'):m.inspect(p,e)
            unzip.assert_not_called()
    def test_unsafe_paths_and_hooks_reject(self):
        for name in ('../escape','/tmp/a','a//b','a/./b','a\\b','a.pth','sitecustomize.py','x.data/y'):
            p,e=self.wheel({name:b'data'})
            with self.assertRaisesRegex(ValueError,'path|hook|scheme'):m.inspect(p,e)
    def test_case_fold_collision_rejects(self):
        p,e=self.wheel({'JINJA2/__init__.py':b'case collision'})
        with self.assertRaisesRegex(ValueError,'case-fold'):m.inspect(p,e)
    def test_symlink_member_rejects(self):
        p,e=self.wheel()
        with zipfile.ZipFile(p,'a') as z:
            h=zipfile.ZipInfo('link');h.create_system=3;h.external_attr=(stat.S_IFLNK|0o777)<<16;z.writestr(h,b'outside')
        e.update(bytes=p.stat().st_size,sha256=m.digest_file(p))
        with self.assertRaisesRegex(ValueError,'link/special'):m.inspect(p,e)
    def test_record_corruption_rejects(self):
        p,e=self.wheel(bad_record=True)
        with self.assertRaisesRegex(ValueError,'RECORD hash'):m.inspect(p,e)
    def test_wrong_tag_and_missing_dependency_reject(self):
        p,e=self.wheel(tag='cp313-cp313-manylinux_2_17_x86_64')
        with self.assertRaisesRegex(ValueError,'tag/ABI'):m.inspect(p,e)
        p,e=self.wheel(requires='UnknownPackage>=1')
        with self.assertRaisesRegex(ValueError,'dependency'):m.inspect(p,e)
    def test_inactive_extra_requires_exact_declaration(self):
        requires='MarkupSafe>=2.0\nRequires-Dist: Babel>=2.7; extra == "i18n"'
        p,e=self.wheel(requires=requires,extra_declaration='Provides-Extra: i18n\n')
        with self.assertRaisesRegex(ValueError,'dependency'):m.inspect(p,e)
        e['requiresDist']=['MarkupSafe>=2.0','Babel>=2.7; extra == "i18n"'];e['providesExtra']=['i18n']
        e['selectedExtras']=[]
        self.assertTrue(m.inspect(p,e)['recordVerified'])
        e['selectedExtras']=['i18n']
        with self.assertRaisesRegex(ValueError,'selected extras'):m.inspect(p,e)
        p,e=self.wheel(requires=requires.replace('2.7','3.0'),extra_declaration='Provides-Extra: i18n\n')
        e['requiresDist']=['MarkupSafe>=2.0','Babel>=2.7; extra == "i18n"'];e['providesExtra']=['i18n']
        with self.assertRaisesRegex(ValueError,'predicate'):m.inspect(p,e)
    def test_zip_crc_failure_rejects(self):
        p,e=self.wheel();data=bytearray(p.read_bytes())
        # Corrupt a compressed payload while repinning only the outer synthetic
        # fixture, so the ZIP/CRC gate must reject it independently.
        with zipfile.ZipFile(p) as z:
            h=z.getinfo('jinja2/__init__.py');offset=h.header_offset+30+len(h.filename)+len(h.extra)
        data[offset]^=0xff;p.write_bytes(data);e['sha256']=m.digest_file(p)
        with self.assertRaises((ValueError,zipfile.BadZipFile,zlib.error)):m.inspect(p,e)


if __name__=='__main__':unittest.main()
