import type {ApiError,TokenPair} from './types';

const BASE=(import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/,'');
let accessToken:string|null=null;
let refreshToken:string|null=null;
let refreshInFlight:Promise<boolean>|null=null;

export function setTokens(tokens:TokenPair|null){accessToken=tokens?.access_token??null;refreshToken=tokens?.refresh_token??null;}
export function clearTokens(){accessToken=null;refreshToken=null;}

async function refreshAccess():Promise<boolean>{
  if(!refreshToken)return false;
  if(refreshInFlight)return refreshInFlight;
  refreshInFlight=(async()=>{
    const res=await fetch(`${BASE}/auth/refresh`,{method:'POST',headers:{'Content-Type':'application/json','X-Request-ID':crypto.randomUUID()},body:JSON.stringify({refresh_token:refreshToken})});
    if(!res.ok){clearTokens();return false;}
    const pair=await res.json() as TokenPair; setTokens(pair); return true;
  })().finally(()=>{refreshInFlight=null});
  return refreshInFlight;
}

/** Prefer the server's structured error message; fall back to the status when the body is not JSON. */
async function apiError(res:Response):Promise<Error>{
  try{
    const body=await res.json() as ApiError;
    return new Error(body.error?.message||`HTTP ${res.status}`);
  }catch{
    return new Error(`HTTP ${res.status}`);
  }
}

export async function api<T>(path:string,init:RequestInit={},retry=true):Promise<T>{
  const headers=new Headers(init.headers); headers.set('Accept','application/json'); headers.set('X-Request-ID',crypto.randomUUID());
  if(init.body)headers.set('Content-Type','application/json'); if(accessToken)headers.set('Authorization',`Bearer ${accessToken}`);
  const controller=new AbortController(); const timer=setTimeout(()=>controller.abort(),15000);
  try{
    let res=await fetch(`${BASE}${path}`,{...init,headers,signal:controller.signal,credentials:'omit'});
    if(res.status===401 && retry && await refreshAccess())return api<T>(path,init,false);
    if(!res.ok)throw await apiError(res);
    if(res.status===204)return undefined as T; return await res.json() as T;
  }finally{clearTimeout(timer)}
}

export async function login(email:string,password:string):Promise<TokenPair>{
  const pair=await api<TokenPair>('/auth/login',{method:'POST',body:JSON.stringify({email,password})},false); setTokens(pair); return pair;
}

/** Multipart upload. Content-Type must be set by the browser so the boundary is correct. */
export async function upload<T>(path:string,file:File,field='file'):Promise<T>{
  const form=new FormData(); form.append(field,file);
  const headers=new Headers(); headers.set('Accept','application/json'); headers.set('X-Request-ID',crypto.randomUUID());
  if(accessToken)headers.set('Authorization',`Bearer ${accessToken}`);
  const controller=new AbortController(); const timer=setTimeout(()=>controller.abort(),300000);
  try{
    let res=await fetch(`${BASE}${path}`,{method:'POST',body:form,headers,signal:controller.signal,credentials:'omit'});
    if(res.status===401 && await refreshAccess()){
      const retryHeaders=new Headers(headers); if(accessToken)retryHeaders.set('Authorization',`Bearer ${accessToken}`);
      res=await fetch(`${BASE}${path}`,{method:'POST',body:form,headers:retryHeaders,signal:controller.signal,credentials:'omit'});
    }
    if(!res.ok)throw await apiError(res);
    return await res.json() as T;
  }finally{clearTimeout(timer)}
}

export async function logoutSession():Promise<void>{
  const token=refreshToken;
  try{
    if(accessToken){
      await api('/auth/logout',{method:'POST',body:JSON.stringify({refresh_token:token})},false);
    }
  }finally{clearTokens();}
}
