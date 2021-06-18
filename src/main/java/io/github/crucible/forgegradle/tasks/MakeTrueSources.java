package io.github.crucible.forgegradle.tasks;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import com.github.abrarsyed.jastyle.ASFormatter;
import com.github.abrarsyed.jastyle.OptParser;
import com.github.abrarsyed.jastyle.constants.EnumFormatStyle;
import com.google.common.io.ByteStreams;

import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.tasks.abstractutil.CachedTask;

public class MakeTrueSources extends CachedTask {

    @InputFile
    private DelayedFile inJar;

    @InputFile
    private DelayedFile astyleConfig;

    @OutputFile
    @Cached
    private DelayedFile outJar;

    private HashMap<String, String> sourceMap = new HashMap<String, String>();

    @TaskAction
    protected void doMCPStuff() throws Throwable {
        this.getLogger().lifecycle("Loading untrue sources: " + this.getInJar().getCanonicalPath());
        this.readJarAndFix(this.getInJar());

        this.getLogger().lifecycle("Cleaning source");
        this.applyMcpCleanup(this.getAstyleConfig());

        this.getLogger().lifecycle("Saving Jar: " + this.getOutJar().getCanonicalPath());
        this.saveJar(this.getOutJar());
    }

    private void readJarAndFix(final File jar) throws IOException {
        this.getProject().getLogger().lifecycle("Begin reading jar...");
        // begin reading jar
        final ZipInputStream zin = new ZipInputStream(new FileInputStream(jar));
        ZipEntry entry = null;
        String fileStr;

        while ((entry = zin.getNextEntry()) != null) {
            // no META or dirs. wel take care of dirs later.
            if (entry.getName().contains("META-INF")) {
                continue;
            }

            // resources or directories.
            if (entry.isDirectory() || !entry.getName().endsWith(".java")) {
                // NO-OP
            } else {
                // source!
                fileStr = new String(ByteStreams.toByteArray(zin), Charset.defaultCharset());

                this.sourceMap.put(entry.getName(), fileStr);
            }
        }

        zin.close();
    }

    private void applyMcpCleanup(File conf) throws IOException {
        ASFormatter formatter = new ASFormatter();
        OptParser parser = new OptParser(formatter);
        parser.parseOptionFile(conf);

        Reader reader;
        Writer writer;

        formatter.setFormattingStyle(EnumFormatStyle.JAVA);

        ArrayList<String> files = new ArrayList<String>(this.sourceMap.keySet());
        Collections.sort(files); // Just to make sure we have the same order.. shouldn't matter on anything but lets be careful.

        for (String file : files) {
            String text = this.sourceMap.get(file);

            this.getLogger().debug("Processing file: " + file);

            this.getLogger().debug("formatting source");
            reader = new StringReader(text);
            writer = new StringWriter();
            formatter.format(reader, writer);
            reader.close();
            writer.flush();
            writer.close();
            text = writer.toString();

            this.sourceMap.put(file, text);
        }
    }

    private void saveJar(File output) throws IOException {
        ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(output));

        // write in sources
        for (Map.Entry<String, String> entry : this.sourceMap.entrySet()) {
            zout.putNextEntry(new ZipEntry(entry.getKey()));
            zout.write(entry.getValue().getBytes());
            zout.closeEntry();
        }

        zout.close();
    }

    public HashMap<String, String> getSourceMap() {
        return this.sourceMap;
    }

    public void setSourceMap(HashMap<String, String> sourceMap) {
        this.sourceMap = sourceMap;
    }

    public File getAstyleConfig() {
        return this.astyleConfig.call();
    }

    public void setAstyleConfig(DelayedFile astyleConfig) {
        this.astyleConfig = astyleConfig;
    }

    public File getInJar() {
        return this.inJar.call();
    }

    public void setInJar(DelayedFile inJar) {
        this.inJar = inJar;
    }

    public File getOutJar() {
        return this.outJar.call();
    }

    public void setOutJar(DelayedFile outJar) {
        this.outJar = outJar;
    }

}
