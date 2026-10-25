"use client";

import React, { useState } from "react";
import api from "@/lib/api";
import { ChatCircleText, Star, Trash } from "@/components/icons";
import { useResource, Notice, ErrorNotice, message, date, PageContainer, PageHeader } from "@/components/BillingUI";
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
  if (!n) return <span className="text-xs text-[var(--fg-faint)]">—</span>;
  return (
    <span className="flex items-center gap-0.5" aria-label={`${n} out of 5 stars`}>
      {[1, 2, 3, 4, 5].map((i) => (
        <Star key={i} size={13} className={i <= n ? "text-amber-500" : "text-slate-300"} />
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
        action={undefined}
      />
      <ErrorNotice text={error} />
      <Notice text={note} />

      {data.length > 0 && (
        <div className="grid gap-4 sm:grid-cols-3">
          <div className="rounded-xl border border-[var(--border)] bg-[var(--surface)] p-5">
            <p className="text-sm text-[var(--fg-muted)]">Total feedback</p>
            <p className="mt-1 text-2xl font-semibold text-[var(--fg)]">{data.length}</p>
          </div>
          <div className="rounded-xl border border-[var(--border)] bg-[var(--surface)] p-5">
            <p className="text-sm text-[var(--fg-muted)]">Average rating</p>
            <p className="mt-1 text-2xl font-semibold text-[var(--fg)]">{avg ? `${avg} / 5` : "—"}</p>
          </div>
          <div className="rounded-xl border border-[var(--border)] bg-[var(--surface)] p-5">
            <p className="text-sm text-[var(--fg-muted)]">Latest</p>
            <p className="mt-1 text-2xl font-semibold text-[var(--fg)]">{date(data[0].createdAt)}</p>
          </div>
        </div>
      )}

      <div className="space-y-3">
        {data.map((f) => (
          <article key={f.id} className="rounded-xl border border-[var(--border)] bg-[var(--surface)] p-5">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-3">
                <span className="flex h-9 w-9 items-center justify-center rounded-full bg-blue-50 text-[var(--accent-text)]"><ChatCircleText size={16} /></span>
                <div>
                  <p className="text-sm font-semibold text-[var(--fg)]">{f.name || "Anonymous"}</p>
                  <p className="text-xs text-[var(--fg-muted)]">
                    {[f.phone, f.router, date(f.createdAt)].filter(Boolean).join(" · ")}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-4">
                <Stars n={f.rating} />
                <button onClick={() => remove(f)} aria-label="Delete feedback" className="text-[var(--fg-faint)] hover:text-red-600"><Trash size={15} /></button>
              </div>
            </div>
            <p className="mt-3 text-sm leading-relaxed text-[var(--fg-body)]">{f.message}</p>
          </article>
        ))}
      </div>

      {!loading && !data.length && (
        <div className="rounded-xl border border-[var(--border)] bg-[var(--surface)] py-16 text-center">
          <ChatCircleText className="mx-auto mb-3 h-8 w-8 text-[var(--fg-faint)]" />
          <p className="text-sm text-[var(--fg-muted)]">No feedback yet</p>
          <p className="mt-1 text-xs text-[var(--fg-faint)]">When customers tap "Share Feedback" on the captive portal, their messages appear here.</p>
        </div>
      )}
    </PageContainer>
  );
}
