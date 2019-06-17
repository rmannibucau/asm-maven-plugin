package com.github.rmannibucau.maven.asm;

import static java.util.stream.Collectors.toList;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.stream.Stream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

public abstract class BaseMojo extends AbstractMojo {
    /**
     * Should the plugin be skipped.
     */
    @Parameter(defaultValue = "false", property = "rmannibucau.asm.skip")
    protected boolean skip;

    /**
     * Binaries to work on (typically target/classes).
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", property = "rmannibucau.asm.classes")
    protected File classes;

    /**
     * Current maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    /**
     * Packaging type, mainly used to skip pom packaging.
     */
    @Parameter(defaultValue = "${project.packaging}", readonly = true)
    protected String packaging;

    protected abstract void doExecute(ClassLoader loader) throws IOException;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info(getClass().getSimpleName() + " is skipped");
            return;
        }
        if ("pom".equals(packaging)) {
            getLog().info("Skipping modules with packaging pom");
            return;
        }
        if (!classes.isDirectory()) {
            getLog().warn(classes + " is not a directory, skipping. Maybe ensure the bound phase for this plugin.");
            return;
        }
        final Thread thread = Thread.currentThread();
        final ClassLoader pluginLoader = thread.getContextClassLoader();
        try (final URLClassLoader loader = new URLClassLoader(getURLs(), pluginLoader) {{
            thread.setContextClassLoader(this);
        }}) {
            doExecute(loader);
        } catch (final RuntimeException | IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } finally {
            thread.setContextClassLoader(pluginLoader);
        }
    }

    private URL[] getURLs() {
        return Stream.concat(
                Stream.of(classes),
                project.getArtifacts().stream().map(Artifact::getFile))
                .collect(toList()).stream().map(file -> {
            try {
                return file.toURI().toURL();
            } catch (final MalformedURLException e) {
                throw new IllegalStateException(e.getMessage());
            }
        }).toArray(URL[]::new);
    }
}
