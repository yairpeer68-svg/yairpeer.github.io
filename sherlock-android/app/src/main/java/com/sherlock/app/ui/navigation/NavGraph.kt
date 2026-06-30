package com.sherlock.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.sherlock.app.data.model.AppTheme
import com.sherlock.app.data.model.SearchType
import com.sherlock.app.ui.screens.*

object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val FACE_SEARCH = "face_search"
    const val USERNAME_SEARCH = "username_search"
    const val EMAIL_SEARCH = "email_search"
    const val PHONE_SEARCH = "phone_search"
    const val FACE_COMPARE = "face_compare"
    const val GOOGLE_DORK = "google_dork"
    const val EXIF_VIEWER = "exif_viewer"
    const val DOMAIN_LOOKUP = "domain_lookup"
    const val HISTORY = "history"
    const val FAVORITES = "favorites"
    const val STATISTICS = "statistics"
    const val SETTINGS = "settings"
    const val MONITOR = "monitor"
    const val OCR = "ocr"
    const val IMAGE_FORENSICS = "image_forensics"
    const val USERNAME_ANALYSIS = "username_analysis"
    const val EMAIL_PATTERN = "email_pattern"
    const val FAKE_PROFILE = "fake_profile"
    const val PROJECTS = "projects"
    const val NOTES = "notes"
    const val SEARCH_TEMPLATES = "search_templates"
    const val QR_GENERATOR = "qr_generator"
    const val IP_GEOLOCATION = "ip_geolocation"
    const val CUSTOM_SITES = "custom_sites"
    const val TIMELINE = "timeline"
    const val BATCH_SCANNER = "batch_scanner"
    const val BUILT_IN_BROWSER = "built_in_browser"
    const val SIDE_BY_SIDE = "side_by_side"
    const val SOCIAL_GRAPH = "social_graph"
    const val PHONE_INFO = "phone_info"
    const val SUBDOMAIN = "subdomain"
    const val METADATA_STRIPPER = "metadata_stripper"
    const val VOICE_SEARCH = "voice_search"
    const val VOICE_RESULT = "voice_result/{query}/{type}"
    fun voiceResultRoute(query: String, type: String) = "voice_result/${android.net.Uri.encode(query)}/$type"
    const val PEOPLE_FINDER = "people_finder"
    const val LICENSE_PLATE = "license_plate"
    const val IMAGE_HASH = "image_hash"
    const val UNIFIED_SEARCH = "unified_search"
    const val OBJECT_DETECTION = "object_detection"
    const val IMAGE_LABELING = "image_labeling"
    const val IMAGE_DIFF = "image_diff"
    const val COLLAGE = "collage"
}

@Composable
fun SherlockNavGraph(
    navController: NavHostController,
    onThemeChange: (AppTheme) -> Unit
) {
    NavHost(navController = navController, startDestination = Routes.SPLASH) {

        composable(Routes.SPLASH) {
            SplashScreen { navController.navigate(Routes.HOME) { popUpTo(Routes.SPLASH) { inclusive = true } } }
        }

        composable(Routes.ONBOARDING) {
            OnboardingScreen { navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } }
        }

        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToFaceSearch = { navController.navigate(Routes.FACE_SEARCH) },
                onNavigateToUsernameSearch = { navController.navigate(Routes.USERNAME_SEARCH) },
                onNavigateToEmailSearch = { navController.navigate(Routes.EMAIL_SEARCH) },
                onNavigateToPhoneSearch = { navController.navigate(Routes.PHONE_SEARCH) },
                onNavigateToGoogleDork = { navController.navigate(Routes.GOOGLE_DORK) },
                onNavigateToFaceCompare = { navController.navigate(Routes.FACE_COMPARE) },
                onNavigateToExifViewer = { navController.navigate(Routes.EXIF_VIEWER) },
                onNavigateToHistory = { navController.navigate(Routes.HISTORY) },
                onNavigateToFavorites = { navController.navigate(Routes.FAVORITES) },
                onNavigateToStatistics = { navController.navigate(Routes.STATISTICS) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToMonitor = { navController.navigate(Routes.MONITOR) },
                onNavigateToDomainLookup = { navController.navigate(Routes.DOMAIN_LOOKUP) },
                onNavigateToOCR = { navController.navigate(Routes.OCR) },
                onNavigateToImageForensics = { navController.navigate(Routes.IMAGE_FORENSICS) },
                onNavigateToUsernameAnalysis = { navController.navigate(Routes.USERNAME_ANALYSIS) },
                onNavigateToEmailPattern = { navController.navigate(Routes.EMAIL_PATTERN) },
                onNavigateToFakeProfile = { navController.navigate(Routes.FAKE_PROFILE) },
                onNavigateToProjects = { navController.navigate(Routes.PROJECTS) },
                onNavigateToNotes = { navController.navigate(Routes.NOTES) },
                onNavigateToSearchTemplates = { navController.navigate(Routes.SEARCH_TEMPLATES) },
                onNavigateToQRGenerator = { navController.navigate(Routes.QR_GENERATOR) },
                onNavigateToIpGeolocation = { navController.navigate(Routes.IP_GEOLOCATION) },
                onNavigateToCustomSites = { navController.navigate(Routes.CUSTOM_SITES) },
                onNavigateToTimeline = { navController.navigate(Routes.TIMELINE) },
                onNavigateToBatchScanner = { navController.navigate(Routes.BATCH_SCANNER) },
                onNavigateToSideBySide = { navController.navigate(Routes.SIDE_BY_SIDE) },
                onNavigateToSocialGraph = { navController.navigate(Routes.SOCIAL_GRAPH) },
                onNavigateToPhoneInfo = { navController.navigate(Routes.PHONE_INFO) },
                onNavigateToSubdomain = { navController.navigate(Routes.SUBDOMAIN) },
                onNavigateToMetadataStripper = { navController.navigate(Routes.METADATA_STRIPPER) },
                onNavigateToVoiceSearch = { navController.navigate(Routes.VOICE_SEARCH) },
                onNavigateToPeopleFinder = { navController.navigate(Routes.PEOPLE_FINDER) },
                onNavigateToLicensePlate = { navController.navigate(Routes.LICENSE_PLATE) },
                onNavigateToImageHash = { navController.navigate(Routes.IMAGE_HASH) },
                onNavigateToUnifiedSearch = { navController.navigate(Routes.UNIFIED_SEARCH) },
                onNavigateToObjectDetection = { navController.navigate(Routes.OBJECT_DETECTION) },
                onNavigateToImageLabeling = { navController.navigate(Routes.IMAGE_LABELING) },
                onNavigateToImageDiff = { navController.navigate(Routes.IMAGE_DIFF) },
                onNavigateToCollage = { navController.navigate(Routes.COLLAGE) }
            )
        }

        composable(Routes.FACE_SEARCH) {
            FaceSearchScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.USERNAME_SEARCH) {
            UsernameSearchScreen(onNavigateBack = { navController.popBackStack() }, searchType = SearchType.USERNAME)
        }

        composable(Routes.EMAIL_SEARCH) {
            UsernameSearchScreen(onNavigateBack = { navController.popBackStack() }, searchType = SearchType.EMAIL)
        }

        composable(Routes.PHONE_SEARCH) {
            UsernameSearchScreen(onNavigateBack = { navController.popBackStack() }, searchType = SearchType.PHONE)
        }

        composable(Routes.FACE_COMPARE) {
            FaceCompareScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.GOOGLE_DORK) {
            GoogleDorkScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.EXIF_VIEWER) {
            ExifViewerScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.DOMAIN_LOOKUP) {
            DomainLookupScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.HISTORY) {
            HistoryScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.FAVORITES) {
            FavoritesScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.STATISTICS) {
            StatisticsScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onThemeChange = onThemeChange
            )
        }

        composable(Routes.MONITOR) {
            MonitorScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.OCR) {
            OCRScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.IMAGE_FORENSICS) {
            ImageForensicsScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.USERNAME_ANALYSIS) {
            UsernameAnalysisScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.EMAIL_PATTERN) {
            EmailPatternScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.FAKE_PROFILE) {
            FakeProfileScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.PROJECTS) {
            ProjectsScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.NOTES) {
            NotesScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.SEARCH_TEMPLATES) {
            SearchTemplatesScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.QR_GENERATOR) {
            QRGeneratorScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.IP_GEOLOCATION) {
            IpGeolocationScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.CUSTOM_SITES) {
            CustomSitesScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.TIMELINE) {
            TimelineScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.BATCH_SCANNER) {
            BatchScannerScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.BUILT_IN_BROWSER) {
            BuiltInBrowserScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.SIDE_BY_SIDE) {
            SideBySideScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.SOCIAL_GRAPH) {
            SocialGraphScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.PHONE_INFO) {
            PhoneInfoScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.SUBDOMAIN) {
            SubdomainScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.METADATA_STRIPPER) {
            MetadataStripperScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.VOICE_SEARCH) {
            VoiceSearchScreen(
                onNavigateBack = { navController.popBackStack() },
                onSearch = { query, type ->
                    navController.navigate(Routes.voiceResultRoute(query, type.name))
                }
            )
        }

        composable(
            route = Routes.VOICE_RESULT,
            arguments = listOf(
                navArgument("query") { type = NavType.StringType; defaultValue = "" },
                navArgument("type") { type = NavType.StringType; defaultValue = "USERNAME" }
            )
        ) { backStack ->
            val query = backStack.arguments?.getString("query") ?: ""
            val type = try { SearchType.valueOf(backStack.arguments?.getString("type") ?: "USERNAME") } catch (_: Exception) { SearchType.USERNAME }
            UsernameSearchScreen(
                onNavigateBack = { navController.popBackStack() },
                searchType = type,
                initialQuery = query
            )
        }

        composable(Routes.PEOPLE_FINDER) {
            PeopleFinderScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.LICENSE_PLATE) {
            LicensePlateScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.IMAGE_HASH) {
            ImageHashScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.UNIFIED_SEARCH) {
            UnifiedSearchScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.OBJECT_DETECTION) {
            ObjectDetectionScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.IMAGE_LABELING) {
            ImageLabelingScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.IMAGE_DIFF) {
            ImageDiffScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.COLLAGE) {
            CollageScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
