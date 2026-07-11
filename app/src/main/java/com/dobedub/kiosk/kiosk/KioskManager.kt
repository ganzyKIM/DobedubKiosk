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
    }
}
