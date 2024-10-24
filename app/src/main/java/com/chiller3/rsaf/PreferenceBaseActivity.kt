/*
 * SPDX-FileCopyrightText: 2023-2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf

import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.chiller3.rsaf.databinding.SettingsActivityBinding

abstract class PreferenceBaseActivity : AppCompatActivity() {
    companion object {
        private const val INACTIVE_TIMEOUT_NS = 60_000_000_000L

        // These are intentionally global to ensure that the prompt does not appear when navigating
        // within the app.
        private var bioAuthenticated = false
        private var lastPause = 0L
    }

    private val tag = javaClass.simpleName

    private lateinit var prefs: Preferences
    private lateinit var bioPrompt: BiometricPrompt
    private lateinit var activityManager: ActivityManager
    private var isCoveredBySafeActivity = false

    protected abstract val actionBarTitle: CharSequence?

    protected abstract val showUpButton: Boolean

    protected abstract fun createFragment(): PreferenceBaseFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val binding = SettingsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val transaction = supportFragmentManager.beginTransaction()

        // https://issuetracker.google.com/issues/181805603
        val bioFragment = supportFragmentManager
            .findFragmentByTag("androidx.biometric.BiometricFragment")
        if (bioFragment != null) {
            transaction.remove(bioFragment)
        }

        val fragment: PreferenceBaseFragment

        if (savedInstanceState == null) {
            fragment = createFragment()
            transaction.replace(R.id.settings, fragment)
        } else {
            fragment = supportFragmentManager.findFragmentById(R.id.settings)
                    as PreferenceBaseFragment
        }

        transaction.commit()

        supportFragmentManager.setFragmentResultListener(fragment.requestTag, this) { _, result ->
            setResult(RESULT_OK, Intent().apply { putExtras(result) })
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )

            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.left
                topMargin = insets.top
                rightMargin = insets.right
            }

            WindowInsetsCompat.CONSUMED
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(showUpButton)

        actionBarTitle?.let {
            setTitle(it)
        }

        prefs = Preferences(this)

        bioPrompt = BiometricPrompt(
            this,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Toast.makeText(
                        this@PreferenceBaseActivity,
                        getString(R.string.biometric_error, errString),
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    bioAuthenticated = true
                    refreshGlobalVisibility()
                }

                override fun onAuthenticationFailed() {
                    Toast.makeText(
                        this@PreferenceBaseActivity,
                        R.string.biometric_failure,
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }
            },
        )

        activityManager = getSystemService(ActivityManager::class.java)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(tag, "onResume()")

        if (bioAuthenticated && (System.nanoTime() - lastPause) >= INACTIVE_TIMEOUT_NS) {
            Log.d(tag, "Biometric authentication timed out due to inactivity")
            bioAuthenticated = false
        }

        if (!bioAuthenticated) {
            if (!prefs.requireAuth) {
                bioAuthenticated = true
            } else {
                startBiometricAuth()
            }
        }

        refreshTaskState()
        refreshGlobalVisibility()
    }

    override fun onPause() {
        super.onPause()
        Log.d(tag, "onPause()")

        lastPause = System.nanoTime()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(tag, "onWindowFocusChanged($hasFocus)")

        refreshTaskState()

        val secure = prefs.requireAuth && !hasFocus && !isCoveredBySafeActivity
        Log.d(tag, "Updating window secure flag: $secure")

        // We only want the top-level activity to handle FLAG_SECURE to avoid flicker in screen
        // recordings and scrcpy.
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // We want the activity to be visible for predictive back gestures as long as the top-level
    // activity in the task is our own.
    private fun canViewAndInteract() = bioAuthenticated || isCoveredBySafeActivity

    override fun onWindowAttributesChanged(params: WindowManager.LayoutParams?) {
        val canInteract = canViewAndInteract()
        Log.d(tag, "Updating focusable/touchable state: $canInteract")

        // This trick is from Signal to drop all input events going to the window.
        val ignoreInput = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        params?.let {
            it.flags = if (canInteract) {
                it.flags and ignoreInput.inv()
            } else {
                it.flags or ignoreInput
            }
        }

        super.onWindowAttributesChanged(params)
    }

    private fun startBiometricAuth() {
        Log.d(tag, "Starting biometric authentication")

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .setTitle(getString(R.string.biometric_title))
            .build()

        bioPrompt.authenticate(promptInfo)
    }

    private fun refreshTaskState() {
        // This is an awful hack, but we need it to be able to only apply the view hiding in the
        // topmost activity to ensure that predictive back gestures still work.
        val taskId = taskId

        val task = activityManager.appTasks.find {
            taskId == if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.taskInfo.taskId
            } else {
                @Suppress("DEPRECATION")
                it.taskInfo.id
            }
        }

        val topActivity = task?.taskInfo?.topActivity

        isCoveredBySafeActivity = topActivity != null
                && topActivity != componentName
                && topActivity.packageName == packageName

        Log.d(tag, "Top-level activity in stack is: $topActivity")
        Log.d(tag, "Covered by safe activity: $isCoveredBySafeActivity")
    }

    private fun refreshGlobalVisibility() {
        window?.let { window ->
            val visible = canViewAndInteract()
            Log.d(tag, "Updating view state: $visible")

            val contentView = window.decorView.findViewById<View>(android.R.id.content)
            contentView.visibility = if (visible) {
                View.VISIBLE
            } else {
                // Using View.GONE causes noticeable scrolling jank due to relayout.
                View.INVISIBLE
            }

            onWindowAttributesChanged(window.attributes)
        }
    }
}
