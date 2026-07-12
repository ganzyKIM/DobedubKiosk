package com.dobedub.kiosk.admin.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dobedub.kiosk.BuildConfig
import com.dobedub.kiosk.ui.components.BackTopBar

/** 정보 화면: 앱 버전, 기기 모델/OS 버전 표시. */
@Composable
fun AdminAboutScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BackTopBar(onBack = onBack)

        Text("정보", style = MaterialTheme.typography.headlineMedium)
        Text("앱 버전: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text("기기 모델: ${Build.MANUFACTURER} ${Build.MODEL}")
        Text("Android 버전: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    }
}
