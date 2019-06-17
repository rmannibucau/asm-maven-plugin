package com.github.rmannibucau.maven.asm;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;
import static org.apache.maven.plugins.annotations.LifecyclePhase.PROCESS_CLASSES;
import static org.apache.maven.plugins.annotations.ResolutionScope.COMPILE_PLUS_RUNTIME;
import static org.apache.xbean.asm7.ClassReader.EXPAND_FRAMES;
import static org.apache.xbean.asm7.ClassWriter.COMPUTE_FRAMES;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.xbean.asm7.ClassReader;
import org.apache.xbean.asm7.ClassWriter;
import org.apache.xbean.asm7.commons.ClassRemapper;
import org.apache.xbean.asm7.commons.SimpleRemapper;

@Mojo(name = "process", defaultPhase = PROCESS_CLASSES, requiresDependencyResolution = COMPILE_PLUS_RUNTIME, threadSafe = true)
public class AsmMojo extends BaseMojo {
    /**
     * The mapping to apply on current classes.
     */
    @Parameter(property = "rmannibucau.asm.mapping")
    private Map<String, String> mapping;

    /**
     * Where to dump the rewritten classes.
     */
    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}_asmmojo_work", property = "rmannibucau.asm.target")
    private File target;

    /**
     * Should a jar be created from the rewritten classes.
     * If set to false, the file are modified inline.
     */
    @Parameter(defaultValue = "true", property = "rmannibucau.asm.attach")
    private boolean attach;

    /**
     * Classifier to use for the rewritten classes artifact.
     */
    @Parameter(defaultValue = "remapped", property = "rmannibucau.asm.classifier")
    private String classifier;

    /**
     * The helper to attach the rewritten classes artifact.
     */
    @Component
    private MavenProjectHelper helper;

    @Override
    protected void doExecute(final ClassLoader loader) throws IOException {
        if (mapping == null) {
            getLog().warn("No mapping, skipping");
            return;
        }

        if (attach && !target.exists() && !target.mkdirs()) {
            throw new IOException("Can't create '" + target + "'");
        }

        final Map<String, String> runtimeMapping = mapping.entrySet().stream()
                .collect(toMap(e -> sanitizeMapping(e.getKey()), e -> sanitizeMapping(e.getValue())));
        final Path root = classes.toPath();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().endsWith(".class")) {
                    if (remap(file, root.relativize(file).toAbsolutePath().toString(), it -> replace(runtimeMapping, it), loader)) {
                        getLog().debug("Remapped '" + root.relativize(file).toAbsolutePath() + "'");
                    }
                }
                return super.visitFile(file, attrs);
            }
        });

        if (attach) {
            if (!target.exists()) {
                throw new IOException("No '" + target + "', can't attach it");
            }
            // create a jar from target
            final Path jar = buildJar(root);
            getLog().info("Attaching '" + jar + "' with classifier '" + classifier + "'");
            helper.attachArtifact(project, "jar", classifier, jar.toFile());
        }
    }

    private String sanitizeMapping(final String key) {
        return key.replace('.', '/');
    }

    private Path buildJar(final Path root) throws IOException {
        final Path jar = target.toPath().getParent().resolve(target.getName() + ".jar");
        final OutputStream jarStream = Files.newOutputStream(jar);
        final Path manifestPath = root.resolve(JarFile.MANIFEST_NAME);
        final Manifest manifest = Files.exists(manifestPath) ? loadManifest(manifestPath) : null;
        final Set<String> dirs = new HashSet<>();
        try (final JarOutputStream jarOutputStream = manifest == null ?
                new JarOutputStream(jarStream) : new JarOutputStream(jarStream, manifest)) {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    final String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                    if (JarFile.MANIFEST_NAME.equalsIgnoreCase(relative)) { // already handled
                        return FileVisitResult.CONTINUE;
                    }
                    if (file.getFileName().toString().endsWith(".class")) {
                        final Path remapped = target.toPath().resolve(relative);
                        if (Files.exists(remapped)) {
                            add(dirs, jarOutputStream, remapped, relative);
                        } else {
                            add(dirs, jarOutputStream, file, relative);
                        }
                    } else { // use original file
                        add(dirs, jarOutputStream, file, relative);
                    }
                    return super.visitFile(file, attrs);
                }
            });
        }
        return jar;
    }

    private String replace(final Map<String, String> runtimeMapping, final String from) {
        boolean changed;
        String out = from;
        do {
            changed = false;
            for (final Map.Entry<String, String> entry : runtimeMapping.entrySet()) {
                final String pattern = entry.getKey();
                if (pattern.endsWith(":all")) {
                    final String prefix = pattern.substring(0, pattern.length() - ":all".length());
                    if (out.startsWith(prefix)) {
                        changed = true;
                        out = entry.getValue() + out.substring(prefix.length());
                    }
                } else if (out.equals(pattern)) {
                    changed = true;
                    out = entry.getValue();
                }
            }
        } while (changed);
        return out;
    }

    private boolean remap(final Path file, final String relative, final Function<String, String> replacer,
                          final ClassLoader loader) throws IOException {
        final AtomicBoolean remapped = new AtomicBoolean(false);
        final ClassWriter writer = new ClassWriter(COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                Class<?> c;
                final Class<?> d;
                try {
                    c = findClass(type1.replace('/', '.'));
                    d = findClass(type2.replace('/', '.'));
                } catch (final Exception e) {
                    throw new RuntimeException(e.toString());
                } catch (final ClassCircularityError e) {
                    return "java/lang/Object";
                }
                if (c.isAssignableFrom(d)) {
                    return type1;
                }
                if (d.isAssignableFrom(c)) {
                    return type2;
                }
                if (c.isInterface() || d.isInterface()) {
                    return "java/lang/Object";
                } else {
                    do {
                        c = c.getSuperclass();
                    } while (!c.isAssignableFrom(d));
                    return c.getName().replace('.', '/');
                }
            }

            private Class<?> findClass(final String className) throws ClassNotFoundException {
                try {
                    return Class.forName(className, false, loader);
                } catch (final ClassNotFoundException e) {
                    return Class.forName(className, false, getClass().getClassLoader());
                }
            }
        };
        try (final InputStream is = Files.newInputStream(file)) {
            final ClassReader reader = new ClassReader(is);
            reader.accept(new ClassRemapper(writer, new SimpleRemapper(mapping) {
                @Override
                public String map(final String key) {
                    final String out = replacer.apply(key);
                    if (!remapped.get() && out != null && !out.equals(key)) {
                        remapped.set(true);
                    }
                    return out;
                }
            }), EXPAND_FRAMES);
        }
        final OutputStream os;
        if (attach) {
            final Path to = target.toPath().resolve(relative);
            if (!Files.exists(to.getParent())) {
                Files.createDirectories(to.getParent());
            }
            os = Files.newOutputStream(to);
        } else {
            os = Files.newOutputStream(file);
        }
        try (final OutputStream out = os) {
            out.write(writer.toByteArray());
        }
        return remapped.get();
    }

    private Manifest loadManifest(final Path manifestPath) throws IOException {
        try (final InputStream stream = Files.newInputStream(manifestPath)) {
            return new Manifest(stream);
        }
    }

    private void add(final Set<String> dirs, final JarOutputStream jar, final Path file, final String name) throws IOException {
        final String[] segments = name.split("/");
        IntStream.range(1, segments.length)
                .mapToObj(count -> Stream.of(segments).limit(count).collect(joining("/", "", "/")))
                .filter(dirs::add)
                .forEach(dir -> {
                    try {
                        jar.putNextEntry(new JarEntry(dir));
                        jar.closeEntry();
                    } catch (final IOException ioe) {
                        throw new IllegalStateException(ioe);
                    }
                });
        jar.putNextEntry(new JarEntry(name));
        Files.copy(file, jar);
        jar.closeEntry();
    }
}
