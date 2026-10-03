"""Build an original native MAML/JsCanvas quota application, not an APK."""
from pathlib import Path
import hashlib
import io
import json
import zipfile
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
FIELDS = {
    'five': ('five_hour_remaining', 'int'), 'fiveReset': ('five_hour_reset', 'string'),
    'week': ('weekly_remaining', 'int'), 'weekReset': ('weekly_reset', 'string'),
    'plan': ('plan', 'string'), 'status': ('status', 'string'), 'updated': ('updated_at', 'string'),
    'kind': ('selected_source_kind', 'string'), 'source': ('selected_source_name', 'string'),
    'health': ('selected_source_health', 'string'),
    'sourceId': ('selected_source_id', 'string'), 'balanceCount': ('balance_count', 'int'),
}
for slot in range(1, 4):
    for suffix, column in [('Name', 'name'), ('Value', 'value'), ('Status', 'status'),
                           ('Detail', 'detail'), ('Updated', 'updated_at')]:
        FIELDS[f'b{slot}{suffix}'] = (f'balance_{slot}_{column}', 'string')

def archive(files):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, data in files.items():
            info = zipfile.ZipInfo(name, (2026, 10, 3, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
    return buffer.getvalue()

def main():
    script = (ROOT / 'quota.js').read_text(encoding='utf-8')
    if ']]>' in script or '</script' in script.lower():
        raise ValueError('Unexpected script wrapper delimiter')
    variables = '\n'.join(f'<Variable name="cq.{name}" type="{kind}" column="{column}" row="0"/>'
                           for name, (column, kind) in FIELDS.items())
    hosts = '\n'.join(f'<HostVar name="cq.{name}" type="{"number" if kind == "int" else "string"}"/>'
                      for name, (_, kind) in FIELDS.items())
    columns = ','.join(column for column, _ in FIELDS.values())
    manifest = f'''<?xml version="1.0" encoding="utf-8"?>
<Widget version="2" screenWidth="904" frameRate="2" clearCanvas="true" useVariableUpdater="DateTime.Minute">
    <VariableBinders>
        <ContentProviderBinder name="credex_quota" uri="content://com.nickwoluff.credex/quota/assistant" columns="{columns}" countName="cq.rows">
            {variables}
        </ContentProviderBinder>
    </VariableBinders>
    <Var name="cq.five" expression="-1" const="true"/>
    <Var name="cq.week" expression="-1" const="true"/>
    <Var name="cq.rows" expression="0" const="true"/>
    <Var name="cq_scale" expression="min((#view_width-#view_width/3)/600,#view_height/572)"/>
    <Var name="cq_dx" expression="#view_width/3+((#view_width-#view_width/3)-600*#cq_scale)/2"/>
    <Var name="cq_dy" expression="(#view_height-572*#cq_scale)/2"/>
    <ExternalCommands><Trigger action="init,resume"><BinderCommand name="credex_quota" command="refresh"/></Trigger></ExternalCommands>
    <JsCanvas name="credex_account_dashboard_v1" x="0" y="0" w="#view_width" h="#view_height">
        <HostVar name="cq.rows" type="number"/>
        {hosts}
        <Script><![CDATA[
{script}
        ]]></Script>
    </JsCanvas>
    <!-- Native transparent touch target performs a real Provider requery; JS never simulates refresh success. -->
    <Button name="credex_refresh" x="#cq_dx+392*#cq_scale" y="#cq_dy+468*#cq_scale" w="184*#cq_scale" h="64*#cq_scale">
        <Pressed><Rectangle w="184*#cq_scale" h="64*#cq_scale" cornerRadius="22*#cq_scale" fillColor="#24FFFFFF"/></Pressed>
        <Triggers><Trigger action="up"><BinderCommand name="credex_quota" command="refresh"/></Trigger></Triggers>
    </Button>
</Widget>
'''
    ET.fromstring(manifest)
    description = '''<?xml version="1.0" encoding="utf-8"?>
<MIUI-Theme><title>Credex 账户速览</title><designer>Orynnx</designer><author>Orynnx</author>
<version>1.1.0</version><uiVersion>15</uiVersion><description>跟随 Credex 所选来源，展示 Codex 双窗口或其他服务的余额、额度、详情与状态。支持翻页、隐私模式和刷新。</description></MIUI-Theme>
'''
    ET.fromstring(description)
    icon = Image.new('RGB', (256, 256), '#020607')
    draw = ImageDraw.Draw(icon)
    draw.rounded_rectangle((14, 14, 242, 242), radius=58, fill='#14252B')
    draw.rounded_rectangle((54, 139, 90, 198), radius=12, fill='#ADF0D1')
    draw.rounded_rectangle((110, 97, 146, 198), radius=12, fill='#A8CEFF')
    draw.rounded_rectangle((166, 54, 202, 198), radius=12, fill='#ADF0D1')
    icon_buffer = io.BytesIO(); icon.save(icon_buffer, format='PNG')
    (ROOT / 'manifest.xml').write_text(manifest, encoding='utf-8', newline='\n')
    (ROOT / 'description.xml').write_text(description, encoding='utf-8', newline='\n')
    (ROOT / 'app_icon.png').write_bytes(icon_buffer.getvalue())
    inner = archive({'manifest.xml': manifest.encode('utf-8')})
    files = {'description.xml': description.encode('utf-8'), 'rearscreen': inner,
             'app/app_icon.png': icon_buffer.getvalue()}
    preview = ROOT / 'preview.png'
    if preview.is_file(): files['preview/preview.png'] = preview.read_bytes()
    deliver = archive(files)
    (ROOT / 'credex-account-rearscreen.mrc').write_bytes(inner)
    (ROOT / 'credex-account-rearapp-1.1.0.zip').write_bytes(deliver)
    report = {'version': '1.1.0', 'uri': 'content://com.nickwoluff.credex/quota/assistant',
              'fields': FIELDS, 'outer_entries': list(files), 'inner_entries': ['manifest.xml'],
              'sha256': hashlib.sha256(deliver).hexdigest(),
              'upstream': 'NickWoluff/Credex@3515708712f6836d2d35e557f0874a3de3d9cbdc',
              'native_refresh': True, 'camera_keepout': 'left third', 'no_credentials': True,
              'sources': ['codex', 'balance'], 'balance_values': 'verbatim; no inferred used/remaining',
              'selection': 'Credex Assistant selected source; Provider does not expose all accounts at once'}
    (ROOT / 'build-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))

if __name__ == '__main__': main()
