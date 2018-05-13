package org.xolstice.maven.plugin.protobuf;

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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.CommandLineUtils.StringStreamConsumer;
import org.codehaus.plexus.util.cli.Commandline;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Lists.newLinkedList;

/**
 * This class represents an invokable configuration of the {@code protoc} compiler.
 * The actual executable is invoked using the plexus {@link Commandline}.
 */
final class Protoc {

    /**
     * Prefix for logging the debug messages.
     */
    private static final String LOG_PREFIX = "[PROTOC] ";

    /**
     * Path to the {@code protoc} executable.
     */
    private final String executable;

    /**
     * A set of directories in which to search for definition imports.
     */
    private final ImmutableSet<File> protoPathElements;

    /**
     * A set of protobuf definitions to process.
     */
    private final ImmutableSet<File> protoFiles;

    /**
     * A directory into which Java source files will be generated.
     */
    private final File javaOutputDirectory;

    /**
     * A directory into which JavaNano source files will be generated.
     */
    private final File javaNanoOutputDirectory;

    private final ImmutableSet<ProtocPlugin> plugins;

    private final File pluginDirectory;

    private final String nativePluginId;

    private final String nativePluginExecutable;

    private final String nativePluginParameter;

    /**
     * A directory into which C++ source files will be generated.
     */
    private final File cppOutputDirectory;

    /**
     * A directory into which Python source files will be generated.
     */
    private final File pythonOutputDirectory;

    /**
     *  A directory into which a custom protoc plugin will generate files.
     */
    private final File customOutputDirectory;

    private final File descriptorSetFile;

    private final boolean includeImportsInDescriptorSet;

    private final boolean includeSourceInfoInDescriptorSet;

    /**
     * A buffer to consume standard output from the {@code protoc} executable.
     */
    private final StringStreamConsumer output;

    /**
     * A buffer to consume error output from the {@code protoc} executable.
     */
    private final StringStreamConsumer error;

    /**
     * A directory where temporary files will be generated.
     */
    private final File tempDirectory;

    /**
     * A boolean indicating if the parameters to protoc should be passed in an argument file.
     */
    private final boolean useArgumentFile;

    /**
     * Constructs a new instance. This should only be used by the {@link Builder}.
     *
     * @param executable path to the {@code protoc} executable.
     * @param protoPath a set of directories in which to search for definition imports.
     * @param protoFiles a set of protobuf definitions to process.
     * @param javaOutputDirectory a directory into which Java source files will be generated.
     * @param javaNanoOutputDirectory a directory into which JavaNano source files will be generated.
     * @param cppOutputDirectory a directory into which C++ source files will be generated.
     * @param pythonOutputDirectory a directory into which Python source files will be generated.
     * @param customOutputDirectory a directory into which a custom protoc plugin will generate files.
     * @param descriptorSetFile The directory into which a descriptor set will be generated;
     *                          if {@code null}, no descriptor set will be written
     * @param includeImportsInDescriptorSet If {@code true}, dependencies will be included in the descriptor set.
     * @param includeSourceInfoInDescriptorSet If {@code true}, source code information will be included
     *                                         in the descriptor set.
     * @param plugins a set of java protoc plugins.
     * @param pluginDirectory location of protoc plugins to be added to system path.
     * @param nativePluginId a unique id of a native plugin.
     * @param nativePluginExecutable path to the native plugin executable.
     * @param nativePluginParameter an optional parameter for a native plugin.
     * @param tempDirectory a directory where temporary files will be generated.
     * @param useArgumentFile If {@code true}, parameters to protoc will be put in an argument file
     */
    private Protoc(
            final String executable,
            final ImmutableSet<File> protoPath,
            final ImmutableSet<File> protoFiles,
            final File javaOutputDirectory,
            final File javaNanoOutputDirectory,
            final File cppOutputDirectory,
            final File pythonOutputDirectory,
            final File customOutputDirectory,
            final File descriptorSetFile,
            final boolean includeImportsInDescriptorSet,
            final boolean includeSourceInfoInDescriptorSet,
            final ImmutableSet<ProtocPlugin> plugins,
            final File pluginDirectory,
            final String nativePluginId,
            final String nativePluginExecutable,
            final String nativePluginParameter,
            final File tempDirectory,
            final boolean useArgumentFile) {
        this.executable = checkNotNull(executable, "executable");
        this.protoPathElements = checkNotNull(protoPath, "protoPath");
        this.protoFiles = checkNotNull(protoFiles, "protoFiles");
        this.javaOutputDirectory = javaOutputDirectory;
        this.javaNanoOutputDirectory = javaNanoOutputDirectory;
        this.cppOutputDirectory = cppOutputDirectory;
        this.pythonOutputDirectory = pythonOutputDirectory;
        this.customOutputDirectory = customOutputDirectory;
        this.descriptorSetFile = descriptorSetFile;
        this.includeImportsInDescriptorSet = includeImportsInDescriptorSet;
        this.includeSourceInfoInDescriptorSet = includeSourceInfoInDescriptorSet;
        this.plugins = plugins;
        this.pluginDirectory = pluginDirectory;
        this.nativePluginId = nativePluginId;
        this.nativePluginExecutable = nativePluginExecutable;
        this.nativePluginParameter = nativePluginParameter;
        this.tempDirectory = tempDirectory;
        this.useArgumentFile = useArgumentFile;
        this.error = new StringStreamConsumer();
        this.output = new StringStreamConsumer();
    }

    /**
     * Invokes the {@code protoc} compiler using the configuration specified at construction.
     *
     * @param log logger instance.
     * @return The exit status of {@code protoc}.
     * @throws CommandLineException if command line environment cannot be set up.
     * @throws InterruptedException if the execution was interrupted by the user.
     */
    public int execute(final Log log) throws CommandLineException, InterruptedException {
        final Commandline cl = new Commandline();
        cl.setExecutable(executable);
        String[] args = buildProtocCommand().toArray(new String[] {});
        if (useArgumentFile) {
            try {
                File argumentsFile = createFileWithArguments(args);
                log.debug(LOG_PREFIX + "Using arguments file " + argumentsFile.getPath());
                cl.addArguments(new String[] {"@" + argumentsFile.getAbsolutePath()});
            } catch (IOException e) {
                log.error(LOG_PREFIX + "Error creating file with protoc arguments", e);
            }
        } else {
            cl.addArguments(args);
        }
        // There is a race condition in JDK that may sporadically prevent process creation on Linux
        // https://bugs.openjdk.java.net/browse/JDK-8068370
        // In order to mitigate that, retry up to 2 more times before giving up
        int attemptsLeft = 3;
        while (true) {
            try {
                return CommandLineUtils.executeCommandLine(cl, null, output, error);
            } catch (CommandLineException e) {
                if (--attemptsLeft == 0 || e.getCause() == null) {
                    throw e;
                }
                log.warn(LOG_PREFIX + "Unable to invoke protoc, will retry " + attemptsLeft + " time(s)", e);
                Thread.sleep(1000L);
            }
        }
    }

    /**
     * Creates the command line arguments.
     *
     * <p>This method has been made visible for testing only.</p>
     *
     * @return A list consisting of the executable followed by any arguments.
     */
    public ImmutableList<String> buildProtocCommand() {
        final List<String> command = newLinkedList();
        // add the executable
        for (final File protoPathElement : protoPathElements) {
            command.add("--proto_path=" + protoPathElement);
        }
        if (javaOutputDirectory != null) {
            command.add("--java_out=" + javaOutputDirectory);

            // For now we assume all custom plugins produce Java output
            for (final ProtocPlugin plugin : plugins) {
                final File pluginExecutable = plugin.getPluginExecutableFile(pluginDirectory);
                command.add("--plugin=protoc-gen-" + plugin.getId() + '=' + pluginExecutable);
                command.add("--" + plugin.getId() + "_out=" + javaOutputDirectory);
            }
        }
        if (javaNanoOutputDirectory != null) {
            String outputOption = "--javanano_out=";
            if (nativePluginParameter != null) {
                outputOption += nativePluginParameter + ':';
            }
            outputOption += javaNanoOutputDirectory;
            command.add(outputOption);
        }
        if (cppOutputDirectory != null) {
            command.add("--cpp_out=" + cppOutputDirectory);
        }
        if (pythonOutputDirectory != null) {
            command.add("--python_out=" + pythonOutputDirectory);
        }
        if (customOutputDirectory != null) {
            if (nativePluginExecutable != null) {
                command.add("--plugin=protoc-gen-" + nativePluginId + '=' + nativePluginExecutable);
            }

            String outputOption = "--" + nativePluginId + "_out=";
            if (nativePluginParameter != null) {
                outputOption += nativePluginParameter + ':';
            }
            outputOption += customOutputDirectory;
            command.add(outputOption);
        }
        for (final File protoFile : protoFiles) {
            command.add(protoFile.toString());
        }
        if (descriptorSetFile != null) {
            command.add("--descriptor_set_out=" + descriptorSetFile);
            if (includeImportsInDescriptorSet) {
                command.add("--include_imports");
            }
            if (includeSourceInfoInDescriptorSet) {
                command.add("--include_source_info");
            }
        }
        return ImmutableList.copyOf(command);
    }

    /**
     * Logs execution parameters on debug level to the specified logger.
     * All log messages will be prefixed with "{@value #LOG_PREFIX}".
     *
     * @param log a logger.
     */
    public void logExecutionParameters(final Log log) {
        if (log.isDebugEnabled()) {
            log.debug(LOG_PREFIX + "Executable: ");
            log.debug(LOG_PREFIX + ' ' + executable);

            if (protoPathElements != null && !protoPathElements.isEmpty()) {
                log.debug(LOG_PREFIX + "Protobuf import paths:");
                for (final File protoPathElement : protoPathElements) {
                    log.debug(LOG_PREFIX + ' ' + protoPathElement);
                }
            }

            if (javaOutputDirectory != null) {
                log.debug(LOG_PREFIX + "Java output directory:");
                log.debug(LOG_PREFIX + ' ' + javaOutputDirectory);

                if (plugins.size() > 0) {
                    log.debug(LOG_PREFIX + "Plugins for Java output:");
                    for (final ProtocPlugin plugin : plugins) {
                        log.debug(LOG_PREFIX + ' ' + plugin.getId());
                    }
                }
            }

            if (pluginDirectory != null) {
                log.debug(LOG_PREFIX + "Plugin directory:");
                log.debug(LOG_PREFIX + ' ' + pluginDirectory);
            }

            if (javaNanoOutputDirectory != null) {
                log.debug(LOG_PREFIX + "Java Nano output directory:");
                log.debug(LOG_PREFIX + ' ' + javaNanoOutputDirectory);
            }
            if (cppOutputDirectory != null) {
                log.debug(LOG_PREFIX + "C++ output directory:");
                log.debug(LOG_PREFIX + ' ' + cppOutputDirectory);
            }
            if (pythonOutputDirectory != null) {
                log.debug(LOG_PREFIX + "Python output directory:");
                log.debug(LOG_PREFIX + ' ' + pythonOutputDirectory);
            }

            if (descriptorSetFile != null) {
                log.debug(LOG_PREFIX + "Descriptor set output file:");
                log.debug(LOG_PREFIX + ' ' + descriptorSetFile);
                log.debug(LOG_PREFIX + "Include imports:");
                log.debug(LOG_PREFIX + ' ' + includeImportsInDescriptorSet);
            }

            log.debug(LOG_PREFIX + "Protobuf descriptors:");
            for (final File protoFile : protoFiles) {
                log.debug(LOG_PREFIX + ' ' + protoFile);
            }

            final List<String> cl = buildProtocCommand();
            if (cl != null && !cl.isEmpty()) {
                log.debug(LOG_PREFIX + "Command line options:");
                log.debug(LOG_PREFIX + Joiner.on(' ').join(cl));
            }
        }
    }

    /**
     * @return the output
     */
    public String getOutput() {
        return fixUnicodeOutput(output.getOutput());
    }

    /**
     * @return the error
     */
    public String getError() {
        return fixUnicodeOutput(error.getOutput());
    }

    /**
     * Transcodes the output from system default charset to UTF-8.
     * Protoc emits messages in UTF-8, but they are captured into a stream that has a system-default encoding.
     *
     * @param message a UTF-8 message in system-default encoding.
     * @return the same message converted into a unicode string.
     */
    private static String fixUnicodeOutput(final String message) {
        return new String(message.getBytes(), Charset.forName("UTF-8"));
    }

    /**
     * Put args into a temp file to be referenced using the @ option in protoc command line.
     *
     * @param args
     * @return the temporary file wth the arguments
     * @throws IOException
     */
    private File createFileWithArguments(String[] args) throws IOException {
        PrintWriter writer = null;
        try {
            final File tempFile = File.createTempFile("protoc", null, tempDirectory);
            tempFile.deleteOnExit();

            writer = new PrintWriter(tempFile, "UTF-8");
            for (final String arg : args) {
                writer.println(arg);
            }
            writer.flush();

            return tempFile;
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    /**
     * This class builds {@link Protoc} instances.
     */
    static final class Builder {

        /**
         * Path to the {@code protoc} executable.
         */
        private final String executable;

        private final Set<File> protopathElements;

        private final Set<File> protoFiles;

        private final Set<ProtocPlugin> plugins;

        private File tempDirectory;

        private File pluginDirectory;

        // TODO reorganise support for custom plugins
        // This place is currently a mess because of the two different type of custom plugins supported:
        // pure java (wrapped in a native launcher) and binary native.

        private String nativePluginId;

        private String nativePluginExecutable;

        private String nativePluginParameter;

        /**
         * A directory into which Java source files will be generated.
         */
        private File javaOutputDirectory;

        /**
         * A directory into which Java Nano source files will be generated.
         */
        private File javaNanoOutputDirectory;

        /**
         * A directory into which C++ source files will be generated.
         */
        private File cppOutputDirectory;

        /**
         * A directory into which Python source files will be generated.
         */
        private File pythonOutputDirectory;

        /**
         * A directory into which a custom protoc plugin will generate files.
         */
        private File customOutputDirectory;

        private File descriptorSetFile;

        private boolean includeImportsInDescriptorSet;

        private boolean includeSourceInfoInDescriptorSet;

        private boolean useArgumentFile;

        /**
         * Constructs a new builder.
         *
         * @param executable The path to the {@code protoc} executable.
         * @throws NullPointerException if {@code executable} is {@code null}.
         */
        Builder(final String executable) {
            this.executable = checkNotNull(executable, "executable");
            this.protoFiles = new LinkedHashSet<File>();
            this.protopathElements = new LinkedHashSet<File>();
            this.plugins = new LinkedHashSet<ProtocPlugin>();
        }

        public Builder setTempDirectory(final File directory) {
            checkNotNull(directory);
            checkArgument(directory.isDirectory(), "Temp directory " + directory + "does not exist");
            tempDirectory = directory;
            return this;
        }

        /**
         * Sets the directory into which Java source files will be generated.
         *
         * @param javaOutputDirectory a directory into which Java source files will be generated.
         * @return this builder instance.
         * @throws NullPointerException if {@code javaOutputDirectory} is {@code null}.
         * @throws IllegalArgumentException if {@code javaOutputDirectory} is not a directory.
         */
        public Builder setJavaOutputDirectory(final File javaOutputDirectory) {
            this.javaOutputDirectory = checkNotNull(javaOutputDirectory, "'javaOutputDirectory' is null");
            checkArgument(
                    javaOutputDirectory.isDirectory(),
                    "'javaOutputDirectory' is not a directory: " + javaOutputDirectory);
            return this;
        }

        /**
         * Sets the directory into which JavaNano source files will be generated.
         *
         * @param javaNanoOutputDirectory a directory into which Java source files will be generated.
         * @return this builder instance.
         * @throws NullPointerException if {@code javaNanoOutputDirectory} is {@code null}.
         * @throws IllegalArgumentException if {@code javaNanoOutputDirectory} is not a directory.
         */
        public Builder setJavaNanoOutputDirectory(final File javaNanoOutputDirectory) {
            this.javaNanoOutputDirectory = checkNotNull(javaNanoOutputDirectory, "'javaNanoOutputDirectory' is null");
            checkArgument(
                    javaNanoOutputDirectory.isDirectory(),
                    "'javaNanoOutputDirectory' is not a directory: " + javaNanoOutputDirectory);
            return this;
        }

        /**
         * Sets the directory into which C++ source files will be generated.
         *
         * @param cppOutputDirectory a directory into which C++ source files will be generated.
         * @return this builder instance.
         * @throws NullPointerException if {@code cppOutputDirectory} is {@code null}.
         * @throws IllegalArgumentException if {@code cppOutputDirectory} is not a directory.
         */
        public Builder setCppOutputDirectory(final File cppOutputDirectory) {
            this.cppOutputDirectory = checkNotNull(cppOutputDirectory, "'cppOutputDirectory' is null");
            checkArgument(
                    cppOutputDirectory.isDirectory(),
                    "'cppOutputDirectory' is not a directory: " + cppOutputDirectory);
            return this;
        }

        /**
         * Sets the directory into which Python source files will be generated.
         *
         * @param pythonOutputDirectory a directory into which Python source files will be generated.
         * @return this builder instance.
         * @throws NullPointerException if {@code pythonOutputDirectory} is {@code null}.
         * @throws IllegalArgumentException if {@code pythonOutputDirectory} is not a directory.
         */
        public Builder setPythonOutputDirectory(final File pythonOutputDirectory) {
            this.pythonOutputDirectory = checkNotNull(pythonOutputDirectory, "'pythonOutputDirectory' is null");
            checkArgument(
                    pythonOutputDirectory.isDirectory(),
                    "'pythonOutputDirectory' is not a directory: " + pythonOutputDirectory);
            return this;
        }

        /**
         * Sets the directory into which a custom protoc plugin will generate files.
         *
         * @param customOutputDirectory a directory into which a custom protoc plugin will generate files.
         * @return this builder instance.
         * @throws NullPointerException if {@code customOutputDirectory} is {@code null}.
         * @throws IllegalArgumentException if {@code customOutputDirectory} is not a directory.
         */
        public Builder setCustomOutputDirectory(final File customOutputDirectory) {
            this.customOutputDirectory = checkNotNull(customOutputDirectory, "'customOutputDirectory' is null");
            checkArgument(
                    customOutputDirectory.isDirectory(),
                    "'customOutputDirectory' is not a directory: " + customOutputDirectory);
            return this;
        }

        /**
         * Adds a proto file to be compiled. Proto files must be on the protopath
         * and this method will fail if a proto file is added without first adding a
         * parent directory to the protopath.
         *
         * @param protoFile source protobuf definitions file.
         * @return The builder.
         * @throws IllegalStateException If a proto file is added without first
         * adding a parent directory to the protopath.
         * @throws NullPointerException If {@code protoFile} is {@code null}.
         */
        public Builder addProtoFile(final File protoFile) {
            checkNotNull(protoFile);
            checkArgument(protoFile.isFile());
            checkArgument(protoFile.getName().endsWith(".proto"));
            checkProtoFileIsInProtopath(protoFile);
            protoFiles.add(protoFile);
            return this;
        }

        /**
         * Adds a protoc plugin definition for custom code generation.
         * @param plugin plugin definition
         * @return this builder instance.
         */
        public Builder addPlugin(final ProtocPlugin plugin) {
            checkNotNull(plugin);
            plugins.add(plugin);
            return this;
        }

        public Builder setPluginDirectory(final File directory) {
            checkNotNull(directory);
            checkArgument(directory.isDirectory(), "Plugin directory " + directory + "does not exist");
            pluginDirectory = directory;
            return this;
        }

        public Builder setNativePluginId(final String nativePluginId) {
            checkNotNull(nativePluginId, "'nativePluginId' is null");
            checkArgument(!nativePluginId.isEmpty(), "'nativePluginId' is empty");
            checkArgument(
                    !(nativePluginId.equals("java")
                            || nativePluginId.equals("javanano")
                            || nativePluginId.equals("python")
                            || nativePluginId.equals("cpp")
                            || nativePluginId.equals("descriptor_set")),
                    "'nativePluginId' matches one of the built-in protoc plugins");
            this.nativePluginId = nativePluginId;
            return this;
        }

        public Builder setNativePluginExecutable(final String nativePluginExecutable) {
            checkNotNull(nativePluginExecutable, "'nativePluginExecutable' is null");
            this.nativePluginExecutable = nativePluginExecutable;
            return this;
        }

        public Builder setNativePluginParameter(final String nativePluginParameter) {
            checkNotNull(nativePluginParameter, "'nativePluginParameter' is null");
            checkArgument(!nativePluginParameter.contains(":"), "'nativePluginParameter' contains illegal characters");
            this.nativePluginParameter = nativePluginParameter;
            return this;
        }

        public Builder withDescriptorSetFile(
                final File descriptorSetFile,
                final boolean includeImports,
                final boolean includeSourceInfoInDescriptorSet) {
            checkNotNull(descriptorSetFile, "descriptorSetFile");
            checkArgument(descriptorSetFile.getParentFile().isDirectory());
            this.descriptorSetFile = descriptorSetFile;
            this.includeImportsInDescriptorSet = includeImports;
            this.includeSourceInfoInDescriptorSet = includeSourceInfoInDescriptorSet;
            return this;
        }

        public Builder useArgumentFile(final boolean useArgumentFile) {
            this.useArgumentFile = useArgumentFile;
            return this;
        }

        private void checkProtoFileIsInProtopath(final File protoFile) {
            assert protoFile.isFile();
            checkState(checkProtoFileIsInProtopathHelper(protoFile.getParentFile()));
        }

        private boolean checkProtoFileIsInProtopathHelper(final File directory) {
            assert directory.isDirectory();
            if (protopathElements.contains(directory)) {
                return true;
            } else {
                final File parentDirectory = directory.getParentFile();
                return parentDirectory != null && checkProtoFileIsInProtopathHelper(parentDirectory);
            }
        }

        /**
         * Adds a collection of proto files to be compiled.
         *
         * @param protoFiles a collection of source protobuf definition files.
         * @return this builder instance.
         * @see #addProtoFile(File)
         */
        public Builder addProtoFiles(final Iterable<File> protoFiles) {
            for (final File protoFile : protoFiles) {
                addProtoFile(protoFile);
            }
            return this;
        }

        /**
         * Adds the {@code protopathElement} to the protopath.
         *
         * @param protopathElement A directory to be searched for imported protocol buffer definitions.
         * @return The builder.
         * @throws NullPointerException If {@code protopathElement} is {@code null}.
         * @throws IllegalArgumentException If {@code protpathElement} is not a
         * directory.
         */
        public Builder addProtoPathElement(final File protopathElement) {
            checkNotNull(protopathElement);
            checkArgument(protopathElement.isDirectory());
            protopathElements.add(protopathElement);
            return this;
        }

        /**
         * Adds a number of elements to the protopath.
         *
         * @param protopathElements directories to be searched for imported protocol buffer definitions.
         * @return this builder instance.
         * @see #addProtoPathElement(File)
         */
        public Builder addProtoPathElements(final Iterable<File> protopathElements) {
            for (final File protopathElement : protopathElements) {
                addProtoPathElement(protopathElement);
            }
            return this;
        }

        /**
         * Validates the internal state for consistency and completeness.
         */
        private void validateState() {
            checkState(!protoFiles.isEmpty());
            checkState(javaOutputDirectory != null
                            || javaNanoOutputDirectory != null
                            || cppOutputDirectory != null
                            || pythonOutputDirectory != null
                            || customOutputDirectory != null,
                    "At least one of these properties must be set: " +
                            "'javaOutputDirectory', 'javaNanoOutputDirectory', 'cppOutputDirectory', " +
                            "'pythonOutputDirectory' or 'customOutputDirectory'");
        }

        /**
         * Builds and returns a fully configured instance of {@link Protoc} wrapper.
         *
         * @return a configured {@link Protoc} instance.
         * @throws IllegalStateException if builder state is incomplete or inconsistent.
         */
        public Protoc build() {
            validateState();
            return new Protoc(
                    executable,
                    ImmutableSet.copyOf(protopathElements),
                    ImmutableSet.copyOf(protoFiles),
                    javaOutputDirectory,
                    javaNanoOutputDirectory,
                    cppOutputDirectory,
                    pythonOutputDirectory,
                    customOutputDirectory,
                    descriptorSetFile,
                    includeImportsInDescriptorSet,
                    includeSourceInfoInDescriptorSet,
                    ImmutableSet.copyOf(plugins),
                    pluginDirectory,
                    nativePluginId,
                    nativePluginExecutable,
                    nativePluginParameter,
                    tempDirectory,
                    useArgumentFile);
        }
    }
}
