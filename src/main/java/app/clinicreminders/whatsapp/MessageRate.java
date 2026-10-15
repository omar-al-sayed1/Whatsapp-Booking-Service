package app.clinicreminders.whatsapp;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A price from Meta's rate card. Global reference data, not tenant-owned. */
@Entity
@Table(name = "message_rate")
public class MessageRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String market;

    @Column(name = "pricing_category")
    private String pricingCategory;

    @Column(name = "usd_per_message")
    private BigDecimal usdPerMessage;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    protected MessageRate() {
    }

    public MessageRate(String market, String pricingCategory, BigDecimal usdPerMessage, LocalDate validFrom) {
        this.market = market;
        this.pricingCategory = pricingCategory;
        this.usdPerMessage = usdPerMessage;
        this.validFrom = validFrom;
    }

    public Long getId() {
        return id;
    }

    public String getMarket() {
        return market;
    }

    public String getPricingCategory() {
        return pricingCategory;
    }

    public BigDecimal getUsdPerMessage() {
        return usdPerMessage;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }
}
