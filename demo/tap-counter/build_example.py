"""Build an unsigned, uninstalled learning sample using Python's standard library."""
from pathlib import Path
import hashlib
import io
import json
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "counter.js"


def zip_bytes(files):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            entry = zipfile.ZipInfo(name, date_time=(2026, 10, 2, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)
    return buffer.getvalue()


def main():
    source = SOURCE.read_text(encoding="utf-8")
    if "]]>" in source or "</script" in source.lower():
        raise ValueError("Source contains a wrapper delimiter; escape it before building.")
    manifest = '''<?xml version="1.0" encoding="utf-8"?>
<Widget version="2" frameRate="30" screenWidth="904" clearCanvas="true">
    <JsCanvas name="orynnx_learning_tap_counter_v1" x="0" y="0" w="#view_width" h="#view_height">
        <Script><![CDATA[
''' + source + '''
        ]]></Script>
    </JsCanvas>
</Widget>
'''
    # Only this browser shell uses normal DOM controls and query-string navigation.
    # The embedded counter.js is identical to the native JsCanvas script.
    preview = '''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>轻触计数器 · 本地学习示例</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#f1f5f0;color:#20362b;font-family:system-ui,sans-serif;padding:32px}
main{max-width:1040px;margin:auto}header{display:flex;justify-content:space-between;align-items:center;gap:24px;margin-bottom:24px}
h1{font-size:25px;margin:0 0 8px}p{line-height:1.7;margin:0;color:#536c5c;font-size:14px}
nav{display:flex;gap:8px;flex-wrap:wrap}a{display:block;padding:10px 16px;border:1px solid #b8cbbc;border-radius:12px;color:#254c34;text-decoration:none;font-size:14px;white-space:nowrap}
a[aria-current="page"]{background:#234e36;color:white;border-color:#234e36}.stage{padding:18px;background:#dde6dd;border-radius:30px;box-shadow:0 20px 50px #223d2214}
canvas{display:block;max-width:100%;height:auto;margin:auto;border-radius:20px;touch-action:none;background:#070d0b}
footer{margin-top:20px}footer p+p{margin-top:6px}@media(max-width:640px){body{padding:18px}header{align-items:flex-start;flex-direction:column}.stage{padding:8px;border-radius:22px}}
</style></head><body><main><header><div><h1>轻触计数器</h1><p>本地学习示例 · 右侧触控，左侧预留镜头区域</p></div>
<nav aria-label="预览尺寸"><a id="size904" href="?size=904">904 × 572</a><a id="size976" href="?size=976">976 × 596</a></nav></header>
<div class="stage"><canvas id="c" width="904" height="572" data-is-game="false" aria-label="轻触计数器。右侧大按钮加一，底部左按钮减一、右按钮归零。"></canvas></div>
<footer><p>点击 +1、−1 或归零；重新打开页面可检查本地保存。数值范围为 0–999999。</p><p>这是真实 Canvas 预览。原生宿主中的触控、存储与动态安装尚未经过设备测试。</p></footer></main>
<script>
const large = new URLSearchParams(window.location.search).get('size') === '976';
window.CANVAS_W = large ? 976 : 904;
window.CANVAS_H = large ? 596 : 572;
document.getElementById('c').width = window.CANVAS_W;
document.getElementById('c').height = window.CANVAS_H;
document.getElementById(large ? 'size976' : 'size904').setAttribute('aria-current', 'page');
</script><script>
''' + source + '''
</script></body></html>
'''
    standalone = '''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>轻触计数器 · 实验样例</title>
<style>html,body{margin:0;background:#070d0b;overflow:hidden}canvas{display:block;touch-action:none;max-width:100%;height:auto}</style>
</head><body><canvas id="c" width="904" height="572" data-is-game="false"></canvas><script>
''' + source + '''
</script></body></html>
'''
    description = '''<?xml version="1.0" encoding="utf-8"?>
<MIUI-Theme>
    <title>轻触计数器 - 实验样例</title>
    <designer>Local learning example</designer>
    <author>Local learning example</author>
    <version>0.1.0</version>
    <uiVersion>15</uiVersion>
    <description>原创本地实验样例；未签名，动态安装与设备运行未经测试。</description>
</MIUI-Theme>
'''
    ET.fromstring(manifest)
    ET.fromstring(description)
    (ROOT / "assets").mkdir(exist_ok=True)
    (ROOT / "manifest.xml").write_text(manifest, encoding="utf-8", newline="\n")
    (ROOT / "description.xml").write_text(description, encoding="utf-8", newline="\n")
    (ROOT / "preview.html").write_text(preview, encoding="utf-8", newline="\n")
    (ROOT / "assets" / "index.html").write_text(standalone, encoding="utf-8", newline="\n")
    rear_files = {"manifest.xml": manifest.encode(), "assets/index.html": standalone.encode()}
    rear_zip = zip_bytes(rear_files)
    (ROOT / "rearscreen").write_bytes(rear_zip)
    (ROOT / "rearscreen.zip").write_bytes(rear_zip)
    outer = zip_bytes({"description.xml": description.encode(), "rearscreen": rear_zip})
    (ROOT / "tap-counter-learning.zip").write_bytes(outer)
    report = {
        "kind": "local unsigned learning example",
        "device_tested": False,
        "source_sha256": hashlib.sha256(source.encode()).hexdigest(),
        "rearscreen_sha256": hashlib.sha256(rear_zip).hexdigest(),
        "outer_sha256": hashlib.sha256(outer).hexdigest(),
        "inner_entries": list(rear_files),
        "outer_entries": ["description.xml", "rearscreen"],
    }
    (ROOT / "build-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
