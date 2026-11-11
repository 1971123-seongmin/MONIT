package com.monit.core.designsystem.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.monit.core.designsystem.component.MonitCard
import com.monit.core.designsystem.component.MonitListItem
import com.monit.core.designsystem.component.MonitTopBar
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.button.MonitButtonSize
import com.monit.core.designsystem.component.button.MonitButtonType
import com.monit.core.designsystem.component.chip.MonitChipSize
import com.monit.core.designsystem.component.chip.MonitChipType
import com.monit.core.designsystem.component.chip.MonitFilterChip
import com.monit.core.designsystem.component.textfield.MonitTextField
import com.monit.core.designsystem.theme.MonitTheme

/** 공통 API 조합 예시. 새 Figma variant나 실제 제품 화면을 의미하지 않는다. */
@Composable
internal fun MonitUsageCatalog(modifier: Modifier = Modifier) {
    Surface(
        modifier,
        color = MonitTheme.colors.surface,
    ) {
        Column(
            Modifier.padding(MonitTheme.spacing.content),
            verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.section),
        ) {
            MonitTopBar(
                "공통 컴포넌트",
                actions = {
                    IconButton(
                        {},
                        Modifier.size(MonitTheme.sizes.touchTarget),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "닫기",
                            modifier = Modifier.size(MonitTheme.sizes.icon),
                        )
                    }
                },
            )

            MonitButton(
                "확인하고 계속하기",
                Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(
                        Icons.Filled.Check,
                        null,
                        Modifier.size(MonitTheme.sizes.icon),
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        null,
                        Modifier.size(MonitTheme.sizes.icon),
                    )
                },
            ) {}

            MonitButton(
                "다른 방법 선택",
                type = MonitButtonType.Secondary,
                size = MonitButtonSize.Compact,
                trailingIcon = {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        null,
                        Modifier.size(MonitTheme.sizes.icon),
                    )
                },
            ) {}

            MonitTextField(
                "",
                {},
                label = "검색어",
                placeholder = {
                    Text("검색할 내용을 입력하세요")
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
                trailingIcon = {
                    IconButton(
                        {},
                        Modifier.size(MonitTheme.sizes.touchTarget),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            "지우기",
                            Modifier.size(MonitTheme.sizes.icon),
                        )
                    }
                },
            )

            MonitFilterChip(
                "필터 적용",
                true,
                leadingIcon = {
                    Icon(
                        Icons.Filled.Check,
                        null,
                    )
                },
            ) {}

            MonitFilterChip(
                "다른 조건",
                false,
                type = MonitChipType.Outlined,
                size = MonitChipSize.Compact,
            ) {}

            MonitCard {
                Text(
                    "직접 구성하는 카드",
                    style = MaterialTheme.typography.labelLarge,
                )

                Text(
                    "문구·데이터·행동은 호출자가 결정합니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                MonitButton(
                    "자세히 보기",
                    type = MonitButtonType.Secondary,
                    size = MonitButtonSize.Compact,
                ) {}
            }

            MonitListItem(
                headline = {
                    Text("목록 제목")
                },
                supportingContent = {
                    Text("부가 설명")
                },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        null,
                        Modifier.size(MonitTheme.sizes.icon),
                    )
                },
            )
        }
    }
}

@Preview(
    name = "Usage Light 360",
    widthDp = 360,
    heightDp = 1000,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Preview(
    name = "Usage Dark 360",
    widthDp = 360,
    heightDp = 1000,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "Usage Light 320 200%",
    widthDp = 320,
    heightDp = 1600,
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Preview(
    name = "Usage Dark 320 200%",
    widthDp = 320,
    heightDp = 1600,
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun CustomizationPreview() = MonitTheme {
    MonitUsageCatalog()
}
