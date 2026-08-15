package com.dobedub.kiosk.kiosk

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.dobedub.kiosk.MainActivity

/**
 * Device Owner 권한을 이용해 태블릿을 키오스크 상태로 잠그거나 해제한다.
 * 실제 잠금 강도는 §2 기획 문서(Device Owner + 커스텀 런처 + Lock Task) 참조.
 */
class KioskManager(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent: ComponentName = AdminReceiver.componentName(context)

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    /**
     * NetBird 가 설치돼 있으면 always-on VPN 으로 지정한다 — 재부팅 후 앱 UI 를 거치지 않고
     * 시스템이 VPN 을 자동으로 올린다. 원격 관리(체크인)가 넷버드 주소로 가므로 이게 없으면
     * **재부팅 한 번에 원격 관리가 끊긴다**(2026-08-15 핫스팟 QA-4 에서 실측 — NetBird 앱은
     * 스스로 부팅 후 재연결하지 않았다).
     *
     * lockdown=false 인 이유: true 면 VPN 이 죽는 순간 태블릿의 모든 통신이 끊겨 도서관
     * 웹뷰(공인 HTTPS 직결)까지 죽는다. false 면 넷버드가 죽어도 키오스크 서비스는 살고
     * 원격 관리만 잠시 끊긴다 — 실패 모드가 훨씬 온화하다.
     *
     * 멱등: 이미 지정돼 있으면 다시 지정해도 무해. NetBird 미설치 기기(구형 세팅)에서는
     * 아무것도 하지 않는다.
     */
    fun ensureAlwaysOnVpn() {
        if (!isDeviceOwner()) return
        try {
            context.packageManager.getPackageInfo(NETBIRD_PACKAGE, 0)
        } catch (e: Exception) {
            return   // NetBird 미설치 — 지정할 수 없다
        }
        try {
            if (dpm.getAlwaysOnVpnPackage(adminComponent) != NETBIRD_PACKAGE) {
                dpm.setAlwaysOnVpnPackage(adminComponent, NETBIRD_PACKAGE, /* lockdownEnabled= */ false)
                Log.i(TAG, "NetBird 를 always-on VPN 으로 지정")
            }
        } catch (e: Exception) {
            // 일부 기기/버전에서 UnsupportedOperationException 가능 — 원격 관리는 수동 연결로 폴백
            Log.w(TAG, "always-on VPN 지정 실패: ${e.message}")
        }
    }

    /** 홈 버튼/최근앱/전원 메뉴 등을 막고 이 앱만 실행되는 잠금 태스크 모드로 진입한다. */
    fun enterKioskMode(activity: Activity) {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not device owner — kiosk lock skipped")
            return
        }

        dpm.setLockTaskPackages(adminComponent, arrayOf(context.packageName))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(
                adminComponent,
                DevicePolicyManager.LOCK_TASK_FEATURE_NONE
            )
        }

        applyUserRestrictions(enable = true)
        // 이전 버전에서 이미 걸어뒀을 수 있는 제한을 확실히 풀어준다(개발/유지보수용 USB 디버깅 접근 유지).
        dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
        dpm.setStatusBarDisabled(adminComponent, true)
        dpm.setKeyguardDisabled(adminComponent, true)
        setPersistentHome(enable = true)
        grantSilentRuntimePermissions()

        if (!activity.isInLockTaskMode()) {
            activity.startLockTask()
        }
    }

    /** 관리자 메뉴에서 "키오스크 해제" 선택 시 태블릿을 일반 상태로 되돌린다. */
    fun exitKioskMode(activity: Activity) {
        if (activity.isInLockTaskMode()) {
            activity.stopLockTask()
        }
        if (!isDeviceOwner()) return

        applyUserRestrictions(enable = false)
        dpm.setStatusBarDisabled(adminComponent, false)
        dpm.setKeyguardDisabled(adminComponent, false)
        setPersistentHome(enable = false)
        dpm.setLockTaskPackages(adminComponent, emptyArray())
    }

    private fun Activity.isInLockTaskMode(): Boolean =
        (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE

    private fun applyUserRestrictions(enable: Boolean) {
        val restrictions = listOf(
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            // DISALLOW_DEBUGGING_FEATURES는 의도적으로 제외한다.
            // 개발/유지보수 중 USB 디버깅으로 계속 접근할 수 있어야 한다는 요구사항 때문(§9 리스크 참고).
            UserManager.DISALLOW_USB_FILE_TRANSFER,
            UserManager.DISALLOW_CONFIG_WIFI
        )
        restrictions.forEach { restriction ->
            if (enable) {
                dpm.addUserRestriction(adminComponent, restriction)
            } else {
                dpm.clearUserRestriction(adminComponent, restriction)
            }
        }
    }

    /** 마이크 등 런타임 권한을 시스템 다이얼로그 없이 조용히 부여한다(§4.3 웹뷰 마이크 접근). */
    private fun grantSilentRuntimePermissions() {
        dpm.setPermissionGrantState(
            adminComponent,
            context.packageName,
            Manifest.permission.RECORD_AUDIO,
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
        )
    }

    /** 재부팅/크래시 후에도 항상 이 앱이 HOME으로 뜨도록 지정한다. */
    private fun setPersistentHome(enable: Boolean) {
        val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val mainActivityComponent = ComponentName(context, MainActivity::class.java)
        if (enable) {
            dpm.addPersistentPreferredActivity(adminComponent, homeFilter, mainActivityComponent)
        } else {
            dpm.clearPackagePersistentPreferredActivities(adminComponent, context.packageName)
        }
    }

    /** 기기 재부팅 (Device Owner 권한 필요, API 24+). */
    fun rebootDevice() {
        if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dpm.reboot(adminComponent)
        }
    }

    /** 반납 등 완전 해제 시 Device Owner 지위 자체를 반환한다. 되돌릴 수 없으니 2단계 확인 후 호출할 것. */
    fun clearDeviceOwner(activity: Activity) {
        exitKioskMode(activity)
        if (isDeviceOwner()) {
            dpm.clearDeviceOwnerApp(context.packageName)
        }
    }

    companion object {
        private const val TAG = "KioskManager"
        private const val NETBIRD_PACKAGE = "io.netbird.client"
    }
}
