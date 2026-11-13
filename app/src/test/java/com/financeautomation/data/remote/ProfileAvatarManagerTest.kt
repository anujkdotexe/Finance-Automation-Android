package com.financeautomation.data.remote

import android.content.Context
import android.content.ContextWrapper
import com.financeautomation.core.utils.accountInitial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileAvatarManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeContext: Context
    private lateinit var manager: ProfileAvatarManager

    @Before
    fun setUp() {
        val filesDir = tempFolder.newFolder("files")
        fakeContext = object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
        }
        manager = ProfileAvatarManager(fakeContext)
    }

    @Test
    fun urlAllowlist_acceptsValidGoogleUserContentHttpsUrls() {
        assertTrue(ProfileAvatarManager.isValidPhotoUrl("https://lh3.googleusercontent.com/a/ACg8ocI-sample=s96-c"))
        assertTrue(ProfileAvatarManager.isValidPhotoUrl("https://googleusercontent.com/photo.jpg"))
        assertTrue(ProfileAvatarManager.isValidPhotoUrl("https://subdomain.googleusercontent.com/avatar"))
        assertTrue(ProfileAvatarManager.isValidPhotoUrl("https://LH3.GOOGLEUSERCONTENT.COM/a/ABC"))
    }

    @Test
    fun urlAllowlist_rejectsHttpAndLookalikeHosts() {
        assertFalse(ProfileAvatarManager.isValidPhotoUrl("http://lh3.googleusercontent.com/a/sample"))

        assertFalse(ProfileAvatarManager.isValidPhotoUrl("https://evilgoogleusercontent.com/photo.jpg"))
        assertFalse(ProfileAvatarManager.isValidPhotoUrl("https://notgoogleusercontent.com/avatar"))

        assertFalse(ProfileAvatarManager.isValidPhotoUrl("https://googleusercontent.com.evil.com/photo.jpg"))
        assertFalse(ProfileAvatarManager.isValidPhotoUrl("https://googleusercontent.com.attacker.org/a/sample"))

        assertFalse(ProfileAvatarManager.isValidPhotoUrl("https://google.com/avatar.png"))
        assertFalse(ProfileAvatarManager.isValidPhotoUrl("https://drive.google.com/avatar.png"))

        assertFalse(ProfileAvatarManager.isValidPhotoUrl("https://example.com/avatar.png"))
        assertFalse(ProfileAvatarManager.isValidPhotoUrl(""))
        assertFalse(ProfileAvatarManager.isValidPhotoUrl("   "))
        assertFalse(ProfileAvatarManager.isValidPhotoUrl(null))
        assertFalse(ProfileAvatarManager.isValidPhotoUrl("not a url"))
    }

    @Test
    fun formatPhotoUrl_enforcesS128SizeVariant() {
        assertEquals(
            "https://lh3.googleusercontent.com/a/abc=s128-c",
            ProfileAvatarManager.formatPhotoUrl("https://lh3.googleusercontent.com/a/abc=s96-c")
        )
        assertEquals(
            "https://lh3.googleusercontent.com/a/abc=s128-c",
            ProfileAvatarManager.formatPhotoUrl("https://lh3.googleusercontent.com/a/abc")
        )
        assertEquals(
            "https://lh3.googleusercontent.com/a/abc=s128-c",
            ProfileAvatarManager.formatPhotoUrl("https://lh3.googleusercontent.com/a/abc=s256")
        )
    }

    @Test
    fun deleteAvatar_removesFileOnSignOut() {
        val avatarFile = File(fakeContext.filesDir, ProfileAvatarManager.AVATAR_FILE_NAME)
        avatarFile.writeText("fake image content")
        assertTrue(avatarFile.exists())
        assertTrue(manager.hasAvatar())

        val deleted = manager.deleteAvatar()
        assertTrue(deleted)
        assertFalse(avatarFile.exists())
        assertFalse(manager.hasAvatar())

        assertFalse(manager.deleteAvatar())
    }

    @Test
    fun accountInitial_fallbackBehavior() {
        assertEquals("A", accountInitial("alex@gmail.com"))
        assertEquals("U", accountInitial("user@finance.org"))
        assertEquals("K", accountInitial("   k@test.com  "))
        assertEquals("?", accountInitial(null))
        assertEquals("?", accountInitial(""))
        assertEquals("?", accountInitial("   "))
    }
}
