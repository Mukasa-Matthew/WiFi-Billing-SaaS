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
  <PageHeader title="IP bindings" description="Manage device bypass access and automatic expiry." action={<Link href="/dashboard/bindings/new" className={button}><LinkSimple size={14}/>Bind IP</Link>}/>
  <Notice text={error||note}/>
  <div className="flex sm:justify-end">
   <FilterTabs loading={loading} value={tab} onChange={setTab} options={[{key:"ACTIVE",label:"Active"},{key:"EXPIRED",label:"Expired"},{key:"ALL",label:"All"}].map(t=>({...t,count:data.filter(b=>t.key==="ALL"||b.status===t.key).length}))}/>
  </div>
  <Table key={tab} emptyTitle="No IP bindings" emptyDescription="Bindings for the selected status will appear here." rows={data.filter(b=>tab==="ALL"||b.status===tab)} loading={loading} columns={[
   {label:"Name",render:b=><span className="font-medium">{b.name}</span>},
   {label:"IP",render:b=>b.address?<span className="font-mono text-xs">{b.address}</span>:"MAC only"},
   {label:"MAC",render:b=><span className="font-mono text-xs">{b.mac}</span>},
   {label:"Router",render:b=>b.router},
   {label:"Package",render:b=>b.packageName||"Free / internal"},
   {label:"Status",render:b=><Badge tone={b.status==="ACTIVE"?"green":"red"}>{b.status}</Badge>},
   {label:"Router sync",render:b=><Badge tone={b.synced?"green":"amber"}>{b.synced?"Acknowledged":"Pending"}</Badge>},
   {label:"Expiry",render:b=>b.expiresAt?date(b.expiresAt):"Never"},
   {label:"",render:b=><button disabled={b.status==="REMOVED"} onClick={()=>remove(b)} className="text-xs font-medium text-red-600 hover:underline disabled:opacity-30">Remove</button>}
  ]}/>
  <p className="text-xs text-[var(--fg-muted)]">Pending means the router has not confirmed this configuration. The management agent must be installed; physical enforcement is verified in the later router test.</p>
 </PageContainer>;
}
