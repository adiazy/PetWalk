package com.karaoketv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karaoketv.ui.HomeScreen
import com.karaoketv.ui.KaraokeTheme
import com.karaoketv.ui.PlayerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KaraokeTheme {
                val vm: KaraokeViewModel = viewModel()
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
