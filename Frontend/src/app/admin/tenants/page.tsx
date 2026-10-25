"use client";

import React, { useEffect, useState, useRef } from "react";
import api from "@/lib/api";
import { Dialog } from "@/components/ui/Dialog";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import {
  Users,
  UserPlus,
  MagnifyingGlass,
  CheckCircle,
  XCircle,
  CellTower,
  Ticket,
  X,
  WarningCircle,
  DotsThreeVertical,
  Prohibit,
  Check,
  EnvelopeSimple,
  Copy,
  Trash
} from "@/components/icons";

interface TenantSummary {
  id: number;
  name: string;
  email: string;
  phone: string;
  role: string;
  status: string;
  routerCount: number;
  voucherCount: number;
  createdAt: string;
}

export default function AdminTenantsPage() {
  const [tenants, setTenants] = useState<TenantSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [openMenuId, setOpenMenuId] = useState<number | null>(null);
  const menuRef = useRef<HTMLDivElement | null>(null);
  const { confirm, dialog } = useConfirmDialog();

  // Form state
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [formLoading, setFormLoading] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [createNotice, setCreateNotice] = useState<string | null>(null);

  // Close dropdown menu when clicking outside
  useEffect(() => {
    const handleOutsideClick = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setOpenMenuId(null);
      }
    };
    if (openMenuId !== null) {
      document.addEventListener("mousedown", handleOutsideClick);
    }
    return () => {
      document.removeEventListener("mousedown", handleOutsideClick);
    };
  }, [openMenuId]);

  const closeCreateDialog = () => {
    if (formLoading) return;
    setIsModalOpen(false);
    setFormError(null);
  };

  const fetchTenants = async () => {
    try {
      const res = await api.get<TenantSummary[]>("/api/admin/tenants");
      setTenants(res.data);
    } catch (err) {
      console.error("Failed to fetch tenants:", err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTenants();
  }, []);

  const handleCreateTenant = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);
    setFormLoading(true);

    try {
      const res = await api.post<{ temporaryPassword: string | null; welcomeEmailSent: boolean }>("/api/admin/tenants", {
        name: name.trim(),
        email: email.trim(),
        phone: phone.trim(),
      });

      setIsModalOpen(false);
      setName("");
      setEmail("");
      setPhone("");
      setCreateNotice(res.data.welcomeEmailSent
        ? "Operator created. Their temporary login details were sent by email."
        : `Operator created, but the welcome email was not sent. Give them this temporary password securely: ${res.data.temporaryPassword}`);
      await fetchTenants();
    } catch (err: unknown) {
      if (err && typeof err === "object" && "response" in err) {
        const axErr = err as { response?: { data?: { message?: string } } };
        setFormError(axErr.response?.data?.message || "Failed to create tenant.");
      } else {
        setFormError("Network error occurred.");
      }
    } finally {
      setFormLoading(false);
    }
  };

  const handleUpdateStatus = async (tenant: TenantSummary, newStatus: string) => {
    setOpenMenuId(null);
    try {
      await api.put(`/api/admin/tenants/${tenant.id}/status`, {
        status: newStatus,
      });
      await fetchTenants();
    } catch (err) {
      console.error("Failed to update status:", err);
    }
  };

  const handleCopyDetails = (tenant: TenantSummary) => {
    setOpenMenuId(null);
    const details = `Business: ${tenant.name}\nEmail: ${tenant.email}\nPhone: ${tenant.phone}\nID: #${tenant.id}`;
    navigator.clipboard.writeText(details);
    setCreateNotice(`Copied details for "${tenant.name}" to clipboard.`);
  };

  const handleRequestDelete = (tenant: TenantSummary) => {
    setOpenMenuId(null);
    confirm({
      title: `Decommission ${tenant.name}?`,
      body: `Permanently deleting a tenant with live transactions and routers is restricted to preserve financial audit logs. You can disable the operator account to instantly block portal access and logins. Would you like to set their status to DISABLED?`,
      confirmLabel: "Disable Account",
      onConfirm: async () => {
        await handleUpdateStatus(tenant, "DISABLED");
      },
    });
  };

  const filteredTenants = tenants.filter((t) => {
    const q = search.toLowerCase();
    return (
      t.name.toLowerCase().includes(q) ||
      t.email.toLowerCase().includes(q) ||
      t.phone.toLowerCase().includes(q)
    );
  });

  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      {dialog}

      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold tracking-tight text-[var(--fg)] flex items-center gap-2.5">
            <Users className="w-6 h-6 text-[var(--accent-text)]" />
            <span>Hotspot Business Operators</span>
          </h1>
          <p className="text-xs text-[var(--fg-muted)] mt-1">
            Provision, manage, and monitor all tenant organizations and accounts
          </p>
        </div>

        <button
          onClick={() => setIsModalOpen(true)}
          className="flex items-center gap-2 px-4 py-2 rounded-xl bg-blue-700 hover:bg-blue-600 text-xs font-semibold text-white shadow-sm transition cursor-pointer"
        >
          <UserPlus className="w-4 h-4" />
          <span>Register New Operator</span>
        </button>
      </div>

      {/* Filter / Search Bar */}
      <div className="p-4 rounded-xl bg-[var(--surface)] border border-[var(--border)] flex items-center justify-between gap-4">
        <div className="relative flex-1 max-w-md">
          <MagnifyingGlass className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--fg-muted)]" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search operator name, email, or phone..."
            className="w-full pl-10 pr-4 py-2 rounded-xl bg-[var(--surface)] border border-[var(--border)] text-xs text-[var(--fg)] placeholder-[var(--fg-muted)] focus:outline-none focus:border-indigo-500 transition"
          />
        </div>
        <span className="text-xs text-[var(--fg-muted)]">
          Showing <b>{filteredTenants.length}</b> operators
        </span>
      </div>

      {createNotice && (
        <div className="p-3.5 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm flex items-start justify-between gap-3">
          <span>{createNotice}</span>
          <button type="button" aria-label="Dismiss notice" onClick={() => setCreateNotice(null)} className="shrink-0"><X className="w-4 h-4" /></button>
        </div>
      )}

      {/* Operators Table */}
      <div className="rounded-xl bg-[var(--surface)] border border-[var(--border)]">
        <div className="overflow-x-auto overflow-y-visible">
          <table className="w-full text-left text-xs">
            <thead>
              <tr className="border-b border-[var(--border)] bg-[var(--subtle)] text-[var(--fg-muted)]">
                <th className="py-3.5 px-6 font-semibold">Operator Business</th>
                <th className="py-3.5 px-4 font-semibold">Contact Email</th>
                <th className="py-3.5 px-4 font-semibold">Phone</th>
                <th className="py-3.5 px-4 font-semibold">Routers</th>
                <th className="py-3.5 px-4 font-semibold">Vouchers</th>
                <th className="py-3.5 px-4 font-semibold">Status</th>
                <th className="py-3.5 px-4 font-semibold">Date Joined</th>
                <th className="py-3.5 px-6 font-semibold text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100/60">
              {loading ? (
                <tr>
                  <td colSpan={8} className="py-12 text-center text-[var(--fg-muted)]">
                    <div className="inline-block h-6 w-6 animate-spin rounded-full border-2 border-indigo-500 border-t-transparent" />
                    <p className="mt-2 text-xs">Loading hotspot operators...</p>
                  </td>
                </tr>
              ) : filteredTenants.length === 0 ? (
                <tr>
                  <td colSpan={8} className="py-12 text-center text-[var(--fg-muted)]">
                    No operators found matching your criteria.
                  </td>
                </tr>
              ) : (
                filteredTenants.map((t) => (
                  <tr key={t.id} className="hover:bg-[var(--subtle)] transition">
                    <td className="py-4 px-6 font-medium text-slate-100">
                      <div className="font-semibold text-[var(--fg)]">{t.name}</div>
                      <span className="text-[10px] text-[var(--fg-muted)]">ID: #{t.id}</span>
                    </td>
                    <td className="py-4 px-4 text-[var(--fg-body)]">{t.email}</td>
                    <td className="py-4 px-4 text-[var(--fg-body)]">{t.phone}</td>
                    <td className="py-4 px-4">
                      <div className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-[var(--fill)] text-[var(--fg-body)] font-semibold">
                        <CellTower className="w-3.5 h-3.5 text-[var(--accent-text)]" />
                        <span>{t.routerCount}</span>
                      </div>
                    </td>
                    <td className="py-4 px-4">
                      <div className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-[var(--fill)] text-[var(--fg-body)] font-semibold">
                        <Ticket className="w-3.5 h-3.5 text-emerald-700" />
                        <span>{t.voucherCount}</span>
                      </div>
                    </td>
                    <td className="py-4 px-4">
                      <span
                        className={`inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[10px] font-semibold ${
                          t.status === "ACTIVE"
                            ? "bg-emerald-50 text-emerald-700 border border-emerald-200"
                            : t.status === "SUSPENDED"
                            ? "bg-amber-50 text-amber-700 border border-amber-200"
                            : "bg-red-50 text-red-600 border border-red-200"
                        }`}
                      >
                        {t.status === "ACTIVE" ? (
                          <CheckCircle className="w-3 h-3" />
                        ) : (
                          <XCircle className="w-3 h-3" />
                        )}
                        <span>{t.status}</span>
                      </span>
                    </td>
                    <td className="py-4 px-4 text-[var(--fg-muted)]">
                      {new Date(t.createdAt).toLocaleDateString()}
                    </td>
                    <td className="py-4 px-6 text-right relative">
                      <div className="inline-block text-left" ref={openMenuId === t.id ? menuRef : null}>
                        <button
                          type="button"
                          onClick={() => setOpenMenuId(openMenuId === t.id ? null : t.id)}
                          aria-label={`Actions for ${t.name}`}
                          className="p-1.5 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-[var(--fg-muted)] hover:text-[var(--fg)] hover:bg-[var(--fill)] transition cursor-pointer"
                        >
                          <DotsThreeVertical className="w-4 h-4" />
                        </button>

                        {openMenuId === t.id && (
                          <div className="absolute right-6 top-12 z-50 w-44 rounded-xl border border-[var(--border)] bg-[var(--surface)] py-1.5 shadow-[var(--shadow-overlay)] animate-in fade-in-0 zoom-in-95 text-left">
                            {t.status === "ACTIVE" ? (
                              <button
                                type="button"
                                onClick={() => handleUpdateStatus(t, "SUSPENDED")}
                                className="w-full flex items-center gap-2 px-3.5 py-2 text-xs font-medium text-amber-600 hover:bg-amber-500/10 transition cursor-pointer"
                              >
                                <Prohibit className="w-4 h-4 text-amber-500 shrink-0" />
                                <span>Suspend Operator</span>
                              </button>
                            ) : (
                              <button
                                type="button"
                                onClick={() => handleUpdateStatus(t, "ACTIVE")}
                                className="w-full flex items-center gap-2 px-3.5 py-2 text-xs font-medium text-emerald-600 hover:bg-emerald-500/10 transition cursor-pointer"
                              >
                                <Check className="w-4 h-4 text-emerald-500 shrink-0" />
                                <span>Activate Operator</span>
                              </button>
                            )}

                            <a
                              href={`mailto:${t.email}`}
                              onClick={() => setOpenMenuId(null)}
                              className="w-full flex items-center gap-2 px-3.5 py-2 text-xs font-medium text-[var(--fg-body)] hover:bg-[var(--fill)] transition cursor-pointer"
                            >
                              <EnvelopeSimple className="w-4 h-4 text-[var(--fg-muted)] shrink-0" />
                              <span>Email Operator</span>
                            </a>

                            <button
                              type="button"
                              onClick={() => handleCopyDetails(t)}
                              className="w-full flex items-center gap-2 px-3.5 py-2 text-xs font-medium text-[var(--fg-body)] hover:bg-[var(--fill)] transition cursor-pointer"
                            >
                              <Copy className="w-4 h-4 text-[var(--fg-muted)] shrink-0" />
                              <span>Copy Info</span>
                            </button>

                            <div className="my-1 border-t border-[var(--border)]" />

                            <button
                              type="button"
                              onClick={() => handleRequestDelete(t)}
                              className="w-full flex items-center gap-2 px-3.5 py-2 text-xs font-medium text-red-600 hover:bg-red-500/10 transition cursor-pointer"
                            >
                              <Trash className="w-4 h-4 text-red-500 shrink-0" />
                              <span>Decommission / Delete</span>
                            </button>
                          </div>
                        )}
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Modal: Create Operator */}
      {isModalOpen && (
        <Dialog
          title="Register hotspot operator"
          description="Create a secure dashboard account for a new business."
          onClose={closeCreateDialog}
        >
          <div className="mb-5 flex items-start gap-3 rounded-xl border border-blue-200 bg-blue-50 p-3.5 text-blue-900">
            <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-blue-100">
              <UserPlus className="h-4 w-4 text-blue-700" />
            </div>
            <div>
              <p className="text-xs font-semibold">Login details are handled automatically</p>
              <p className="mt-0.5 text-[11px] leading-5 text-blue-700">
                We’ll generate a temporary password, email it to the operator, and ask them to change it on first sign-in.
              </p>
            </div>
          </div>
            {formError && (
              <div className="mb-4 flex items-start gap-2 rounded-xl border border-red-200 bg-red-50 p-3 text-xs text-red-700">
                <WarningCircle className="w-4 h-4 shrink-0" />
                <span>{formError}</span>
              </div>
            )}

            <form onSubmit={handleCreateTenant} className="space-y-5">
              <div>
                <label htmlFor="operator-business" className="mb-1.5 block text-xs font-semibold text-[var(--fg-body)]">
                  Business name
                </label>
                <input
                  id="operator-business"
                  type="text"
                  required
                  autoFocus
                  maxLength={120}
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="e.g. Acacia Cafe & Lounge"
                  className="w-full rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] px-3.5 py-3 text-sm text-[var(--fg)] outline-none transition placeholder:text-[var(--fg-faint)] focus:border-blue-600 focus:ring-2 focus:ring-blue-600/15"
                />
              </div>

              <div>
                <label htmlFor="operator-email" className="mb-1.5 block text-xs font-semibold text-[var(--fg-body)]">
                  Operator email
                </label>
                <input
                  id="operator-email"
                  type="email"
                  required
                  autoComplete="email"
                  maxLength={120}
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="e.g. manager@acaciacafe.ug"
                  className="w-full rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] px-3.5 py-3 text-sm text-[var(--fg)] outline-none transition placeholder:text-[var(--fg-faint)] focus:border-blue-600 focus:ring-2 focus:ring-blue-600/15"
                />
              </div>

              <div>
                <label htmlFor="operator-phone" className="mb-1.5 block text-xs font-semibold text-[var(--fg-body)]">
                  Contact phone number
                </label>
                <input
                  id="operator-phone"
                  type="tel"
                  required
                  autoComplete="tel"
                  maxLength={30}
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                  placeholder="e.g. +256770000000"
                  className="w-full rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] px-3.5 py-3 text-sm text-[var(--fg)] outline-none transition placeholder:text-[var(--fg-faint)] focus:border-blue-600 focus:ring-2 focus:ring-blue-600/15"
                />
              </div>

              <div className="flex flex-col-reverse gap-2 border-t border-[var(--border)] pt-5 sm:flex-row sm:items-center sm:justify-end">
                <button
                  type="button"
                  onClick={closeCreateDialog}
                  disabled={formLoading}
                  className="rounded-xl border border-[var(--border)] px-4 py-2.5 text-xs font-semibold text-[var(--fg-body)] transition hover:bg-[var(--fill)] disabled:opacity-50"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={formLoading}
                  className="inline-flex min-w-48 items-center justify-center gap-2 rounded-xl bg-blue-700 px-5 py-2.5 text-xs font-semibold text-white shadow-sm transition hover:bg-blue-600 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {formLoading ? <><span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/40 border-t-white" />Creating account…</> : <><UserPlus className="h-4 w-4" />Create operator account</>}
                </button>
              </div>
            </form>
        </Dialog>
      )}
    </div>
  );
}
