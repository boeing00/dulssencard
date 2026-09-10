package com.msyim.dulssencard.ui.component

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.msyim.dulssencard.domain.Money

/**
 * 금액 입력칸에 천 단위 쉼표를 **표시만** 한다. 상태에는 숫자만 들어간다.
 *
 * 예전에는 글자를 입력할 때마다 `Money.reformatInput` 으로 값 자체를 갈아 끼웠다.
 * `String` 을 받는 `BasicTextField` 는 값이 바뀌면 커서를 끝으로 보내기 때문에,
 * 가운데 자리를 고치려고 커서를 옮겨도 한 글자 누르는 순간 맨 뒤로 튀었다.
 * 300,000 을 30,000 으로 고치려면 전부 지우고 다시 치는 수밖에 없었다.
 */
object AmountVisualTransformation : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val formatted = Money.group(digits)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                Money.groupedOffset(digits.length, offset)

            override fun transformedToOriginal(offset: Int): Int =
                formatted.take(offset.coerceIn(0, formatted.length)).count { it.isDigit() }
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }
}
