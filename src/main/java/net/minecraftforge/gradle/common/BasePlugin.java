package net.minecraftforge.gradle.common;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.repositories.FlatDirectoryArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.Delete;
import org.gradle.testfixtures.ProjectBuilder;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import groovy.lang.Closure;
import net.minecraftforge.gradle.FileLogListenner;
import net.minecraftforge.gradle.GradleConfigurationException;
import net.minecraftforge.gradle.delayed.DelayedBase.IDelayedResolver;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.delayed.DelayedFileTree;
import net.minecraftforge.gradle.delayed.DelayedString;
import net.minecraftforge.gradle.json.JsonFactory;
import net.minecraftforge.gradle.json.version.AssetIndex;
import net.minecraftforge.gradle.json.version.Version;
import net.minecraftforge.gradle.tasks.DownloadAssetsTask;
import net.minecraftforge.gradle.tasks.ExtractConfigTask;
import net.minecraftforge.gradle.tasks.ObtainFernFlowerTask;
import net.minecraftforge.gradle.tasks.abstractutil.DownloadTask;
import net.minecraftforge.gradle.tasks.abstractutil.EtagDownloadTask;

public abstract class BasePlugin<K extends BaseExtension> implements Plugin<Project>, IDelayedResolver<K> {
    public Project project;
    @SuppressWarnings("rawtypes")
    public BasePlugin otherPlugin;
    public Version version;
    public AssetIndex assetIndex;

    @SuppressWarnings("rawtypes")
    @Override
    public final void apply(Project arg) {
        this.project = arg;

        // search for overlays..
        for (Plugin p : this.project.getPlugins()) {
            if (p instanceof BasePlugin && p != this) {
                if (this.canOverlayPlugin()) {
                    this.project.getLogger().info("Applying Overlay");

                    // found another BasePlugin thats already applied.
                    // do only overlay stuff and return;
                    this.otherPlugin = (BasePlugin) p;
                    this.applyOverlayPlugin();
                    return;
                } else
                    throw new GradleConfigurationException("Seems you are trying to apply 2 ForgeGradle plugins that are not designed to overlay... Fix your buildscripts.");
            }
        }

        // logging
        {
            File projectCacheDir = this.project.getGradle().getStartParameter().getProjectCacheDir();
            if (projectCacheDir == null) {
                projectCacheDir = new File(this.project.getProjectDir(), ".gradle");
            }

            FileLogListenner listener = new FileLogListenner(new File(projectCacheDir, "gradle.log"));
            this.project.getLogging().addStandardOutputListener(listener);
            this.project.getLogging().addStandardErrorListener(listener);
            this.project.getGradle().addBuildListener(listener);
        }

        if (this.project.getBuildDir().getAbsolutePath().contains("!")) {
            this.project.getLogger().error("Build path has !, This will screw over a lot of java things as ! is used to denote archive paths, REMOVE IT if you want to continue");
            throw new RuntimeException("Build path contains !");
        }

        // extension objects
        this.project.getExtensions().create(Constants.EXT_NAME_MC, this.getExtensionClass(), this);
        this.project.getExtensions().create(Constants.EXT_NAME_JENKINS, JenkinsExtension.class, this.project);

        // repos
        this.project.allprojects(new Action<Project>() {
            @Override
            public void execute(Project proj) {
                BasePlugin.this.addMavenRepo(proj, "mcp_archive", Constants.MCP_ARCHIVE_REPO);
                BasePlugin.this.addMavenRepo(proj, "forge", Constants.FORGE_MAVEN);
                proj.getRepositories().mavenCentral();
                BasePlugin.this.addMavenRepo(proj, "minecraft", Constants.LIBRARY_URL);
            }
        });

        // do Mcp Snapshots Stuff
        this.setVersionInfoJson();
        this.project.getConfigurations().create(Constants.CONFIG_MCP_DATA);

        // after eval
        this.project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project project) {
                // dont continue if its already failed!
                if (project.getState().getFailure() != null)
                    return;

                BasePlugin.this.afterEvaluate();

                try {
                    if (BasePlugin.this.version != null) {
                        File index = BasePlugin.this.delayedFile(Constants.ASSETS + "/indexes/" + BasePlugin.this.version.getAssets() + ".json").call();
                        if (index.exists()) {
                            BasePlugin.this.parseAssetIndex();
                        }
                    }
                } catch (Exception e) {
                    Throwables.propagate(e);
                }

                BasePlugin.this.finalCall();
            }
        });

        // some default tasks
        this.makeObtainTasks();

        // at last, apply the child plugins
        this.applyPlugin();
    }

    public abstract void applyPlugin();

    public abstract void applyOverlayPlugin();

    /**
     * return true if this plugin can be applied over another BasePlugin.
     *
     * @return TRUE if this can be applied upon another base plugin.
     */
    public abstract boolean canOverlayPlugin();

    protected abstract DelayedFile getDevJson();

    private static boolean displayBanner = true;

    private void setVersionInfoJson() {
        File jsonCache = Constants.cacheFile(this.project, "caches", "minecraft", "McpMappings.json");
        File etagFile = new File(jsonCache.getAbsolutePath() + ".etag");

        this.getExtension().mcpJson = JsonFactory.GSON.fromJson(
                this.getWithEtag(Constants.MCP_JSON_URL, jsonCache, etagFile),
                new TypeToken<Map<String, Map<String, int[]>>>() {}.getType());
    }

    public void afterEvaluate() {
        if (this.getExtension().mappingsSet()) {
            this.project.getDependencies().add(Constants.CONFIG_MCP_DATA, ImmutableMap.of(
                    "group", "de.oceanlabs.mcp",
                    "name", this.delayedString("mcp_{MAPPING_CHANNEL}").call(),
                    "version", this.delayedString("{MAPPING_VERSION}-{MC_VERSION}").call(),
                    "ext", "zip"
                    ));
        }

        if (!displayBanner)
            return;
        Logger logger = this.project.getLogger();
        logger.lifecycle("#####################################################");
        logger.lifecycle("       ForgeGradle {}         ",                      this.getVersionString());
        logger.lifecycle("     https://github.com/juanmuscaria/ForgeGradle     ");
        logger.lifecycle("#####################################################");
        logger.lifecycle("                    Powered by MCP                   "/*, this.delayedString("{MCP_VERSION}")*/);
        logger.lifecycle("               http://modcoderpack.com               ");
        logger.lifecycle("           by: Searge, ProfMobius, Fesh0r,           ");
        logger.lifecycle("           R4wk, ZeuX, IngisKahn, bspkrs             ");
        logger.lifecycle("                                                     ");
        logger.lifecycle("                     Reissued by:                    ");
        logger.lifecycle("              juanmuscaria, Aizistral                ");
        logger.lifecycle("#####################################################");
        displayBanner = false;
    }

    private String getVersionString() {
        String version = this.getClass().getPackage().getImplementationVersion();
        if (Strings.isNullOrEmpty(version)) {
            version = "unknown version";
        }

        return version;
    }

    public void finalCall() {
    }

    @SuppressWarnings("serial")
    private void makeObtainTasks() {
        // download tasks
        DownloadTask task;

        task = this.makeTask("downloadClient", DownloadTask.class);
        {
            task.setOutput(this.delayedFile(Constants.JAR_CLIENT_FRESH));
            task.setUrl(this.delayedString(Constants.MC_JAR_URL));
        }

        task = this.makeTask("downloadServer", DownloadTask.class);
        {
            task.setOutput(this.delayedFile(Constants.JAR_SERVER_FRESH));
            task.setUrl(this.delayedString(Constants.MC_SERVER_URL));
        }

        ObtainFernFlowerTask mcpTask = this.makeTask("downloadMcpTools", ObtainFernFlowerTask.class);
        {
            mcpTask.setMcpUrl(this.delayedString(Constants.MCP_URL));
            mcpTask.setFfJar(this.delayedFile(Constants.FERNFLOWER));
        }

        EtagDownloadTask etagDlTask = this.makeTask("getAssetsIndex", EtagDownloadTask.class);
        {
            etagDlTask.setUrl(this.delayedString(Constants.ASSETS_INDEX_URL));
            etagDlTask.setFile(this.delayedFile(Constants.ASSETS + "/indexes/{ASSET_INDEX}.json"));
            etagDlTask.setDieWithError(false);

            etagDlTask.doLast(new Action<Task>() {
                @Override
                public void execute(Task task) {
                    try {
                        BasePlugin.this.parseAssetIndex();
                    } catch (Exception e) {
                        Throwables.propagate(e);
                    }
                }
            });
        }

        DownloadAssetsTask assets = this.makeTask("getAssets", DownloadAssetsTask.class);
        {
            assets.setAssetsDir(this.delayedFile(Constants.ASSETS));
            assets.setIndex(this.getAssetIndexClosure());
            assets.setIndexName(this.delayedString("{ASSET_INDEX}"));
            assets.dependsOn("getAssetsIndex");
        }

        etagDlTask = this.makeTask("getVersionJson", EtagDownloadTask.class);
        {
            etagDlTask.setUrl(this.delayedString(Constants.MC_JSON_URL));
            etagDlTask.setFile(this.delayedFile(Constants.VERSION_JSON));
            etagDlTask.setDieWithError(false);
            etagDlTask.doLast(new Closure<Boolean>(this.project) // normalizes to linux endings
                    {
                @Override
                public Boolean call() {
                    try {
                        File json = BasePlugin.this.delayedFile(Constants.VERSION_JSON).call();
                        if (!json.exists())
                            return true;

                        List<String> lines = Files.readLines(json, Charsets.UTF_8);
                        StringBuilder buf = new StringBuilder();
                        for (String line : lines) {
                            buf = buf.append(line).append('\n');
                        }
                        Files.write(buf.toString().getBytes(Charsets.UTF_8), json);
                    } catch (Throwable t) {
                        Throwables.propagate(t);
                    }
                    return true;
                }
                    });
        }

        Delete clearCache = this.makeTask("cleanCache", Delete.class);
        {
            clearCache.delete(this.delayedFile("{CACHE_DIR}/minecraft"));
            clearCache.setGroup("ForgeGradle");
            clearCache.setDescription("Cleares the ForgeGradle cache. DONT RUN THIS unless you want a fresh start, or the dev tells you to.");
        }

        // special userDev stuff
        ExtractConfigTask extractMcpData = this.makeTask("extractMcpData", ExtractConfigTask.class);
        {
            extractMcpData.setOut(this.delayedFile(Constants.MCP_DATA_DIR));
            extractMcpData.setConfig(Constants.CONFIG_MCP_DATA);
            extractMcpData.setDoesCache(true);
        }
    }

    public void parseAssetIndex() throws JsonSyntaxException, JsonIOException, IOException {
        this.assetIndex = JsonFactory.loadAssetsIndex(this.delayedFile(Constants.ASSETS + "/indexes/{ASSET_INDEX}.json").call());
    }

    @SuppressWarnings("serial")
    public Closure<AssetIndex> getAssetIndexClosure() {
        return new Closure<AssetIndex>(this, null) {
            @Override
            public AssetIndex call(Object... obj) {
                return BasePlugin.this.getAssetIndex();
            }
        };
    }

    public AssetIndex getAssetIndex() {
        return this.assetIndex;
    }

    /**
     * This extension object will have the name "minecraft"
     *
     * @return extension object class
     */
    @SuppressWarnings("unchecked")
    protected Class<K> getExtensionClass() {
        return (Class<K>) BaseExtension.class;
    }

    /**
     * @return the extension object with name
     * @see Constants#EXT_NAME_MC
     */
    @SuppressWarnings("unchecked")
    public final K getExtension() {
        if (this.otherPlugin != null && this.canOverlayPlugin())
            return this.getOverlayExtension();
        else
            return (K) this.project.getExtensions().getByName(Constants.EXT_NAME_MC);
    }

    /**
     * @return the extension object with name EXT_NAME_MC
     * @see Constants#EXT_NAME_MC
     */
    protected abstract K getOverlayExtension();

    public DefaultTask makeTask(String name) {
        return this.makeTask(name, DefaultTask.class);
    }

    public <T extends Task> T makeTask(String name, Class<T> type) {
        return makeTask(this.project, name, type);
    }

    @SuppressWarnings("unchecked")
    public static <T extends Task> T makeTask(Project proj, String name, Class<T> type) {
        HashMap<String, Object> map = new HashMap<String, Object>();
        map.put("name", name);
        map.put("type", type);
        return (T) proj.task(map, name);
    }

    public static Project getProject(File buildFile, Project parent) {
        ProjectBuilder builder = ProjectBuilder.builder();
        if (buildFile != null && parent != null) {
            String name = buildFile.getParentFile().getAbsolutePath().replace(parent.getBuildFile().getParentFile().getAbsolutePath() + File.separator,"" );
            name = name.replace(File.separator, ":");
            builder = builder.withProjectDir(buildFile.getParentFile())
                    .withName(name);
        } else if (buildFile != null) {
            builder = builder.withProjectDir(buildFile.getParentFile())
                    .withName(buildFile.getParentFile().getName());
        } else {
            builder = builder.withProjectDir(new File("."));
        }

        if (parent != null) {
            builder = builder.withParent(parent);
        }

        Project project = builder.build();

        if (buildFile != null) {
            HashMap<String, String> map = new HashMap<String, String>();
            map.put("from", buildFile.getAbsolutePath());

            project.apply(map);
        }

        return project;
    }

    public void applyExternalPlugin(String plugin) {
        HashMap<String, Object> map = new HashMap<String, Object>();
        map.put("plugin", plugin);
        this.project.apply(map);
    }

    public MavenArtifactRepository addMavenRepo(Project proj, final String name, final String url) {
        return proj.getRepositories().maven(new Action<MavenArtifactRepository>() {
            @Override
            public void execute(MavenArtifactRepository repo) {
                repo.setName(name);
                repo.setUrl(url);
            }
        });
    }

    public FlatDirectoryArtifactRepository addFlatRepo(Project proj, final String name, final Object... dirs) {
        return proj.getRepositories().flatDir(new Action<FlatDirectoryArtifactRepository>() {
            @Override
            public void execute(FlatDirectoryArtifactRepository repo) {
                repo.setName(name);
                repo.dirs(dirs);
            }
        });
    }

    protected String getWithEtag(String strUrl, File cache, File etagFile) {
        try {
            if (this.project.getGradle().getStartParameter().isOffline()) // dont even try the internet
                return Files.toString(cache, Charsets.UTF_8);

            // dude, its been less than 5 minutes since the last time..
            if (cache.exists() && cache.lastModified() + 300000 >= System.currentTimeMillis())
                return Files.toString(cache, Charsets.UTF_8);

            String etag;
            if (etagFile.exists()) {
                etag = Files.toString(etagFile, Charsets.UTF_8);
            } else {
                etagFile.getParentFile().mkdirs();
                etag = "";
            }

            URL url = new URL(strUrl);

            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", Constants.USER_AGENT);

            if (!Strings.isNullOrEmpty(etag)) {
                con.setRequestProperty("If-None-Match", etag);
            }

            con.connect();

            String out = null;
            if (con.getResponseCode() == 304) {
                // the existing file is good
                Files.touch(cache); // touch it to update last-modified time
                out = Files.toString(cache, Charsets.UTF_8);
            } else if (con.getResponseCode() == 200) {
                InputStream stream = con.getInputStream();
                byte[] data = ByteStreams.toByteArray(stream);
                Files.write(data, cache);
                stream.close();

                // write etag
                etag = con.getHeaderField("ETag");
                if (Strings.isNullOrEmpty(etag)) {
                    Files.touch(etagFile);
                } else {
                    Files.write(etag, etagFile, Charsets.UTF_8);
                }

                out = new String(data);
            } else {
                this.project.getLogger().error("Etag download for " + strUrl + " failed with code " + con.getResponseCode());
            }

            con.disconnect();

            return out;
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (cache.exists()) {
            try {
                return Files.toString(cache, Charsets.UTF_8);
            } catch (IOException e) {
                Throwables.propagate(e);
            }
        }

        throw new RuntimeException("Unable to obtain url (" + strUrl + ") with etag!");
    }

    @Override
    public String resolve(String pattern, Project project, K exten) {
        if (this.version != null) {
            pattern = pattern.replace("{ASSET_INDEX}", this.version.getAssets());
        }

        if (exten.mappingsSet()) {
            pattern = pattern.replace("{MCP_DATA_DIR}", Constants.MCP_DATA_DIR);
        }

        return pattern;
    }

    protected DelayedString delayedString(String path) {
        return new DelayedString(this.project, path, this);
    }

    protected DelayedFile delayedFile(String path) {
        return new DelayedFile(this.project, path, this);
    }

    protected DelayedFileTree delayedFileTree(String path) {
        return new DelayedFileTree(this.project, path, this);
    }

    protected DelayedFileTree delayedZipTree(String path) {
        return new DelayedFileTree(this.project, path, true, this);
    }

}
