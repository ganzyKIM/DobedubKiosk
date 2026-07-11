package com.dobedub.kiosk.admin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dobedub.kiosk.admin.AdminViewModel
import com.dobedub.kiosk.ui.components.BackTopBar

/** 콘텐츠 설정: 웹뷰 시작 URL과 허용 도메인 화이트리스트 편집. */
@Composable
fun AdminContentScreen(viewModel: AdminViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    var startUrl by remember(settings.startUrl) { mutableStateOf(settings.startUrl) }
    var domainsText by remember(settings.allowedDomains) {
        mutableStateOf(settings.allowedDomains.joinToString(", "))
    }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BackTopBar(onBack = onBack)

        Text("콘텐츠 설정", style = MaterialTheme.typography.headlineMedium)

        Text("웹사이트 시작 URL")
        OutlinedTextField(
            value = startUrl,
            onValueChange = { startUrl = it },
            modifier = Modifier.fillMaxWidth()
        )

        Text("허용 도메인 (쉼표로 구분, 서브도메인 자동 허용)")
        OutlinedTextField(
            value = domainsText,
            onValueChange = { domainsText = it },
            modifier = Modifier.fillMaxWidth()
        )

        Button(onClick = {
            viewModel.updateStartUrl(startUrl.trim())
            val domains = domainsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            viewModel.updateAllowedDomains(domains)
            savedMessage = "저장되었습니다."
        }) { Text("저장") }

        savedMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}
