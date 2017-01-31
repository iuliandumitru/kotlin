/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.js.config;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.StandardFileSystems;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileSystem;
import com.intellij.util.PathUtil;
import com.intellij.util.io.URLUtil;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.utils.JsMetadataVersion;
import org.jetbrains.kotlin.utils.KotlinJavascriptMetadata;
import org.jetbrains.kotlin.utils.KotlinJavascriptMetadataUtils;

import java.io.File;
import java.util.Collections;
import java.util.List;

import static org.jetbrains.kotlin.utils.PathUtil.getKotlinPathsForDistDirectory;

public class LibrarySourcesConfig extends JsConfig {
    public static final List<String> JS_STDLIB =
            Collections.singletonList(getKotlinPathsForDistDirectory().getJsStdLibJarPath().getAbsolutePath());

    public static final List<String> JS_KOTLIN_TEST =
            Collections.singletonList(getKotlinPathsForDistDirectory().getJsKotlinTestJarPath().getAbsolutePath());

    public static final String UNKNOWN_EXTERNAL_MODULE_NAME = "<unknown>";

    public LibrarySourcesConfig(@NotNull Project project, @NotNull CompilerConfiguration configuration) {
        super(project, configuration);
    }

    @NotNull
    public List<String> getLibraries() {
        return getConfiguration().getList(JSConfigurationKeys.LIBRARY_FILES);
    }

    @Override
    protected void init(@NotNull final List<KotlinJavascriptMetadata> metadata) {
        if (getLibraries().isEmpty()) return;

        Function1<String, Unit> report = new Function1<String, Unit>() {
            @Override
            public Unit invoke(String message) {
                throw new IllegalStateException(message);
            }
        };

        Function1<VirtualFile, Unit> action = new Function1<VirtualFile, Unit>() {
            @Override
            public Unit invoke(VirtualFile file) {
                String libraryPath = PathUtil.getLocalPath(file);
                assert libraryPath != null : "libraryPath for " + file + " should not be null";
                metadata.addAll(KotlinJavascriptMetadataUtils.loadMetadata(libraryPath));

                return Unit.INSTANCE;
            }
        };

        boolean hasErrors = checkLibFilesAndReportErrors(report, action);
        assert !hasErrors : "hasErrors should be false";
    }

    @Override
    public boolean checkLibFilesAndReportErrors(@NotNull Function1<String, Unit> report) {
        return checkLibFilesAndReportErrors(report, null);
    }

    private boolean checkLibFilesAndReportErrors(@NotNull Function1<String, Unit> report, @Nullable Function1<VirtualFile, Unit> action) {
        List<String> libraries = getLibraries();
        if (libraries.isEmpty()) {
            return false;
        }

        VirtualFileSystem fileSystem = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL);
        VirtualFileSystem jarFileSystem = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.JAR_PROTOCOL);

        for (String path : libraries) {
            VirtualFile file;

            File filePath = new File(path);
            if (!filePath.exists()) {
                report.invoke("Path '" + path + "' does not exist");
                return true;
            }

            if (path.endsWith(".jar") || path.endsWith(".zip")) {
                file = jarFileSystem.findFileByPath(path + URLUtil.JAR_SEPARATOR);
            }
            else {
                file = fileSystem.findFileByPath(path);
            }

            if (file == null) {
                report.invoke("File '" + path + "' does not exist or could not be read");
                return true;
            }

            List<KotlinJavascriptMetadata> metadataList = KotlinJavascriptMetadataUtils.loadMetadata(filePath);
            if (metadataList.isEmpty()) {
                report.invoke("'" + path + "' is not a valid Kotlin Javascript library");
                return true;
            }

            for (KotlinJavascriptMetadata metadata : metadataList) {
                if (!metadata.getVersion().isCompatible()) {
                    report.invoke("File '" + path + "' was compiled with an incompatible version of Kotlin. " +
                                  "The binary version of its metadata is " + metadata.getVersion() +
                                  ", expected version is " + JsMetadataVersion.INSTANCE);
                    return true;
                }
            }

            if (action != null) {
                action.invoke(file);
            }
        }

        return false;
    }
}
