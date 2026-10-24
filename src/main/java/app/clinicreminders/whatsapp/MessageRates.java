package app.clinicreminders.whatsapp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

import app.clinicreminders.common.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rate card the cost estimates come from, kept by the operator from Meta's published rates (we
 * never guess a price). A new rate applies from its date on; an estimate already recorded is not
 * changed, as {@code message_log} keeps no phone numbers to find a message's market again.
 */
@Service
@Transactional
public class MessageRates {

    /** Above this, a rate is a typo (cents typed as dollars): no WhatsApp message costs a dollar. */
    static final BigDecimal MAX_USD = BigDecimal.ONE;
    private static final Pattern CATEGORY = Pattern.compile("[a-z][a-z_]{1,39}");

    private final MessageRateRepository rates;
    private final WhatsAppProperties properties;

    MessageRates(MessageRateRepository rates, WhatsAppProperties properties) {
        this.rates = rates;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<MessageRate> all() {
        return rates.findAllByOrderByMarketAscPricingCategoryAscValidFromDesc();
    }

    /** The markets estimates are made for ({@code whatsapp.markets}); a rate for any other would never be used. */
    public SortedSet<String> markets() {
        return new TreeSet<>(properties.markets().values());
    }

    /** The categories our messages are sent in: the templates' (as configured) and service. */
    public SortedSet<String> categories() {
        SortedSet<String> categories = new TreeSet<>(properties.templateCategories().values());
        categories.add(MessageCostEstimator.SERVICE);
        return categories;
    }

    /**
     * @throws IllegalArgumentException for an unknown market, a malformed category, a price below zero, from a
     *                                  dollar up or with more than six decimals, or a rate already set from that date
     */
    public MessageRate add(String market, String category, BigDecimal usdPerMessage, LocalDate validFrom) {
        String cleanCategory = category.strip().toLowerCase();
        if (!markets().contains(market) || !CATEGORY.matcher(cleanCategory).matches()) {
            throw new IllegalArgumentException("Unknown market or malformed category: " + market + ", " + category);
        }
        if (usdPerMessage.signum() < 0 || usdPerMessage.compareTo(MAX_USD) >= 0
                || usdPerMessage.stripTrailingZeros().scale() > 6) {
            throw new IllegalArgumentException("Not a per-message price in dollars: " + usdPerMessage);
        }
        if (rates.existsByMarketAndPricingCategoryAndValidFrom(market, cleanCategory, validFrom)) {
            throw new IllegalArgumentException("A rate is already set from " + validFrom);
        }
        return rates.save(new MessageRate(market, cleanCategory, usdPerMessage, validFrom));
    }

    /** A rate entered by mistake; messages it priced keep their estimate. */
    public void delete(long id) {
        rates.delete(rates.findById(id).orElseThrow(() -> new NotFoundException("Rate", id)));
    }
}
