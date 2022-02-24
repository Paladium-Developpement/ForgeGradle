package io.github.crucible.forgegradle.tasks.signum;

import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;

import org.objectweb.asm.tree.ClassNode;

class ClassCollection {
    private List<ClassNode> classes;
    private Manifest manifest;
    private Map<String, byte[]> extraFiles;

    public ClassCollection(List<ClassNode> classes, Manifest manifest, Map<String, byte[]> extraFiles) {
        this.classes = classes;
        this.manifest = manifest;
        this.extraFiles = extraFiles;
    }

    public List<ClassNode> getClasses() {
        return this.classes;
    }

    public Manifest getManifest() {
        return this.manifest;
    }

    public Map<String, byte[]> getExtraFiles() {
        return this.extraFiles;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || this.getClass() != o.getClass())
            return false;

        ClassCollection that = (ClassCollection) o;

        if (this.classes != null ? !this.classes.equals(that.classes) : that.classes != null)
            return false;
        if (this.extraFiles != null ? !this.extraFiles.equals(that.extraFiles) : that.extraFiles != null)
            return false;
        if (this.manifest != null ? !this.manifest.equals(that.manifest) : that.manifest != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = this.classes != null ? this.classes.hashCode() : 0;
        result = 31 * result + (this.manifest != null ? this.manifest.hashCode() : 0);
        result = 31 * result + (this.extraFiles != null ? this.extraFiles.hashCode() : 0);
        return result;
    }
}