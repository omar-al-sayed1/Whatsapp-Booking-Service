package app.clinicreminders.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository method on a tenant-owned entity that deliberately does not filter by
 * {@code clinic_id} (e.g. login by username, the reminder scheduler, webhook routing).
 * Every other method must take a {@code clinicId} parameter; {@code TenantRepositoryConventionTest}
 * enforces this.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CrossTenant {

    /** Why this query has to see every clinic. */
    String value();
}
