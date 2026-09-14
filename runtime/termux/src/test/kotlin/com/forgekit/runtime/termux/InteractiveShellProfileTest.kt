package com.forgekit.runtime.termux

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InteractiveShellProfileTest {

    private val dir = createTempDirectory("shell-profile")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `profile sets the colored prompt and aliases and sources the user bashrc last`() {
        val text = InteractiveShellProfile.render()
        assertTrue("PS1='\\[\\e[38;2;255;107;44m\\]\\w" in text, "ember working directory")
        assertTrue("alias ls='ls --color=auto'" in text)
        assertTrue("alias grep='grep --color=auto'" in text)
        val userRc = text.indexOf("\$HOME/.bashrc")
        assertTrue(userRc > text.indexOf("alias diff"), "~/.bashrc must be sourced after ForgeKit defaults")
        assertEquals("truecolor", InteractiveShellProfile.environment["COLORTERM"])
    }

    @Test
    fun `install writes once and repairs an edited file`() {
        val file = InteractiveShellProfile.install(dir.resolve("shell"))
        assertEquals(InteractiveShellProfile.render(), file.toFile().readText())
        val stamp = Files.getLastModifiedTime(file)
        InteractiveShellProfile.install(dir.resolve("shell"))
        assertEquals(stamp, Files.getLastModifiedTime(file), "unchanged content is not rewritten")
        file.toFile().writeText("PS1='x'")
        InteractiveShellProfile.install(dir.resolve("shell"))
        assertEquals(InteractiveShellProfile.render(), file.toFile().readText())
    }
}
