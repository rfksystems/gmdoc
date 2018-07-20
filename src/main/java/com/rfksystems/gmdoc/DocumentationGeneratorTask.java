/*
 * Copyright 2018 RFK Systems and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rfksystems.gmdoc;

import com.mitchellbosecke.pebble.PebbleEngine;
import com.mitchellbosecke.pebble.error.PebbleException;
import com.mitchellbosecke.pebble.loader.FileLoader;
import com.mitchellbosecke.pebble.template.PebbleTemplate;
import com.vladsch.flexmark.ast.Document;
import com.vladsch.flexmark.ext.abbreviation.AbbreviationExtension;
import com.vladsch.flexmark.ext.definition.DefinitionExtension;
import com.vladsch.flexmark.ext.footnotes.FootnoteExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.parser.ParserEmulationProfile;
import com.vladsch.flexmark.util.options.MutableDataSet;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DocumentationGeneratorTask extends DefaultTask {
    private final PebbleEngine pebble;

    private File sourceDir = null;
    private File outputDir = null;
    private Map<String, Object> properties = null;

    public DocumentationGeneratorTask() {
        final FileLoader fileLoader = new FileLoader();
        fileLoader.setPrefix(getTemporaryDir().getAbsolutePath());
        this.pebble = new PebbleEngine.Builder()
            .cacheActive(true)
            .strictVariables(false)
            .newLineTrimming(true)
            .loader(fileLoader)
            .build();
    }

    public File getSourceDir() {
        return sourceDir;
    }

    public void setSourceDir(final File sourceDir) {
        this.sourceDir = sourceDir;
    }

    public File getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(final File outputDir) {
        this.outputDir = outputDir;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(final Map<String, Object> properties) {
        this.properties = properties;
    }

    @TaskAction
    public void buildDocs() throws IOException, PebbleException {
        if (null == sourceDir) {
            throw new IllegalArgumentException("sourceDir is not defined");
        }

        if (!sourceDir.exists()) {
            throw new IllegalArgumentException("sourceDir does not exist");
        }

        if (!sourceDir.isDirectory()) {
            throw new IllegalArgumentException("sourceDir is not a directory");
        }

        final File tmpDir = getTemporaryDir();

        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        getLogger().lifecycle("Source directory is {}", sourceDir.getAbsolutePath());
        getLogger().lifecycle("Output directory is {}", outputDir.getAbsolutePath());

        final List<Path> files = Files.find(
            Paths.get(sourceDir.toURI()),
            Integer.MAX_VALUE,
            this::filterPath
        ).collect(Collectors.toList());


        MutableDataSet options = new MutableDataSet();
        options.setFrom(ParserEmulationProfile.GITHUB_DOC);
        options.set(Parser.EXTENSIONS, Arrays.asList(
            AbbreviationExtension.create(),
            DefinitionExtension.create(),
            FootnoteExtension.create(),
            TablesExtension.create(),
            TaskListExtension.create()
        ));

        options.set(HtmlRenderer.UNESCAPE_HTML_ENTITIES, true);

        Parser parser = Parser.builder(options).build();
        final HtmlRenderer.Builder builder = HtmlRenderer
            .builder(options)
            .escapeHtml(false)
            .percentEncodeUrls(false);

        final HtmlRenderer build = builder.build();
        final List<TemplateOrder> orders = new ArrayList<>();

        for (final Path file : files) {
            final Path relativize = sourceDir.toPath().relativize(file);
            final String s = relativize.toString();
            final String name;

            boolean copyOnly = false;
            if (s.toLowerCase().endsWith(".md.twig")) {
                name = s.substring(0, s.length() - 8) + ".twig";
            } else if (s.toLowerCase().endsWith(".twig")) {
                name = s;
                copyOnly = true;
            } else {
                name = s.substring(0, s.length() - 3) + ".twig";
            }

            final File file1 = new File(tmpDir, name);

            final File parent = file1.getParentFile();
            if (!parent.exists()) {
                parent.mkdirs();
            }

            final String name1 = file1.getName();

            if (!name1.startsWith("_")) {
                orders.add(new TemplateOrder(file1, name));
            }

            if (copyOnly) {
                Files.copy(
                    file.toAbsolutePath(),
                    file1.getAbsoluteFile().toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                );
                continue;
            }

            final Document document = parser.parseReader(new FileReader(file.toFile()));

            final StringWriter stringWriter = new StringWriter();
            build.render(document, stringWriter);
            final String s1 = stringWriter.toString();


            final FileWriter fileWriter = new FileWriter(file1);
            fileWriter.write(s1);
            fileWriter.flush();
            fileWriter.close();
        }

        for (final TemplateOrder order : orders) {
            PebbleTemplate compiledTemplate = pebble.getTemplate(order.relative);

            final String ssss = order.relative.substring(0, order.relative.length() - 5) + ".html";

            final File outs = new File(outputDir, ssss);
            if (!outs.getParentFile().exists()) {
                outs.getParentFile().mkdirs();
            }
            final FileWriter fileWriter = new FileWriter(outs);
            compiledTemplate.evaluate(fileWriter, properties);
            fileWriter.flush();
            fileWriter.close();
        }
    }

    private boolean filterPath(final Path filePath, final BasicFileAttributes fileAttr) {
        if (!fileAttr.isRegularFile()) {
            return false;
        }

        final String fileNameLc = filePath.getFileName().toString().toLowerCase();

        return fileNameLc.endsWith(".md") || fileNameLc.endsWith(".twig");
    }

    private class TemplateOrder {
        private final File file;
        private final String relative;

        public TemplateOrder(final File file1, final String relativize) {
            this.file = file1;
            this.relative = relativize;
        }

        public File getFile() {
            return file;
        }

        public String getRelative() {
            return relative;
        }
    }
}
