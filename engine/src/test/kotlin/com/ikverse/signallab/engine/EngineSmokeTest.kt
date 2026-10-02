package com.ikverse.signallab.engine

import org.junit.Test

class EngineSmokeTest {
    @Test
    fun theEngineModuleBuildsAndRuns() {
        check(Engine.NAME.isNotEmpty())
    }
}
