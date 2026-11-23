package net.uncorp.lurkr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SitesTest {
    @Test fun matchesHostsAndSubdomains() {
        assertEquals("tiktok", Sites.match("vm.tiktok.com")?.id)
        assertEquals("tiktok", Sites.match("tiktok.com")?.id)
        assertEquals("facebook", Sites.match("l.facebook.com.")?.id)
        assertEquals("facebook", Sites.match("FB.WATCH")?.id)
        assertEquals("instagram", Sites.match("www.instagram.com")?.id)
        assertEquals("x", Sites.match("mobile.twitter.com")?.id)
        assertEquals("x", Sites.match("x.com")?.id)
        assertEquals("youtube", Sites.match("youtu.be")?.id)
        assertEquals("spotify", Sites.match("open.spotify.com")?.id)
        assertNull(Sites.match("reddit.com"))   // not one of Lurkr's sites
        assertNull(Sites.match("nytimes.com"))
        assertNull(Sites.match("notfacebook.com"))   // "*.facebook.com" needs the dot
        assertNull(Sites.match(null as String?))
    }

    @Test fun idsAndHostsAreUnique() {
        assertEquals(Sites.ALL.size, Sites.ALL.map { it.id }.toSet().size)
        assertEquals(Sites.ALL_HOSTS.size, Sites.ALL_HOSTS.toSet().size)
    }

    @Test fun sitesAreAlphabetical() {
        val names = Sites.ALL.map { it.name.lowercase() }
        assertEquals(names.sorted(), names)
    }

    @Test fun baseDomains() {
        assertEquals("nytimes.com", Links.baseDomain("www.nytimes.com"))
        assertEquals("bbc.co.uk", Links.baseDomain("news.bbc.co.uk"))
        assertEquals("publico.pt", Links.baseDomain("www.publico.pt"))
        assertEquals("example.com", Links.baseDomain("example.com"))
    }

    /** The generated list matches the folders in sites/ (the build generates it from them). */
    @Test fun sitesComeFromTheFolders() {
        val folders = File("../sites").listFiles { f -> f.isDirectory }!!.map { it.name }.toSet()
        assertEquals(folders, Sites.ALL.map { it.id }.toSet())
        Sites.ALL.forEach { assert(File("../sites/${it.id}/logo.svg").exists()) { "no logo for ${it.id}" } }
    }

    /** The link rules, from the real site.json files. */
    @Test fun rewrites() {
        fun site(id: String) = Sites.ALL.first { it.id == id }
        val ig = site("instagram")
        assertEquals("https://www.instagram.com/reel/abc/?l=1", Rewrites.rewrite(ig, "https://www.instagram.com/reel/abc/"))
        assertEquals("https://www.instagram.com/p/abc/?igsh=x&l=1", Rewrites.rewrite(ig, "https://www.instagram.com/p/abc/?igsh=x"))
        assertEquals("https://www.instagram.com/p/abc/?l=1", Rewrites.rewrite(ig, "https://www.instagram.com/p/abc/?l=1"))
        assertEquals("https://www.instagram.com/instagram/", Rewrites.rewrite(ig, "https://www.instagram.com/instagram/"))

        val tt = site("tiktok")
        val player = Rewrites.rewrite(tt, "https://www.tiktok.com/@scout2015/video/6718335390845095173?lang=en")
        assert(player.startsWith("https://www.tiktok.com/player/v1/6718335390845095173?")) { player }
        assert(Rewrites.needsResolve(tt, "https://vm.tiktok.com/ZMabc/"))
        assert(Rewrites.needsResolve(tt, "https://www.tiktok.com/t/ZTabc/"))
        assert(!Rewrites.needsResolve(tt, "https://www.tiktok.com/@scout2015"))

        val fb = site("facebook")
        assertEquals("https://example.com/a?b=1", Rewrites.unwrap(fb, "https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1&h=x"))
        assertEquals("https://l.facebook.com/l.php?u=javascript:x", Rewrites.unwrap(fb, "https://l.facebook.com/l.php?u=javascript:x"))
        assertEquals("https://www.facebook.com/x", Rewrites.unwrap(fb, "https://www.facebook.com/x"))
    }

    @Test fun firstUrlInSharedText() {
        assertEquals("https://www.instagram.com/p/abc/", Links.firstUrl("Look! https://www.instagram.com/p/abc/."))
        assertEquals("https://vm.tiktok.com/ZM123/", Links.firstUrl("https://vm.tiktok.com/ZM123/"))
        assertNull(Links.firstUrl("no link here"))
    }

    @Test fun chromeUserAgent() {
        val wv = "Mozilla/5.0 (Linux; Android 16; Pixel 10 Pro Fold Build/BD3A; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/142.0.7444.12 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36",
            Web.chromeUserAgent(wv),
        )
    }

    @Test fun recentsRoundTrip() {
        val list = listOf(Lurk("https://www.instagram.com/reel/abc/", 2000L), Lurk("https://vm.tiktok.com/ZM1/", 1000L))
        assertEquals(list, Recents.decode(Recents.encode(list)))
        assertEquals(emptyList<Lurk>(), Recents.decode(null))
        assertEquals(emptyList<Lurk>(), Recents.decode("garbage"))
    }

    @Test fun pickPackages() {
        assertEquals("org.example.app", Apps.pkgOf("org.example.app"))
        assertEquals("org.chromium.webapk.a1", Apps.pkgOf("share:org.chromium.webapk.a1/org.chromium.webapk.shell_apk.ShareActivity0"))
    }

    @Test fun postsAreRecognised() {
        assertEquals("Post", Links.postKind("x", "/NASA/status/2123435938848067841"))
        assertNull(Links.postKind("x", "/NASA"))
        assertEquals("Reel", Links.postKind("instagram", "/reel/Df8YsMzBulc/"))
        assertNull(Links.postKind("instagram", "/instagram/"))
        assertEquals("Video", Links.postKind("tiktok", "/@scout2015/video/6718335390845095173"))
        assertEquals("Video", Links.postKind("facebook", "/reel/1115627211085350/"))
        assertNull(Links.postKind("facebook", "/NASA/"))
        assertEquals("Video", Links.postKind("youtube", "/watch?v=K8DG7LUTbaM"))
        assertNull(Links.postKind("youtube", "/watch"))
        assertEquals("Short", Links.postKind("youtube", "/shorts/_hFKxXG_SrY"))
        assertEquals("Video", Links.postKind("youtube", "/K8DG7LUTbaM"))  // youtu.be
        assertEquals("Track", Links.postKind("spotify", "/track/4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals("Page", Links.postKind(null, "/2026/11/an-article"))
        assertNull(Links.postKind(null, "/"))
    }

    @Test fun adsAreRecognised() {
        assertTrue(Links.isAd("googleads.g.doubleclick.net", "/pagead/id"))
        assertTrue(Links.isAd("www.youtube.com", "/pagead/adview"))
        assertTrue(Links.isAd("m.youtube.com", "/api/stats/ads"))
        assertFalse(Links.isAd("m.youtube.com", "/watch"))
        assertFalse(Links.isAd("m.youtube.com", "/youtubei/v1/player"))
        assertFalse(Links.isAd("example.com", "/pagead/x"))
    }

    @Test fun trackingTagsComeOff() {
        assertEquals("https://www.instagram.com/reel/abc/", Links.clean("https://www.instagram.com/reel/abc/?igsh=MTc4MmM1YmI2Ng=="))
        assertEquals("https://www.instagram.com/reel/abc/", Links.clean("https://www.instagram.com/reel/abc/?fqnt=MXdq&igsh=MXdq"))
        assertEquals("https://x.com/jack/status/20", Links.clean("https://x.com/jack/status/20?s=46&t=AbCdEf"))
        assertEquals("https://www.tiktok.com/@a/video/1", Links.clean("https://www.tiktok.com/@a/video/1?_t=8x&_r=1&is_from_webapp=1&sender_device=pc"))
        assertEquals("https://www.facebook.com/x/posts/1?id=2", Links.clean("https://www.facebook.com/x/posts/1?id=2&fbclid=IwAR0&mibextid=abc"))
        assertEquals("https://news.example/a?page=2", Links.clean("https://news.example/a?utm_source=x&page=2&utm_medium=social"))
        assertEquals("https://example.com/search?s=shoes", Links.clean("https://example.com/search?s=shoes"))  // "s" is X's only
        assertEquals("https://example.com/a", Links.clean("https://example.com/a"))
    }

    @Test fun loginPagesAreRecognised() {
        listOf("/login", "/login.php", "/accounts/login/", "/i/flow/login", "/r.php", "/signup", "/accounts/emailsignup/").forEach {
            assert(Links.isLogin(it)) { it }
        }
        listOf("/loginhelp_tips", "/zuck/posts/1", "/reel/123/", "/p/abc/", "/i/status/1").forEach { assert(!Links.isLogin(it)) { it } }
    }
}
