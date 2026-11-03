package io.github.edrock200.appdownloader.net

/** Picks the best installable asset of a release for this device. */
object AssetSelector {

    private val INSTALLABLE = listOf(".apk", ".apks", ".xapk")
    private val ARM32_BARE = Regex("(^|[^a-z0-9])(arm|arm32|armhf|armv7a?)([^a-z0-9]|$)")

    fun isInstallable(name: String) = INSTALLABLE.any { name.lowercase().endsWith(it) }

    /** Returns the Android ABI an asset was built for, or null if it looks universal. */
    fun detectAbi(name: String): String? {
        val n = name.lowercase()
        return when {
            "arm64" in n || "aarch64" in n || "armv8" in n -> "arm64-v8a"
            "armeabi-v7a" in n || "armeabi_v7a" in n || "armeabi" in n || ARM32_BARE.containsMatchIn(n) -> "armeabi-v7a"
            "x86_64" in n || "x86-64" in n || "amd64" in n || Regex("(^|[^a-z0-9])x64([^a-z0-9]|$)").containsMatchIn(n) -> "x86_64"
            "x86" in n || "i686" in n || "i386" in n -> "x86"
            else -> null
        }
    }

    fun select(assets: List<GhAsset>, filter: String?, supportedAbis: List<String>): GhAsset? {
        var candidates = assets.filter { isInstallable(it.name) }
        if (!filter.isNullOrBlank()) {
            val regex = runCatching { Regex(filter, RegexOption.IGNORE_CASE) }.getOrNull()
            candidates = candidates.filter { asset ->
                regex?.containsMatchIn(asset.name) ?: asset.name.contains(filter, ignoreCase = true)
            }
        }
        if (candidates.isEmpty()) return null

        val abis = supportedAbis.map { if (it == "armeabi") "armeabi-v7a" else it }.distinct()
        fun score(asset: GhAsset): Int {
            val abi = detectAbi(asset.name)
            var s = when {
                abi == null -> if ("universal" in asset.name.lowercase()) 60 else 50
                abi in abis -> 100 - abis.indexOf(abi) * 10
                else -> -1 // Built for an ABI this device can't run.
            }
            if (s >= 0 && asset.name.lowercase().endsWith(".apk")) s += 1 // Prefer plain APKs over bundles.
            return s
        }
        val best = candidates.maxByOrNull(::score) ?: return null
        return best.takeIf { score(it) >= 0 }
    }
}
