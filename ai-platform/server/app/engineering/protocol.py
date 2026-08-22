import json
import re
from dataclasses import dataclass, field
from typing import Any

@dataclass
class AgentOperation:
    op: str
    path: str
    content: str | None = None

@dataclass
class AgentEnvelope:
    summary: str = ""
    operations: list[AgentOperation] = field(default_factory=list)
    commands: list[list[str]] = field(default_factory=list)
    memory: list[dict[str, str]] = field(default_factory=list)
    notes: dict[str, Any] = field(default_factory=dict)


def _json_object(text: str) -> dict:
    text=text.strip()
    if text.startswith('```'):
        text=re.sub(r'^```(?:json)?\s*','',text); text=re.sub(r'\s*```$','',text)
    try: return json.loads(text)
    except json.JSONDecodeError:
        start=text.find('{'); end=text.rfind('}')
        if start<0 or end<=start:
            raise ValueError('AI response does not contain a JSON object') from None
        return json.loads(text[start:end+1])


def parse_envelope(text: str) -> AgentEnvelope:
    data=_json_object(text)
    ops=[]
    for raw in data.get('operations',[]):
        if not isinstance(raw,dict): continue
        op=str(raw.get('op','')); path=str(raw.get('path',''))
        if op not in {'write','mkdir'} or not path: continue
        content=raw.get('content')
        if content is not None and not isinstance(content,str): content=str(content)
        ops.append(AgentOperation(op,path,content))
    commands=[]
    for cmd in data.get('commands',[]):
        if isinstance(cmd,list) and cmd and all(isinstance(x,str) for x in cmd): commands.append(cmd)
    memory=[x for x in data.get('memory',[]) if isinstance(x,dict) and isinstance(x.get('key'),str) and isinstance(x.get('content'),str)]
    return AgentEnvelope(str(data.get('summary','')),ops,commands,memory,data.get('notes',{}) if isinstance(data.get('notes',{}),dict) else {})
