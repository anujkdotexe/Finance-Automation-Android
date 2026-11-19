package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionConfigCodecTest {

    private fun json(amount: String) =
        """{"name":"Netflix","amount":"$amount","frequency":"MONTHLY","anchor":"2026-11-05","account":"HDFC"}"""

    @Test
    fun decodeRejectsNegativeAndNonFiniteAmount() {
        for (amount in listOf("-199", "Infinity", "NaN", "1e308", "0", "abc", "20000000000")) {
            assertNull("amount '$amount' must make the subscription unreadable", SubscriptionConfigCodec.decode("sub_1", json(amount)))
        }
    }

    @Test
    fun decodeKeepsValidAndVaryingAmounts() {
        assertEquals(199.0, SubscriptionConfigCodec.decode("sub_1", json("199"))!!.amount!!, 0.0)
        assertEquals(199.99, SubscriptionConfigCodec.decode("sub_1", json("199.99"))!!.amount!!, 0.0)
        val varying = SubscriptionConfigCodec.decode("sub_1", json(""))
        assertNotNull(varying)
        assertNull("an empty amount means the amount varies", varying!!.amount)
    }

    @Test
    fun encodedSubscriptionRoundTrips() {
        val sub = Subscription(
            id = "sub_1", name = "Netflix", amount = 649.0, frequency = SubscriptionFrequency.MONTHLY,
            anchorEpochDay = 20_000L, account = "HDFC"
        )
        assertEquals(649.0, SubscriptionConfigCodec.decode("sub_1", SubscriptionConfigCodec.encode(sub))!!.amount!!, 0.0)
    }
}
