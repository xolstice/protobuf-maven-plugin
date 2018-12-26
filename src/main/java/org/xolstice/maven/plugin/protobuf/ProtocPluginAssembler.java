package org.xolstice.maven.plugin.protobuf;

/*
 * Copyright (c) 2018 Maven Protocol Buffers Plugin Authors. All rights reserved.
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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ResolutionErrorHandler;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.Os;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static java.util.Collections.emptyMap;

/**
 * Creates an executable {@code protoc} plugin (written in Java) from a {@link ProtocPlugin} specification.
 *
 * @since 0.3.0
 */
public class ProtocPluginAssembler {

    private final ProtocPlugin pluginDefinition;

    private final MavenSession session;

    private final Artifact rootResolutionArtifact;

    private final ArtifactFactory artifactFactory;

    private final RepositorySystem repositorySystem;

    private final ResolutionErrorHandler resolutionErrorHandler;

    private final ArtifactRepository localRepository;

    private final List<ArtifactRepository> remoteRepositories;

    private final File pluginDirectory;

    private final List<File> resolvedJars = new ArrayList<>();

    private final File pluginExecutableFile;

    private final Log log;

    public ProtocPluginAssembler(
            final ProtocPlugin pluginDefinition,
            final MavenSession session,
            final Artifact rootResolutionArtifact,
            final ArtifactFactory artifactFactory,
            final RepositorySystem repositorySystem,
            final ResolutionErrorHandler resolutionErrorHandler,
            final ArtifactRepository localRepository,
            final List<ArtifactRepository> remoteRepositories,
            final File pluginDirectory,
            final Log log) {
        this.pluginDefinition = pluginDefinition;
        this.session = session;
        this.rootResolutionArtifact = rootResolutionArtifact;
        this.artifactFactory = artifactFactory;
        this.repositorySystem = repositorySystem;
        this.resolutionErrorHandler = resolutionErrorHandler;
        this.localRepository = localRepository;
        this.remoteRepositories = remoteRepositories;
        this.pluginDirectory = pluginDirectory;
        this.pluginExecutableFile = pluginDefinition.getPluginExecutableFile(pluginDirectory);
        this.log = log;
    }

    /**
     * Resolves the plugin's dependencies to the local Maven repository and builds the plugin executable.
     */
    public void execute() {
        pluginDefinition.validate(log);

        if (log.isDebugEnabled()) {
            log.debug("plugin definition: " + pluginDefinition);
        }

        resolvePluginDependencies();

        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
            buildWindowsPlugin();
            copyWinRun4JExecutable();
        } else {
            buildUnixPlugin();
            pluginExecutableFile.setExecutable(true);
        }
    }

    private void buildWindowsPlugin() {
        createPluginDirectory();

        // Try to locate jvm.dll based on pluginDefinition's javaHome property
        final File javaHome = new File(pluginDefinition.getJavaHome());
        final File jvmLocation = findJvmLocation(javaHome,
                "jre/bin/server/jvm.dll",
                "bin/server/jvm.dll",
                "jre/bin/client/jvm.dll",
                "bin/client/jvm.dll");
        final File winRun4JIniFile = new File(pluginDirectory, pluginDefinition.getPluginName() + ".ini");

        if (log.isDebugEnabled()) {
            log.debug("javaHome=" + javaHome.getAbsolutePath());
            log.debug("jvmLocation=" + (jvmLocation != null ? jvmLocation.getAbsolutePath() : "(none)"));
            log.debug("winRun4JIniFile=" + winRun4JIniFile.getAbsolutePath());
            log.debug("winJvmDataModel=" + pluginDefinition.getWinJvmDataModel());
        }

        try (final PrintWriter out = new PrintWriter(new FileWriter(winRun4JIniFile))) {
            if (jvmLocation != null) {
                out.println("vm.location=" + jvmLocation.getAbsolutePath());
            }
            int index = 1;
            for (final File resolvedJar : resolvedJars) {
                out.println("classpath." + index + "=" + resolvedJar.getAbsolutePath());
                index++;
            }
            out.println("main.class=" + pluginDefinition.getMainClass());

            index = 1;
            for (final String arg : pluginDefinition.getArgs()) {
                out.println("arg." + index + "=" + arg);
                index++;
            }

            index = 1;
            for (final String jvmArg : pluginDefinition.getJvmArgs()) {
                out.println("vmarg." + index + "=" + jvmArg);
                index++;
            }

            out.println("vm.version.min=1.8");

            // keep from logging to stdout (the default)
            out.println("log.level=none");
            out.println("[ErrorMessages]");
            out.println("show.popup=false");
        } catch (IOException e) {
            throw new MojoInitializationException(
                    "Could not write WinRun4J ini file: " + winRun4JIniFile.getAbsolutePath(), e);
        }
    }

    private static File findJvmLocation(final File javaHome, final String... paths) {
        for (final String path : paths) {
            final File jvmLocation = new File(javaHome, path);
            if (jvmLocation.isFile()) {
                return jvmLocation;
            }
        }
        return null;
    }

    private void copyWinRun4JExecutable() {
        final String executablePath = getWinrun4jExecutablePath();
        final URL url = Thread.currentThread().getContextClassLoader().getResource(executablePath);
        if (url == null) {
            throw new MojoInitializationException(
                    "Could not locate WinRun4J executable at path: " + executablePath);
        }
        try {
            FileUtils.copyURLToFile(url, pluginExecutableFile);
        } catch (IOException e) {
            throw new MojoInitializationException(
                    "Could not copy WinRun4J executable to: " + pluginExecutableFile.getAbsolutePath(), e);
        }
    }

    private void buildUnixPlugin() {
        createPluginDirectory();

        final File javaLocation = new File(pluginDefinition.getJavaHome(), "bin/java");

        if (log.isDebugEnabled()) {
            log.debug("javaLocation=" + javaLocation.getAbsolutePath());
        }

        try (final PrintWriter out = new PrintWriter(new FileWriter(pluginExecutableFile))) {
            out.println("#!/bin/sh");
            out.println();
            out.print("CP=");
            for (int i = 0; i < resolvedJars.size(); i++) {
                if (i > 0) {
                    out.print(":");
                }
                out.print("\"" + resolvedJars.get(i).getAbsolutePath() + "\"");
            }
            out.println();
            out.print("ARGS=\"");
            for (final String arg : pluginDefinition.getArgs()) {
                out.print(arg + " ");
            }
            out.println("\"");
            out.print("JVMARGS=\"");
            for (final String jvmArg : pluginDefinition.getJvmArgs()) {
                out.print(jvmArg + " ");
            }
            out.println("\"");
            out.println();
            out.println("\"" + javaLocation.getAbsolutePath() + "\" $JVMARGS -cp $CP "
                    + pluginDefinition.getMainClass() + " $ARGS");
            out.println();
        } catch (IOException e) {
            throw new MojoInitializationException("Could not write plugin script file: " + pluginExecutableFile, e);
        }
    }

    private void createPluginDirectory() {
        pluginDirectory.mkdirs();
        if (!pluginDirectory.isDirectory()) {
            throw new MojoInitializationException("Could not create protoc plugin directory: "
                    + pluginDirectory.getAbsolutePath());
        }
    }

    private void resolvePluginDependencies() {

        final VersionRange versionSpec;
        try {
            versionSpec = VersionRange.createFromVersionSpec(pluginDefinition.getVersion());
        } catch (InvalidVersionSpecificationException e) {
            throw new MojoConfigurationException("Invalid plugin version specification", e);
        }
        final Artifact protocPluginArtifact =
                artifactFactory.createDependencyArtifact(
                        pluginDefinition.getGroupId(),
                        pluginDefinition.getArtifactId(),
                        versionSpec,
                        "jar",
                        pluginDefinition.getClassifier(),
                        Artifact.SCOPE_RUNTIME);

        final ArtifactResolutionRequest request = new ArtifactResolutionRequest()
                .setArtifact(rootResolutionArtifact)
                .setResolveRoot(false)
                .setArtifactDependencies(Collections.singleton(protocPluginArtifact))
                .setManagedVersionMap(emptyMap())
                .setLocalRepository(localRepository)
                .setRemoteRepositories(remoteRepositories)
                .setOffline(session.isOffline())
                .setForceUpdate(session.getRequest().isUpdateSnapshots())
                .setServers(session.getRequest().getServers())
                .setMirrors(session.getRequest().getMirrors())
                .setProxies(session.getRequest().getProxies());

        final ArtifactResolutionResult result = repositorySystem.resolve(request);

        try {
            resolutionErrorHandler.throwErrors(request, result);
        } catch (ArtifactResolutionException e) {
            throw new MojoInitializationException("Unable to resolve plugin artifact: " + e.getMessage(), e);
        }

        final Set<Artifact> artifacts = result.getArtifacts();

        if (artifacts == null || artifacts.isEmpty()) {
            throw new MojoInitializationException("Unable to resolve plugin artifact");
        }

        for (final Artifact artifact : artifacts) {
            resolvedJars.add(artifact.getFile());
        }

        if (log.isDebugEnabled()) {
            log.debug("Resolved jars: " + resolvedJars);
        }
    }

    private String getWinrun4jExecutablePath() {
        return "winrun4j/WinRun4J" + pluginDefinition.getWinJvmDataModel() + ".exe";
    }
}
