/**
 * How a hand-recorded payment was received. Keys match the backend's accepted methods.
 * Anything received by mobile money, on any network, is never counted as walk-in revenue.
 */
export const PAYMENT_METHODS = [
  ["CASH", "Cash"],
  ["MTN", "MTN Mobile Money"],
  ["AIRTEL", "Airtel Money"],
  ["MOMO", "Other Mobile Money"],
  ["BANK", "Bank"],
  ["OTHER", "Other"],
] as const;

/** Methods that are mobile money: MTN, Airtel, or any other network. */
export const MOBILE_MONEY: readonly string[] = ["MTN", "AIRTEL", "MOMO"];
