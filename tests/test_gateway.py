import json
import os
import tempfile
import threading
import unittest
import urllib.request
import uuid
from unittest.mock import patch
from http.server import ThreadingHTTPServer
from gateway import Store, Reject, handler
import mcp_stdio

HEALTH={"model":"test phone","android":"15","bridge":"0.1.0-dev","battery":50,"temperature_tenths_c":300,"available_ram_bytes":100,"free_disk_bytes":200,"power_save":False,"network_connected":True}
class GatewayTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.now=1000
        self.s=Store(self.tmp.name+"/test.db",lambda:self.now)
        self.a=self.s.provision()
        self.did=self.a["device_id"]; self.dt=self.a["device_token"]; self.ot=self.a["operator_token"]
    def tearDown(self): self.tmp.cleanup()
    def enqueue(self): return self.s.enqueue(self.did,self.ot,str(uuid.uuid4()))
    def test_end_to_end_simulated(self):
        row=self.enqueue(); oid=row["operation_id"]
        self.assertEqual(self.s.poll(self.did,self.dt)["operation"]["operation_id"],oid)
        self.s.finish(self.did,self.dt,oid,HEALTH)
        self.assertEqual(self.s.status(self.did,self.ot,oid)["result"],HEALTH)
    def test_duplicate_and_restart(self):
        rid=str(uuid.uuid4())
        a=self.s.enqueue(self.did,self.ot,rid)
        s=Store(self.s.path,lambda:self.now)
        self.assertEqual(s.enqueue(self.did,self.ot,rid),a)
        oid=a["operation_id"]
        result=s.finish(self.did,self.dt,oid,HEALTH)
        self.assertEqual(s.finish(self.did,self.dt,oid,HEALTH),result)
        with self.assertRaises(Reject): s.finish(self.did,self.dt,oid,{**HEALTH,"battery":51})
    def test_wrong_role_and_cross_device(self):
        b=self.s.provision()
        with self.assertRaises(Reject): self.s.poll(self.did,self.ot)
        with self.assertRaises(Reject): self.s.enqueue(self.did,self.dt,str(uuid.uuid4()))
        with self.assertRaises(Reject): self.s.status(b["device_id"],b["operator_token"],self.enqueue()["operation_id"])
    def test_expiry(self):
        oid=self.enqueue()["operation_id"]; self.now+=301
        self.assertEqual(self.s.status(self.did,self.ot,oid)["status"],"EXPIRED")
        with self.assertRaises(Reject): self.s.finish(self.did,self.dt,oid,HEALTH)
        self.now+=3600
        with self.assertRaises(Reject): self.s.poll(self.did,self.dt)
    def test_revoke(self):
        oid=self.enqueue()["operation_id"]; self.s.revoke(self.did)
        with self.assertRaises(Reject): self.s.finish(self.did,self.dt,oid,HEALTH)
        with self.assertRaises(Reject): self.s.status(self.did,self.ot,oid)
    def test_schema_rejects(self):
        oid=self.enqueue()["operation_id"]
        for bad in ({**HEALTH,"secret":"bad"},{**HEALTH,"battery":True},{**HEALTH,"battery":101}):
            with self.assertRaises(Reject): self.s.finish(self.did,self.dt,oid,bad)
    def test_queue_bound(self):
        for _ in range(10): self.enqueue()
        with self.assertRaises(Reject): self.enqueue()
    def test_http_mcp_simulation(self):
        with ThreadingHTTPServer(("127.0.0.1",0),handler(self.s)) as server:
            thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
            try:
                base="http://127.0.0.1:"+str(server.server_port)
                def device(path,body):
                    req=urllib.request.Request(base+"/v1/"+path,data=json.dumps({"device_id":self.did,**body}).encode(),headers={"Authorization":"Bearer "+self.dt,"Content-Type":"application/json"})
                    with urllib.request.urlopen(req,timeout=5) as response: return json.load(response)
                with patch.dict(os.environ,{"BRIDGE_URL":base,"BRIDGE_OPERATOR_TOKEN":self.ot,"BRIDGE_DEVICE_ID":self.did}):
                    self.assertEqual(len(mcp_stdio.dispatch({"id":1,"method":"tools/list"})["result"]["tools"]),2)
                    def rpc(name,args):
                        result=mcp_stdio.dispatch({"id":1,"method":"tools/call","params":{"name":name,"arguments":args}})["result"]
                        self.assertFalse(result.get("isError",False))
                        return json.loads(result["content"][0]["text"])
                    a=rpc("bridge_enqueue_health",{"request_id":str(uuid.uuid4())})
                    oid=device("poll",{})["operation"]["operation_id"]
                    self.assertEqual(oid,a["operation_id"])
                    device("finish",{"operation_id":oid,"health":HEALTH})
                    self.assertEqual(rpc("bridge_get_operation_status",{"operation_id":oid})["result"],HEALTH)
            finally:
                server.shutdown(); thread.join()
if __name__=="__main__": unittest.main()
