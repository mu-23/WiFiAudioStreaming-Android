package com.cuscus.wifiaudiostreaming

import com.cuscus.wifiaudiostreaming.scripting.AutomationGate
import com.cuscus.wifiaudiostreaming.scripting.AutomationGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AutomationGateTest {

    private val token = "Xk3p9QvR2sT7uW1yA4bC6dE8fG0hJ2kL4mN6pQ8rS0t"

    @Before
    fun reset() = AutomationGate.resetThrottle()

    @Test
    fun matchingTokenIsAllowed() {
        assertEquals(Verdict.ALLOWED, AutomationGate.verifyAt(1_000, true, token, token))
    }

    @Test
    fun wrongTokenIsRefused() {
        assertEquals(Verdict.BAD_TOKEN, AutomationGate.verifyAt(1_000, true, token, "${token}x"))
    }

    @Test
    fun missingTokenIsRefused() {
        assertEquals(Verdict.BAD_TOKEN, AutomationGate.verifyAt(1_000, true, token, null))
        assertEquals(Verdict.BAD_TOKEN, AutomationGate.verifyAt(1_000, true, token, ""))
    }

    /** Prima che il token esista nessun comando esterno deve passare. */
    @Test
    fun blankExpectedTokenNeverAuthorizes() {
        assertEquals(Verdict.BAD_TOKEN, AutomationGate.verifyAt(1_000, true, "", null))
        assertEquals(Verdict.BAD_TOKEN, AutomationGate.verifyAt(1_000, true, "", ""))
    }

    /** L'interruttore spento vince anche su un token corretto. */
    @Test
    fun disabledRefusesEvenTheRightToken() {
        assertEquals(Verdict.DISABLED, AutomationGate.verifyAt(1_000, false, token, token))
    }

    @Test
    fun repeatedFailuresStopBeingAnswered() {
        repeat(AutomationGate.MAX_FAILURES_PER_WINDOW) {
            assertEquals(Verdict.BAD_TOKEN, AutomationGate.verifyAt(1_000, true, token, "nope"))
        }
        // Il token giusto resta bloccato finche' la finestra non scade...
        assertEquals(Verdict.THROTTLED, AutomationGate.verifyAt(1_000, true, token, token))
        // ...e riparte subito dopo.
        assertEquals(
            Verdict.ALLOWED,
            AutomationGate.verifyAt(1_000 + AutomationGate.FAILURE_WINDOW_MS + 1, true, token, token)
        )
    }

    @Test
    fun successClearsTheFailureCount() {
        repeat(AutomationGate.MAX_FAILURES_PER_WINDOW - 1) {
            AutomationGate.verifyAt(1_000, true, token, "nope")
        }
        assertEquals(Verdict.ALLOWED, AutomationGate.verifyAt(1_000, true, token, token))
        repeat(AutomationGate.MAX_FAILURES_PER_WINDOW - 1) {
            assertEquals(Verdict.BAD_TOKEN, AutomationGate.verifyAt(1_000, true, token, "nope"))
        }
        assertEquals(Verdict.ALLOWED, AutomationGate.verifyAt(1_000, true, token, token))
    }
}
