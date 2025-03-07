// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.newvfs.persistent;

import com.intellij.openapi.util.io.FileAttributes;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;

@ApiStatus.Internal
public interface BatchingFileSystem {
  @NotNull Map<String, FileAttributes> listWithAttributes(@NotNull VirtualFile dir);

  @NotNull Map<String, FileAttributes> listWithAttributes(@NotNull VirtualFile dir, @NotNull Collection<String> childrenNames);
}
