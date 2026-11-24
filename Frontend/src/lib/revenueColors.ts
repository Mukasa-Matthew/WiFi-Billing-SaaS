/**
 * Fixed colour per payment channel / customer group, shared by the Dashboard and
 * Financial analysis so an entity keeps its colour everywhere (MTN yellow, Airtel red).
 */
export const CHANNEL_COLOR: Record<string, string> = {
  MTN: "var(--status-warn)",
  AIRTEL: "var(--status-bad)",
  WALK_IN: "var(--status-good)",
  OTHER: "var(--chart-1)",
};

export const GROUP_COLOR: Record<string, string> = {
  STUDENT: "var(--status-good)",
  PAID_CUSTOMER: "var(--chart-1)",
  WALK_IN: "var(--status-warn)",
};
