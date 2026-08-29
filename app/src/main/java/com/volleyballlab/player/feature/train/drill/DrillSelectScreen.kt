package com.volleyballlab.player.feature.train.drill

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.volleyballlab.player.R
import com.volleyballlab.player.appContainer
import com.volleyballlab.player.domain.model.Drill
import com.volleyballlab.player.domain.model.Skill
import com.volleyballlab.player.domain.repository.DrillRepository
import com.volleyballlab.player.ui.component.EntranceAnimation
import com.volleyballlab.player.ui.component.pressScale
import com.volleyballlab.player.ui.component.SkillAccentRule
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

class DrillSelectViewModel(
    drillRepository: DrillRepository,
    skill: Skill,
) : ViewModel() {

    val drills: StateFlow<List<Drill>> = flow { emit(drillRepository.drillsFor(skill)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        /**
         * The skill is a navigation argument rather than something this ViewModel
         * looks up, so it is passed to the factory. That keeps the ViewModel a
         * plain constructor-injected class with no SavedStateHandle parsing in it.
         */
        fun factory(skill: Skill): ViewModelProvider.Factory = viewModelFactory {
            initializer { DrillSelectViewModel(appContainer.drillRepository, skill) }
        }
    }
}

@Composable
fun DrillSelectScreen(
    skill: Skill,
    onDrillSelected: (Drill) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel: DrillSelectViewModel = viewModel(
        // Keyed by skill so switching skills produces a new ViewModel rather than
        // reusing the previous skill's loaded drill list.
        key = "drills-${skill.name}",
        factory = DrillSelectViewModel.factory(skill),
    )
    val drills by viewModel.drills.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            EntranceAnimation(index = 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.drill_select_title,
                            stringResource(skill.displayName),
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        itemsIndexed(drills, key = { _, drill -> drill.id }) { index, drill ->
            EntranceAnimation(index = index + 1) {
                DrillRow(
                    drill = drill,
                    onClick = { onDrillSelected(drill) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun DrillRow(drill: Drill, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interactionSource),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SkillAccentRule(
                skill = drill.skill,
                height = 34,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(text = drill.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = drill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaChip(stringResource(drill.level.displayName))
                    MetaChip(
                        pluralStringResource(
                            R.plurals.drill_reps,
                            drill.targetReps,
                            drill.targetReps,
                        )
                    )
                    MetaChip(stringResource(R.string.drill_duration, drill.estimatedMinutes))
                }
            }
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
