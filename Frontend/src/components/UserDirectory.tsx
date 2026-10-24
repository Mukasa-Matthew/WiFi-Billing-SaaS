"use client";
import React, { useState } from "react";
import { Users, WifiHigh, WifiSlash, ArrowClockwise, Archive, Plugs, Eye, X, ClockCountdown, Prohibit, CalendarX, Package, DeviceMobile, Globe, User as UserIcon } from "@phosphor-icons/react";
import api from "@/lib/api";
import { useResource, Table, Badge, Notice, ErrorNotice, date, bytes, remaining, message, StatCard, FilterTabs, buttonGhost, PageContainer, PageHeader } from "./BillingUI";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";

interface User {id:number;customerId:number|null;customerName:string|null;phone:string|null;voucher:string;packageName:string;status:string;online:boolean;ip:string|null;mac:string|null;router:string|null;deviceLimit:number;devices:number;firstLogin:string|null;expiresAt:string|null;dataBytes:number;timeLeftSeconds:number|null}

function expiredAgo(value:string|null) {
 if (!value) return "—";
 const minutes=Math.max(0,Math.floor((Date.now()-new Date(value).getTime())/60000));
 return minutes<1?"Just now":minutes<60?`${minutes}m ago`:minutes<1440?`${Math.floor(minutes/60)}h ${minutes%60}m ago`:`${Math.floor(minutes/1440)}d ago`;
}

function Detail({label,value,mono}:{label:string;value:string;mono?:boolean}) {
 return <div><dt className="text-[10px] font-bold uppercase tracking-wider text-slate-400">{label}</dt><dd className={`mt-0.5 text-xs font-medium text-slate-800 ${mono?"font-mono":""}`}>{value}</dd></div>;
}

function OnlineDot({online}:{online:boolean}) {
 return <span className="inline-flex items-center gap-1.5 text-xs font-semibold">
  <span className={`h-2 w-2 rounded-full ${online?"bg-emerald-500":"bg-slate-300"}`} />
  {online?<span className="text-emerald-700">Online</span>:<span className="text-slate-500">Offline</span>}
 </span>;
}

export default function UserDirectory({expired=false}:{expired?:boolean}) {
 const {data,loading,refreshing,error,load}=useResource<User>("/api/users",true);
 const [tab,setTab]=useState("ONLINE"), [note,setNote]=useState(""), [actionError,setActionError]=useState("");
 const [pending,setPending]=useState<number|null>(null);
 const [view,setView]=useState<User|null>(null);
 const toast=useToast();
 const {confirm,dialog}=useConfirmDialog();
 const active=data.filter(u=>u.status==="active"), online=active.filter(u=>u.online), offline=active.filter(u=>!u.online);
 const expiredRows=data.filter(u=>["expired","revoked"].includes(u.status)).sort((a,b)=>(Date.parse(b.expiresAt||"")||0)-(Date.parse(a.expiresAt||"")||0));
 const now=Date.now(), day=86400000;
 const expToday=expiredRows.filter(u=>u.expiresAt&&now-Date.parse(u.expiresAt)<day).length;
 const expWeek=expiredRows.filter(u=>u.expiresAt&&now-Date.parse(u.expiresAt)<7*day).length;
 const revoked=expiredRows.filter(u=>u.status==="revoked").length;
 const rows=expired?expiredRows:tab==="ONLINE"?online:tab==="OFFLINE"?offline:active;
 const action=(u:User)=>confirm({
  title:expired?"Archive record":`Disconnect ${u.voucher}`,
  body:expired?"Archive this expired record? Payment and accounting history will be retained.":"Send a disconnect request? The session ends only when the router confirms it.",
  confirmLabel:expired?"Archive":"Disconnect",
  onConfirm:async()=>{
   setPending(u.id);setActionError("");setNote("");
   try{await api.post(expired?`/api/vouchers/${encodeURIComponent(u.voucher)}/archive`:"/api/sessions/disconnect",expired?{}:{username:u.voucher});toast("success",expired?"Record archived.":"Disconnect request sent.");await load();}
   catch(e){setActionError(message(e));}finally{setPending(null);}
  }
 });
 const actionColumn={label:"Actions",render:(u:User)=><span className="inline-flex items-center gap-3">
  <button aria-label={`View ${u.voucher}`} onClick={()=>setView(u)} className="inline-flex items-center gap-1.5 text-xs font-semibold text-blue-700"><Eye size={14}/>View</button>
  <button disabled={pending!==null||(!expired&&!u.online)} onClick={()=>action(u)} className="inline-flex items-center gap-1.5 text-xs font-semibold text-red-700 disabled:opacity-40">{expired?<Archive size={14}/>:<Plugs size={14}/ >}{pending===u.id?"Processing…":expired?"Archive":"Disconnect"}</button>
 </span>};
 return <PageContainer>
  {dialog}
  <PageHeader title={expired?"Expired users":"Active users"} description={expired?"Review expired access and revoked vouchers. Latest expiry appears first.":"Monitor voucher validity, connected devices, and customer usage."} action={<button onClick={()=>load()} disabled={refreshing} className={buttonGhost}><ArrowClockwise size={14} className={refreshing?"animate-spin":""}/>Refresh</button>}/>
  <ErrorNotice text={error||actionError}/><Notice text={note}/>
  {expired?<div className="grid gap-4 sm:grid-cols-4">
   <StatCard label="Expired today" value={loading?"—":expToday} icon={<ClockCountdown size={20}/>} tone="amber" hintText="Access ended in the last 24h"/>
   <StatCard label="Expired this week" value={loading?"—":expWeek} icon={<CalendarX size={20}/>} hintText="Ended within the last 7 days"/>
   <StatCard label="Revoked" value={loading?"—":revoked} icon={<Prohibit size={20}/>} tone="red" hintText="Manually blocked vouchers"/>
   <StatCard label="Total inactive" value={loading?"—":expiredRows.length} icon={<Users size={20}/>} hintText="All expired and revoked records"/>
  </div>:<>
   <div className="grid gap-4 sm:grid-cols-3"><StatCard label="All active" value={loading?"—":active.length} icon={<Users size={20}/>} hintText="Vouchers within their validity period"/><StatCard label="Online now" value={loading?"—":online.length} icon={<WifiHigh size={20}/>} tone="green" hintText="Recent accounting received within 6 minutes"/><StatCard label="Offline" value={loading?"—":offline.length} icon={<WifiSlash size={20}/>} hintText="Valid access with no recent connection"/></div>
   <div className="flex sm:justify-end"><FilterTabs value={tab} onChange={setTab} options={[{key:"ONLINE",label:"Online now",count:online.length},{key:"OFFLINE",label:"Offline",count:offline.length},{key:"ALL",label:"All active",count:active.length}]}/></div>
  </>}
  <Table key={expired?"expired":tab} rows={rows} loading={loading} emptyTitle={expired?"No expired users":tab==="ONLINE"?"No users online":tab==="OFFLINE"?"No offline users":"No active users"} emptyDescription={expired?"Expired and revoked vouchers will appear here.":tab==="ONLINE"?"Connected users appear when recent router accounting is received.":"Users appear here after activating a voucher."} columns={expired?[
   {label:"Username",render:u=><span className="font-mono text-xs font-semibold">{u.customerId?`C${u.customerId}`:u.voucher}</span>},
   {label:"Full name",render:u=><span className="font-medium">{u.customerName||"Walk-in customer"}</span>},{label:"Phone",render:u=>u.phone||"—"},
   {label:"Status",render:u=><Badge tone="red">{u.status}</Badge>},{label:"Package",render:u=>u.packageName},
   {label:"Expiry date",render:u=><span className="text-red-700">{date(u.expiresAt)}</span>},
   {label:"Expired",render:u=>u.expiresAt?<Badge tone="red">{expiredAgo(u.expiresAt)}</Badge>:"—"},actionColumn
  ]:[
   {label:"Customer",render:u=><div><span className="font-medium">{u.customerName||"Unassigned"}</span>{u.phone&&<span className="block text-xs text-slate-500">{u.phone}</span>}</div>},
   {label:"Voucher",render:u=><Badge tone="amber">{u.voucher}</Badge>},
   {label:"Connection",render:u=><OnlineDot online={u.online}/>},
   {label:"Package",render:u=>u.packageName},
   {label:"IP",render:u=>u.ip?<span className="font-mono text-xs">{u.ip}</span>:"—"},
   {label:"MAC",render:u=>u.mac?<span className="font-mono text-xs">{u.mac}</span>:"—"},
   {label:"Devices",render:u=><span className="tabular-nums">{u.devices}/{u.deviceLimit}</span>},
   {label:"Router",render:u=>u.router||"—"},{label:"First activation",render:u=><span className="text-slate-500">{date(u.firstLogin)}</span>},{label:"Access expires",render:u=><span className="text-slate-500">{date(u.expiresAt)}</span>},
   {label:"Data usage",render:u=>bytes(u.dataBytes)},{label:"Time left",render:u=><Badge tone={(u.timeLeftSeconds??0)<3600?"amber":"green"}>{remaining(u.timeLeftSeconds)}</Badge>},actionColumn
  ]}/>
 {view&&<div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/50 p-4" onClick={()=>setView(null)}>
  <div className="w-full max-w-lg overflow-hidden rounded-2xl bg-white shadow-xl" onClick={e=>e.stopPropagation()}>
   <div className="flex items-start justify-between border-b border-slate-100 bg-slate-50 px-6 py-4">
    <div className="flex items-center gap-3">
     <div className={`flex h-11 w-11 items-center justify-center rounded-full border ${view.status==="active"?"border-emerald-200 bg-emerald-50 text-emerald-700":"border-red-200 bg-red-50 text-red-600"}`}><UserIcon size={20}/></div>
     <div>
      <h2 className="text-sm font-bold text-slate-900">{view.customerName||"Walk-in customer"}</h2>
      <div className="mt-0.5 flex items-center gap-2">
       <span className="font-mono text-xs font-semibold text-slate-600">{view.voucher}</span>
       <Badge tone={view.status==="active"?"green":"red"}>{view.status}</Badge>
       <OnlineDot online={view.online}/>
      </div>
     </div>
    </div>
    <button aria-label="Close" onClick={()=>setView(null)} className="text-slate-400 hover:text-slate-700"><X size={18}/></button>
   </div>
   <div className="space-y-5 px-6 py-5">
    <section>
     <h3 className="mb-2 flex items-center gap-1.5 text-[10px] font-bold uppercase tracking-wider text-slate-400"><Package size={12}/>Package & access</h3>
     <div className="grid grid-cols-3 gap-3 rounded-xl border border-slate-100 bg-slate-50/60 p-3">
      <Detail label="Package" value={view.packageName}/>
      <Detail label="Time left" value={remaining(view.timeLeftSeconds)}/>
      <Detail label="Data used" value={bytes(view.dataBytes)}/>
      <Detail label="First login" value={date(view.firstLogin)}/>
      <Detail label="Expires" value={date(view.expiresAt)}/>
      <Detail label="Devices" value={`${view.devices}/${view.deviceLimit}`}/>
     </div>
    </section>
    <section>
     <h3 className="mb-2 flex items-center gap-1.5 text-[10px] font-bold uppercase tracking-wider text-slate-400"><Globe size={12}/>Connection</h3>
     <div className="grid grid-cols-3 gap-3 rounded-xl border border-slate-100 bg-slate-50/60 p-3">
      <Detail label="Router" value={view.router||"—"}/>
      <Detail label="IP address" value={view.ip||"—"} mono/>
      <Detail label="MAC address" value={view.mac||"—"} mono/>
     </div>
    </section>
    <section>
     <h3 className="mb-2 flex items-center gap-1.5 text-[10px] font-bold uppercase tracking-wider text-slate-400"><DeviceMobile size={12}/>Customer</h3>
     <div className="grid grid-cols-2 gap-3 rounded-xl border border-slate-100 bg-slate-50/60 p-3">
      <Detail label="Name" value={view.customerName||"Walk-in customer"}/>
      <Detail label="Phone" value={view.phone||"—"}/>
     </div>
    </section>
   </div>
  </div>
 </div>}
 </PageContainer>;
}
