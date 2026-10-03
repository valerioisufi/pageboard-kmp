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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.ui.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private data class ThirdPartyItem(
    val name: String,
    val license: String,
    val copyright: String,
    val purpose: String,
    val notice: String
)

private val THIRD_PARTY_LIBRARIES = listOf(
    ThirdPartyItem(
        name = "Google Ink",
        license = "Apache License 2.0",
        copyright = "Copyright © Google LLC",
        purpose = "Stroke modeling and vector geometry engine for smooth hand-drawn curves.",
        notice = "Licensed under the Apache License, Version 2.0. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0\n\nUnless required by applicable law or agreed to in writing, software distributed under the License is distributed on an 'AS IS' BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND."
    ),
    ThirdPartyItem(
        name = "PDFium",
        license = "BSD 3-Clause / MIT",
        copyright = "Copyright © 2014 The PDFium Authors.\nPrecompiled binaries Copyright © 2014-2025 Benoit Blanchon.",
        purpose = "PDF document rendering engine used on Desktop (JVM), Android and iOS.",
        notice = "Redistribution and use in source and binary forms, with or without modification, are permitted provided that the copyright notice and disclaimers are retained.\n\nTHIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS 'AS IS' AND ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED."
    ),
    ThirdPartyItem(
        name = "The FreeType Project",
        license = "The FreeType Project License (FTL)",
        copyright = "Copyright © 1996-2002, 2006 David Turner, Robert Wilhelm, Werner Lemberg.",
        purpose = "Font rasterization engine statically incorporated inside PDFium.",
        notice = "Portions of this software are copyright © 1996-2024 The FreeType Project (www.freetype.org). All rights reserved."
    ),
    ThirdPartyItem(
        name = "MicroTeX",
        license = "MIT License",
        copyright = "Copyright © NanoMichael",
        purpose = "Dynamic cross-platform LaTeX mathematical formula rendering engine.",
        notice = "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files, to deal in the Software without restriction, subject to the MIT License conditions."
    ),
    ThirdPartyItem(
        name = "Abseil C++",
        license = "Apache License 2.0",
        copyright = "Copyright © 2017 The Abseil Authors",
        purpose = "C++ standard library extensions and foundation types used by Google Ink.",
        notice = "Licensed under the Apache License, Version 2.0. Copyright The Abseil Authors."
    ),
    ThirdPartyItem(
        name = "libtess2",
        license = "SGI Free Software License B",
        copyright = "Copyright © Silicon Graphics, Inc. / Mikko Mononen",
        purpose = "Polygon tessellation algorithms used by Google Ink.",
        notice = "Permission is hereby granted, free of charge, to any person obtaining a copy of this software... THE SOFTWARE IS PROVIDED 'AS IS', WITHOUT WARRANTY OF ANY KIND."
    ),
    ThirdPartyItem(
        name = "Typography & Fonts",
        license = "SIL OFL 1.1 / Knuth License",
        copyright = "Nunito: Copyright © 2014 The Nunito Project Authors.\nComputer Modern: Donald E. Knuth.\nAMS Fonts: American Mathematical Society.",
        purpose = "Application UI typography (Nunito) and LaTeX mathematical symbols.",
        notice = "Nunito and AMS Fonts are licensed under the SIL Open Font License, Version 1.1. Computer Modern is licensed under Donald E. Knuth's license."
    )
)

@Composable
fun AboutLicensesDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.about_dialog_close))
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    painter = painterResource(Res.drawable.logo),
                    contentDescription = stringResource(Res.string.app_name),
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Column {
                    Text(
                        text = stringResource(Res.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(Res.string.about_dialog_version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
            ) {
                Text(
                    text = stringResource(Res.string.about_dialog_app_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))

                Text(
                    text = stringResource(Res.string.about_dialog_libraries_header),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(THIRD_PARTY_LIBRARIES) { item ->
                        ThirdPartyCard(item = item)
                    }
                }
            }
        }
    )
}

@Composable
private fun ThirdPartyCard(item: ThirdPartyItem) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = item.license,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = item.purpose,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Text(
                        text = item.copyright,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(8.dp)
                    ) {
                        Text(
                            text = item.notice,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
