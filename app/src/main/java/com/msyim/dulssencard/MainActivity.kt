package com.msyim.dulssencard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.msyim.dulssencard.ui.DulSsenApp
import com.msyim.dulssencard.ui.MainViewModel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    /**
     * 사진 선택기(Photo Picker).
     *
     * 저장소 권한을 요청하지 않는다 — 사용자가 고른 이미지 한 장만 시스템이 넘겨준다.
     * `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` 를 선언하지 않는 이유다.
     */
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.importFromImage(uri)
        }
        viewModel.clearImagePickerRequest()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        setContent { DulSsenApp(viewModel) }

        lifecycleScope.launch {
            viewModel.state
                .filter { it.showImagePicker }
                .collect {
                    imagePickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
        }
    }

    /**
     * 알림 접근은 앱이 요청할 수 없고 시스템 설정에서만 켜진다.
     * 그래서 화면에 돌아올 때마다 상태를 다시 읽는 것 말고는 알 방법이 없다.
     */
    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }
}
