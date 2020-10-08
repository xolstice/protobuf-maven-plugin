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

import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.CommandLineUtils.StringStreamConsumer;
import org.codehaus.plexus.util.cli.Commandline;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.codehaus.plexus.util.StringUtils.join;

/**
 * This class represents an invokable configuration of the {@code protoc} compiler.
 * The actual executable is invoked using the plexus {@link Commandline}.
 */
final class Protoc {

    /**
     * Prefix for logging the debug messages.
     */
    public static final String LOG_PREFIX = "[PROTOC] ";

    /**
     * Path to the {@code protoc} executable.
     */
    private final String executable;

    /**
     * A set of directories in which to search for definition imports.
     */
    private final List<File> protoPathElements;

    /**
     * A set of protobuf definitions to process.
     */
    private final List<File> protoFiles;

    /**
     * A directory into which Java source files will be generated.
     */
    private final File javaOutputDirectory;

    private final List<ProtocPlugin> plugins;

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
     * A directory into which C# source files will be generated.
     */
    private final File csharpOutputDirectory;

    /**
     * A directory into which JavaScript source files will be generated.
     */
    private final File javaScriptOutputDirectory;

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
     * A boolean indicating if experimental optional feature should be enabled.
     */
    private boolean useExperimentalOptional;

    /**
     * Constructs a new instance. This should only be used by the {@link Builder}.
     *
     * @param executable path to the {@code protoc} executable.
     * @param protoPath a set of directories in which to search for definition imports.
     * @param protoFiles a set of protobuf definitions to process.
     * @param javaOutputDirectory a directory into which Java source files will be generated.
     * @param cppOutputDirectory a directory into which C++ source files will be generated.
     * @param pythonOutputDirectory a directory into which Python source files will be generated.
     * @param csharpOutputDirectory a directory into which C# source files will be generated.
     * @param javaScriptOutputDirectory a directory into which JavaScript source files will be generated.
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
     * @param useExperimentalOptional If {@code true}, experimental optional feature will be enabled
     */
    private Protoc(
            final String executable,
            final List<File> protoPath,
            final List<File> protoFiles,
            final File javaOutputDirectory,
            final File cppOutputDirectory,
            final File pythonOutputDirectory,
            final File csharpOutputDirectory,
            final File javaScriptOutputDirectory,
            final File customOutputDirectory,
            final File descriptorSetFile,
            final boolean includeImportsInDescriptorSet,
            final boolean includeSourceInfoInDescriptorSet,
            final List<ProtocPlugin> plugins,
            final File pluginDirectory,
            final String nativePluginId,
            final String nativePluginExecutable,
            final String nativePluginParameter,
            final File tempDirectory,
            final boolean useArgumentFile,
            final boolean useExperimentalOptional
    ) {
        if (executable == null) {
            throw new MojoConfigurationException("'executable' is null");
        }
        if (protoPath == null) {
            throw new MojoConfigurationException("'protoPath' is null");
        }
        if (protoFiles == null) {
            throw new MojoConfigurationException("'protoFiles' is null");
        }
        this.executable = executable;
        this.protoPathElements = protoPath;
        this.protoFiles = protoFiles;
        this.javaOutputDirectory = javaOutputDirectory;
        this.cppOutputDirectory = cppOutputDirectory;
        this.pythonOutputDirectory = pythonOutputDirectory;
        this.csharpOutputDirectory = csharpOutputDirectory;
        this.javaScriptOutputDirectory = javaScriptOutputDirectory;
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
        this.useExperimentalOptional = useExperimentalOptional;
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
            } catch (final IOException e) {
                throw new CommandLineException("Error creating file with protoc arguments", e);
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
    public List<String> buildProtocCommand() {
        final List<String> command = new ArrayList<>();
        // add the executable
        for (final File protoPathElement : protoPathElements) {
            command.add("--proto_path=" + protoPathElement);
        }
        if (useExperimentalOptional) {
            command.add("--experimental_allow_proto3_optional");

        }
        if (javaOutputDirectory != null) {
            String outputOption = "--java_out=";
            if (nativePluginParameter != null) {
                outputOption += nativePluginParameter + ':';
            }
            outputOption += javaOutputDirectory;
            command.add(outputOption);

            // For now we assume all custom plugins produce Java output
            for (final ProtocPlugin plugin : plugins) {
                final File pluginExecutable = plugin.getPluginExecutableFile(pluginDirectory);
                command.add("--plugin=protoc-gen-" + plugin.getId() + '=' + pluginExecutable);
                command.add("--" + plugin.getId() + "_out=" + javaOutputDirectory);
            }
        }
        if (cppOutputDirectory != null) {
            command.add("--cpp_out=" + cppOutputDirectory);
        }
        if (pythonOutputDirectory != null) {
            command.add("--python_out=" + pythonOutputDirectory);
        }
        if (csharpOutputDirectory != null) {
            command.add("--csharp_out=" + csharpOutputDirectory);
        }
        if (javaScriptOutputDirectory != null) {
            String outputOption = "--js_out=";
            if (nativePluginParameter != null) {
                outputOption += nativePluginParameter + ':';
            }
            outputOption += javaScriptOutputDirectory;
            command.add(outputOption);
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
        return command;
    }

    /**
     * Logs execution parameters on debug level to the specified logger.
     * All log messages will be prefixed with {@value #LOG_PREFIX}.
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

            if (cppOutputDirectory != null) {
                log.debug(LOG_PREFIX + "C++ output directory:");
                log.debug(LOG_PREFIX + ' ' + cppOutputDirectory);
            }
            if (pythonOutputDirectory != null) {
                log.debug(LOG_PREFIX + "Python output directory:");
                log.debug(LOG_PREFIX + ' ' + pythonOutputDirectory);
            }
            if (csharpOutputDirectory != null) {
                log.debug(LOG_PREFIX + "C# output directory:");
                log.debug(LOG_PREFIX + ' ' + csharpOutputDirectory);
            }
            if (javaScriptOutputDirectory != null) {
                log.debug(LOG_PREFIX + "JavaScript output directory:");
                log.debug(LOG_PREFIX + ' ' + javaScriptOutputDirectory);
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
                log.debug(LOG_PREFIX + join(cl.iterator(), " "));
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
    private File createFileWithArguments(String... args) throws IOException {
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

        private final LinkedHashSet<File> protopathElements;

        private final List<File> protoFiles;

        private final List<ProtocPlugin> plugins;

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
         * A directory into which C++ source files will be generated.
         */
        private File cppOutputDirectory;

        /**
         * A directory into which Python source files will be generated.
         */
        private File pythonOutputDirectory;

        /**
         * A directory into which C# source files will be generated.
         */
        private File csharpOutputDirectory;

        /**
         * A directory into which JavaScript source files will be generated.
         */
        private File javaScriptOutputDirectory;

        /**
         * A directory into which a custom protoc plugin will generate files.
         */
        private File customOutputDirectory;

        private File descriptorSetFile;

        private boolean includeImportsInDescriptorSet;

        private boolean includeSourceInfoInDescriptorSet;

        private boolean useArgumentFile;

        private boolean useExperimentalOptional;

        /**
         * Constructs a new builder.
         *
         * @param executable The path to the {@code protoc} executable.
         */
        Builder(final String executable) {
            if (executable == null) {
                throw new MojoConfigurationException("'executable' is null");
            }
            this.executable = executable;
            protoFiles = new ArrayList<>();
            protopathElements = new LinkedHashSet<>();
            plugins = new ArrayList<>();
        }

        public Builder setTempDirectory(final File tempDirectory) {
            if (tempDirectory == null) {
                throw new MojoConfigurationException("'tempDirectory' is null");
            }
            if (!tempDirectory.isDirectory()) {
                throw new MojoConfigurationException(
                        "'tempDirectory' is not a directory: " + tempDirectory.getAbsolutePath());
            }
            this.tempDirectory = tempDirectory;
            return this;
        }

        /**
         * Sets the directory into which Java source files will be generated.
         *
         * @param javaOutputDirectory a directory into which Java source files will be generated.
         * @return this builder instance.
         */
        public Builder setJavaOutputDirectory(final File javaOutputDirectory) {
            if (javaOutputDirectory == null) {
                throw new MojoConfigurationException("'javaOutputDirectory' is null");
            }
            if (!javaOutputDirectory.isDirectory()) {
                throw new MojoConfigurationException(
                        "'javaOutputDirectory' is not a directory: " + javaOutputDirectory.getAbsolutePath());
            }
            this.javaOutputDirectory = javaOutputDirectory;
            return this;
        }

        /**
         * Sets the directory into which C++ source files will be generated.
         *
         * @param cppOutputDirectory a directory into which C++ source files will be generated.
         * @return this builder instance.
         */
        public Builder setCppOutputDirectory(final File cppOutputDirectory) {
            if (cppOutputDirectory == null) {
                throw new MojoConfigurationException("'cppOutputDirectory' is null");
            }
            if (!cppOutputDirectory.isDirectory()) {
                throw new MojoConfigurationException(
                        "'cppOutputDirectory' is not a directory: " + cppOutputDirectory.getAbsolutePath());
            }
            this.cppOutputDirectory = cppOutputDirectory;
            return this;
        }

        /**
         * Sets the directory into which Python source files will be generated.
         *
         * @param pythonOutputDirectory a directory into which Python source files will be generated.
         * @return this builder instance.
         */
        public Builder setPythonOutputDirectory(final File pythonOutputDirectory) {
            if (pythonOutputDirectory == null) {
                throw new MojoConfigurationException("'pythonOutputDirectory' is null");
            }
            if (!pythonOutputDirectory.isDirectory()) {
                throw new MojoConfigurationException(
                        "'pythonOutputDirectory' is not a directory: " + pythonOutputDirectory.getAbsolutePath());
            }
            this.pythonOutputDirectory = pythonOutputDirectory;
            return this;
        }

        /**
         * Sets the directory into which C# source files will be generated.
         *
         * @param csharpOutputDirectory a directory into which C# source files will be generated.
         * @return this builder instance.
         */
        public Builder setCsharpOutputDirectory(final File csharpOutputDirectory) {
            if (csharpOutputDirectory == null) {
                throw new MojoConfigurationException("'csharpOutputDirectory' is null");
            }
            if (!csharpOutputDirectory.isDirectory()) {
                throw new MojoConfigurationException(
                        "'csharpOutputDirectory' is not a directory: " + csharpOutputDirectory.getAbsolutePath());
            }
            this.csharpOutputDirectory = csharpOutputDirectory;
            return this;
        }

        /**
         * Sets the directory into which JavaScript source files will be generated.
         *
         * @param javaScriptOutputDirectory a directory into which JavaScript source files will be generated.
         * @return this builder instance.
         */
        public Builder setJavaScriptOutputDirectory(final File javaScriptOutputDirectory) {
            if (javaScriptOutputDirectory == null) {
                throw new MojoConfigurationException("'javaScriptOutputDirectory' is null");
            }
            if (!javaScriptOutputDirectory.isDirectory()) {
                throw new MojoConfigurationException(
                        "'javaScriptOutputDirectory' is not a directory: "
                                + javaScriptOutputDirectory.getAbsolutePath());
            }
            this.javaScriptOutputDirectory = javaScriptOutputDirectory;
            return this;
        }

        /**
         * Sets the directory into which a custom protoc plugin will generate files.
         *
         * @param customOutputDirectory a directory into which a custom protoc plugin will generate files.
         * @return this builder instance.
         */
        public Builder setCustomOutputDirectory(final File customOutputDirectory) {
            if (customOutputDirectory == null) {
                throw new MojoConfigurationException("'customOutputDirectory' is null");
            }
            if (!customOutputDirectory.isDirectory()) {
                throw new MojoConfigurationException(
                        "'customOutputDirectory' is not a directory: " + customOutputDirectory.getAbsolutePath());
            }
            this.customOutputDirectory = customOutputDirectory;
            return this;
        }

        /**
         * Adds a proto file to be compiled. Proto files must be on the protopath
         * and this method will fail if a proto file is added without first adding a
         * parent directory to the protopath.
         *
         * @param protoFile source protobuf definitions file.
         * @return The builder.
         */
        public Builder addProtoFile(final File protoFile) {
            if (protoFile == null) {
                throw new MojoConfigurationException("'protoFile' is null");
            }
            if (!protoFile.isFile()) {
                throw new MojoConfigurationException("Proto file is not a file: " + protoFile.getAbsolutePath());
            }
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
            if (plugin == null) {
                throw new MojoConfigurationException("'plugin' is null");
            }
            plugins.add(plugin);
            return this;
        }

        public Builder setPluginDirectory(final File pluginDirectory) {
            if (pluginDirectory == null) {
                throw new MojoConfigurationException("'pluginDirectory' is null");
            }
            if (!pluginDirectory.isDirectory()) {
                throw new MojoConfigurationException(
                        "'pluginDirectory' is not a directory: " + pluginDirectory.getAbsolutePath());
            }
            this.pluginDirectory = pluginDirectory;
            return this;
        }

        public Builder setNativePluginId(final String nativePluginId) {
            if (nativePluginId == null || nativePluginId.isEmpty()) {
                throw new MojoConfigurationException("'nativePluginId' is null or empty");
            }
            if (nativePluginId.equals("java")
                    || nativePluginId.equals("js")
                    || nativePluginId.equals("python")
                    || nativePluginId.equals("csharp")
                    || nativePluginId.equals("cpp")
                    || nativePluginId.equals("descriptor_set")) {
                throw new MojoConfigurationException("'nativePluginId' matches one of the built-in "
                        + "protoc plugins: " + nativePluginId);
            }
            this.nativePluginId = nativePluginId;
            return this;
        }

        public Builder setNativePluginExecutable(final String nativePluginExecutable) {
            if (nativePluginExecutable == null || nativePluginExecutable.isEmpty()) {
                throw new MojoConfigurationException("'nativePluginExecutable' is null or empty");
            }
            this.nativePluginExecutable = nativePluginExecutable;
            return this;
        }

        public Builder setNativePluginParameter(final String nativePluginParameter) {
            if (nativePluginParameter == null) {
                throw new MojoConfigurationException("'nativePluginParameter' is null");
            }
            if (nativePluginParameter.contains(":")) {
                throw new MojoConfigurationException("'nativePluginParameter' contains illegal characters");
            }
            this.nativePluginParameter = nativePluginParameter;
            return this;
        }

        public Builder withDescriptorSetFile(
                final File descriptorSetFile,
                final boolean includeImports,
                final boolean includeSourceInfoInDescriptorSet
        ) {
            if (descriptorSetFile == null) {
                throw new MojoConfigurationException("'descriptorSetFile' is null");
            }
            final File descriptorSetFileParent = descriptorSetFile.getParentFile();
            if (!descriptorSetFileParent.exists()) {
                throw new MojoConfigurationException("Parent directory for 'descriptorSetFile' does not exist");
            }
            if (!descriptorSetFileParent.isDirectory()) {
                throw new MojoConfigurationException("Parent for 'descriptorSetFile' is not a directory");
            }
            this.descriptorSetFile = descriptorSetFile;
            this.includeImportsInDescriptorSet = includeImports;
            this.includeSourceInfoInDescriptorSet = includeSourceInfoInDescriptorSet;
            return this;
        }

        public Builder useArgumentFile(final boolean useArgumentFile) {
            this.useArgumentFile = useArgumentFile;
            return this;
        }

        public Builder useExperimentalOptional(final boolean useExperimentalOptional) {
            this.useExperimentalOptional = useExperimentalOptional;
            return this;
        }

        private void checkProtoFileIsInProtopath(final File protoFile) {
            if (!protoFile.isFile()) {
                throw new MojoConfigurationException("Not a regular file: " + protoFile.getAbsolutePath());
            }
            if (!checkProtoFileIsInProtopathHelper(protoFile.getParentFile())) {
                throw new MojoConfigurationException("File is not in proto path: " + protoFile.getAbsolutePath());
            }
        }

        private boolean checkProtoFileIsInProtopathHelper(final File directory) {
            if (!directory.isDirectory()) {
                throw new MojoConfigurationException("Not a directory: " + directory.getAbsolutePath());
            }
            if (protopathElements.contains(directory)) {
                return true;
            }
            final File parentDirectory = directory.getParentFile();
            return parentDirectory != null && checkProtoFileIsInProtopathHelper(parentDirectory);
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
         */
        public Builder addProtoPathElement(final File protopathElement) {
            if (protopathElement == null) {
                throw new MojoConfigurationException("'protopathElement' is null");
            }
            if (!protopathElement.isDirectory()) {
                throw new MojoConfigurationException(
                        "Proto path element is not a directory: " + protopathElement.getAbsolutePath());
            }
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
            if (protoFiles.isEmpty()) {
                throw new MojoConfigurationException("No proto files specified");
            }
            if (javaOutputDirectory == null
                    && cppOutputDirectory == null
                    && pythonOutputDirectory == null
                    && csharpOutputDirectory == null
                    && javaScriptOutputDirectory == null
                    && customOutputDirectory == null
                    && descriptorSetFile == null) {
                throw new MojoConfigurationException("At least one of these properties must be set:" +
                        " 'javaOutputDirectory', 'cppOutputDirectory'," +
                        " 'pythonOutputDirectory', 'csharpOutputDirectory', 'javaScriptOutputDirectory'," +
                        " 'customOutputDirectory', or 'descriptorSetFile'");
            }
        }

        /**
         * Builds and returns a fully configured instance of {@link Protoc} wrapper.
         *
         * @return a configured {@link Protoc} instance.
         */
        public Protoc build() {
            validateState();
            return new Protoc(
                    executable,
                    new ArrayList<>(protopathElements),
                    protoFiles,
                    javaOutputDirectory,
                    cppOutputDirectory,
                    pythonOutputDirectory,
                    csharpOutputDirectory,
                    javaScriptOutputDirectory,
                    customOutputDirectory,
                    descriptorSetFile,
                    includeImportsInDescriptorSet,
                    includeSourceInfoInDescriptorSet,
                    plugins,
                    pluginDirectory,
                    nativePluginId,
                    nativePluginExecutable,
                    nativePluginParameter,
                    tempDirectory,
                    useArgumentFile,
                    useExperimentalOptional);
        }
    }
}
