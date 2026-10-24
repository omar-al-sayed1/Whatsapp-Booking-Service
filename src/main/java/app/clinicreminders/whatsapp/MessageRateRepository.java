package app.clinicreminders.whatsapp;

import java.time.LocalDate;
import java.util.List;
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

    /** The rate card as the operator keeps it: by market and category, latest first. */
    List<MessageRate> findAllByOrderByMarketAscPricingCategoryAscValidFromDesc();

    Optional<MessageRate> findById(Long id);

    boolean existsByMarketAndPricingCategoryAndValidFrom(String market, String pricingCategory, LocalDate validFrom);

    MessageRate save(MessageRate rate);

    void delete(MessageRate rate);
}
