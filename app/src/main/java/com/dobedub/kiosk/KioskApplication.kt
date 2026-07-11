package com.dobedub.kiosk

import android.app.Application
import android.webkit.WebView
import com.dobedub.kiosk.data.KioskSettingsRepository
import com.dobedub.kiosk.kiosk.KioskManager

/**
 * 앱 전역에서 공유하는 리포지토리/매니저를 보관한다.
 * 화면 수가 적은 단일 액티비티 앱이라 별도 DI 프레임워크 없이 간단한 서비스 로케이터로 충분하다.
 */
class KioskApplication : Application() {

    lateinit var settingsRepository: KioskSettingsRepository
        private set

    lateinit var kioskManager: KioskManager
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = KioskSettingsRepository(this)
        kioskManager = KioskManager(this)

        if (BuildConfig.DEBUG) {
            // chrome://inspect 로 웹뷰 네트워크/콘솔 오류를 원격 디버깅할 수 있게 한다.
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }
}
