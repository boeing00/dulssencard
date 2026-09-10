# 이미지 인식 거래 입력 기능 구현 계획

## 목표
카드앱이나 카카오톡 캡처 화면에서 OCR로 텍스트를 추출하고, 기존 PaymentParser로 파싱해서 거래를 입력하는 기능 추가

## 아키텍처 분석

### 기존 구조
1. **화면**: `Screen` enum에 7개 화면 (ONBOARD, HOME, INBOX, DETAIL, CARDS, EDIT, SETTINGS, SOURCES)
2. **수집 경로**: 
   - SMS (READ_SMS 권한)
   - 알림 접근 (카드앱 푸시, 카카오톡)
   - 이번에 추가: **이미지 OCR**
3. **파싱**: `PaymentParser.parse(RawMessage)` - 이미 구현됨
4. **저장**: `DulSsenRepository.ingest(RawMessage)` - 중복 체크 포함

## 구현 접근 방법

### 1. OCR 라이브러리 선택
**ML Kit Text Recognition v2** 사용
- 이유:
  - Google Play Services 기반 (온디바이스, 네트워크 불필요)
  - 한글 지원 우수
  - 무료
  - "네트워크 호출 없음" 정책 유지

### 2. UI 플로우
```
설정 화면
  └─ "이미지에서 불러오기" 버튼
       └─ 갤러리/카메라 선택 (ActivityResultContracts.PickVisualMedia)
            └─ 이미지 선택
                 └─ OCR 처리 중...
                      └─ 텍스트 추출
                           └─ PaymentParser로 파싱
                                └─ Repository.ingest()
                                     └─ 결과 토스트 ("N건 불러왔습니다")
```

### 3. 새 화면 추가 여부
**추가하지 않음** - 설정 화면에 버튼만 추가
- 이유: 간단한 플로우이고, 중간 확인 화면 불필요
- 이미지 선택 → OCR → 자동 파싱 → 결과만 표시

### 4. 구현 파일

#### 4.1 의존성 추가
`app/build.gradle.kts`:
```kotlin
implementation("com.google.mlkit:text-recognition-korean:16.0.0")
```

#### 4.2 권한 추가
`AndroidManifest.xml`:
- READ_MEDIA_IMAGES (Android 13+)
- READ_EXTERNAL_STORAGE (Android 12 이하)
- 둘 다 런타임 권한

#### 4.3 SettingsScreen.kt
"최근 14일 내역 불러오기" 아래에 새 버튼 추가:
```kotlin
SettingRow(
    title = "이미지에서 불러오기",
    subtitle = "카드앱·카카오톡 캡처 화면을 인식합니다",
    onClick = onImportFromImage,
)
```

#### 4.4 MainViewModel.kt
```kotlin
// UiState에 추가
val needsImagePermission: Boolean = false,
val showImagePicker: Boolean = false,

// 메서드 추가
fun requestImageImport()
fun importFromImage(uri: Uri)
fun clearImagePermissionRequest()
```

#### 4.5 MainActivity.kt
```kotlin
// 이미지 피커
val imagePickerLauncher = registerForActivityResult(PickVisualMedia()) { uri ->
    if (uri != null) viewModel.importFromImage(uri)
}

// 권한 요청
val imagePermissionLauncher = registerForActivityResult(RequestPermission()) { granted ->
    if (granted) {
        imagePickerLauncher.launch(PickVisualMediaRequest(ImageOnly))
    }
    viewModel.clearImagePermissionRequest()
}
```

#### 4.6 ImageOcrHelper.kt (새 파일)
```kotlin
object ImageOcrHelper {
    suspend fun extractText(context: Context, uri: Uri): String?
    // ML Kit로 이미지 → 텍스트 추출
}
```

#### 4.7 Repository 확장
기존 `ingest(RawMessage)` 재사용
- source: TxSource.MANUAL
- senderKey: "image-ocr"
- body: OCR 추출 텍스트

### 5. 작업 순서
1. `app/build.gradle.kts` - ML Kit 의존성 추가
2. `AndroidManifest.xml` - 이미지 권한 추가
3. `ImageOcrHelper.kt` - OCR 유틸 작성
4. `MainViewModel.kt` - 상태 및 로직 추가
5. `MainActivity.kt` - 권한 및 피커 런처 추가
6. `SettingsScreen.kt` - UI 버튼 추가
7. `DulSsenApp.kt` - 콜백 연결
8. 빌드 및 테스트

### 6. 제약사항
- **네트워크 사용 안 함**: ML Kit 온디바이스 모델만 사용
- **원본 보관 안 함**: 이미지는 OCR 후 즉시 폐기, 텍스트만 파서에 전달
- **중복 방지**: 기존 fingerprint 로직으로 자동 처리
- **파싱 실패 시**: "확인 필요"로 inbox에 들어감 (기존 동작)

### 7. 사용자 경험
1. 설정 → "이미지에서 불러오기" 탭
2. 권한 요청 (최초 1회)
3. 갤러리에서 스크린샷 선택
4. 1-2초 OCR 처리
5. "2건 불러왔습니다" 토스트
6. Inbox로 이동 (자동 or 사용자)

### 8. 에러 처리
- OCR 실패: "이미지에서 텍스트를 읽지 못했습니다"
- 파싱 0건: "결제 정보를 찾지 못했습니다"
- 권한 거부: 권한 필요 안내 토스트
