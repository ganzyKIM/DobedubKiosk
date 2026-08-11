package com.dobedub.kiosk.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.dobedub.kiosk.admin.AdminViewModel
import com.dobedub.kiosk.admin.ui.AdminAboutScreen
import com.dobedub.kiosk.admin.ui.AdminContentScreen
import com.dobedub.kiosk.admin.ui.AdminDeviceScreen
import com.dobedub.kiosk.admin.ui.AdminInfoScreen
import com.dobedub.kiosk.admin.ui.AdminMenuScreen
import com.dobedub.kiosk.admin.ui.AdminPinScreen
import com.dobedub.kiosk.admin.ui.AdminUpdateScreen
import com.dobedub.kiosk.admin.ui.AdminWifiScreen
import com.dobedub.kiosk.data.KioskSettingsRepository
import com.dobedub.kiosk.ui.home.HomeScreen
import com.dobedub.kiosk.video.VideoItem
import com.dobedub.kiosk.video.VideoRepository
import com.dobedub.kiosk.video.ui.VideoListScreen
import com.dobedub.kiosk.video.ui.VideoPlayerScreen
import com.dobedub.kiosk.web.RestrictedWebViewScreen

@Composable
fun KioskNavHost(
    navController: NavHostController,
    settingsRepository: KioskSettingsRepository,
    onUserInteraction: () -> Unit,
    onExitKiosk: () -> Unit,
    onReenterKiosk: () -> Unit,
    onReboot: () -> Unit,
    onReleaseDeviceOwner: () -> Unit
) {
    val context = LocalContext.current
    val settings by settingsRepository.settingsFlow.collectAsState(initial = com.dobedub.kiosk.data.KioskSettings())
    val adminViewModel: AdminViewModel = viewModel(factory = AdminViewModel.Factory(settingsRepository))

    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }

    fun goHome() {
        navController.navigate(Routes.HOME) {
            popUpTo(Routes.HOME) { inclusive = true }
        }
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenVideos = { navController.navigate(Routes.VIDEO_LIST) },
                onOpenWebsite = { navController.navigate(Routes.WEB_VIEW) },
                onOpenMyVoice = { navController.navigate(Routes.WEB_MY_VOICE) },
                onAdminUnlockRequested = { navController.navigate(Routes.ADMIN_PIN) },
                institutionLabel = settings.institutionLabel
            )
        }

        composable(Routes.ADMIN_PIN) {
            AdminPinScreen(
                viewModel = adminViewModel,
                onPinVerified = {
                    navController.navigate(Routes.ADMIN_MENU) {
                        popUpTo(Routes.HOME)
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }

        composable(Routes.ADMIN_MENU) {
            AdminMenuScreen(
                onOpenInfo = { navController.navigate(Routes.ADMIN_INFO) },
                onOpenContent = { navController.navigate(Routes.ADMIN_CONTENT) },
                onOpenDevice = { navController.navigate(Routes.ADMIN_DEVICE) },
                onOpenWifi = { navController.navigate(Routes.ADMIN_WIFI) },
                onOpenUpdate = { navController.navigate(Routes.ADMIN_UPDATE) },
                onOpenAbout = { navController.navigate(Routes.ADMIN_ABOUT) },
                onExitKiosk = { onExitKiosk(); goHome() },
                onReenterKiosk = onReenterKiosk,
                onReboot = onReboot,
                onReleaseDeviceOwner = { onReleaseDeviceOwner(); goHome() },
                onExit = { goHome() }
            )
        }

        composable(Routes.ADMIN_INFO) {
            AdminInfoScreen(viewModel = adminViewModel, onBack = { navController.popBackStack() })
        }

        composable(Routes.ADMIN_CONTENT) {
            AdminContentScreen(viewModel = adminViewModel, onBack = { navController.popBackStack() })
        }

        composable(Routes.ADMIN_DEVICE) {
            AdminDeviceScreen(
                settings = settings,
                onIdleTimeoutChange = adminViewModel::updateIdleTimeoutMinutes,
                onAutoPlayChange = adminViewModel::updateAutoPlayNext,
                onVolumeChange = adminViewModel::updateVolumeMax,
                onBrightnessChange = adminViewModel::updateBrightness,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.ADMIN_WIFI) {
            AdminWifiScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ADMIN_UPDATE) {
            AdminUpdateScreen(viewModel = adminViewModel, onBack = { navController.popBackStack() })
        }

        composable(Routes.ADMIN_ABOUT) {
            AdminAboutScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.VIDEO_LIST) {
            LaunchedEffect(Unit) {
                videos = VideoRepository(context).listVideos()
            }
            VideoListScreen(
                videos = videos,
                onOpenVideo = { video ->
                    val index = videos.indexOf(video)
                    navController.navigate(Routes.videoPlayer(index))
                },
                onBackToHome = { goHome() }
            )
        }

        composable(
            route = Routes.VIDEO_PLAYER,
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: 0
            val video = videos.getOrNull(index)
            if (video != null) {
                VideoPlayerScreen(
                    video = video,
                    onPlaybackEnded = {
                        val nextIndex = index + 1
                        if (settings.autoPlayNext && nextIndex < videos.size) {
                            navController.navigate(Routes.videoPlayer(nextIndex)) {
                                popUpTo(Routes.VIDEO_LIST)
                            }
                        } else {
                            navController.popBackStack(Routes.VIDEO_LIST, inclusive = false)
                        }
                    },
                    onBackToList = { navController.popBackStack(Routes.VIDEO_LIST, inclusive = false) },
                    onUserInteraction = onUserInteraction
                )
            }
        }

        composable(Routes.WEB_VIEW) {
            RestrictedWebViewScreen(
                startUrl = settings.startUrl,
                allowedDomains = settings.allowedDomains,
                onExitToKioskHome = { goHome() },
                onUserInteraction = onUserInteraction
            )
        }

        composable(Routes.WEB_MY_VOICE) {
            // '마이보이스'는 도서관 웹사이트와 같은 출처(서브도메인)의 /my-voice 경로를 연다.
            RestrictedWebViewScreen(
                startUrl = myVoiceUrlFrom(settings.startUrl),
                allowedDomains = settings.allowedDomains,
                onExitToKioskHome = { goHome() },
                onUserInteraction = onUserInteraction
            )
        }
    }
}

/** 시작 URL(예: https://splib.dobedub.com/home)과 같은 scheme+host 의 /my-voice 주소를 만든다. */
private fun myVoiceUrlFrom(startUrl: String): String = try {
    val uri = android.net.Uri.parse(startUrl)
    val scheme = uri.scheme ?: "https"
    val host = uri.authority
    if (host.isNullOrBlank()) "https://splib.dobedub.com/my-voice" else "$scheme://$host/my-voice"
} catch (e: Exception) {
    "https://splib.dobedub.com/my-voice"
}
