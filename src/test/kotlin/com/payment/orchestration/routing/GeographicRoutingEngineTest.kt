package com.payment.orchestration.routing

import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.domain.model.Provider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested

/**
 * Geographic Routing Engine Tests
 * 
 * Test Coverage:
 * - Country-specific routing
 * - Region-specific routing
 * - Payment method routing
 * - Default routing
 * - Fallback provider selection
 * - Custom configuration
 * - Edge cases
 */
@DisplayName("Geographic Routing Engine Tests")
class GeographicRoutingEngineTest {

    private lateinit var routingEngine: GeographicRoutingEngine

    @BeforeEach
    fun setup() {
        routingEngine = GeographicRoutingEngine()
    }

    @Nested
    @DisplayName("Country-Specific Routing Tests")
    inner class CountrySpecificRoutingTests {

        @Test
        @DisplayName("Should route India to Provider B")
        fun testIndiaRouting() {
            // Given
            val country = "IN"
            val paymentMethod = PaymentMethod.CARD
            val amount = 10000L
            val currency = "INR"

            // When
            val selection = routingEngine.selectProvider(country, paymentMethod, amount, currency)

            // Then
            assertEquals(Provider.PROVIDER_B, selection.primary)
            assertTrue(selection.fallbacks.contains(Provider.PROVIDER_A))
            assertTrue(selection.reason.contains("india") || selection.reason.contains("country"))
        }

        @Test
        @DisplayName("Should route United States to Provider A")
        fun testUSRouting() {
            // Given
            val country = "US"
            val paymentMethod = PaymentMethod.CARD
            val amount = 10000L
            val currency = "USD"

            // When
            val selection = routingEngine.selectProvider(country, paymentMethod, amount, currency)

            // Then
            assertEquals(Provider.PROVIDER_A, selection.primary)
            assertTrue(selection.fallbacks.contains(Provider.PROVIDER_B))
            assertTrue(selection.reason.contains("US") || selection.reason.contains("country"))
        }

        @Test
        @DisplayName("Should route United Kingdom to Provider A")
        fun testUKRouting() {
            // Given
            val country = "GB"
            val paymentMethod = PaymentMethod.CARD
            val amount = 10000L
            val currency = "GBP"

            // When
            val selection = routingEngine.selectProvider(country, paymentMethod, amount, currency)

            // Then
            assertEquals(Provider.PROVIDER_A, selection.primary)
            assertTrue(selection.fallbacks.contains(Provider.PROVIDER_B))
        }
    }

    @Nested
    @DisplayName("Region-Specific Routing Tests")
    inner class RegionSpecificRoutingTests {

        @Test
        @DisplayName("Should route European countries to Provider A")
        fun testEuropeRouting() {
            // Test multiple European countries
            val europeanCountries = listOf("DE", "FR", "IT", "ES", "NL", "SE", "NO", "CH")

            europeanCountries.forEach { country ->
                // When
                val selection = routingEngine.selectProvider(
                    country = country,
                    paymentMethod = PaymentMethod.CARD,
                    amount = 10000L,
                    currency = "EUR"
                )

                // Then
                assertEquals(Provider.PROVIDER_A, selection.primary, "Failed for country: $country")
                assertTrue(selection.fallbacks.contains(Provider.PROVIDER_B))
            }
        }

        @Test
        @DisplayName("Should route Asian countries to Provider B")
        fun testAsiaRouting() {
            // Test multiple Asian countries (excluding India which has specific rule)
            val asianCountries = listOf("CN", "JP", "KR", "SG", "MY", "TH", "ID", "PH")

            asianCountries.forEach { country ->
                // When
                val selection = routingEngine.selectProvider(
                    country = country,
                    paymentMethod = PaymentMethod.CARD,
                    amount = 10000L,
                    currency = "USD"
                )

                // Then
                assertEquals(Provider.PROVIDER_B, selection.primary, "Failed for country: $country")
                assertTrue(selection.fallbacks.contains(Provider.PROVIDER_A))
            }
        }

        @Test
        @DisplayName("Should route North American countries to Provider A")
        fun testNorthAmericaRouting() {
            // Test Canada and Mexico (US has specific rule)
            val naCountries = listOf("CA", "MX")

            naCountries.forEach { country ->
                // When
                val selection = routingEngine.selectProvider(
                    country = country,
                    paymentMethod = PaymentMethod.CARD,
                    amount = 10000L,
                    currency = "USD"
                )

                // Then
                assertEquals(Provider.PROVIDER_A, selection.primary, "Failed for country: $country")
            }
        }
    }

    @Nested
    @DisplayName("Payment Method Routing Tests")
    inner class PaymentMethodRoutingTests {

        @Test
        @DisplayName("Should route UPI in India to Provider B")
        fun testUPIIndia() {
            // Given
            val country = "IN"
            val paymentMethod = PaymentMethod.UPI

            // When
            val selection = routingEngine.selectProvider(country, paymentMethod, 10000L, "INR")

            // Then
            assertEquals(Provider.PROVIDER_B, selection.primary)
            assertEquals("upi_india_routing", selection.reason)
        }

        @Test
        @DisplayName("Should not route UPI outside India")
        fun testUPIOutsideIndia() {
            // Given
            val country = "US"
            val paymentMethod = PaymentMethod.UPI

            // When
            val selection = routingEngine.selectProvider(country, paymentMethod, 10000L, "USD")

            // Then
            // Should fall back to country/region routing
            assertNotEquals("upi_india_routing", selection.reason)
        }

        @Test
        @DisplayName("Should route Net Banking in India to Provider B")
        fun testNetBankingIndia() {
            // Given
            val country = "IN"
            val paymentMethod = PaymentMethod.NET_BANKING

            // When
            val selection = routingEngine.selectProvider(country, paymentMethod, 10000L, "INR")

            // Then
            assertEquals(Provider.PROVIDER_B, selection.primary)
            assertEquals("netbanking_india_routing", selection.reason)
        }

        @Test
        @DisplayName("Should route Wallet in India to Provider B")
        fun testWalletIndia() {
            // Given
            val country = "IN"
            val paymentMethod = PaymentMethod.WALLET

            // When
            val selection = routingEngine.selectProvider(country, paymentMethod, 10000L, "INR")

            // Then
            assertEquals(Provider.PROVIDER_B, selection.primary)
            assertEquals("wallet_india_routing", selection.reason)
        }

        @Test
        @DisplayName("Should route EMI in India to Provider B")
        fun testEMIIndia() {
            // Given
            val country = "IN"
            val paymentMethod = PaymentMethod.EMI

            // When
            val selection = routingEngine.selectProvider(country, paymentMethod, 10000L, "INR")

            // Then
            assertEquals(Provider.PROVIDER_B, selection.primary)
            assertEquals("emi_india_routing", selection.reason)
        }

        @Test
        @DisplayName("Should use geographic routing for cards")
        fun testCardGeographicRouting() {
            // Cards should use geographic routing, not payment method routing
            
            // India
            val indiaSelection = routingEngine.selectProvider("IN", PaymentMethod.CARD, 10000L, "INR")
            assertEquals(Provider.PROVIDER_B, indiaSelection.primary)
            
            // US
            val usSelection = routingEngine.selectProvider("US", PaymentMethod.CARD, 10000L, "USD")
            assertEquals(Provider.PROVIDER_A, usSelection.primary)
        }
    }

    @Nested
    @DisplayName("Default Routing Tests")
    inner class DefaultRoutingTests {

        @Test
        @DisplayName("Should use default provider for unknown country")
        fun testUnknownCountry() {
            // Given
            val country = "XX" // Unknown country code
            val paymentMethod = PaymentMethod.CARD

            // When
            val selection = routingEngine.selectProvider(country, paymentMethod, 10000L, "USD")

            // Then
            assertEquals(Provider.PROVIDER_A, selection.primary) // Default provider
            assertEquals("default_routing", selection.reason)
        }

        @Test
        @DisplayName("Should provide fallback providers for default routing")
        fun testDefaultRoutingFallbacks() {
            // Given
            val country = "XX"
            val paymentMethod = PaymentMethod.CARD

            // When
            val selection = routingEngine.selectProvider(country, paymentMethod, 10000L, "USD")

            // Then
            assertFalse(selection.fallbacks.isEmpty())
            assertFalse(selection.fallbacks.contains(selection.primary))
        }
    }

    @Nested
    @DisplayName("Custom Configuration Tests")
    inner class CustomConfigurationTests {

        @Test
        @DisplayName("Should use custom country rules")
        fun testCustomCountryRules() {
            // Given
            val customConfig = GeographicRoutingConfig.custom(
                countryRules = mapOf(
                    "BR" to RoutingRule(
                        provider = Provider.PROVIDER_B,
                        fallbacks = listOf(Provider.PROVIDER_A)
                    )
                ),
                defaultProvider = Provider.PROVIDER_A
            )
            val customEngine = GeographicRoutingEngine(customConfig)

            // When
            val selection = customEngine.selectProvider("BR", PaymentMethod.CARD, 10000L, "BRL")

            // Then
            assertEquals(Provider.PROVIDER_B, selection.primary)
        }

        @Test
        @DisplayName("Should use custom region rules")
        fun testCustomRegionRules() {
            // Given
            val customConfig = GeographicRoutingConfig.custom(
                regionRules = mapOf(
                    "SA" to RoutingRule(
                        provider = Provider.PROVIDER_B,
                        fallbacks = listOf(Provider.PROVIDER_A)
                    )
                ),
                defaultProvider = Provider.PROVIDER_A
            )
            val customEngine = GeographicRoutingEngine(customConfig)

            // When
            val selection = customEngine.selectProvider("AR", PaymentMethod.CARD, 10000L, "ARS")

            // Then
            assertEquals(Provider.PROVIDER_B, selection.primary)
        }

        @Test
        @DisplayName("Should use custom default provider")
        fun testCustomDefaultProvider() {
            // Given
            val customConfig = GeographicRoutingConfig.custom(
                defaultProvider = Provider.PROVIDER_B
            )
            val customEngine = GeographicRoutingEngine(customConfig)

            // When
            val selection = customEngine.selectProvider("XX", PaymentMethod.CARD, 10000L, "USD")

            // Then
            assertEquals(Provider.PROVIDER_B, selection.primary)
        }
    }

    @Nested
    @DisplayName("Routing Priority Tests")
    inner class RoutingPriorityTests {

        @Test
        @DisplayName("Payment method routing should override country routing")
        fun testPaymentMethodPriority() {
            // Given - India has country rule for Provider B
            // But UPI has specific routing to Provider B
            val country = "IN"
            val paymentMethod = PaymentMethod.UPI

            // When
            val selection = routingEngine.selectProvider(country, paymentMethod, 10000L, "INR")

            // Then
            assertEquals(Provider.PROVIDER_B, selection.primary)
            assertEquals("upi_india_routing", selection.reason) // Payment method reason, not country
        }

        @Test
        @DisplayName("Country routing should override region routing")
        fun testCountryPriority() {
            // Given - India is in Asia region (Provider B)
            // But India has specific country rule (Provider B)
            val country = "IN"
            val paymentMethod = PaymentMethod.CARD

            // When
            val selection = routingEngine.selectProvider(country, paymentMethod, 10000L, "INR")

            // Then
            assertEquals(Provider.PROVIDER_B, selection.primary)
            assertTrue(selection.reason.contains("country") || selection.reason.contains("india"))
        }
    }

    @Nested
    @DisplayName("Fallback Provider Tests")
    inner class FallbackProviderTests {

        @Test
        @DisplayName("Should provide fallback providers")
        fun testFallbackProviders() {
            // Given
            val country = "IN"
            val paymentMethod = PaymentMethod.CARD

            // When
            val selection = routingEngine.selectProvider(country, paymentMethod, 10000L, "INR")

            // Then
            assertFalse(selection.fallbacks.isEmpty())
            assertFalse(selection.fallbacks.contains(selection.primary))
        }

        @Test
        @DisplayName("Fallback providers should be different from primary")
        fun testFallbackDifferentFromPrimary() {
            // Test multiple countries
            val countries = listOf("IN", "US", "GB", "DE", "JP")

            countries.forEach { country ->
                // When
                val selection = routingEngine.selectProvider(
                    country = country,
                    paymentMethod = PaymentMethod.CARD,
                    amount = 10000L,
                    currency = "USD"
                )

                // Then
                selection.fallbacks.forEach { fallback ->
                    assertNotEquals(selection.primary, fallback, "Fallback should differ for $country")
                }
            }
        }
    }

    @Nested
    @DisplayName("Routing Statistics Tests")
    inner class RoutingStatisticsTests {

        @Test
        @DisplayName("Should return routing statistics")
        fun testRoutingStatistics() {
            // When
            val stats = routingEngine.getRoutingStatistics()

            // Then
            assertTrue(stats.totalCountryRules > 0)
            assertTrue(stats.totalRegionRules > 0)
            assertNotNull(stats.defaultProvider)
        }

        @Test
        @DisplayName("Should reflect custom configuration in statistics")
        fun testCustomConfigStatistics() {
            // Given
            val customConfig = GeographicRoutingConfig.custom(
                countryRules = mapOf(
                    "BR" to RoutingRule(Provider.PROVIDER_B),
                    "AR" to RoutingRule(Provider.PROVIDER_A)
                ),
                regionRules = mapOf(
                    "SA" to RoutingRule(Provider.PROVIDER_B)
                ),
                defaultProvider = Provider.PROVIDER_B
            )
            val customEngine = GeographicRoutingEngine(customConfig)

            // When
            val stats = customEngine.getRoutingStatistics()

            // Then
            assertEquals(2, stats.totalCountryRules)
            assertEquals(1, stats.totalRegionRules)
            assertEquals(Provider.PROVIDER_B, stats.defaultProvider)
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    inner class EdgeCaseTests {

        @Test
        @DisplayName("Should handle null or empty country gracefully")
        fun testEmptyCountry() {
            // Given
            val country = ""
            val paymentMethod = PaymentMethod.CARD

            // When
            val selection = routingEngine.selectProvider(country, paymentMethod, 10000L, "USD")

            // Then
            assertNotNull(selection)
            assertNotNull(selection.primary)
            assertEquals("default_routing", selection.reason)
        }

        @Test
        @DisplayName("Should handle lowercase country codes")
        fun testLowercaseCountryCode() {
            // Given
            val country = "in" // lowercase
            val paymentMethod = PaymentMethod.CARD

            // When
            val selection = routingEngine.selectProvider(country, paymentMethod, 10000L, "INR")

            // Then
            // Should use default routing since country codes are case-sensitive
            assertNotNull(selection)
        }

        @Test
        @DisplayName("Should handle all payment methods")
        fun testAllPaymentMethods() {
            // Test all payment methods don't throw exceptions
            PaymentMethod.values().forEach { method ->
                // When
                val selection = routingEngine.selectProvider("US", method, 10000L, "USD")

                // Then
                assertNotNull(selection)
                assertNotNull(selection.primary)
            }
        }

        @Test
        @DisplayName("Should handle zero amount")
        fun testZeroAmount() {
            // Given
            val amount = 0L

            // When
            val selection = routingEngine.selectProvider("US", PaymentMethod.CARD, amount, "USD")

            // Then
            assertNotNull(selection)
            assertEquals(Provider.PROVIDER_A, selection.primary)
        }

        @Test
        @DisplayName("Should handle large amount")
        fun testLargeAmount() {
            // Given
            val amount = Long.MAX_VALUE

            // When
            val selection = routingEngine.selectProvider("US", PaymentMethod.CARD, amount, "USD")

            // Then
            assertNotNull(selection)
            assertEquals(Provider.PROVIDER_A, selection.primary)
        }
    }

    @Nested
    @DisplayName("Region Classification Tests")
    inner class RegionClassificationTests {

        @Test
        @DisplayName("Should classify all European countries correctly")
        fun testEuropeanCountries() {
            val europeanCountries = GeographicRoutingEngine.EUROPE_COUNTRIES
            
            assertTrue(europeanCountries.contains("DE")) // Germany
            assertTrue(europeanCountries.contains("FR")) // France
            assertTrue(europeanCountries.contains("GB")) // United Kingdom
            assertTrue(europeanCountries.contains("IT")) // Italy
            assertTrue(europeanCountries.contains("ES")) // Spain
        }

        @Test
        @DisplayName("Should classify all Asian countries correctly")
        fun testAsianCountries() {
            val asianCountries = GeographicRoutingEngine.ASIA_COUNTRIES
            
            assertTrue(asianCountries.contains("IN")) // India
            assertTrue(asianCountries.contains("CN")) // China
            assertTrue(asianCountries.contains("JP")) // Japan
            assertTrue(asianCountries.contains("KR")) // South Korea
            assertTrue(asianCountries.contains("SG")) // Singapore
        }

        @Test
        @DisplayName("Should classify all North American countries correctly")
        fun testNorthAmericanCountries() {
            val naCountries = GeographicRoutingEngine.NORTH_AMERICA_COUNTRIES
            
            assertTrue(naCountries.contains("US")) // United States
            assertTrue(naCountries.contains("CA")) // Canada
            assertTrue(naCountries.contains("MX")) // Mexico
        }
    }
}


