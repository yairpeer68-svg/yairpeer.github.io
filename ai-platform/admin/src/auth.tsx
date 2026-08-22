import {createContext,useContext,useMemo,useState,type ReactNode} from 'react';
import {api,clearTokens,login,logoutSession,setTokens} from './api';
import type {User} from './types';

type AuthValue={user:User|null;loading:boolean;signIn:(email:string,password:string)=>Promise<void>;signOut:()=>Promise<void>};
const Ctx=createContext<AuthValue|null>(null);
export function AuthProvider({children}:{children:ReactNode}){
 const [user,setUser]=useState<User|null>(null); const [loading,setLoading]=useState(false);
 const signIn=async(email:string,password:string)=>{setLoading(true);try{const pair=await login(email,password);const me=await api<User>('/users/me');if(!me.is_admin){await logoutSession();throw new Error('Administrator privileges required')}setTokens(pair);setUser(me)}finally{setLoading(false)}};
 const signOut=async()=>{try{await logoutSession()}catch{clearTokens()}finally{setUser(null)}};
 const value=useMemo(()=>({user,loading,signIn,signOut}),[user,loading]); return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
export function useAuth(){const v=useContext(Ctx);if(!v)throw new Error('AuthProvider missing');return v}
