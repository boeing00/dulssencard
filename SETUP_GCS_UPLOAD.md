# GitHub Actions APK 빌드 및 GCS 업로드 설정 가이드

## 1. Google Cloud Storage 버킷 생성

```bash
# GCS 버킷 생성 (이름은 전역 고유해야 함)
gsutil mb gs://dulssencard-apk-builds

# 공개 접근 설정 (선택사항 - APK를 공개하려면)
gsutil iam ch allUsers:objectViewer gs://dulssencard-apk-builds
```

## 2. GCP Service Account 생성

```bash
# Service account 생성
gcloud iam service-accounts create github-actions-apk-uploader \
    --display-name="GitHub Actions APK Uploader"

# Storage Object Admin 권한 부여
gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
    --member="serviceAccount:github-actions-apk-uploader@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/storage.objectAdmin"

# JSON 키 생성 및 다운로드
gcloud iam service-accounts keys create ~/gcp-sa-key.json \
    --iam-account=github-actions-apk-uploader@YOUR_PROJECT_ID.iam.gserviceaccount.com
```

## 3. GitHub Secrets 설정

GitHub 저장소로 가서:
1. **Settings** → **Secrets and variables** → **Actions** → **New repository secret**

2. 두 개의 Secret 추가:

   **GCP_SA_KEY**
   - Value: `~/gcp-sa-key.json` 파일의 전체 내용 복사/붙여넣기
   
   **GCS_BUCKET_NAME**
   - Value: `dulssencard-apk-builds` (또는 생성한 버킷 이름)

## 4. Workflow 실행

### 자동 실행
- `main` 브랜치에 push하면 자동으로 실행됨

### 수동 실행
1. GitHub 저장소 → **Actions** 탭
2. **Build and Upload APK to Google Cloud Storage** 선택
3. **Run workflow** 버튼 클릭

## 5. 빌드된 APK 확인

### Google Cloud Storage에서
```bash
gsutil ls gs://dulssencard-apk-builds/
```

또는 웹 브라우저에서:
```
https://console.cloud.google.com/storage/browser/dulssencard-apk-builds
```

### GitHub Artifacts에서
- GitHub 저장소 → **Actions** 탭 → 완료된 workflow 클릭
- **Artifacts** 섹션에서 `dulssencard-debug-apk` 다운로드

## 6. APK 다운로드 URL

빌드가 완료되면 APK는 다음 형식의 URL로 접근 가능:
```
https://storage.googleapis.com/dulssencard-apk-builds/dulssencard-debug-YYYYMMDD-HHMMSS-COMMITHASH.apk
```

## 보안 참고사항

- Service account JSON 키는 절대 코드에 포함하지 마세요
- GitHub Secrets에만 저장하세요
- 최소 권한 원칙: Storage Object Admin만 부여
- APK를 공개하지 않으려면 버킷을 private으로 유지하세요

## Release 버전 빌드 (선택사항)

Release APK를 빌드하려면:
1. `app/build.gradle.kts`에 signing config 설정
2. Keystore 파일을 GitHub Secrets에 base64로 저장
3. Workflow 수정: `assembleDebug` → `assembleRelease`
