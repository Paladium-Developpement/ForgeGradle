package net.minecraftforge.gradle.common;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;

import org.gradle.api.Project;

import com.google.common.base.Strings;

import net.minecraftforge.gradle.GradleConfigurationException;

public class BaseExtension {
    protected transient Project project;
    protected String version = "null";
    protected String mcpVersion = "unknown";
    protected String runDir = "run";
    private LinkedList<String> srgExtra = new LinkedList<String>();

    protected Map<String, Map<String, int[]>> mcpJson;
    protected boolean mappingsSet = false;
    protected String mappingsChannel = null;
    protected int mappingsVersion = -1;
    protected String customVersion = null;

    public BaseExtension(BasePlugin<? extends BaseExtension> plugin) {
        this.project = plugin.project;
    }

    public String getVersion() {
        return this.version;
    }

    public void setVersion(String version) {
        this.version = version;

        // maybe they set the mappings first
        this.checkMappings();
    }

    public String getMcpVersion() {
        return this.mcpVersion;
    }

    public void setMcpVersion(String mcpVersion) {
        this.mcpVersion = mcpVersion;
    }

    public void setRunDir(String value) {
        this.runDir = value;
    }

    public String getRunDir() {
        return this.runDir;
    }

    @Deprecated
    public void setAssetDir(String value) {
        this.setRunDir(value + "/..");
        this.project.getLogger().warn("The assetDir is deprecated!  I actually just did all this generalizing stuff just now.. Use runDir instead! runDir set to " + this.runDir);
        this.project.getLogger().warn("The runDir should be the location where you want MC to be run, usually he parent of the asset dir");
    }

    public LinkedList<String> getSrgExtra() {
        return this.srgExtra;
    }

    public void srgExtra(String in) {
        this.srgExtra.add(in);
    }

    public void copyFrom(BaseExtension ext) {
        if ("null".equals(this.version)) {
            this.setVersion(ext.getVersion());
        }

        if ("unknown".equals(this.mcpVersion)) {
            this.setMcpVersion(ext.getMcpVersion());
        }
    }

    public String getMappings() {
        return this.mappingsChannel + "_" + (this.customVersion == null ? this.mappingsVersion : this.customVersion);
    }

    public String getMappingsChannel() {
        return this.mappingsChannel;
    }

    public String getMappingsChannelNoSubtype() {
        int underscore = this.mappingsChannel.indexOf('_');
        if (underscore <= 0) // already has docs.
            return this.mappingsChannel;
        else
            return this.mappingsChannel.substring(0, underscore);
    }

    public String getMappingsVersion() {
        return this.customVersion == null ? "" + this.mappingsVersion : this.customVersion;
    }

    public boolean mappingsSet() {
        return this.mappingsSet;
    }

    public void setMappings(String mappings) {
        if (Strings.isNullOrEmpty(mappings)) {
            this.mappingsChannel = null;
            this.mappingsVersion = -1;
            return;
        }

        mappings = mappings.toLowerCase();

        if (!mappings.contains("_"))
            throw new IllegalArgumentException("Mappings must be in format 'channel_version'. eg: snapshot_20140910");

        int index = mappings.lastIndexOf('_');
        this.mappingsChannel = mappings.substring(0, index);
        this.customVersion = mappings.substring(index + 1);

        if (!this.customVersion.equals("custom")) {
            try {
                this.mappingsVersion = Integer.parseInt(this.customVersion);
                this.customVersion = null;
            } catch (NumberFormatException e) {
                throw new GradleConfigurationException("The mappings version must be a number! eg: channel_### or channel_custom (for custom mappings).");
            }
        }

        this.mappingsSet = true;

        // check
        this.checkMappings();
    }

    /**
     * Checks that the set mappings are valid based on the channel, version, and MC version.
     * If the mappings are invalid, this method will throw a runtime exception.
     */
    protected void checkMappings() {
        // mappings or mc version are null
        if (!this.mappingsSet || "null".equals(this.version) || Strings.isNullOrEmpty(this.version) || this.customVersion != null)
            return;

        // check if it exists
        Map<String, int[]> versionMap = this.mcpJson.get(this.version);
        if (versionMap == null)
            throw new GradleConfigurationException("There are no mappings for MC " + this.version);

        String channel = this.getMappingsChannelNoSubtype();
        int[] channelList = versionMap.get(channel);
        if (channelList == null)
            throw new GradleConfigurationException("There is no such MCP mapping channel named " + channel);

        // all is well with the world
        if (searchArray(channelList, this.mappingsVersion))
            return;

        // if it gets here.. it wasnt found. Now we try to actually find it..
        for (Entry<String, Map<String, int[]>> mcEntry : this.mcpJson.entrySet()) {
            for (Entry<String, int[]> channelEntry : mcEntry.getValue().entrySet()) {
                // found it!
                if (searchArray(channelEntry.getValue(), this.mappingsVersion)) {
                    boolean rightMc = mcEntry.getKey().equals(this.version);
                    boolean rightChannel = channelEntry.getKey().equals(channel);

                    // right channel, but wrong mc
                    if (rightChannel && !rightMc)
                        throw new GradleConfigurationException("This mapping '" + this.getMappings() + "' exists only for MC " + mcEntry.getKey() + "!");
                    else if (rightMc && !rightChannel)
                        throw new GradleConfigurationException("This mapping '" + this.getMappings() + "' doesnt exist! perhaps you meant '" + channelEntry.getKey() + "_" + this.mappingsVersion + "'");
                }
            }
        }

        // wasnt found
        throw new GradleConfigurationException("The specified mapping '" + this.getMappings() + "' does not exist!");
    }

    private static boolean searchArray(int[] array, int key) {
        Arrays.sort(array);
        int foundIndex = Arrays.binarySearch(array, key);
        return foundIndex >= 0 && array[foundIndex] == key;
    }
}
