package com.budjetame.android.ui.login

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.data.api.AccountDto
import com.budjetame.android.data.api.AuthConfigDto
import com.budjetame.android.data.auth.AuthGateway
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The auth screen's ModeLinkRow alignment (ticket #38): in every row —
 * both sign-in-mode rows ("Forgot your password? Reset it" and "Don't
 * have an Account? Sign up") and the sign-up-mode row ("Already have an
 * Account? Sign in") — the action's text sits on the same visual line as
 * the label. A Row aligns its children Top by default, and the action is
 * a TextButton, whose M3 minimum height (40dp, the touch target) exceeds
 * the ~16dp bodySmall label, so the button's centered text hangs roughly
 * (40 − 16·scale)/2 dp below the label line; the fix centers both on the
 * Row's cross axis (Alignment.CenterVertically), keeping the button and
 * the row's horizontal centering as they were. The screen is driven
 * directly at a forced font scale (the same harness the Transactions
 * layout tests use), asserting at the default and a 1.3x scale per the
 * ticket's acceptance criteria; sign-up mode is reached by clicking the
 * "Sign up" action, which also pins that the click behavior is unchanged.
 * Since the label and the action share one style (bodySmall), the two
 * texts have equal line heights, so "same visual line" is exactly their
 * vertical centers coinciding — measuring centers rather than comparing
 * tops needs no glyph-metric or rounding assumptions. A tolerance of
 * 1dp cleanly separates the fixed layout (subpixel rounding only) from
 * the pre-fix drift (~12dp at 1x, ~9.6dp at 1.3x).
 */
@RunWith(AndroidJUnit4::class)
class LoginScreenLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun launchScreen(fontScale: Float) {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale = fontScale),
            ) {
                LoginScreen(auth = SilentAuthGateway(), onSignedIn = {})
            }
        }
    }

    /** The label and the action text sit on one visual line when their
     *  vertical centers coincide. */
    private fun assertActionOnLabelLine(label: String, action: String) {
        val labelCenter = composeRule.onNodeWithText(label).getUnclippedBoundsInRoot().centerY()
        val actionCenter = composeRule.onNodeWithText(action).getUnclippedBoundsInRoot().centerY()
        val drift = (labelCenter - actionCenter).value
        assertTrue(
            "'$action' sits ${abs(drift)}dp off the '$label' line",
            abs(drift) <= 1f,
        )
    }

    @Test
    fun `action texts share the label line in both sign-in rows and the sign-up row at the default font scale`() {
        launchScreen(fontScale = 1f)

        assertActionOnLabelLine("Forgot your password?", "Reset it")
        assertActionOnLabelLine("Don't have an Account?", "Sign up")

        composeRule.onNodeWithText("Sign up").performClick()
        assertActionOnLabelLine("Already have an Account?", "Sign in")
    }

    @Test
    fun `action texts share the label line in both sign-in rows and the sign-up row at a 1_3 font scale`() {
        launchScreen(fontScale = 1.3f)

        assertActionOnLabelLine("Forgot your password?", "Reset it")
        assertActionOnLabelLine("Don't have an Account?", "Sign up")

        composeRule.onNodeWithText("Sign up").performClick()
        assertActionOnLabelLine("Already have an Account?", "Sign in")
    }
}

/** The center line of a node's unclipped bounds, in the root's Dp space. */
private fun DpRect.centerY(): Dp = top + height / 2

/**
 * A sign-in-only AuthGateway for the layout test: the Google client id is
 * blank, so no Google button and no Credential Manager involvement; the
 * other operations are never reached by these tests.
 */
private class SilentAuthGateway : AuthGateway {
    override suspend fun signIn(email: String, password: String): AccountDto =
        error("not used by the layout test")

    override suspend fun signUp(email: String, password: String): AccountDto =
        error("not used by the layout test")

    override suspend fun signInWithGoogle(idToken: String): AccountDto =
        error("not used by the layout test")

    override suspend fun authConfig(): AuthConfigDto = AuthConfigDto(google_client_id = "")

    override suspend fun requestPasswordReset(email: String) =
        error("not used by the layout test")
}
