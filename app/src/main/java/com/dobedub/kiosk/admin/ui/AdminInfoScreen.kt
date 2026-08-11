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
import com.dobedub.kiosk.data.DEFAULT_CONTACT_INFO
import com.dobedub.kiosk.ui.components.BackTopBar
import com.dobedub.kiosk.ui.theme.LabelSecondary

/** 관리자 정보: PIN 변경, 문의 연락처 편집. */
@Composable
fun AdminInfoScreen(viewModel: AdminViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    var newPin by remember { mutableStateOf("") }
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

        savedMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        // 연락처는 기기에서 못 고친다. 태블릿과 백오피스 양쪽에서 바꿀 수 있게 두면 백오피스가
        // 지정한 값이 체크인마다 기기 값을 덮어써서, 현장에서 고쳐도 계속 되돌아간다.
        Text("문의 연락처 (문제 발생 시 화면에 표시)")
        Text(settings.contactInfo, style = MaterialTheme.typography.headlineSmall)
        Text(
            "기본값은 $DEFAULT_CONTACT_INFO 입니다. 도서관별로 다르게 쓰려면 PC 관리자 화면의 기기 목록에서 변경하세요.",
            style = MaterialTheme.typography.bodySmall,
            color = LabelSecondary
        )
    }
}
