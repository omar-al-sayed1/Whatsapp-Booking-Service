package app.clinicreminders.whatsapp;

import java.math.BigDecimal;
import java.time.LocalDate;

import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Estimates what Meta charges for a message: the {@code message_rate} for the recipient's market
 * and the message's pricing category.
 *
 * <p>Meta prices by the recipient's country calling code ({@code whatsapp.markets}). Since
 * 1 October 2026 service messages and utility templates are charged inside the customer service
 * window too, so the send-time estimate assumes a charge; status webhooks then report Meta's
 * actual category and whether the message was free, and {@link #reprice} corrects the estimate.
 * Meta's monthly free allowance of service messages is left to the monthly report.
 */
@Component
public class MessageCostEstimator {

    static final String SERVICE = "service";

    private final WhatsAppProperties properties;
    private final MessageRateRepository rates;

    MessageCostEstimator(WhatsAppProperties properties, MessageRateRepository rates) {
        this.properties = properties;
        this.rates = rates;
    }

    /** Templates use their approved category; everything else is a service message. */
    public @Nullable String categoryFor(OutboundMessage message) {
        return message instanceof TemplateMessage template
                ? properties.templateCategories().get(template.name())
                : SERVICE;
    }

    /** The market of the longest configured calling code that prefixes the number. */
    public @Nullable String marketFor(@Nullable String phoneE164) {
        if (phoneE164 == null) {
            return null;
        }
        String digits = phoneE164.substring(1);
        String market = null;
        int matched = 0;
        for (var entry : properties.markets().entrySet()) {
            if (digits.startsWith(entry.getKey()) && entry.getKey().length() > matched) {
                market = entry.getValue();
                matched = entry.getKey().length();
            }
        }
        return market;
    }

    /** Null when the market, category or rate is unknown. */
    public @Nullable BigDecimal estimate(@Nullable String phoneE164, @Nullable String category, LocalDate date) {
        String market = marketFor(phoneE164);
        if (market == null || category == null) {
            return null;
        }
        return rates.findInForce(market, category, date).map(MessageRate::getUsdPerMessage).orElse(null);
    }

    /**
     * The estimate after a status webhook reported Meta's pricing.
     *
     * @param pricingType {@code regular}, {@code free_customer_service} or {@code free_entry_point}
     */
    public @Nullable BigDecimal reprice(@Nullable String recipientPhoneE164, String category,
                                        @Nullable String pricingType, @Nullable BigDecimal current, LocalDate date) {
        if (pricingType != null && pricingType.startsWith("free")) {
            return BigDecimal.ZERO;
        }
        BigDecimal estimate = estimate(recipientPhoneE164, category, date);
        return estimate != null ? estimate : current;
    }
}
