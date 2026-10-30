package com.example.helloandroid.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.helloandroid.recording.MotionSensor
import com.example.helloandroid.recording.RecorderUiState
import org.junit.Rule
import org.junit.Test

/** UI tests for [com.example.helloandroid.ui.main.MainScreen]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun startAndStopButtons_toggleWithRecordingState() {
    var state by mutableStateOf(RecorderUiState())
    composeTestRule.setContent {
      MainScreen(
        state = state,
        onStart = { state = state.copy(recording = true) },
        onStop = { state = state.copy(recording = false) },
        onSave = {},
        onRequestPermissions = {},
      )
    }
    composeTestRule.onNodeWithText("計測開始").performClick()
    composeTestRule.onNodeWithText("計測停止").assertExists()
    composeTestRule.onNodeWithText("計測停止").performClick()
    composeTestRule.onNodeWithText("計測開始").assertExists()
  }

  @Test
  fun missingSensor_isShownAsUnavailable() {
    composeTestRule.setContent {
      MainScreen(
        state =
          RecorderUiState(
            sensorAvailable = MotionSensor.entries.associateWith { it != MotionSensor.BAROMETER }
          ),
        onStart = {},
        onStop = {},
        onSave = {},
        onRequestPermissions = {},
      )
    }
    composeTestRule.onNodeWithText("利用不可（未搭載）").assertExists()
  }
}
