import json
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from adapters.williams_archive import inspect_archive, REQUIRED

class WilliamsArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.path=Path(self.temp.name)/"archive.zip"
    def tearDown(self): self.temp.cleanup()
    def make(self,omit=None,extra=None,count=1):
        with zipfile.ZipFile(self.path,"w",compression=zipfile.ZIP_DEFLATED) as z:
            for name in REQUIRED:
                if name==omit: continue
                value=b"{}"
                if name=="archive_metadata.json":
                    value=json.dumps({"schema":"WILLIAMS_ARCHIVE_META_V1","report_at_ms":1234,"diagnostic_contract_version":1}).encode()
                if name=="observer_paper_full_history_metadata.json":
                    value=json.dumps({"export_complete":True,"record_count":count}).encode()
                if name=="observer_paper_full_history.ndjson":
                    value=b"{}\n"
                z.writestr(name,value)
            if extra:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore",UserWarning)
                    z.writestr(extra,b"{}")
    def test_valid_not_live(self):
        self.make()
        r=inspect_archive(self.path)
        self.assertEqual(r["archive_integrity"],"VALIDATED")
        self.assertEqual(r["update_readiness"],"BLOCKED")
        self.assertEqual(r["live_exchange_state"],"NOT_VERIFIED")
        self.assertEqual(len(r["sha256"]),64)
    def test_missing(self):
        self.make(omit="diagnostic_report.json")
        with self.assertRaises(ValueError): inspect_archive(self.path)
    def test_traversal(self):
        self.make(extra="../outside")
        with self.assertRaises(ValueError): inspect_archive(self.path)
    def test_duplicate(self):
        self.make(extra="diagnostic_report.json")
        with self.assertRaises(ValueError): inspect_archive(self.path)
    def test_incomplete(self):
        self.make(count=2)
        with self.assertRaises(ValueError): inspect_archive(self.path)
    def test_corrupt(self):
        self.path.write_bytes(b"invalid")
        with self.assertRaises(zipfile.BadZipFile): inspect_archive(self.path)
