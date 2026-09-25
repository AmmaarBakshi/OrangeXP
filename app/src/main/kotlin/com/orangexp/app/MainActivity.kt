package com.orangexp.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.orangexp.app.navigation.OrangeXpApp
import com.orangexp.app.navigation.TopLevelDestination
import com.orangexp.core.common.holstrom.HolstromIntents
import com.orangexp.core.designsystem.theme.OrangeXpTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var requestedTab by mutableStateOf<TopLevelDestination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) requestedTab = tabFor(intent)
        setContent {
            OrangeXpTheme {
                OrangeXpApp(requestedTab = requestedTab, onTabShown = { requestedTab = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tabFor(intent)?.let { requestedTab = it }
    }

    /** Holstrom's notifications and widget open the app on its tab. */
    private fun tabFor(intent: Intent?): TopLevelDestination? =
        when (intent?.getStringExtra(HolstromIntents.EXTRA_DESTINATION)) {
            HolstromIntents.DESTINATION_HOLSTROM -> TopLevelDestination.Holstrom
            else -> null
        }
}
