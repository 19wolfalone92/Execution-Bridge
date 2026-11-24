"""Read-only, owner-initiated health snapshot gateway. No arbitrary commands."""
import argparse
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import ssl
import time
import uuid
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TTL = 300
TOKEN_TTL = 3600
MAX_BODY = 4096
FIELDS = {"model", "android", "bridge", "battery", "temperature_tenths_c",
          "available_ram_bytes", "free_disk_bytes", "power_save", "network_connected"}

class Reject(Exception):
    def __init__(self, status, code):
        self.status, self.code = status, code

def digest(token):
    return hashlib.sha256(token.encode("utf-8")).hexdigest()

def valid_uuid(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except (ValueError, TypeError, AttributeError):
        return False

def validate_health(data):
    if not isinstance(data, dict) or set(data) != FIELDS:
        raise Reject(400, "invalid_health_fields")
    for k in ("model", "android", "bridge"):
        if type(data[k]) is not str or not 1 <= len(data[k]) <= 80 or not all(c.isprintable() for c in data[k]):
            raise Reject(400, "invalid_health_text")
    for k, low, high in (("battery", 0, 100), ("temperature_tenths_c", -500, 1500),
                         ("available_ram_bytes", 0, 2**50), ("free_disk_bytes", 0, 2**50)):
        if data[k] is not None and (type(data[k]) is not int or not low <= data[k] <= high):
            raise Reject(400, "invalid_health_number")
    for k in ("power_save", "network_connected"):
        if type(data[k]) is not bool:
            raise Reject(400, "invalid_health_boolean")
    return data

class Store:
    def __init__(self, path, clock=time.time):
        self.path, self.clock = path, clock
        with self.tx() as c:
            c.execute("CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, device_hash TEXT NOT NULL, operator_hash TEXT NOT NULL, expires REAL NOT NULL, revoked INTEGER NOT NULL DEFAULT 0)")
            c.execute("CREATE TABLE IF NOT EXISTS operations(id TEXT PRIMARY KEY, device TEXT NOT NULL REFERENCES devices(id), request_id TEXT NOT NULL, created REAL NOT NULL, expires REAL NOT NULL, status TEXT NOT NULL, result TEXT, completed REAL, UNIQUE(device,request_id))")
    @contextmanager
    def tx(self):
        c = sqlite3.connect(self.path, timeout=5)
        c.row_factory = sqlite3.Row
        try:
            c.execute("PRAGMA foreign_keys=ON")
            c.execute("BEGIN IMMEDIATE")
            yield c
            c.commit()
        except BaseException:
            c.rollback()
            raise
        finally:
            c.close()
    def provision(self):
        did, dt, ot = str(uuid.uuid4()), secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        with self.tx() as c:
            c.execute("INSERT INTO devices VALUES(?,?,?,?,0)", (did,digest(dt),digest(ot),self.clock()+TOKEN_TTL))
        return {"device_id":did,"device_token":dt,"operator_token":ot,"expires_in":TOKEN_TTL}
    def revoke(self, did):
        with self.tx() as c:
            c.execute("UPDATE devices SET revoked=1 WHERE id=?", (did,))
            c.execute("UPDATE operations SET status='CANCELED',completed=? WHERE device=? AND status='PENDING'", (self.clock(),did))
    def _auth(self,c,did,token,role):
        if not valid_uuid(did) or not isinstance(token,str) or len(token)>128:
            raise Reject(401,"unauthorized")
        d=c.execute("SELECT * FROM devices WHERE id=?", (did,)).fetchone()
        if not d or d["revoked"] or d["expires"]<=self.clock() or not hmac.compare_digest(d[role+"_hash"],digest(token)):
            raise Reject(401,"unauthorized")
    def _expire(self,c,did):
        c.execute("UPDATE operations SET status='EXPIRED',completed=? WHERE device=? AND status='PENDING' AND expires<=?",(self.clock(),did,self.clock()))
    def _get(self,c,did,oid):
        row=c.execute("SELECT * FROM operations WHERE device=? AND id=?",(did,oid)).fetchone()
        if not row:
            raise Reject(404,"not_found")
        return self._view(row)
    @staticmethod
    def _view(row):
        return {"operation_id":row["id"],"type":"bridge.get_device_health","status":row["status"],
                "created_at":row["created"],"expires_at":row["expires"],"completed_at":row["completed"],
                "result":json.loads(row["result"]) if row["result"] else None}
    def enqueue(self,did,token,request_id):
        if not valid_uuid(request_id):
            raise Reject(400,"invalid_request_id")
        with self.tx() as c:
            self._auth(c,did,token,"operator")
            self._expire(c,did)
            row=c.execute("SELECT * FROM operations WHERE device=? AND request_id=?",(did,request_id)).fetchone()
            if row:
                return self._view(row)
            if c.execute("SELECT COUNT(*) FROM operations WHERE device=? AND status='PENDING'",(did,)).fetchone()[0]>=10:
                raise Reject(429,"queue_full")
            if c.execute("SELECT COUNT(*) FROM operations WHERE device=?",(did,)).fetchone()[0]>=1000:
                raise Reject(429,"history_limit")
            oid=str(uuid.uuid4())
            now=self.clock()
            c.execute("INSERT INTO operations VALUES(?,?,?,?,?,'PENDING',NULL,NULL)",(oid,did,request_id,now,now+TTL))
            return self._get(c,did,oid)
    def poll(self,did,token):
        with self.tx() as c:
            self._auth(c,did,token,"device")
            self._expire(c,did)
            row=c.execute("SELECT * FROM operations WHERE device=? AND status='PENDING' ORDER BY created,id LIMIT 1",(did,)).fetchone()
            return {"operation":self._view(row) if row else None}
    def finish(self,did,token,oid,health):
        health=validate_health(health)
        payload=json.dumps(health,sort_keys=True,separators=(",",":"))
        with self.tx() as c:
            self._auth(c,did,token,"device")
            self._expire(c,did)
            row=self._get(c,did,oid)
            if row["status"]=="SUCCEEDED" and row["result"]==health:
                return row
            if row["status"]!="PENDING":
                raise Reject(409,"not_pending")
            c.execute("UPDATE operations SET status='SUCCEEDED',result=?,completed=? WHERE id=?",(payload,self.clock(),oid))
            return self._get(c,did,oid)
    def status(self,did,token,oid):
        with self.tx() as c:
            self._auth(c,did,token,"operator")
            self._expire(c,did)
            return self._get(c,did,oid)

def handler(store):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(10)
        def log_message(self,*args):
            pass
        def do_POST(self):
            try:
                if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Type","").split(";")[0]!="application/json":
                    raise Reject(415,"json_required")
                length=int(self.headers.get("Content-Length","0"))
                if not 1<=length<=MAX_BODY:
                    raise Reject(413,"body_limit")
                body=json.loads(self.rfile.read(length))
                if not isinstance(body,dict):
                    raise Reject(400,"invalid_body")
                token=self.headers.get("Authorization","")
                if not token.startswith("Bearer "):
                    raise Reject(401,"unauthorized")
                token=token[7:]
                routes={"/v1/enqueue":({"device_id","request_id"},lambda:store.enqueue(body["device_id"],token,body["request_id"])),
                        "/v1/poll":({"device_id"},lambda:store.poll(body["device_id"],token)),
                        "/v1/finish":({"device_id","operation_id","health"},lambda:store.finish(body["device_id"],token,body["operation_id"],body["health"])),
                        "/v1/status":({"device_id","operation_id"},lambda:store.status(body["device_id"],token,body["operation_id"]))}
                if self.path not in routes:
                    raise Reject(404,"not_found")
                fields,fn=routes[self.path]
                if set(body)!=fields or any(not valid_uuid(body[k]) for k in fields-{"health"}):
                    raise Reject(400,"invalid_arguments")
                code,out=200,fn()
            except Reject as e:
                code,out=e.status,{"error":e.code}
            except (ValueError,UnicodeError,TypeError):
                code,out=400,{"error":"invalid_request"}
            except (OSError,sqlite3.Error):
                code,out=503,{"error":"temporary_failure"}
            data=json.dumps(out,allow_nan=False).encode()
            self.send_response(code)
            self.send_header("Content-Type","application/json")
            self.send_header("Cache-Control","no-store")
            self.send_header("Content-Length",str(len(data)))
            self.end_headers()
            self.wfile.write(data)
    return Handler

def main():
    os.umask(0o077)
    p=argparse.ArgumentParser()
    p.add_argument("--db",required=True)
    sub=p.add_subparsers(dest="action",required=True)
    sub.add_parser("provision")
    revoke=sub.add_parser("revoke")
    revoke.add_argument("device_id")
    serve=sub.add_parser("serve")
    serve.add_argument("--host",default="127.0.0.1")
    serve.add_argument("--port",type=int,default=8080)
    serve.add_argument("--cert")
    serve.add_argument("--key")
    a=p.parse_args()
    s=Store(a.db)
    if a.action=="provision":
        print(json.dumps(s.provision()))
    elif a.action=="revoke":
        s.revoke(a.device_id)
    else:
        if bool(a.cert)!=bool(a.key) or (a.host not in ("127.0.0.1","localhost") and not a.cert):
            p.error("non-loopback requires TLS certificate and key")
        with ThreadingHTTPServer((a.host,a.port),handler(s)) as server:
            if a.cert:
                ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                ctx.minimum_version=ssl.TLSVersion.TLSv1_2
                ctx.load_cert_chain(a.cert,a.key)
                server.socket=ctx.wrap_socket(server.socket,server_side=True)
            server.serve_forever()
if __name__=="__main__":
    main()
