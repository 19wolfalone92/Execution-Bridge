"""Offline, read-only inspection of an owner-exported Williams diagnostics ZIP.
No extraction, no network, no raw log output, no claim of exchange freshness.
"""
import argparse
import hashlib
import json
import os
import re
import stat
import zipfile

MAX_FILE=128*1024*1024
MAX_EXPANDED=512*1024*1024
MAX_META=65536
REQUIRED={"archive_metadata.json","diagnostic_report.json","test_results.json",
          "diagnostic_chain_audit.json","diagnostic_chain_comparison.json",
          "futures_fill_stop_timing.json","signed_position_stop_join.json",
          "runtime_resource_diagnostics.json","futures_positions_signed_snapshot.json",
          "futures_exchange_stop_proofs.json","futures_intent_stop_lifecycle.json",
          "futures_exit_reconciliation.json","signal_decision_history.json",
          "observer_paper_full_history.ndjson","observer_paper_full_history_metadata.json",
          "stop_trail_decisions_full.json"}

def inspect_archive(path):
    with open(path,"rb") as f:
        before=os.fstat(f.fileno())
        if not stat.S_ISREG(before.st_mode) or not 0<before.st_size<=MAX_FILE:
            raise ValueError("archive size or file type rejected")
        sha=hashlib.sha256()
        for chunk in iter(lambda:f.read(65536),b""):
            sha.update(chunk)
        f.seek(0)
        with zipfile.ZipFile(f) as z:
            infos=z.infolist()
            names=[i.filename for i in infos]
            if len(infos)>256 or len(set(names))!=len(names) or not REQUIRED.issubset(names):
                raise ValueError("missing, duplicate or excess entries")
            if sum(i.file_size for i in infos)>MAX_EXPANDED:
                raise ValueError("expanded size limit")
            paper_rows=0
            actual_total=0
            for i in infos:
                if (not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}",i.filename) or i.filename in (".","..")
                    or i.is_dir() or i.flag_bits&1 or stat.S_ISLNK(i.external_attr>>16)
                    or i.compress_type not in (zipfile.ZIP_STORED,zipfile.ZIP_DEFLATED)):
                    raise ValueError("unsupported entry")
                n=0
                with z.open(i) as entry:
                    for chunk in iter(lambda:entry.read(65536),b""):
                        n+=len(chunk)
                        actual_total+=len(chunk)
                        if actual_total>MAX_EXPANDED or n>i.file_size:
                            raise ValueError("stream size limit")
                        if i.filename=="observer_paper_full_history.ndjson":
                            paper_rows+=chunk.count(b"\n")
                if n!=i.file_size:
                    raise ValueError("entry size mismatch")
            def meta(name):
                i=z.getinfo(name)
                if not 0<i.file_size<=MAX_META:
                    raise ValueError("metadata size")
                data=json.loads(z.read(i))
                if not isinstance(data,dict):
                    raise ValueError("invalid metadata")
                return data
            m=meta("archive_metadata.json")
            o=meta("observer_paper_full_history_metadata.json")
            stamp=m.get("report_at_ms")
            contract=m.get("diagnostic_contract_version")
            if (m.get("schema")!="WILLIAMS_ARCHIVE_META_V1" or type(stamp) is not int or stamp<=0
                or type(contract) is not int or contract<=0 or o.get("export_complete") is not True
                or type(o.get("record_count")) is not int or o["record_count"]!=paper_rows):
                raise ValueError("invalid archive metadata or incomplete history")
        after=os.fstat(f.fileno())
        if (before.st_size,before.st_mtime_ns,before.st_ctime_ns)!=(after.st_size,after.st_mtime_ns,after.st_ctime_ns):
            raise ValueError("archive changed during inspection")
        return {"archive_integrity":"VALIDATED","sha256":sha.hexdigest(),"report_at_ms":stamp,
                "diagnostic_contract_version":contract,"entry_count":len(infos),
                "paper_record_count":paper_rows,"source_attestation":"NOT_VERIFIED",
                "live_exchange_state":"NOT_VERIFIED","update_readiness":"BLOCKED"}

if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("archive")
    args=p.parse_args()
    try:
        print(json.dumps(inspect_archive(args.archive)))
    except (OSError,ValueError,zipfile.BadZipFile,RuntimeError,NotImplementedError):
        print(json.dumps({"archive_integrity":"REJECTED","update_readiness":"BLOCKED"}))
        raise SystemExit(1)
