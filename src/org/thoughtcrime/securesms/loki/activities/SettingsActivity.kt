package org.thoughtcrime.securesms.loki.activities

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_settings.*
import network.loki.messenger.BuildConfig
import network.loki.messenger.R
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.all
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.ui.alwaysUi
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.avatar.AvatarSelection
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil
import org.thoughtcrime.securesms.database.Address
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.loki.dialogs.ClearAllDataDialog
import org.thoughtcrime.securesms.loki.dialogs.SeedDialog
import org.thoughtcrime.securesms.loki.utilities.fadeIn
import org.thoughtcrime.securesms.loki.utilities.fadeOut
import org.thoughtcrime.securesms.loki.utilities.push
import org.thoughtcrime.securesms.loki.utilities.toPx
import org.thoughtcrime.securesms.mms.GlideApp
import org.thoughtcrime.securesms.mms.GlideRequests
import org.thoughtcrime.securesms.profiles.AvatarHelper
import org.thoughtcrime.securesms.profiles.ProfileMediaConstraints
import org.thoughtcrime.securesms.util.BitmapDecodingException
import org.thoughtcrime.securesms.util.BitmapUtil
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.signalservice.api.crypto.ProfileCipher
import org.whispersystems.signalservice.api.util.StreamDetails
import org.whispersystems.signalservice.loki.api.fileserver.FileServerAPI
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom
import java.util.*

class SettingsActivity : PassphraseRequiredActionBarActivity() {
    private lateinit var glide: GlideRequests
    private var isEditingDisplayName = false
        set(value) { field = value; handleIsEditingDisplayNameChanged() }
    private var displayNameToBeUploaded: String? = null
    private var profilePictureToBeUploaded: ByteArray? = null
    private var tempFile: File? = null

    private val hexEncodedPublicKey: String
        get() {
            val masterHexEncodedPublicKey = TextSecurePreferences.getMasterHexEncodedPublicKey(this)
            val userHexEncodedPublicKey = TextSecurePreferences.getLocalNumber(this)
            return masterHexEncodedPublicKey ?: userHexEncodedPublicKey
        }

    // region Lifecycle
    override fun onCreate(savedInstanceState: Bundle?, isReady: Boolean) {
        super.onCreate(savedInstanceState, isReady)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(toolbar)
        cancelButton.setOnClickListener { cancelEditingDisplayName() }
        saveButton.setOnClickListener { saveDisplayName() }
        showQRCodeButton.setOnClickListener { showQRCode() }
        glide = GlideApp.with(this)
        profilePictureView.glide = glide
        profilePictureView.publicKey = hexEncodedPublicKey
        profilePictureView.isLarge = true
        profilePictureView.update()
        profilePictureView.setOnClickListener { showEditProfilePictureUI() }
        ctnGroupNameSection.setOnClickListener { showEditDisplayNameUI() }
        btnGroupNameDisplay.text = DatabaseFactory.getLokiUserDatabase(this).getDisplayName(hexEncodedPublicKey)
        publicKeyTextView.text = hexEncodedPublicKey
        copyButton.setOnClickListener { copyPublicKey() }
        shareButton.setOnClickListener { sharePublicKey() }
        val isMasterDevice = (TextSecurePreferences.getMasterHexEncodedPublicKey(this) == null)
        linkedDevicesButtonTopSeparator.visibility = View.GONE
        linkedDevicesButton.visibility = View.GONE
        if (!isMasterDevice) {
            seedButtonTopSeparator.visibility = View.GONE
            seedButton.visibility = View.GONE
        }
        privacyButton.setOnClickListener { showPrivacySettings() }
        notificationsButton.setOnClickListener { showNotificationSettings() }
        chatsButton.setOnClickListener { showChatSettings() }
//        linkedDevicesButton.setOnClickListener { showLinkedDevices() }
        seedButton.setOnClickListener { showSeed() }
        clearAllDataButton.setOnClickListener { clearAllData() }
        versionTextView.text = String.format(getString(R.string.version_s), BuildConfig.VERSION_NAME)
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            AvatarSelection.REQUEST_CODE_AVATAR -> {
                if (resultCode != Activity.RESULT_OK) { return }
                val outputFile = Uri.fromFile(File(cacheDir, "cropped"))
                var inputFile: Uri? = data?.data
                if (inputFile == null && tempFile != null) {
                    inputFile = Uri.fromFile(tempFile)
                }
                AvatarSelection.circularCropImage(this, inputFile, outputFile, R.string.CropImageActivity_profile_avatar)
            }
            AvatarSelection.REQUEST_CODE_CROP_IMAGE -> {
                if (resultCode != Activity.RESULT_OK) { return }
                AsyncTask.execute {
                    try {
                        profilePictureToBeUploaded = BitmapUtil.createScaledBytes(this@SettingsActivity, AvatarSelection.getResultUri(data), ProfileMediaConstraints()).bitmap
                        Handler(Looper.getMainLooper()).post {
                            updateProfile(true)
                        }
                    } catch (e: BitmapDecodingException) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
    // endregion

    // region Updating
    private fun handleIsEditingDisplayNameChanged() {
        cancelButton.visibility = if (isEditingDisplayName) View.VISIBLE else View.GONE
        showQRCodeButton.visibility = if (isEditingDisplayName) View.GONE else View.VISIBLE
        saveButton.visibility = if (isEditingDisplayName) View.VISIBLE else View.GONE
        btnGroupNameDisplay.visibility = if (isEditingDisplayName) View.INVISIBLE else View.VISIBLE
        displayNameEditText.visibility = if (isEditingDisplayName) View.VISIBLE else View.INVISIBLE
        val titleTextViewLayoutParams = titleTextView.layoutParams as LinearLayout.LayoutParams
        titleTextViewLayoutParams.leftMargin = if (isEditingDisplayName) toPx(16, resources) else 0
        titleTextView.layoutParams = titleTextViewLayoutParams
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (isEditingDisplayName) {
            displayNameEditText.requestFocus()
            inputMethodManager.showSoftInput(displayNameEditText, 0)
        } else {
            inputMethodManager.hideSoftInputFromWindow(displayNameEditText.windowToken, 0)
        }
    }

    private fun updateProfile(isUpdatingProfilePicture: Boolean) {
        loader.fadeIn()
        val promises = mutableListOf<Promise<*, Exception>>()
        val displayName = displayNameToBeUploaded
        if (displayName != null) {
            val publicChatAPI = ApplicationContext.getInstance(this).publicChatAPI
            if (publicChatAPI != null) {
                val servers = DatabaseFactory.getLokiThreadDatabase(this).getAllPublicChatServers()
                promises.addAll(servers.map { publicChatAPI.setDisplayName(displayName, it) })
            }
            TextSecurePreferences.setProfileName(this, displayName)
        }
        val profilePicture = profilePictureToBeUploaded
        val encodedProfileKey = ProfileKeyUtil.generateEncodedProfileKey(this)
        val profileKey = ProfileKeyUtil.getProfileKeyFromEncodedString(encodedProfileKey)
        if (isUpdatingProfilePicture && profilePicture != null) {
            val storageAPI = FileServerAPI.shared
            val deferred = deferred<Unit, Exception>()
            AsyncTask.execute {
                val stream = StreamDetails(ByteArrayInputStream(profilePicture), "image/jpeg", profilePicture.size.toLong())
                val (_, url) = storageAPI.uploadProfilePicture(storageAPI.server, profileKey, stream) {
                    TextSecurePreferences.setLastProfilePictureUpload(this@SettingsActivity, Date().time)
                }
                TextSecurePreferences.setProfilePictureURL(this, url)
                deferred.resolve(Unit)
            }
            promises.add(deferred.promise)
        }
        all(promises).alwaysUi {
            if (displayName != null) {
                btnGroupNameDisplay.text = displayName
            }
            displayNameToBeUploaded = null
            if (isUpdatingProfilePicture && profilePicture != null) {
                AvatarHelper.setAvatar(this, Address.fromSerialized(TextSecurePreferences.getLocalNumber(this)), profilePicture)
                TextSecurePreferences.setProfileAvatarId(this, SecureRandom().nextInt())
                ProfileKeyUtil.setEncodedProfileKey(this, encodedProfileKey)
                ApplicationContext.getInstance(this).updateOpenGroupProfilePicturesIfNeeded()
                profilePictureView.update()
            }
            profilePictureToBeUploaded = null
            loader.fadeOut()
        }
    }
    // endregion

    // region Interaction
    private fun cancelEditingDisplayName() {
        isEditingDisplayName = false
    }

    private fun saveDisplayName() {
        val displayName = displayNameEditText.text.toString().trim()
        if (displayName.isEmpty()) {
            return Toast.makeText(this, R.string.activity_settings_display_name_missing_error, Toast.LENGTH_SHORT).show()
        }
        if (displayName.toByteArray().size > ProfileCipher.NAME_PADDED_LENGTH) {
            return Toast.makeText(this, R.string.activity_settings_display_name_too_long_error, Toast.LENGTH_SHORT).show()
        }
        isEditingDisplayName = false
        displayNameToBeUploaded = displayName
        updateProfile(false)
    }

    private fun showQRCode() {
        val intent = Intent(this, QRCodeActivity::class.java)
        push(intent)
    }

    private fun showEditProfilePictureUI() {
        tempFile = AvatarSelection.startAvatarSelection(this, false, true)
    }

    private fun showEditDisplayNameUI() {
        isEditingDisplayName = true
    }

    private fun copyPublicKey() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Session ID", hexEncodedPublicKey)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun sharePublicKey() {
        val intent = Intent()
        intent.action = Intent.ACTION_SEND
        intent.putExtra(Intent.EXTRA_TEXT, hexEncodedPublicKey)
        intent.type = "text/plain"
        startActivity(intent)
    }

    private fun showPrivacySettings() {
        val intent = Intent(this, PrivacySettingsActivity::class.java)
        push(intent)
    }

    private fun showNotificationSettings() {
        val intent = Intent(this, NotificationSettingsActivity::class.java)
        push(intent)
    }

    private fun showChatSettings() {
        val intent = Intent(this, ChatSettingsActivity::class.java)
        push(intent)
    }

    private fun showLinkedDevices() {
        val intent = Intent(this, LinkedDevicesActivity::class.java)
        push(intent)
    }

    private fun showSeed() {
        SeedDialog().show(supportFragmentManager, "Recovery Phrase Dialog")
    }

    private fun clearAllData() {
        ClearAllDataDialog().show(supportFragmentManager, "Clear All Data Dialog")
    }
    // endregion
}