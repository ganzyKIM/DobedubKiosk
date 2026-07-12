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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dobedub.kiosk.admin.AdminViewModel
import com.dobedub.kiosk.ui.components.BackTopBar

/** 관리자 정보: PIN 변경, 문의 연락처 편집. */
@Composable
fun AdminInfoScreen(viewModel: AdminViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    var newPin by remember { mutableStateOf("") }
    var contact by remember(settings.contactInfo) { mutableStateOf(settings.contactInfo) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BackTopBar(onBack = onBack)

        Text("관리자 정보", style = MaterialTheme.typography.headlineMedium)

        Text("PIN 변경 (4~6자리 숫자)")
        OutlinedTextField(
            value = newPin,
            onValueChange = { if (it.length <= 6) newPin = it.filter(Char::isDigit) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                if (newPin.length in 4..6) {
                    viewModel.changePin(newPin)
                    newPin = ""
                    savedMessage = "PIN이 변경되었습니다."
                } else {
                    savedMessage = "PIN은 4~6자리 숫자여야 합니다."
                }
            }
        ) { Text("PIN 저장") }

        Text("문의 연락처 (문제 발생 시 화면에 표시)")
        OutlinedTextField(
            value = contact,
            onValueChange = { contact = it },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            viewModel.updateContactInfo(contact)
            savedMessage = "연락처가 저장되었습니다."
        }) { Text("연락처 저장") }

        savedMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}
