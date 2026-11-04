package app.gamenative.addons

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupTest {
    private fun bold(text: String, markdown: Boolean) =
        Markup.render(text, markdown).let { r -> r.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }.map { r.text.substring(it.start, it.end) } }

    @Test
    fun bbcode_keepsTextAndBoldsAndBullets() {
        val r = Markup.render("[SIZE=\"4\"][B][U]CTMod[/U][/B][/SIZE]\r\nIt does [b]things[/b].\r\n[LIST]\r\n[*]One\r\n[*]Two\r\n[/LIST]", false)
        assertEquals("CTMod\nIt does things.\n\n• One\n\n• Two", r.text.replace("\n\n\n", "\n\n").let { it })
    }

    @Test
    fun bbcode_boldSpansCoverTheRightText() {
        assertEquals(listOf("things"), bold("It does [B]things[/B] well", false))
    }

    @Test
    fun bbcode_dropsImagesKeepsLinkText_andLeavesUnknownBracketsAlone() {
        val r = Markup.render("See [URL=https://x.test]the site[/URL] [IMG]https://x.test/a.png[/IMG] and [Warning] [1] ok", false)
        assertEquals("See the site  and [Warning] [1] ok", r.text)
    }

    @Test
    fun markdown_headingsBulletsAndInline() {
        val r = Markup.render("### Version 16.00\n* **Big** change with `code`\n    * nested [link](https://x.test) here\n\n\n\nend", true)
        assertEquals("Version 16.00\n• Big change with code\n  • nested link here\n\nend", r.text)
        assertEquals(listOf("Version 16.00", "Big"), bold("### Version 16.00\n* **Big** change", true).let { it })
    }

    @Test
    fun markdown_blankLineAfterAHeadingDoesNotDoubleTheGap() {
        assertEquals("About\nElvUI rocks", Markup.render("## About\n\nElvUI rocks", true).text)
        assertEquals("About\n\nElvUI rocks", Markup.render("## About\n\n\nElvUI rocks", true).text)
    }

    @Test
    fun markdown_stripsImagesAndHtml_andRendersFencedCodeAsIs() {
        val r = Markup.render("Hi ![shot](https://x.test/s.png) <b>there</b>\n```\nlet x = 1\n```", true)
        assertEquals("Hi  there\nlet x = 1", r.text)
    }

    @Test
    fun extractImageUrls_findsBbcodeMarkdownAndHtml_httpsOnly() {
        val raw = "[IMG]https://a.test/1.png[/IMG] ![x](https://b.test/2.png) <img src=\"https://c.test/3.jpg\"> ![n](http://insecure.test/4.png) [img]https://a.test/1.png[/img]"
        assertEquals(listOf("https://a.test/1.png", "https://b.test/2.png", "https://c.test/3.jpg"), Markup.extractImageUrls(raw))
    }

    @Test
    fun emptyInput_rendersEmpty() {
        assertTrue(Markup.render("", true).text.isEmpty())
        assertTrue(Markup.render("   \n\n", false).text.isEmpty())
    }

    @Test
    fun markdown_linkedBadgeImages_keepTheirAltText_notRawSyntax() {
        // ElvUI's real description ends with these; they used to render as "![Support](https://...)".
        val raw = "## Links\n\n[![Support](https://img.shields.io/badge/Support-x?style=flat)](https://tukui.org/support)\n[![Discord](https://img.shields.io/discord/1?label=Discord)](https://discord.tukui.org)"
        assertEquals("Links\nSupport\nDiscord", Markup.render(raw, true).text)
    }

    @Test
    fun extractImageUrls_skipsBadgesAndSvgs_butKeepsRealScreenshots() {
        val raw = "[![Support](https://img.shields.io/badge/Support-x)](https://tukui.org/support) ![shot](https://cdn.test/shot.png) ![logo](https://cdn.test/logo.svg?v=1) ![n](https://badges.test/a.png)"
        assertEquals(listOf("https://cdn.test/shot.png"), Markup.extractImageUrls(raw))
    }
}
