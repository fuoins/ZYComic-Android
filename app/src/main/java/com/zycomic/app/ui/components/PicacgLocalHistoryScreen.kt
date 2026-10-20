package com.zycomic.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zycomic.app.reader.PicacgSource
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.manga.components.MangaCover.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat

/**
 * Displays only reader history persisted for Picacg's source ID. This intentionally uses the
 * shared local history interactor, not Picacg's online account or any cloud-history endpoint.
 */
@Composable
fun PicacgLocalHistoryScreen(
    onOpenManga: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val getHistory = remember { Injekt.get<GetHistory>() }
    val getManga = remember { Injekt.get<GetManga>() }
    val historyFlow = remember(getHistory, getManga) {
        getHistory.subscribe(
            query = "",
            unfinishedManga = null,
            unfinishedChapter = null,
            nonLibraryEntries = null,
        ).map<List<HistoryWithRelations>, List<Pair<HistoryWithRelations, String>>?> { entries ->
            withContext(Dispatchers.IO) {
                PicacgLocalHistoryPolicy.onlyPicacg(entries, PicacgSource.id).mapNotNull { entry ->
                    val manga = getManga.await(entry.mangaId) ?: return@mapNotNull null
                    val comicId = PicacgLocalHistoryPolicy.comicId(manga.url, manga.source, PicacgSource.id) ?: return@mapNotNull null
                    entry to comicId
                }
            }
        }
    }
    val picacgHistory by historyFlow.collectAsState(initial = null)

    when {
        picacgHistory == null -> LoadingScreen(modifier = modifier)
        picacgHistory?.isEmpty() == true -> EmptyScreen(
            message = stringResource(KMR.strings.picacg_local_history_empty),
            modifier = modifier,
        )
        else -> {
            val dateTimeFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
            LazyColumn(modifier = modifier.fillMaxSize()) {
                items(picacgHistory.orEmpty(), key = { it.first.id }) { (history, comicId) ->
                    val chapterLabel = history.chapterNumber.takeIf { it > 0 }?.let { number ->
                        val formattedNumber = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
                        stringResource(KMR.strings.picacg_local_history_chapter, formattedNumber)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenManga("picacg:$comicId") }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MangaCover.Book(
                            data = history.coverData,
                            modifier = Modifier.width(68.dp),
                            contentDescription = history.title,
                            size = Size.Medium,
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = history.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            chapterLabel?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            history.readAt?.let { readAt ->
                                Text(
                                    text = dateTimeFormat.format(readAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
