package ai.openclaw.android.skill

import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityReviewTest {

    @Test
    fun `idempotent tool returns AUTO_EXECUTE`() {
        val policy = SecurityReview.reviewTool("get_price", isIdempotent = true, preference = null)
        assertEquals(ToolSecurityPolicy.AUTO_EXECUTE, policy)
    }

    @Test
    fun `idempotent tool returns AUTO_EXECUTE even with DENY preference`() {
        val pref = UserApprovalPreference("bitcoin_price_get_price", ApprovalDecision.ALWAYS_DENY)
        val policy = SecurityReview.reviewTool("get_price", isIdempotent = true, preference = pref)
        assertEquals(ToolSecurityPolicy.AUTO_EXECUTE, policy)
    }

    @Test
    fun `non-idempotent without preference returns ASK_USER`() {
        val policy = SecurityReview.reviewTool("set_alert", isIdempotent = false, preference = null)
        assertEquals(ToolSecurityPolicy.ASK_USER, policy)
    }

    @Test
    fun `ALWAYS_APPROVE preference returns AUTO_EXECUTE`() {
        val pref = UserApprovalPreference("bitcoin_price_set_alert", ApprovalDecision.ALWAYS_APPROVE)
        val policy = SecurityReview.reviewTool("set_alert", isIdempotent = false, preference = pref)
        assertEquals(ToolSecurityPolicy.AUTO_EXECUTE, policy)
    }

    @Test
    fun `ALWAYS_DENY preference returns DENY`() {
        val pref = UserApprovalPreference("bitcoin_price_set_alert", ApprovalDecision.ALWAYS_DENY)
        val policy = SecurityReview.reviewTool("set_alert", isIdempotent = false, preference = pref)
        assertEquals(ToolSecurityPolicy.DENY, policy)
    }

    @Test
    fun `ASK_EVERY_TIME preference returns ASK_USER`() {
        val pref = UserApprovalPreference("bitcoin_price_set_alert", ApprovalDecision.ASK_EVERY_TIME)
        val policy = SecurityReview.reviewTool("set_alert", isIdempotent = false, preference = pref)
        assertEquals(ToolSecurityPolicy.ASK_USER, policy)
    }
}
