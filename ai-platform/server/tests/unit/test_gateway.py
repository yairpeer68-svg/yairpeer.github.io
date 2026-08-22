import uuid
import pytest

from app.ai.gateway import AIGateway
from app.ai.provider import AIProvider
from app.ai.types import ProviderResponse, ProviderUsage
from app.core.config import Settings


class FakeDb:
    def __init__(self): self.added=[]; self.commits=0
    def add(self,obj): self.added.append(obj)
    async def commit(self): self.commits+=1


class FakeRedis:
    def __init__(self,cached=None): self.cached=cached; self.writes=[]; self.values={}
    async def get_json(self,key): return self.cached
    async def set_json(self,key,value,ttl): self.writes.append((key,value,ttl))
    async def get_value(self,key): return self.values.get(key)
    async def set_value(self,key,value,ttl=None): self.values[key]=value
    async def delete(self,key): self.values.pop(key,None)
    async def increment(self,key,ttl): self.values[key]=str(int(self.values.get(key,"0"))+1); return int(self.values[key])
    async def reserve(self,key,amount,limit,ttl): self.values[key]=str(int(self.values.get(key,"0"))+amount); return int(self.values[key])<=limit
    async def adjust(self,key,delta,ttl): self.values[key]=str(max(0,int(self.values.get(key,"0"))+delta))


class FakeQuota:
    def __init__(self): self.checked=0; self.recorded=[]
    async def check(self,user_id,max_tokens,estimated_total_tokens): self.checked+=1; return None, estimated_total_tokens
    async def record(self,user_id,prompt,completion,reserved_tokens=0): self.recorded.append((prompt,completion))
    async def release_reservation(self,user_id,reserved_tokens): return None


class FakeProvider(AIProvider):
    def __init__(self): self.calls=0
    async def chat(self,messages,model,temperature,max_tokens):
        self.calls+=1; return ProviderResponse(model,"answer",ProviderUsage(4,3,7))


def cfg(): return Settings(APP_ENV="test",JWT_SECRET="x"*80,AI_CACHE_TTL_SECONDS=60)


@pytest.mark.asyncio
async def test_ai_cache_hit_avoids_provider():
    db=FakeDb(); redis=FakeRedis({"model":"deepseek-chat","content":"cached","usage":{"prompt_tokens":4,"completion_tokens":3,"total_tokens":7}}); provider=FakeProvider()
    g=AIGateway(db,redis,cfg(),provider); g.quota=FakeQuota()
    out=await g.chat("r1",uuid.uuid4(),None,[{"role":"user","content":" hello   world "}],"deepseek-chat",0.7,100,True)
    assert out["content"]=="cached" and out["cache_hit"] is True
    assert provider.calls==0 and db.commits==1


@pytest.mark.asyncio
async def test_ai_cache_miss_calls_provider_and_caches():
    db=FakeDb(); redis=FakeRedis(); provider=FakeProvider(); g=AIGateway(db,redis,cfg(),provider); q=FakeQuota(); g.quota=q
    out=await g.chat("r2",uuid.uuid4(),None,[{"role":"user","content":"hello"}],"deepseek-chat",0.2,100,True)
    assert out["content"]=="answer" and not out["cache_hit"]
    assert provider.calls==1 and redis.writes and q.recorded==[(4,3)]
    assert db.added[0].prompt_encrypted is None
