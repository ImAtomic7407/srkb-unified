package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * "Delete account" is a request, not an instant action: the button opens a web page that says how
 * to ask (email the developer). Google Play requires an in-app way to reach that page.
 */
object AccountDeletion {
    /**
     * The public delete-account page on the website. MUST be set to the real Vercel URL before a
     * Play build is uploaded, e.g. "https://<project>.vercel.app/delete-account". Left empty on
     * purpose rather than guessed; while empty the button falls back to emailing the developer.
     */
    const val DELETE_ACCOUNT_URL = "https://srkb-unified.vercel.app/delete-account"

    const val CONTACT_EMAIL = "krsna6366@gmail.com"

    fun open(context: Context) {
        val target = if (DELETE_ACCOUNT_URL.isNotBlank()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(DELETE_ACCOUNT_URL))
        } else {
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$CONTACT_EMAIL")).apply {
                putExtra(Intent.EXTRA_SUBJECT, "SRKB Unified - delete my account")
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(target)
        } catch (e: Exception) {
            // No browser / mail app at all: still tell the user how to do it.
            Toast.makeText(context, "To delete your account, email $CONTACT_EMAIL", Toast.LENGTH_LONG).show()
        }
    }
}
