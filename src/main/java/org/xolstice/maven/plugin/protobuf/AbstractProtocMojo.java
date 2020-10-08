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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.resolver.ResolutionErrorHandler;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.apache.maven.toolchain.java.DefaultJavaToolChain;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.Os;
import org.codehaus.plexus.util.SelectorUtils;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.io.RawInputStreamFacade;
import org.sonatype.plexus.build.incremental.BuildContext;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static java.lang.Math.max;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singleton;
import static org.codehaus.plexus.util.FileUtils.cleanDirectory;
import static org.codehaus.plexus.util.FileUtils.copyStreamToFile;
import static org.codehaus.plexus.util.FileUtils.getDefaultExcludesAsString;
import static org.codehaus.plexus.util.FileUtils.getFileNames;
import static org.codehaus.plexus.util.FileUtils.getFiles;
import static org.codehaus.plexus.util.StringUtils.join;

/**
 * Abstract Mojo implementation.
 *
 * <p>This class is extended by {@link ProtocCompileMojo} and
 * {@link ProtocTestCompileMojo} in order to override the specific configuration for
 * compiling the main or test classes respectively.</p>
 */
abstract class AbstractProtocMojo extends AbstractMojo {

    private static final String DEFAULT_INCLUDES = "**/*.proto*";

    /**
     * The current Maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    /**
     * The current Maven Session Object.
     *
     * @since 0.2.0
     */
    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession session;

    /**
     * Build context that tracks changes to the source and target files.
     *
     * @since 0.3.0
     */
    @Component
    protected BuildContext buildContext;

    /**
     * An optional tool chain manager.
     *
     * @since 0.2.0
     */
    @Component
    protected ToolchainManager toolchainManager;

    /**
     * A helper used to add resources to the project.
     */
    @Component
    protected MavenProjectHelper projectHelper;

    /**
     * A factory for Maven artifact definitions.
     *
     * @since 0.3.1
     */
    @Component
    private ArtifactFactory artifactFactory;

    /**
     * A component that implements resolution of Maven artifacts from repositories.
     *
     * @since 0.3.1
     */
    @Component
    private ArtifactResolver artifactResolver;

    /**
     * A component that handles resolution of Maven artifacts.
     *
     * @since 0.4.0
     */
    @Component
    private RepositorySystem repositorySystem;

    /**
     * A component that handles resolution errors.
     *
     * @since 0.4.0
     */
    @Component
    private ResolutionErrorHandler resolutionErrorHandler;

    /**
     * This is the path to the local maven {@code repository}.
     */
    @Parameter(
            required = true,
            readonly = true,
            property = "localRepository"
    )
    private ArtifactRepository localRepository;

    /**
     * Remote repositories for artifact resolution.
     *
     * @since 0.3.0
     */
    @Parameter(
            required = true,
            readonly = true,
            defaultValue = "${project.remoteArtifactRepositories}"
    )
    private List<ArtifactRepository> remoteRepositories;

    /**
     * A directory where temporary files will be generated.
     *
     * @since 0.6.0
     */
    @Parameter(
            required = true,
            readonly = true,
            defaultValue = "${project.build.directory}"
    )
    private File tempDirectory;

    /**
     * A directory where native launchers for java protoc plugins will be generated.
     *
     * @since 0.3.0
     */
    @Parameter(
            required = false,
            defaultValue = "${project.build.directory}/protoc-plugins"
    )
    private File protocPluginDirectory;

    /**
     * This is the path to the {@code protoc} executable.
     * When this parameter is not set, the plugin attempts to load
     * a {@code protobuf} toolchain and use it locate {@code protoc} executable.
     * If no {@code protobuf} toolchain is defined in the project,
     * the {@code protoc} executable in the {@code PATH} is used.
     */
    @Parameter(
            required = false,
            property = "protocExecutable"
    )
    private String protocExecutable;

    /**
     * Protobuf compiler artifact specification, in {@code groupId:artifactId:version[:type[:classifier]]} format.
     * When this parameter is set, the plugin attempts to resolve the specified artifact as {@code protoc} executable.
     *
     * @since 0.4.1
     */
    @Parameter(
            required = false,
            property = "protocArtifact"
    )
    private String protocArtifact;

    /**
     * Additional source paths for {@code .proto} definitions.
     */
    @Parameter(
            required = false
    )
    private File[] additionalProtoPathElements = {};

    /**
     * Since {@code protoc} cannot access jars, proto files in dependencies are extracted to this location
     * and deleted on exit. This directory is always cleaned during execution.
     */
    @Parameter(
            required = true,
            defaultValue = "${project.build.directory}/protoc-dependencies"
    )
    private File temporaryProtoFileDirectory;

    /**
     * Set this to {@code false} to disable hashing of dependent jar paths.
     * <p/>
     * This plugin expands jars on the classpath looking for embedded {@code .proto} files.
     * Normally these paths are hashed (MD5) to avoid issues with long file names on windows.
     * However if this property is set to {@code false} longer paths will be used.
     */
    @Parameter(
            required = true,
            defaultValue = "true"
    )
    private boolean hashDependentPaths;

    /**
     * A list of &lt;include&gt; elements specifying the protobuf definition files (by pattern)
     * that should be included in compilation.
     * When not specified, the default includes will be:
     * <code><br/>
     * &lt;includes&gt;<br/>
     * &nbsp;&lt;include&gt;**&#47;*.proto&lt;/include&gt;<br/>
     * &lt;/includes&gt;<br/>
     * </code>
     */
    @Parameter(
            required = false
    )
    private String[] includes = {DEFAULT_INCLUDES};

    /**
     * A list of &lt;exclude&gt; elements specifying the protobuf definition files (by pattern)
     * that should be excluded from compilation.
     * When not specified, the default excludes will be empty:
     * <code><br/>
     * &lt;excludes&gt;<br/>
     * &lt;/excludes&gt;<br/>
     * </code>
     */
    @Parameter(
            required = false
    )
    private String[] excludes = {};

    /**
     * If set to {@code true}, then the specified protobuf source files from this project will be attached
     * as resources to the build, for subsequent inclusion into the final artifact.
     * This is the default behaviour, as it allows downstream projects to import protobuf definitions
     * from the upstream projects, and those imports are automatically resolved at build time.
     *
     * <p>If distribution of {@code .proto} source files is undesirable for security reasons
     * or because of other considerations, then this parameter should be set to {@code false}.</p>
     *
     * @since 0.4.1
     */
    @Parameter(
            required = true,
            defaultValue = "true"
    )
    protected boolean attachProtoSources;

    /**
     * If set to {@code true}, all command line arguments to protoc will be written to a file,
     * and only a path to that file will be passed to protoc on the command line.
     * This helps prevent <i>Command line is too long</i> errors when the number of {@code .proto} files is large.
     *
     * <p><b>NOTE:</b> This is only supported for protoc 3.5.0 and higher.</p>
     *
     * @since 0.6.0
     */
    @Parameter(
            required = false,
            defaultValue = "false"
    )
    protected boolean useArgumentFile;

    /**
     * If set to {@code true}, experimental optional feature will be enabled.
     *
     * <p><b>NOTE:</b> This is only supported for protoc 3.12.0 and higher.</p>
     *
     * @since 0.7.0
     */
    @Parameter(
            required = false,
            defaultValue = "false"
    )
    protected boolean useExperimentalOptional;

    /**
     * Specifies one of more custom protoc plugins, written in Java
     * and available as Maven artifacts. An executable plugin will be created
     * at execution time. On UNIX the executable is a shell script and on
     * Windows it is a WinRun4J .exe and .ini.
     */
    @Parameter(
            required = false
    )
    private List<ProtocPlugin> protocPlugins;

    /**
     * Sets the granularity in milliseconds of the last modification date
     * for testing whether source protobuf definitions need recompilation.
     *
     * <p>This parameter is only used when {@link #checkStaleness} parameter is set to {@code true}.
     *
     * <p>If the project is built on NFS it's recommended to set this parameter to {@code 10000}.
     */
    @Parameter(
            required = false,
            defaultValue = "0"
    )
    private long staleMillis;

    /**
     * Normally {@code protoc} is invoked on every execution of the plugin.
     * Setting this parameter to {@code true} will enable checking
     * timestamps of source protobuf definitions vs. generated sources.
     *
     * @see #staleMillis
     */
    @Parameter(
            required = false,
            defaultValue = "false"
    )
    private boolean checkStaleness;

    /**
     * When {@code true}, skip the execution.
     *
     * @since 0.2.0
     */
    @Parameter(
            required = false,
            property = "protoc.skip",
            defaultValue = "false"
    )
    private boolean skip;

    /**
     * Usually most of protobuf mojos will not get executed on parent poms
     * (i.e. projects with packaging type 'pom').
     * Setting this parameter to {@code true} will force
     * the execution of this mojo, even if it would usually get skipped in this case.
     *
     * @since 0.2.0
     */
    @Parameter(
            required = false,
            property = "protoc.force",
            defaultValue = "false"
    )
    private boolean forceMojoExecution;

    /**
     * When {@code true}, the output directory will be cleared out prior to code generation.
     * With the latest versions of protoc (2.5.0 or later) this is generally not required,
     * although some earlier versions reportedly had issues with running
     * two code generations in a row without clearing out the output directory in between.
     *
     * @since 0.4.0
     */
    @Parameter(
            required = false,
            property = "protoc.clearOutputDirectory",
            defaultValue = "false"
    )
    private boolean clearOutputDirectory;

    /**
     * Executes the mojo.
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        if (skipMojo()) {
            return;
        }

        try {
            checkParameters();
        } catch (final MojoConfigurationException e) {
            throw new MojoExecutionException("Configuration error: " + e.getMessage(), e);
        } catch (final MojoInitializationException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        final File protoSourceRoot = getProtoSourceRoot();
        if (protoSourceRoot.exists()) {
            try {
                final List<File> protoFiles = findProtoFilesInDirectory(protoSourceRoot);
                final File outputDirectory = getOutputDirectory();
                final List<File> outputFiles = findGeneratedFilesInDirectory(getOutputDirectory());

                if (protoFiles.isEmpty()) {
                    getLog().info("No proto files to compile.");
                } else if (!hasDelta(protoFiles)) {
                    getLog().info("Skipping compilation because build context has no changes.");
                    doAttachFiles();
                } else if (checkStaleness && checkFilesUpToDate(protoFiles, outputFiles)) {
                    getLog().info("Skipping compilation because target directory newer than sources.");
                    doAttachFiles();
                } else {
                    final List<File> derivedProtoPathElements =
                            makeProtoPathFromJars(temporaryProtoFileDirectory, getDependencyArtifactFiles());
                    FileUtils.mkdir(outputDirectory.getAbsolutePath());

                    if (clearOutputDirectory) {
                        try {
                            cleanDirectory(outputDirectory);
                        } catch (final IOException e) {
                            throw new MojoInitializationException("Unable to clean output directory", e);
                        }
                    }

                    createProtocPlugins();

                    //get toolchain from context
                    final Toolchain tc = toolchainManager.getToolchainFromBuildContext("protobuf", session); //NOI18N
                    if (tc != null) {
                        getLog().info("Toolchain in protobuf-maven-plugin: " + tc);
                        //when the executable to use is explicitly set by user in mojo's parameter, ignore toolchains.
                        if (protocExecutable != null) {
                            getLog().warn(
                                    "Toolchains are ignored, 'protocExecutable' parameter is set to " + protocExecutable);
                        } else {
                            //assign the path to executable from toolchains
                            protocExecutable = tc.findTool("protoc"); //NOI18N
                        }
                    }
                    if (protocExecutable == null && protocArtifact != null) {
                        final Artifact artifact = createDependencyArtifact(protocArtifact);
                        final File file = resolveBinaryArtifact(artifact);
                        protocExecutable = file.getAbsolutePath();
                    }
                    if (protocExecutable == null) {
                        // Try to fall back to 'protoc' in $PATH
                        getLog().warn("No 'protocExecutable' parameter is configured, using the default: 'protoc'");
                        protocExecutable = "protoc";
                    }

                    final Protoc.Builder protocBuilder =
                            new Protoc.Builder(protocExecutable)
                                    .addProtoPathElement(protoSourceRoot)
                                    .addProtoPathElements(derivedProtoPathElements)
                                    .addProtoPathElements(asList(additionalProtoPathElements))
                                    .addProtoFiles(protoFiles);
                    addProtocBuilderParameters(protocBuilder);
                    final Protoc protoc = protocBuilder.build();

                    if (getLog().isDebugEnabled()) {
                        getLog().debug("Proto source root:");
                        getLog().debug(" " + protoSourceRoot);

                        if (derivedProtoPathElements != null && !derivedProtoPathElements.isEmpty()) {
                            getLog().debug("Derived proto paths:");
                            for (final File path : derivedProtoPathElements) {
                                getLog().debug(" " + path);
                            }
                        }

                        if (additionalProtoPathElements != null && additionalProtoPathElements.length > 0) {
                            getLog().debug("Additional proto paths:");
                            for (final File path : additionalProtoPathElements) {
                                getLog().debug(" " + path);
                            }
                        }
                    }
                    protoc.logExecutionParameters(getLog());

                    getLog().info(format("Compiling %d proto file(s) to %s", protoFiles.size(), outputDirectory));

                    final int exitStatus = protoc.execute(getLog());
                    if (StringUtils.isNotBlank(protoc.getOutput())) {
                        getLog().info("PROTOC: " + protoc.getOutput());
                    }
                    if (exitStatus != 0) {
                        getLog().error("PROTOC FAILED: " + protoc.getError());
                        for (File pf : protoFiles) {
                            buildContext.removeMessages(pf);
                            buildContext.addMessage(pf, 0, 0, protoc.getError(), BuildContext.SEVERITY_ERROR, null);
                        }
                        throw new MojoFailureException(
                                "protoc did not exit cleanly. Review output for more information.");
                    } else if (StringUtils.isNotBlank(protoc.getError())) {
                        getLog().warn("PROTOC: " + protoc.getError());
                    }
                    doAttachFiles();
                }
            } catch (final MojoConfigurationException e) {
                throw new MojoExecutionException("Configuration error: " + e.getMessage(), e);
            } catch (final MojoInitializationException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            } catch (final CommandLineException e) {
                throw new MojoExecutionException("An error occurred while invoking protoc: " + e.getMessage(), e);
            } catch (final InterruptedException e) {
                getLog().info("Process interrupted");
            }
        } else {
            getLog().info(format("%s does not exist. Review the configuration or consider disabling the plugin.",
                    protoSourceRoot));
        }
    }

    /**
     * Generates native launchers for java protoc plugins.
     * These launchers will later be added as parameters for protoc compiler.
     *
     * @since 0.3.0
     */
    protected void createProtocPlugins() {
        if (protocPlugins == null) {
            return;
        }
        final String javaHome = detectJavaHome();

        for (final ProtocPlugin plugin : protocPlugins) {

            if (plugin.getJavaHome() != null) {
                getLog().debug("Using javaHome defined in plugin definition: " + plugin.getJavaHome());
            } else {
                getLog().debug("Setting javaHome for plugin: " + javaHome);
                plugin.setJavaHome(javaHome);
            }

            getLog().info("Building protoc plugin: " + plugin.getId());
            final ProtocPluginAssembler assembler = new ProtocPluginAssembler(
                    plugin,
                    session,
                    project.getArtifact(),
                    artifactFactory,
                    repositorySystem,
                    resolutionErrorHandler,
                    localRepository,
                    remoteRepositories,
                    protocPluginDirectory,
                    getLog());
            assembler.execute();
        }
    }

    /**
     * Attempts to detect java home directory, using {@code jdk} toolchain if available,
     * with a fallback to {@code java.home} system property.
     *
     * @return path to java home directory.
     *
     * @since 0.3.0
     */
    protected String detectJavaHome() {
        String javaHome = null;

        final Toolchain tc = toolchainManager.getToolchainFromBuildContext("jdk", session);
        if (tc != null) {
            if (tc instanceof DefaultJavaToolChain) {
                javaHome = ((DefaultJavaToolChain) tc).getJavaHome();
                if (javaHome != null) {
                    getLog().debug("Using javaHome from toolchain: " + javaHome);
                }
            } else {
                // Try to infer JAVA_HOME from location of 'java' tool in toolchain, if available.
                // We don't use 'java' directly because for Windows we need to find the path to
                // jvm.dll instead, which the assembler tries to figure out relative to JAVA_HOME.
                final String javaExecutable = tc.findTool("java");
                if (javaExecutable != null) {
                    File parent = new File(javaExecutable).getParentFile();
                    if (parent != null) {
                        parent = parent.getParentFile();
                        if (parent != null && parent.isDirectory()) {
                            javaHome = parent.getAbsolutePath();
                            getLog().debug(
                                    "Using javaHome based on 'java' location returned by toolchain: " + javaHome);
                        }
                    }
                }
            }
        }
        if (javaHome == null) {
            // Default location is the current JVM's JAVA_HOME.
            javaHome = System.getProperty("java.home");
            getLog().debug("Using javaHome from java.home system property: " + javaHome);
        }
        return javaHome;
    }

    /**
     * Adds mojo-specific parameters to the protoc builder.
     *
     * @param protocBuilder the builder to be modified.
     */
    protected void addProtocBuilderParameters(final Protoc.Builder protocBuilder) {
        if (protocPlugins != null) {
            for (final ProtocPlugin plugin : protocPlugins) {
                protocBuilder.addPlugin(plugin);
            }
            protocPluginDirectory.mkdirs();
            protocBuilder.setPluginDirectory(protocPluginDirectory);
        }
        protocBuilder.setTempDirectory(tempDirectory);
        protocBuilder.useArgumentFile(useArgumentFile);
        protocBuilder.useExperimentalOptional(useExperimentalOptional);
    }

    /**
     * <p>Determine if the mojo execution should get skipped.</p>
     * This is the case if:
     * <ul>
     * <li>{@link #skip} is <code>true</code></li>
     * <li>if the mojo gets executed on a project with packaging type 'pom' and
     * {@link #forceMojoExecution} is <code>false</code></li>
     * </ul>
     *
     * @return <code>true</code> if the mojo execution should be skipped.
     *
     * @since 0.2.0
     */
    protected boolean skipMojo() {
        if (skip) {
            getLog().info("Skipping mojo execution");
            return true;
        }

        if (!forceMojoExecution && "pom".equals(this.project.getPackaging())) {
            getLog().info("Skipping mojo execution for project with packaging type 'pom'");
            return true;
        }

        return false;
    }

    protected static List<File> findGeneratedFilesInDirectory(final File directory) {
        if (directory == null || !directory.isDirectory()) {
            return emptyList();
        }

        final List<File> generatedFilesInDirectory;
        try {
            generatedFilesInDirectory = getFiles(directory, "**/*", getDefaultExcludesAsString());
        } catch (final IOException e) {
            throw new MojoInitializationException("Unable to scan output directory", e);
        }
        return generatedFilesInDirectory;
    }

    /**
     * Returns timestamp for the most recently modified file in the given set.
     *
     * @param files a collection of file descriptors.
     * @return timestamp of the most recently modified file.
     */
    protected static long lastModified(final Iterable<File> files) {
        long result = 0L;
        for (final File file : files) {
            result = max(result, file.lastModified());
        }
        return result;
    }

    /**
     * Checks that the source files don't have modification time that is later than the target files.
     *
     * @param sourceFiles a collection of source files.
     * @param targetFiles a collection of target files.
     * @return {@code true}, if source files are not later than the target files; {@code false}, otherwise.
     */
    protected boolean checkFilesUpToDate(final Iterable<File> sourceFiles, final Iterable<File> targetFiles) {
        return lastModified(sourceFiles) + staleMillis < lastModified(targetFiles);
    }

    /**
     * Checks if the injected build context has changes in any of the specified files.
     *
     * @param files files to be checked for changes.
     * @return {@code true}, if at least one file has changes; {@code false}, if no files have changes.
     *
     * @since 0.3.0
     */
    protected boolean hasDelta(final Iterable<File> files) {
        for (final File file : files) {
            if (buildContext.hasDelta(file)) {
                return true;
            }
        }
        return false;
    }

    protected void checkParameters() {
        if (project == null) {
            throw new MojoConfigurationException("'project' is null");
        }
        if (projectHelper == null) {
            throw new MojoConfigurationException("'projectHelper' is null");
        }
        final File protoSourceRoot = getProtoSourceRoot();
        if (protoSourceRoot == null) {
            throw new MojoConfigurationException("'protoSourceRoot' is null");
        }
        if (protoSourceRoot.isFile()) {
            throw new MojoConfigurationException("'protoSourceRoot' is a file, not a directory");
        }
        if (temporaryProtoFileDirectory == null) {
            throw new MojoConfigurationException("'temporaryProtoFileDirectory' is null");
        }
        if (temporaryProtoFileDirectory.isFile()) {
            throw new MojoConfigurationException("'temporaryProtoFileDirectory' is a file, not a directory");
        }
        final File outputDirectory = getOutputDirectory();
        if (outputDirectory == null) {
            throw new MojoConfigurationException("'outputDirectory' is null");
        }
        if (outputDirectory.isFile()) {
            throw new MojoConfigurationException("'outputDirectory' is a file, not a directory");
        }
    }

    protected abstract File getProtoSourceRoot();

    protected String[] getIncludes() {
        return includes;
    }

    protected String[] getExcludes() {
        return excludes;
    }

    // TODO add artifact filtering (inclusions and exclusions)
    // TODO add filtering for proto definitions in included artifacts
    protected abstract List<Artifact> getDependencyArtifacts();

    /**
     * Returns the output directory for generated sources. Depends on build phase so must
     * be defined in concrete implementation.
     *
     * @return output directory for generated sources.
     */
    protected abstract File getOutputDirectory();

    protected void doAttachFiles() {
        if (attachProtoSources) {
            doAttachProtoSources();
        }
        doAttachGeneratedFiles();
    }

    protected abstract void doAttachProtoSources();

    protected abstract void doAttachGeneratedFiles();

    /**
     * Gets the {@link File} for each dependency artifact.
     *
     * @return A list of all dependency artifacts.
     */
    protected List<File> getDependencyArtifactFiles() {
        final List<Artifact> dependencyArtifacts = getDependencyArtifacts();
        if (dependencyArtifacts.isEmpty()) {
            return emptyList();
        }
        final List<File> dependencyArtifactFiles = new ArrayList<>(dependencyArtifacts.size());
        for (final Artifact artifact : dependencyArtifacts) {
            dependencyArtifactFiles.add(artifact.getFile());
        }
        return dependencyArtifactFiles;
    }

    /**
     * Unpacks proto descriptors that are bundled inside dependent artifacts into a temporary directory.
     * This is needed because protobuf compiler cannot handle imported descriptors that are packed inside jar files.
     *
     * @param temporaryProtoFileDirectory temporary directory to serve as root for unpacked structure.
     * @param classpathElementFiles classpath elements, can be either jar files or directories.
     * @return a list of import roots for protobuf compiler
     *         (these will all be subdirectories of the temporary directory).
     */
    protected List<File> makeProtoPathFromJars(
            final File temporaryProtoFileDirectory,
            final Iterable<File> classpathElementFiles
    ) {
        if (classpathElementFiles == null) {
            throw new MojoConfigurationException("'classpathElementFiles' is null");
        }
        if (!classpathElementFiles.iterator().hasNext()) {
            return emptyList();
        }
        // clean the temporary directory to ensure that stale files aren't used
        if (temporaryProtoFileDirectory.exists()) {
            try {
                cleanDirectory(temporaryProtoFileDirectory);
            } catch (IOException e) {
                throw new MojoInitializationException("Unable to clean up temporary proto file directory", e);
            }
        }
        final List<File> protoDirectories = new ArrayList<>();
        for (final File classpathElementFile : classpathElementFiles) {
            // for some reason under IAM, we receive poms as dependent files
            // I am excluding .xml rather than including .jar as there may be other extensions in use (sar, har, zip)
            if (classpathElementFile.isFile() && classpathElementFile.canRead() &&
                    !classpathElementFile.getName().endsWith(".xml")) {

                // create the jar file. the constructor validates.
                try (final JarFile classpathJar = new JarFile(classpathElementFile)) {
                    final Enumeration<JarEntry> jarEntries = classpathJar.entries();
                    while (jarEntries.hasMoreElements()) {
                        final JarEntry jarEntry = jarEntries.nextElement();
                        final String jarEntryName = jarEntry.getName();
                        if (!jarEntry.isDirectory() && SelectorUtils.matchPath(DEFAULT_INCLUDES, jarEntryName, "/", true)) {
                            final File jarDirectory;
                            try {
                                jarDirectory = new File(temporaryProtoFileDirectory, truncatePath(classpathJar.getName()));
                                // Check for Zip Slip vulnerability
                                // https://snyk.io/research/zip-slip-vulnerability
                                final String canonicalJarDirectoryPath = jarDirectory.getCanonicalPath();
                                final File uncompressedCopy = new File(jarDirectory, jarEntryName);
                                final String canonicalUncompressedCopyPath = uncompressedCopy.getCanonicalPath();
                                if (!canonicalUncompressedCopyPath.startsWith(canonicalJarDirectoryPath + File.separator)) {
                                    throw new MojoInitializationException(
                                            "ZIP SLIP: Entry " + jarEntry.getName() +
                                                    " in " + classpathJar.getName() + " is outside of the target dir");
                                }
                                FileUtils.mkdir(uncompressedCopy.getParentFile().getAbsolutePath());
                                copyStreamToFile(
                                        new RawInputStreamFacade(classpathJar.getInputStream(jarEntry)),
                                        uncompressedCopy);
                            } catch (final IOException e) {
                                throw new MojoInitializationException("Unable to unpack proto files", e);
                            }
                            protoDirectories.add(jarDirectory);
                        }
                    }
                } catch (final IOException e) {
                    throw new MojoInitializationException(
                            "Not a readable JAR artifact: " + classpathElementFile.getAbsolutePath(), e);
                }
            } else if (classpathElementFile.isDirectory()) {
                final List<String> protoFiles;
                try {
                    protoFiles = getFileNames(classpathElementFile, DEFAULT_INCLUDES, null, true);
                } catch (final IOException e) {
                    throw new MojoInitializationException(
                            "Unable to scan for proto files in: " + classpathElementFile.getAbsolutePath(), e);
                }
                if (!protoFiles.isEmpty()) {
                    protoDirectories.add(classpathElementFile);
                }
            }
        }
        return protoDirectories;
    }

    protected List<File> findProtoFilesInDirectory(final File directory) {
        if (directory == null) {
            throw new MojoConfigurationException("'directory' is null");
        }
        if (!directory.isDirectory()) {
            throw new MojoConfigurationException(format("%s is not a directory", directory));
        }
        final List<File> protoFilesInDirectory;
        try {
            final String includes = join(getIncludes(), ",");
            final String excludes = join(getExcludes(), ",");
            protoFilesInDirectory = getFiles(directory, includes, excludes);
        } catch (IOException e) {
            throw new MojoInitializationException("Unable to retrieve the list of files: " + e.getMessage(), e);
        }
        return protoFilesInDirectory;
    }

    protected List<File> findProtoFilesInDirectories(final Iterable<File> directories) {
        if (directories == null) {
            throw new MojoConfigurationException("'directories' is null");
        }
        final List<File> protoFiles = new ArrayList<>();
        for (final File directory : directories) {
            protoFiles.addAll(findProtoFilesInDirectory(directory));
        }
        return protoFiles;
    }

    /**
     * Truncates the path of jar files so that they are relative to the local repository.
     *
     * @param jarPath the full path of a jar file.
     * @return the truncated path relative to the local repository or root of the drive.
     */
    protected String truncatePath(final String jarPath) {

        if (hashDependentPaths) {
            return md5Hash(jarPath);
        }

        String repository = localRepository.getBasedir().replace('\\', '/');
        if (!repository.endsWith("/")) {
            repository += "/";
        }

        String path = jarPath.replace('\\', '/');
        final int repositoryIndex = path.indexOf(repository);
        if (repositoryIndex != -1) {
            path = path.substring(repositoryIndex + repository.length());
        }

        // By now the path should be good, but do a final check to fix windows machines.
        final int colonIndex = path.indexOf(':');
        if (colonIndex != -1) {
            // 2 = :\ in C:\
            path = path.substring(colonIndex + 2);
        }

        return path;
    }

    private static String md5Hash(final String string) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (final NoSuchAlgorithmException e) {
            throw new MojoInitializationException("Unable to create MD5 digest", e);
        }
        final byte[] input = string.getBytes(Charset.forName("UTF-8"));
        return toHexString(digest.digest(input));
    }

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    protected static String toHexString(final byte[] byteArray) {
        final StringBuilder hexString = new StringBuilder(2 * byteArray.length);
        for (final byte b : byteArray) {
            hexString.append(HEX_CHARS[(b & 0xF0) >> 4]).append(HEX_CHARS[b & 0x0F]);
        }
        return hexString.toString();
    }

    protected File resolveBinaryArtifact(final Artifact artifact) {
        final ArtifactResolutionResult result;
        final ArtifactResolutionRequest request = new ArtifactResolutionRequest()
                .setArtifact(project.getArtifact())
                .setResolveRoot(false)
                .setResolveTransitively(false)
                .setArtifactDependencies(singleton(artifact))
                .setManagedVersionMap(emptyMap())
                .setLocalRepository(localRepository)
                .setRemoteRepositories(remoteRepositories)
                .setOffline(session.isOffline())
                .setForceUpdate(session.getRequest().isUpdateSnapshots())
                .setServers(session.getRequest().getServers())
                .setMirrors(session.getRequest().getMirrors())
                .setProxies(session.getRequest().getProxies());

        result = repositorySystem.resolve(request);

        try {
            resolutionErrorHandler.throwErrors(request, result);
        } catch (final ArtifactResolutionException e) {
            throw new MojoInitializationException("Unable to resolve artifact: " + e.getMessage(), e);
        }

        final Set<Artifact> artifacts = result.getArtifacts();

        if (artifacts == null || artifacts.isEmpty()) {
            throw new MojoInitializationException("Unable to resolve artifact");
        }

        final Artifact resolvedBinaryArtifact = artifacts.iterator().next();
        if (getLog().isDebugEnabled()) {
            getLog().debug("Resolved artifact: " + resolvedBinaryArtifact);
        }

        // Copy the file to the project build directory and make it executable
        final File sourceFile = resolvedBinaryArtifact.getFile();
        final String sourceFileName = sourceFile.getName();
        final String targetFileName;
        if (Os.isFamily(Os.FAMILY_WINDOWS) && !sourceFileName.endsWith(".exe")) {
            targetFileName = sourceFileName + ".exe";
        } else {
            targetFileName = sourceFileName;
        }
        final File targetFile = new File(protocPluginDirectory, targetFileName);
        if (targetFile.exists()) {
            // The file must have already been copied in a prior plugin execution/invocation
            getLog().debug("Executable file already exists: " + targetFile.getAbsolutePath());
            return targetFile;
        }
        try {
            FileUtils.forceMkdir(protocPluginDirectory);
        } catch (final IOException e) {
            throw new MojoInitializationException("Unable to create directory " + protocPluginDirectory, e);
        }
        try {
            FileUtils.copyFile(sourceFile, targetFile);
        } catch (final IOException e) {
            throw new MojoInitializationException("Unable to copy the file to " + protocPluginDirectory, e);
        }
        if (!Os.isFamily(Os.FAMILY_WINDOWS)) {
            targetFile.setExecutable(true);
        }

        if (getLog().isDebugEnabled()) {
            getLog().debug("Executable file: " + targetFile.getAbsolutePath());
        }
        return targetFile;
    }

    /**
     * Creates a dependency artifact from a specification in
     * {@code groupId:artifactId:version[:type[:classifier]]} format.
     *
     * @param artifactSpec artifact specification.
     * @return artifact object instance.
     */
    protected Artifact createDependencyArtifact(final String artifactSpec) {
        final String[] parts = artifactSpec.split(":");
        if (parts.length < 3 || parts.length > 5) {
            throw new MojoConfigurationException(
                    "Invalid artifact specification format"
                            + ", expected: groupId:artifactId:version[:type[:classifier]]"
                            + ", actual: " + artifactSpec);
        }
        final String type = parts.length >= 4 ? parts[3] : "exe";
        final String classifier = parts.length == 5 ? parts[4] : null;
        return createDependencyArtifact(parts[0], parts[1], parts[2], type, classifier);
    }

    protected Artifact createDependencyArtifact(
            final String groupId,
            final String artifactId,
            final String version,
            final String type,
            final String classifier
    ) {
        final VersionRange versionSpec;
        try {
            versionSpec = VersionRange.createFromVersionSpec(version);
        } catch (final InvalidVersionSpecificationException e) {
            throw new MojoConfigurationException("Invalid version specification", e);
        }
        return artifactFactory.createDependencyArtifact(
                groupId,
                artifactId,
                versionSpec,
                type,
                classifier,
                Artifact.SCOPE_RUNTIME);
    }
}
