package com.dobedub.kiosk.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * PackageInstaller 세션 결과 수신. Device Owner 무인 설치라 대개 곧바로 성공하고 프로세스가 재시작되지만,
 * 상태를 로그로 남겨 원격 진단을 돕는다.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "업데이트 설치 성공")
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                // Device Owner 라면 원칙적으로 오지 않지만, 만약 오면 확인 인텐트를 띄운다.
                Log.w(TAG, "설치에 사용자 확인이 요구됨(Device Owner 아님?) — $msg")
            else ->
                Log.e(TAG, "업데이트 설치 실패: status=$status msg=$msg")
        }
    }

    companion object {
        private const val TAG = "UpdateInstall"
        const val ACTION_INSTALL_STATUS = "com.dobedub.kiosk.INSTALL_STATUS"
    }
}
