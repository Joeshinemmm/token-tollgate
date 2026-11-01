package tokentollgate.pricing

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bound from `tollgate.prices.file`, which Spring Boot's relaxed binding also reads from the
 * `TOLLGATE_PRICES_FILE` environment variable. When set, the file is imported right after startup;
 * rows that already exist are left alone.
 */
@ConfigurationProperties("tollgate.prices")
data class PricingProperties(val file: String? = null)
