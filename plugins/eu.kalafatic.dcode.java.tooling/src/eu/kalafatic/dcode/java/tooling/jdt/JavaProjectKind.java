package eu.kalafatic.dcode.java.tooling.jdt;

/** How a discovered Java project declares itself. */
public enum JavaProjectKind {
    /** Eclipse project (a {@code .project} file with the JDT nature or a .classpath). */
    ECLIPSE,

    /** Maven module (pom.xml). */
    MAVEN,

    /** OSGi bundle (MANIFEST.MF without a build descriptor). */
    OSGI
}
