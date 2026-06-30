package com.sherlock.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.sherlock.app.data.model.AppTheme
import com.sherlock.app.data.model.SearchType
import com.sherlock.app.ui.screens.*
import com.sherlock.app.util.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    const val PROJECT_DETAIL = "project_detail/{projectId}"
    fun projectDetailRoute(projectId: Long) = "project_detail/$projectId"
    const val TRASH = "trash"
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
    const val PROFILE_LINK_HEALTH = "profile_link_health"
    const val USERNAME_MATCHER = "username_matcher"
    const val PLATFORM_FOOTPRINT = "platform_footprint"
    const val BIO_LINK_EXTRACTOR = "bio_link_extractor"
    const val USERNAME_FORMAT_VALIDATOR = "username_format_validator"
    const val PLATFORM_GUIDE = "platform_guide"
    const val DIGITAL_IDENTITY = "digital_identity"
    const val SSL_CERTIFICATE = "ssl_certificate"
    const val DNS_RECORDS = "dns_records"
    const val HTTP_HEADERS = "http_headers"
    const val WEBSITE_SNAPSHOT = "website_snapshot"
    const val MY_IP = "my_ip"
    const val REDIRECT_CHAIN = "redirect_chain"
    const val VPN_PROXY_CHECK = "vpn_proxy_check"
    const val HISTORY_EXPORT = "history_export"
    const val FAVORITES_EXPORT = "favorites_export"
    const val NOTES_EXPORT = "notes_export"
    const val PROJECT_REPORT = "project_report"
    const val IDENTITY_REPORT = "identity_report"
    const val FULL_BACKUP_EXPORT = "full_backup_export"
    const val SUMMARY_CARD = "summary_card"
    const val SCHEDULED_SEARCHES = "scheduled_searches"
    const val INVESTIGATION_WORKFLOW = "investigation_workflow"
    const val AUTOMATION_HUB = "automation_hub"
    const val CATEGORY_BREAKDOWN = "category_breakdown"
    const val TOP_SITES = "top_sites"
    const val ACTIVITY_TREND = "activity_trend"
    const val PERFORMANCE_ANALYTICS = "performance_analytics"
    const val PERIOD_COMPARISON = "period_comparison"
    const val FORMAT_CONVERTER = "format_converter"
    const val DATE_CALCULATOR = "date_calculator"
    const val SLANG_DICTIONARY = "slang_dictionary"
    const val DORKS_LIBRARY = "dorks_library"
    const val URL_EMAIL_VALIDATOR = "url_email_validator"
    const val SAVED_LINKS = "saved_links"
    const val TRANSLATE_RESULTS = "translate_results"
    const val SOURCE_RELIABILITY = "source_reliability"
    const val DEMO_MODE = "demo_mode"
    const val BREACH_CHECK = "breach_check"
    const val HASH_LOOKUP = "hash_lookup"
    const val PASTE_SEARCH = "paste_search"
    const val WAYBACK_MACHINE = "wayback_machine"
    const val EXPOSURE_REPORT = "exposure_report"
}

@Composable
fun SherlockNavGraph(
    navController: NavHostController,
    onThemeChange: (AppTheme) -> Unit
) {
    NavHost(navController = navController, startDestination = Routes.SPLASH) {

        composable(Routes.SPLASH) {
            val context = LocalContext.current
            val settings = remember { SettingsManager(context) }
            val scope = rememberCoroutineScope()
            SplashScreen {
                scope.launch {
                    val destination = if (settings.isFirstLaunch.first()) Routes.ONBOARDING else Routes.HOME
                    navController.navigate(destination) { popUpTo(Routes.SPLASH) { inclusive = true } }
                }
            }
        }

        composable(Routes.ONBOARDING) {
            val context = LocalContext.current
            val settings = remember { SettingsManager(context) }
            val scope = rememberCoroutineScope()
            OnboardingScreen {
                scope.launch {
                    settings.setFirstLaunch(false)
                    navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                }
            }
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
                onNavigateToCollage = { navController.navigate(Routes.COLLAGE) },
                onNavigateToProfileLinkHealth = { navController.navigate(Routes.PROFILE_LINK_HEALTH) },
                onNavigateToUsernameMatcher = { navController.navigate(Routes.USERNAME_MATCHER) },
                onNavigateToPlatformFootprint = { navController.navigate(Routes.PLATFORM_FOOTPRINT) },
                onNavigateToBioLinkExtractor = { navController.navigate(Routes.BIO_LINK_EXTRACTOR) },
                onNavigateToUsernameFormatValidator = { navController.navigate(Routes.USERNAME_FORMAT_VALIDATOR) },
                onNavigateToPlatformGuide = { navController.navigate(Routes.PLATFORM_GUIDE) },
                onNavigateToDigitalIdentity = { navController.navigate(Routes.DIGITAL_IDENTITY) },
                onNavigateToSslCertificate = { navController.navigate(Routes.SSL_CERTIFICATE) },
                onNavigateToDnsRecords = { navController.navigate(Routes.DNS_RECORDS) },
                onNavigateToHttpHeaders = { navController.navigate(Routes.HTTP_HEADERS) },
                onNavigateToWebsiteSnapshot = { navController.navigate(Routes.WEBSITE_SNAPSHOT) },
                onNavigateToMyIp = { navController.navigate(Routes.MY_IP) },
                onNavigateToRedirectChain = { navController.navigate(Routes.REDIRECT_CHAIN) },
                onNavigateToVpnProxyCheck = { navController.navigate(Routes.VPN_PROXY_CHECK) },
                onNavigateToHistoryExport = { navController.navigate(Routes.HISTORY_EXPORT) },
                onNavigateToFavoritesExport = { navController.navigate(Routes.FAVORITES_EXPORT) },
                onNavigateToNotesExport = { navController.navigate(Routes.NOTES_EXPORT) },
                onNavigateToProjectReport = { navController.navigate(Routes.PROJECT_REPORT) },
                onNavigateToIdentityReport = { navController.navigate(Routes.IDENTITY_REPORT) },
                onNavigateToFullBackupExport = { navController.navigate(Routes.FULL_BACKUP_EXPORT) },
                onNavigateToSummaryCard = { navController.navigate(Routes.SUMMARY_CARD) },
                onNavigateToQuickSearch = { query, type -> navController.navigate(Routes.voiceResultRoute(query, type)) },
                onNavigateToScheduledSearches = { navController.navigate(Routes.SCHEDULED_SEARCHES) },
                onNavigateToInvestigationWorkflow = { navController.navigate(Routes.INVESTIGATION_WORKFLOW) },
                onNavigateToAutomationHub = { navController.navigate(Routes.AUTOMATION_HUB) },
                onNavigateToCategoryBreakdown = { navController.navigate(Routes.CATEGORY_BREAKDOWN) },
                onNavigateToTopSites = { navController.navigate(Routes.TOP_SITES) },
                onNavigateToActivityTrend = { navController.navigate(Routes.ACTIVITY_TREND) },
                onNavigateToPerformanceAnalytics = { navController.navigate(Routes.PERFORMANCE_ANALYTICS) },
                onNavigateToPeriodComparison = { navController.navigate(Routes.PERIOD_COMPARISON) },
                onNavigateToFormatConverter = { navController.navigate(Routes.FORMAT_CONVERTER) },
                onNavigateToDateCalculator = { navController.navigate(Routes.DATE_CALCULATOR) },
                onNavigateToSlangDictionary = { navController.navigate(Routes.SLANG_DICTIONARY) },
                onNavigateToDorksLibrary = { navController.navigate(Routes.DORKS_LIBRARY) },
                onNavigateToUrlEmailValidator = { navController.navigate(Routes.URL_EMAIL_VALIDATOR) },
                onNavigateToSavedLinks = { navController.navigate(Routes.SAVED_LINKS) },
                onNavigateToTranslateResults = { navController.navigate(Routes.TRANSLATE_RESULTS) },
                onNavigateToSourceReliability = { navController.navigate(Routes.SOURCE_RELIABILITY) },
                onNavigateToDemoMode = { navController.navigate(Routes.DEMO_MODE) },
                onNavigateToBreachCheck = { navController.navigate(Routes.BREACH_CHECK) },
                onNavigateToHashLookup = { navController.navigate(Routes.HASH_LOOKUP) },
                onNavigateToPasteSearch = { navController.navigate(Routes.PASTE_SEARCH) },
                onNavigateToWaybackMachine = { navController.navigate(Routes.WAYBACK_MACHINE) },
                onNavigateToExposureReport = { navController.navigate(Routes.EXPOSURE_REPORT) }
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
            ProjectsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToProjectDetail = { projectId -> navController.navigate(Routes.projectDetailRoute(projectId)) },
                onNavigateToTrash = { navController.navigate(Routes.TRASH) }
            )
        }

        composable(
            route = Routes.PROJECT_DETAIL,
            arguments = listOf(navArgument("projectId") { type = NavType.LongType })
        ) { backStack ->
            val projectId = backStack.arguments?.getLong("projectId") ?: 0L
            ProjectDetailScreen(projectId = projectId, onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.TRASH) {
            TrashScreen(onNavigateBack = { navController.popBackStack() })
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

        composable(Routes.PROFILE_LINK_HEALTH) {
            ProfileLinkHealthScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.USERNAME_MATCHER) {
            UsernameMatcherScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.PLATFORM_FOOTPRINT) {
            PlatformFootprintScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.BIO_LINK_EXTRACTOR) {
            BioLinkExtractorScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.USERNAME_FORMAT_VALIDATOR) {
            UsernameFormatValidatorScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.PLATFORM_GUIDE) {
            PlatformGuideScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.DIGITAL_IDENTITY) {
            DigitalIdentityScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.SSL_CERTIFICATE) {
            SslCertificateScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.DNS_RECORDS) {
            DnsRecordsScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.HTTP_HEADERS) {
            HttpHeadersScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.WEBSITE_SNAPSHOT) {
            WebsiteSnapshotScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.MY_IP) {
            MyIpScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.REDIRECT_CHAIN) {
            RedirectChainScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.VPN_PROXY_CHECK) {
            VpnProxyCheckScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.HISTORY_EXPORT) {
            HistoryExportScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.FAVORITES_EXPORT) {
            FavoritesExportScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.NOTES_EXPORT) {
            NotesExportScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.PROJECT_REPORT) {
            ProjectReportScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.IDENTITY_REPORT) {
            DigitalIdentityReportScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.FULL_BACKUP_EXPORT) {
            FullBackupExportScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.SUMMARY_CARD) {
            SummaryCardScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.SCHEDULED_SEARCHES) {
            ScheduledSearchesScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.INVESTIGATION_WORKFLOW) {
            InvestigationWorkflowScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToUsernameSearch = { navController.navigate(Routes.USERNAME_SEARCH) },
                onNavigateToEmailSearch = { navController.navigate(Routes.EMAIL_SEARCH) },
                onNavigateToFaceSearch = { navController.navigate(Routes.FACE_SEARCH) },
                onNavigateToDomainLookup = { navController.navigate(Routes.DOMAIN_LOOKUP) },
                onNavigateToSocialGraph = { navController.navigate(Routes.SOCIAL_GRAPH) },
                onNavigateToIdentityReport = { navController.navigate(Routes.IDENTITY_REPORT) }
            )
        }

        composable(Routes.AUTOMATION_HUB) {
            AutomationHubScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToMonitor = { navController.navigate(Routes.MONITOR) },
                onNavigateToScheduledSearches = { navController.navigate(Routes.SCHEDULED_SEARCHES) },
                onNavigateToInvestigationWorkflow = { navController.navigate(Routes.INVESTIGATION_WORKFLOW) }
            )
        }

        composable(Routes.CATEGORY_BREAKDOWN) {
            CategoryBreakdownScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.TOP_SITES) {
            TopSitesScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.ACTIVITY_TREND) {
            ActivityTrendScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.PERFORMANCE_ANALYTICS) {
            PerformanceAnalyticsScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.PERIOD_COMPARISON) {
            PeriodComparisonScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.FORMAT_CONVERTER) {
            FormatConverterScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.DATE_CALCULATOR) {
            DateCalculatorScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.SLANG_DICTIONARY) {
            SlangDictionaryScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.DORKS_LIBRARY) {
            DorksLibraryScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.URL_EMAIL_VALIDATOR) {
            UrlEmailValidatorScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.SAVED_LINKS) {
            SavedLinksScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.TRANSLATE_RESULTS) {
            TranslateResultsScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.SOURCE_RELIABILITY) {
            SourceReliabilityScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.DEMO_MODE) {
            DemoModeScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.BREACH_CHECK) {
            BreachCheckScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.HASH_LOOKUP) {
            HashLookupScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.PASTE_SEARCH) {
            PasteSearchScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.WAYBACK_MACHINE) {
            WaybackMachineScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.EXPOSURE_REPORT) {
            ExposureReportScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
