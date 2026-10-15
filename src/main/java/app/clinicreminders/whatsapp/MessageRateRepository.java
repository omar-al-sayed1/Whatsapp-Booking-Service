package app.clinicreminders.whatsapp;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Meta's rate card. Global reference data, so no clinic filter. */
public interface MessageRateRepository extends Repository<MessageRate, Long> {

    /** The rate in force on {@code date}: the latest one that became valid on or before it. */
    @Query("""
            select r from MessageRate r
            where r.market = :market and r.pricingCategory = :category and r.validFrom <= :date
            order by r.validFrom desc
            limit 1""")
    Optional<MessageRate> findInForce(String market, String category, LocalDate date);

    MessageRate save(MessageRate rate);
}
