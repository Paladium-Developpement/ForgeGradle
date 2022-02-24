package net.minecraftforge.gradle.user;

import static net.minecraftforge.gradle.common.Constants.FERNFLOWER;
import static net.minecraftforge.gradle.common.Constants.JAR_CLIENT_FRESH;
import static net.minecraftforge.gradle.common.Constants.JAR_MERGED;
import static net.minecraftforge.gradle.common.Constants.JAR_SERVER_FRESH;
import static net.minecraftforge.gradle.user.UserConstants.ASTYLE_CFG;
import static net.minecraftforge.gradle.user.UserConstants.CLASSIFIER_DECOMPILED;
import static net.minecraftforge.gradle.user.UserConstants.CLASSIFIER_DEOBF_SRG;
import static net.minecraftforge.gradle.user.UserConstants.CLASSIFIER_SOURCES;
import static net.minecraftforge.gradle.user.UserConstants.CONFIG_DEPS;
import static net.minecraftforge.gradle.user.UserConstants.CONFIG_MC;
import static net.minecraftforge.gradle.user.UserConstants.CONFIG_NATIVES;
import static net.minecraftforge.gradle.user.UserConstants.CONFIG_START;
import static net.minecraftforge.gradle.user.UserConstants.CONFIG_USERDEV;
import static net.minecraftforge.gradle.user.UserConstants.CONF_DIR;
import static net.minecraftforge.gradle.user.UserConstants.DEOBF_MCP_SRG;
import static net.minecraftforge.gradle.user.UserConstants.DEOBF_SRG_MCP_SRG;
import static net.minecraftforge.gradle.user.UserConstants.DEOBF_SRG_SRG;
import static net.minecraftforge.gradle.user.UserConstants.DIRTY_DIR;
import static net.minecraftforge.gradle.user.UserConstants.EXC_JSON;
import static net.minecraftforge.gradle.user.UserConstants.EXC_MCP;
import static net.minecraftforge.gradle.user.UserConstants.EXC_SRG;
import static net.minecraftforge.gradle.user.UserConstants.FIELD_CSV;
import static net.minecraftforge.gradle.user.UserConstants.GRADLE_START_CLIENT;
import static net.minecraftforge.gradle.user.UserConstants.GRADLE_START_SERVER;
import static net.minecraftforge.gradle.user.UserConstants.MAPPING_APPENDAGE;
import static net.minecraftforge.gradle.user.UserConstants.MCP_PATCH_DIR;
import static net.minecraftforge.gradle.user.UserConstants.MERGE_CFG;
import static net.minecraftforge.gradle.user.UserConstants.METHOD_CSV;
import static net.minecraftforge.gradle.user.UserConstants.PACKAGED_EXC;
import static net.minecraftforge.gradle.user.UserConstants.PACKAGED_SRG;
import static net.minecraftforge.gradle.user.UserConstants.PARAM_CSV;
import static net.minecraftforge.gradle.user.UserConstants.RECOMP_CLS_DIR;
import static net.minecraftforge.gradle.user.UserConstants.RECOMP_SRC_DIR;
import static net.minecraftforge.gradle.user.UserConstants.REOBF_NOTCH_SRG;
import static net.minecraftforge.gradle.user.UserConstants.REOBF_SRG;
import static net.minecraftforge.gradle.user.UserConstants.SOURCES_DIR;

import java.io.File;
import java.io.IOException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.XmlProvider;
import org.gradle.api.artifacts.Configuration.State;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.execution.TaskExecutionGraph;
import org.gradle.api.internal.ConventionTask;
import org.gradle.api.internal.plugins.DslObject;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.GroovySourceSet;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.ScalaSourceSet;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.api.tasks.compile.GroovyCompile;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.scala.ScalaCompile;
import org.gradle.plugins.ide.idea.model.IdeaModel;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;

import groovy.lang.Closure;
import io.github.crucible.forgegradle.tasks.MakeTrueSources;
import io.github.crucible.forgegradle.tasks.signum.RestoreGenericSignatures;
import net.minecraftforge.gradle.common.BasePlugin;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.json.JsonFactory;
import net.minecraftforge.gradle.tasks.CreateStartTask;
import net.minecraftforge.gradle.tasks.DecompileTask;
import net.minecraftforge.gradle.tasks.ExtractConfigTask;
import net.minecraftforge.gradle.tasks.GenSrgTask;
import net.minecraftforge.gradle.tasks.MergeJarsTask;
import net.minecraftforge.gradle.tasks.ProcessJarTask;
import net.minecraftforge.gradle.tasks.RemapSourcesTask;
import net.minecraftforge.gradle.tasks.abstractutil.ExtractTask;
import net.minecraftforge.gradle.tasks.user.SourceCopyTask;
import net.minecraftforge.gradle.tasks.user.reobf.ArtifactSpec;
import net.minecraftforge.gradle.tasks.user.reobf.ReobfTask;

public abstract class UserBasePlugin<T extends UserExtension> extends BasePlugin<T> {
    @SuppressWarnings("serial")
    @Override
    public void applyPlugin() {
        this.applyExternalPlugin("java");
        this.applyExternalPlugin("maven");
        this.applyExternalPlugin("eclipse");
        this.applyExternalPlugin("idea");

        this.hasScalaBefore = this.project.getPlugins().hasPlugin("scala");
        this.hasGroovyBefore = this.project.getPlugins().hasPlugin("groovy");

        this.addGitIgnore(); //Morons -.-

        this.configureDeps();
        this.configureCompilation();
        this.configureIntellij();

        // create basic tasks.
        this.tasks();

        // create lifecycle tasks.

        Task task = this.makeTask("setupCIWorkspace", DefaultTask.class);
        task.dependsOn("genSrgs", "deobfBinJar");
        task.setDescription("Sets up the bare minimum to build a minecraft mod. Idea for CI servers");
        task.setGroup("ForgeGradle");
        //configureCISetup(task);

        task = this.makeTask("setupDevWorkspace", DefaultTask.class);
        task.dependsOn("genSrgs", "deobfBinJar", "makeStart");
        task.setDescription("CIWorkspace + natives and assets to run and test Minecraft");
        task.setGroup("ForgeGradle");
        //configureDevSetup(task);

        task = this.makeTask("setupDecompWorkspace", DefaultTask.class);
        task.dependsOn("genSrgs", "makeStart", "repackMinecraft");
        task.setDescription("DevWorkspace + the deobfuscated Minecraft source linked as a source jar.");
        task.setGroup("ForgeGradle");
        //configureDecompSetup(task);

        this.project.getGradle().getTaskGraph().whenReady(new Closure<Object>(this, null) {
            @Override
            public Object call() {
                TaskExecutionGraph graph = UserBasePlugin.this.project.getGradle().getTaskGraph();
                String path = UserBasePlugin.this.project.getPath();

                if (graph.hasTask(path + "setupDecompWorkspace")) {
                    UserBasePlugin.this.getExtension().setDecomp();
                    UserBasePlugin.this.configurePostDecomp(true, true);
                }
                return null;
            }

            @Override
            public Object call(Object obj) {
                return this.call();
            }

            @Override
            public Object call(Object... obj) {
                return this.call();
            }
        });
    }

    private boolean hasAppliedJson = false;
    private boolean hasScalaBefore = false;
    private boolean hasGroovyBefore = false;

    /**
     * may not include delayed tokens.
     *
     * @return name of API, eg "forge", "fml", "liteloader"
     */
    public abstract String getApiName();

    /**
     * Name of the source dependency.  eg: forgeSrc
     * may not include delayed tokens.
     *
     * @return the name of the recompiled dependency
     */
    protected abstract String getSrcDepName();

    /**
     * Name of the source dependency.  eg: forgeBin
     * may not include delayed tokens.
     *
     * @return the name of the bin-patched dependency
     */
    protected abstract String getBinDepName();

    /**
     * May invoke the extension object, or be hardcoded.
     * may not include delayed tokens.
     *
     * @return has an api version
     */
    protected abstract boolean hasApiVersion();

    /**
     * May invoke the extension object, or be hardcoded.
     * may not include delayed tokens.
     *
     * @param exten the extension object
     * @return the api version
     */
    protected abstract String getApiVersion(T exten);

    /**
     * May invoke the extension object, or be hardcoded.
     * may not include delayed tokens.
     *
     * @param exten the extension object
     * @return the MC version
     */
    protected abstract String getMcVersion(T exten);

    /**
     * May invoke the extension object, or be hardcoded.
     * This unlike the others, is evaluated as a delayed file, and may contain various tokens including:
     * {API_NAME} {API_VERSION} {MC_VERSION}
     *
     * @param exten the extension object
     * @return the API cache dir
     */
    protected abstract String getApiCacheDir(T exten);

    /**
     * May invoke the extension object, or be hardcoded.
     * This unlike the others, is evaluated as a delayed file, and may contain various tokens including:
     * {API_NAME} {API_VERSION} {MC_VERSION}
     *
     * @param exten the extension object
     * @return the SRG cache dir
     */
    protected abstract String getSrgCacheDir(T exten);

    /**
     * May invoke the extension object, or be hardcoded.
     * This unlike the others, is evaluated as a delayed file, and may contain various tokens including:
     * {API_NAME} {API_VERSION} {MC_VERSION}
     *
     * @param exten the extension object
     * @return the userdev cache dir
     */
    protected abstract String getUserDevCacheDir(T exten);

    /**
     * This unlike the others, is evaluated as a delayed string, and may contain various tokens including:
     * {API_NAME} {API_VERSION} {MC_VERSION}
     *
     * @return the userdev dep string
     */
    protected abstract String getUserDev();

    /**
     * For run configurations. Is delayed.
     *
     * @return the client tweaker class name
     */
    protected abstract String getClientTweaker();

    /**
     * For run configurations. Is delayed.
     *
     * @return the server tweaker class name
     */
    protected abstract String getServerTweaker();

    /**
     * For run configurations
     *
     * @return the start location
     */
    protected abstract String getStartDir();

    /**
     * For run configurations. Is delayed.
     *
     * @return the client main class name
     */
    protected abstract String getClientRunClass();

    /**
     * For run configurations
     *
     * @return the client run arguments
     */
    protected abstract Iterable<String> getClientRunArgs();

    /**
     * For run configurations. Is delayed.
     *
     * @return the server main class name
     */
    protected abstract String getServerRunClass();

    /**
     * For run configurations
     *
     * @return the server run arguments
     */
    protected abstract Iterable<String> getServerRunArgs();

    //    protected abstract void configureCISetup(Task task);
    //    protected abstract void configureDevSetup(Task task);
    //    protected abstract void configureDecompSetup(Task task);

    @Override
    public String resolve(String pattern, Project project, T exten) {
        pattern = super.resolve(pattern, project, exten);

        pattern = pattern.replace("{MCP_DATA_DIR}", CONF_DIR);
        pattern = pattern.replace("{USER_DEV}", this.getUserDevCacheDir(exten));
        pattern = pattern.replace("{SRG_DIR}", this.getSrgCacheDir(exten));
        pattern = pattern.replace("{API_CACHE_DIR}", this.getApiCacheDir(exten));
        pattern = pattern.replace("{MC_VERSION}", this.getMcVersion(exten));

        // do run config stuff.
        pattern = pattern.replace("{RUN_CLIENT_TWEAKER}", this.getClientTweaker());
        pattern = pattern.replace("{RUN_SERVER_TWEAKER}", this.getServerTweaker());
        pattern = pattern.replace("{RUN_BOUNCE_CLIENT}", this.getClientRunClass());
        pattern = pattern.replace("{RUN_BOUNCE_SERVER}", this.getServerRunClass());

        if (!exten.mappingsSet()) {
            // no mappings set?remove these tokens
            pattern = pattern.replace("{MAPPING_CHANNEL}", "");
            pattern = pattern.replace("{MAPPING_VERSION}", "");
        }

        if (this.hasApiVersion()) {
            pattern = pattern.replace("{API_VERSION}", this.getApiVersion(exten));
        }

        pattern = pattern.replace("{API_NAME}", this.getApiName());
        return pattern;
    }

    protected void configureDeps() {
        // create configs
        this.project.getConfigurations().create(CONFIG_USERDEV);
        this.project.getConfigurations().create(CONFIG_NATIVES);
        this.project.getConfigurations().create(CONFIG_START);
        this.project.getConfigurations().create(CONFIG_DEPS);
        this.project.getConfigurations().create(CONFIG_MC);

        // special userDev stuff
        ExtractConfigTask extractUserDev = this.makeTask("extractUserDev", ExtractConfigTask.class);
        extractUserDev.setOut(this.delayedFile("{USER_DEV}"));
        extractUserDev.setConfig(CONFIG_USERDEV);
        extractUserDev.setDoesCache(true);
        extractUserDev.dependsOn("getVersionJson");
        extractUserDev.doLast(new Action<Task>() {
            @Override
            public void execute(Task arg0) {
                UserBasePlugin.this.readAndApplyJson(UserBasePlugin.this.getDevJson().call(), CONFIG_DEPS, CONFIG_NATIVES, arg0.getLogger());
            }
        });
        this.project.getTasks().findByName("getAssetsIndex").dependsOn("extractUserDev");

        // special native stuff
        ExtractConfigTask extractNatives = this.makeTask("extractNatives", ExtractConfigTask.class);
        extractNatives.setOut(this.delayedFile(Constants.NATIVES_DIR));
        extractNatives.setConfig(CONFIG_NATIVES);
        extractNatives.exclude("META-INF/**", "META-INF/**");
        extractNatives.doesCache();
        extractNatives.dependsOn("extractUserDev");

        // special gradleStart stuff
        this.project.getDependencies().add(CONFIG_START, this.project.files(this.delayedFile(this.getStartDir())));

        // extra libs folder.
        this.project.getDependencies().add("compile", this.project.fileTree("libs"));

        // make MC dependencies into normal compile classpath
        this.project.getDependencies().add("compile", this.project.getConfigurations().getByName(CONFIG_DEPS));
        this.project.getDependencies().add("compile", this.project.getConfigurations().getByName(CONFIG_MC));
        this.project.getDependencies().add("runtime", this.project.getConfigurations().getByName(CONFIG_START));
    }

    /**
     * This mod adds the API sourceSet, and correctly configures the
     */
    protected void configureCompilation() {
        // get conventions
        JavaPluginConvention javaConv = (JavaPluginConvention) this.project.getConvention().getPlugins().get("java");

        SourceSet main = javaConv.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet test = javaConv.getSourceSets().getByName(SourceSet.TEST_SOURCE_SET_NAME);
        SourceSet api = javaConv.getSourceSets().create("api");

        // set the Source
        javaConv.setSourceCompatibility("1.6");
        javaConv.setTargetCompatibility("1.6");

        main.setCompileClasspath(main.getCompileClasspath().plus(api.getOutput()));
        test.setCompileClasspath(test.getCompileClasspath().plus(api.getOutput()));

        this.project.getConfigurations().getByName("apiCompile").extendsFrom(this.project.getConfigurations().getByName("compile"));
        this.project.getConfigurations().getByName("testCompile").extendsFrom(this.project.getConfigurations().getByName("apiCompile"));

        // set compile not to take from libs
        File dirRoot_main = new File(this.project.getBuildDir(), "sources/"+main.getName());
        File dir_main = new File(dirRoot_main, "java");

        File dirRoot_test = new File(this.project.getBuildDir(), "sources/"+test.getName());
        File dir_test = new File(dirRoot_test, "java");

        File dirRoot_api = new File(this.project.getBuildDir(), "sources/"+api.getName());
        File dir_api = new File(dirRoot_api, "java");

        JavaCompile compileTask = ((JavaCompile) this.project.getTasks().getByName(main.getCompileJavaTaskName()));
        compileTask.source(dir_main, dir_test, dir_api);
    }

    private void readAndApplyJson(File file, String depConfig, String nativeConfig, Logger log) {
        if (this.version == null) {
            try {
                this.version = JsonFactory.loadVersion(file, this.delayedFile(Constants.JSONS_DIR).call());
            } catch (Exception e) {
                log.error("" + file + " could not be parsed");
                Throwables.propagate(e);
            }
        }

        if (this.hasAppliedJson)
            return;

        // apply the dep info.
        DependencyHandler handler = this.project.getDependencies();

        // actual dependencies
        if (this.project.getConfigurations().getByName(depConfig).getState() == State.UNRESOLVED) {
            for (net.minecraftforge.gradle.json.version.Library lib : this.version.getLibraries()) {
                if (lib.natives == null) {
                    handler.add(depConfig, lib.getArtifactName());
                }
            }
        } else {
            log.debug("RESOLVED: " + depConfig);
        }

        // the natives
        if (this.project.getConfigurations().getByName(nativeConfig).getState() == State.UNRESOLVED) {
            for (net.minecraftforge.gradle.json.version.Library lib : this.version.getLibraries()) {
                if (lib.natives != null) {
                    handler.add(nativeConfig, lib.getArtifactName());
                }
            }
        } else {
            log.debug("RESOLVED: " + nativeConfig);
        }

        this.hasAppliedJson = true;
    }

    @SuppressWarnings("serial")
    protected void configureIntellij() {
        IdeaModel ideaConv = (IdeaModel) this.project.getExtensions().getByName("idea");

        ideaConv.getModule().getExcludeDirs().addAll(this.project.files(".gradle", "build", ".idea").getFiles());
        ideaConv.getModule().setDownloadJavadoc(true);
        ideaConv.getModule().setDownloadSources(true);

        // fix the idea bug
        ideaConv.getModule().setInheritOutputDirs(true);

        Task task = this.makeTask("genIntellijRuns", DefaultTask.class);
        task.doLast(new Action<Task>() {
            @Override
            public void execute(Task task) {
                try {
                    String module = task.getProject().getProjectDir().getCanonicalPath();

                    File root = task.getProject().getProjectDir().getCanonicalFile();
                    File file = null;
                    while (file == null && !root.equals(task.getProject().getRootProject().getProjectDir().getCanonicalFile().getParentFile())) {
                        file = new File(root, ".idea/workspace.xml");
                        if (!file.exists()) {
                            file = null;
                            // find iws file
                            for (File f : root.listFiles()) {
                                if (f.isFile() && f.getName().endsWith(".iws")) {
                                    file = f;
                                    break;
                                }
                            }
                        }

                        root = root.getParentFile();
                    }

                    if (file == null || !file.exists())
                        throw new RuntimeException("Intellij workspace file could not be found! are you sure you imported the project into intellij?");

                    DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
                    Document doc = docBuilder.parse(file);

                    UserBasePlugin.this.injectIntellijRuns(doc, module);

                    // write the content into xml file
                    TransformerFactory transformerFactory = TransformerFactory.newInstance();
                    Transformer transformer = transformerFactory.newTransformer();
                    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
                    transformer.setOutputProperty(OutputKeys.METHOD, "xml");
                    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

                    DOMSource source = new DOMSource(doc);
                    StreamResult result = new StreamResult(file);
                    //StreamResult result = new StreamResult(System.out);

                    transformer.transform(source, result);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        if (ideaConv.getWorkspace().getIws() == null)
            return;

        ideaConv.getWorkspace().getIws().withXml(new Closure<Object>(this, null) {
            @Override
            public Object call(Object... obj) {
                Element root = ((XmlProvider) this.getDelegate()).asElement();
                Document doc = root.getOwnerDocument();
                try {
                    UserBasePlugin.this.injectIntellijRuns(doc, UserBasePlugin.this.project.getProjectDir().getCanonicalPath());
                } catch (Exception e) {
                    e.printStackTrace();
                }

                return null;
            }
        });
    }

    public final void injectIntellijRuns(Document doc, String module) throws DOMException, IOException {
        Element root = null;

        {
            NodeList list = doc.getElementsByTagName("component");
            for (int i = 0; i < list.getLength(); i++) {
                Element e = (Element) list.item(i);
                if ("RunManager".equals(e.getAttribute("name"))) {
                    root = e;
                    break;
                }
            }
        }

        String[][] config = new String[][]
                {
            new String[]
                    {
                            "Minecraft Client",
                            GRADLE_START_CLIENT,
                            "-Xincgc -Xmx1024M -Xms1024M",
                            Joiner.on(' ').join(this.getClientRunArgs())
                    },
                    new String[]
                            {
                                    "Minecraft Server",
                                    GRADLE_START_SERVER,
                                    "-Xincgc -Dfml.ignoreInvalidMinecraftCertificates=true",
                                    Joiner.on(' ').join(this.getServerRunArgs())
                            }
                };

                for (String[] data : config) {
                    Element child = this.add(root, "configuration",
                            "default", "false",
                            "name", data[0],
                            "type", "Application",
                            "factoryName", "Application",
                            "default", "false");

                    this.add(child, "extension",
                            "name", "coverage",
                            "enabled", "false",
                            "sample_coverage", "true",
                            "runner", "idea");
                    this.add(child, "option", "name", "MAIN_CLASS_NAME", "value", data[1]);
                    this.add(child, "option", "name", "VM_PARAMETERS", "value", data[2]);
                    this.add(child, "option", "name", "PROGRAM_PARAMETERS", "value", data[3]);
                    this.add(child, "option", "name", "WORKING_DIRECTORY", "value", "file://" + this.delayedFile("{RUN_DIR}").call().getCanonicalPath().replace(module, "$PROJECT_DIR$"));
                    this.add(child, "option", "name", "ALTERNATIVE_JRE_PATH_ENABLED", "value", "false");
                    this.add(child, "option", "name", "ALTERNATIVE_JRE_PATH", "value", "");
                    this.add(child, "option", "name", "ENABLE_SWING_INSPECTOR", "value", "false");
                    this.add(child, "option", "name", "ENV_VARIABLES");
                    this.add(child, "option", "name", "PASS_PARENT_ENVS", "value", "true");
                    this.add(child, "module", "name", ((IdeaModel) this.project.getExtensions().getByName("idea")).getModule().getName());
                    this.add(child, "envs");
                    this.add(child, "RunnerSettings", "RunnerId", "Run");
                    this.add(child, "ConfigurationWrapper", "RunnerId", "Run");
                    this.add(child, "method");
                }
                File f = this.delayedFile("{RUN_DIR}").call();
                if (!f.exists()) {
                    f.mkdirs();
                }
    }

    private Element add(Element parent, String name, String... values) {
        Element e = parent.getOwnerDocument().createElement(name);
        for (int x = 0; x < values.length; x += 2) {
            e.setAttribute(values[x], values[x + 1]);
        }
        parent.appendChild(e);
        return e;
    }

    private void tasks() {
        {
            GenSrgTask task = this.makeTask("genSrgs", GenSrgTask.class);
            task.setInSrg(this.delayedFile(PACKAGED_SRG));
            task.setInExc(this.delayedFile(PACKAGED_EXC));
            task.setMethodsCsv(this.delayedFile(METHOD_CSV));
            task.setFieldsCsv(this.delayedFile(FIELD_CSV));
            task.setNotchToSrg(this.delayedFile(DEOBF_SRG_SRG));
            task.setNotchToMcp(this.delayedFile(DEOBF_MCP_SRG));
            task.setSrgToMcp(this.delayedFile(DEOBF_SRG_MCP_SRG));
            task.setMcpToSrg(this.delayedFile(REOBF_SRG));
            task.setMcpToNotch(this.delayedFile(REOBF_NOTCH_SRG));
            task.setSrgExc(this.delayedFile(EXC_SRG));
            task.setMcpExc(this.delayedFile(EXC_MCP));
            task.dependsOn("extractUserDev", "extractMcpData");
        }

        {
            MergeJarsTask task = this.makeTask("mergeJars", MergeJarsTask.class);
            task.setClient(this.delayedFile(JAR_CLIENT_FRESH));
            task.setServer(this.delayedFile(JAR_SERVER_FRESH));
            task.setOutJar(this.delayedFile(JAR_MERGED));
            task.setMergeCfg(this.delayedFile(MERGE_CFG));
            task.setMcVersion(this.delayedString("{MC_VERSION}"));
            task.dependsOn("extractUserDev", "downloadClient", "downloadServer");
        }

        {
            String name = this.getBinDepName() + "-" + (this.hasApiVersion() ? "{API_VERSION}" : "{MC_VERSION}") + ".jar";

            ProcessJarTask task = this.makeTask("deobfBinJar", ProcessJarTask.class);
            task.setSrg(this.delayedFile(DEOBF_MCP_SRG));
            task.setExceptorJson(this.delayedFile(EXC_JSON));
            task.setExceptorCfg(this.delayedFile(EXC_MCP));
            task.setFieldCsv(this.delayedFile(FIELD_CSV));
            task.setMethodCsv(this.delayedFile(METHOD_CSV));
            task.setInJar(this.delayedFile(JAR_MERGED));
            task.setOutCleanJar(this.delayedFile("{API_CACHE_DIR}/" + MAPPING_APPENDAGE + name));
            task.setOutDirtyJar(this.delayedFile(DIRTY_DIR + "/" + name));
            task.setApplyMarkers(false);
            task.setStripSynthetics(true);
            this.configureDeobfuscation(task);
            task.dependsOn("downloadMcpTools", "mergeJars", "genSrgs");
        }

        {
            String name = "{API_NAME}-" + (this.hasApiVersion() ? "{API_VERSION}" : "{MC_VERSION}") + "-" + CLASSIFIER_DEOBF_SRG + "t.jar";

            ProcessJarTask task = this.makeTask("deobfuscateJar", ProcessJarTask.class);
            task.setSrg(this.delayedFile(DEOBF_SRG_SRG));
            task.setExceptorJson(this.delayedFile(EXC_JSON));
            task.setExceptorCfg(this.delayedFile(EXC_SRG));
            task.setInJar(this.delayedFile(JAR_MERGED));
            task.setOutCleanJar(this.delayedFile("{API_CACHE_DIR}/" + name));
            task.setOutDirtyJar(this.delayedFile(DIRTY_DIR + "/" + name));
            task.setApplyMarkers(true);
            this.configureDeobfuscation(task);
            task.dependsOn("downloadMcpTools", "mergeJars", "genSrgs");
        }

        {
            ReobfTask task = this.makeTask("reobf", ReobfTask.class);
            task.dependsOn("genSrgs");
            task.setExceptorCfg(this.delayedFile(EXC_SRG));
            task.setSrg(this.delayedFile(REOBF_SRG));
            task.setFieldCsv(this.delayedFile(FIELD_CSV));
            task.setFieldCsv(this.delayedFile(METHOD_CSV));
            task.setMcVersion(this.delayedString("{MC_VERSION}"));

            task.mustRunAfter("test");
            this.project.getTasks().getByName("assemble").dependsOn(task);
            this.project.getTasks().getByName("uploadArchives").dependsOn(task);
        }

        {
            // create GradleStart
            CreateStartTask task = this.makeTask("makeStart", CreateStartTask.class);
            task.addResource("GradleStart.java");
            task.addResource("GradleStartServer.java");
            task.addResource("net/minecraftforge/gradle/GradleStartCommon.java");
            task.addResource("net/minecraftforge/gradle/OldPropertyMapSerializer.java");
            task.addResource("net/minecraftforge/gradle/tweakers/CoremodTweaker.java");
            task.addResource("net/minecraftforge/gradle/tweakers/AccessTransformerTweaker.java");
            task.addReplacement("@@MCVERSION@@", this.delayedString("{MC_VERSION}"));
            task.addReplacement("@@ASSETINDEX@@", this.delayedString("{ASSET_INDEX}"));
            task.addReplacement("@@ASSETSDIR@@", this.delayedFile("{CACHE_DIR}/minecraft/assets"));
            task.addReplacement("@@NATIVESDIR@@", this.delayedFile(Constants.NATIVES_DIR));
            task.addReplacement("@@SRGDIR@@", this.delayedFile("{SRG_DIR}"));
            task.addReplacement("@@SRG_NOTCH_SRG@@", this.delayedFile(UserConstants.DEOBF_SRG_SRG));
            task.addReplacement("@@SRG_NOTCH_MCP@@", this.delayedFile(UserConstants.DEOBF_MCP_SRG));
            task.addReplacement("@@SRG_SRG_MCP@@", this.delayedFile(UserConstants.DEOBF_SRG_MCP_SRG));
            task.addReplacement("@@SRG_MCP_SRG@@", this.delayedFile(UserConstants.REOBF_SRG));
            task.addReplacement("@@SRG_MCP_NOTCH@@", this.delayedFile(UserConstants.REOBF_NOTCH_SRG));
            task.addReplacement("@@CSVDIR@@", this.delayedFile("{MCP_DATA_DIR}"));
            task.addReplacement("@@CLIENTTWEAKER@@", this.delayedString("{RUN_CLIENT_TWEAKER}"));
            task.addReplacement("@@SERVERTWEAKER@@", this.delayedString("{RUN_SERVER_TWEAKER}"));
            task.addReplacement("@@BOUNCERCLIENT@@", this.delayedString("{RUN_BOUNCE_CLIENT}"));
            task.addReplacement("@@BOUNCERSERVER@@", this.delayedString("{RUN_BOUNCE_SERVER}"));
            task.setStartOut(this.delayedFile(this.getStartDir()));
            task.compileResources(CONFIG_DEPS);

            // see delayed task config for some more config

            task.dependsOn("extractUserDev", "getAssets", "getAssetsIndex", "extractNatives");
        }

        this.createPostDecompTasks();
        this.createExecTasks();
        this.createSourceCopyTasks();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final void createPostDecompTasks() {
        DelayedFile decompOut = this.delayedDirtyFile(null, CLASSIFIER_DECOMPILED, "jar", false);
        DelayedFile remapped = this.delayedDirtyFile(this.getSrcDepName(), CLASSIFIER_SOURCES, "jar");
        DelayedFile trueSourcesFile = this.delayedDirtyFile(this.getSrcDepName(), "sources", "jar");
        final DelayedFile recomp = this.delayedDirtyFile(this.getSrcDepName(), null, "jar");
        final DelayedFile recompSrc = this.delayedFile(RECOMP_SRC_DIR);
        final DelayedFile recompCls = this.delayedFile(RECOMP_CLS_DIR);

        DecompileTask decomp = this.makeTask("decompile", DecompileTask.class);
        {
            decomp.setInJar(this.delayedDirtyFile(null, CLASSIFIER_DEOBF_SRG, "jar", false));
            decomp.setOutJar(decompOut);
            decomp.setFernFlower(this.delayedFile(FERNFLOWER));
            decomp.setPatch(this.delayedFile(MCP_PATCH_DIR));
            decomp.setAstyleConfig(this.delayedFile(ASTYLE_CFG));
            //decomp.dependsOn("downloadMcpTools", "deobfuscateJar", "genSrgs");
        }

        // Restore generic signatures
        RestoreGenericSignatures restore = this.makeTask("restoreGenericSignatures", RestoreGenericSignatures.class);
        {
            restore.setInJar(this.delayedDirtyFile(null, CLASSIFIER_DEOBF_SRG + "t", "jar", false));
            restore.setOutJar(decomp.inJar);
            restore.dependsOn("downloadMcpTools", "deobfuscateJar", "genSrgs");
            restore.finalizedBy(decomp);
            decomp.dependsOn(restore);
        }

        // Remap to MCP names
        RemapSourcesTask remap = this.makeTask("remapJar", RemapSourcesTask.class);
        {
            remap.setInJar(decompOut);
            remap.setOutJar(remapped);
            remap.setFieldsCsv(this.delayedFile(FIELD_CSV));
            remap.setMethodsCsv(this.delayedFile(METHOD_CSV));
            remap.setParamsCsv(this.delayedFile(PARAM_CSV));
            remap.setDoesJavadocs(true);
            remap.dependsOn(decomp);
        }

        // Create actual -sources file with PROPER GODDAMN JAVA FORMATTING I SWEAR THIS BLASTED
        // OPENING-BRACKET-FROM-NEW-LINE-THING ALL OVER MC/FORGE SOURCES DID INVOKE MY ANGER
        // WAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAY TOO MANY TIMES IN THE PAST
        MakeTrueSources trueSources = this.makeTask("makeTrueSources", MakeTrueSources.class);
        {
            trueSources.setInJar(remapped);
            trueSources.setOutJar(trueSourcesFile);
            trueSources.setAstyleConfig(this.delayedFile(ASTYLE_CFG));
            remap.finalizedBy(trueSources);
        }


        Spec onlyIfCheck = new Spec() {
            @Override
            public boolean isSatisfiedBy(Object obj) {
                Task task = (Task)obj;
                TaskDependency dependency = task.getTaskDependencies();
                boolean didWork = false;
                for (Task depTask : dependency.getDependencies(task)) {
                    if (depTask.getDidWork()) {
                        didWork = true;
                        break;
                    }
                }
                boolean exists = recomp.call().exists();
                if (!exists)
                    return true;
                else
                    return didWork;
            }
        };

        ExtractTask extract = this.makeTask("extractMinecraftSrc", ExtractTask.class);
        {
            extract.from(remapped);
            extract.into(recompSrc);
            extract.setIncludeEmptyDirs(false);
            extract.setClean(true);
            extract.dependsOn(remap);

            extract.onlyIf(onlyIfCheck);
        }

        JavaCompile recompTask = this.makeTask("recompMinecraft", JavaCompile.class);
        {
            recompTask.setSource(recompSrc);
            recompTask.setSourceCompatibility("1.6");
            recompTask.setTargetCompatibility("1.6");
            recompTask.setClasspath(this.project.getConfigurations().getByName(CONFIG_DEPS));
            recompTask.dependsOn(extract);
            recompTask.getOptions().setWarnings(false);

            recompTask.onlyIf(onlyIfCheck);
        }

        Jar repackageTask = this.makeTask("repackMinecraft", Jar.class);
        {
            repackageTask.from(recompSrc);
            repackageTask.from(recompCls);
            repackageTask.exclude("*.java", "**/*.java", "**.java");
            repackageTask.dependsOn(recompTask);

            // file output configuration done in the delayed configuration.

            repackageTask.onlyIf(onlyIfCheck);
        }
    }

    @SuppressWarnings("serial")
    private class MakeDirExist extends Closure<Boolean> {
        DelayedFile path;

        MakeDirExist(DelayedFile path) {
            super(UserBasePlugin.this.project);
            this.path = path;
        }

        @Override
        public Boolean call() {
            File f = this.path.call();
            if (!f.exists()) {
                f.mkdirs();
            }
            return true;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void createExecTasks() {
        // In gradle 4.2 or newer, workingDir be resolved immediately. So set workingDir in afterEvaluate.
        {
            final JavaExec exec = this.makeTask("runClient", JavaExec.class);
            this.project.afterEvaluate(new Action<Project>() {
                @Override
                public void execute(Project project) {
                    exec.workingDir(UserBasePlugin.this.delayedFile("{RUN_DIR}"));
                }
            });
            exec.doFirst(new MakeDirExist(this.delayedFile("{RUN_DIR}")));
            exec.setMain(GRADLE_START_CLIENT);
            //exec.jvmArgs("-Xincgc", "-Xmx1024M", "-Xms1024M", "-Dfml.ignoreInvalidMinecraftCertificates=true");
            exec.args(this.getClientRunArgs());
            exec.setStandardOutput(System.out);
            exec.setErrorOutput(System.err);

            exec.setGroup("ForgeGradle");
            exec.setDescription("Runs the Minecraft client");

            exec.dependsOn("makeStart");
        }

        {
            final JavaExec exec = this.makeTask("runServer", JavaExec.class);
            this.project.afterEvaluate(new Action<Project>() {
                @Override
                public void execute(Project project) {
                    exec.workingDir(UserBasePlugin.this.delayedFile("{RUN_DIR}"));
                }
            });
            exec.doFirst(new MakeDirExist(this.delayedFile("{RUN_DIR}")));
            exec.setMain(GRADLE_START_SERVER);
            exec.jvmArgs("-Xincgc", "-Dfml.ignoreInvalidMinecraftCertificates=true");
            exec.args(this.getServerRunArgs());
            exec.setStandardOutput(System.out);
            exec.setStandardInput(System.in);
            exec.setErrorOutput(System.err);

            exec.setGroup("ForgeGradle");
            exec.setDescription("Runs the Minecraft Server");

            exec.dependsOn("makeStart");
        }

        {

            final JavaExec exec = this.makeTask("debugClient", JavaExec.class);
            this.project.afterEvaluate(new Action<Project>() {
                @Override
                public void execute(final Project project) {
                    exec.workingDir(UserBasePlugin.this.delayedFile("{RUN_DIR}"));
                }
            });
            exec.doFirst(new MakeDirExist(this.delayedFile("{RUN_DIR}")));
            exec.doFirst(new Action() {
                @Override
                public void execute(Object o) {
                    UserBasePlugin.this.project.getLogger().error("");
                    UserBasePlugin.this.project.getLogger().error("THIS TASK WILL BE DEP RECATED SOON!");
                    UserBasePlugin.this.project.getLogger().error("Instead use the runClient task, with the --debug-jvm option");
                    if (!UserBasePlugin.this.project.getGradle().getGradleVersion().equals("1.12")) {
                        UserBasePlugin.this.project.getLogger().error("You may have to update to Gradle 1.12");
                    }
                    UserBasePlugin.this.project.getLogger().error("");
                }
            });
            exec.setMain(GRADLE_START_CLIENT);
            exec.jvmArgs("-Xincgc", "-Xmx1024M", "-Xms1024M", "-Dfml.ignoreInvalidMinecraftCertificates=true");
            exec.args(this.getClientRunArgs());
            exec.setStandardOutput(System.out);
            exec.setErrorOutput(System.err);
            exec.setDebug(true);

            exec.setGroup("ForgeGradle");
            exec.setDescription("Runs the Minecraft client in debug mode");

            exec.dependsOn("makeStart");
        }

        {
            final JavaExec exec = this.makeTask("debugServer", JavaExec.class);
            this.project.afterEvaluate(new Action<Project>() {
                @Override
                public void execute(final Project project) {
                    exec.workingDir(UserBasePlugin.this.delayedFile("{RUN_DIR}"));
                }
            });
            exec.doFirst(new MakeDirExist(this.delayedFile("{RUN_DIR}")));
            exec.doFirst(new Action() {
                @Override
                public void execute(Object o) {
                    UserBasePlugin.this.project.getLogger().error("");
                    UserBasePlugin.this.project.getLogger().error("THIS TASK WILL BE DEPRECATED SOON!");
                    UserBasePlugin.this.project.getLogger().error("Instead use the runServer task, with the --debug-jvm option");
                    if (!UserBasePlugin.this.project.getGradle().getGradleVersion().equals("1.12")) {
                        UserBasePlugin.this.project.getLogger().error("You may have to update to Gradle 1.12");
                    }
                    UserBasePlugin.this.project.getLogger().error("");
                }
            });
            exec.setMain(GRADLE_START_SERVER);
            exec.jvmArgs("-Xincgc", "-Dfml.ignoreInvalidMinecraftCertificates=true");
            exec.args(this.getServerRunArgs());
            exec.setStandardOutput(System.out);
            exec.setStandardInput(System.in);
            exec.setErrorOutput(System.err);
            exec.setDebug(true);

            exec.setGroup("ForgeGradle");
            exec.setDescription("Runs the Minecraft serevr in debug mode");

            exec.dependsOn("makeStart");
        }
    }

    private final void createSourceCopyTasks() {
        JavaPluginConvention javaConv = (JavaPluginConvention) this.project.getConvention().getPlugins().get("java");
        SourceSet main = javaConv.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

        // do the special source moving...
        SourceCopyTask task;

        // main
        {
            DelayedFile dir = this.delayedFile(SOURCES_DIR + "/java");

            task = this.makeTask("sourceMainJava", SourceCopyTask.class);
            task.setSource(main.getJava());
            task.setOutput(dir);

            JavaCompile compile = (JavaCompile) this.project.getTasks().getByName(main.getCompileJavaTaskName());
            compile.dependsOn("sourceMainJava");
            compile.setSource(dir);
        }

        // scala!!!
        if (this.project.getPlugins().hasPlugin("scala")) {
            ScalaSourceSet set = (ScalaSourceSet) new DslObject(main).getConvention().getPlugins().get("scala");
            DelayedFile dir = this.delayedFile(SOURCES_DIR + "/scala");

            task = this.makeTask("sourceMainScala", SourceCopyTask.class);
            task.setSource(set.getScala());
            task.setOutput(dir);

            ScalaCompile compile = (ScalaCompile) this.project.getTasks().getByName(main.getCompileTaskName("scala"));
            compile.dependsOn("sourceMainScala");
            compile.setSource(dir);
        }

        // groovy!!!
        if (this.project.getPlugins().hasPlugin("groovy")) {
            GroovySourceSet set = (GroovySourceSet) new DslObject(main).getConvention().getPlugins().get("groovy");
            DelayedFile dir = this.delayedFile(SOURCES_DIR + "/groovy");

            task = this.makeTask("sourceMainGroovy", SourceCopyTask.class);
            task.setSource(set.getGroovy());
            task.setOutput(dir);

            GroovyCompile compile = (GroovyCompile) this.project.getTasks().getByName(main.getCompileTaskName("groovy"));
            compile.dependsOn("sourceMainGroovy");
            compile.setSource(dir);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public final void afterEvaluate() {
        String mcversion = this.getMcVersion(this.getExtension());
        if (!this.getExtension().mappingsSet() && mcversion.startsWith("1.8")) {
            this.getExtension().setMappings("snapshot_20141001"); //Default snapshots for 1.8
        }

        super.afterEvaluate();

        // version checks
        {
            String version = this.getMcVersion(this.getExtension());
            if (this.hasApiVersion()) {
                version = this.getApiVersion(this.getExtension());
            }

            this.doVersionChecks(version);
        }

        // ensure plugin application sequence.. groovy or scala or wtvr first, then the forge/fml/liteloader plugins
        if (!this.hasScalaBefore && this.project.getPlugins().hasPlugin("scala"))
            throw new RuntimeException(this.delayedString("You have applied the 'scala' plugin after '{API_NAME}', you must apply it before.").call());
        if (!this.hasGroovyBefore && this.project.getPlugins().hasPlugin("groovy"))
            throw new RuntimeException(this.delayedString("You have applied the 'groovy' plugin after '{API_NAME}', you must apply it before.").call());

        this.project.getDependencies().add(CONFIG_USERDEV, this.delayedString(this.getUserDev()).call() + ":userdev");

        // grab the json && read dependencies
        if (this.getDevJson().call().exists()) {
            this.readAndApplyJson(this.getDevJson().call(), CONFIG_DEPS, CONFIG_NATIVES, this.project.getLogger());
        }

        this.delayedTaskConfig();

        // add MC repo.
        final String repoDir = this.delayedDirtyFile("this", "doesnt", "matter").call().getParentFile().getAbsolutePath();
        this.project.allprojects(new Action<Project>() {
            @Override
            public void execute(Project proj) {
                UserBasePlugin.this.addFlatRepo(proj, UserBasePlugin.this.getApiName() + "FlatRepo", repoDir);
                proj.getLogger().debug("Adding repo to " + proj.getPath() + " >> " + repoDir);
            }
        });

        // check for decompilation status.. has decompiled or not etc
        final File decompFile = this.delayedDirtyFile(this.getSrcDepName(), CLASSIFIER_SOURCES, "jar").call();
        if (decompFile.exists()) {
            this.getExtension().setDecomp();
        }

        // post decompile status thing.
        this.configurePostDecomp(this.getExtension().isDecomp(), false);

        {
            // stop getting empty dirs
            Action<ConventionTask> act = new Action() {
                @Override
                public void execute(Object arg0) {
                    Zip task = (Zip) arg0;
                    task.setIncludeEmptyDirs(false);
                }
            };

            this.project.getTasks().withType(Jar.class, act);
            this.project.getTasks().withType(Zip.class, act);
        }
    }

    /**
     * Allows for the configuration of tasks in AfterEvaluate
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected void delayedTaskConfig() {
        // add extraSRG lines to reobf task
        {
            ReobfTask task = ((ReobfTask) this.project.getTasks().getByName("reobf"));
            task.reobf(this.project.getTasks().getByName("jar"), new Action<ArtifactSpec>() {
                @Override
                public void execute(ArtifactSpec arg0) {
                    JavaPluginConvention javaConv = (JavaPluginConvention) UserBasePlugin.this.project.getConvention().getPlugins().get("java");
                    arg0.setClasspath(javaConv.getSourceSets().getByName("main").getCompileClasspath());
                }

            });
            task.setExtraSrg(this.getExtension().getSrgExtra());
        }

        // configure output of recompile task
        {
            JavaCompile compile = (JavaCompile) this.project.getTasks().getByName("recompMinecraft");
            compile.setDestinationDir(this.delayedFile(RECOMP_CLS_DIR).call());
        }

        // configure output of repackage task.
        {
            Jar repackageTask = (Jar) this.project.getTasks().getByName("repackMinecraft");
            final DelayedFile recomp = this.delayedDirtyFile(this.getSrcDepName(), null, "jar");

            //done in the delayed configuration.
            File out = recomp.call();
            repackageTask.setArchiveName(out.getName());
            repackageTask.setDestinationDir(out.getParentFile());
        }

        {
            // because different versions of authlib
            CreateStartTask task = (CreateStartTask) this.project.getTasks().getByName("makeStart");

            if (this.getMcVersion(this.getExtension()).startsWith("1.7")) // MC 1.7.X
            {
                if (this.getMcVersion(this.getExtension()).endsWith("10")) // MC 1.7.10
                {
                    task.addReplacement("//@@USERTYPE@@", "argMap.put(\"userType\", auth.getUserType().getName());");
                    task.addReplacement("//@@USERPROP@@", "argMap.put(\"userProperties\", new GsonBuilder().registerTypeAdapter(com.mojang.authlib.properties.PropertyMap.class, new net.minecraftforge.gradle.OldPropertyMapSerializer()).create().toJson(auth.getUserProperties()));");
                } else {
                    task.removeResource("net/minecraftforge/gradle/OldPropertyMapSerializer.java");
                }
            } else // MC 1.8 +
            {
                task.removeResource("net/minecraftforge/gradle/OldPropertyMapSerializer.java");
                task.addReplacement("//@@USERTYPE@@", "argMap.put(\"userType\", auth.getUserType().getName());");
                task.addReplacement("//@@USERPROP@@", "argMap.put(\"userProperties\", new GsonBuilder().registerTypeAdapter(com.mojang.authlib.properties.PropertyMap.class, new com.mojang.authlib.properties.PropertyMap.Serializer()).create().toJson(auth.getUserProperties()));");
            }
        }

        // Add the mod and stuff to the classpath of the exec tasks.
        final Jar jarTask = (Jar) this.project.getTasks().getByName("jar");

        JavaExec exec = (JavaExec) this.project.getTasks().getByName("runClient");
        {
            exec.classpath(this.project.getConfigurations().getByName("runtime"));
            exec.classpath(jarTask.getArchivePath());
            exec.dependsOn(jarTask);
        }

        exec = (JavaExec) this.project.getTasks().getByName("runServer");
        {
            exec.classpath(this.project.getConfigurations().getByName("runtime"));
            exec.classpath(jarTask.getArchivePath());
            exec.dependsOn(jarTask);
        }

        exec = (JavaExec) this.project.getTasks().getByName("debugClient");
        {
            exec.classpath(this.project.getConfigurations().getByName("runtime"));
            exec.classpath(jarTask.getArchivePath());
            exec.dependsOn(jarTask);
        }

        exec = (JavaExec) this.project.getTasks().getByName("debugServer");
        {
            exec.classpath(this.project.getConfigurations().getByName("runtime"));
            exec.classpath(jarTask.getArchivePath());
            exec.dependsOn(jarTask);
        }

        // configure source replacement.
        for (SourceCopyTask t : this.project.getTasks().withType(SourceCopyTask.class)) {
            t.replace(this.getExtension().getReplacements());
            t.include(this.getExtension().getIncludes());
        }
    }

    /**
     * Configure tasks and stuff after you know if the decomp file exists or not.
     *
     * @param decomp will decompile this task
     * @param remove should remove old dependencies or not
     */
    protected void configurePostDecomp(boolean decomp, boolean remove) {
        if (decomp) {
            ((ReobfTask) this.project.getTasks().getByName("reobf")).setDeobfFile(((ProcessJarTask) this.project.getTasks().getByName("restoreGenericSignatures")).getDelayedOutput());
            ((ReobfTask) this.project.getTasks().getByName("reobf")).setRecompFile(this.delayedDirtyFile(this.getSrcDepName(), null, "jar"));
        } else {
            (this.project.getTasks().getByName("compileJava")).dependsOn("deobfBinJar");
            (this.project.getTasks().getByName("compileApiJava")).dependsOn("deobfBinJar");
        }

        this.setMinecraftDeps(decomp, remove);

        if (decomp && remove) {
            (this.project.getTasks().getByName("deobfBinJar")).onlyIf(Constants.CALL_FALSE);
        }
    }

    protected void setMinecraftDeps(boolean decomp, boolean remove) {
        String version = this.getMcVersion(this.getExtension());
        if (this.hasApiVersion()) {
            version = this.getApiVersion(this.getExtension());
        }


        if (decomp) {
            this.project.getDependencies().add(CONFIG_MC, ImmutableMap.of("name", this.getSrcDepName(), "version", version));
            if (remove) {
                this.project.getConfigurations().getByName(CONFIG_MC).exclude(ImmutableMap.of("module", this.getBinDepName()));
            }
        } else {
            this.project.getDependencies().add(CONFIG_MC, ImmutableMap.of("name", this.getBinDepName(), "version", version));
            if (remove) {
                this.project.getConfigurations().getByName(CONFIG_MC).exclude(ImmutableMap.of("module", this.getSrcDepName()));
            }
        }
    }

    /**
     * Add Forge/FML ATs here.
     * This happens during normal evaluation, and NOT AfterEvaluate.
     *
     * @param task the deobfuscation task
     */
    protected abstract void configureDeobfuscation(ProcessJarTask task);

    /**
     * @param version may have pre-release suffix _pre#
     */
    protected abstract void doVersionChecks(String version);

    /**
     * Returns a file in the DirtyDir if the deobfuscation task is dirty. Otherwise returns the cached one.
     *
     * @param name       the name..
     * @param classifier the classifier
     * @param ext        the extension
     * @return delayed file
     */
    protected DelayedFile delayedDirtyFile(final String name, final String classifier, final String ext) {
        return this.delayedDirtyFile(name, classifier, ext, true);
    }

    /**
     * Returns a file in the DirtyDir if the deobfuscation task is dirty. Otherwise returns the cached one.
     *
     * @param name         the name..
     * @param classifier   the classifier
     * @param ext          the extension
     * @param usesMappings whether or not MCP mappings are specified
     * @return delayed file
     */
    @SuppressWarnings("serial")
    protected DelayedFile delayedDirtyFile(final String name, final String classifier, final String ext, final boolean usesMappings) {
        return new DelayedFile(this.project, "", this) {
            @Override
            public File resolveDelayed() {
                ProcessJarTask decompDeobf = (ProcessJarTask) this.project.getTasks().getByName("deobfuscateJar");
                this.pattern = (decompDeobf.isClean() ? "{API_CACHE_DIR}/" + (usesMappings ? MAPPING_APPENDAGE : "") : DIRTY_DIR) + "/";

                if (!Strings.isNullOrEmpty(name)) {
                    this.pattern += name;
                } else {
                    this.pattern += "{API_NAME}";
                }

                this.pattern += "-" + (UserBasePlugin.this.hasApiVersion() ? "{API_VERSION}" : "{MC_VERSION}");

                if (!Strings.isNullOrEmpty(classifier)) {
                    this.pattern += "-" + classifier;
                }
                if (!Strings.isNullOrEmpty(ext)) {
                    this.pattern += "." + ext;
                }

                return super.resolveDelayed();
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Class<T> getExtensionClass() {
        return (Class<T>) UserExtension.class;
    }

    private void addGitIgnore() {
        if (true)
            return;

        // TODO Re-evaluate some of our life choices
        File git = new File(this.project.getBuildDir(), ".gitignore");
        if (!git.exists()) {
            git.getParentFile().mkdir();
            try {
                Files.write("#Seriously guys, stop commiting this to your git repo!\r\n*".getBytes(), git);
            } catch (IOException e) {
            }
        }
    }
}
