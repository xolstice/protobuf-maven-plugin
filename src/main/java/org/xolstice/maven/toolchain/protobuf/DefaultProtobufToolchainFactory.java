package org.xolstice.maven.toolchain.protobuf;

/*
 * Copyright (c) 2016 Maven Protocol Buffers Plugin Authors. All rights reserved.
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

import org.apache.maven.toolchain.MisconfiguredToolchainException;
import org.apache.maven.toolchain.RequirementMatcher;
import org.apache.maven.toolchain.RequirementMatcherFactory;
import org.apache.maven.toolchain.ToolchainFactory;
import org.apache.maven.toolchain.ToolchainPrivate;
import org.apache.maven.toolchain.model.ToolchainModel;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.util.Map;
import java.util.Properties;

/**
 * Based on {@code org.apache.maven.toolchain.java.DefaultJavaToolchainFactory}.
 *
 * @since 0.2.0
 */
@Component(
        role = ToolchainFactory.class,
        hint = "protobuf",
        description = "A default factory for 'protobuf' toolchains")
public class DefaultProtobufToolchainFactory implements ToolchainFactory {

    @Requirement
    private Logger logger;

    @Override
    public ToolchainPrivate createToolchain(final ToolchainModel model) throws MisconfiguredToolchainException {
        if (model == null) {
            return null;
        }
        final DefaultProtobufToolchain toolchain = new DefaultProtobufToolchain(model, logger);

        // populate the configuration section
        final Properties configuration = toProperties((Xpp3Dom) model.getConfiguration());
        final String protocExecutable = configuration.getProperty(DefaultProtobufToolchain.KEY_PROTOC_EXECUTABLE);
        if (protocExecutable == null) {
            throw new MisconfiguredToolchainException(
                    "Protobuf toolchain without the "
                            + DefaultProtobufToolchain.KEY_PROTOC_EXECUTABLE
                            + " configuration element.");
        }
        final String normalizedProtocExecutablePath = FileUtils.normalize(protocExecutable);
        final File protocExecutableFile = new File(normalizedProtocExecutablePath);
        if (protocExecutableFile.exists()) {
            toolchain.setProtocExecutable(normalizedProtocExecutablePath);
        } else {
            throw new MisconfiguredToolchainException(
                    "Non-existing protoc executable at " + protocExecutableFile.getAbsolutePath());
        }

        // populate the provides section
        final Properties provides = model.getProvides();
        for (final Map.Entry<Object, Object> provide : provides.entrySet()) {
            final String key = (String) provide.getKey();
            final String value = (String) provide.getValue();

            if (value == null) {
                throw new MisconfiguredToolchainException(
                        "Provides token '" + key + "' doesn't have any value configured.");
            }

            final RequirementMatcher matcher;
            if ("version".equals(key)) {
                matcher = RequirementMatcherFactory.createVersionMatcher(value);
            } else {
                matcher = RequirementMatcherFactory.createExactMatcher(value);
            }
            toolchain.addProvideToken(key, matcher);
        }

        return toolchain;
    }

    @Override
    public ToolchainPrivate createDefaultToolchain() {
        return null;
    }

    protected static Properties toProperties(final Xpp3Dom dom) {
        final Properties props = new Properties();
        final Xpp3Dom[] children = dom.getChildren();
        for (final Xpp3Dom child : children) {
            props.setProperty(child.getName(), child.getValue());
        }
        return props;
    }
}
