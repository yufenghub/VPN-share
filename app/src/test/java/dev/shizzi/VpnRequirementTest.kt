package dev.shizzi

import org.junit.Test

class VpnRequirementTest {
    @Test(expected = IllegalStateException::class)
    fun `a vpn disappearing before adoption cannot use the physical default route`() {
        requireVpnHandle(0L)
    }

    @Test
    fun `an adopted vpn handle is accepted`() {
        requireVpnHandle(4294967296L)
    }
}
