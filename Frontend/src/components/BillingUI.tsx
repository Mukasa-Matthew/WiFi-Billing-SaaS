"use client";
import React, { useCallback, useEffect, useState } from "react";
import api from "@/lib/api";
export const field = "mt-2 w-full rounded-lg border border-slate-700 bg-slate-800 px-3 py-2.5 text-sm text-white outline-none focus:border-blue-500";
export const button = "rounded-lg bg-blue-600 px-4 py-2 text-xs font-semibold text-white hover:bg-blue-500 disabled:opacity-40";
export const panel = "rounded-xl border border-slate-800 bg-slate-900";
export function message(error: unknown) { const e = error as { response?: { data?: { message?: string; detail?: string } } }; return e.response?.data?.message || e.response?.data?.detail || "Request failed. Please retry."; }
export function useResource<T>(url: string, refresh = false) {
 const [data, setData] = useState<T[]>([]); const [loading,setLoading]=useState(true); const [error,setError]=useState("");
 const load=useCallback(async()=>{try{setData((await api.get<T[]>(url)).data);setError("");}catch(e){setError(message(e));}finally{setLoading(false);}},[url]);
 useEffect(()=>{void load();if(refresh){const t=setInterval(load,30000);return ()=>clearInterval(t);}},[load,refresh]);
 return {data,loading,error,load};
}
export function Badge({children,tone="blue"}:{children:React.ReactNode;tone?:string}) { const color=tone==="green"?"text-emerald-400 bg-emerald-500/10 border-emerald-500/20":tone==="red"?"text-red-400 bg-red-500/10 border-red-500/20":tone==="amber"?"text-amber-400 bg-amber-500/10 border-amber-500/20":"text-blue-400 bg-blue-500/10 border-blue-500/20";return <span className={`inline-block rounded border px-1.5 py-0.5 text-[11px] ${color}`}>{children}</span>; }
export function Notice({text}:{text:string}) {return text?<p role="status" className="rounded-lg border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-300">{text}</p>:null;}
export function Table<T>({rows,columns,loading=false}:{rows:T[];columns:{label:string;render:(row:T)=>React.ReactNode}[];loading?:boolean}) {
 const [search,setSearch]=useState("");const [page,setPage]=useState(1);const [size,setSize]=useState(10);
 const filtered=rows.filter(r=>JSON.stringify(r).toLowerCase().includes(search.toLowerCase()));const pages=Math.max(1,Math.ceil(filtered.length/size));const current=Math.min(page,pages);
 return <section className={`${panel} overflow-hidden`}><div className="flex justify-end border-b border-slate-800 p-3"><input aria-label="Search records" placeholder="Search…" value={search} onChange={e=>{setSearch(e.target.value);setPage(1);}} className="rounded-lg border border-slate-700 bg-slate-800 px-3 py-2 text-xs text-white" /></div><div className="overflow-x-auto"><table className="w-full whitespace-nowrap text-left text-xs"><thead className="bg-slate-800/50 text-slate-400"><tr>{columns.map((c,i)=><th key={i} className="px-4 py-4 font-medium">{c.label}</th>)}</tr></thead><tbody className="divide-y divide-slate-800">{filtered.slice((current-1)*size,current*size).map((r,i)=><tr key={i} className="hover:bg-slate-800/30">{columns.map((c,j)=><td key={j} className="px-4 py-4 text-slate-300">{c.render(r)}</td>)}</tr>)}{!filtered.length&&<tr><td colSpan={columns.length} className="py-12 text-center text-slate-500">{loading?"Loading…":"No records found."}</td></tr>}</tbody></table></div><div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-800 p-4 text-xs text-slate-400"><span>{filtered.length} results</span><div className="flex items-center gap-3"><select aria-label="Rows per page" value={size} onChange={e=>{setSize(Number(e.target.value));setPage(1);}} className="rounded border border-slate-700 bg-slate-800 p-2">{[10,25,50].map(n=><option key={n} value={n}>{n} per page</option>)}</select><button disabled={current===1} onClick={()=>setPage(current-1)} className="disabled:opacity-30">Previous</button><span>{current}/{pages}</span><button disabled={current===pages} onClick={()=>setPage(current+1)} className="disabled:opacity-30">Next</button></div></div></section>;
}
export function date(value:string|null){return value?new Date(value).toLocaleString():"—";}
export function bytes(value:number){return value<1048576?`${(value/1024).toFixed(1)} KB`:value<1073741824?`${(value/1048576).toFixed(2)} MB`:`${(value/1073741824).toFixed(2)} GB`;}
export function remaining(value:number|null){if(value===null)return "Not started";if(value===0)return "Expired";return `${Math.floor(value/86400)}d ${Math.floor(value%86400/3600)}h ${Math.floor(value%3600/60)}m`;}
