"use client";

import React, { useEffect, useState } from "react";
import api from "@/lib/api";
import { QRCodeSVG } from "qrcode.react";
import { Dialog } from "@/components/ui/Dialog";
import { useConfirmDialog } from "@/components/ui/ConfirmDialog";
import {
  GearSix,
  User,
  ShieldCheck,
  Globe,
  Bell,
  HardDrives,
  Lock,
  EnvelopeSimple,
  Phone,
  CheckCircle,
  XCircle,
  WarningCircle,
  Key,
  Copy,
  Check,
  Desktop,
  Trash,
  SignOut,
  Eye,
  EyeSlash,
  Clock,
  Building2
} from "@/components/icons";
import { FormSkeleton, BusyLabel } from "@/components/LoadingUI";
import { date } from "@/components/BillingUI";

interface Profile {
  id: number;
  name: string;
  email: string;
  phone: string;
  role: string;
  status: string;
  totpEnabled: boolean;
  createdAt: string;
  lastLoginAt: string | null;
  lastLoginIp: string | null;
}

interface SessionItem {
  sessionId: string;
  ipAddress: string | null;
  userAgent: string | null;
  current: boolean;
  createdAt: string;
  lastActiveAt: string;
}

interface AuditItem {
  id: number;
  action: string;
  actorEmail: string;
  ipAddress: string;
  details: string;
  createdAt: string;
}

type TabKey = "account" | "security" | "platform" | "communication" | "audit";

export default function SuperAdminSettingsPage() {
  const [activeTab, setActiveTab] = useState<TabKey>("account");
  const [loading, setLoading] = useState(true);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [sessions, setSessions] = useState<SessionItem[]>([]);
  const [auditLogs, setAuditLogs] = useState<AuditItem[]>([]);
  const [platformSettings, setPlatformSettings] = useState<Record<string, string>>({});

  const [feedback, setFeedback] = useState<{ type: "success" | "error"; message: string } | null>(null);
  const { confirm, dialog } = useConfirmDialog();

  // Modals
  const [isEmailModalOpen, setIsEmailModalOpen] = useState(false);
  const [isPhoneModalOpen, setIsPhoneModalOpen] = useState(false);
  const [isPasswordModalOpen, setIsPasswordModalOpen] = useState(false);
  const [is2faModalOpen, setIs2faModalOpen] = useState(false);
  const [isDisable2faModalOpen, setIsDisable2faModalOpen] = useState(false);

  // Profile Form
  const [editName, setEditName] = useState("");
  const [nameSaving, setNameSaving] = useState(false);

  // Email Change Form
  const [newEmail, setNewEmail] = useState("");
  const [emailCurrentPassword, setEmailCurrentPassword] = useState("");
  const [emailStep, setEmailStep] = useState<"request" | "verify">("request");
  const [currentEmailCode, setCurrentEmailCode] = useState("");
  const [newEmailCode, setNewEmailCode] = useState("");
  const [emailBusy, setEmailBusy] = useState(false);

  // Phone Change Form
  const [newPhone, setNewPhone] = useState("");
  const [phoneCurrentPassword, setPhoneCurrentPassword] = useState("");
  const [phoneStep, setPhoneStep] = useState<"request" | "verify">("request");
  const [phoneCode, setPhoneCode] = useState("");
  const [phoneBusy, setPhoneBusy] = useState(false);

  // Password Change Form
  const [oldPassword, setOldPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [passwordBusy, setPasswordBusy] = useState(false);
  const [showPw, setShowPw] = useState(false);

  // 2FA Setup
  const [totpSetupData, setTotpSetupData] = useState<{ secret: string; qrCodeUri: string; manualEntryKey: string } | null>(null);
  const [totpVerifyCode, setTotpVerifyCode] = useState("");
  const [totpCurrentPassword, setTotpCurrentPassword] = useState("");
  const [totpBusy, setTotpBusy] = useState(false);
  const [newRecoveryCodes, setNewRecoveryCodes] = useState<string[] | null>(null);
  const [copiedKey, setCopiedKey] = useState(false);

  // Disable 2FA Form
  const [disablePw, setDisablePw] = useState("");
  const [disableCode, setDisableCode] = useState("");
  const [disableBusy, setDisableBusy] = useState(false);

  // Platform Settings State
  const [platformSaving, setPlatformSaving] = useState(false);

  const fetchData = async () => {
    try {
      setLoading(true);
      const [profRes, sessRes, platRes, auditRes] = await Promise.all([
        api.get<Profile>("/api/admin/settings/profile"),
        api.get<SessionItem[]>("/api/admin/settings/sessions"),
        api.get<Record<string, string>>("/api/admin/settings/platform"),
        api.get<AuditItem[]>("/api/admin/settings/audit-logs"),
      ]);

      setProfile(profRes.data);
      setEditName(profRes.data.name);
      setSessions(sessRes.data);
      setPlatformSettings(platRes.data);
      setAuditLogs(auditRes.data);
    } catch (err: unknown) {
      console.error("Failed to load admin settings:", err);
      setFeedback({ type: "error", message: "Failed to load administrative settings." });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, []);

  // Update Profile Name
  const handleSaveProfile = async (e: React.FormEvent) => {
    e.preventDefault();
    setNameSaving(true);
    setFeedback(null);
    try {
      const res = await api.put<Profile>("/api/admin/settings/profile", { name: editName });
      setProfile(res.data);
      setFeedback({ type: "success", message: "Account profile updated successfully." });
    } catch (err: unknown) {
      const msg = err && typeof err === "object" && "response" in err
        ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
        : "Failed to update profile";
      setFeedback({ type: "error", message: msg || "Failed to update profile." });
    } finally {
      setNameSaving(false);
    }
  };

  // Email Change
  const handleRequestEmailChange = async (e: React.FormEvent) => {
    e.preventDefault();
    setEmailBusy(true);
    setFeedback(null);
    try {
      await api.post("/api/admin/settings/email/request", {
        currentPassword: emailCurrentPassword,
        newEmail: newEmail.trim(),
      });
      setEmailStep("verify");
      setFeedback({ type: "success", message: "Verification codes dispatched to current and new emails." });
    } catch (err: unknown) {
      const msg = err && typeof err === "object" && "response" in err
        ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
        : "Email change request failed";
      setFeedback({ type: "error", message: msg || "Verification request failed." });
    } finally {
      setEmailBusy(false);
    }
  };

  const handleVerifyEmailChange = async (e: React.FormEvent) => {
    e.preventDefault();
    setEmailBusy(true);
    setFeedback(null);
    try {
      const res = await api.post<Profile>("/api/admin/settings/email/verify", {
        currentEmailCode: currentEmailCode.trim(),
        newEmailCode: newEmailCode.trim(),
      });
      setProfile(res.data);
      setIsEmailModalOpen(false);
      setEmailStep("request");
      setNewEmail("");
      setEmailCurrentPassword("");
      setCurrentEmailCode("");
      setNewEmailCode("");
      setFeedback({ type: "success", message: "Your registered email has been securely updated." });
    } catch (err: unknown) {
      const msg = err && typeof err === "object" && "response" in err
        ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
        : "Verification failed";
      setFeedback({ type: "error", message: msg || "Verification code mismatch." });
    } finally {
      setEmailBusy(false);
    }
  };

  // Phone Change
  const handleRequestPhoneChange = async (e: React.FormEvent) => {
    e.preventDefault();
    setPhoneBusy(true);
    setFeedback(null);
    try {
      await api.post("/api/admin/settings/phone/request", {
        currentPassword: phoneCurrentPassword,
        newPhone: newPhone.trim(),
      });
      setPhoneStep("verify");
      setFeedback({ type: "success", message: "Verification code sent to registered account contact." });
    } catch (err: unknown) {
      const msg = err && typeof err === "object" && "response" in err
        ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
        : "Phone update request failed";
      setFeedback({ type: "error", message: msg || "Phone update request failed." });
    } finally {
      setPhoneBusy(false);
    }
  };

  const handleVerifyPhoneChange = async (e: React.FormEvent) => {
    e.preventDefault();
    setPhoneBusy(true);
    setFeedback(null);
    try {
      const res = await api.post<Profile>("/api/admin/settings/phone/verify", {
        code: phoneCode.trim(),
      });
      setProfile(res.data);
      setIsPhoneModalOpen(false);
      setPhoneStep("request");
      setNewPhone("");
      setPhoneCurrentPassword("");
      setPhoneCode("");
      setFeedback({ type: "success", message: "Phone number updated successfully." });
    } catch (err: unknown) {
      const msg = err && typeof err === "object" && "response" in err
        ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
        : "Phone verification failed";
      setFeedback({ type: "error", message: msg || "Verification code mismatch." });
    } finally {
      setPhoneBusy(false);
    }
  };

  // Password Change
  const handleChangePassword = async (e: React.FormEvent) => {
    e.preventDefault();
    if (newPassword !== confirmPassword) {
      setFeedback({ type: "error", message: "New password and confirmation do not match." });
      return;
    }
    setPasswordBusy(true);
    setFeedback(null);
    try {
      await api.post("/api/auth/change-password", {
        currentPassword: oldPassword,
        newPassword: newPassword,
      });
      setIsPasswordModalOpen(false);
      setOldPassword("");
      setNewPassword("");
      setConfirmPassword("");
      setFeedback({ type: "success", message: "Password updated successfully. Other sessions have been revoked." });
      void fetchData();
    } catch (err: unknown) {
      const msg = err && typeof err === "object" && "response" in err
        ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
        : "Failed to change password";
      setFeedback({ type: "error", message: msg || "Password change rejected." });
    } finally {
      setPasswordBusy(false);
    }
  };

  // 2FA Setup
  const handleOpen2faSetup = async () => {
    setTotpBusy(true);
    setFeedback(null);
    try {
      const res = await api.post<{ secret: string; qrCodeUri: string; manualEntryKey: string }>("/api/admin/settings/2fa/setup");
      setTotpSetupData(res.data);
      setIs2faModalOpen(true);
    } catch (err: unknown) {
      console.error(err);
      setFeedback({ type: "error", message: "Failed to initiate 2FA setup." });
    } finally {
      setTotpBusy(false);
    }
  };

  const handleActivate2fa = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!totpSetupData) return;
    setTotpBusy(true);
    setFeedback(null);
    try {
      const res = await api.post<{ recoveryCodes: string[]; message: string }>("/api/admin/settings/2fa/activate", {
        currentPassword: totpCurrentPassword,
        secret: totpSetupData.secret,
        code: totpVerifyCode.trim(),
      });
      setNewRecoveryCodes(res.data.recoveryCodes);
      setProfile((prev) => (prev ? { ...prev, totpEnabled: true } : null));
      setFeedback({ type: "success", message: "Two-Factor Authentication is now active on your account." });
      void fetchData();
    } catch (err: unknown) {
      const msg = err && typeof err === "object" && "response" in err
        ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
        : "Failed to activate 2FA";
      setFeedback({ type: "error", message: msg || "Invalid authenticator code or password." });
    } finally {
      setTotpBusy(false);
    }
  };

  // Disable 2FA
  const handleDisable2fa = async (e: React.FormEvent) => {
    e.preventDefault();
    setDisableBusy(true);
    setFeedback(null);
    try {
      await api.post("/api/admin/settings/2fa/disable", {
        currentPassword: disablePw,
        code: disableCode.trim(),
      });
      setIsDisable2faModalOpen(false);
      setDisablePw("");
      setDisableCode("");
      setProfile((prev) => (prev ? { ...prev, totpEnabled: false } : null));
      setFeedback({ type: "success", message: "Two-Factor Authentication has been disabled." });
      void fetchData();
    } catch (err: unknown) {
      const msg = err && typeof err === "object" && "response" in err
        ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
        : "Failed to disable 2FA";
      setFeedback({ type: "error", message: msg || "Invalid code or password." });
    } finally {
      setDisableBusy(false);
    }
  };

  // Sessions
  const handleRevokeSession = (sessionId: string) => {
    confirm({
      title: "Revoke Session?",
      body: "This device will be immediately signed out and its token invalidated.",
      confirmLabel: "Revoke Session",
      onConfirm: async () => {
        try {
          await api.delete(`/api/admin/settings/sessions/${sessionId}`);
          setSessions((prev) => prev.filter((s) => s.sessionId !== sessionId));
          setFeedback({ type: "success", message: "Session revoked successfully." });
        } catch {
          setFeedback({ type: "error", message: "Failed to revoke session." });
        }
      },
    });
  };

  const handleRevokeAllOtherSessions = () => {
    confirm({
      title: "Sign out all other devices?",
      body: "All other active sessions on phones, laptops, and tablets will be terminated.",
      confirmLabel: "Sign Out Others",
      onConfirm: async () => {
        try {
          await api.post("/api/admin/settings/sessions/revoke-others");
          setSessions((prev) => prev.filter((s) => s.current));
          setFeedback({ type: "success", message: "All other devices signed out." });
        } catch {
          setFeedback({ type: "error", message: "Failed to revoke other sessions." });
        }
      },
    });
  };

  // Save Platform Settings
  const handleSavePlatformSettings = async (e: React.FormEvent) => {
    e.preventDefault();
    setPlatformSaving(true);
    setFeedback(null);
    try {
      const res = await api.put<Record<string, string>>("/api/admin/settings/platform", platformSettings);
      setPlatformSettings(res.data);
      setFeedback({ type: "success", message: "Platform settings saved successfully." });
    } catch {
      setFeedback({ type: "error", message: "Failed to update platform settings." });
    } finally {
      setPlatformSaving(false);
    }
  };

  const tabs: { key: TabKey; label: string; icon: React.ComponentType<{ className?: string }> }[] = [
    { key: "account", label: "Account Profile", icon: User },
    { key: "security", label: "Account Security & 2FA", icon: ShieldCheck },
    { key: "platform", label: "Platform & Business", icon: Globe },
    { key: "communication", label: "Communication & Alerts", icon: Bell },
    { key: "audit", label: "Security Audit Logs", icon: HardDrives },
  ];

  return (
    <div className="space-y-6 max-w-6xl mx-auto pb-12">
      {dialog}

      {/* Header */}
      <div>
        <h1 className="text-[1.65rem] font-bold leading-tight tracking-[-0.02em] text-[var(--fg)] flex items-center gap-2.5">
          <GearSix className="w-6 h-6 text-[var(--accent-text)]" />
          <span>Super Admin Settings & Governance</span>
        </h1>
        <p className="text-xs text-[var(--fg-muted)] mt-1">
          Manage your platform owner credentials, two-factor authentication, active sessions, and global billing configurations.
        </p>
      </div>

      {/* Alert feedback */}
      {feedback && (
        <div
          className={`p-3.5 rounded-xl border flex items-start justify-between gap-3 text-xs ${
            feedback.type === "success"
              ? "bg-emerald-50 border-emerald-200 text-emerald-800"
              : "bg-red-50 border-red-200 text-red-700"
          }`}
        >
          <div className="flex items-center gap-2">
            {feedback.type === "success" ? <CheckCircle className="w-4 h-4 text-emerald-600 shrink-0" /> : <WarningCircle className="w-4 h-4 text-red-600 shrink-0" />}
            <span>{feedback.message}</span>
          </div>
          <button type="button" onClick={() => setFeedback(null)} className="font-bold hover:opacity-75">✕</button>
        </div>
      )}

      {/* Navigation Tabs */}
      <div className="flex items-center gap-2 border-b border-[var(--border)] pb-2 overflow-x-auto">
        {tabs.map((tab) => {
          const Icon = tab.icon;
          const isActive = activeTab === tab.key;
          return (
            <button
              key={tab.key}
              onClick={() => setActiveTab(tab.key)}
              className={`flex items-center gap-2 px-3.5 py-2 max-sm:min-h-11 rounded-xl text-xs font-semibold transition cursor-pointer whitespace-nowrap ${
                isActive
                  ? "bg-blue-700 text-white shadow-sm"
                  : "text-[var(--fg-muted)] hover:bg-[var(--fill)] hover:text-[var(--fg)]"
              }`}
            >
              <Icon className="w-4 h-4" />
              <span>{tab.label}</span>
            </button>
          );
        })}
      </div>

      {loading ? (
        <div className="rounded-xl border border-[var(--border)] bg-[var(--surface)] p-6" aria-busy="true">
          <span className="sr-only" role="status">Loading administrative settings…</span>
          <FormSkeleton fields={4} />
        </div>
      ) : (
        <>
          {/* TAB 1: ACCOUNT PROFILE */}
          {activeTab === "account" && profile && (
            <div className="space-y-6">
              <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl p-6 shadow-sm">
                <h2 className="text-sm font-semibold text-[var(--fg)] mb-4 flex items-center gap-2">
                  <User className="w-4 h-4 text-blue-600" />
                  <span>Personal Information</span>
                </h2>

                <form onSubmit={handleSaveProfile} className="space-y-4 max-w-xl">
                  <div>
                    <label className="block text-xs font-medium text-[var(--fg-body)] mb-1">Full Name</label>
                    <input
                      type="text"
                      required
                      value={editName}
                      onChange={(e) => setEditName(e.target.value)}
                      className="w-full px-3.5 py-2.5 rounded-xl bg-[var(--surface)] border border-[var(--border)] text-xs text-[var(--fg)] focus:outline-none focus:border-blue-700 transition"
                    />
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-2">
                    <div>
                      <span className="block text-[11px] text-[var(--fg-muted)]">System Role</span>
                      <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-blue-50 text-blue-700 text-xs font-semibold mt-1">
                        <ShieldCheck className="w-3.5 h-3.5" />
                        {profile.role}
                      </span>
                    </div>

                    <div>
                      <span className="block text-[11px] text-[var(--fg-muted)]">Account Status</span>
                      <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-emerald-50 text-emerald-700 text-xs font-semibold mt-1">
                        <CheckCircle className="w-3.5 h-3.5" />
                        {profile.status}
                      </span>
                    </div>
                  </div>

                  <div className="pt-4">
                    <button
                      type="submit"
                      disabled={nameSaving || editName.trim() === profile.name}
                      className="px-4 py-2 rounded-xl bg-blue-700 hover:bg-blue-600 disabled:opacity-40 text-white text-xs font-semibold shadow-sm transition cursor-pointer"
                    >
                      <BusyLabel busy={nameSaving} label="Saving…">Save Changes</BusyLabel>
                    </button>
                  </div>
                </form>
              </div>

              {/* Verified Email & Phone cards */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                {/* Email Address */}
                <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl p-6 shadow-sm flex flex-col justify-between">
                  <div>
                    <div className="flex items-center justify-between mb-2">
                      <h3 className="text-xs font-semibold text-[var(--fg)] flex items-center gap-2">
                        <EnvelopeSimple className="w-4 h-4 text-blue-600" />
                        <span>Registered Email</span>
                      </h3>
                      <span className="text-[10px] bg-emerald-50 text-emerald-700 font-semibold px-2 py-0.5 rounded-full border border-emerald-200">
                        Verified Primary
                      </span>
                    </div>
                    <p className="text-sm font-semibold text-[var(--fg)] mt-2 font-mono">{profile.email}</p>
                    <p className="text-[11px] text-[var(--fg-muted)] mt-1">
                      Used for authentication, two-factor notifications, and emergency recovery.
                    </p>
                  </div>

                  <div className="pt-6">
                    <button
                      type="button"
                      onClick={() => {
                        setEmailStep("request");
                        setIsEmailModalOpen(true);
                      }}
                      className="px-3.5 py-2 rounded-xl border border-[var(--border)] text-xs font-semibold hover:bg-[var(--fill)] text-[var(--fg)] transition cursor-pointer"
                    >
                      Change Email Address
                    </button>
                  </div>
                </div>

                {/* Phone Number */}
                <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl p-6 shadow-sm flex flex-col justify-between">
                  <div>
                    <div className="flex items-center justify-between mb-2">
                      <h3 className="text-xs font-semibold text-[var(--fg)] flex items-center gap-2">
                        <Phone className="w-4 h-4 text-blue-600" />
                        <span>Security Contact Phone</span>
                      </h3>
                      <span className="text-[10px] bg-emerald-50 text-emerald-700 font-semibold px-2 py-0.5 rounded-full border border-emerald-200">
                        Registered
                      </span>
                    </div>
                    <p className="text-sm font-semibold text-[var(--fg)] mt-2 font-mono">{profile.phone || "No phone configured"}</p>
                    <p className="text-[11px] text-[var(--fg-muted)] mt-1">
                      International format with E.164 country code for urgent system alerts.
                    </p>
                  </div>

                  <div className="pt-6">
                    <button
                      type="button"
                      onClick={() => {
                        setPhoneStep("request");
                        setIsPhoneModalOpen(true);
                      }}
                      className="px-3.5 py-2 rounded-xl border border-[var(--border)] text-xs font-semibold hover:bg-[var(--fill)] text-[var(--fg)] transition cursor-pointer"
                    >
                      Update Phone Number
                    </button>
                  </div>
                </div>
              </div>

              {/* Password Section */}
              <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl p-6 shadow-sm flex items-center justify-between">
                <div>
                  <h3 className="text-xs font-semibold text-[var(--fg)] flex items-center gap-2">
                    <Lock className="w-4 h-4 text-blue-600" />
                    <span>Account Password</span>
                  </h3>
                  <p className="text-xs text-[var(--fg-muted)] mt-1">
                    Keep your account secure with a strong password. Changing it will sign out all other devices.
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => setIsPasswordModalOpen(true)}
                  className="px-3.5 py-2 rounded-xl bg-blue-700 hover:bg-blue-600 text-white text-xs font-semibold shadow-sm transition cursor-pointer shrink-0"
                >
                  Change Password
                </button>
              </div>
            </div>
          )}

          {/* TAB 2: SECURITY & 2FA */}
          {activeTab === "security" && profile && (
            <div className="space-y-6">
              {/* 2FA Card */}
              <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl p-6 shadow-sm">
                <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
                  <div>
                    <div className="flex items-center gap-2.5">
                      <ShieldCheck className="w-5 h-5 text-blue-600" />
                      <h2 className="text-sm font-semibold text-[var(--fg)]">Two-Factor Authentication (TOTP)</h2>
                      <span
                        className={`text-[10px] font-semibold px-2.5 py-0.5 rounded-full border ${
                          profile.totpEnabled
                            ? "bg-emerald-50 text-emerald-700 border-emerald-200"
                            : "bg-amber-50 text-amber-700 border-amber-200"
                        }`}
                      >
                        {profile.totpEnabled ? "Active / Enabled" : "Disabled"}
                      </span>
                    </div>
                    <p className="text-xs text-[var(--fg-muted)] mt-2 max-w-2xl leading-relaxed">
                      Protect your platform superadmin privileges with standard RFC 6238 time-based passcodes. Compatible with Google Authenticator, Microsoft Authenticator, Apple Passwords, and 1Password.
                    </p>
                  </div>

                  <div>
                    {profile.totpEnabled ? (
                      <button
                        type="button"
                        onClick={() => setIsDisable2faModalOpen(true)}
                        className="px-4 py-2 rounded-xl border border-red-200 bg-red-50 hover:bg-red-100 text-red-700 text-xs font-semibold transition cursor-pointer"
                      >
                        Disable 2FA
                      </button>
                    ) : (
                      <button
                        type="button"
                        onClick={handleOpen2faSetup}
                        className="px-4 py-2 rounded-xl bg-blue-700 hover:bg-blue-600 text-white text-xs font-semibold shadow-sm transition cursor-pointer"
                      >
                        Enable Two-Factor
                      </button>
                    )}
                  </div>
                </div>
              </div>

              {/* Active Sessions */}
              <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl p-6 shadow-sm">
                <div className="flex items-center justify-between mb-4">
                  <div>
                    <h2 className="text-sm font-semibold text-[var(--fg)] flex items-center gap-2">
                      <Desktop className="w-4 h-4 text-blue-600" />
                      <span>Active Login Sessions</span>
                    </h2>
                    <p className="text-xs text-[var(--fg-muted)] mt-0.5">
                      Devices currently authorized to access the Superadmin command center.
                    </p>
                  </div>

                  {sessions.length > 1 && (
                    <button
                      type="button"
                      onClick={handleRevokeAllOtherSessions}
                      className="px-3 py-1.5 rounded-xl border border-red-200 text-red-600 hover:bg-red-50 text-xs font-semibold transition cursor-pointer flex items-center gap-1.5"
                    >
                      <SignOut className="w-3.5 h-3.5" />
                      <span>Sign Out Other Devices</span>
                    </button>
                  )}
                </div>

                <div className="divide-y divide-[var(--border)]">
                  {sessions.map((sess) => (
                    <div key={sess.sessionId} className="py-3 flex items-center justify-between">
                      <div className="flex items-start gap-3">
                        <div className="p-2 rounded-xl bg-[var(--fill)] text-[var(--fg-muted)] mt-0.5">
                          <Desktop className="w-4 h-4" />
                        </div>
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="text-xs font-semibold text-[var(--fg)]">
                              {sess.ipAddress || "Unknown IP"}
                            </span>
                            {sess.current && (
                              <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-emerald-50 text-emerald-700 border border-emerald-200">
                                This Device (Current)
                              </span>
                            )}
                          </div>
                          <p className="text-[11px] text-[var(--fg-muted)] truncate max-w-md mt-0.5">
                            {sess.userAgent || "Browser session"}
                          </p>
                          <span className="text-[10px] text-[var(--fg-muted)] flex items-center gap-1 mt-1">
                            <Clock className="w-3 h-3" />
                            Last active: {date(sess.lastActiveAt)}
                          </span>
                        </div>
                      </div>

                      {!sess.current && (
                        <button
                          type="button"
                          onClick={() => handleRevokeSession(sess.sessionId)}
                          className="p-2 rounded-lg text-red-600 hover:bg-red-50 transition cursor-pointer"
                          title="Revoke session"
                        >
                          <Trash className="w-4 h-4" />
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}

          {/* TAB 3: PLATFORM & BUSINESS */}
          {activeTab === "platform" && (
            <form onSubmit={handleSavePlatformSettings} className="space-y-6">
              <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl p-6 shadow-sm space-y-4">
                <h2 className="text-sm font-semibold text-[var(--fg)] flex items-center gap-2 mb-2">
                  <Building2 className="w-4 h-4 text-blue-600" />
                  <span>Platform Identity & Branding</span>
                </h2>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div>
                    <label className="block text-xs font-medium text-[var(--fg-body)] mb-1">Platform Brand Name</label>
                    <input
                      type="text"
                      value={platformSettings.platform_name || "AirPairy"}
                      onChange={(e) => setPlatformSettings({ ...platformSettings, platform_name: e.target.value })}
                      className="w-full px-3.5 py-2.5 rounded-xl bg-[var(--surface)] border border-[var(--border)] text-xs text-[var(--fg)] focus:outline-none focus:border-blue-700"
                    />
                  </div>

                  <div>
                    <label className="block text-xs font-medium text-[var(--fg-body)] mb-1">Parent Brand Attribution</label>
                    <input
                      type="text"
                      disabled
                      value="A product by Thewton."
                      className="w-full px-3.5 py-2.5 rounded-xl bg-[var(--fill)] border border-[var(--border)] text-xs text-[var(--fg-muted)] cursor-not-allowed"
                    />
                  </div>

                  <div className="sm:col-span-2">
                    <label className="block text-xs font-medium text-[var(--fg-body)] mb-1">Platform Tagline</label>
                    <input
                      type="text"
                      value={platformSettings.tagline || "Connecting WiFi. Simplifying Business."}
                      onChange={(e) => setPlatformSettings({ ...platformSettings, tagline: e.target.value })}
                      className="w-full px-3.5 py-2.5 rounded-xl bg-[var(--surface)] border border-[var(--border)] text-xs text-[var(--fg)] focus:outline-none focus:border-blue-700"
                    />
                  </div>
                </div>
              </div>

              <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl p-6 shadow-sm space-y-4">
                <h2 className="text-sm font-semibold text-[var(--fg)] flex items-center gap-2 mb-2">
                  <Globe className="w-4 h-4 text-blue-600" />
                  <span>Default Subscription & Financial Rules</span>
                </h2>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div>
                    <label className="block text-xs font-medium text-[var(--fg-body)] mb-1">Default Monthly Subscription Fee (UGX)</label>
                    <input
                      type="number"
                      value={platformSettings.default_subscription_fee || "50000"}
                      onChange={(e) => setPlatformSettings({ ...platformSettings, default_subscription_fee: e.target.value })}
                      className="w-full px-3.5 py-2.5 rounded-xl bg-[var(--surface)] border border-[var(--border)] text-xs text-[var(--fg)] focus:outline-none focus:border-blue-700"
                    />
                    <p className="text-[10px] text-[var(--fg-muted)] mt-1">Applied to newly provisioned hotspot operators.</p>
                  </div>

                  <div>
                    <label className="block text-xs font-medium text-[var(--fg-body)] mb-1">Platform Currency</label>
                    <input
                      type="text"
                      disabled
                      value="UGX (Ugandan Shilling)"
                      className="w-full px-3.5 py-2.5 rounded-xl bg-[var(--fill)] border border-[var(--border)] text-xs text-[var(--fg-muted)] cursor-not-allowed"
                    />
                  </div>
                </div>
              </div>

              <button
                type="submit"
                disabled={platformSaving}
                className="px-5 py-2.5 rounded-xl bg-blue-700 hover:bg-blue-600 text-white text-xs font-semibold shadow-sm transition cursor-pointer"
              >
                <BusyLabel busy={platformSaving} label="Saving Configuration…">Save Platform Settings</BusyLabel>
              </button>
            </form>
          )}

          {/* TAB 4: COMMUNICATION & ALERTS */}
          {activeTab === "communication" && (
            <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl p-6 shadow-sm space-y-6">
              <h2 className="text-sm font-semibold text-[var(--fg)] flex items-center gap-2">
                <Bell className="w-4 h-4 text-blue-600" />
                <span>Superadmin Security & System Notifications</span>
              </h2>

              <div className="space-y-4">
                <div className="flex items-center justify-between py-3 border-b border-[var(--border)]">
                  <div>
                    <p className="text-xs font-semibold text-[var(--fg)]">Security & Authentication Alerts</p>
                    <p className="text-[11px] text-[var(--fg-muted)]">Instant alerts on password updates, 2FA changes, or unexpected logins.</p>
                  </div>
                  <span className="text-[10px] font-semibold px-2.5 py-0.5 rounded-full bg-emerald-50 text-emerald-700 border border-emerald-200">
                    Always Enforced
                  </span>
                </div>

                <div className="flex items-center justify-between py-3 border-b border-[var(--border)]">
                  <div>
                    <p className="text-xs font-semibold text-[var(--fg)]">New Hotspot Tenant Registration</p>
                    <p className="text-[11px] text-[var(--fg-muted)]">Receive an email whenever a new hotspot business operator is provisioned.</p>
                  </div>
                  <input
                    type="checkbox"
                    checked={platformSettings.new_tenant_alerts_enabled !== "false"}
                    onChange={(e) => setPlatformSettings({ ...platformSettings, new_tenant_alerts_enabled: e.target.checked ? "true" : "false" })}
                    className="w-4 h-4 rounded text-blue-600 focus:ring-blue-500 cursor-pointer"
                  />
                </div>

                <div className="flex items-center justify-between py-3">
                  <div>
                    <p className="text-xs font-semibold text-[var(--fg)]">Router Fleet Health Notifications</p>
                    <p className="text-[11px] text-[var(--fg-muted)]">Alert when a provisioned router fails telemetry or goes offline.</p>
                  </div>
                  <input
                    type="checkbox"
                    checked={platformSettings.router_offline_alerts_enabled !== "false"}
                    onChange={(e) => setPlatformSettings({ ...platformSettings, router_offline_alerts_enabled: e.target.checked ? "true" : "false" })}
                    className="w-4 h-4 rounded text-blue-600 focus:ring-blue-500 cursor-pointer"
                  />
                </div>
              </div>
            </div>
          )}

          {/* TAB 5: AUDIT LOGS */}
          {activeTab === "audit" && (
            <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl overflow-hidden shadow-sm">
              <div className="p-4 border-b border-[var(--border)] bg-[var(--subtle)]">
                <h2 className="text-xs font-semibold text-[var(--fg)] flex items-center gap-2">
                  <HardDrives className="w-4 h-4 text-blue-600" />
                  <span>Security & Administrative Activity History</span>
                </h2>
              </div>

              <div className="overflow-x-auto">
                <table className="stack-table w-full text-left text-xs">
                  <thead>
                    <tr className="border-b border-[var(--border)] text-[var(--fg-muted)]">
                      <th className="py-3 px-4 font-semibold">Action</th>
                      <th className="py-3 px-4 font-semibold">Details</th>
                      <th className="py-3 px-4 font-semibold">IP Address</th>
                      <th className="py-3 px-4 font-semibold">Timestamp</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-[var(--border)]">
                    {auditLogs.length === 0 ? (
                      <tr>
                        <td colSpan={4} className="py-8 text-center text-[var(--fg-muted)]">
                          No security events recorded.
                        </td>
                      </tr>
                    ) : (
                      auditLogs.map((log) => (
                        <tr key={log.id} className="hover:bg-[var(--subtle)] transition">
                          <td className="py-3 px-4 font-mono font-semibold text-blue-600">
                            {log.action}
                          </td>
                          <td data-label="Details" className="py-3 px-4 text-[var(--fg-body)] max-w-md">
                            {log.details}
                          </td>
                          <td data-label="IP address" className="py-3 px-4 font-mono text-[var(--fg-muted)]">
                            {log.ipAddress || "—"}
                          </td>
                          <td data-label="Timestamp" className="py-3 px-4 text-[var(--fg-muted)]">
                            {date(log.createdAt)}
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </>
      )}

      {/* MODAL: CHANGE EMAIL */}
      {isEmailModalOpen && (
        <Dialog
          title="Change Registered Email"
          description="Update your primary Superadmin email address through dual verification."
          onClose={() => !emailBusy && setIsEmailModalOpen(false)}
        >
          {emailStep === "request" ? (
            <form onSubmit={handleRequestEmailChange} className="space-y-4">
              <div>
                <label className="block text-xs font-semibold text-[var(--fg-body)] mb-1">New Email Address</label>
                <input
                  type="email"
                  required
                  value={newEmail}
                  onChange={(e) => setNewEmail(e.target.value)}
                  placeholder="newadmin@airpairy.com"
                  className="w-full px-3.5 py-2.5 rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] text-xs text-[var(--fg)] outline-none focus:border-blue-600"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-[var(--fg-body)] mb-1">Current Account Password</label>
                <input
                  type="password"
                  required
                  value={emailCurrentPassword}
                  onChange={(e) => setEmailCurrentPassword(e.target.value)}
                  placeholder="••••••••••••"
                  className="w-full px-3.5 py-2.5 rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] text-xs text-[var(--fg)] outline-none focus:border-blue-600"
                />
              </div>

              <div className="flex justify-end gap-2 pt-3">
                <button
                  type="button"
                  onClick={() => setIsEmailModalOpen(false)}
                  className="px-4 py-2 rounded-xl border border-[var(--border)] text-xs font-semibold text-[var(--fg-body)]"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={emailBusy}
                  className="px-4 py-2 rounded-xl bg-blue-700 hover:bg-blue-600 text-white text-xs font-semibold"
                >
                  <BusyLabel busy={emailBusy} label="Sending verification…">Dispatch Verification Codes</BusyLabel>
                </button>
              </div>
            </form>
          ) : (
            <form onSubmit={handleVerifyEmailChange} className="space-y-4">
              <div className="p-3 bg-blue-50 border border-blue-200 rounded-xl text-xs text-blue-900">
                Codes were dispatched to <b>{profile?.email}</b> and <b>{newEmail}</b>. Enter both codes to complete the change.
              </div>

              <div>
                <label className="block text-xs font-semibold text-[var(--fg-body)] mb-1">Code sent to current email</label>
                <input
                  type="text"
                  required
                  maxLength={6}
                  value={currentEmailCode}
                  onChange={(e) => setCurrentEmailCode(e.target.value)}
                  placeholder="123456"
                  className="w-full px-3.5 py-2.5 font-mono text-center tracking-widest rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] text-sm text-[var(--fg)]"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-[var(--fg-body)] mb-1">Code sent to new email</label>
                <input
                  type="text"
                  required
                  maxLength={6}
                  value={newEmailCode}
                  onChange={(e) => setNewEmailCode(e.target.value)}
                  placeholder="654321"
                  className="w-full px-3.5 py-2.5 font-mono text-center tracking-widest rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] text-sm text-[var(--fg)]"
                />
              </div>

              <div className="flex justify-end gap-2 pt-3">
                <button
                  type="button"
                  onClick={() => setEmailStep("request")}
                  className="px-4 py-2 rounded-xl border border-[var(--border)] text-xs font-semibold text-[var(--fg-body)]"
                >
                  Back
                </button>
                <button
                  type="submit"
                  disabled={emailBusy}
                  className="px-4 py-2 rounded-xl bg-blue-700 hover:bg-blue-600 text-white text-xs font-semibold"
                >
                  <BusyLabel busy={emailBusy} label="Verifying…">Confirm & Update Email</BusyLabel>
                </button>
              </div>
            </form>
          )}
        </Dialog>
      )}

      {/* MODAL: CHANGE PHONE */}
      {isPhoneModalOpen && (
        <Dialog
          title="Update Security Phone"
          description="Enter your new international phone number and verify ownership."
          onClose={() => !phoneBusy && setIsPhoneModalOpen(false)}
        >
          {phoneStep === "request" ? (
            <form onSubmit={handleRequestPhoneChange} className="space-y-4">
              <div>
                <label className="block text-xs font-semibold text-[var(--fg-body)] mb-1">New Phone Number (E.164)</label>
                <input
                  type="tel"
                  required
                  value={newPhone}
                  onChange={(e) => setNewPhone(e.target.value)}
                  placeholder="+256 700 000000"
                  className="w-full px-3.5 py-2.5 rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] text-xs text-[var(--fg)] outline-none focus:border-blue-600"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-[var(--fg-body)] mb-1">Current Account Password</label>
                <input
                  type="password"
                  required
                  value={phoneCurrentPassword}
                  onChange={(e) => setPhoneCurrentPassword(e.target.value)}
                  placeholder="••••••••••••"
                  className="w-full px-3.5 py-2.5 rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] text-xs text-[var(--fg)] outline-none focus:border-blue-600"
                />
              </div>

              <div className="flex justify-end gap-2 pt-3">
                <button
                  type="button"
                  onClick={() => setIsPhoneModalOpen(false)}
                  className="px-4 py-2 rounded-xl border border-[var(--border)] text-xs font-semibold text-[var(--fg-body)]"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={phoneBusy}
                  className="px-4 py-2 rounded-xl bg-blue-700 hover:bg-blue-600 text-white text-xs font-semibold"
                >
                  <BusyLabel busy={phoneBusy} label="Sending code…">Send Verification Code</BusyLabel>
                </button>
              </div>
            </form>
          ) : (
            <form onSubmit={handleVerifyPhoneChange} className="space-y-4">
              <div className="p-3 bg-blue-50 border border-blue-200 rounded-xl text-xs text-blue-900">
                A verification code was sent to your registered account email to authorize this phone update.
              </div>

              <div>
                <label className="block text-xs font-semibold text-[var(--fg-body)] mb-1">Verification Code</label>
                <input
                  type="text"
                  required
                  maxLength={6}
                  value={phoneCode}
                  onChange={(e) => setPhoneCode(e.target.value)}
                  placeholder="123456"
                  className="w-full px-3.5 py-2.5 font-mono text-center tracking-widest rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] text-sm text-[var(--fg)]"
                />
              </div>

              <div className="flex justify-end gap-2 pt-3">
                <button
                  type="button"
                  onClick={() => setPhoneStep("request")}
                  className="px-4 py-2 rounded-xl border border-[var(--border)] text-xs font-semibold text-[var(--fg-body)]"
                >
                  Back
                </button>
                <button
                  type="submit"
                  disabled={phoneBusy}
                  className="px-4 py-2 rounded-xl bg-blue-700 hover:bg-blue-600 text-white text-xs font-semibold"
                >
                  <BusyLabel busy={phoneBusy} label="Verifying…">Confirm & Update Phone</BusyLabel>
                </button>
              </div>
            </form>
          )}
        </Dialog>
      )}

      {/* MODAL: CHANGE PASSWORD */}
      {isPasswordModalOpen && (
        <Dialog
          title="Change Account Password"
          description="Enter your current password and create a new secure password."
          onClose={() => !passwordBusy && setIsPasswordModalOpen(false)}
        >
          <form onSubmit={handleChangePassword} className="space-y-4">
            <div>
              <label className="block text-xs font-semibold text-[var(--fg-body)] mb-1">Current Password</label>
              <input
                type={showPw ? "text" : "password"}
                required
                value={oldPassword}
                onChange={(e) => setOldPassword(e.target.value)}
                placeholder="••••••••••••"
                className="w-full px-3.5 py-2.5 rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] text-xs text-[var(--fg)] outline-none focus:border-blue-600"
              />
            </div>

            <div>
              <label className="block text-xs font-semibold text-[var(--fg-body)] mb-1">New Password (min 8 chars)</label>
              <input
                type={showPw ? "text" : "password"}
                required
                minLength={8}
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                placeholder="••••••••••••"
                className="w-full px-3.5 py-2.5 rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] text-xs text-[var(--fg)] outline-none focus:border-blue-600"
              />
            </div>

            <div>
              <label className="block text-xs font-semibold text-[var(--fg-body)] mb-1">Confirm New Password</label>
              <input
                type={showPw ? "text" : "password"}
                required
                minLength={8}
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                placeholder="••••••••••••"
                className="w-full px-3.5 py-2.5 rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] text-xs text-[var(--fg)] outline-none focus:border-blue-600"
              />
            </div>

            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setShowPw(!showPw)}
                className="text-xs text-[var(--fg-muted)] hover:text-[var(--fg)] flex items-center gap-1 cursor-pointer"
              >
                {showPw ? <EyeSlash className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                <span>{showPw ? "Hide passwords" : "Show passwords"}</span>
              </button>
            </div>

            <div className="flex justify-end gap-2 pt-3">
              <button
                type="button"
                onClick={() => setIsPasswordModalOpen(false)}
                className="px-4 py-2 rounded-xl border border-[var(--border)] text-xs font-semibold text-[var(--fg-body)]"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={passwordBusy}
                className="px-4 py-2 rounded-xl bg-blue-700 hover:bg-blue-600 text-white text-xs font-semibold"
              >
                <BusyLabel busy={passwordBusy} label="Updating…">Update Password</BusyLabel>
              </button>
            </div>
          </form>
        </Dialog>
      )}

      {/* MODAL: 2FA SETUP & RECOVERY CODES */}
      {is2faModalOpen && totpSetupData && (
        <Dialog
          title={newRecoveryCodes ? "Save Your Emergency Recovery Codes" : "Set Up Two-Factor Authentication"}
          description={
            newRecoveryCodes
              ? "Store these single-use recovery codes in a secure location. You will need them if you lose your authenticator."
              : "Scan this QR code with Google Authenticator or enter the key manually."
          }
          onClose={() => {
            if (!totpBusy) {
              setIs2faModalOpen(false);
              setNewRecoveryCodes(null);
              setTotpSetupData(null);
            }
          }}
        >
          {newRecoveryCodes ? (
            <div className="space-y-5">
              <div className="p-4 bg-[var(--subtle)] border border-[var(--border)] rounded-xl font-mono text-sm grid grid-cols-2 gap-2 text-center text-[var(--fg)]">
                {newRecoveryCodes.map((code) => (
                  <span key={code} className="py-1 px-2 rounded bg-[var(--fill)] font-semibold">
                    {code}
                  </span>
                ))}
              </div>

              <div className="flex justify-between items-center pt-3">
                <button
                  type="button"
                  onClick={() => {
                    navigator.clipboard.writeText(newRecoveryCodes.join("\n"));
                    setCopiedKey(true);
                    setTimeout(() => setCopiedKey(false), 2000);
                  }}
                  className="px-3.5 py-2 rounded-xl border border-[var(--border)] text-xs font-semibold flex items-center gap-2 hover:bg-[var(--fill)]"
                >
                  {copiedKey ? <Check className="w-4 h-4 text-emerald-600" /> : <Copy className="w-4 h-4" />}
                  <span>{copiedKey ? "Copied to clipboard" : "Copy Recovery Codes"}</span>
                </button>

                <button
                  type="button"
                  onClick={() => {
                    setIs2faModalOpen(false);
                    setNewRecoveryCodes(null);
                    setTotpSetupData(null);
                  }}
                  className="px-4 py-2 rounded-xl bg-blue-700 hover:bg-blue-600 text-white text-xs font-semibold shadow-sm"
                >
                  I have saved them safely
                </button>
              </div>
            </div>
          ) : (
            <form onSubmit={handleActivate2fa} className="space-y-4">
              <div className="flex flex-col items-center justify-center p-4 bg-white rounded-xl border border-[var(--border)]">
                <QRCodeSVG value={totpSetupData.qrCodeUri} size={180} />
              </div>

              <div>
                <label className="block text-[11px] font-semibold text-[var(--fg-muted)] mb-1">Manual Setup Key</label>
                <div className="flex items-center gap-2">
                  <input
                    type="text"
                    readOnly
                    value={totpSetupData.secret}
                    className="w-full px-3 py-1.5 font-mono text-xs rounded-lg bg-[var(--fill)] border border-[var(--border)] text-[var(--fg)] select-all"
                  />
                  <button
                    type="button"
                    onClick={() => {
                      navigator.clipboard.writeText(totpSetupData.secret);
                      setCopiedKey(true);
                      setTimeout(() => setCopiedKey(false), 2000);
                    }}
                    className="p-2 rounded-lg border border-[var(--border)] hover:bg-[var(--fill)] shrink-0"
                    title="Copy key"
                  >
                    {copiedKey ? <Check className="w-4 h-4 text-emerald-600" /> : <Copy className="w-4 h-4" />}
                  </button>
                </div>
              </div>

              <div>
                <label className="block text-xs font-semibold text-[var(--fg-body)] mb-1">Current Password (Reauthentication)</label>
                <input
                  type="password"
                  required
                  value={totpCurrentPassword}
                  onChange={(e) => setTotpCurrentPassword(e.target.value)}
                  placeholder="••••••••••••"
                  className="w-full px-3.5 py-2.5 rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] text-xs text-[var(--fg)] outline-none focus:border-blue-600"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-[var(--fg-body)] mb-1">6-Digit Code from Authenticator App</label>
                <input
                  type="text"
                  required
                  maxLength={6}
                  value={totpVerifyCode}
                  onChange={(e) => setTotpVerifyCode(e.target.value)}
                  placeholder="123456"
                  className="w-full px-3.5 py-2.5 font-mono text-center tracking-widest rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] text-base text-[var(--fg)] outline-none focus:border-blue-600"
                />
              </div>

              <div className="flex justify-end gap-2 pt-3">
                <button
                  type="button"
                  onClick={() => setIs2faModalOpen(false)}
                  className="px-4 py-2 rounded-xl border border-[var(--border)] text-xs font-semibold text-[var(--fg-body)]"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={totpBusy || totpVerifyCode.trim().length !== 6 || !totpCurrentPassword}
                  className="px-4 py-2 rounded-xl bg-blue-700 hover:bg-blue-600 disabled:opacity-50 text-white text-xs font-semibold shadow-sm"
                >
                  <BusyLabel busy={totpBusy} label="Activating…">Verify & Activate 2FA</BusyLabel>
                </button>
              </div>
            </form>
          )}
        </Dialog>
      )}

      {/* MODAL: DISABLE 2FA */}
      {isDisable2faModalOpen && (
        <Dialog
          title="Disable Two-Factor Authentication"
          description="Disabling 2FA will lower your account security. You will be required to reauthenticate."
          onClose={() => !disableBusy && setIsDisable2faModalOpen(false)}
        >
          <form onSubmit={handleDisable2fa} className="space-y-4">
            <div>
              <label className="block text-xs font-semibold text-[var(--fg-body)] mb-1">Current Password</label>
              <input
                type="password"
                required
                value={disablePw}
                onChange={(e) => setDisablePw(e.target.value)}
                placeholder="••••••••••••"
                className="w-full px-3.5 py-2.5 rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] text-xs text-[var(--fg)] outline-none focus:border-blue-600"
              />
            </div>

            <div>
              <label className="block text-xs font-semibold text-[var(--fg-body)] mb-1">6-Digit Authenticator Code or Recovery Code</label>
              <input
                type="text"
                required
                value={disableCode}
                onChange={(e) => setDisableCode(e.target.value)}
                placeholder="123456 or XXXX-XXXX"
                className="w-full px-3.5 py-2.5 font-mono text-center tracking-widest rounded-xl border border-[var(--border-strong)] bg-[var(--surface)] text-sm text-[var(--fg)] outline-none focus:border-blue-600"
              />
            </div>

            <div className="flex justify-end gap-2 pt-3">
              <button
                type="button"
                onClick={() => setIsDisable2faModalOpen(false)}
                className="px-4 py-2 rounded-xl border border-[var(--border)] text-xs font-semibold text-[var(--fg-body)]"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={disableBusy}
                className="px-4 py-2 rounded-xl bg-red-600 hover:bg-red-500 text-white text-xs font-semibold shadow-sm"
              >
                <BusyLabel busy={disableBusy} label="Disabling…">Confirm & Disable 2FA</BusyLabel>
              </button>
            </div>
          </form>
        </Dialog>
      )}
    </div>
  );
}
