"use client";

import React, { useState } from "react";
import api from "@/lib/api";
import { ChatCircleText, Star, Trash, ArrowClockwise } from "@phosphor-icons/react";
import { useResource, Notice, ErrorNotice, message, date, PageContainer, PageHeader, buttonGhost } from "@/components/BillingUI";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import { useToast } from "@/components/ui/Toast";

interface Feedback {
  id: number;
  name: string | null;
  phone: string | null;
  mac: string | null;
  router: string | null;
  rating: number | null;
  message: string;
  createdAt: string;
}

function Stars({ n }: { n: number | null }) {
  if (!n) return <span className="text-xs text-slate-400">—</span>;
  return (
    <span className="flex items-center gap-0.5" aria-label={`${n} out of 5 stars`}>
      {[1, 2, 3, 4, 5].map((i) => (
        <Star key={i} size={13} weight={i <= n ? "fill" : "regular"} className={i <= n ? "text-amber-500" : "text-slate-300"} />
      ))}
    </span>
  );
}

export default function FeedbackPage() {
  const { data, loading, error, load } = useResource<Feedback>("/api/feedback", true);
  const [note, setNote] = useState("");
  const toast = useToast();
  const { confirm, dialog } = useConfirmDialog();

  const remove = (f: Feedback) => confirm({
    title: "Delete feedback",
    body: "Remove this feedback entry? This can't be undone.",
    confirmLabel: "Delete",
    onConfirm: async () => {
      try { await api.delete(`/api/feedback/${f.id}`); await load(); }
      catch (e) { toast("error", message(e)); }
    },
  });

  const avg = data.length
    ? (data.reduce((s, f) => s + (f.rating || 0), 0) / (data.filter((f) => f.rating).length || 1)).toFixed(1)
    : null;

  return (
    <PageContainer>
      {dialog}
      <PageHeader
        title="Customer feedback"
        description="Feedback customers leave from the captive portal."
        action={<button onClick={() => load()} disabled={loading} className={buttonGhost}><ArrowClockwise size={14} className={loading ? "animate-spin" : ""} />Refresh</button>}
      />
      <ErrorNotice text={error} />
      <Notice text={note} />

      {data.length > 0 && (
        <div className="grid gap-4 sm:grid-cols-3">
          <div className="rounded-xl border border-slate-200 bg-white p-5">
            <p className="text-sm text-slate-500">Total feedback</p>
            <p className="mt-1 text-2xl font-semibold text-slate-900">{data.length}</p>
          </div>
          <div className="rounded-xl border border-slate-200 bg-white p-5">
            <p className="text-sm text-slate-500">Average rating</p>
            <p className="mt-1 text-2xl font-semibold text-slate-900">{avg ? `${avg} / 5` : "—"}</p>
          </div>
          <div className="rounded-xl border border-slate-200 bg-white p-5">
            <p className="text-sm text-slate-500">Latest</p>
            <p className="mt-1 text-2xl font-semibold text-slate-900">{date(data[0].createdAt)}</p>
          </div>
        </div>
      )}

      <div className="space-y-3">
        {data.map((f) => (
          <article key={f.id} className="rounded-xl border border-slate-200 bg-white p-5">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-3">
                <span className="flex h-9 w-9 items-center justify-center rounded-full bg-blue-50 text-blue-700"><ChatCircleText size={16} /></span>
                <div>
                  <p className="text-sm font-semibold text-slate-900">{f.name || "Anonymous"}</p>
                  <p className="text-xs text-slate-500">
                    {[f.phone, f.router, date(f.createdAt)].filter(Boolean).join(" · ")}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-4">
                <Stars n={f.rating} />
                <button onClick={() => remove(f)} aria-label="Delete feedback" className="text-slate-400 hover:text-red-600"><Trash size={15} /></button>
              </div>
            </div>
            <p className="mt-3 text-sm leading-relaxed text-slate-700">{f.message}</p>
          </article>
        ))}
      </div>

      {!loading && !data.length && (
        <div className="rounded-xl border border-slate-200 bg-white py-16 text-center">
          <ChatCircleText className="mx-auto mb-3 h-8 w-8 text-slate-400" />
          <p className="text-sm text-slate-500">No feedback yet</p>
          <p className="mt-1 text-xs text-slate-400">When customers tap "Share Feedback" on the captive portal, their messages appear here.</p>
        </div>
      )}
    </PageContainer>
  );
}
