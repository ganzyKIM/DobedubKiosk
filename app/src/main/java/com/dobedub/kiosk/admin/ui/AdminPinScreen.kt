package com.dobedub.kiosk.admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

    // 이 화면만 상단바 뒤로가기를 쓰지 않는다. 좌측 상단은 다른 화면의 "뒤로"와 위치가
    // 겹쳐서, 로고 5회 탭으로 막 들어온 직후 그 자리를 눌러 튕겨 나가는 오조작이 잦았다.
    // 취소를 화면 중앙 확인 버튼 왼쪽으로 내려 의도적으로 눌러야만 닿게 한다.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            OutlinedButton(onClick = onCancel) {
                Text("취소")
            }
            Button(onClick = { submit() }) {
                Text("확인")
            }
        }
    }
}
