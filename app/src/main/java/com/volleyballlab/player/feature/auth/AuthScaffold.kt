package com.volleyballlab.player.feature.auth

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volleyballlab.player.R
import com.volleyballlab.player.ui.component.pressScale
import com.volleyballlab.player.ui.theme.VbSplashBackground

/**
 * The shared frame both auth screens sit in: branded backdrop, brand mark, and a
 * white card holding the form.
 *
 * ## Why the character from the mockup is not here
 *
 * The design puts a 3D figure above the card. The app already owns that figure -
 * `AthleteScene`, the model that waves on the splash - but it is a WebView running
 * WebGL, and this screen appears the instant the splash's copy is torn down. Paying
 * that cost twice inside three seconds of a cold start, to decorate the screen
 * standing between the athlete and the app, is the wrong trade. The mark keeps the
 * two screens visually continuous for free.
 *
 * Swapping it in later is a one-line change, and worth revisiting if the login
 * screen stops being on the cold-start path.
 */
@Composable
fun AuthScaffold(
    @StringRes title: Int,
    @StringRes subtitle: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VbSplashBackground),
    ) {
        AuthBackdrop(Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // The form must stay reachable above the keyboard; without this the
                // password field on a short phone sits underneath it.
                .imePadding()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Image(
                painter = painterResource(R.drawable.volleyball_lab_logo),
                contentDescription = stringResource(R.string.cd_app_logo),
                modifier = Modifier.size(84.dp),
            )
            Spacer(Modifier.height(18.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                shadowElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TitleColor,
                    )
                    Spacer(Modifier.height(8.dp))
                    // The short rule under the heading, in the brand primary. Same
                    // device as the skill cards' title rule, for the same reason.
                    Box(
                        Modifier
                            .width(42.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    content()
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * Two soft arcs, top-left and bottom-right, echoing the mockup's wave shapes.
 *
 * Drawn rather than composed for the same reason the splash backdrop is: this is
 * two paths in one layer, on a screen that appears during a cold start while
 * Firebase is initialising behind it.
 */
@Composable
private fun AuthBackdrop(modifier: Modifier = Modifier) {
    val wash = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
    val cool = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)

    Canvas(modifier) {
        val w = size.width
        val h = size.height

        drawPath(
            path = Path().apply {
                moveTo(0f, 0f)
                lineTo(w * 0.62f, 0f)
                cubicTo(w * 0.34f, h * 0.06f, w * 0.20f, h * 0.14f, 0f, h * 0.20f)
                close()
            },
            color = cool,
        )
        drawPath(
            path = Path().apply {
                moveTo(w, h)
                lineTo(w, h * 0.78f)
                cubicTo(w * 0.62f, h * 0.84f, w * 0.34f, h * 0.92f, w * 0.18f, h)
                close()
            },
            color = wash,
        )
    }
}

/**
 * A labelled field in the mockup's style: the label above rather than floating
 * inside, so it stays readable once the field has content.
 */
@Composable
fun AuthField(
    @StringRes label: Int,
    @StringRes placeholder: Int,
    value: String,
    onValueChange: (String) -> Unit,
    leadingIcon: ImageVector,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePasswordVisible: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = TitleColor,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            placeholder = {
                Text(
                    text = stringResource(placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = onTogglePasswordVisible?.let { toggle ->
                {
                    Icon(
                        imageVector = if (passwordVisible) {
                            Icons.Rounded.VisibilityOff
                        } else {
                            Icons.Rounded.Visibility
                        },
                        contentDescription = stringResource(
                            if (passwordVisible) {
                                R.string.auth_hide_password
                            } else {
                                R.string.auth_show_password
                            },
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(onClick = toggle),
                    )
                }
            },
            visualTransformation = when {
                !isPassword || passwordVisible -> VisualTransformation.None
                else -> PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The primary action: full width, brand orange, with an inline busy state. */
@Composable
fun AuthPrimaryButton(
    @StringRes label: Int,
    onClick: () -> Unit,
    enabled: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            // A clickable Surface stops accepting taps when disabled but does not
            // change colour, so without this the button would look identical
            // whether it worked or not - and it starts life disabled, on an empty
            // form. Alpha rather than a different colour, so it reads as this
            // button currently unavailable rather than as some other control.
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .pressScale(interactionSource),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = Color.White,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

/** "or continue with", as a rule with the words sitting in a gap in it. */
@Composable
fun AuthDivider(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = stringResource(R.string.auth_or_continue),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * A federated sign-in button.
 *
 * When [onClick] is provided the button is enabled; otherwise it is drawn muted
 * and is not clickable.
 */
@Composable
fun AuthProviderButton(
    @StringRes label: Int,
    modifier: Modifier = Modifier,
    mark: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .height(52.dp)
            // Muted as well as inert. A control that stops responding but looks
            // unchanged reads as the app having frozen.
            .alpha(if (onClick != null) 1f else DISABLED_ALPHA)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { mark() }
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A checkbox drawn to match the mockup's square outline rather than Material's. */
@Composable
fun AuthCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    @StringRes label: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (checked) MaterialTheme.colorScheme.primary else Color.White)
                .border(
                    width = 1.dp,
                    color = if (checked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(5.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Navy, matching the mockup's heading colour. Shared by both auth screens. */
internal val TitleColor = Color(0xFF13285C)

/** Faded enough to read as unavailable, solid enough to still read as the button. */
private const val DISABLED_ALPHA = 0.45f
