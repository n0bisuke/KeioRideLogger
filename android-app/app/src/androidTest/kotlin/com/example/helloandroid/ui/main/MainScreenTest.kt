package com.example.helloandroid.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import com.example.helloandroid.recording.CellReading
import com.example.helloandroid.recording.CellSnapshot
import com.example.helloandroid.recording.CellStatus
import com.example.helloandroid.recording.MotionSensor
import com.example.helloandroid.recording.RecorderUiState
import com.example.helloandroid.recording.SensorRate
import com.example.helloandroid.recording.SessionFileInfo
import com.example.helloandroid.recording.SpeedBand
import com.example.helloandroid.recording.SpeedDisplay
import com.example.helloandroid.recording.SpeedUnknownReason
import com.example.helloandroid.recording.SensorReading
import org.junit.Assert.assertEquals
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
        onProbeEnabledChange = {},
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
        onProbeEnabledChange = {},
      )
    }
    composeTestRule.onNodeWithText("利用不可（未搭載）").assertExists()
  }

  @Test
  fun afterStop_lastValuesAreLabelledAsStopTimeValues() {
    composeTestRule.setContent {
      MainScreen(
        state =
          RecorderUiState(
            recording = false,
            frozen = true,
            sensorAvailable = mapOf(MotionSensor.ACCELEROMETER to true),
            sensors =
              mapOf(
                MotionSensor.ACCELEROMETER to
                  SensorReading(listOf(0f, 0f, 9.8f), ageMs = 0, rate = SensorRate(25.0, 20.0))
              ),
          ),
        onStart = {},
        onStop = {},
        onSave = {},
        onRequestPermissions = {},
        onProbeEnabledChange = {},
      )
    }
    composeTestRule.onNodeWithText("計測停止時の最終値", substring = true).assertExists()
    composeTestRule.onNodeWithText("最終取得 0 秒前").assertDoesNotExist()
  }

  @Test
  fun switchSummary_distinguishesNoServingInfoFromNoSwitches() {
    var state by
      mutableStateOf(
        RecorderUiState(
          recording = true,
          cell = CellReading(CellSnapshot(CellStatus.NEIGHBORS_ONLY, observedCount = 7), null),
        )
      )
    composeTestRule.setContent {
      MainScreen(
        state = state,
        onStart = {},
        onStop = {},
        onSave = {},
        onRequestPermissions = {},
        onProbeEnabledChange = {},
      )
    }
    composeTestRule.onNodeWithText("在圏セル情報を取得できず", substring = true).assertExists()

    state =
      state.copy(cell = CellReading(CellSnapshot(CellStatus.SERVING, servingEverSeen = true), null))
    composeTestRule.onNodeWithText("確認できた切り替わりなし", substring = true).assertExists()
  }

  @Test
  fun speedCharacter_showsBandWithSpeed_andUnknownSeparately() {
    var state by
      mutableStateOf(
        RecorderUiState(recording = true, speed = SpeedDisplay(SpeedBand.WALK, 4.2, null))
      )
    composeTestRule.setContent {
      MainScreen(
        state = state,
        onStart = {},
        onStop = {},
        onSave = {},
        onRequestPermissions = {},
        onProbeEnabledChange = {},
      )
    }
    composeTestRule.onNodeWithText("普通歩き").assertExists()
    composeTestRule.onNodeWithText("GPS速度 4.2 km/h", substring = true).assertExists()

    state = state.copy(speed = SpeedDisplay(null, null, SpeedUnknownReason.STALE))
    composeTestRule.onNodeWithText("GPS更新なし").assertExists()
    composeTestRule.onNodeWithText("0 km/h ではありません", substring = true).assertExists()
  }

  @Test
  fun liveSection_isCollapsedByDefault_andShowsGpsWaiting() {
    composeTestRule.setContent {
      MainScreen(
        state = RecorderUiState(recording = true, locationGranted = true),
        onStart = {},
        onStop = {},
        onSave = {},
        onRequestPermissions = {},
        onProbeEnabledChange = {},
      )
    }
    composeTestRule.onNodeWithText("GPS測位軌跡（概略）").assertDoesNotExist()
    composeTestRule.onNodeWithText("▼ 開く").performClick()
    composeTestRule.onNodeWithText("GPS測位軌跡（概略）").assertExists()
    composeTestRule.onNodeWithText("GPS受信待ち（測位なし）").assertExists()
  }

  @Test
  fun csvOff_warnsAndHidesSaveButton() {
    composeTestRule.setContent {
      MainScreen(
        state = RecorderUiState(recording = true, csvEnabled = false, sessionHasCsv = false),
        onStart = {},
        onStop = {},
        onSave = {},
        onRequestPermissions = {},
        onProbeEnabledChange = {},
      )
    }
    composeTestRule.onNodeWithText("CSV 記録オフ・データは保存されません").assertExists()
    composeTestRule.onNodeWithText("CSV を保存").assertDoesNotExist()
  }

  @Test
  fun deleteAll_asksForConfirmationWithCountAndSize_beforeDeleting() {
    var deleted = 0
    composeTestRule.setContent {
      MainScreen(
        state =
          RecorderUiState(
            sessionFiles =
              listOf(
                SessionFileInfo("keio-ride-20261030-100000-000.csv", 2048, 0),
                SessionFileInfo("keio-ride-20261030-090000-000.csv", 1024, 0),
              )
          ),
        onStart = {},
        onStop = {},
        onSave = {},
        onRequestPermissions = {},
        onProbeEnabledChange = {},
        onDeleteAllSessionFiles = { deleted++ },
      )
    }
    composeTestRule.onNodeWithText("アプリ内の計測 CSV をすべて削除").performClick()
    composeTestRule.onNodeWithText("2 件（合計 3.0 KB）", substring = true).assertExists()
    composeTestRule.onNodeWithText("取り消せません", substring = true).assertExists()
    assertEquals(0, deleted)
    composeTestRule.onNodeWithText("削除する").performClick()
    assertEquals(1, deleted)
  }

  @Test
  fun deleteAll_isDisabledWhileRecording() {
    composeTestRule.setContent {
      MainScreen(
        state =
          RecorderUiState(
            recording = true,
            sessionFiles = listOf(SessionFileInfo("keio-ride-20261030-100000-000.csv", 10, 0)),
          ),
        onStart = {},
        onStop = {},
        onSave = {},
        onRequestPermissions = {},
        onProbeEnabledChange = {},
      )
    }
    composeTestRule.onNodeWithText("アプリ内の計測 CSV をすべて削除").assertIsNotEnabled()
  }
}
