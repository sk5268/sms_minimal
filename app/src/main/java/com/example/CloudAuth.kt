package com.example

import android.app.Activity
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cloud sync signs in with the owner's Google account, the same one the web
 * client uses. Firestore rules only admit that verified account, so an
 * anonymous session can no longer read or write anyone's messages.
 */
object CloudAuth {
    private const val TAG = "CloudAuth"

    /** The signed-in Google user, or null when sync must wait for an explicit sign-in. */
    fun signedInUser(): FirebaseUser? {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: return null
        if (user.isAnonymous) {
            // Left over from the old anonymous flow; the rules reject it anyway.
            auth.signOut()
            return null
        }
        return user
    }

    suspend fun signIn(activity: Activity): Result<FirebaseUser> {
        return try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(activity.getString(R.string.default_web_client_id))
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val credential = CredentialManager.create(activity)
                .getCredential(activity, request)
                .credential

            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return Result.failure(IllegalStateException("Unexpected credential type"))
            }

            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)

            val user = withContext(Dispatchers.IO) {
                val auth = FirebaseAuth.getInstance()
                if (auth.currentUser?.isAnonymous == true) auth.signOut()
                Tasks.await(auth.signInWithCredential(firebaseCredential)).user
            } ?: return Result.failure(IllegalStateException("Sign-in returned no user"))

            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Google sign-in for cloud sync failed", e)
            Result.failure(e)
        }
    }
}
