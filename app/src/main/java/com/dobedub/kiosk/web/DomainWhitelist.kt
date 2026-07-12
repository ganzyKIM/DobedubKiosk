package com.dobedub.kiosk.web

import java.net.URI

/**
 * 제한 웹뷰의 내비게이션 허용 여부를 판정한다.
 * http/https만 허용하고, 허용 도메인 또는 그 서브도메인만 통과시킨다.
 */
object DomainWhitelist {

    fun isAllowed(url: String, allowedDomains: List<String>): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false

        val host = uri.host?.lowercase() ?: return false
        return allowedDomains.any { domain ->
            val normalized = domain.trim().lowercase().removePrefix("www.")
            host == normalized || host.endsWith(".$normalized")
        }
    }
}
