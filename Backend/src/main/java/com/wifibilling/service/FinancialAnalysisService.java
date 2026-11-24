package com.wifibilling.service;

import com.wifibilling.dto.FinancialAnalysisDto;
import com.wifibilling.dto.FinancialAnalysisDto.*;
import com.wifibilling.model.Customer;
import com.wifibilling.model.PackagePlan;
import com.wifibilling.model.Router;
import com.wifibilling.model.SubscriptionPayment;
import com.wifibilling.model.Tenant;
import com.wifibilling.model.Transaction;
import com.wifibilling.model.Voucher;
import com.wifibilling.model.WithdrawalRequest;
import com.wifibilling.repository.CustomerRepository;
import com.wifibilling.repository.PackagePlanRepository;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.SubscriptionPaymentRepository;
import com.wifibilling.repository.TenantRepository;
import com.wifibilling.repository.TransactionRepository;
import com.wifibilling.repository.VoucherRepository;
import com.wifibilling.repository.WalletAccountRepository;
import com.wifibilling.repository.WithdrawalRequestRepository;
import com.wifibilling.util.AccessState;
import com.wifibilling.util.MobileNetwork;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Predicate;

/**
 * The owner's Financial analysis page. Money follows the dashboard's rules exactly
 * (TenantDashboardService): only SUCCESSFUL payments count, net = amount − gateway
 * fee − platform fee, and the channel/walk-in split comes from MobileNetwork, so mobile
 * money on any network is never counted as walk-in. Day and hour boundaries are the
 * reporting zone's (Africa/Kampala), via ReportingClock.
 */
@Service
@RequiredArgsConstructor
public class FinancialAnalysisService {

    public static final List<String> PERIODS = List.of(
            "today", "yesterday", "7d", "30d", "week", "lastweek", "month", "lastmonth",
            "quarter", "year", "lastyear", "custom");
    /** A mobile money payment still pending after this long was abandoned (the customer never approved it). */
    static final Duration ABANDONED_AFTER = Duration.ofMinutes(30);
    static final long MAX_TARGET = 10_000_000_000L;
    static final int MAX_CUSTOM_DAYS = 731;

    private final TransactionRepository transactionRepository;
    private final VoucherRepository voucherRepository;
    private final RouterRepository routerRepository;
    private final PackagePlanRepository planRepository;
    private final CustomerRepository customerRepository;
    private final TenantRepository tenantRepository;
    private final WithdrawalRequestRepository withdrawalRepository;
    private final SubscriptionPaymentRepository subscriptionPaymentRepository;
    private final WalletAccountRepository walletRepository;
    private final ReportingClock clock;

    /** Optional filters; null means "all". channel: MTN, AIRTEL, OTHER, WALK_IN. group: STUDENT, PAID_CUSTOMER, WALK_IN. */
    public record Filters(Long routerId, Long packageId, String channel, String group) {
        public static Filters none() { return new Filters(null, null, null, null); }
    }

    record Window(OffsetDateTime start, OffsetDateTime end) {
        boolean has(OffsetDateTime t) { return t != null && !t.isBefore(start) && t.isBefore(end); }
    }

    record Resolved(String key, String label, Window cur, Window prev, boolean partial) {}

    // ---------------------------------------------------------------- periods

    Resolved resolve(String period, LocalDate from, LocalDate to) {
        String key = period == null || !PERIODS.contains(period) ? "30d" : period;
        OffsetDateTime now = clock.now();
        LocalDate today = clock.today();
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        LocalDate m1 = today.withDayOfMonth(1);
        LocalDate q1 = m1.withMonth(((m1.getMonthValue() - 1) / 3) * 3 + 1);
        LocalDate y1 = today.withDayOfYear(1);
        return switch (key) {
            case "today" -> running(key, "Today", day(today), now, java.time.Period.ofDays(1));
            case "yesterday" -> closed(key, "Yesterday", today.minusDays(1), today, today.minusDays(2));
            case "7d" -> running(key, "Last 7 days", day(today.minusDays(6)), now, java.time.Period.ofDays(7));
            case "week" -> running(key, "This week", day(monday), now, java.time.Period.ofDays(7));
            case "lastweek" -> closed(key, "Last week", monday.minusDays(7), monday, monday.minusDays(14));
            case "month" -> running(key, "This month", day(m1), now, java.time.Period.ofMonths(1));
            case "lastmonth" -> closed(key, "Last month", m1.minusMonths(1), m1, m1.minusMonths(2));
            case "quarter" -> running(key, "This quarter", day(q1), now, java.time.Period.ofMonths(3));
            case "year" -> running(key, "This year", day(y1), now, java.time.Period.ofYears(1));
            case "lastyear" -> closed(key, "Last year", y1.minusYears(1), y1, y1.minusYears(2));
            case "custom" -> custom(from, to, now);
            default -> running("30d", "Last 30 days", day(today.minusDays(29)), now, java.time.Period.ofDays(30));
        };
    }

    private OffsetDateTime day(LocalDate d) { return clock.startOf(d); }

    /** Local-calendar shift back (a month back from 31 March is 28/29 February, like a calendar). */
    private OffsetDateTime back(OffsetDateTime t, java.time.Period p) {
        return t.atZoneSameInstant(clock.zone()).minus(p).withZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime();
    }

    /** A period still running: compared with the same stretch of time one unit earlier. */
    private Resolved running(String key, String label, OffsetDateTime start, OffsetDateTime now, java.time.Period unit) {
        OffsetDateTime ps = back(start, unit);
        OffsetDateTime pe = back(now, unit);
        if (pe.isAfter(start)) pe = start;
        return new Resolved(key, label, new Window(start, now), new Window(ps, pe), true);
    }

    private Resolved closed(String key, String label, LocalDate from, LocalDate toExclusive, LocalDate prevFrom) {
        return new Resolved(key, label, new Window(day(from), day(toExclusive)), new Window(day(prevFrom), day(from)), false);
    }

    private Resolved custom(LocalDate from, LocalDate to, OffsetDateTime now) {
        if (from == null || to == null) throw badRequest("Choose both a start and an end date.");
        if (to.isBefore(from)) throw badRequest("The end date must be on or after the start date.");
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_CUSTOM_DAYS) throw badRequest("Choose a range of at most two years.");
        if (from.isAfter(clock.today())) throw badRequest("The start date is in the future.");
        OffsetDateTime start = day(from);
        OffsetDateTime end = day(to.plusDays(1));
        boolean partial = end.isAfter(now);
        if (partial) end = now;
        java.time.Period shift = java.time.Period.ofDays((int) days);
        DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
        String label = from.equals(to) ? from.format(f) : from.format(f) + " – " + to.format(f);
        return new Resolved("custom", label, new Window(start, end), new Window(back(start, shift), back(end, shift)), partial);
    }

    // ---------------------------------------------------------------- buckets

    enum Bucket { HOUR, DAY, WEEK, MONTH }

    Bucket bucketFor(Window w) {
        long days = ChronoUnit.DAYS.between(clock.dateOf(w.start()), clock.dateOf(w.end().minusNanos(1))) + 1;
        if (days <= 1) return Bucket.HOUR;
        if (days <= 45) return Bucket.DAY;
        if (days <= 120) return Bucket.WEEK;
        return Bucket.MONTH;
    }

    /** Bucket start instants covering the window; the first starts at the window start. */
    List<OffsetDateTime> bucketStarts(Window w, Bucket b) {
        List<OffsetDateTime> out = new ArrayList<>();
        OffsetDateTime cursor = w.start();
        while (cursor.isBefore(w.end()) && out.size() < 400) {
            out.add(cursor);
            var local = cursor.atZoneSameInstant(clock.zone());
            var next = switch (b) {
                case HOUR -> local.truncatedTo(ChronoUnit.HOURS).plusHours(1);
                case DAY -> local.toLocalDate().plusDays(1).atStartOfDay(clock.zone());
                case WEEK -> local.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(clock.zone());
                case MONTH -> local.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(clock.zone());
            };
            cursor = next.withZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime();
        }
        return out;
    }

    static int indexOf(List<OffsetDateTime> starts, OffsetDateTime t) {
        int lo = 0, hi = starts.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (!starts.get(mid).isAfter(t)) { ans = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return ans;
    }

    private String bucketLabel(OffsetDateTime start, Bucket b) {
        var local = start.atZoneSameInstant(clock.zone());
        return switch (b) {
            case HOUR -> local.format(DateTimeFormatter.ofPattern("HH:00", Locale.ENGLISH));
            case DAY -> local.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH));
            case WEEK -> "Week of " + local.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
            case MONTH -> local.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH));
        };
    }

    // ---------------------------------------------------------------- money rules (same as the dashboard)

    static long amount(Transaction t) { return t.getAmount() == null ? 0 : t.getAmount().longValue(); }
    static long gatewayFee(Transaction t) { return t.getGatewayFee() == null ? 0 : t.getGatewayFee(); }
    static long platformFee(Transaction t) { return t.getPlatformFee() == null ? 0 : t.getPlatformFee(); }
    static long net(Transaction t) {
        long base = t.getNetAmount() != null ? t.getNetAmount() : amount(t) - gatewayFee(t);
        return Math.max(0, base - platformFee(t));
    }
    static boolean successful(Transaction t) { return "SUCCESSFUL".equalsIgnoreCase(t.getStatus()); }
    static boolean online(Transaction t) { return t.getGatewayMode() != null && !t.getGatewayMode().isBlank(); }

    /** MTN, AIRTEL, OTHER (other mobile money) or WALK_IN (handed out without mobile money). */
    static String channel(Transaction t) {
        String n = MobileNetwork.ofPayment(t.getNetwork(), t.getPhone(), t.getGatewayMode());
        return MobileNetwork.MTN.equals(n) ? "MTN" : MobileNetwork.AIRTEL.equals(n) ? "AIRTEL"
                : MobileNetwork.isMobileMoney(n) ? "OTHER" : "WALK_IN";
    }

    static String group(Transaction t, Map<String, Voucher> byCode) {
        Voucher v = t.getVoucherCode() != null ? byCode.get(t.getVoucherCode()) : null;
        boolean student = t.getPersonId() != null || (v != null && "STUDENT".equals(AccessState.category(v)));
        return student ? "STUDENT" : "WALK_IN".equals(channel(t)) ? "WALK_IN" : "PAID_CUSTOMER";
    }

    /** A customer is a phone number (last 9 digits); walk-in sales without one have no customer. */
    static String customerKey(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("[^0-9]", "");
        return d.length() >= 9 ? d.substring(d.length() - 9) : null;
    }

    private static Long routerId(Transaction t) { return t.getRouter() != null ? t.getRouter().getId() : null; }
    private static Long planId(Transaction t) { return t.getPackagePlan() != null ? t.getPackagePlan().getId() : null; }

    // ---------------------------------------------------------------- analysis

    @Transactional(readOnly = true)
    public FinancialAnalysisDto analyse(Long tenantId, String period, LocalDate from, LocalDate to, Filters filters) {
        Filters f = filters == null ? Filters.none() : filters;
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        boolean school = tenant.isSchool();
        Resolved r = resolve(period, from, to);
        OffsetDateTime now = clock.now();

        List<Transaction> all = transactionRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        List<PackagePlan> plans = planRepository.findAllByTenantIdOrderByPriceAsc(tenantId);
        List<Router> routers = routerRepository.findAllByTenantId(tenantId);
        Map<Long, String> planNames = new LinkedHashMap<>();
        Map<Long, Long> planPrices = new HashMap<>();
        for (PackagePlan p : plans) {
            planNames.put(p.getId(), p.getName());
            planPrices.put(p.getId(), p.getPrice() == null ? 0 : p.getPrice().longValue());
        }
        Map<Long, String> routerNames = new LinkedHashMap<>();
        for (Router rt : routers) {
            if (!"DELETED".equalsIgnoreCase(rt.getStatus())) routerNames.put(rt.getId(), rt.getName());
        }

        // Student check needs the voucher for hand-recorded school sales (as on the dashboard).
        Map<String, Voucher> byCode = new HashMap<>();
        if (school) {
            Set<String> codes = new HashSet<>();
            for (Transaction t : all) {
                if (t.getVoucherCode() != null && t.getPersonId() == null
                        && (r.cur().has(t.getCreatedAt()) || r.prev().has(t.getCreatedAt()))) codes.add(t.getVoucherCode());
            }
            if (!codes.isEmpty()) {
                for (Voucher v : voucherRepository.findAllByCodeIn(codes)) {
                    if (v.getTenant() == null || Objects.equals(v.getTenant().getId(), tenantId)) byCode.put(v.getCode(), v);
                }
            }
        }

        Predicate<Transaction> matches = t ->
                (f.routerId() == null || Objects.equals(routerId(t), f.routerId()))
                        && (f.packageId() == null || Objects.equals(planId(t), f.packageId()))
                        && (f.channel() == null || f.channel().equals(channel(t)))
                        && (f.group() == null || f.group().equals(group(t, byCode)));

        List<Transaction> cur = new ArrayList<>();
        List<Transaction> prev = new ArrayList<>();
        for (Transaction t : all) {
            if (!successful(t) || !matches.test(t)) continue;
            if (r.cur().has(t.getCreatedAt())) cur.add(t);
            else if (r.prev().has(t.getCreatedAt())) prev.add(t);
        }

        Bucket bucket = bucketFor(r.cur());
        DateTimeFormatter isoDate = DateTimeFormatter.ISO_LOCAL_DATE;
        Period periodDto = new Period(r.key(), r.label(),
                clock.dateOf(r.cur().start()).format(isoDate), clock.dateOf(r.cur().end().minusNanos(1)).format(isoDate),
                clock.dateOf(r.prev().start()).format(isoDate),
                clock.dateOf(r.prev().end().minusNanos(1)).format(isoDate),
                bucket.name().toLowerCase(Locale.ROOT), r.partial());

        FilterOptions options = new FilterOptions(
                routerNames.entrySet().stream().map(e -> new Option(e.getKey(), e.getValue())).toList(),
                planNames.entrySet().stream().map(e -> new Option(e.getKey(), e.getValue())).toList());

        return new FinancialAnalysisDto(periodDto, school, options,
                totals(cur), totals(prev),
                series(r, bucket, cur, prev),
                byChannel(cur, prev),
                school ? byGroup(cur, prev, byCode) : List.of(),
                ranked(cur, prev, planNames, planPrices, true),
                ranked(cur, prev, routerNames, Map.of(), false),
                heatGross(cur), heatSales(cur),
                customers(tenantId, all, r, cur, prev),
                lost(all, r, matches, now),
                moneyFlow(tenantId, all, r.cur()),
                target(tenant, all, now));
    }

    static Totals totals(List<Transaction> txs) {
        long gross = 0, gw = 0, plat = 0, net = 0;
        Set<String> people = new HashSet<>();
        for (Transaction t : txs) {
            gross += amount(t); gw += gatewayFee(t); plat += platformFee(t); net += net(t);
            String k = customerKey(t.getPhone());
            if (k != null) people.add(k);
        }
        long sales = txs.size();
        return new Totals(gross, gw, plat, net, sales, sales == 0 ? 0 : Math.round((double) gross / sales),
                people.size(), people.isEmpty() ? 0 : Math.round((double) gross / people.size()));
    }

    private List<SeriesPoint> series(Resolved r, Bucket bucket, List<Transaction> cur, List<Transaction> prev) {
        List<OffsetDateTime> starts = bucketStarts(r.cur(), bucket);
        List<OffsetDateTime> prevStarts = bucketStarts(r.prev(), bucket);
        int n = starts.size();
        long[] gross = new long[n], net = new long[n], fees = new long[n], sales = new long[n];
        long[] pg = new long[prevStarts.size()];
        for (Transaction t : cur) {
            int i = indexOf(starts, t.getCreatedAt());
            if (i < 0) continue;
            gross[i] += amount(t); net[i] += net(t); fees[i] += gatewayFee(t) + platformFee(t); sales[i]++;
        }
        for (Transaction t : prev) {
            int i = indexOf(prevStarts, t.getCreatedAt());
            if (i >= 0) pg[i] += amount(t);
        }
        List<SeriesPoint> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new SeriesPoint(starts.get(i).atZoneSameInstant(clock.zone()).toLocalDateTime().toString(),
                    bucketLabel(starts.get(i), bucket), gross[i], net[i], fees[i], sales[i],
                    i < pg.length ? pg[i] : null));
        }
        return out;
    }

    static final Map<String, String> CHANNEL_LABELS = linked(
            "MTN", "MTN Mobile Money", "AIRTEL", "Airtel Money", "OTHER", "Other mobile money", "WALK_IN", "Walk-in / cash");
    static final Map<String, String> GROUP_LABELS = linked(
            "STUDENT", "Students", "PAID_CUSTOMER", "Other paying customers", "WALK_IN", "Walk-in / cash");

    private static Map<String, String> linked(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return Collections.unmodifiableMap(m);
    }

    static List<Slice> byChannel(List<Transaction> cur, List<Transaction> prev) {
        return slices(CHANNEL_LABELS, cur, prev, FinancialAnalysisService::channel);
    }

    static List<Slice> byGroup(List<Transaction> cur, List<Transaction> prev, Map<String, Voucher> byCode) {
        return slices(GROUP_LABELS, cur, prev, t -> group(t, byCode));
    }

    private static List<Slice> slices(Map<String, String> labels, List<Transaction> cur, List<Transaction> prev,
                                      java.util.function.Function<Transaction, String> keyOf) {
        Map<String, long[]> agg = new LinkedHashMap<>();
        labels.keySet().forEach(k -> agg.put(k, new long[3]));
        for (Transaction t : cur) { long[] a = agg.get(keyOf.apply(t)); if (a != null) { a[0] += amount(t); a[1]++; } }
        for (Transaction t : prev) { long[] a = agg.get(keyOf.apply(t)); if (a != null) a[2] += amount(t); }
        List<Slice> out = new ArrayList<>();
        agg.forEach((k, a) -> out.add(new Slice(k, labels.get(k), a[0], a[1], a[2])));
        return out;
    }

    /** Every package (or router) the owner has, so ones that sell nothing show up too; best first. */
    private static List<Ranked> ranked(List<Transaction> cur, List<Transaction> prev, Map<Long, String> names,
                                       Map<Long, Long> prices, boolean byPlan) {
        record Agg(long[] v, Set<String> people) {}
        Map<Long, Agg> agg = new LinkedHashMap<>();
        names.keySet().forEach(id -> agg.put(id, new Agg(new long[4], new HashSet<>())));
        for (Transaction t : cur) {
            Long id = byPlan ? planId(t) : routerId(t);
            Agg a = agg.computeIfAbsent(id == null ? 0L : id, x -> new Agg(new long[4], new HashSet<>()));
            a.v()[0] += amount(t); a.v()[1] += net(t); a.v()[2]++;
            String k = customerKey(t.getPhone());
            if (k != null) a.people().add(k);
        }
        for (Transaction t : prev) {
            Long id = byPlan ? planId(t) : routerId(t);
            agg.computeIfAbsent(id == null ? 0L : id, x -> new Agg(new long[4], new HashSet<>())).v()[3] += amount(t);
        }
        List<Ranked> out = new ArrayList<>();
        agg.forEach((id, a) -> {
            String name = names.get(id);
            if (name == null) name = id == 0L ? (byPlan ? "No package" : "No router") : (byPlan ? "Deleted package" : "Removed router");
            out.add(new Ranked(id, name, a.v()[0], a.v()[1], a.v()[2], a.people().size(), a.v()[3], prices.getOrDefault(id, 0L)));
        });
        out.sort(Comparator.comparingLong(Ranked::gross).reversed().thenComparing(Ranked::previousGross, Comparator.reverseOrder()));
        return out;
    }

    private long[][] heatGross(List<Transaction> cur) {
        long[][] g = new long[7][24];
        for (Transaction t : cur) {
            var local = t.getCreatedAt().atZoneSameInstant(clock.zone());
            g[local.getDayOfWeek().getValue() - 1][local.getHour()] += amount(t);
        }
        return g;
    }

    private int[][] heatSales(List<Transaction> cur) {
        int[][] s = new int[7][24];
        for (Transaction t : cur) {
            var local = t.getCreatedAt().atZoneSameInstant(clock.zone());
            s[local.getDayOfWeek().getValue() - 1][local.getHour()]++;
        }
        return s;
    }

    private Customers customers(Long tenantId, List<Transaction> all, Resolved r, List<Transaction> cur, List<Transaction> prev) {
        // "New" means the customer's first ever purchase with this business falls in the period.
        Map<String, OffsetDateTime> first = new HashMap<>();
        for (Transaction t : all) {
            if (!successful(t) || t.getCreatedAt() == null) continue;
            String k = customerKey(t.getPhone());
            if (k != null) first.merge(k, t.getCreatedAt(), (a, b) -> a.isBefore(b) ? a : b);
        }
        record Spend(long[] v, boolean[] student, OffsetDateTime[] last) {}
        Map<String, Spend> spend = new HashMap<>();
        for (Transaction t : cur) {
            String k = customerKey(t.getPhone());
            if (k == null) continue;
            Spend s = spend.computeIfAbsent(k, x -> new Spend(new long[2], new boolean[1], new OffsetDateTime[1]));
            s.v()[0] += amount(t); s.v()[1]++;
            if (t.getPersonId() != null) s.student()[0] = true;
            if (s.last()[0] == null || t.getCreatedAt().isAfter(s.last()[0])) s.last()[0] = t.getCreatedAt();
        }
        Map<String, Long> prevSpend = new HashMap<>();
        for (Transaction t : prev) {
            String k = customerKey(t.getPhone());
            if (k != null) prevSpend.merge(k, amount(t), Long::sum);
        }
        long newC = 0, newGross = 0, retGross = 0, repeat = 0;
        for (var e : spend.entrySet()) {
            boolean isNew = r.cur().has(first.get(e.getKey()));
            if (isNew) { newC++; newGross += e.getValue().v()[0]; } else retGross += e.getValue().v()[0];
            if (e.getValue().v()[1] >= 2) repeat++;
        }
        long lapsed = 0, lapsedGross = 0;
        for (var e : prevSpend.entrySet()) {
            if (!spend.containsKey(e.getKey())) { lapsed++; lapsedGross += e.getValue(); }
        }
        // Students' identities stay out of the owner's money pages (school rule): top list is non-students only.
        Map<String, String> names = new HashMap<>();
        for (Customer c : customerRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId)) {
            String k = customerKey(c.getPhone());
            if (k != null) names.put(k, c.getName());
        }
        List<TopCustomer> top = spend.entrySet().stream()
                .filter(e -> !e.getValue().student()[0])
                .sorted((a, b) -> Long.compare(b.getValue().v()[0], a.getValue().v()[0]))
                .limit(10)
                .map(e -> new TopCustomer("0" + e.getKey(), names.get(e.getKey()), e.getValue().v()[0], e.getValue().v()[1],
                        e.getValue().last()[0] == null ? null : e.getValue().last()[0].toString()))
                .toList();
        return new Customers(spend.size(), newC, spend.size() - newC, newGross, retGross, repeat, lapsed, lapsedGross,
                prevSpend.size(), top);
    }

    private Lost lost(List<Transaction> all, Resolved r, Predicate<Transaction> matches, OffsetDateTime now) {
        Map<String, long[]> net = new LinkedHashMap<>();
        for (String k : List.of("MTN", "AIRTEL", "OTHER")) net.put(k, new long[3]); // failed, abandoned, value
        long failed = 0, failedValue = 0, abandoned = 0, abandonedValue = 0, ok = 0;
        long pFail = 0, pOk = 0;
        OffsetDateTime abandonedBefore = now.minus(ABANDONED_AFTER);
        for (Transaction t : all) {
            if (!online(t) || t.getCreatedAt() == null) continue;
            boolean inCur = r.cur().has(t.getCreatedAt());
            boolean inPrev = !inCur && r.prev().has(t.getCreatedAt());
            if ((!inCur && !inPrev) || !matches.test(t)) continue;
            boolean isFailed = "FAILED".equalsIgnoreCase(t.getStatus());
            boolean isAbandoned = "PENDING".equalsIgnoreCase(t.getStatus()) && t.getCreatedAt().isBefore(abandonedBefore);
            if (inPrev) {
                if (successful(t)) pOk++; else if (isFailed || isAbandoned) pFail++;
                continue;
            }
            if (successful(t)) { ok++; continue; }
            if (!isFailed && !isAbandoned) continue;
            long[] a = net.get(channel(t));
            if (a == null) a = net.get("OTHER");
            if (isFailed) { failed++; failedValue += amount(t); a[0]++; } else { abandoned++; abandonedValue += amount(t); a[1]++; }
            a[2] += amount(t);
        }
        long attempts = ok + failed + abandoned;
        List<LostByNetwork> byNet = new ArrayList<>();
        net.forEach((k, a) -> byNet.add(new LostByNetwork(k, CHANNEL_LABELS.get(k), a[0], a[1], a[2])));
        return new Lost(failed, failedValue, abandoned, abandonedValue, attempts,
                attempts == 0 ? null : (double) ok / attempts,
                pOk + pFail == 0 ? null : (double) pOk / (pOk + pFail), byNet);
    }

    private MoneyFlow moneyFlow(Long tenantId, List<Transaction> all, Window w) {
        long gross = 0, gw = 0, plat = 0, net = 0;
        for (Transaction t : all) {
            if (successful(t) && w.has(t.getCreatedAt())) { gross += amount(t); gw += gatewayFee(t); plat += platformFee(t); net += net(t); }
        }
        long withdrawn = 0, wFees = 0, wCount = 0;
        for (WithdrawalRequest wr : withdrawalRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId)) {
            if (!WithdrawalRequest.COMPLETED.equals(wr.getStatus())) continue;
            OffsetDateTime at = wr.getCompletedAt() != null ? wr.getCompletedAt() : wr.getUpdatedAt() != null ? wr.getUpdatedAt() : wr.getCreatedAt();
            if (!w.has(at)) continue;
            withdrawn += wr.getAmount() == null ? 0 : wr.getAmount();
            wFees += wr.getFee() == null ? 0 : wr.getFee();
            wCount++;
        }
        long subs = 0;
        for (SubscriptionPayment p : subscriptionPaymentRepository
                .findAllByTenantIdAndPaidAtGreaterThanEqualAndPaidAtLessThan(tenantId, w.start(), w.end())) {
            subs += p.getAmount() == null ? 0 : p.getAmount();
        }
        var wallet = walletRepository.findByTenantId(tenantId).orElse(null);
        return new MoneyFlow(gross, gw, plat, net, withdrawn, wFees, wCount, subs,
                wallet == null || wallet.getAvailableBalance() == null ? 0 : wallet.getAvailableBalance(),
                wallet == null || wallet.getPendingBalance() == null ? 0 : wallet.getPendingBalance(),
                wallet == null || wallet.getReservedBalance() == null ? 0 : wallet.getReservedBalance());
    }

    private Target target(Tenant tenant, List<Transaction> all, OffsetDateTime now) {
        LocalDate today = clock.today();
        LocalDate m1 = today.withDayOfMonth(1);
        OffsetDateTime monthStart = day(m1);
        OffsetDateTime nextMonth = day(m1.plusMonths(1));
        OffsetDateTime lastMonthStart = day(m1.minusMonths(1));
        OffsetDateTime lastMonthSame = back(now, java.time.Period.ofMonths(1));
        if (lastMonthSame.isAfter(monthStart)) lastMonthSame = monthStart;
        Window mtd = new Window(monthStart, now);
        Window last = new Window(lastMonthStart, monthStart);
        Window lastSame = new Window(lastMonthStart, lastMonthSame);
        long mtdGross = 0, lastGross = 0, lastSameGross = 0;
        for (Transaction t : all) {
            if (!successful(t)) continue;
            OffsetDateTime at = t.getCreatedAt();
            if (mtd.has(at)) mtdGross += amount(t);
            if (last.has(at)) lastGross += amount(t);
            if (lastSame.has(at)) lastSameGross += amount(t);
        }
        double elapsed = Math.max(Duration.ofHours(1).toSeconds(), Duration.between(monthStart, now).toSeconds());
        double length = Duration.between(monthStart, nextMonth).toSeconds();
        long projected = Math.round(mtdGross * Math.max(1.0, length / elapsed));
        return new Target(tenant.getMonthlySalesTarget(), mtdGross, lastSameGross, lastGross, projected,
                today.getDayOfMonth(), today.lengthOfMonth(),
                m1.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)));
    }

    // ---------------------------------------------------------------- target setting

    @Transactional
    public Long setTarget(Long tenantId, Long target) {
        if (target != null && (target < 1 || target > MAX_TARGET)) {
            throw badRequest("Enter a monthly target between UGX 1 and UGX 10,000,000,000, or clear it.");
        }
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        tenant.setMonthlySalesTarget(target);
        tenantRepository.save(tenant);
        return target;
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
