package com.zycomic.app.ui.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.imageLoader
import com.zycomic.app.data.dto.Chapter
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.ui.components.CoverError
import com.zycomic.app.ui.components.coverUrl
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/** Shared cover/title/author presentation used by each platform's manga detail screen. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun MangaDetailCoverHeader(
    manga: Manga,
    authors: List<String>,
    onSearchTitle: (String) -> Unit,
    onSearchAuthor: (String) -> Unit = onSearchTitle,
    imageLoader: ImageLoader? = null,
    modifier: Modifier = Modifier,
    coverModel: String = coverUrl(manga),
    trailingInfo: @Composable () -> Unit = {},
) {
    var titleExpanded by rememberSaveable(manga.id) { mutableStateOf(false) }
    val resolvedImageLoader = imageLoader ?: LocalContext.current.imageLoader
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        SubcomposeAsyncImage(
            model = coverModel,
            contentDescription = manga.name,
            imageLoader = resolvedImageLoader,
            modifier = Modifier
                .width(100.dp)
                .aspectRatio(2f / 3f)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
            error = { CoverError(Modifier.fillMaxSize()) },
        )
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = manga.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = detailTitleMaxLines(titleExpanded),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onSearchTitle(manga.name) },
            )
            Text(
                stringResource(if (titleExpanded) KMR.strings.detail_title_collapse else KMR.strings.detail_title_expand),
                modifier = Modifier
                    .clickable { titleExpanded = !titleExpanded }
                    .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                authors.map(String::trim).filter(String::isNotBlank).distinct().forEach { author ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable { onSearchAuthor(author) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            Icons.Outlined.PersonOutline,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            author,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            trailingInfo()
        }
    }
}

/** Clickable, rounded tag capsules shared across detail screens. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun MangaDetailTagChips(
    tags: List<String>,
    onTagClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: @Composable () -> Unit = {},
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.map(String::trim).filter(String::isNotBlank).distinct().forEach { tag ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onTagClick(tag) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(tag, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailingContent()
    }
}

/** Expandable three-line description shared with the platform-specific detail overlays. */
@Composable
fun MangaDetailDescription(text: String, modifier: Modifier = Modifier) {
    var expanded by remember(text) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Chapter rows match the existing platform detail screen without changing reader routing. */
@Composable
fun MangaDetailChapterGrid(
    chapters: List<Chapter>,
    onChapterClick: (Chapter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        chapters.forEach { chapter ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onChapterClick(chapter) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (chapter.readed != 1) {
                    Icon(Icons.Filled.Circle, contentDescription = null, modifier = Modifier.size(8.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    chapter.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    color = if (chapter.readed == 1) LocalContentColor.current.copy(alpha = 0.38f) else LocalContentColor.current,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
        }
    }
}

/** Recommendation card used in both the shared screen and the Picacg detail overlay. */
@Composable
fun MangaDetailRecommendationCard(
    manga: Manga,
    imageLoader: ImageLoader? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverModel: String = coverUrl(manga),
) {
    val resolvedImageLoader = imageLoader ?: LocalContext.current.imageLoader
    Column(
        modifier = modifier
            .width(96.dp)
            .height(180.dp)
            .clickable(onClick = onClick),
    ) {
        SubcomposeAsyncImage(
            model = coverModel,
            contentDescription = manga.name,
            imageLoader = resolvedImageLoader,
            modifier = Modifier
                .width(96.dp)
                .height(144.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
            error = { CoverError(Modifier.fillMaxSize()) },
        )
        Spacer(Modifier.height(4.dp))
        Text(manga.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MangaDetailRecommendationRow(
    mangas: List<Manga>,
    imageLoader: ImageLoader? = null,
    onMangaClick: (Manga) -> Unit,
    modifier: Modifier = Modifier,
    coverModel: (Manga) -> String = { coverUrl(it) },
) {
    val resolvedImageLoader = imageLoader ?: LocalContext.current.imageLoader
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(mangas, key = { it.id }) { manga ->
            MangaDetailRecommendationCard(manga, resolvedImageLoader, { onMangaClick(manga) }, coverModel = coverModel(manga))
        }
    }
}
