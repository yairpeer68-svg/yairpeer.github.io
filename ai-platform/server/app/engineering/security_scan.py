import re
from app.engineering.workspace import Workspace
SECRET_PATTERNS={
 'private_key':re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
 'generic_api_key':re.compile(r'(?i)(api[_-]?key|secret|token)\s*[:=]\s*["\']?[A-Za-z0-9_\-]{20,}'),
 'aws_access_key':re.compile(r'AKIA[0-9A-Z]{16}'),
}
RISK_PATTERNS={
 'shell_true':re.compile(r'subprocess\.(?:run|Popen|call)\([^\n]{0,300}shell\s*=\s*True'),
 'eval_exec':re.compile(r'(?m)^\s*(?:eval|exec)\s*\('),
 'js_eval':re.compile(r'\beval\s*\('),
}

def scan_workspace(ws:Workspace)->dict:
    findings=[]; scanned=0
    for p in ws.files():
        if p.stat().st_size>ws.settings.ENGINEERING_MAX_FILE_BYTES: continue
        try: text=p.read_text(encoding='utf-8')
        except (UnicodeDecodeError,OSError): continue
        scanned+=1; rel=str(p.relative_to(ws.root)).replace('\\','/')
        for name,pattern in SECRET_PATTERNS.items():
            if pattern.search(text): findings.append({'severity':'high','rule':name,'path':rel})
        if p.suffix.lower() in {'.py','.js','.ts','.tsx','.dart','.java','.kt'}:
            for name,pattern in RISK_PATTERNS.items():
                if pattern.search(text): findings.append({'severity':'medium','rule':name,'path':rel})
    return {'scanned_files':scanned,'findings':findings,'high':sum(x['severity']=='high' for x in findings),'medium':sum(x['severity']=='medium' for x in findings)}
