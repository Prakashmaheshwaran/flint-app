package com.flint.peakfocus.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flint.peakfocus.core.common.theme.FlintTheme

/**
 * PROMINENT DISCLOSURE + AFFIRMATIVE CONSENT (Play AccessibilityService policy, §4.3).
 *
 * This is a compliance gate, not a UX nicety. It must be shown in-app during normal use,
 * describe the data the AccessibilityService accesses and how it is used/shared, and require an
 * explicit accept BEFORE the user is sent to enable the service. Every word lives in
 * [ConsentCopy], where ConsentCopyTest pins the policy-required elements — do not weaken it.
 */
@Composable
fun AccessibilityConsentScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = ConsentCopy.HEADLINE,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = ConsentCopy.WHAT_IT_READS,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = ConsentCopy.WHERE_IT_GOES,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = ConsentCopy.NEXT_STEP,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
                Text(ConsentCopy.ACCEPT_LABEL)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) {
                Text(ConsentCopy.DECLINE_LABEL)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConsentPreview() {
    FlintTheme { AccessibilityConsentScreen(onAccept = {}, onDecline = {}) }
}
