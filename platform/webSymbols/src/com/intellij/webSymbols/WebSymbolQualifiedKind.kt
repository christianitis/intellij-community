// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.webSymbols

data class WebSymbolQualifiedKind(
  val namespace: SymbolNamespace,
  val kind: SymbolKind,
)