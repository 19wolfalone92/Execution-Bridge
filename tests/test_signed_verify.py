import base64,hashlib,subprocess,tempfile,unittest
from pathlib import Path
from signed_verify import Ledger,canonical,load

class SignedTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.path=Path(self.temp.name)
        self.now=1795536000000
        self.db=Ledger(self.path/"ledger.db",lambda:self.now)
        subprocess.run(["openssl","genpkey","-algorithm","EC","-pkeyopt","ec_paramgen_curve:P-256","-out",str(self.path/"key.pem")],check=True,capture_output=True)
        self.pub=subprocess.check_output(["openssl","pkey","-in",str(self.path/"key.pem"),"-pubout","-outform","DER"])
        self.fingerprint=hashlib.sha256(self.pub).hexdigest()
        self.doc={"schema":"EXECUTION_BRIDGE_PUBLIC_KEY_V2","key_id":self.fingerprint,"public_key_der":base64.b64encode(self.pub).decode(),"attestation":"NOT_VERIFIED"}
        self.db.pin(self.doc,self.fingerprint)
    def tearDown(self):self.temp.cleanup()
    def response(self,op="GET_BATTERY_STATUS",value="44"):
        _,rid,key,op,issued,expires,nonce=self.db.issue(self.fingerprint,op).split(":")
        r={"schema":"EXECUTION_BRIDGE_SIGNED_RESPONSE_V2","request_id":rid,"key_id":key,"operation":op,"issued_at_ms":int(issued),"expires_at_ms":int(expires),"nonce":nonce,"captured_at_ms":self.now,"bridge_version":"0.2.4-dev","transport":"OWNER_MEDIATED","value":value,"signature":""}
        sig=subprocess.check_output(["openssl","dgst","-sha256","-sign",str(self.path/"key.pem")],input=canonical(r))
        r["signature"]=base64.b64encode(sig).decode()
        return r
    def test_valid_four_operations(self):
        for op,v in [("GET_BATTERY_STATUS","44"),("GET_STORAGE_STATUS","123456"),("GET_NETWORK_STATUS","VALIDATED"),("GET_BRIDGE_VERSION","0.2.4-dev")]:
            result=self.db.verify(self.response(op,v))
            self.assertEqual(result["value"],v)
            self.assertEqual(result["automatic_connection"],"NOT_VERIFIED")
    def test_replay_persists_across_restart(self):
        r=self.response();self.db.verify(r)
        with self.assertRaises(ValueError):Ledger(self.db.path,lambda:self.now).verify(r)
    def test_tampering_does_not_consume(self):
        r=self.response()
        with self.assertRaises(ValueError):self.db.verify({**r,"value":"45"})
        self.db.verify(r)
    def test_wrong_nonce(self):
        r=self.response()
        with self.assertRaises(ValueError):self.db.verify({**r,"nonce":"f"*32})
    def test_unknown_request(self):
        r=self.response()
        with self.assertRaises(ValueError):self.db.verify({**r,"request_id":"e8b51510-1271-4460-9b8e-6622029f29e3"})
    def test_expiry(self):
        r=self.response();self.now+=300001
        with self.assertRaises(ValueError):self.db.verify(r)
    def test_revoked(self):
        r=self.response();self.db.revoke(self.fingerprint)
        with self.assertRaises(ValueError):self.db.verify(r)
        with self.assertRaises(ValueError):self.db.pin(self.doc,self.fingerprint)
    def test_pin_requires_confirmation(self):
        with self.assertRaises(ValueError):self.db.pin(self.doc,"f"*64)
    def test_prohibited_operation(self):
        with self.assertRaises(ValueError):self.db.issue(self.fingerprint,"SHELL")
    def test_duplicate_json_key(self):
        p=self.path/"bad.json";p.write_text('{"a":1,"a":2}')
        with self.assertRaises(ValueError):load(p)
    def test_forged_signature(self):
        r=self.response()
        with self.assertRaises(ValueError):self.db.verify({**r,"signature":base64.b64encode(b"fake"*12).decode()})
    def test_strict_types(self):
        r=self.response()
        with self.assertRaises(ValueError):self.db.verify({**r,"captured_at_ms":str(self.now)})
