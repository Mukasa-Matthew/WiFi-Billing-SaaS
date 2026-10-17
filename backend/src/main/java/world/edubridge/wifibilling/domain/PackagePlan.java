package world.edubridge.wifibilling.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "packages", schema = "public")
public class PackagePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    @Column(nullable = false, length = 10)
    private String currency = "UGX";

    @Column(name = "duration_seconds", nullable = false)
    private Integer durationSeconds = 3600;

    @Column(name = "rate_limit_up_kbps", nullable = false)
    private Integer rateLimitUpKbps = 2048;

    @Column(name = "rate_limit_down_kbps", nullable = false)
    private Integer rateLimitDownKbps = 5120;

    @Column(name = "simultaneous_use", nullable = false)
    private Integer simultaneousUse = 1;

    @Column(name = "session_timeout", nullable = false)
    private Integer sessionTimeout = 3600;

    @Column(name = "interim_interval", nullable = false)
    private Integer interimInterval = 300;

    @Column(name = "data_quota_bytes")
    private Long dataQuotaBytes;

    @Column(name = "is_free_trial")
    private boolean freeTrial = false;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "portal_visible", nullable = false)
    private boolean portalVisible = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "badge_text", length = 50)
    private String badgeText;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }
    public Integer getRateLimitUpKbps() { return rateLimitUpKbps; }
    public void setRateLimitUpKbps(Integer rateLimitUpKbps) { this.rateLimitUpKbps = rateLimitUpKbps; }
    public Integer getRateLimitDownKbps() { return rateLimitDownKbps; }
    public void setRateLimitDownKbps(Integer rateLimitDownKbps) { this.rateLimitDownKbps = rateLimitDownKbps; }
    public Integer getSimultaneousUse() { return simultaneousUse; }
    public void setSimultaneousUse(Integer simultaneousUse) { this.simultaneousUse = simultaneousUse; }
    public Integer getSessionTimeout() { return sessionTimeout; }
    public void setSessionTimeout(Integer sessionTimeout) { this.sessionTimeout = sessionTimeout; }
    public Integer getInterimInterval() { return interimInterval; }
    public void setInterimInterval(Integer interimInterval) { this.interimInterval = interimInterval; }
    public Long getDataQuotaBytes() { return dataQuotaBytes; }
    public void setDataQuotaBytes(Long dataQuotaBytes) { this.dataQuotaBytes = dataQuotaBytes; }
    public boolean isFreeTrial() { return freeTrial; }
    public void setFreeTrial(boolean freeTrial) { this.freeTrial = freeTrial; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isPortalVisible() { return portalVisible; }
    public void setPortalVisible(boolean portalVisible) { this.portalVisible = portalVisible; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getBadgeText() { return badgeText; }
    public void setBadgeText(String badgeText) { this.badgeText = badgeText; }
}
