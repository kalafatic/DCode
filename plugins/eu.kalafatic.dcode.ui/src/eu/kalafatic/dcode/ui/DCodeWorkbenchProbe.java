package eu.kalafatic.dcode.ui;

/** Test/runtime probe: set by the workbench window advisor during startup. */
public final class DCodeWorkbenchProbe {

    /** True once the DCode main window advisor ran. */
    public static volatile boolean windowAdvisorCalled;

    private DCodeWorkbenchProbe() {
    }
}
