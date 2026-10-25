package com.karaoketv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karaoketv.ui.HomeScreen
import com.karaoketv.ui.KaraokeTheme
import com.karaoketv.ui.LocalAppearance
import com.karaoketv.ui.PlayerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KaraokeTheme {
                val vm: KaraokeViewModel = viewModel()
                // "Menu & list size" shrinks everything by lowering the density the app draws with.
                val density = LocalDensity.current
                val scale = vm.appearance.uiScale.scale
                CompositionLocalProvider(
                    LocalAppearance provides vm.appearance,
                    LocalDensity provides Density(density.density * scale, density.fontScale),
                ) {
                    if (vm.nowPlaying != null) {
                        BackHandler { vm.stop() }
                        PlayerScreen(vm)
                    } else {
                        HomeScreen(vm)
                    }
                }
            }
        }
    }
}
