package io.github.valerioisufi.drawview.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** A third-party component listed by [AboutLicensesDialog]. */
data class ThirdPartyLicense(
    val name: String,
    val license: String,
    val copyright: String,
    val purpose: String,
    val notice: String
)

/**
 * The application described by [AboutLicensesDialog], with the third-party components it adds
 * to the ones of DrawView.
 */
data class AboutAppInfo(
    val name: String,
    val version: String,
    val description: String,
    val additionalLicenses: List<ThirdPartyLicense> = emptyList(),
    /** The mark of the application (its logo), 64 dp. */
    val mark: (@Composable () -> Unit)? = null,
    /** The typeface of its name (its wordmark). */
    val nameFontFamily: FontFamily? = null
)

/** Application shown by [AboutLicensesDialog]: DrawView itself when not provided. */
val LocalAboutAppInfo = staticCompositionLocalOf<AboutAppInfo?> { null }

private val THIRD_PARTY_LIBRARIES = listOf(
    ThirdPartyLicense(
        name = "Google Ink",
        license = "Apache License 2.0",
        copyright = "Copyright © Google LLC",
        purpose = "Stroke modeling and vector geometry engine for smooth hand-drawn curves.",
        notice = "Licensed under the Apache License, Version 2.0. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0\n\nUnless required by applicable law or agreed to in writing, software distributed under the License is distributed on an 'AS IS' BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND."
    ),
    ThirdPartyLicense(
        name = "PDFium",
        license = "BSD 3-Clause / MIT",
        copyright = "Copyright © 2014 The PDFium Authors.\nPrecompiled binaries Copyright © 2014-2025 Benoit Blanchon.",
        purpose = "PDF document rendering engine used on Desktop (JVM), Android and iOS.",
        notice = "Redistribution and use in source and binary forms, with or without modification, are permitted provided that the copyright notice and disclaimers are retained.\n\nTHIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS 'AS IS' AND ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED."
    ),
    ThirdPartyLicense(
        name = "The FreeType Project",
        license = "The FreeType Project License (FTL)",
        copyright = "Copyright © 1996-2002, 2006 David Turner, Robert Wilhelm, Werner Lemberg.",
        purpose = "Font rasterization engine statically incorporated inside PDFium.",
        notice = "Portions of this software are copyright © 1996-2024 The FreeType Project (www.freetype.org). All rights reserved."
    ),
    ThirdPartyLicense(
        name = "MicroTeX",
        license = "MIT License",
        copyright = "Copyright © NanoMichael",
        purpose = "Dynamic cross-platform LaTeX mathematical formula rendering engine.",
        notice = "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files, to deal in the Software without restriction, subject to the MIT License conditions."
    ),
    ThirdPartyLicense(
        name = "Abseil C++",
        license = "Apache License 2.0",
        copyright = "Copyright © 2017 The Abseil Authors",
        purpose = "C++ standard library extensions and foundation types used by Google Ink.",
        notice = "Licensed under the Apache License, Version 2.0. Copyright The Abseil Authors."
    ),
    ThirdPartyLicense(
        name = "libtess2",
        license = "SGI Free Software License B",
        copyright = "Copyright © Silicon Graphics, Inc. / Mikko Mononen",
        purpose = "Polygon tessellation algorithms used by Google Ink.",
        notice = "Permission is hereby granted, free of charge, to any person obtaining a copy of this software... THE SOFTWARE IS PROVIDED 'AS IS', WITHOUT WARRANTY OF ANY KIND."
    ),
    ThirdPartyLicense(
        name = "Typography & Fonts",
        license = "SIL OFL 1.1 / Knuth License",
        copyright = "Plus Jakarta Sans: Copyright © 2020 The Plus Jakarta Sans Project Authors.\nNoto Sans, Noto Sans Mono: Copyright © 2018 The Noto Project Authors.\nComputer Modern: Donald E. Knuth.\nAMS Fonts: American Mathematical Society.",
        purpose = "Application UI typography (Plus Jakarta Sans), text of the notes (Noto Sans, Noto Sans Mono) and LaTeX mathematical symbols.",
        notice = "Plus Jakarta Sans, Noto Sans, Noto Sans Mono and AMS Fonts are licensed under the SIL Open Font License, Version 1.1. Computer Modern is licensed under Donald E. Knuth's license."
    ),
    ThirdPartyLicense(
        name = "Lucide",
        license = "ISC License",
        copyright = "Copyright © 2022 Lucide Contributors. Portions Copyright © 2013-2022 Cole Bemis (Feather).",
        purpose = "Icons of the interface.",
        notice = "Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted, provided that the above copyright notice and this permission notice appear in all copies. THE SOFTWARE IS PROVIDED 'AS IS' AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE."
    )
)

/**
 * The information of the application (its mark, its name, its version, what it is) and the
 * licenses of the components it is made of, each opening its notice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutLicensesDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appInfo = LocalAboutAppInfo.current
    val appName = appInfo?.name ?: stringResource(Res.string.app_name)
    BasicAlertDialog(onDismissRequest = onDismiss, modifier = modifier.widthIn(max = 560.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(modifier = Modifier.padding(28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(20.dp))) {
                        val mark = appInfo?.mark
                        if (mark != null) {
                            mark()
                        } else {
                            Image(painter = painterResource(Res.drawable.logo), contentDescription = appName, modifier = Modifier.size(64.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = appName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = appInfo?.nameFontFamily,
                            color = if (appInfo?.nameFontFamily != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = appInfo?.version ?: stringResource(Res.string.about_dialog_version),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DesignIconButton(DrawViewIcons.Close, stringResource(Res.string.about_dialog_close), onDismiss)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = appInfo?.description ?: stringResource(Res.string.about_dialog_app_description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = stringResource(Res.string.about_dialog_libraries_header),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .padding(vertical = 6.dp)
                ) {
                    items(THIRD_PARTY_LIBRARIES + appInfo?.additionalLicenses.orEmpty()) { item ->
                        LicenseRow(item = item)
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(Res.string.about_dialog_close))
                }
            }
        }
    }
}

/** A component and its license: what it does here, opening its copyright and its notice. */
@Composable
private fun LicenseRow(item: ThirdPartyLicense) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(item.purpose, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                item.license,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
            Icon(
                imageVector = if (expanded) DrawViewIcons.ChevronDown else DrawViewIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    text = item.copyright,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = item.notice,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
