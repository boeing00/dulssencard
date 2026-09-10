package com.msyim.dulssencard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.msyim.dulssencard.ui.DulSsenApp
import com.msyim.dulssencard.ui.MainViewModel
import com.msyim.dulssencard.ui.UiState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    /**
     * 사진 선택기(Photo Picker).
     *
     * 저장소 권한을 요청하지 않는다 — 사용자가 고른 이미지 한 장만 시스템이 넘겨준다.
     * `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` 를 선언하지 않는 이유다.
     */
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.importFromImage(uri)
        }
        viewModel.clearImagePickerRequest()
    }

    /**
     * 백업 파일 선택기(SAF).
     *
     * 사진 선택기와 같은 이유로 권한이 필요 없다. 사용자가 고른 파일 하나에 대해서만
     * 읽기 권한이 넘어온다. 백업은 확장자가 `.dsc` 라 등록된 MIME 타입이 없어 `*/*` 로 연다.
     */
    private val backupPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.onBackupFilePicked(uri)
        }
        viewModel.clearBackupPickerRequest()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        setContent { DulSsenApp(viewModel) }

        // **플래그 하나만** 본다. 예전에는 UiState 전체를 흘려보내며 `filter` 로 걸렀는데,
        // 사진을 고르는 사이에 결제 알림이 하나만 들어와도 새 UiState 가 방출돼
        // (showImagePicker 는 여전히 true 니까) 선택기가 한 번 더 떴다.
        onFlagRaised(UiState::showImagePicker) {
            imagePickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
        onFlagRaised(UiState::showBackupPicker) {
            backupPickerLauncher.launch(arrayOf("*/*"))
        }

        // 내보내기가 끝나면 공유 시트로 넘긴다. 백업은 내부 저장소에 있어서
        // 이 경로가 없으면 사용자가 파일에 닿을 방법이 없다.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state
                    .map { it.shareBackupPath }
                    .distinctUntilChanged()
                    .collect { path ->
                        if (path != null) {
                            shareBackup(path)
                            viewModel.clearShareRequest()
                        }
                    }
            }
        }
    }

    private fun onFlagRaised(select: (UiState) -> Boolean, action: () -> Unit) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.map { select(it) }.distinctUntilChanged().collect { raised ->
                    if (raised) action()
                }
            }
        }
    }

    /**
     * 암호화된 백업 파일을 공유 시트로 내보낸다.
     *
     * 파일 자체는 내부 저장소(filesDir/exports)에 있고, 사용자가 고른 앱에만
     * 그 파일 하나에 대한 일회성 읽기 권한이 간다([FileProvider]).
     */
    private fun shareBackup(path: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "암호화 백업 저장"))
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
