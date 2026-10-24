"use client";

import React, { useEffect, useState } from "react";
import api from "@/lib/api";
import {
  Users,
  UserPlus,
  MagnifyingGlass,
  CheckCircle,
  XCircle,
  CellTower,
  Ticket,
  X,
  WarningCircle
} from "@phosphor-icons/react";

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

  // Form state
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [password, setPassword] = useState("");
  const [formLoading, setFormLoading] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const fetchTenants = async () => {
    try {
      setLoading(true);
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
      await api.post("/api/admin/tenants", {
        name: name.trim(),
        email: email.trim(),
        phone: phone.trim(),
        password: password,
      });

      setIsModalOpen(false);
      setName("");
      setEmail("");
      setPhone("");
      setPassword("");
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

  const handleToggleStatus = async (tenant: TenantSummary) => {
    const nextStatus = tenant.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE";
    try {
      await api.put(`/api/admin/tenants/${tenant.id}/status`, {
        status: nextStatus,
      });
      await fetchTenants();
    } catch (err) {
      console.error("Failed to update status:", err);
    }
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

      {/* Operators Table */}
      <div className="rounded-xl bg-[var(--surface)] border border-[var(--border)] overflow-hidden">
        <div className="overflow-x-auto">
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
                    <td className="py-4 px-6 text-right">
                      <button
                        onClick={() => handleToggleStatus(t)}
                        className={`px-3 py-1 rounded-lg text-xs font-medium border transition cursor-pointer ${
                          t.status === "ACTIVE"
                            ? "bg-red-50 text-red-600 border-red-200 hover:bg-red-500/20"
                            : "bg-emerald-50 text-emerald-700 border-emerald-200 hover:bg-emerald-500/20"
                        }`}
                      >
                        {t.status === "ACTIVE" ? "Suspend" : "Activate"}
                      </button>
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
        <div className="fixed inset-0 z-50 bg-[var(--canvas)]/80 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="w-full max-w-lg bg-[var(--surface)] border border-[var(--border)] rounded-xl p-6 shadow-sm relative">
            <button
              onClick={() => setIsModalOpen(false)}
              className="absolute right-5 top-5 text-[var(--fg-muted)] hover:text-[var(--fg)]"
            >
              <X className="w-5 h-5" />
            </button>

            <div className="mb-6">
              <h2 className="text-lg font-bold text-[var(--fg)] flex items-center gap-2">
                <UserPlus className="w-5 h-5 text-[var(--accent-text)]" />
                <span>Register Hotspot Operator</span>
              </h2>
              <p className="text-xs text-[var(--fg-muted)] mt-1">
                Creates a new business tenant and credentials for their dashboard.
              </p>
            </div>

            {formError && (
              <div className="mb-4 p-3 rounded-xl bg-red-50 border border-red-200 text-red-600 text-xs flex items-center gap-2">
                <WarningCircle className="w-4 h-4 shrink-0" />
                <span>{formError}</span>
              </div>
            )}

            <form onSubmit={handleCreateTenant} className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-[var(--fg-body)] mb-1">
                  Business / Company Name
                </label>
                <input
                  type="text"
                  required
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="e.g. Acacia Cafe & Lounge"
                  className="w-full px-3.5 py-2.5 rounded-xl bg-[var(--surface)] border border-[var(--border)] text-xs text-[var(--fg)] placeholder-[var(--fg-muted)] focus:outline-none focus:border-indigo-500 transition"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-[var(--fg-body)] mb-1">
                  Admin Email Address
                </label>
                <input
                  type="email"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="e.g. manager@acaciacafe.ug"
                  className="w-full px-3.5 py-2.5 rounded-xl bg-[var(--surface)] border border-[var(--border)] text-xs text-[var(--fg)] placeholder-[var(--fg-muted)] focus:outline-none focus:border-indigo-500 transition"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-[var(--fg-body)] mb-1">
                  Contact Phone Number
                </label>
                <input
                  type="tel"
                  required
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                  placeholder="e.g. +256770000000"
                  className="w-full px-3.5 py-2.5 rounded-xl bg-[var(--surface)] border border-[var(--border)] text-xs text-[var(--fg)] placeholder-[var(--fg-muted)] focus:outline-none focus:border-indigo-500 transition"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-[var(--fg-body)] mb-1">
                  Initial Password
                </label>
                <input
                  type="password"
                  required
                  minLength={6}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Minimum 6 characters"
                  className="w-full px-3.5 py-2.5 rounded-xl bg-[var(--surface)] border border-[var(--border)] text-xs text-[var(--fg)] placeholder-[var(--fg-muted)] focus:outline-none focus:border-indigo-500 transition"
                />
              </div>

              <div className="flex items-center justify-end gap-3 pt-3 border-t border-[var(--border)]">
                <button
                  type="button"
                  onClick={() => setIsModalOpen(false)}
                  className="px-4 py-2 rounded-xl text-xs font-medium text-[var(--fg-muted)] hover:text-[var(--fg)] transition cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={formLoading}
                  className="px-4 py-2 rounded-xl bg-blue-700 hover:bg-blue-600 disabled:opacity-50 text-xs font-semibold text-white shadow-sm transition cursor-pointer"
                >
                  {formLoading ? "Creating Operator..." : "Create Operator Account"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
