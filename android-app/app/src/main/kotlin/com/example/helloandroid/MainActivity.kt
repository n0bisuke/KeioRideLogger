package com.example.helloandroid

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import com.example.helloandroid.recording.RecorderViewModel
import com.example.helloandroid.ui.main.MainScreen
import com.example.helloandroid.ui.theme.HelloAndroidTheme

class MainActivity : ComponentActivity() {
  private val recorder: RecorderViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    setContent {
      val state by recorder.state.collectAsState()

      val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
          recorder.refreshPermissions()
        }
      val saveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
          if (uri != null) recorder.saveTo(uri)
        }

      // Keep the screen on while recording: the MVP only records while the screen is shown.
      val view = LocalView.current
      DisposableEffect(state.recording) {
        view.keepScreenOn = state.recording
        onDispose { view.keepScreenOn = false }
      }

      HelloAndroidTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          MainScreen(
            state = state,
            onStart = recorder::start,
            onStop = { recorder.stop() },
            onSave = { state.savableFileName?.let(saveLauncher::launch) },
            onRequestPermissions = {
              permissionLauncher.launch(
                arrayOf(
                  Manifest.permission.ACCESS_FINE_LOCATION,
                  Manifest.permission.ACCESS_COARSE_LOCATION,
                  Manifest.permission.READ_PHONE_STATE,
                )
              )
            },
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // Permissions may have been changed in system settings while away.
    recorder.refreshPermissions()
  }

  override fun onStop() {
    super.onStop()
    if (!isChangingConfigurations) recorder.stop("background")
  }
}
