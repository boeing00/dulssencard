package com.msyim.dulssencard.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.msyim.dulssencard.ingest.OcrLayout
import kotlinx.coroutines.tasks.await

/**
 * ML Kit 으로 이미지에서 텍스트를 뽑는다. 온디바이스 모델이라 네트워크를 쓰지 않는다
 * (그래서 매니페스트에서 INTERNET 권한을 도로 제거했다).
 *
 * ## `Text.getText()` 를 쓰지 않는 이유
 *
 * ML Kit 이 주는 평문은 **화면의 시각적 행 구조를 보존하지 않는다.** 실기기에서 확인한 결과,
 * 2단 레이아웃(왼쪽 라벨 / 오른쪽 값)에서 오른쪽 열이 통째로 문자열 끝으로 밀렸다:
 *
 * ```
 * 개인 구매 추적 한도
 * 1,000,000
 * 한도 저장
 * …
 * 원          ← 오른쪽 열이
 * 매월 1일     ← 전부 맨 끝에
 * 허용됨
 * ```
 *
 * 카드사 이용내역 화면이 정확히 이 구조다(왼쪽 가맹점 / 오른쪽 금액). 평문을 그대로 쓰면
 * 가맹점과 금액의 짝이 어긋나 엉뚱한 거래가 만들어진다.
 *
 * 그래서 줄마다 딸려 오는 좌표로 **같은 시각적 행을 다시 묶는다**([OcrLayout.rebuildRows]).
 */
object ImageOcrHelper {

    /**
     * 이미지에서 텍스트를 뽑아 화면에 보이던 행 구조로 되돌린다.
     * 실패하면 null.
     */
    suspend fun extractText(context: Context, uri: Uri): String? = runCatching {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            val result = recognizer.process(image).await()
            val lines = result.textBlocks
                .flatMap { it.lines }
                .mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    OcrLayout.Fragment(
                        text = line.text,
                        left = box.left,
                        top = box.top,
                        bottom = box.bottom,
                    )
                }
            OcrLayout.rebuildRows(lines).takeIf { it.isNotBlank() }
        } finally {
            recognizer.close()
        }
    }.getOrNull()
}
