"""Offline owner-pinned public-key verifier, NOT a remote ChatGPT transport."""
import argparse
import base64
import hashlib
import json
import os
import re
import secrets
import sqlite3
import subprocess
import tempfile
import time
import uuid
from contextlib import contextmanager
from pathlib import Path

SCHEMA="EXECUTION_BRIDGE_SIGNED_RESPONSE_V2"
OPS={"GET_BATTERY_STATUS","GET_STORAGE_STATUS","GET_NETWORK_STATUS","GET_BRIDGE_VERSION"}
FIELDS={"schema","request_id","key_id","operation","issued_at_ms","expires_at_ms","nonce","captured_at_ms","bridge_version","transport","value","signature"}
PREFIX=bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d03010703420004")
def require(ok):
    if not ok: raise ValueError("invalid, expired, untrusted, revoked or replayed evidence")
def now_ms(): return time.time_ns()//1_000_000
def unique(pairs):
    out={}
    for k,v in pairs:
        require(k not in out)
        out[k]=v
    return out
def load(path):
    with open(path,"rb") as f:
        raw=f.read(8193)
    require(len(raw)<=8192)
    d=json.loads(raw,object_pairs_hook=unique)
    require(type(d) is dict)
    return d
def b64(s,max_len):
    require(type(s) is str and len(s)<=max_len)
    result=base64.b64decode(s,validate=True)
    require(base64.b64encode(result).decode()==s)
    return result
def key_bytes(doc):
    require(set(doc)=={"schema","key_id","public_key_der","attestation"})
    require(doc["schema"]=="EXECUTION_BRIDGE_PUBLIC_KEY_V2" and doc["attestation"]=="NOT_VERIFIED")
    der=b64(doc["public_key_der"],200)
    require(len(der)==91 and der.startswith(PREFIX))
    require(hashlib.sha256(der).hexdigest()==doc["key_id"])
    with tempfile.TemporaryDirectory() as d:
        p=Path(d)/"pub.der";p.write_bytes(der)
        result=subprocess.run(["openssl","pkey","-pubin","-inform","DER","-in",str(p),"-pubcheck","-noout"],capture_output=True,timeout=5)
        require(result.returncode==0)
    return der
def canonical(r):
    require(set(r)==FIELDS and r["schema"]==SCHEMA and r["transport"]=="OWNER_MEDIATED")
    for k in ("request_id","key_id","operation","nonce","bridge_version","value"):
        require(type(r[k]) is str)
    require(str(uuid.UUID(r["request_id"]))==r["request_id"])
    require(re.fullmatch("[0-9a-f]{64}",r["key_id"]) and re.fullmatch("[0-9a-f]{32}",r["nonce"]))
    require(r["operation"] in OPS and re.fullmatch("[A-Za-z0-9._-]{1,40}",r["bridge_version"]))
    for k in ("issued_at_ms","expires_at_ms","captured_at_ms"):
        require(type(r[k]) is int and 10**12<=r[k]<10**13)
    require(0<r["expires_at_ms"]-r["issued_at_ms"]<=1800000 and r["issued_at_ms"]<=r["captured_at_ms"]<=r["expires_at_ms"])
    v=r["value"]
    if r["operation"]=="GET_BATTERY_STATUS":
        require(re.fullmatch("0|[1-9][0-9]{0,2}",v) and int(v)<=100)
    elif r["operation"]=="GET_STORAGE_STATUS":
        require(re.fullmatch("0|[1-9][0-9]{0,15}",v) and int(v)<=2**50)
    elif r["operation"]=="GET_NETWORK_STATUS": require(v in ("VALIDATED","NOT_VALIDATED"))
    else: require(v==r["bridge_version"])
    return "\n".join(str(r[k]) for k in ("schema","request_id","key_id","operation","issued_at_ms","expires_at_ms","nonce","captured_at_ms","bridge_version","transport","value")).encode("ascii")
def verify_signature(der,r):
    sig=b64(r["signature"],128)
    require(8<=len(sig)<=72)
    with tempfile.TemporaryDirectory() as d:
        p=Path(d)
        (p/"key.der").write_bytes(der);(p/"signature").write_bytes(sig);(p/"message").write_bytes(canonical(r))
        result=subprocess.run(["openssl","dgst","-sha256","-keyform","DER","-verify",str(p/"key.der"),"-signature",str(p/"signature"),str(p/"message")],capture_output=True,timeout=5)
        require(result.returncode==0)

class Ledger:
    def __init__(self,path,clock=now_ms):
        self.path=path;self.clock=clock
        with self.tx() as c:
            c.execute("CREATE TABLE IF NOT EXISTS keys(id TEXT PRIMARY KEY, der BLOB NOT NULL, revoked INTEGER NOT NULL DEFAULT 0)")
            c.execute("CREATE TABLE IF NOT EXISTS requests(id TEXT PRIMARY KEY, key_id TEXT NOT NULL REFERENCES keys(id), operation TEXT NOT NULL, issued INTEGER NOT NULL, expires INTEGER NOT NULL, nonce TEXT NOT NULL UNIQUE, used INTEGER NOT NULL DEFAULT 0)")
    @contextmanager
    def tx(self):
        c=sqlite3.connect(self.path,timeout=5)
        c.row_factory=sqlite3.Row
        try:
            c.execute("PRAGMA foreign_keys=ON")
            c.execute("BEGIN IMMEDIATE")
            yield c;c.commit()
        except BaseException:
            c.rollback();raise
        finally:c.close()
    def pin(self,doc,confirmed_fingerprint):
        der=key_bytes(doc);require(doc["key_id"]==confirmed_fingerprint)
        with self.tx() as c:
            row=c.execute("SELECT * FROM keys WHERE id=?",(confirmed_fingerprint,)).fetchone()
            if row:require(not row["revoked"] and row["der"]==der)
            else:c.execute("INSERT INTO keys VALUES(?,?,0)",(confirmed_fingerprint,der))
        return {"key_id":confirmed_fingerprint,"trust":"OWNER_PINNED","hardware_attestation":"NOT_VERIFIED"}
    def revoke(self,key_id):
        with self.tx() as c:
            require(c.execute("UPDATE keys SET revoked=1 WHERE id=?",(key_id,)).rowcount==1)
    def issue(self,key_id,op,ttl_ms=300000):
        require(op in OPS and type(ttl_ms) is int and 1000<=ttl_ms<=1800000)
        with self.tx() as c:
            key=c.execute("SELECT * FROM keys WHERE id=?",(key_id,)).fetchone();require(key and not key["revoked"])
            now=self.clock()
            require(type(now) is int and 10**12<=now<10**13-1800000)
            c.execute("DELETE FROM requests WHERE expires<?",(now-86400000,))
            require(c.execute("SELECT COUNT(*) FROM requests").fetchone()[0]<10000)
            q=(str(uuid.uuid4()),key_id,op,now,now+ttl_ms,secrets.token_hex(16))
            c.execute("INSERT INTO requests VALUES(?,?,?,?,?,?,0)",q)
        return "EB2:"+":".join(map(str,q))
    def verify(self,r):
        canonical(r)
        with self.tx() as c:
            q=c.execute("SELECT * FROM requests WHERE id=?",(r["request_id"],)).fetchone()
            require(q and not q["used"])
            key=c.execute("SELECT * FROM keys WHERE id=?",(q["key_id"],)).fetchone()
            require(key and not key["revoked"])
            require(tuple(r[k] for k in ("key_id","operation","issued_at_ms","expires_at_ms","nonce"))==tuple(q[k] for k in ("key_id","operation","issued","expires","nonce")))
            require(r["captured_at_ms"]<=self.clock()<=q["expires"])
            verify_signature(key["der"],r)
            require(c.execute("UPDATE requests SET used=1 WHERE id=? AND used=0",(q["id"],)).rowcount==1)
        return {"status":"SIGNATURE_AND_CORRELATION_VERIFIED","request_id":r["request_id"],"key_id":r["key_id"],"operation":r["operation"],"value":r["value"],"captured_at_ms":r["captured_at_ms"],"transport":"OWNER_MEDIATED","hardware_attestation":"NOT_VERIFIED","automatic_connection":"NOT_VERIFIED"}

def main():
    os.umask(0o077)
    p=argparse.ArgumentParser();p.add_argument("--db",required=True)
    s=p.add_subparsers(dest="command",required=True)
    pin=s.add_parser("pin");pin.add_argument("file");pin.add_argument("--confirm-fingerprint",required=True)
    issue=s.add_parser("issue");issue.add_argument("key_id");issue.add_argument("operation",choices=sorted(OPS));issue.add_argument("--ttl-ms",type=int,default=300000)
    verify=s.add_parser("verify");verify.add_argument("file")
    revoke=s.add_parser("revoke");revoke.add_argument("key_id")
    a=p.parse_args()
    try:
        db=Ledger(a.db)
        if a.command=="pin":result=db.pin(load(a.file),a.confirm_fingerprint)
        elif a.command=="issue":result={"code":db.issue(a.key_id,a.operation,a.ttl_ms)}
        elif a.command=="verify":result=db.verify(load(a.file))
        else:db.revoke(a.key_id);result={"status":"REVOKED"}
        print(json.dumps(result))
    except (ValueError,TypeError,KeyError,OSError,sqlite3.Error,subprocess.SubprocessError):
        print(json.dumps({"status":"REJECTED","reason":"invalid input, untrusted/expired/replayed evidence or verification unavailable"}))
        raise SystemExit(1)
if __name__=="__main__":main()
