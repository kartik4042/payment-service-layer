package com.payment.orchestration.routing

import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.domain.model.Provider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Geographic Routing Engine
 * 
 * Routes payments to optimal providers based on customer geography.
 * 
 * Routing Strategy:
 * - India (IN) → Provider B (Razorpay-like, optimized for India)
 * - Europe (EU countries) → Provider A (Adyen-like, optimized for EU)
 * - United States (US) → Provider A (Stripe-like, optimized for US)
 * - Other countries → Provider A (default)
 * 
 * Benefits:
 * - Lower transaction fees in local markets
 * - Better success rates with local providers
 * - Compliance with local regulations
 * - Faster settlement times
 * 
 * Configuration:
 * - Routing rules are configurable per country
 * - Fallback providers for each region
 * - Override rules for specific payment methods
 * 
 * @property routingConfig Geographic routing configuration
 */
@Service
class GeographicRoutingEngine(
    private val routingConfig: GeographicRoutingConfig = GeographicRoutingConfig.default()
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Select provider based on customer country
     * 
     * @param country ISO 3166-1 alpha-2 country code (e.g., "US", "IN", "GB")
     * @param paymentMethod Payment method type
     * @param amount Transaction amount in cents
     * @param currency Currency code
     * @return Selected provider
     */
    fun selectProvider(
        country: String,
        paymentMethod: PaymentMethod,
        amount: Long,
        currency: String
    ): ProviderSelection {
        logger.debug("Selecting provider for country: $country, method: $paymentMethod")

        // 1. Check for payment method specific routing
        val methodSpecificProvider = selectByPaymentMethod(paymentMethod, country)
        if (methodSpecificProvider != null) {
            logger.info("Selected provider by payment method: ${methodSpecificProvider.primary}")
            return methodSpecificProvider
        }

        // 2. Check for country-specific routing
        val countrySpecificProvider = selectByCountry(country)
        if (countrySpecificProvider != null) {
            logger.info("Selected provider by country: ${countrySpecificProvider.primary}")
            return countrySpecificProvider
        }

        // 3. Check for region-specific routing
        val regionSpecificProvider = selectByRegion(country)
        if (regionSpecificProvider != null) {
            logger.info("Selected provider by region: ${regionSpecificProvider.primary}")
            return regionSpecificProvider
        }

        // 4. Use default provider
        val defaultProvider = routingConfig.defaultProvider
        logger.info("Using default provider: $defaultProvider")
        
        return ProviderSelection(
            primary = defaultProvider,
            fallbacks = routingConfig.getFallbackProviders(defaultProvider),
            reason = "default_routing"
        )
    }

    /**
     * Select provider based on payment method
     * 
     * Some payment methods work better with specific providers.
     * 
     * @param paymentMethod Payment method
     * @param country Customer country
     * @return Provider selection or null if no specific routing
     */
    private fun selectByPaymentMethod(
        paymentMethod: PaymentMethod,
        country: String
    ): ProviderSelection? {
        return when (paymentMethod) {
            PaymentMethod.UPI -> {
                // UPI is India-specific, route to Provider B
                if (country == "IN") {
                    ProviderSelection(
                        primary = Provider.PROVIDER_B,
                        fallbacks = listOf(Provider.PROVIDER_A),
                        reason = "upi_india_routing"
                    )
                } else {
                    null
                }
            }
            PaymentMethod.CARD -> {
                // Cards work with both providers, use geographic routing
                null
            }
            PaymentMethod.WALLET -> {
                // Wallets may have regional preferences
                if (country == "IN") {
                    ProviderSelection(
                        primary = Provider.PROVIDER_B,
                        fallbacks = listOf(Provider.PROVIDER_A),
                        reason = "wallet_india_routing"
                    )
                } else {
                    null
                }
            }
            PaymentMethod.NET_BANKING -> {
                // Net banking is primarily used in India
                if (country == "IN") {
                    ProviderSelection(
                        primary = Provider.PROVIDER_B,
                        fallbacks = listOf(Provider.PROVIDER_A),
                        reason = "netbanking_india_routing"
                    )
                } else {
                    null
                }
            }
            PaymentMethod.EMI -> {
                // EMI routing based on country
                if (country == "IN") {
                    ProviderSelection(
                        primary = Provider.PROVIDER_B,
                        fallbacks = listOf(Provider.PROVIDER_A),
                        reason = "emi_india_routing"
                    )
                } else {
                    null
                }
            }
        }
    }

    /**
     * Select provider based on specific country
     * 
     * @param country Country code
     * @return Provider selection or null if no specific routing
     */
    private fun selectByCountry(country: String): ProviderSelection? {
        val countryRule = routingConfig.countryRules[country] ?: return null
        
        return ProviderSelection(
            primary = countryRule.provider,
            fallbacks = countryRule.fallbacks,
            reason = "country_routing_$country"
        )
    }

    /**
     * Select provider based on region
     * 
     * @param country Country code
     * @return Provider selection or null if no specific routing
     */
    private fun selectByRegion(country: String): ProviderSelection? {
        // Determine region from country
        val region = when (country) {
            in EUROPE_COUNTRIES -> "EU"
            in ASIA_COUNTRIES -> "ASIA"
            in NORTH_AMERICA_COUNTRIES -> "NA"
            in SOUTH_AMERICA_COUNTRIES -> "SA"
            in AFRICA_COUNTRIES -> "AFRICA"
            in OCEANIA_COUNTRIES -> "OCEANIA"
            else -> null
        }

        if (region == null) return null

        val regionRule = routingConfig.regionRules[region] ?: return null
        
        return ProviderSelection(
            primary = regionRule.provider,
            fallbacks = regionRule.fallbacks,
            reason = "region_routing_$region"
        )
    }

    /**
     * Get routing statistics
     * 
     * @return Routing statistics
     */
    fun getRoutingStatistics(): RoutingStatistics {
        return RoutingStatistics(
            totalCountryRules = routingConfig.countryRules.size,
            totalRegionRules = routingConfig.regionRules.size,
            defaultProvider = routingConfig.defaultProvider
        )
    }

    companion object {
        // Europe countries (EU + EEA)
        val EUROPE_COUNTRIES = setOf(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
            "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
            "PL", "PT", "RO", "SK", "SI", "ES", "SE", "GB", "IS", "LI",
            "NO", "CH"
        )

        // Asia countries
        val ASIA_COUNTRIES = setOf(
            "IN", "CN", "JP", "KR", "SG", "MY", "TH", "ID", "PH", "VN",
            "BD", "PK", "LK", "NP", "MM", "KH", "LA", "BN", "TL", "MN",
            "HK", "MO", "TW"
        )

        // North America countries
        val NORTH_AMERICA_COUNTRIES = setOf(
            "US", "CA", "MX"
        )

        // South America countries
        val SOUTH_AMERICA_COUNTRIES = setOf(
            "BR", "AR", "CL", "CO", "PE", "VE", "EC", "BO", "PY", "UY",
            "GY", "SR", "GF"
        )

        // Africa countries
        val AFRICA_COUNTRIES = setOf(
            "ZA", "NG", "EG", "KE", "GH", "TZ", "UG", "ET", "MA", "DZ",
            "TN", "LY", "SD", "SS", "SO", "DJ", "ER", "AO", "MZ", "ZW",
            "ZM", "MW", "BW", "NA", "LS", "SZ", "MG", "MU", "SC", "KM"
        )

        // Oceania countries
        val OCEANIA_COUNTRIES = setOf(
            "AU", "NZ", "FJ", "PG", "NC", "PF", "SB", "VU", "WS", "GU",
            "KI", "MH", "FM", "NR", "PW", "TO", "TV"
        )
    }
}

/**
 * Provider Selection
 * 
 * Result of routing decision.
 */
data class ProviderSelection(
    val primary: Provider,
    val fallbacks: List<Provider>,
    val reason: String
)

/**
 * Geographic Routing Configuration
 * 
 * Configures routing rules per country and region.
 */
data class GeographicRoutingConfig(
    val countryRules: Map<String, RoutingRule>,
    val regionRules: Map<String, RoutingRule>,
    val defaultProvider: Provider
) {
    /**
     * Get fallback providers for a primary provider
     */
    fun getFallbackProviders(primary: Provider): List<Provider> {
        return Provider.values().filter { it != primary }
    }

    companion object {
        /**
         * Default routing configuration
         */
        fun default(): GeographicRoutingConfig {
            return GeographicRoutingConfig(
                countryRules = mapOf(
                    // India - Route to Provider B (Razorpay-like)
                    "IN" to RoutingRule(
                        provider = Provider.PROVIDER_B,
                        fallbacks = listOf(Provider.PROVIDER_A)
                    ),
                    // United States - Route to Provider A (Stripe-like)
                    "US" to RoutingRule(
                        provider = Provider.PROVIDER_A,
                        fallbacks = listOf(Provider.PROVIDER_B)
                    ),
                    // United Kingdom - Route to Provider A
                    "GB" to RoutingRule(
                        provider = Provider.PROVIDER_A,
                        fallbacks = listOf(Provider.PROVIDER_B)
                    )
                ),
                regionRules = mapOf(
                    // Europe - Route to Provider A (Adyen-like)
                    "EU" to RoutingRule(
                        provider = Provider.PROVIDER_A,
                        fallbacks = listOf(Provider.PROVIDER_B)
                    ),
                    // Asia - Route to Provider B
                    "ASIA" to RoutingRule(
                        provider = Provider.PROVIDER_B,
                        fallbacks = listOf(Provider.PROVIDER_A)
                    ),
                    // North America - Route to Provider A
                    "NA" to RoutingRule(
                        provider = Provider.PROVIDER_A,
                        fallbacks = listOf(Provider.PROVIDER_B)
                    )
                ),
                defaultProvider = Provider.PROVIDER_A
            )
        }

        /**
         * Create custom configuration
         */
        fun custom(
            countryRules: Map<String, RoutingRule> = emptyMap(),
            regionRules: Map<String, RoutingRule> = emptyMap(),
            defaultProvider: Provider = Provider.PROVIDER_A
        ): GeographicRoutingConfig {
            return GeographicRoutingConfig(
                countryRules = countryRules,
                regionRules = regionRules,
                defaultProvider = defaultProvider
            )
        }
    }
}

/**
 * Routing Rule
 * 
 * Defines provider selection for a country or region.
 */
data class RoutingRule(
    val provider: Provider,
    val fallbacks: List<Provider> = emptyList()
)

/**
 * Routing Statistics
 * 
 * Statistics about routing configuration.
 */
data class RoutingStatistics(
    val totalCountryRules: Int,
    val totalRegionRules: Int,
    val defaultProvider: Provider
)


