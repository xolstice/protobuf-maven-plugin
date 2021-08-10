package org.xolstice.maven.plugin.protobuf;

/*
 * Copyright (c) 2019 Maven Protocol Buffers Plugin Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;

/**
 * This mojo executes the {@code protoc} compiler for generating main Java sources
 * from protocol buffer definitions. It also searches dependency artifacts for
 * {@code .proto} files and includes them in the {@code proto_path} so that they can be
 * referenced. Finally, it adds the {@code .proto} files to the project as resources so
 * that they are included in the final artifact.
 */
@Mojo(
        name = "compile",
        defaultPhase = LifecyclePhase.GENERATE_SOURCES,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        threadSafe = true
)
public final class ProtocCompileMojo extends AbstractProtocCompileMojo {

    /**
     * This is the directory into which the {@code .java} will be created.
     */
    @Parameter(
            required = true,
            property = "javaOutputDirectory",
            defaultValue = "${project.build.directory}/generated-sources/protobuf/java"
    )
    private File outputDirectory;

    /**
     * Additional comma-separated options to be passed to the Java generator.
     * <b>Cannot</b> contain colon (<tt>:</tt>) symbols.
     *
     * @since 0.7.0
     */
    @Parameter(
            required = false,
            property = "javaOptions"
    )
    private String javaOptions;


    @Parameter(
            required = false,
            property = "skipJava",
            defaultValue = "false"
    )
    private Boolean skipJava;

    @Override
    protected void addProtocBuilderParameters(final Protoc.Builder protocBuilder) {
        super.addProtocBuilderParameters(protocBuilder);
        if (javaOptions != null) {
            protocBuilder.setNativePluginParameter(javaOptions);
        }
        protocBuilder
                .setSkipJava(skipJava)
                .setJavaOutputDirectory(getOutputDirectory());
    }

    @Override
    protected File getOutputDirectory() {
        return outputDirectory;
    }
}
