"""Local MCP JSON-RPC stdio adapter; intentionally not a remote OAuth server."""
import json
import os
import sys
import urllib.parse
import urllib.request
import uuid

TOOLS=[
    {"name":"bridge_enqueue_health","description":"Queue one read-only health snapshot request. Owner must explicitly send it on Android. Return pending operation ID, not phone evidence.","inputSchema":{"type":"object","properties":{"request_id":{"type":"string","format":"uuid"}},"required":["request_id"],"additionalProperties":False}},
    {"name":"bridge_get_operation_status","description":"Read the actual stored result or pending/expired status.","inputSchema":{"type":"object","properties":{"operation_id":{"type":"string","format":"uuid"}},"required":["operation_id"],"additionalProperties":False},"annotations":{"readOnlyHint":True}}
]
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):
        return None

def call(name,args):
    routes={"bridge_enqueue_health":("enqueue","request_id"),"bridge_get_operation_status":("status","operation_id")}
    path,key=routes[name]
    if set(args)!={key} or str(uuid.UUID(args[key]))!=args[key]:
        raise ValueError("invalid arguments")
    base=os.environ["BRIDGE_URL"].rstrip("/")
    url=urllib.parse.urlsplit(base)
    if url.username or url.password or url.query or url.fragment or url.path or not url.hostname or (url.scheme!="https" and not (url.scheme=="http" and url.hostname=="127.0.0.1")):
        raise ValueError("trusted HTTPS or loopback only")
    body={"device_id":os.environ["BRIDGE_DEVICE_ID"],key:args[key]}
    req=urllib.request.Request(base+"/v1/"+path,data=json.dumps(body).encode(),headers={"Authorization":"Bearer "+os.environ["BRIDGE_OPERATOR_TOKEN"],"Content-Type":"application/json"})
    with urllib.request.build_opener(NoRedirect()).open(req,timeout=10) as res:
        return json.loads(res.read(16384))

def dispatch(msg):
    method=msg.get("method")
    if "id" not in msg:
        return None
    response={"jsonrpc":"2.0","id":msg["id"]}
    if method=="initialize":
        version=msg.get("params",{}).get("protocolVersion")
        response["result"]={"protocolVersion":version if version in ("2024-11-05","2025-03-26","2025-06-18") else "2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"execution-bridge-local","version":"0.1.0"}}
    elif method=="ping":
        response["result"]={}
    elif method=="tools/list":
        response["result"]={"tools":TOOLS}
    elif method=="tools/call":
        try:
            params=msg["params"]
            data=call(params["name"],params.get("arguments",{}))
            response["result"]={"content":[{"type":"text","text":json.dumps(data)}]}
        except Exception:
            response["result"]={"content":[{"type":"text","text":"Request rejected or gateway unavailable; no execution confirmed."}],"isError":True}
    else:
        response["error"]={"code":-32601,"message":"Method not found"}
    return response

if __name__=="__main__":
    for line in sys.stdin:
        try:
            result=dispatch(json.loads(line))
            if result is not None:
                print(json.dumps(result),flush=True)
        except Exception:
            print(json.dumps({"jsonrpc":"2.0","id":None,"error":{"code":-32600,"message":"Invalid request"}}),flush=True)
