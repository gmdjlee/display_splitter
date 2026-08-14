package com.displaysplitter.spacer

import org.junit.Assert.assertEquals
import org.junit.Test

class SpacerWidgetModeTest {

    @Test
    fun `stored names round-trip`() {
        for (mode in SpacerWidgetMode.entries) {
            assertEquals(mode, SpacerWidgetMode.fromStorage(mode.name))
        }
    }

    @Test
    fun `null, blank and unknown values fall back to black`() {
        assertEquals(SpacerWidgetMode.BLACK, SpacerWidgetMode.fromStorage(null))
        assertEquals(SpacerWidgetMode.BLACK, SpacerWidgetMode.fromStorage(""))
        assertEquals(SpacerWidgetMode.BLACK, SpacerWidgetMode.fromStorage("clock"))
        assertEquals(SpacerWidgetMode.BLACK, SpacerWidgetMode.fromStorage("ANYTHING"))
    }
}
