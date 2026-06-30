package com.sherlock.app.data.repository

import com.sherlock.app.data.model.ErrorType
import com.sherlock.app.data.model.SiteCategory
import com.sherlock.app.data.model.SiteConfig

object SitesDatabase {

    val sites: List<SiteConfig> = listOf(
        // ═══════════════════════════════════════════
        // SOCIAL MEDIA (40+)
        // ═══════════════════════════════════════════
        SiteConfig("Instagram", "https://www.instagram.com/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Facebook", "https://www.facebook.com/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Twitter/X", "https://x.com/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("TikTok", "https://www.tiktok.com/@{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("LinkedIn", "https://www.linkedin.com/in/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Snapchat", "https://www.snapchat.com/add/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Pinterest", "https://www.pinterest.com/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Reddit", "https://www.reddit.com/user/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Tumblr", "https://{}.tumblr.com", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("VK", "https://vk.com/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Mastodon", "https://mastodon.social/@{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Threads", "https://www.threads.net/@{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Bluesky", "https://bsky.app/profile/{}.bsky.social", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Clubhouse", "https://www.clubhouse.com/@{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Telegram", "https://t.me/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("WeChat", "https://www.wechat.com/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Line", "https://line.me/ti/p/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Viber", "https://viber.com/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Signal", "https://signal.me/#{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Discord (ID)", "https://discord.com/users/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Gab", "https://gab.com/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Parler", "https://parler.com/profile/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Truth Social", "https://truthsocial.com/@{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Gettr", "https://gettr.com/user/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("MeWe", "https://mewe.com/i/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Minds", "https://www.minds.com/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Diaspora", "https://diaspora.social/people?q={}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Ello", "https://ello.co/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Ask.fm", "https://ask.fm/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Curious Cat", "https://curiouscat.live/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Retrospring", "https://retrospring.net/@{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Plurk", "https://www.plurk.com/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("OK.ru", "https://ok.ru/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Taringa", "https://www.taringa.net/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Wattpad", "https://www.wattpad.com/user/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Amino", "https://aminoapps.com/u/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Kik", "https://kik.me/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Badoo", "https://badoo.com/profile/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Tagged", "https://www.tagged.com/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Hi5", "https://www.hi5.com/{}", SiteCategory.SOCIAL_MEDIA),

        // ═══════════════════════════════════════════
        // PHOTO & VIDEO (30+)
        // ═══════════════════════════════════════════
        SiteConfig("YouTube", "https://www.youtube.com/@{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Flickr", "https://www.flickr.com/people/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Vimeo", "https://vimeo.com/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("500px", "https://500px.com/p/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("VSCO", "https://vsco.co/{}/gallery", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Imgur", "https://imgur.com/user/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("DeviantArt", "https://{}.deviantart.com", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Twitch", "https://www.twitch.tv/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Dailymotion", "https://www.dailymotion.com/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Rumble", "https://rumble.com/user/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Giphy", "https://giphy.com/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Unsplash", "https://unsplash.com/@{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Pexels", "https://www.pexels.com/@{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Pixiv", "https://www.pixiv.net/users/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("ArtStation", "https://www.artstation.com/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("SmugMug", "https://{}.smugmug.com", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Photobucket", "https://photobucket.com/u/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Loom", "https://www.loom.com/share/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Bitchute", "https://www.bitchute.com/channel/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Odysee", "https://odysee.com/@{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("PeerTube", "https://videos.pair2jeux.tube/a/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Kick", "https://kick.com/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("DLive", "https://dlive.tv/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Trovo", "https://trovo.live/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Bilibili", "https://space.bilibili.com/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Niconico", "https://www.nicovideo.jp/user/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Newgrounds", "https://{}.newgrounds.com", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Eyeem", "https://www.eyeem.com/u/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Behance", "https://www.behance.net/{}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("Dribbble", "https://dribbble.com/{}", SiteCategory.PHOTO_VIDEO),

        // ═══════════════════════════════════════════
        // MUSIC (15+)
        // ═══════════════════════════════════════════
        SiteConfig("Spotify", "https://open.spotify.com/user/{}", SiteCategory.MUSIC),
        SiteConfig("SoundCloud", "https://soundcloud.com/{}", SiteCategory.MUSIC),
        SiteConfig("Bandcamp", "https://{}.bandcamp.com", SiteCategory.MUSIC),
        SiteConfig("Last.fm", "https://www.last.fm/user/{}", SiteCategory.MUSIC),
        SiteConfig("Deezer", "https://www.deezer.com/profile/{}", SiteCategory.MUSIC),
        SiteConfig("Apple Music", "https://music.apple.com/profile/{}", SiteCategory.MUSIC),
        SiteConfig("Genius", "https://genius.com/artists/{}", SiteCategory.MUSIC),
        SiteConfig("Mixcloud", "https://www.mixcloud.com/{}", SiteCategory.MUSIC),
        SiteConfig("ReverbNation", "https://www.reverbnation.com/{}", SiteCategory.MUSIC),
        SiteConfig("Audiomack", "https://audiomack.com/{}", SiteCategory.MUSIC),
        SiteConfig("Rate Your Music", "https://rateyourmusic.com/~{}", SiteCategory.MUSIC),
        SiteConfig("Discogs", "https://www.discogs.com/user/{}", SiteCategory.MUSIC),
        SiteConfig("Musescore", "https://musescore.com/user/{}", SiteCategory.MUSIC),
        SiteConfig("Songkick", "https://www.songkick.com/artists/{}", SiteCategory.MUSIC),
        SiteConfig("Tidal", "https://tidal.com/browse/artist/{}", SiteCategory.MUSIC),

        // ═══════════════════════════════════════════
        // GAMING (25+)
        // ═══════════════════════════════════════════
        SiteConfig("Steam", "https://steamcommunity.com/id/{}", SiteCategory.GAMING),
        SiteConfig("Xbox", "https://xboxgamertag.com/search/{}", SiteCategory.GAMING),
        SiteConfig("PlayStation", "https://psnprofiles.com/{}", SiteCategory.GAMING),
        SiteConfig("Chess.com", "https://www.chess.com/member/{}", SiteCategory.GAMING),
        SiteConfig("Lichess", "https://lichess.org/@/{}", SiteCategory.GAMING),
        SiteConfig("Roblox", "https://www.roblox.com/user.aspx?username={}", SiteCategory.GAMING, ErrorType.MESSAGE_IN_PAGE, "Page cannot be found"),
        SiteConfig("Minecraft (NameMC)", "https://namemc.com/profile/{}", SiteCategory.GAMING),
        SiteConfig("Fortnite Tracker", "https://fortnitetracker.com/profile/all/{}", SiteCategory.GAMING),
        SiteConfig("League of Legends (op.gg)", "https://www.op.gg/summoners/euw/{}", SiteCategory.GAMING),
        SiteConfig("Valorant Tracker", "https://tracker.gg/valorant/profile/riot/{}%23NA1", SiteCategory.GAMING),
        SiteConfig("Osu!", "https://osu.ppy.sh/users/{}", SiteCategory.GAMING),
        SiteConfig("Speedrun.com", "https://www.speedrun.com/users/{}", SiteCategory.GAMING),
        SiteConfig("RetroAchievements", "https://retroachievements.org/user/{}", SiteCategory.GAMING),
        SiteConfig("Roblox Trade", "https://www.rolimons.com/player/{}", SiteCategory.GAMING),
        SiteConfig("Hypixel (Plancke)", "https://plancke.io/hypixel/player/stats/{}", SiteCategory.GAMING),
        SiteConfig("Epic Games (Fortnite)", "https://fortnitetracker.com/profile/all/{}", SiteCategory.GAMING),
        SiteConfig("Battle.net", "https://playoverwatch.com/career/pc/{}", SiteCategory.GAMING),
        SiteConfig("Nintendo", "https://www.nintendo.com/us/accounts/{}", SiteCategory.GAMING),
        SiteConfig("Tabletop Simulator", "https://steamcommunity.com/id/{}", SiteCategory.GAMING),
        SiteConfig("FACEIT", "https://www.faceit.com/en/players/{}", SiteCategory.GAMING),
        SiteConfig("GGPoker", "https://pokergo.com/players/{}", SiteCategory.GAMING),
        SiteConfig("Apex Tracker", "https://apex.tracker.gg/apex/profile/origin/{}", SiteCategory.GAMING),
        SiteConfig("R6 Tracker", "https://r6.tracker.network/profile/pc/{}", SiteCategory.GAMING),
        SiteConfig("Destiny Tracker", "https://destinytracker.com/destiny-2/profile/bungie/{}", SiteCategory.GAMING),
        SiteConfig("Brawlhalla", "https://brawlhalla.com/rankings/player?player_name={}", SiteCategory.GAMING),

        // ═══════════════════════════════════════════
        // TECH & DEV (30+)
        // ═══════════════════════════════════════════
        SiteConfig("GitHub", "https://github.com/{}", SiteCategory.TECH),
        SiteConfig("GitLab", "https://gitlab.com/{}", SiteCategory.TECH),
        SiteConfig("Bitbucket", "https://bitbucket.org/{}/", SiteCategory.TECH),
        SiteConfig("Stack Overflow", "https://stackoverflow.com/users/?tab=accounts&SearchText={}", SiteCategory.TECH),
        SiteConfig("HackerNews", "https://news.ycombinator.com/user?id={}", SiteCategory.TECH),
        SiteConfig("Dev.to", "https://dev.to/{}", SiteCategory.TECH),
        SiteConfig("Medium", "https://medium.com/@{}", SiteCategory.TECH),
        SiteConfig("Kaggle", "https://www.kaggle.com/{}", SiteCategory.TECH),
        SiteConfig("Replit", "https://replit.com/@{}", SiteCategory.TECH),
        SiteConfig("CodePen", "https://codepen.io/{}", SiteCategory.TECH),
        SiteConfig("Hashnode", "https://hashnode.com/@{}", SiteCategory.TECH),
        SiteConfig("LeetCode", "https://leetcode.com/{}", SiteCategory.TECH),
        SiteConfig("HackerRank", "https://www.hackerrank.com/{}", SiteCategory.TECH),
        SiteConfig("CodeWars", "https://www.codewars.com/users/{}", SiteCategory.TECH),
        SiteConfig("HackerEarth", "https://www.hackerearth.com/@{}", SiteCategory.TECH),
        SiteConfig("TopCoder", "https://www.topcoder.com/members/{}", SiteCategory.TECH),
        SiteConfig("Codeforces", "https://codeforces.com/profile/{}", SiteCategory.TECH),
        SiteConfig("npm", "https://www.npmjs.com/~{}", SiteCategory.TECH),
        SiteConfig("PyPI", "https://pypi.org/user/{}/", SiteCategory.TECH),
        SiteConfig("Docker Hub", "https://hub.docker.com/u/{}", SiteCategory.TECH),
        SiteConfig("SourceForge", "https://sourceforge.net/u/{}/profile/", SiteCategory.TECH),
        SiteConfig("Launchpad", "https://launchpad.net/~{}", SiteCategory.TECH),
        SiteConfig("Keybase", "https://keybase.io/{}", SiteCategory.TECH),
        SiteConfig("Codeberg", "https://codeberg.org/{}", SiteCategory.TECH),
        SiteConfig("Gitea", "https://gitea.com/{}", SiteCategory.TECH),
        SiteConfig("XDA Developers", "https://xdaforums.com/m/{}.0/", SiteCategory.TECH),
        SiteConfig("Instructables", "https://www.instructables.com/member/{}", SiteCategory.TECH),
        SiteConfig("Thingiverse", "https://www.thingiverse.com/{}", SiteCategory.TECH),
        SiteConfig("Observable", "https://observablehq.com/@{}", SiteCategory.TECH),
        SiteConfig("Glitch", "https://glitch.com/@{}", SiteCategory.TECH),
        SiteConfig("Vercel", "https://vercel.com/{}", SiteCategory.TECH),
        SiteConfig("Netlify", "https://app.netlify.com/teams/{}", SiteCategory.TECH),

        // ═══════════════════════════════════════════
        // FORUMS & COMMUNITY (20+)
        // ═══════════════════════════════════════════
        SiteConfig("Quora", "https://www.quora.com/profile/{}", SiteCategory.FORUM),
        SiteConfig("Disqus", "https://disqus.com/by/{}/", SiteCategory.FORUM),
        SiteConfig("Discourse", "https://meta.discourse.org/u/{}", SiteCategory.FORUM),
        SiteConfig("4chan Archive", "https://archived.moe/_/search/username/{}", SiteCategory.FORUM),
        SiteConfig("Voat", "https://voat.co/user/{}", SiteCategory.FORUM),
        SiteConfig("Lemmy", "https://lemmy.world/u/{}", SiteCategory.FORUM),
        SiteConfig("Kbin", "https://kbin.social/u/{}", SiteCategory.FORUM),
        SiteConfig("Hive (Pair2Jeux)", "https://peakd.com/@{}", SiteCategory.FORUM),
        SiteConfig("SlashDot", "https://slashdot.org/~{}", SiteCategory.FORUM),
        SiteConfig("Lobsters", "https://lobste.rs/u/{}", SiteCategory.FORUM),
        SiteConfig("Tildes", "https://tildes.net/user/{}", SiteCategory.FORUM),
        SiteConfig("Fandom", "https://www.fandom.com/u/{}", SiteCategory.FORUM),
        SiteConfig("Wikipedia", "https://en.wikipedia.org/wiki/User:{}", SiteCategory.FORUM),
        SiteConfig("WikiHow", "https://www.wikihow.com/User:{}", SiteCategory.FORUM),
        SiteConfig("StackExchange", "https://stackexchange.com/users/?searchText={}", SiteCategory.FORUM),
        SiteConfig("Zhihu", "https://www.zhihu.com/people/{}", SiteCategory.FORUM),
        SiteConfig("Naver", "https://blog.naver.com/{}", SiteCategory.FORUM),
        SiteConfig("FPV Freedom Coalition", "https://fpvfc.org/member/{}", SiteCategory.FORUM),
        SiteConfig("HubPages", "https://hubpages.com/@{}", SiteCategory.FORUM),
        SiteConfig("LiveJournal", "https://{}.livejournal.com", SiteCategory.FORUM),

        // ═══════════════════════════════════════════
        // BUSINESS & PROFESSIONAL (25+)
        // ═══════════════════════════════════════════
        SiteConfig("Fiverr", "https://www.fiverr.com/{}", SiteCategory.BUSINESS),
        SiteConfig("Upwork", "https://www.upwork.com/freelancers/~{}", SiteCategory.BUSINESS),
        SiteConfig("About.me", "https://about.me/{}", SiteCategory.BUSINESS),
        SiteConfig("Gravatar", "https://en.gravatar.com/{}", SiteCategory.BUSINESS),
        SiteConfig("Product Hunt", "https://www.producthunt.com/@{}", SiteCategory.BUSINESS),
        SiteConfig("Linktree", "https://linktr.ee/{}", SiteCategory.BUSINESS),
        SiteConfig("Patreon", "https://www.patreon.com/{}", SiteCategory.BUSINESS),
        SiteConfig("Buy Me a Coffee", "https://www.buymeacoffee.com/{}", SiteCategory.BUSINESS),
        SiteConfig("Ko-fi", "https://ko-fi.com/{}", SiteCategory.BUSINESS),
        SiteConfig("Etsy", "https://www.etsy.com/shop/{}", SiteCategory.BUSINESS),
        SiteConfig("Substack", "https://{}.substack.com", SiteCategory.BUSINESS),
        SiteConfig("Carrd", "https://{}.carrd.co", SiteCategory.BUSINESS),
        SiteConfig("Crunchbase", "https://www.crunchbase.com/person/{}", SiteCategory.BUSINESS),
        SiteConfig("AngelList", "https://angel.co/u/{}", SiteCategory.BUSINESS),
        SiteConfig("Freelancer", "https://www.freelancer.com/u/{}", SiteCategory.BUSINESS),
        SiteConfig("99designs", "https://99designs.com/profiles/{}", SiteCategory.BUSINESS),
        SiteConfig("Toptal", "https://www.toptal.com/resume/{}", SiteCategory.BUSINESS),
        SiteConfig("Guru", "https://www.guru.com/freelancers/{}", SiteCategory.BUSINESS),
        SiteConfig("People Per Hour", "https://www.peopleperhour.com/freelancer/{}", SiteCategory.BUSINESS),
        SiteConfig("SlideShare", "https://www.slideshare.net/{}", SiteCategory.BUSINESS),
        SiteConfig("Issuu", "https://issuu.com/{}", SiteCategory.BUSINESS),
        SiteConfig("Calendly", "https://calendly.com/{}", SiteCategory.BUSINESS),
        SiteConfig("Gumroad", "https://{}.gumroad.com", SiteCategory.BUSINESS),
        SiteConfig("Teachable", "https://{}.teachable.com", SiteCategory.BUSINESS),
        SiteConfig("Udemy", "https://www.udemy.com/user/{}", SiteCategory.BUSINESS),

        // ═══════════════════════════════════════════
        // FINANCE & CRYPTO (15+)
        // ═══════════════════════════════════════════
        SiteConfig("CoinMarketCap", "https://coinmarketcap.com/community/profile/{}", SiteCategory.CRYPTO),
        SiteConfig("CoinGecko", "https://www.coingecko.com/en/accounts/{}", SiteCategory.CRYPTO),
        SiteConfig("TradingView", "https://www.tradingview.com/u/{}/", SiteCategory.CRYPTO),
        SiteConfig("Binance", "https://www.binance.com/en/feed/profile/{}", SiteCategory.CRYPTO),
        SiteConfig("Ethereum Name", "https://app.ens.domains/name/{}.eth", SiteCategory.CRYPTO),
        SiteConfig("OpenSea", "https://opensea.io/{}", SiteCategory.CRYPTO),
        SiteConfig("Rarible", "https://rarible.com/{}", SiteCategory.CRYPTO),
        SiteConfig("Foundation", "https://foundation.app/@{}", SiteCategory.CRYPTO),
        SiteConfig("Mirror.xyz", "https://mirror.xyz/{}", SiteCategory.CRYPTO),
        SiteConfig("Polymarket", "https://polymarket.com/profile/{}", SiteCategory.CRYPTO),
        SiteConfig("Seeking Alpha", "https://seekingalpha.com/user/{}", SiteCategory.FINANCE),
        SiteConfig("StockTwits", "https://stocktwits.com/{}", SiteCategory.FINANCE),
        SiteConfig("Investing.com", "https://www.investing.com/members/{}", SiteCategory.FINANCE),
        SiteConfig("Kiplinger", "https://www.kiplinger.com/author/{}", SiteCategory.FINANCE),

        // ═══════════════════════════════════════════
        // NEWS & BLOGGING (15+)
        // ═══════════════════════════════════════════
        SiteConfig("WordPress", "https://{}.wordpress.com", SiteCategory.NEWS),
        SiteConfig("Blogger", "https://{}.blogspot.com", SiteCategory.NEWS),
        SiteConfig("Ghost", "https://{}.ghost.io", SiteCategory.NEWS),
        SiteConfig("Wix", "https://{}.wixsite.com", SiteCategory.NEWS),
        SiteConfig("Weebly", "https://{}.weebly.com", SiteCategory.NEWS),
        SiteConfig("Notion", "https://notion.so/@{}", SiteCategory.NEWS),
        SiteConfig("Telegra.ph", "https://telegra.ph/@{}", SiteCategory.NEWS),
        SiteConfig("Bear Blog", "https://{}.bearblog.dev", SiteCategory.NEWS),
        SiteConfig("Write.as", "https://write.as/{}", SiteCategory.NEWS),
        SiteConfig("Svbtle", "https://{}.svbtle.com", SiteCategory.NEWS),
        SiteConfig("Micro.blog", "https://micro.blog/{}", SiteCategory.NEWS),
        SiteConfig("Cohost", "https://cohost.org/{}", SiteCategory.NEWS),
        SiteConfig("Letterboxd", "https://letterboxd.com/{}", SiteCategory.NEWS),
        SiteConfig("Goodreads", "https://www.goodreads.com/{}", SiteCategory.NEWS),
        SiteConfig("LibraryThing", "https://www.librarything.com/profile/{}", SiteCategory.NEWS),

        // ═══════════════════════════════════════════
        // EDUCATION (10+)
        // ═══════════════════════════════════════════
        SiteConfig("Coursera", "https://www.coursera.org/user/{}", SiteCategory.EDUCATION),
        SiteConfig("Khan Academy", "https://www.khanacademy.org/profile/{}", SiteCategory.EDUCATION),
        SiteConfig("Duolingo", "https://www.duolingo.com/profile/{}", SiteCategory.EDUCATION),
        SiteConfig("Memrise", "https://www.memrise.com/user/{}", SiteCategory.EDUCATION),
        SiteConfig("Codecademy", "https://www.codecademy.com/profiles/{}", SiteCategory.EDUCATION),
        SiteConfig("FreeCodeCamp", "https://www.freecodecamp.org/{}", SiteCategory.EDUCATION),
        SiteConfig("Brilliant", "https://brilliant.org/profile/{}", SiteCategory.EDUCATION),
        SiteConfig("Skillshare", "https://www.skillshare.com/profile/{}", SiteCategory.EDUCATION),
        SiteConfig("edX", "https://profile.edx.org/{}", SiteCategory.EDUCATION),
        SiteConfig("ResearchGate", "https://www.researchgate.net/profile/{}", SiteCategory.EDUCATION),
        SiteConfig("Academia.edu", "https://independent.academia.edu/{}", SiteCategory.EDUCATION),
        SiteConfig("Google Scholar", "https://scholar.google.com/citations?user={}", SiteCategory.EDUCATION),

        // ═══════════════════════════════════════════
        // SHOPPING & MARKETPLACE (10+)
        // ═══════════════════════════════════════════
        SiteConfig("eBay", "https://www.ebay.com/usr/{}", SiteCategory.SHOPPING),
        SiteConfig("Amazon Wishlist", "https://www.amazon.com/hz/wishlist/ls/{}", SiteCategory.SHOPPING),
        SiteConfig("Poshmark", "https://poshmark.com/closet/{}", SiteCategory.SHOPPING),
        SiteConfig("Depop", "https://www.depop.com/{}", SiteCategory.SHOPPING),
        SiteConfig("Mercari", "https://www.mercari.com/u/{}", SiteCategory.SHOPPING),
        SiteConfig("Vinted", "https://www.vinted.com/member/{}", SiteCategory.SHOPPING),
        SiteConfig("Redbubble", "https://www.redbubble.com/people/{}", SiteCategory.SHOPPING),
        SiteConfig("Society6", "https://society6.com/{}", SiteCategory.SHOPPING),
        SiteConfig("Teespring", "https://teespring.com/stores/{}", SiteCategory.SHOPPING),
        SiteConfig("Storenvy", "https://{}.storenvy.com", SiteCategory.SHOPPING),
        SiteConfig("Big Cartel", "https://{}.bigcartel.com", SiteCategory.SHOPPING),

        // ═══════════════════════════════════════════
        // TRAVEL & FITNESS (12+)
        // ═══════════════════════════════════════════
        SiteConfig("Strava", "https://www.strava.com/athletes/{}", SiteCategory.FITNESS),
        SiteConfig("Garmin Connect", "https://connect.garmin.com/modern/profile/{}", SiteCategory.FITNESS),
        SiteConfig("Fitbit", "https://www.fitbit.com/user/{}", SiteCategory.FITNESS),
        SiteConfig("MapMyRun", "https://www.mapmyrun.com/profile/{}", SiteCategory.FITNESS),
        SiteConfig("MyFitnessPal", "https://www.myfitnesspal.com/profile/{}", SiteCategory.FITNESS),
        SiteConfig("Komoot", "https://www.komoot.com/user/{}", SiteCategory.FITNESS),
        SiteConfig("TripAdvisor", "https://www.tripadvisor.com/Profile/{}", SiteCategory.TRAVEL),
        SiteConfig("Couchsurfing", "https://www.couchsurfing.com/people/{}", SiteCategory.TRAVEL),
        SiteConfig("Airbnb", "https://www.airbnb.com/users/show/{}", SiteCategory.TRAVEL),
        SiteConfig("Atlas Obscura", "https://www.atlasobscura.com/users/{}", SiteCategory.TRAVEL),
        SiteConfig("Geocaching", "https://www.geocaching.com/p/default.aspx?u={}", SiteCategory.TRAVEL),
        SiteConfig("Polarsteps", "https://www.polarsteps.com/{}", SiteCategory.TRAVEL),

        // ═══════════════════════════════════════════
        // FOOD (8+)
        // ═══════════════════════════════════════════
        SiteConfig("Yelp", "https://www.yelp.com/user_details?userid={}", SiteCategory.FOOD),
        SiteConfig("Untappd", "https://untappd.com/user/{}", SiteCategory.FOOD),
        SiteConfig("Vivino", "https://www.vivino.com/users/{}", SiteCategory.FOOD),
        SiteConfig("Allrecipes", "https://www.allrecipes.com/cook/{}", SiteCategory.FOOD),
        SiteConfig("Cookpad", "https://cookpad.com/us/users/{}", SiteCategory.FOOD),
        SiteConfig("Food52", "https://food52.com/users/{}", SiteCategory.FOOD),
        SiteConfig("Yummly", "https://www.yummly.com/profile/{}", SiteCategory.FOOD),
        SiteConfig("HappyCow", "https://www.happycow.net/members/profile/{}", SiteCategory.FOOD),

        // ═══════════════════════════════════════════
        // DATING (8+)
        // ═══════════════════════════════════════════
        SiteConfig("OkCupid", "https://www.okcupid.com/profile/{}", SiteCategory.DATING),
        SiteConfig("Plenty of Fish", "https://www.pof.com/viewprofile.aspx?profile_id={}", SiteCategory.DATING),
        SiteConfig("Match.com", "https://www.match.com/profile/{}", SiteCategory.DATING),
        SiteConfig("Bumble", "https://bumble.com/app/profile/{}", SiteCategory.DATING),
        SiteConfig("Hinge", "https://hinge.co/profile/{}", SiteCategory.DATING),
        SiteConfig("Coffee Meets Bagel", "https://coffeemeetsbagel.com/{}", SiteCategory.DATING),
        SiteConfig("Happn", "https://www.happn.com/en/profile/{}", SiteCategory.DATING),
        SiteConfig("Zoosk", "https://www.zoosk.com/personals/{}", SiteCategory.DATING),

        // ═══════════════════════════════════════════
        // OTHER (20+)
        // ═══════════════════════════════════════════
        SiteConfig("MyAnimeList", "https://myanimelist.net/profile/{}", SiteCategory.OTHER),
        SiteConfig("AniList", "https://anilist.co/user/{}", SiteCategory.OTHER),
        SiteConfig("Kitsu", "https://kitsu.io/users/{}", SiteCategory.OTHER),
        SiteConfig("Trello", "https://trello.com/{}", SiteCategory.OTHER),
        SiteConfig("Slack", "https://{}.slack.com", SiteCategory.OTHER),
        SiteConfig("IFTTT", "https://ifttt.com/p/{}", SiteCategory.OTHER),
        SiteConfig("Linktree", "https://linktr.ee/{}", SiteCategory.OTHER),
        SiteConfig("Bio.link", "https://bio.link/{}", SiteCategory.OTHER),
        SiteConfig("Beacons", "https://beacons.ai/{}", SiteCategory.OTHER),
        SiteConfig("Taplink", "https://taplink.cc/{}", SiteCategory.OTHER),
        SiteConfig("AllMyLinks", "https://allmylinks.com/{}", SiteCategory.OTHER),
        SiteConfig("Campsite", "https://campsite.bio/{}", SiteCategory.OTHER),
        SiteConfig("Lnk.Bio", "https://lnk.bio/{}", SiteCategory.OTHER),
        SiteConfig("Solo.to", "https://solo.to/{}", SiteCategory.OTHER),
        SiteConfig("WithMe", "https://withme.so/{}", SiteCategory.OTHER),
        SiteConfig("Throne", "https://throne.com/{}", SiteCategory.OTHER),
        SiteConfig("Wishlist", "https://www.wishlist.com/{}", SiteCategory.OTHER),
        SiteConfig("Cash App", "https://cash.app/${'$'}{}", SiteCategory.OTHER),
        SiteConfig("Venmo", "https://venmo.com/{}", SiteCategory.OTHER),
        SiteConfig("PayPal.me", "https://paypal.me/{}", SiteCategory.OTHER),
    )

    val emailSites: List<SiteConfig> = listOf(
        SiteConfig("Gravatar", "https://en.gravatar.com/{}", SiteCategory.BUSINESS),
        SiteConfig("GitHub (email)", "https://api.github.com/search/users?q={}+in:email", SiteCategory.TECH),
        SiteConfig("Keybase", "https://keybase.io/_/api/1.0/user/lookup.json?email={}", SiteCategory.TECH),
        SiteConfig("Spotify", "https://open.spotify.com/user/{}", SiteCategory.MUSIC),
        SiteConfig("Duolingo", "https://www.duolingo.com/2017-06-30/users?email={}", SiteCategory.EDUCATION),
        SiteConfig("Pinterest", "https://www.pinterest.com/{}/_saved/", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Flickr", "https://www.flickr.com/search/people/?q={}", SiteCategory.PHOTO_VIDEO),
        SiteConfig("WordPress", "https://public-api.wordpress.com/rest/v1.1/users/{}/profile", SiteCategory.NEWS),
    )

    val phoneSites: List<SiteConfig> = listOf(
        SiteConfig("WhatsApp", "https://wa.me/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Telegram", "https://t.me/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Viber", "https://viber.com/{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Truecaller", "https://www.truecaller.com/search/il/{}", SiteCategory.OTHER),
        SiteConfig("Sync.me", "https://sync.me/search/?number={}", SiteCategory.OTHER),
        SiteConfig("CallerID", "https://www.calleridtest.com/look-up-phone-number/{}", SiteCategory.OTHER),
    )

    fun generateVariations(username: String): List<String> {
        val variations = mutableSetOf(username)
        variations.add(username.lowercase())
        variations.add(username.uppercase())
        variations.add("${username}1")
        variations.add("${username}123")
        variations.add("${username}_")
        variations.add("_${username}")
        variations.add("_${username}_")
        variations.add("${username}.")
        variations.add(".${username}")
        variations.add("the${username}")
        variations.add("real${username}")
        variations.add("${username}official")
        variations.add("official${username}")
        variations.add("its${username}")
        variations.add("im${username}")
        variations.add("${username}real")
        if (username.contains(".")) {
            variations.add(username.replace(".", ""))
            variations.add(username.replace(".", "_"))
        }
        if (username.contains("_")) {
            variations.add(username.replace("_", ""))
            variations.add(username.replace("_", "."))
        }
        return variations.toList()
    }

    private val leetMap = mapOf('a' to '4', 'e' to '3', 'i' to '1', 'o' to '0', 's' to '5', 't' to '7', 'g' to '9', 'b' to '8')

    fun generateSmartVariations(username: String): List<String> {
        val base = username.trim()
        val variations = mutableSetOf<String>()
        variations.addAll(generateVariations(base))

        // Leetspeak substitution (full + single-letter swaps)
        val fullLeet = base.lowercase().map { leetMap[it] ?: it }.joinToString("")
        variations.add(fullLeet)
        base.lowercase().forEachIndexed { index, c ->
            val sub = leetMap[c]
            if (sub != null) {
                variations.add(base.lowercase().substring(0, index) + sub + base.lowercase().substring(index + 1))
            }
        }

        // Common separators
        listOf("-", ".", "_", "").forEach { sep ->
            variations.add("${base}${sep}gaming")
            variations.add("${base}${sep}official")
            variations.add("${base}${sep}il")
        }

        // Birth-year style suffixes (common ranges)
        (1985..2010 step 5).forEach { year -> variations.add("${base}$year") }
        (0..99 step 11).forEach { yy -> variations.add("${base}${yy.toString().padStart(2, '0')}") }

        // Country / locale tags
        listOf("il", "isr", "tlv", "official", "real", "1", "2", "x", "xo", "yt").forEach { suffix ->
            variations.add("$base$suffix")
            variations.add("${base}_$suffix")
        }

        // Reversed and doubled forms
        variations.add(base.reversed().lowercase())
        variations.add("$base$base".lowercase())

        return variations.filter { it.isNotBlank() }.distinct()
    }

    fun buildNameCityDorks(name: String, city: String): List<Pair<String, String>> {
        return listOf(
            "פרופילים ברשתות חברתיות" to "\"$name\" \"$city\" site:facebook.com OR site:linkedin.com OR site:instagram.com",
            "מאגרי טלפונים ישראליים" to "\"$name\" \"$city\" site:d.co.il OR site:b144.co.il OR site:144.co.il",
            "חדשות מקומיות" to "\"$name\" \"$city\" site:ynet.co.il OR site:walla.co.il OR site:mako.co.il",
            "פורומים ורשת" to "\"$name\" \"$city\" forum OR פורום",
            "מסמכים ציבוריים" to "\"$name\" \"$city\" filetype:pdf",
        )
    }

    fun buildAddressDorks(address: String): List<Pair<String, String>> {
        return listOf(
            "נדל\"ן ורישומי טאבו" to "\"$address\" site:nadlan.gov.il OR site:gov.il",
            "מודעות נדל\"ן" to "\"$address\" site:yad2.co.il OR site:homeless.co.il OR site:madlan.co.il",
            "חיפוש כללי בכתובת" to "\"$address\"",
            "עסקים בכתובת" to "\"$address\" site:google.com/maps OR עסק",
        )
    }

    fun buildWorkplaceDorks(name: String, company: String): List<Pair<String, String>> {
        return listOf(
            "LinkedIn" to "\"$name\" \"$company\" site:linkedin.com",
            "אתר החברה" to "\"$name\" \"$company\"",
            "חדשות ועדכוני חברה" to "\"$name\" \"$company\" site:globes.co.il OR site:calcalist.co.il OR site:themarker.com",
            "פרופיל מקצועי" to "\"$name\" \"$company\" resume OR CV OR קורות חיים",
        )
    }

    fun buildGoogleDorks(query: String): List<Pair<String, String>> {
        return listOf(
            "שם מלא" to "\"${query}\"",
            "פרופילים חברתיים" to "\"${query}\" site:instagram.com OR site:facebook.com OR site:twitter.com OR site:linkedin.com",
            "תמונות" to "\"${query}\" site:instagram.com OR site:flickr.com OR site:500px.com",
            "מסמכים" to "\"${query}\" filetype:pdf OR filetype:doc OR filetype:xlsx",
            "אימייל" to "\"${query}\" \"@gmail.com\" OR \"@yahoo.com\" OR \"@hotmail.com\"",
            "מספר טלפון" to "\"${query}\" \"05\" OR \"+972\"",
            "פורומים" to "\"${query}\" site:reddit.com OR site:quora.com OR site:forum",
            "קורות חיים" to "\"${query}\" resume OR CV filetype:pdf",
            "YouTube" to "\"${query}\" site:youtube.com",
            "חדשות" to "\"${query}\" site:news.google.com OR site:ynet.co.il OR site:walla.co.il",
        )
    }
}
