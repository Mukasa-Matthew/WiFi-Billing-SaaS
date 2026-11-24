package com.wifibilling.dto;

import java.util.List;

/**
 * Everything the owner's Financial analysis page shows for one period and filter set.
 * Money is whole UGX. "Gross" is what customers paid; "net" is what reaches the owner
 * after the gateway (MarzPay) fee and the platform fee — the same rules as the dashboard.
 * The money-flow and target blocks always cover the whole business (no filters).
 */
public record FinancialAnalysisDto(
        Period period,
        boolean school,
        FilterOptions options,
        Totals totals,
        Totals previous,
        List<SeriesPoint> series,
        List<Slice> byChannel,
        List<Slice> byGroup,
        List<Ranked> byPackage,
        List<Ranked> byRouter,
        long[][] heatmapGross,
        int[][] heatmapSales,
        Customers customers,
        Lost lost,
        MoneyFlow moneyFlow,
        Target target
) {
    /** {@code to} is inclusive (the last local day shown); bucket is hour, day, week or month. */
    public record Period(String key, String label, String from, String to,
                         String previousFrom, String previousTo, String bucket, boolean partial) {}

    public record Option(long id, String name) {}

    public record FilterOptions(List<Option> routers, List<Option> packages) {}

    public record Totals(long gross, long gatewayFees, long platformFees, long net, long sales,
                         long avgSale, long customers, long perCustomer) {}

    /** One time bucket; previousGross is the matching bucket of the comparison period. */
    public record SeriesPoint(String start, String label, long gross, long net, long fees, long sales,
                              Long previousGross) {}

    public record Slice(String key, String label, long gross, long sales, long previousGross) {}

    public record Ranked(long id, String name, long gross, long net, long sales, long customers,
                         long previousGross, long price) {}

    public record TopCustomer(String phone, String name, long gross, long sales, String lastPurchase) {}

    public record Customers(long total, long newCustomers, long returning, long newGross, long returningGross,
                            long repeatBuyers, long lapsed, long lapsedPreviousGross,
                            long previousTotal, List<TopCustomer> top) {}

    public record LostByNetwork(String key, String label, long failed, long abandoned, long value) {}

    /** Mobile money only: hand-recorded sales cannot fail. successRate is null with no attempts. */
    public record Lost(long failed, long failedValue, long abandoned, long abandonedValue, long attempts,
                       Double successRate, Double previousSuccessRate, List<LostByNetwork> byNetwork) {}

    public record MoneyFlow(long gross, long gatewayFees, long platformFees, long net,
                            long withdrawn, long withdrawalFees, long withdrawals, long subscriptionPaid,
                            long walletAvailable, long walletPending, long walletReserved) {}

    /** Calendar month to date, always the current month regardless of the chosen period. */
    public record Target(Long monthlyTarget, long monthToDate, long lastMonthSameDay, long lastMonthTotal,
                         long projected, int dayOfMonth, int daysInMonth, String month) {}
}
