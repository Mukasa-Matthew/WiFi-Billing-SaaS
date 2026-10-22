"use client";
import React, { useState } from "react";
import { Users, Wifi, WifiOff, RefreshCw, Archive, Unplug } from "lucide-react";
import api from "@/lib/api";
import { useResource, Table, Badge, Notice, ErrorNotice, date, bytes, remaining, message, StatCard, FilterTabs, buttonGhost } from "./BillingUI";
interface User {id:number;customerId:number|null;customerName:string|null;phone:string|null;voucher:string;packageName:string;status:string;online:boolean;ip:string|null;mac:string|null;router:string|null;deviceLimit:number;devices:number;firstLogin:string|null;expiresAt:string|null;dataBytes:number;timeLeftSeconds:number|null}
function expiredAgo(value:string|null) {
 if (!value) return "—";
 const minutes=Math.max(0,Math.floor((Date.now()-new Date(value).getTime())/60000));
 return minutes<1?"Just now":minutes<60?`${minutes}m ago`:minutes<1440?`${Math.floor(minutes/60)}h ${minutes%60}m ago`:`${Math.floor(minutes/1440)}d ago`;
}
export default function UserDirectory({expired=false}:{expired?:boolean}) {
 const {data,loading,refreshing,error,load}=useResource<User>("/api/users",true);
 const [tab,setTab]=useState("ONLINE"), [note,setNote]=useState(""), [actionError,setActionError]=useState("");
 const [pending,setPending]=useState<number|null>(null);
 const active=data.filter(u=>u.status==="active"), online=active.filter(u=>u.online), offline=active.filter(u=>!u.online);
 const rows=expired?data.filter(u=>["expired","revoked"].includes(u.status)).sort((a,b)=>(Date.parse(b.expiresAt||"")||0)-(Date.parse(a.expiresAt||"")||0)):tab==="ONLINE"?online:tab==="OFFLINE"?offline:active;
 const action=async(u:User)=>{
  if(!confirm(expired?"Archive this expired record? Payment and accounting history will be retained.":`Send a disconnect request for ${u.voucher}?`))return;
  setPending(u.id);setActionError("");setNote("");
  try {await api.post(expired?`/api/vouchers/${encodeURIComponent(u.voucher)}/archive`:"/api/sessions/disconnect",expired?{}:{username:u.voucher});setNote(expired?"Record archived. Payment and accounting history retained.":"Disconnect request sent. Check accounting for confirmation.");await load();}
  catch(e){setActionError(message(e));}finally{setPending(null);}
 };
 const actionColumn={label:"Actions",render:(u:User)=><button disabled={pending!==null||(!expired&&!u.online)} onClick={()=>action(u)} className="inline-flex items-center gap-1.5 text-xs font-semibold text-red-700 disabled:opacity-40">{expired?<Archive size={14}/>:<Unplug size={14}/ >}{pending===u.id?"Processing…":expired?"Archive":"Disconnect"}</button>};
 return <div className="mx-auto max-w-7xl space-y-6">
  <div className="flex flex-wrap items-center justify-between gap-3"><div><h1 className="text-xl font-bold tracking-tight text-slate-900">{expired?"Expired users":"Active users"}</h1><p className="mt-1 text-sm text-slate-600">{expired?"Review expired access and revoked vouchers. Latest expiry appears first.":"Monitor voucher validity, connected devices, and customer usage."}</p></div><button onClick={()=>load()} disabled={refreshing} className={buttonGhost}><RefreshCw size={14} className={refreshing?"animate-spin":""}/>Refresh</button></div>
  <ErrorNotice text={error||actionError}/><Notice text={note}/>
  {!expired&&<><div className="grid gap-4 sm:grid-cols-3"><StatCard label="All active" value={loading?"—":active.length} icon={<Users size={20}/>} hintText="Vouchers within their validity period"/><StatCard label="Online now" value={loading?"—":online.length} icon={<Wifi size={20}/>} tone="green" hintText="Recent accounting received within 6 minutes"/><StatCard label="Offline" value={loading?"—":offline.length} icon={<WifiOff size={20}/>} hintText="Valid access with no recent connection"/></div><div className="flex sm:justify-end"><FilterTabs value={tab} onChange={setTab} options={[{key:"ONLINE",label:"Online now",count:online.length},{key:"OFFLINE",label:"Offline",count:offline.length},{key:"ALL",label:"All active",count:active.length}]}/></div></>}
  <Table key={expired?"expired":tab} rows={rows} loading={loading} emptyTitle={expired?"No expired users":tab==="ONLINE"?"No users online":tab==="OFFLINE"?"No offline users":"No active users"} emptyDescription={expired?"Expired and revoked vouchers will appear here.":tab==="ONLINE"?"Connected users appear when recent router accounting is received.":"Users appear here after activating a voucher."} columns={expired?[
   {label:"Username",render:u=><Badge tone="amber">{u.customerId?`C${u.customerId}`:u.voucher}</Badge>},
   {label:"Full name",render:u=>u.customerName||"Walk-in customer"},{label:"Phone",render:u=>u.phone||"—"},
   {label:"Status",render:u=><Badge tone="red">{u.status}</Badge>},{label:"Package",render:u=><Badge>{u.packageName}</Badge>},
   {label:"Expiry date",render:u=><span className="text-red-700">{date(u.expiresAt)}</span>},
   {label:"Expired",render:u=>u.expiresAt?<Badge tone="red">{expiredAgo(u.expiresAt)}</Badge>:"—"},actionColumn
  ]:[
   {label:"Customer ID",render:u=>u.customerId?<Badge tone="slate">C{u.customerId}</Badge>:"Unassigned"},
   {label:"Voucher",render:u=><Badge tone="amber">{u.voucher}</Badge>},{label:"IP",render:u=>u.ip?<Badge tone="green">{u.ip}</Badge>:"—"},
   {label:"MAC",render:u=>u.mac?<Badge>{u.mac}</Badge>:"—"},{label:"Devices",render:u=><Badge tone="amber">{u.devices}/{u.deviceLimit}</Badge>},
   {label:"Router",render:u=>u.router||"—"},{label:"First activation",render:u=>date(u.firstLogin)},{label:"Access expires",render:u=>date(u.expiresAt)},
   {label:"Data usage",render:u=><Badge>{bytes(u.dataBytes)}</Badge>},{label:"Time left",render:u=><Badge tone={(u.timeLeftSeconds??0)<3600?"amber":"green"}>{remaining(u.timeLeftSeconds)}</Badge>},actionColumn
  ]}/>
 </div>;
}
