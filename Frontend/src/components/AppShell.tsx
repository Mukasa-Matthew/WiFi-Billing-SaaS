"use client";
import { prefetchRouteData } from "@/lib/dashboardPrefetch";

/**
 * The one application frame shared by the business workspace (/dashboard) and
 * the platform console (/admin). Each layout supplies its own navigation,
 * brand line and header actions; the shell owns layout, responsiveness, the
 * collapsible rail, the mobile drawer and the account menu, so the two
 * products can't drift apart visually.
 */

import Link from "next/link";
import React, { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { usePathname } from "next/navigation";
import { LinkPending } from "@/components/LoadingUI";
import ThemeToggle from "@/components/ThemeToggle";
import { CaretDown, CaretLeft, CaretRight, GearSix, List, SignOut, WifiHigh, X } from "@/components/icons";

export type NavTone = "neutral" | "info" | "warn" | "danger";

export interface NavItem {
  name: string;
  href: string;
  icon: React.ElementType;
  group: string;
  badge?: { value: number; tone?: NavTone; label?: string };
  /** Extra path prefixes that should highlight this item (e.g. detail pages under another section). */
  also?: string[];
}

/** Routes that aren't in the sidebar but still deserve a proper header title. */
export interface ExtraRoute { name: string; href: string; group: string }

interface ShellUser { name?: string; email?: string; role?: string; tenantId?: number }

interface AppShellProps {
  brand: { title: string; subtitle: string };
  nav: NavItem[];
  extraRoutes?: ExtraRoute[];
  rootHref: string;
  collapsibleGroups?: boolean;
  sidebarNotice?: React.ReactNode;
  headerActions?: React.ReactNode;
  user: ShellUser | null;
  roleLabel: string;
  settingsHref: string;
  onLogout: () => void;
  ready: boolean;
  readyLabel: string;
  children: React.ReactNode;
}

const RAIL_KEY = "wb_sidebar_rail";
const RAIL_EVENT = "wb-sidebar-rail";

// The rail preference lives in localStorage; reading it through an external
// store keeps the server render (always expanded) and hydration in agreement.
const railStore = {
  subscribe(cb: () => void) {
    window.addEventListener(RAIL_EVENT, cb);
    window.addEventListener("storage", cb);
    return () => { window.removeEventListener(RAIL_EVENT, cb); window.removeEventListener("storage", cb); };
  },
  get() { try { return localStorage.getItem(RAIL_KEY) === "1"; } catch { return false; } },
  set(value: boolean) {
    try { localStorage.setItem(RAIL_KEY, value ? "1" : "0"); } catch { /* storage unavailable */ }
    window.dispatchEvent(new Event(RAIL_EVENT));
  },
};

const badgeTone: Record<NavTone, string> = {
  neutral: "bg-white/10 text-[var(--sidebar-fg-dim)]",
  // Literal values: the sidebar is dark in both themes, and the palette
  // utilities are re-tinted under [data-theme="dark"].
  info: "bg-[#38BDF8]/15 text-[#BAE6FD]",
  warn: "bg-[#FBBF24]/15 text-[#FDE68A]",
  danger: "bg-[#F87171]/20 text-[#FECACA]",
};

function compact(n: number) {
  return n > 999 ? `${(n / 1000).toFixed(n > 9999 ? 0 : 1)}k` : String(n);
}

function initials(name?: string) {
  const parts = (name || "").trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return "?";
  return (parts.length === 1 ? parts[0].slice(0, 2) : parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

function matches(pathname: string, href: string, rootHref: string) {
  return href === rootHref ? pathname === rootHref : pathname === href || pathname.startsWith(`${href}/`);
}

export default function AppShell(props: AppShellProps) {
  const { brand, nav, extraRoutes = [], rootHref, collapsibleGroups, sidebarNotice, headerActions, user, roleLabel, settingsHref, onLogout, ready, readyLabel, children } = props;
  const pathname = usePathname() || rootHref;
  // The drawer remembers the path it was opened on, so navigating closes it.
  const [drawerPath, setDrawerPath] = useState<string | null>(null);
  const drawerOpen = drawerPath === pathname;
  const setDrawerOpen = (open: boolean) => setDrawerPath(open ? pathname : null);
  const rail = useSyncExternalStore(railStore.subscribe, railStore.get, () => false);
  const toggleRail = () => railStore.set(!rail);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  useEffect(() => {
    if (!drawerOpen) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setDrawerPath(null); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [drawerOpen]);

  const longest = <T extends { href: string }>(items: T[]) =>
    [...items].sort((a, b) => b.href.length - a.href.length).find((item) => matches(pathname, item.href, rootHref));
  const activeNav = nav
    .flatMap((item) => [item.href, ...(item.also || [])].filter((p) => matches(pathname, p, rootHref)).map((p) => ({ item, len: p.length })))
    .sort((a, b) => b.len - a.len)[0]?.item;
  const current = longest<ExtraRoute | NavItem>([...nav, ...extraRoutes]);

  const groups: { name: string; items: NavItem[] }[] = [];
  nav.forEach((item) => {
    const last = groups[groups.length - 1];
    if (last && last.name === item.group) last.items.push(item);
    else groups.push({ name: item.group, items: [item] });
  });

  return (
    <div className="flex h-dvh overflow-hidden bg-[var(--canvas)] text-[var(--fg)]">
      <button
        aria-hidden={!drawerOpen}
        tabIndex={-1}
        onClick={() => setDrawerOpen(false)}
        className={`fixed inset-0 z-30 bg-slate-950/50 transition-opacity duration-200 lg:hidden ${drawerOpen ? "opacity-100" : "pointer-events-none opacity-0"}`}
      />

      <aside
        aria-label="Main navigation"
        className={`fixed inset-y-0 left-0 z-40 flex w-[17rem] shrink-0 flex-col border-r border-[var(--sidebar-line)] bg-[var(--sidebar)] transition-[transform,visibility,width] duration-200 ease-[var(--ease-out)] lg:static lg:visible lg:translate-x-0 ${rail ? "lg:w-[4.5rem]" : "lg:w-64"} ${drawerOpen ? "visible translate-x-0" : "invisible -translate-x-full"}`}
      >
        {/* Brand */}
        <div className={`flex h-16 shrink-0 items-center gap-3 border-b border-[var(--sidebar-line)] ${rail ? "lg:justify-center lg:px-0" : ""} px-5`}>
          <span className="relative flex h-9 w-9 shrink-0 items-center justify-center rounded-[10px] bg-white/[0.08] text-white ring-1 ring-inset ring-white/10">
            <WifiHigh className="h-[18px] w-[18px]" aria-hidden />
            <span className="absolute -right-0.5 -top-0.5 h-2 w-2 rounded-full bg-[var(--sidebar-indicator)] ring-2 ring-[var(--sidebar)]" aria-hidden />
          </span>
          <div className={`min-w-0 flex-1 ${rail ? "lg:hidden" : ""}`}>
            <span className="block truncate text-[14px] font-semibold tracking-tight text-white">{brand.title}</span>
            <span className="block truncate text-[11px] font-medium text-[var(--sidebar-fg-faint)]">{brand.subtitle}</span>
          </div>
          <button onClick={() => setDrawerOpen(false)} aria-label="Close navigation" className="rounded-md p-1.5 text-[var(--sidebar-fg-dim)] hover:bg-[var(--sidebar-hover)] hover:text-white lg:hidden">
            <X className="h-4 w-4" />
          </button>
        </div>

        {sidebarNotice && <div className={`px-3 pt-3 ${rail ? "lg:hidden" : ""}`}>{sidebarNotice}</div>}

        <nav className="sidebar-scroll min-h-0 flex-1 overflow-y-auto overscroll-contain px-3 py-3">
          {groups.map((group, gi) => {
            const isCollapsed = collapsibleGroups && collapsed[group.name] && !group.items.some((i) => i === activeNav);
            return (
              <div key={group.name} className={gi ? "mt-4" : ""}>
                {gi > 0 && rail && <div className="mx-3 mb-3 hidden h-px bg-[var(--sidebar-line)] lg:block" aria-hidden />}
                {group.name && (
                  collapsibleGroups ? (
                    <button
                      type="button"
                      onClick={() => setCollapsed((c) => ({ ...c, [group.name]: !c[group.name] }))}
                      aria-expanded={!isCollapsed}
                      className={`group flex w-full items-center justify-between rounded-md px-3 pb-1.5 text-[11px] font-semibold uppercase tracking-[0.08em] text-[var(--sidebar-fg-faint)] hover:text-[var(--sidebar-fg-dim)] ${rail ? "lg:hidden" : ""}`}
                    >
                      <span>{group.name}</span>
                      <CaretDown className={`h-3 w-3 opacity-60 transition-transform group-hover:opacity-100 ${isCollapsed ? "-rotate-90" : ""}`} aria-hidden />
                    </button>
                  ) : (
                    <p className={`px-3 pb-1.5 text-[11px] font-semibold uppercase tracking-[0.08em] text-[var(--sidebar-fg-faint)] ${rail ? "lg:hidden" : ""}`}>{group.name}</p>
                  )
                )}
                {!isCollapsed && (
                  <ul className="space-y-0.5">
                    {group.items.map((item) => {
                      const Icon = item.icon;
                      const active = item === activeNav;
                      const badge = item.badge && item.badge.value > 0 ? item.badge : null;
                      return (
                        <li key={item.href}>
                          <Link
                            href={item.href}
                            onMouseEnter={() => prefetchRouteData(item.href, user?.role, user?.tenantId)}
                            aria-current={active ? "page" : undefined}
                            title={rail ? item.name : undefined}
                            className={`group relative flex h-9 items-center gap-3 rounded-lg px-3 text-[13.5px] font-medium transition-colors ${rail ? "lg:justify-center lg:px-0" : ""} ${
                              active
                                ? "bg-[var(--sidebar-current)] text-white"
                                : "text-[var(--sidebar-fg-dim)] hover:bg-[var(--sidebar-hover)] hover:text-white"
                            }`}
                          >
                            {active && <span className="absolute inset-y-2 left-0 w-[3px] rounded-r-full bg-[var(--sidebar-indicator)]" aria-hidden />}
                            <Icon aria-hidden className={`h-[18px] w-[18px] shrink-0 ${active ? "text-white" : "text-[var(--sidebar-fg-faint)] group-hover:text-[var(--sidebar-fg-dim)]"}`} />
                            <span className={`min-w-0 flex-1 truncate ${rail ? "lg:sr-only" : ""}`}>{item.name}</span>
                            {badge && (
                              <span
                                aria-label={badge.label ? `${badge.value} ${badge.label}` : undefined}
                                className={`num rounded-md px-1.5 py-px text-[11px] font-semibold ${badgeTone[badge.tone || "neutral"]} ${rail ? "lg:absolute lg:right-1 lg:top-0.5 lg:px-1 lg:text-[9px]" : ""}`}
                              >
                                {compact(badge.value)}
                              </span>
                            )}
                            <span className={rail ? "lg:hidden" : ""}><LinkPending /></span>
                          </Link>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>
            );
          })}
        </nav>

        <div className="hidden shrink-0 border-t border-[var(--sidebar-line)] p-3 lg:block">
          <button
            onClick={toggleRail}
            aria-label={rail ? "Expand sidebar" : "Collapse sidebar"}
            title={rail ? "Expand sidebar" : "Collapse sidebar"}
            className={`flex h-9 w-full items-center gap-3 rounded-lg px-3 text-[12.5px] font-medium text-[var(--sidebar-fg-faint)] transition-colors hover:bg-[var(--sidebar-hover)] hover:text-white ${rail ? "justify-center px-0" : ""}`}
          >
            {rail ? <CaretRight className="h-4 w-4" aria-hidden /> : <><CaretLeft className="h-4 w-4" aria-hidden /><span>Collapse</span><span className="ml-auto text-[10.5px] text-[var(--sidebar-fg-faint)]/70">by Thewton</span></>}
          </button>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
        <header className="flex h-16 shrink-0 items-center justify-between gap-3 border-b border-[var(--border)] bg-[var(--header)] px-4 sm:px-6 lg:px-8">
          <div className="flex min-w-0 items-center gap-2.5">
            <button aria-label="Open navigation" aria-expanded={drawerOpen} onClick={() => setDrawerOpen(true)}
              className="-ml-1.5 rounded-lg p-2 text-[var(--fg-muted)] hover:bg-[var(--fill)] hover:text-[var(--fg)] lg:hidden">
              <List className="h-5 w-5" />
            </button>
            <nav aria-label="Breadcrumb" className="min-w-0">
              <ol className="flex min-w-0 items-center gap-1.5 text-[13.5px]">
                {current && current.href !== rootHref && current.group && (
                  <li className="hidden shrink-0 items-center gap-1.5 text-[var(--fg-muted)] sm:flex">
                    <span>{current.group}</span>
                    <CaretRight className="h-3.5 w-3.5 text-[var(--fg-faint)]" aria-hidden />
                  </li>
                )}
                <li aria-current="page" className="truncate font-semibold text-[var(--fg)]">{current?.name || brand.subtitle}</li>
              </ol>
            </nav>
          </div>
          <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
            {headerActions}
            <UserMenu user={user} roleLabel={roleLabel} settingsHref={settingsHref} onLogout={onLogout} />
          </div>
        </header>

        <main className="main-scroll flex-1 overflow-y-auto px-4 py-5 sm:px-6 sm:py-6 lg:px-8 lg:py-7">
          <div className="page-enter mx-auto w-full max-w-[1440px]">{children}</div>
        </main>
      </div>

      {/* Covers the workspace instead of replacing it: the layout has to keep
          rendering {children} so Next can validate the segments below it for
          instant navigation. No data is server-rendered — every panel fetches
          with the bearer token — so nothing is exposed behind this. */}
      {!ready && (
        <div role="status" aria-live="polite" className="fixed inset-0 z-50 flex items-center justify-center bg-[var(--canvas)] text-[var(--fg-muted)]">
          <div className="flex flex-col items-center gap-3">
            <div className="h-8 w-8 animate-spin rounded-full border-2 border-[var(--primary)] border-t-transparent motion-reduce:animate-none" />
            <p className="text-sm">{readyLabel}</p>
          </div>
        </div>
      )}
    </div>
  );
}

function UserMenu({ user, roleLabel, settingsHref, onLogout }: { user: ShellUser | null; roleLabel: string; settingsHref: string; onLogout: () => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false); };
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("mousedown", onDown);
    window.addEventListener("keydown", onKey);
    return () => { document.removeEventListener("mousedown", onDown); window.removeEventListener("keydown", onKey); };
  }, [open]);

  return (
    <div ref={ref} className="relative">
      <button
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Account menu"
        className="flex items-center gap-2 rounded-lg p-1 pr-1.5 transition-colors hover:bg-[var(--fill)] sm:pr-2"
      >
        <span className="flex h-8 w-8 items-center justify-center rounded-full bg-[var(--sidebar)] text-[11px] font-semibold text-white ring-1 ring-inset ring-white/10">{initials(user?.name)}</span>
        <span className="hidden max-w-36 truncate text-[13px] font-medium text-[var(--fg-body)] md:block">{user?.name}</span>
        <CaretDown className="hidden h-3.5 w-3.5 text-[var(--fg-faint)] md:block" aria-hidden />
      </button>
      {open && (
        <div role="menu" className="menu-enter absolute right-0 top-full z-50 mt-2 w-64 overflow-hidden rounded-xl border border-[var(--border)] bg-[var(--surface)] shadow-[var(--shadow-overlay)]">
          <div className="border-b border-[var(--border)] px-4 py-3">
            <p className="truncate text-sm font-semibold text-[var(--fg)]">{user?.name}</p>
            <p className="truncate text-xs text-[var(--fg-muted)]">{user?.email}</p>
            <p className="mt-1.5 inline-flex rounded-md bg-[var(--fill)] px-1.5 py-0.5 text-[11px] font-medium text-[var(--fg-muted)]">{roleLabel}</p>
          </div>
          <div className="flex items-center justify-between gap-3 border-b border-[var(--border)] px-4 py-2.5">
            <span className="text-xs font-medium text-[var(--fg-muted)]">Theme</span>
            <ThemeToggle compact />
          </div>
          <div className="p-1.5">
            <Link role="menuitem" href={settingsHref} onClick={() => setOpen(false)} className="flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13px] font-medium text-[var(--fg-body)] hover:bg-[var(--fill)]">
              <GearSix className="h-4 w-4 text-[var(--fg-muted)]" aria-hidden />Settings
            </Link>
            <button role="menuitem" onClick={onLogout} className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-[13px] font-medium text-red-600 hover:bg-red-500/10">
              <SignOut className="h-4 w-4" aria-hidden />Sign out
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
