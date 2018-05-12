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

outputDirectory = new File(basedir, 'target/generated-sources/protobuf/java');
assert outputDirectory.exists();
assert outputDirectory.isDirectory();

generatedJavaFile = new File(outputDirectory, 'test/TestProtos.java');
assert generatedJavaFile.exists();
assert generatedJavaFile.isFile();

content = generatedJavaFile.text;
assert content.contains('package test');
assert content.contains('class TestProtos');
assert content.contains('class TestMessage');

generatedJavaFile = new File(outputDirectory, 'test/TestProtosJp.java');
assert generatedJavaFile.exists();
assert generatedJavaFile.isFile();

content = generatedJavaFile.text;
assert content.contains('package test');
assert content.contains('class TestProtosJp');
assert content.contains('class TestMessageJp');

generatedJavaFile = new File(outputDirectory, 'test/TestProtosRu.java');
assert generatedJavaFile.exists();
assert generatedJavaFile.isFile();

content = generatedJavaFile.text;
assert content.contains('package test');
assert content.contains('class TestProtosRu');
assert content.contains('class TestMessageRu');

return true;
