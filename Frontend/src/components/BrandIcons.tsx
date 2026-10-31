import React from "react";

/**
 * AirPairy's own navigation icons. Every one follows the logo's recipe: a line
 * drawing, a soft tint, and one signal-coloured detail that moves when its link
 * is hovered or current (see .bi in globals.css). Motion is decoration only and
 * stops under reduced motion; the labels beside them carry the meaning.
 */
function Icon({ className, children }: { className?: string; children: React.ReactNode }) {
  return <svg viewBox="0 0 24 24" className={`bi ${className ?? ""}`} aria-hidden>{children}</svg>;
}
type P = { className?: string };
const Arc = ({ d, late }: { d: string; late?: boolean }) => <path className={`acc m-ping ${late ? "m-late" : ""}`} d={d} />;

export const OverviewIcon = ({ className }: P) => <Icon className={className}>
  <path className="soft" d="M4 3h16a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z" />
  <path className="m-grow" d="M7 18v-4" /><path className="m-grow m-late" d="M12 18V10" /><path className="acc m-grow m-later" d="M17 18V6" />
</Icon>;

export const LiveUsersIcon = ({ className }: P) => <Icon className={className}>
  <circle className="soft" cx="12" cy="15.5" r="5" />
  <circle cx="12" cy="12.5" r="2.6" /><path d="M6.8 20.5c.9-2.5 2.8-3.8 5.2-3.8s4.3 1.3 5.2 3.8" />
  <Arc d="M8.8 6.4a4.6 4.6 0 0 1 6.4 0" /><Arc late d="M6 3.6a8.6 8.6 0 0 1 12 0" />
</Icon>;

export const CustomersIcon = ({ className }: P) => <Icon className={className}>
  <circle className="soft" cx="9" cy="8" r="4" />
  <circle cx="9" cy="8" r="3.4" /><path d="M2.8 20c.7-3.6 3-5.5 6.2-5.5s5.5 1.9 6.2 5.5" />
  <g className="m-rise"><path className="acc" d="M15.5 4.9a3.4 3.4 0 0 1 0 6.2" /><path className="acc" d="M18 14.9c1.8.8 2.8 2.5 3.2 5.1" /></g>
</Icon>;

export const ExpiredIcon = ({ className }: P) => <Icon className={className}>
  <circle className="soft" cx="12" cy="12" r="8.6" />
  <path d="M20.6 12A8.6 8.6 0 1 1 18 5.9" /><path d="M18.4 2.8V6h-3.2" />
  <path className="acc m-spin" d="M12 7.4V12l3 1.8" />
</Icon>;

export const BindingIcon = ({ className }: P) => <Icon className={className}>
  <rect className="soft" x="2.6" y="8" width="11" height="8" rx="4" />
  <rect x="2.6" y="8" width="11" height="8" rx="4" />
  <g className="m-slide"><path className="acc" d="M14 8h3.4a4 4 0 0 1 0 8H14a4 4 0 0 1-3.3-1.8" /></g>
</Icon>;

export const FeedbackIcon = ({ className }: P) => <Icon className={className}>
  <path className="soft" d="M4 4.5h16a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5H10l-5 4v-4H4A1.5 1.5 0 0 1 2.5 16V6A1.5 1.5 0 0 1 4 4.5z" />
  <path d="M4 4.5h16a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5H10l-5 4v-4H4A1.5 1.5 0 0 1 2.5 16V6A1.5 1.5 0 0 1 4 4.5z" />
  <circle className="dot m-bounce" cx="8" cy="11" r="1.2" /><circle className="dot m-bounce m-late" cx="12" cy="11" r="1.2" /><circle className="dot m-bounce m-later" cx="16" cy="11" r="1.2" />
</Icon>;

export const PackagesIcon = ({ className }: P) => <Icon className={className}>
  <path className="soft" d="M12 3 21 8l-9 5-9-5z" />
  <path d="m3 16.5 9 5 9-5" /><path className="m-rise m-late" d="m3 12.2 9 5 9-5" />
  <path className="acc m-rise m-later" d="M12 3 21 8l-9 5-9-5z" />
</Icon>;

export const VoucherIcon = ({ className }: P) => <Icon className={className}>
  <path className="soft" d="M3 6h18v4a2 2 0 0 0 0 4v4H3v-4a2 2 0 0 0 0-4z" />
  <path d="M3 6h18v4a2 2 0 0 0 0 4v4H3v-4a2 2 0 0 0 0-4z" />
  <path className="acc m-draw" pathLength={1} d="M14.5 6.5v11" strokeDasharray="0.14 0.1" />
  <path d="M7 10.5h4M7 13.5h2.5" />
</Icon>;

export const PaymentsIcon = ({ className }: P) => <Icon className={className}>
  <rect className="soft" x="2.5" y="6" width="19" height="12" rx="2" />
  <rect x="2.5" y="6" width="19" height="12" rx="2" />
  <circle className="acc m-flip" cx="12" cy="12" r="2.8" /><path d="M6 9.5v.01M18 14.5v.01" />
</Icon>;

export const WalletIcon = ({ className }: P) => <Icon className={className}>
  <path className="soft" d="M3.5 7h16a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5h-14A2 2 0 0 1 3.5 18z" />
  <path d="M3.5 7v11a2 2 0 0 0 2 2h14a1.5 1.5 0 0 0 1.5-1.5v-10A1.5 1.5 0 0 0 19.5 7h-16a2 2 0 0 1 0-3.5h14" />
  <g className="m-slide"><path className="acc" d="M21 11.5h-4.5a2 2 0 0 0 0 4H21" /></g>
</Icon>;

export const RouterIcon = ({ className }: P) => <Icon className={className}>
  <rect className="soft" x="2.5" y="13.5" width="19" height="7" rx="2" />
  <rect x="2.5" y="13.5" width="19" height="7" rx="2" /><path d="M16.5 13.5V8.8" /><path d="M7 17h.01M10.2 17h.01" />
  <Arc d="M13.9 6.8a3.6 3.6 0 0 1 5.2 0" /><Arc late d="M11.8 4.2a6.6 6.6 0 0 1 9.4 0" />
</Icon>;

export const SessionsIcon = ({ className }: P) => <Icon className={className}>
  <rect className="soft" x="2.5" y="4" width="19" height="16" rx="3" />
  <path className="acc m-draw" pathLength={1} d="M3 12.5h4.2l2.1-5.8 3.8 11 2.3-5.2H21" />
</Icon>;

export const LogsIcon = ({ className }: P) => <Icon className={className}>
  <path className="soft" d="M6 2.8h8.5L19.5 8v11.7a1.5 1.5 0 0 1-1.5 1.5H6a1.5 1.5 0 0 1-1.5-1.5V4.3A1.5 1.5 0 0 1 6 2.8z" />
  <path d="M6 2.8h8.5L19.5 8v11.7a1.5 1.5 0 0 1-1.5 1.5H6a1.5 1.5 0 0 1-1.5-1.5V4.3A1.5 1.5 0 0 1 6 2.8z" /><path d="M14 3v5.2h5.2" />
  <path className="acc m-draw" pathLength={1} d="M8 13h8" /><path className="acc m-draw m-late" pathLength={1} d="M8 16.8h5.4" />
</Icon>;

export const SubscriptionIcon = ({ className }: P) => <Icon className={className}>
  <rect className="soft" x="3" y="5" width="18" height="16" rx="2.5" />
  <rect x="3" y="5" width="18" height="16" rx="2.5" /><path d="M3 10h18M8 3v4M16 3v4" />
  <path className="acc m-draw" pathLength={1} d="m8.6 15.4 2.4 2.3 4.4-4.5" />
</Icon>;

export const TuneIcon = ({ className }: P) => <Icon className={className}>
  <path d="M3 7h18M3 17h18" />
  <circle className="knob m-slide" cx="8.5" cy="7" r="2.7" /><circle className="knob acc-ring m-slide-back" cx="15.5" cy="17" r="2.7" />
</Icon>;

export const RadarIcon = ({ className }: P) => <Icon className={className}>
  <circle className="soft" cx="12" cy="12" r="9" />
  <circle cx="12" cy="12" r="9" /><circle cx="12" cy="12" r="4.6" />
  <g className="m-sweep"><path className="acc" d="M12 12 18 5.6" /></g>
  <circle className="dot m-bounce" cx="8.4" cy="9.2" r="1.15" />
</Icon>;

export const BusinessIcon = ({ className }: P) => <Icon className={className}>
  <path className="soft" d="M5 21V4.5A1.5 1.5 0 0 1 6.5 3h7A1.5 1.5 0 0 1 15 4.5V21z" />
  <path d="M5 21V4.5A1.5 1.5 0 0 1 6.5 3h7A1.5 1.5 0 0 1 15 4.5V21M15 10h3.5a1.5 1.5 0 0 1 1.5 1.5V21M3 21h18" />
  <path className="acc m-blink" d="M8.5 7.5h.01M11.5 7.5h.01" /><path className="acc m-blink m-late" d="M8.5 11.5h.01M11.5 11.5h.01" /><path className="acc m-blink m-later" d="M8.5 15.5h.01M11.5 15.5h.01" />
</Icon>;

export const PlanIcon = ({ className }: P) => <Icon className={className}>
  <rect className="soft" x="2.5" y="5" width="19" height="14" rx="2.5" />
  <rect x="2.5" y="5" width="19" height="14" rx="2.5" />
  <path className="acc m-draw" pathLength={1} d="M2.8 10h18.4" /><path d="M6.5 15h3" />
</Icon>;

export const FinanceIcon = ({ className }: P) => <Icon className={className}>
  <path className="soft" d="M12 3 21 8.5H3z" />
  <path d="M12 3 21 8.5H3zM3.5 20.5h17" />
  <path className="m-grow" d="M6.5 18v-6.5" /><path className="acc m-grow m-late" d="M12 18v-6.5" /><path className="m-grow m-later" d="M17.5 18v-6.5" />
</Icon>;

export const NetworkIcon = ({ className }: P) => <Icon className={className}>
  <path d="M12 12 5.5 6M12 12l6.5-6M12 12v7" />
  <circle className="soft" cx="12" cy="12" r="6" />
  <circle cx="5" cy="5.5" r="2" /><circle cx="19" cy="5.5" r="2" /><circle cx="12" cy="20" r="2" />
  <circle className="dot m-bounce" cx="12" cy="12" r="2.1" />
</Icon>;

export const FleetIcon = ({ className }: P) => <Icon className={className}>
  <rect className="soft" x="3" y="13" width="18" height="7" rx="2" />
  <rect x="3" y="13" width="18" height="7" rx="2" /><rect className="m-rise" x="3" y="4" width="18" height="7" rx="2" />
  <path className="acc m-blink" d="M7 7.5h.01M10 7.5h.01" /><path className="acc m-blink m-late" d="M7 16.5h.01M10 16.5h.01" />
</Icon>;

export const HealthIcon = ({ className }: P) => <Icon className={className}>
  <path className="soft" d="M12 20.5S3 15.4 3 9.2A5 5 0 0 1 12 6a5 5 0 0 1 9 3.2c0 6.2-9 11.3-9 11.3z" />
  <path d="M12 20.5S3 15.4 3 9.2A5 5 0 0 1 12 6a5 5 0 0 1 9 3.2c0 6.2-9 11.3-9 11.3z" />
  <path className="acc m-draw" pathLength={1} d="M6.8 11.8h3l1.6-2.8 2 5 1.3-2.2h2.6" />
</Icon>;

export const GuardIcon = ({ className }: P) => <Icon className={className}>
  <path className="soft" d="M12 2.8 20 5.6v6.2c0 4.6-3.2 7.9-8 9.4-4.8-1.5-8-4.8-8-9.4V5.6z" />
  <path d="M12 2.8 20 5.6v6.2c0 4.6-3.2 7.9-8 9.4-4.8-1.5-8-4.8-8-9.4V5.6z" />
  <path className="acc m-draw" pathLength={1} d="m8.6 11.8 2.4 2.4 4.4-4.6" />
</Icon>;
