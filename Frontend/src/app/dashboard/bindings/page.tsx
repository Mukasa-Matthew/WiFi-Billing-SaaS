"use client";
import React,{useState} from "react";
import Link from "next/link";
import api from "@/lib/api";
import {useResource,Table,Badge,Notice,FilterTabs,button,date,message,PageContainer,PageHeader} from "@/components/BillingUI";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";
import { LinkSimple } from "@/components/icons";

interface Binding{id:number;name:string;address:string;mac:string;router:string;packageName:string;status:string;synced:boolean;expiresAt:string}

export default function Page(){
 const {data,loading,error,load}=useResource<Binding>("/api/bindings",true);
 const [tab,setTab]=useState("ACTIVE");
 const [note,setNote]=useState("");
 const toast=useToast();
 const {confirm,dialog}=useConfirmDialog();
 const remove=(b:Binding)=>confirm({
  title:"Remove access",
  body:`Remove access for ${b.name}? The router will apply the change at its next management sync.`,
  confirmLabel:"Remove",
  onConfirm:async()=>{
   try{await api.delete(`/api/bindings/${b.id}`);toast("success","Removal saved. The router will acknowledge it at its next management sync.");await load();}
   catch(e){toast("error",message(e));}
  }
 });
 return <PageContainer>
  {dialog}
  <PageHeader title="Device access" description="Devices that get Wi-Fi without a voucher — staff machines, printers, CCTV or your own." action={<Link href="/dashboard/bindings/new" className={button}><LinkSimple size={14}/>Add a device</Link>}/>
  <Notice text={error||note}/>
  <div className="flex sm:justify-end">
   <FilterTabs loading={loading} value={tab} onChange={setTab} options={[{key:"ACTIVE",label:"Active"},{key:"EXPIRED",label:"Expired"},{key:"ALL",label:"All"}].map(t=>({...t,count:data.filter(b=>t.key==="ALL"||b.status===t.key).length}))}/>
  </div>
  <Table key={tab} emptyTitle="No devices added" emptyDescription="Devices you give free access to will appear here." rows={data.filter(b=>tab==="ALL"||b.status===tab)} loading={loading} columns={[
   {label:"Name",render:b=><span className="font-medium">{b.name}</span>},
   {label:"IP address",render:b=>b.address?<span className="font-mono text-xs">{b.address}</span>:"Device ID only"},
   {label:"Device ID",render:b=><span className="font-mono text-xs">{b.mac}</span>},
   {label:"Router",render:b=>b.router},
   {label:"Plan",render:b=>b.packageName||"Free / internal"},
   {label:"Status",render:b=><Badge tone={b.status==="ACTIVE"?"green":"red"}>{b.status}</Badge>},
   {label:"On router",render:b=><Badge tone={b.synced?"green":"amber"}>{b.synced?"Applied":"Applying…"}</Badge>},
   {label:"Access ends",render:b=>b.expiresAt?date(b.expiresAt):"Never"},
   {label:"",render:b=><button disabled={b.status==="REMOVED"} onClick={()=>remove(b)} className="text-xs font-medium text-red-600 hover:underline disabled:opacity-30">Remove</button>}
  ]}/>
  <p className="text-xs text-[var(--fg-muted)]">&ldquo;Applying…&rdquo; means the router hasn&apos;t confirmed the change yet — it updates itself about once a minute.</p>
 </PageContainer>;
}
