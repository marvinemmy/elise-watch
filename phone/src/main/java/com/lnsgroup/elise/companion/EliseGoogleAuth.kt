package com.lnsgroup.elise.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope

private const val TAG = "EliseGoogleAuth"

// Scopes Google nécessaires pour Élise
private val GOOGLE_SCOPES = listOf(
    "https://www.googleapis.com/auth/gmail.readonly",
    "https://www.googleapis.com/auth/calendar.readonly",
    "https://www.googleapis.com/auth/drive.readonly",
    "https://www.googleapis.com/auth/contacts.readonly",
    "profile",
    "email",
)

/**
 * Gère l'authentification Google OAuth pour accéder à :
 * Gmail, Google Calendar, Google Drive, Contacts.
 *
 * Usage :
 *   val auth = EliseGoogleAuth(activity)
 *   auth.register()
 *   auth.signIn { account -> ... }
 */
class EliseGoogleAuth(private val activity: AppCompatActivity) {

    private lateinit var googleClient: GoogleSignInClient
    private lateinit var launcher: ActivityResultLauncher<Intent>
    private var onSuccess: ((GoogleSignInAccount) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    fun register() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .apply {
                GOOGLE_SCOPES.filter { it.startsWith("https://") }
                    .forEach { requestScopes(Scope(it)) }
            }
            .build()

        googleClient = GoogleSignIn.getClient(activity, gso)

        launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    .addOnSuccessListener { account ->
                        Log.i(TAG, "Signed in: ${account.email}")
                        saveAccount(account)
                        onSuccess?.invoke(account)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Sign-in failed: ${e.message}")
                        onError?.invoke(e.message ?: "Erreur Google Sign-In")
                    }
            } else {
                onError?.invoke("Connexion annulée")
            }
        }
    }

    fun signIn(onDone: (GoogleSignInAccount) -> Unit, onFail: (String) -> Unit = {}) {
        onSuccess = onDone
        onError = onFail
        // Vérifie si déjà connecté
        val existing = GoogleSignIn.getLastSignedInAccount(activity)
        if (existing != null && !existing.isExpired) {
            Log.i(TAG, "Already signed in: ${existing.email}")
            onDone(existing)
            return
        }
        launcher.launch(googleClient.signInIntent)
    }

    fun signOut(onDone: () -> Unit = {}) {
        googleClient.signOut().addOnCompleteListener { onDone() }
        clearAccount(activity)
    }

    fun getAccount(): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(activity)

    fun isConnected(): Boolean {
        val a = GoogleSignIn.getLastSignedInAccount(activity)
        return a != null && !a.isExpired
    }

    private fun saveAccount(account: GoogleSignInAccount) {
        activity.getSharedPreferences("elise_prefs", Context.MODE_PRIVATE).edit()
            .putString("google_email", account.email)
            .putString("google_name", account.displayName)
            .apply()
    }

    companion object {
        fun clearAccount(ctx: Context) {
            ctx.getSharedPreferences("elise_prefs", Context.MODE_PRIVATE).edit()
                .remove("google_email").remove("google_name").apply()
        }
        fun getSavedEmail(ctx: Context): String? =
            ctx.getSharedPreferences("elise_prefs", Context.MODE_PRIVATE)
                .getString("google_email", null)
    }
}
