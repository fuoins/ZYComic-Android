package com.zycomic.app.ui.picacg

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun PicacgProfileExtras(onOpenSettings: () -> Unit) {
    TextPreferenceWidget(
        title = stringResource(KMR.strings.picacg_settings),
        icon = Icons.Outlined.Settings,
        onPreferenceClick = onOpenSettings,
    )
}
