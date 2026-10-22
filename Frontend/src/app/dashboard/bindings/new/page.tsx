"use client";
import React,{useState} from "react";
import Link from "next/link";
import {useRouter} from "next/navigation";
import api from "@/lib/api";
import {useResource,Notice,field,button,buttonGhost,message,PageContainer,PageHeader,label,hint} from "@/components/BillingUI";
import { ArrowLeft } from "lucide-react";

interface Router{id:number;name:string}
interface Plan{id:number;name:string;price:number;sessionTimeout:number}
interface Host{address?:string;"mac-address"?:string;"host-name"?:string}
const req=<span className="text-red-500"> *</span>;

export default function Page(){
 const nav=useRouter();
 const routers=useResource<Router>("/api/routers");
 const plans=useResource<Plan>("/api/packages");
 const [router,setRouter]=useState("");const [name,setName]=useState("");
 const [plan,setPlan]=useState("");const [mac,setMac]=useState("");const [address,setAddress]=useState("");const [expires,setExpires]=useState("");
 const [paid,setPaid]=useState(false);const [amount,setAmount]=useState("");const [method,setMethod]=useState("CASH");const [reference,setReference]=useState("");
 const [hosts,setHosts]=useState<Host[]>([]);const [seen,setSeen]=useState("");
 const [note,setNote]=useState("");const [saving,setSaving]=useState(false);
 const loadHosts=async(id:string)=>{setHosts([]);setSeen("");if(!id)return;try{const res=await api.get<{hosts:Host[];seenAt:string}>(`/api/routers/${id}/hosts`);setHosts(res.data.hosts);setSeen(res.data.seenAt);}catch(e){setNote(message(e));}};
 const save=async(e:React.FormEvent)=>{e.preventDefault();setSaving(true);try{await api.post("/api/bindings",{routerId:Number(router),name,packageId:plan?Number(plan):null,mac,address:address||null,expiresAt:expires?new Date(expires).toISOString():null,recordPayment:paid,amount:paid?Number(amount):null,method:paid?method:null,reference:reference||null});nav.push("/dashboard/bindings");}catch(e){setNote(message(e));}finally{setSaving(false);}};

 return <PageContainer width="narrow">
  <div>
   <Link href="/dashboard/bindings" className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-500 hover:text-slate-900"><ArrowLeft size={13}/>IP bindings</Link>
   <div className="mt-3"><PageHeader title="Bind a device" description="Grant a device automatic access that skips the captive portal, with optional expiry and paid access rules."/></div>
  </div>
  <Notice text={note||routers.error||plans.error}/>
  <form onSubmit={save} className="space-y-6">
   <section className="grid gap-5 rounded-xl border border-slate-200 bg-white p-6 shadow-sm sm:grid-cols-2">
    <div className="sm:col-span-2">
     <label className={label}>MikroTik{req}</label>
     <select required value={router} onChange={e=>{setRouter(e.target.value);void loadHosts(e.target.value);}} className={field}>
      <option value="">Select router</option>
      {routers.data.map(r=><option key={r.id} value={r.id}>{r.name}</option>)}
     </select>
    </div>
    <div>
     <label className={label}>Name{req}</label>
     <input required maxLength={120} value={name} onChange={e=>setName(e.target.value)} className={field}/>
    </div>
    <div>
     <label className={label}>Package</label>
     <select required={paid} value={plan} onChange={e=>{setPlan(e.target.value);const p=plans.data.find(x=>x.id===Number(e.target.value));if(p)setAmount(String(p.price));}} className={field}>
      <option value="">Free / internal bypass</option>
      {plans.data.map(p=><option key={p.id} value={p.id}>{p.name}</option>)}
     </select>
     <p className={hint}>Sets speed and default expiry from now.</p>
    </div>
    <div>
     <label className={label}>Active host</label>
     <select aria-label="Active host" defaultValue="" onChange={e=>{const h=hosts[Number(e.target.value)];if(h){setMac(h["mac-address"]||"");setAddress(h.address||"");}}} className={field}>
      <option value="">Select a reported DHCP lease</option>
      {hosts.map((h,i)=><option key={i} value={i}>{h.address} · {h["mac-address"]} {h["host-name"]}</option>)}
     </select>
     <button type="button" disabled={!router} onClick={()=>loadHosts(router)} className="mt-2 text-xs font-medium text-blue-700 hover:underline disabled:opacity-40">Refresh reported hosts</button>
     <p className={hint}>{seen?`Last report: ${new Date(seen).toLocaleString()}`:"No host report yet. Enter the IP and MAC manually."}</p>
    </div>
    <div>
     <label className={label}>Address{plan&&req}</label>
     <input required={!!plan} value={address} onChange={e=>setAddress(e.target.value)} placeholder="10.10.0.7" className={field}/>
     <p className={hint}>Optional for free MAC-only bypass. Required when a package sets speed limits.</p>
    </div>
    <div>
     <label className={label}>MAC address{req}</label>
     <input required pattern="[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}" value={mac} onChange={e=>setMac(e.target.value.toUpperCase())} placeholder="AA:BB:CC:DD:EE:FF" className={field}/>
    </div>
    <div>
     <label className={label}>Expires at</label>
     <input type="datetime-local" value={expires} onChange={e=>setExpires(e.target.value)} className={field}/>
     <p className={hint}>Overrides package expiry. Without a package or date, access never expires.</p>
    </div>
   </section>
   <section className="space-y-5 rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
    <h2 className="text-sm font-semibold text-slate-900">Payment recording</h2>
    <label className="flex items-center gap-2.5 text-sm text-slate-700">
     <input type="checkbox" checked={paid} onChange={e=>setPaid(e.target.checked)} className="h-4 w-4 rounded border-slate-300 text-blue-700"/>
     Record payment
    </label>
    <p className={hint}>Records an already received payment in revenue. Leave off for free or internal access.</p>
    {paid&&<div className="grid gap-5 sm:grid-cols-2">
     <div>
      <label className={label}>Amount paid (UGX){req}</label>
      <input required type="number" min="0.01" step="0.01" value={amount} onChange={e=>setAmount(e.target.value)} className={field}/>
     </div>
     <div>
      <label className={label}>Payment method{req}</label>
      <select value={method} onChange={e=>setMethod(e.target.value)} className={field}>{["CASH","MTN","AIRTEL","BANK","OTHER"].map(m=><option key={m}>{m}</option>)}</select>
     </div>
     <div className="sm:col-span-2">
      <label className={label}>Payment reference</label>
      <input maxLength={128} value={reference} onChange={e=>setReference(e.target.value)} className={field}/>
     </div>
    </div>}
   </section>
   <div className="flex items-center gap-3">
    <button disabled={saving} className={button}>{saving?"Saving…":"Create binding"}</button>
    <Link href="/dashboard/bindings" className={buttonGhost}>Cancel</Link>
   </div>
  </form>
 </PageContainer>;
}
