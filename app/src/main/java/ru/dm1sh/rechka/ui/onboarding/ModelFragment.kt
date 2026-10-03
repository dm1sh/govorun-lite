package ru.dm1sh.rechka.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.dm1sh.rechka.R
import ru.dm1sh.rechka.model.GigaAmModel

/** Installs the fixed GigaAM model from a user-selected SAF folder. */
class ModelFragment : OnboardingStepFragment() {
    private lateinit var status: MaterialTextView
    private lateinit var chooseButton: MaterialButton

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        chooseButton.isEnabled = false
        status.setText(R.string.onb_model_copying)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                GigaAmModel.copyFromSelectedFolder(requireContext(), uri)
            }
            chooseButton.isEnabled = true
            showResult(result)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding_model, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        status = view.findViewById(R.id.modelStatus)
        chooseButton = view.findViewById(R.id.modelChoose)
        view.findViewById<MaterialButton>(R.id.modelDownload).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MODEL_RELEASE_URL)))
        }
        chooseButton.setOnClickListener { folderPicker.launch(null) }
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshState()
    }

    override fun onStepFocused() {
        if (::status.isInitialized) refreshState()
    }

    private fun refreshState() {
        if (GigaAmModel.isInstalled(requireContext())) {
            status.setText(R.string.onb_model_ready)
            setStepComplete(true)
        } else {
            status.setText(R.string.onb_model_pending)
            setStepComplete(false)
        }
    }

    private fun showResult(result: GigaAmModel.CopyResult) {
        when (result) {
            GigaAmModel.CopyResult.SUCCESS -> {
                status.setText(R.string.onb_model_ready)
                setStepComplete(true)
            }
            GigaAmModel.CopyResult.MISSING -> status.setText(R.string.onb_model_missing)
            GigaAmModel.CopyResult.INVALID -> status.setText(R.string.onb_model_invalid)
            GigaAmModel.CopyResult.UNREADABLE -> status.setText(R.string.onb_model_unreadable)
        }
    }

    companion object {
        private const val MODEL_RELEASE_URL =
            "https://github.com/dm1sh/rechka/releases/tag/model-gigaam-v3"
    }
}
