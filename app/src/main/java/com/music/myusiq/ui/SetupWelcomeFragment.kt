package com.music.myusiq.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.music.myusiq.R

class SetupWelcomeFragment : Fragment(R.layout.fragment_setup_welcome) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialButton>(R.id.btnGetStarted)?.setOnClickListener {
            (activity as? SetupActivity)?.navigateToNextPage()
        }
    }
}