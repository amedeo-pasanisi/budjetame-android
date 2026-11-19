package com.budjetame.android.ui.common

import androidx.test.platform.app.InstrumentationRegistry

/** This [UiText] as the test device's resources render it (the English
 * instrumented suites run under `en`): the text a node is matched by. */
fun UiText.deviceText(): String =
    resolve(InstrumentationRegistry.getInstrumentation().targetContext.resources)
