package world.edubridge.wifibilling.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tenants", schema = "public")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, unique = true, length = 64)
    private String slug;

    @Column(nullable = false, length = 20)
    private String status = "active";

    @Column(name = "company_name", length = 255)
    private String companyName;

    @Column(name = "contact_name", length = 255)
    private String contactName;

    @Column(name = "contact_email", nullable = false, length = 255)
    private String contactEmail;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(length = 10)
    private String currency = "UGX";

    @Column(length = 50)
    private String timezone = "Africa/Kampala";

    @Column(name = "branding_logo_url", columnDefinition = "TEXT")
    private String brandingLogoUrl;

    @Column(name = "branding_primary_color", length = 10)
    private String brandingPrimaryColor = "#2563eb";

    @Column(name = "branding_secondary_color", length = 10)
    private String brandingSecondaryColor = "#0f172a";

    @Column(name = "whatsapp_phone", length = 50)
    private String whatsappPhone;

    @Column(name = "support_phone", length = 50)
    private String supportPhone;

    @Column(name = "portal_welcome_title", length = 255)
    private String portalWelcomeTitle = "Fast, Reliable High-Speed WiFi";

    @Column(name = "portal_welcome_message", columnDefinition = "TEXT")
    private String portalWelcomeMessage = "Select a package below to get online instantly or enter an existing voucher code.";

    @Column(name = "portal_promotional_banner", columnDefinition = "TEXT")
    private String portalPromotionalBanner;

    @Column(name = "portal_enable_mtn_momo", nullable = false)
    private boolean portalEnableMtnMomo = true;

    @Column(name = "portal_enable_airtel_money", nullable = false)
    private boolean portalEnableAirtelMoney = true;

    @Column(name = "portal_enable_voucher_code", nullable = false)
    private boolean portalEnableVoucherCode = true;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getBrandingLogoUrl() { return brandingLogoUrl; }
    public void setBrandingLogoUrl(String brandingLogoUrl) { this.brandingLogoUrl = brandingLogoUrl; }
    public String getBrandingPrimaryColor() { return brandingPrimaryColor; }
    public void setBrandingPrimaryColor(String brandingPrimaryColor) { this.brandingPrimaryColor = brandingPrimaryColor; }
    public String getBrandingSecondaryColor() { return brandingSecondaryColor; }
    public void setBrandingSecondaryColor(String brandingSecondaryColor) { this.brandingSecondaryColor = brandingSecondaryColor; }
    public String getWhatsappPhone() { return whatsappPhone; }
    public void setWhatsappPhone(String whatsappPhone) { this.whatsappPhone = whatsappPhone; }
    public String getSupportPhone() { return supportPhone; }
    public void setSupportPhone(String supportPhone) { this.supportPhone = supportPhone; }
    public String getPortalWelcomeTitle() { return portalWelcomeTitle; }
    public void setPortalWelcomeTitle(String portalWelcomeTitle) { this.portalWelcomeTitle = portalWelcomeTitle; }
    public String getPortalWelcomeMessage() { return portalWelcomeMessage; }
    public void setPortalWelcomeMessage(String portalWelcomeMessage) { this.portalWelcomeMessage = portalWelcomeMessage; }
    public String getPortalPromotionalBanner() { return portalPromotionalBanner; }
    public void setPortalPromotionalBanner(String portalPromotionalBanner) { this.portalPromotionalBanner = portalPromotionalBanner; }
    public boolean isPortalEnableMtnMomo() { return portalEnableMtnMomo; }
    public void setPortalEnableMtnMomo(boolean portalEnableMtnMomo) { this.portalEnableMtnMomo = portalEnableMtnMomo; }
    public boolean isPortalEnableAirtelMoney() { return portalEnableAirtelMoney; }
    public void setPortalEnableAirtelMoney(boolean portalEnableAirtelMoney) { this.portalEnableAirtelMoney = portalEnableAirtelMoney; }
    public boolean isPortalEnableVoucherCode() { return portalEnableVoucherCode; }
    public void setPortalEnableVoucherCode(boolean portalEnableVoucherCode) { this.portalEnableVoucherCode = portalEnableVoucherCode; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
