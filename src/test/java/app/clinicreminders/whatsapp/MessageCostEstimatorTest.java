package app.clinicreminders.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TextMessage;
import org.junit.jupiter.api.Test;

class MessageCostEstimatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    private final InMemoryRates rates = new InMemoryRates();
    private final MessageCostEstimator estimator = new MessageCostEstimator(TestWhatsAppProperties.fake(), rates);

    @Test
    void templatesUseTheirApprovedCategoryAndEverythingElseIsService() {
        assertThat(estimator.categoryFor(new TemplateMessage("+96170123456", "appointment_reminder", "ar", List.of())))
                .isEqualTo("utility");
        assertThat(estimator.categoryFor(new TemplateMessage("+96170123456", "unknown_template", "ar", List.of())))
                .isNull();
        assertThat(estimator.categoryFor(new TextMessage("+96170123456", "hi"))).isEqualTo("service");
    }

    @Test
    void theMarketComesFromTheCallingCode() {
        assertThat(estimator.marketFor("+96170123456")).isEqualTo("Rest of Middle East");
        assertThat(estimator.marketFor("+16505551234")).isEqualTo("North America");
        assertThat(estimator.marketFor("+33612345678")).isNull();
        assertThat(estimator.marketFor(null)).isNull();
    }

    @Test
    void usesTheRateInForceOnTheDay() {
        rates.add("Rest of Middle East", "utility", "0.0100", LocalDate.of(2026, 7, 1));
        rates.add("Rest of Middle East", "utility", "0.0110", LocalDate.of(2026, 10, 1));
        rates.add("Rest of Middle East", "utility", "0.0120", LocalDate.of(2027, 1, 1));

        assertThat(estimator.estimate("+96170123456", "utility", TODAY)).isEqualByComparingTo("0.0110");
        assertThat(estimator.estimate("+96170123456", "utility", LocalDate.of(2026, 9, 30))).isEqualByComparingTo("0.0100");
    }

    @Test
    void unknownMarketCategoryOrRateGivesNoEstimate() {
        rates.add("Rest of Middle East", "utility", "0.0110", LocalDate.of(2026, 10, 1));

        assertThat(estimator.estimate("+33612345678", "utility", TODAY)).isNull();
        assertThat(estimator.estimate("+96170123456", null, TODAY)).isNull();
        assertThat(estimator.estimate("+96170123456", "marketing", TODAY)).isNull();
    }

    @Test
    void repricingFollowsWhatMetaReported() {
        rates.add("Rest of Middle East", "service", "0.0080", LocalDate.of(2026, 10, 1));
        BigDecimal current = new BigDecimal("0.0110");

        assertThat(estimator.reprice("+96170123456", "service", "free_customer_service", current, TODAY))
                .isEqualByComparingTo("0");
        assertThat(estimator.reprice("+96170123456", "service", "regular", current, TODAY))
                .isEqualByComparingTo("0.0080");
        assertThat(estimator.reprice(null, "service", "regular", current, TODAY)).isEqualByComparingTo("0.0110");
    }

    private static final class InMemoryRates implements MessageRateRepository {

        private final List<MessageRate> rates = new ArrayList<>();

        void add(String market, String category, String usd, LocalDate validFrom) {
            rates.add(new MessageRate(market, category, new BigDecimal(usd), validFrom));
        }

        @Override
        public Optional<MessageRate> findInForce(String market, String category, LocalDate date) {
            return rates.stream()
                    .filter(r -> r.getMarket().equals(market) && r.getPricingCategory().equals(category))
                    .filter(r -> !r.getValidFrom().isAfter(date))
                    .max(Comparator.comparing(MessageRate::getValidFrom));
        }

        @Override
        public MessageRate save(MessageRate rate) {
            rates.add(rate);
            return rate;
        }
    }
}
