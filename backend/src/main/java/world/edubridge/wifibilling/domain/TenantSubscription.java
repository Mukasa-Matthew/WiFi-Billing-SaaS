package world.edubridge.wifibilling.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tenant_subscriptions", schema = "public")
public class TenantSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "plan_tier", nullable = false, length = 50)
    private String planTier = "starter";

    @Column(name = "router_limit", nullable = false)
    private Integer routerLimit = 5;

    @Column(name = "voucher_limit", nullable = false)
    private Integer voucherLimit = 5000;

    @Column(nullable = false, length = 20)
    private String status = "active";

    @Column(name = "billing_cycle", nullable = false, length = 20)
    private String billingCycle = "monthly";

    @Column(name = "current_period_start", nullable = false)
    private OffsetDateTime currentPeriodStart = OffsetDateTime.now();

    @Column(name = "current_period_end", nullable = false)
    private OffsetDateTime currentPeriodEnd = OffsetDateTime.now().plusMonths(1);

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getPlanTier() { return planTier; }
    public void setPlanTier(String planTier) { this.planTier = planTier; }
    public Integer getRouterLimit() { return routerLimit; }
    public void setRouterLimit(Integer routerLimit) { this.routerLimit = routerLimit; }
    public Integer getVoucherLimit() { return voucherLimit; }
    public void setVoucherLimit(Integer voucherLimit) { this.voucherLimit = voucherLimit; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getBillingCycle() { return billingCycle; }
    public void setBillingCycle(String billingCycle) { this.billingCycle = billingCycle; }
    public OffsetDateTime getCurrentPeriodStart() { return currentPeriodStart; }
    public void setCurrentPeriodStart(OffsetDateTime currentPeriodStart) { this.currentPeriodStart = currentPeriodStart; }
    public OffsetDateTime getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(OffsetDateTime currentPeriodEnd) { this.currentPeriodEnd = currentPeriodEnd; }
}
