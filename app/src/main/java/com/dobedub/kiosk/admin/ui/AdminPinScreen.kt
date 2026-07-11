package com.dobedub.kiosk.admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dobedub.kiosk.admin.AdminViewModel
import com.dobedub.kiosk.ui.components.BackTopBar
import com.dobedub.kiosk.ui.theme.AccentRed

/**
 * 홈 화면 로고 5회 탭으로 진입하는 숨겨진 관리자 PIN 입력 화면.
 */
@Composable
fun AdminPinScreen(
    viewModel: AdminViewModel,
    onPinVerified: () -> Unit,
    onCancel: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    val error by viewModel.pinError.collectAsState()

    fun submit() {
        viewModel.submitPin(pin) {
            pin = ""
            onPinVerified()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp)
    ) {
        BackTopBar(label = "취소하고 돌아가기", onBack = onCancel)

        Column(
            modifier = Modifier.fillMaxSize().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("관리자 PIN 입력", style = MaterialTheme.typography.headlineMedium)

            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 6) pin = it.filter(Char::isDigit) },
                label = { Text("PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.width(240.dp)
            )

            if (error != null) {
                Text(text = error.orEmpty(), color = AccentRed)
            }

            Button(onClick = { submit() }) {
                Text("확인")
            }
        }
    }
}
