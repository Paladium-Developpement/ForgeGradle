package net.minecraftforge.gradle.user.patch;

import static net.minecraftforge.gradle.common.Constants.JAR_MERGED;
import static net.minecraftforge.gradle.user.UserConstants.CLASSIFIER_DECOMPILED;
import static net.minecraftforge.gradle.user.patch.UserPatchConstants.BINARIES_JAR;
import static net.minecraftforge.gradle.user.patch.UserPatchConstants.BINPATCHES;
import static net.minecraftforge.gradle.user.patch.UserPatchConstants.CLASSIFIER_PATCHED;
import static net.minecraftforge.gradle.user.patch.UserPatchConstants.ECLIPSE_LOCATION;
import static net.minecraftforge.gradle.user.patch.UserPatchConstants.JAR_BINPATCHED;
import static net.minecraftforge.gradle.user.patch.UserPatchConstants.JSON;
import static net.minecraftforge.gradle.user.patch.UserPatchConstants.RES_DIR;
import static net.minecraftforge.gradle.user.patch.UserPatchConstants.START_DIR;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.tools.ant.types.Commandline;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;

import net.minecraftforge.gradle.StringUtils;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.tasks.ProcessJarTask;
import net.minecraftforge.gradle.tasks.ProcessSrcJarTask;
import net.minecraftforge.gradle.tasks.RemapSourcesTask;
import net.minecraftforge.gradle.tasks.user.ApplyBinPatchesTask;
import net.minecraftforge.gradle.user.UserBasePlugin;
import net.minecraftforge.gradle.user.UserConstants;

public abstract class UserPatchBasePlugin extends UserBasePlugin<UserPatchExtension> {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void applyPlugin() {
        super.applyPlugin();

        // add the binPatching task
        {
            ApplyBinPatchesTask task = this.makeTask("applyBinPatches", ApplyBinPatchesTask.class);
            task.setInJar(this.delayedFile(JAR_MERGED));
            task.setOutJar(this.delayedFile(JAR_BINPATCHED));
            task.setPatches(this.delayedFile(BINPATCHES));
            task.setClassesJar(this.delayedFile(BINARIES_JAR));
            task.setResources(this.delayedFileTree(RES_DIR));
            task.dependsOn("mergeJars");

            this.project.getTasks().getByName("deobfBinJar").dependsOn(task);

            ProcessJarTask deobf = (ProcessJarTask) this.project.getTasks().getByName("deobfBinJar").dependsOn(task);
            ;
            deobf.setInJar(this.delayedFile(JAR_BINPATCHED));
            deobf.dependsOn(task);
        }

        // add source patching task
        {
            DelayedFile decompOut = this.delayedDirtyFile(null, CLASSIFIER_DECOMPILED, "jar", false);
            DelayedFile processed = this.delayedDirtyFile(null, CLASSIFIER_PATCHED, "jar", false);

            ProcessSrcJarTask patch = this.makeTask("processSources", ProcessSrcJarTask.class);
            patch.dependsOn("decompile");
            patch.setInJar(decompOut);
            patch.setOutJar(processed);
            this.configurePatching(patch);

            RemapSourcesTask remap = (RemapSourcesTask) this.project.getTasks().getByName("remapJar");
            remap.setInJar(processed);
            remap.dependsOn(patch);
        }

        // Delete old .classpath file, because we need it fresh and clean
        this.project.getTasks().getByName("eclipse").dependsOn(this.makeTask("deleteEclipseClasspath", DeleteClasspathTask.class));

        // configure eclipse task to do extra stuff.
        this.project.getTasks().getByName("eclipse").doLast(new Action() {

            @Override
            public void execute(Object arg0) {
                // BEGIN JEBANYE KOSTILY FOR ECLIPSE .CLASSPATH

                try {
                    File classpath = new File(UserPatchBasePlugin.this.project.getProjectDir(), ".classpath");
                    if (classpath.exists() && classpath.isFile()) {
                        UserPatchBasePlugin.this.project.getLogger().lifecycle("Located new Eclipse .classpath file: " + classpath);
                    }

                    if (StringUtils.replaceContainingString(classpath.toPath(), "bin",  "bin/default",  "bin/main")) {
                        UserPatchBasePlugin.this.project.getLogger().lifecycle("Successfully rectified duplicate output paths in .classpath");
                    } else {
                        UserPatchBasePlugin.this.project.getLogger().lifecycle("Could not rectify duplicate output paths in .classpath; not found");
                    }
                } catch (Exception ex) {
                    UserPatchBasePlugin.this.project.getLogger().error("Error when patching .classpath file:");
                    UserPatchBasePlugin.this.project.getLogger().error(ex.toString());
                }

                // END JEBANYE KOSTILY FOR ECLIPSE .CLASSPATH


                // find the file
                File f = new File(ECLIPSE_LOCATION);
                if (!f.exists())
                    return;
                File[] files = f.listFiles();
                if (files.length < 1) // empty folder
                    return;

                f = new File(files[0], ".location");

                if (f.exists()) // if .location exists
                {
                    String projectDir = "URI//" + UserPatchBasePlugin.this.project.getProjectDir().toURI().toString();
                    try {
                        byte[] LOCATION_BEFORE = new byte[]{0x40, (byte) 0xB1, (byte) 0x8B, (byte) 0x81, 0x23, (byte) 0xBC, 0x00, 0x14, 0x1A, 0x25, (byte) 0x96, (byte) 0xE7, (byte) 0xA3, (byte) 0x93, (byte) 0xBE, 0x1E};
                        byte[] LOCATION_AFTER = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xC0, 0x58, (byte) 0xFB, (byte) 0xF3, 0x23, (byte) 0xBC, 0x00, 0x14, 0x1A, 0x51, (byte) 0xF3, (byte) 0x8C, 0x7B, (byte) 0xBB, 0x77, (byte) 0xC6};

                        FileOutputStream fos = new FileOutputStream(f);
                        fos.write(LOCATION_BEFORE); //Unknown but w/e
                        fos.write((byte) ((projectDir.length() & 0xFF) >> 8));
                        fos.write((byte) ((projectDir.length() & 0xFF) >> 0));
                        fos.write(projectDir.getBytes());
                        fos.write(LOCATION_AFTER); //Unknown but w/e
                        fos.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

        });

        // Make sure to clear build cache before compiling/processing classes/resources.
        // Required for proper inflation/token replacement when building
        this.project.getTasks().getByName("sourceMainJava").dependsOn(this.makeTask("clearBuildCache", ClearBuildTask.class));
        this.project.getTasks().getByName("processResources").dependsOn(this.makeTask("clearResourcesCache", ClearResourcesTask.class));

        // Setting custom archive classifiers for some reason causes Gradle to clean
        // build output right before reobf task is executed. This should help prevent it
        Task test = this.project.getTasks().getByName("test");
        test.getInputs().dir(this.project.getBuildDir());
        test.getOutputs().dir(this.project.getBuildDir());

        Task reobf = this.project.getTasks().getByName("reobf");
        reobf.getInputs().dir(this.project.getBuildDir());
        reobf.getOutputs().dir(this.project.getBuildDir());
    }

    @Override
    public final void applyOverlayPlugin() {
        // NO-OP
    }

    @Override
    public final boolean canOverlayPlugin() {
        return false;
    }

    @Override
    public final UserPatchExtension getOverlayExtension() {
        return null; // nope.
    }

    /**
     * Allows for the configuration of tasks in AfterEvaluate
     */
    @Override
    protected void delayedTaskConfig() {
        // add src ATs
        ProcessJarTask binDeobf = (ProcessJarTask) this.project.getTasks().getByName("deobfBinJar");
        ProcessJarTask decompDeobf = (ProcessJarTask) this.project.getTasks().getByName("deobfuscateJar");

        // ATs from the ExtensionObject
        Object[] extAts = this.getExtension().getAccessTransformers().toArray();
        binDeobf.addTransformer(extAts);
        decompDeobf.addTransformer(extAts);

        // from the resources dirs
        {
            JavaPluginConvention javaConv = (JavaPluginConvention) this.project.getConvention().getPlugins().get("java");

            SourceSet main = javaConv.getSourceSets().getByName("main");
            SourceSet api = javaConv.getSourceSets().getByName("api");

            for (File at : main.getResources().getFiles()) {
                if (at.getName().toLowerCase().endsWith("_at.cfg")) {
                    this.project.getLogger().lifecycle("Found AccessTransformer in main resources: " + at.getName());
                    binDeobf.addTransformer(at);
                    decompDeobf.addTransformer(at);
                }
            }

            for (File at : api.getResources().getFiles()) {
                if (at.getName().toLowerCase().endsWith("_at.cfg")) {
                    this.project.getLogger().lifecycle("Found AccessTransformer in api resources: " + at.getName());
                    binDeobf.addTransformer(at);
                    decompDeobf.addTransformer(at);
                }
            }
        }

        // configure fuzzing.
        ProcessSrcJarTask patch = (ProcessSrcJarTask) this.project.getTasks().getByName("processSources");
        patch.setMaxFuzz(this.getExtension().getMaxFuzz());

        super.delayedTaskConfig();
    }

    @Override
    protected void doVersionChecks(String version) {
        if (version.indexOf('-') > 0)
        {
            version = version.split("-")[1]; // We get passed the full version, including MC ver and branch, we only want api's version.
        }
        int buildNumber = Integer.parseInt(version.substring(version.lastIndexOf('.') + 1));

        this.doVersionChecks(version, buildNumber);
    }

    protected abstract void doVersionChecks(String version, int buildNumber);

    @Override
    protected DelayedFile getDevJson() {
        return this.delayedFile(JSON);
    }

    @Override
    protected String getSrcDepName() {
        return this.getApiName() + "Src";
    }

    @Override
    protected String getBinDepName() {
        return this.getApiName() + "Bin";
    }

    @Override
    protected boolean hasApiVersion() {
        return true;
    }

    @Override
    protected String getApiCacheDir(UserPatchExtension exten) {
        return "{CACHE_DIR}/minecraft/" + this.getApiPath(exten) + "/{API_NAME}/{API_VERSION}";
    }

    @Override
    protected String getSrgCacheDir(UserPatchExtension exten) {
        return "{API_CACHE_DIR}/" + UserConstants.MAPPING_APPENDAGE + "srgs";
    }

    @Override
    protected String getUserDevCacheDir(UserPatchExtension exten) {
        return "{API_CACHE_DIR}/unpacked";
    }

    @Override
    protected String getUserDev() {
        return this.getApiGroup() + ":{API_NAME}:{API_VERSION}";
    }

    @Override
    protected Class<UserPatchExtension> getExtensionClass() {
        return UserPatchExtension.class;
    }

    @Override
    protected String getApiVersion(UserPatchExtension exten) {
        return exten.getApiVersion();
    }

    @Override
    protected String getMcVersion(UserPatchExtension exten) {
        return exten.getVersion();
    }

    /**
     * THIS HAPPENS EARLY!  no delay tokens or stuff!
     *
     * @return url of the version json
     */
    protected abstract String getVersionsJsonUrl();

    @Override
    protected Iterable<String> getClientRunArgs() {
        return this.getRunArgsFromProperty();
        //return ImmutableList.of("--version", "1.7", "--tweakClass", "cpw.mods.fml.common.launcher.FMLTweaker", "--username=ForgeDevName", "--accessToken", "FML", "--userProperties={}");
    }

    private Iterable<String> getRunArgsFromProperty() {
        List<String> ret = new ArrayList<String>();
        String arg = (String) this.project.getProperties().get("runArgs");
        if (arg != null) {
            ret.addAll(Arrays.asList(Commandline.translateCommandline(arg)));
        }
        return ret;
    }

    @Override
    protected Iterable<String> getServerRunArgs() {
        return this.getRunArgsFromProperty();
    }

    /**
     * Add in the desired patching stages.
     * This happens during normal evaluation, and NOT AfterEvaluate.
     *
     * @param patch patching task
     */
    protected abstract void configurePatching(ProcessSrcJarTask patch);

    /**
     * Should be with separate with periods.
     *
     * @return API group
     */
    protected abstract String getApiGroup();

    /**
     * Should be with separate with slashes.
     *
     * @param exten extension object
     * @return api path
     */
    protected String getApiPath(UserPatchExtension exten) {
        return this.getApiGroup().replace('.', '/');
    }

    @Override
    protected String getStartDir() {
        return START_DIR;
    }

    @Override
    protected String getClientRunClass() {
        return "net.minecraft.launchwrapper.Launch";
    }

    @Override
    protected String getClientTweaker() {
        return "fml.common.launcher.FMLTweaker";
    }

    @Override
    protected String getServerTweaker() {
        return "fml.common.launcher.FMLServerTweaker";
    }

    @Override
    protected String getServerRunClass() {
        return this.getClientRunClass();
    }

    @Override
    public String resolve(String pattern, Project project, UserPatchExtension exten) {
        // override tweaker and server run class.
        // do run config stuff.
        String prefix = this.getMcVersion(exten).startsWith("1.8") ? "net.minecraftforge." : "cpw.mods.";
        pattern = pattern.replace("{RUN_CLIENT_TWEAKER}", prefix + this.getClientTweaker());
        pattern = pattern.replace("{RUN_SERVER_TWEAKER}", prefix + this.getServerTweaker());

        pattern = super.resolve(pattern, project, exten);

        return pattern;
    }

    @Override
    protected void configurePostDecomp(boolean decomp, boolean remove) {
        super.configurePostDecomp(decomp, remove);

        if (decomp && remove) {
            (this.project.getTasks().getByName("applyBinPatches")).onlyIf(Constants.CALL_FALSE);
        }
    }

    public static class ClearBuildTask extends Delete {
        public ClearBuildTask() {
            super();
            this.delete(this.getProject().file(this.getProject().getBuildDir().getName() + "/classes/main"));
            this.delete(this.getProject().file(this.getProject().getBuildDir().getName() + "/classes/java/main"));
        }
    }

    public static class ClearResourcesTask extends Delete {
        public ClearResourcesTask() {
            super();
            this.delete(this.getProject().file(this.getProject().getBuildDir().getName() + "/resources/main"));
        }
    }

    public static class CopySrgsTask extends Copy {
        public CopySrgsTask() {
            super();
        }

        public void init(UserPatchBasePlugin forgePlugin) {
            this.from(forgePlugin.delayedFile("{SRG_DIR}"));

            this.include("**/*.srg");
            this.into(this.getProject().getBuildDir().getName() + "/srgs");
        }

        @TaskAction
        public void doTask() {
            // NO-OP
        }
    }

    /**
     * Sgorel saray - gori i hata.
     * @author Integral
     */

    public static class DeleteClasspathTask extends DefaultTask {
        public DeleteClasspathTask() {
            super();
        }

        @TaskAction
        public void doTask() {
            try {
                File classpath = new File(this.getProject().getProjectDir(), ".classpath");

                if (classpath.exists() && classpath.isFile()) {
                    classpath.delete();
                    this.getProject().getLogger().lifecycle("Deleted old Eclipse .classpath file");
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }
}
