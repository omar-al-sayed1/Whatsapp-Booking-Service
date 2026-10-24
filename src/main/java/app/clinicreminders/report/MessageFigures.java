package app.clinicreminders.report;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

/**
 * The messages a clinic sent in a month in one Meta pricing category.
 *
 * @param category     {@code utility}, {@code service}...; null for sends that failed before Meta priced them
 * @param sent         sent (delivered or not); failed sends are not charged
 * @param failed       failed sends
 * @param estimatedUsd the estimates of the sent ones, in US dollars
 * @param unpriced     sent ones with no estimate: no rate was set for their market and category when they went out
 */
public record MessageFigures(@Nullable String category, long sent, long failed, BigDecimal estimatedUsd, long unpriced) {
}
