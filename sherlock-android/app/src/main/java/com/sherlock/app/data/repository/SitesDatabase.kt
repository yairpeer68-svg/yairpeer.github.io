package com.sherlock.app.data.repository

import com.sherlock.app.data.model.ErrorType
import com.sherlock.app.data.model.SiteCategory
import com.sherlock.app.data.model.SiteConfig

object SitesDatabase {

    val sites: List<SiteConfig> = listOf(
        // Social Media
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
        SiteConfig("Mastodon (mastodon.social)", "https://mastodon.social/@{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Threads", "https://www.threads.net/@{}", SiteCategory.SOCIAL_MEDIA),
        SiteConfig("Bluesky", "https://bsky.app/profile/{}.bsky.social", SiteCategory.SOCIAL_MEDIA),

        // Photo & Video
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

        // Music
        SiteConfig("Spotify", "https://open.spotify.com/user/{}", SiteCategory.MUSIC),
        SiteConfig("SoundCloud", "https://soundcloud.com/{}", SiteCategory.MUSIC),
        SiteConfig("Bandcamp", "https://{}.bandcamp.com", SiteCategory.MUSIC),
        SiteConfig("Last.fm", "https://www.last.fm/user/{}", SiteCategory.MUSIC),
        SiteConfig("Deezer", "https://www.deezer.com/profile/{}", SiteCategory.MUSIC),

        // Gaming
        SiteConfig("Steam", "https://steamcommunity.com/id/{}", SiteCategory.GAMING),
        SiteConfig("Xbox Gamertag", "https://xboxgamertag.com/search/{}", SiteCategory.GAMING),
        SiteConfig("Chess.com", "https://www.chess.com/member/{}", SiteCategory.GAMING),
        SiteConfig("Lichess", "https://lichess.org/@/{}", SiteCategory.GAMING),
        SiteConfig("Roblox", "https://www.roblox.com/user.aspx?username={}", SiteCategory.GAMING, ErrorType.MESSAGE_IN_PAGE, "Page cannot be found"),
        SiteConfig("Minecraft (NameMC)", "https://namemc.com/profile/{}", SiteCategory.GAMING),
        SiteConfig("Epic Games", "https://fortnitetracker.com/profile/all/{}", SiteCategory.GAMING),

        // Tech
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

        // Forums
        SiteConfig("Quora", "https://www.quora.com/profile/{}", SiteCategory.FORUM),
        SiteConfig("Disqus", "https://disqus.com/by/{}/", SiteCategory.FORUM),
        SiteConfig("Discourse (meta)", "https://meta.discourse.org/u/{}", SiteCategory.FORUM),

        // Business
        SiteConfig("Fiverr", "https://www.fiverr.com/{}", SiteCategory.BUSINESS),
        SiteConfig("Upwork", "https://www.upwork.com/freelancers/~{}", SiteCategory.BUSINESS),
        SiteConfig("Dribbble", "https://dribbble.com/{}", SiteCategory.BUSINESS),
        SiteConfig("Behance", "https://www.behance.net/{}", SiteCategory.BUSINESS),
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

        // Other
        SiteConfig("Telegram", "https://t.me/{}", SiteCategory.OTHER),
        SiteConfig("Keybase", "https://keybase.io/{}", SiteCategory.OTHER),
        SiteConfig("Letterboxd", "https://letterboxd.com/{}", SiteCategory.OTHER),
        SiteConfig("Goodreads", "https://www.goodreads.com/{}", SiteCategory.OTHER),
        SiteConfig("MyAnimeList", "https://myanimelist.net/profile/{}", SiteCategory.OTHER),
        SiteConfig("Trello", "https://trello.com/{}", SiteCategory.OTHER),
        SiteConfig("Slack (Community)", "https://{}.slack.com", SiteCategory.OTHER),
        SiteConfig("WordPress", "https://{}.wordpress.com", SiteCategory.OTHER),
        SiteConfig("Blogger", "https://{}.blogspot.com", SiteCategory.OTHER),
        SiteConfig("Wix", "https://{}.wixsite.com", SiteCategory.OTHER),
        SiteConfig("Notion", "https://notion.so/@{}", SiteCategory.OTHER),
        SiteConfig("Strava", "https://www.strava.com/athletes/{}", SiteCategory.OTHER),
        SiteConfig("Duolingo", "https://www.duolingo.com/profile/{}", SiteCategory.OTHER),
        SiteConfig("Clubhouse", "https://www.clubhouse.com/@{}", SiteCategory.OTHER),
    )
}
