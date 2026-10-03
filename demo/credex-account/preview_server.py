"""Local preview server with a whitelist-only, credential-free ADB display query."""
import argparse
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
import json
import shutil
import re
import subprocess
from build import FIELDS

ROOT = Path(__file__).resolve().parent
ADB = 'adb'
SERIAL = None

def target_args():
    if SERIAL: return ['-s', SERIAL]
    result = subprocess.run([ADB, 'devices'], capture_output=True, text=True, timeout=10)
    phones = [line.split()[0] for line in result.stdout.splitlines()[1:]
              if len(line.split()) >= 2 and line.split()[1] == 'device' and not line.startswith('emulator-')]
    if len(phones) != 1: raise RuntimeError('请连接一部手机，或使用 --serial 指定 ADB 设备')
    return ['-s', phones[0]]

def query():
    result = subprocess.run([ADB,*target_args(),'shell','content','query','--uri',
        'content://com.nickwoluff.credex/quota/assistant'], capture_output=True, text=True,
        encoding='utf-8', errors='replace', timeout=20, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    if result.returncode or not result.stdout.startswith('Row: 0 '):
        raise RuntimeError('手机或 Credex 展示接口暂不可用')
    # Parse column delimiters, not arbitrary commas in status/service text. Return whitelist only.
    pairs = re.findall(r'(?:Row: 0 |, )([a-z][a-z0-9_]*)=(.*?)(?=, [a-z][a-z0-9_]*=|$)', result.stdout.strip())
    row = dict(pairs); payload = {'rows':1}
    for name,(column,kind) in FIELDS.items():
        val = row.get(column, '')
        payload[name] = int(val) if kind == 'int' and re.fullmatch(r'-?\d+',val) else val
    return payload

class Handler(SimpleHTTPRequestHandler):
    def __init__(self,*args,**kwargs): super().__init__(*args,directory=str(ROOT),**kwargs)
    def do_GET(self):
        if self.path.split('?')[0] == '/api/credex':
            try: data,status=query(),200
            except Exception as exc: data,status={'error':str(exc)},503
            raw=json.dumps(data,ensure_ascii=False).encode()
            self.send_response(status); self.send_header('Content-Type','application/json; charset=utf-8')
            self.send_header('Cache-Control','no-store'); self.end_headers(); self.wfile.write(raw)
        else: super().do_GET()
    def log_message(self,format,*args): pass

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--port',type=int,default=8767)
    p.add_argument('--adb',default=shutil.which('adb') or 'adb');p.add_argument('--serial');args=p.parse_args()
    ADB=args.adb;SERIAL=args.serial
    print(f'Preview: http://127.0.0.1:{args.port}/preview.html',flush=True)
    ThreadingHTTPServer(('127.0.0.1',args.port),Handler).serve_forever()
