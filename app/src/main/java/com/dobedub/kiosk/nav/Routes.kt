package com.dobedub.kiosk.nav

object Routes {
    const val HOME = "home"
    const val ADMIN_PIN = "admin_pin"
    const val ADMIN_MENU = "admin_menu"
    const val ADMIN_KIOSK = "admin_kiosk"
    const val ADMIN_INFO = "admin_info"
    const val ADMIN_CONTENT = "admin_content"
    const val ADMIN_DEVICE = "admin_device"
    const val ADMIN_WIFI = "admin_wifi"
    const val ADMIN_UPDATE = "admin_update"
    const val ADMIN_ABOUT = "admin_about"
    const val VIDEO_LIST = "video_list"
    const val VIDEO_PLAYER = "video_player/{index}"
    const val WEB_VIEW = "web_view"

    fun videoPlayer(index: Int) = "video_player/$index"
}
